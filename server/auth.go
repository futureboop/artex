package server

import (
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"log"
	"math/big"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
	"unicode/utf8"

	"github.com/golang-jwt/jwt/v5"
	"golang.org/x/crypto/bcrypt"
)

const (
	jwtKeyFilename = "jwt.key"
	authPassKey    = "auth.password_hash"
	jwtTTL         = 7 * 24 * time.Hour
	keyChars       = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

	// 下限与 setup 页的前端校验一致——校验只放在前端等于没放，直接打 API 就能
	// 绕过。上限是 bcrypt 的硬限制：超过 72 字节 GenerateFromPassword 会返回
	// ErrPasswordTooLong，提前挡掉好过让用户收到一句含义不明的「密码加密失败」。
	minPasswordRunes = 8
	maxPasswordBytes = 72
)

// errDataSourceUnavailable 是密码相关读操作失败时统一的回复。这些 handler 绝不能
// 把"读不到"当成"没有设置"：authInit 曾因此在数据库报错时放行，让未认证请求覆盖
// 掉已有的管理员密码。
const errDataSourceUnavailable = "数据源暂时不可用，请稍后重试"

// One administrator account exists. A process-wide budget bounds anonymous
// bcrypt work even when requests arrive through a reverse proxy, where all
// clients may share one RemoteAddr. The small burst lets a human retry a typo.
type loginRateLimiter struct {
	mu     sync.Mutex
	tokens float64
	last   time.Time
	active int
}

func (l *loginRateLimiter) allow(now time.Time) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	if l.last.IsZero() {
		l.tokens = 5
	} else if elapsed := now.Sub(l.last).Seconds(); elapsed > 0 {
		l.tokens = min(5, l.tokens+elapsed/10)
	}
	l.last = now
	if l.active >= 2 || l.tokens < 1 {
		return false
	}
	l.tokens--
	l.active++
	return true
}

func (l *loginRateLimiter) done() {
	l.mu.Lock()
	l.active--
	l.mu.Unlock()
}

func (s *Server) signToken() (string, error) {
	s.jwtMu.RLock()
	defer s.jwtMu.RUnlock()
	return signJWT(s.jwtKey)
}

func (s *Server) verifyToken(token string) bool {
	s.jwtMu.RLock()
	defer s.jwtMu.RUnlock()
	return verifyJWT(token, s.jwtKey)
}

// prepareJWTKey writes a new key beside the old one so the final rename is
// atomic on Linux. Callers remove the temp file if the password update fails.
func prepareJWTKey(path string) ([]byte, string, error) {
	buf := make([]byte, 32)
	if _, err := rand.Read(buf); err != nil {
		return nil, "", err
	}
	key := []byte(hex.EncodeToString(buf))
	if path == "" { // in-memory test servers
		return key, "", nil
	}
	f, err := os.CreateTemp(filepath.Dir(path), ".jwt-key-*")
	if err != nil {
		return nil, "", err
	}
	defer f.Close()
	if err := f.Chmod(0600); err != nil {
		os.Remove(f.Name())
		return nil, "", err
	}
	if _, err := f.Write(key); err != nil {
		os.Remove(f.Name())
		return nil, "", err
	}
	if err := f.Sync(); err != nil {
		os.Remove(f.Name())
		return nil, "", err
	}
	return key, f.Name(), nil
}

// validatePassword 返回空串表示通过，否则返回可直接展示给用户的中文原因。
func validatePassword(pw string) string {
	if utf8.RuneCountInString(pw) < minPasswordRunes {
		return fmt.Sprintf("密码长度至少 %d 位", minPasswordRunes)
	}
	if len(pw) > maxPasswordBytes {
		return fmt.Sprintf("密码长度不能超过 %d 字节", maxPasswordBytes)
	}
	return ""
}

// loadOrCreateJWTKey reads the 32-byte signing key from keyDir/jwt.key. keyDir is
// the project base dir (next to the executable), NOT the browsable workspace root
// (dataDir) — the signing key must never be listable/downloadable via the file
// manager. Legacy installs kept it at dataDir/jwt.key; if present there and not yet
// at the new location, it is migrated (key preserved, so sessions stay valid) and
// the old file removed so it disappears from the workspace. On first run a random
// key is generated and persisted.
func loadOrCreateJWTKey(keyDir, dataDir string) ([]byte, error) {
	path := filepath.Join(keyDir, jwtKeyFilename)
	// one-time migration out of the old in-workspace location.
	if legacy := filepath.Join(dataDir, jwtKeyFilename); legacy != path {
		if _, err := os.Stat(path); os.IsNotExist(err) {
			if data, rerr := os.ReadFile(legacy); rerr == nil {
				if werr := os.WriteFile(path, data, 0o600); werr == nil {
					_ = os.Remove(legacy)
					log.Printf("[auth] JWT key 已从 %s 迁移到 %s（移出可浏览工作区）", legacy, path)
				}
			}
		}
	}
	if data, err := os.ReadFile(path); err == nil && len(strings.TrimSpace(string(data))) >= 32 {
		return []byte(strings.TrimSpace(string(data))), nil
	}
	buf := make([]byte, 32)
	for i := range buf {
		n, err := rand.Int(rand.Reader, big.NewInt(int64(len(keyChars))))
		if err != nil {
			return nil, fmt.Errorf("generate jwt key: %w", err)
		}
		buf[i] = keyChars[n.Int64()]
	}
	if err := os.WriteFile(path, buf, 0600); err != nil {
		return nil, fmt.Errorf("write jwt key: %w", err)
	}
	log.Printf("[auth] 新 JWT key 已写入 %s", path)
	return buf, nil
}

// signJWT issues a 7-day HS256 token for user ARTEX.
func signJWT(key []byte) (string, error) {
	return jwt.NewWithClaims(jwt.SigningMethodHS256, jwt.RegisteredClaims{
		Subject:   "ARTEX",
		ExpiresAt: jwt.NewNumericDate(time.Now().Add(jwtTTL)),
		IssuedAt:  jwt.NewNumericDate(time.Now()),
	}).SignedString(key)
}

// verifyJWT returns true when tokenStr is a valid, non-expired HS256 token.
func verifyJWT(tokenStr string, key []byte) bool {
	if len(key) == 0 {
		return false
	}
	t, err := jwt.Parse(tokenStr, func(t *jwt.Token) (any, error) {
		return key, nil
	}, jwt.WithValidMethods([]string{"HS256"}), jwt.WithExpirationRequired(), jwt.WithSubject("ARTEX"))
	return err == nil && t.Valid
}

// extractToken reads the JWT from Authorization: Bearer header,
// artex_token cookie, or ?token= query param (for SSE connections).
func extractToken(r *http.Request) string {
	if h := r.Header.Get("Authorization"); strings.HasPrefix(h, "Bearer ") {
		return strings.TrimPrefix(h, "Bearer ")
	}
	if c, err := r.Cookie("artex_token"); err == nil && c.Value != "" {
		return c.Value
	}
	return r.URL.Query().Get("token")
}

// Browsers send Origin (and usually Sec-Fetch-Site) on writes. A cookie may
// authenticate a write from this UI, but another origin must not be able to
// borrow that cookie to run a command or change server state. Compare Host,
// including its port; TLS may terminate before the request reaches this server.
func cookieMutationOriginAllowed(r *http.Request) bool {
	switch r.Header.Get("Sec-Fetch-Site") {
	case "", "same-origin", "none":
	default:
		return false
	}
	sameHost := func(raw string, origin bool) bool {
		u, err := url.Parse(raw)
		if err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" || u.User != nil || !strings.EqualFold(u.Host, r.Host) {
			return false
		}
		return !origin || (u.Path == "" && u.RawQuery == "" && u.Fragment == "")
	}
	if origins, present := r.Header["Origin"]; present {
		return len(origins) == 1 && sameHost(origins[0], true)
	}
	if referer := r.Referer(); referer != "" {
		return sameHost(referer, false)
	}
	return true
}

// requireAuth wraps h with JWT validation.
// Only the exact public login, initialization, status and health routes are exempt.
func (s *Server) requireAuth(h http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		p := r.URL.EscapedPath()
		publicGet := (r.Method == http.MethodGet || r.Method == http.MethodHead) &&
			(p == "/api/auth/status" || p == "/api/health")
		publicPost := r.Method == http.MethodPost && (p == "/api/auth/init" || p == "/api/auth/login")
		if publicGet || publicPost {
			h.ServeHTTP(w, r)
			return
		}
		var tok string
		if r.Method == http.MethodGet || r.Method == http.MethodHead {
			tok = extractToken(r)
		} else if h := r.Header.Get("Authorization"); strings.HasPrefix(h, "Bearer ") {
			tok = strings.TrimPrefix(h, "Bearer ")
		} else if c, err := r.Cookie("artex_token"); err == nil && c.Value != "" {
			if !cookieMutationOriginAllowed(r) {
				writeErr(w, http.StatusForbidden, "跨来源 Cookie 请求被拒绝")
				return
			}
			tok = c.Value
		}
		if tok == "" {
			writeErr(w, 401, "未授权")
			return
		}
		if !s.verifyToken(tok) {
			writeErr(w, 401, "token 无效或已过期")
			return
		}
		h.ServeHTTP(w, r)
	})
}

// GET /api/auth/status — reports whether the admin password has been initialised.
// 读失败必须回 503 而不是 initialized:false：前端在 initialized:false 时会把用户
// 送到 /setup 去设置密码（login/page.tsx），把数据库故障包装成 200 等于把用户往
// 覆盖已有密码的路上推。
func (s *Server) authStatus(w http.ResponseWriter, r *http.Request) {
	pg := s.pg(w)
	if pg == nil {
		return
	}
	hash, _, err := pg.GetSetting(authPassKey)
	if err != nil {
		writeErr(w, 503, errDataSourceUnavailable)
		return
	}
	writeJSON(w, 200, map[string]any{"initialized": hash != ""})
}

// POST /api/auth/init — sets the password for the first time; rejected if already set.
func (s *Server) authInit(w http.ResponseWriter, r *http.Request) {
	pg := s.pg(w)
	if pg == nil {
		return
	}
	existing, _, err := pg.GetSetting(authPassKey)
	if err != nil {
		writeErr(w, 503, errDataSourceUnavailable)
		return
	}
	if existing != "" {
		writeErr(w, 403, "密码已设置")
		return
	}
	var req struct {
		Password string `json:"password"`
	}
	if err := decode(r, &req); err != nil || req.Password == "" {
		writeErr(w, 400, "密码不能为空")
		return
	}
	if msg := validatePassword(req.Password); msg != "" {
		writeErr(w, 400, msg)
		return
	}
	hash, err := bcrypt.GenerateFromPassword([]byte(req.Password), bcrypt.DefaultCost)
	if err != nil {
		writeErr(w, 500, "密码加密失败")
		return
	}
	// 用 INSERT ... ON CONFLICT DO NOTHING 而不是 upsert：上面那次 GetSetting 只是
	// 快速失败路径，真正"仅首次可设"的保证落在主键约束上。bcrypt 要跑几十毫秒，
	// 这期间别的请求完全可能先把密码设好，而读检查本身也可能因故障而失效。
	inserted, err := pg.InsertSettingIfAbsent(authPassKey, string(hash))
	if err != nil {
		writeErr(w, 500, "保存失败: "+err.Error())
		return
	}
	if !inserted {
		writeErr(w, 403, "密码已设置")
		return
	}
	tok, err := s.signToken()
	if err != nil {
		writeErr(w, 500, "token 生成失败")
		return
	}
	writeJSON(w, 200, map[string]any{"token": tok})
}

// POST /api/auth/change-password — changes the admin password. Requires a valid
// token AND the current password. The handler also validates the token itself
// so direct invocations are protected.
func (s *Server) authChangePassword(w http.ResponseWriter, r *http.Request) {
	pg := s.pg(w)
	if pg == nil {
		return
	}
	currentToken := extractToken(r)
	if !s.verifyToken(currentToken) {
		writeErr(w, 401, "未授权")
		return
	}
	var req struct {
		OldPassword string `json:"old_password"`
		NewPassword string `json:"new_password"`
	}
	if err := decode(r, &req); err != nil {
		writeErr(w, 400, "请求格式错误")
		return
	}
	if req.NewPassword == "" {
		writeErr(w, 400, "新密码不能为空")
		return
	}
	if msg := validatePassword(req.NewPassword); msg != "" {
		writeErr(w, 400, msg)
		return
	}
	s.jwtMu.Lock()
	defer s.jwtMu.Unlock()
	// A second password change may have completed while this request was parsed.
	if !verifyJWT(currentToken, s.jwtKey) {
		writeErr(w, 401, "token 无效或已过期")
		return
	}
	hash, ok, err := pg.GetSetting(authPassKey)
	if err != nil {
		writeErr(w, 503, errDataSourceUnavailable)
		return
	}
	if !ok || hash == "" {
		writeErr(w, 403, "密码未初始化，请先设置密码")
		return
	}
	if err := bcrypt.CompareHashAndPassword([]byte(hash), []byte(req.OldPassword)); err != nil {
		writeErr(w, 401, "当前密码错误")
		return
	}
	newHash, err := bcrypt.GenerateFromPassword([]byte(req.NewPassword), bcrypt.DefaultCost)
	if err != nil {
		writeErr(w, 500, "密码加密失败")
		return
	}
	newKey, tempPath, err := prepareJWTKey(s.jwtKeyPath)
	if err != nil {
		writeErr(w, 500, "准备会话更新失败")
		return
	}
	if tempPath != "" {
		defer os.Remove(tempPath)
	}
	if err := pg.SetSetting(authPassKey, string(newHash)); err != nil {
		writeErr(w, 500, "保存失败: "+err.Error())
		return
	}
	if tempPath != "" {
		if err := os.Rename(tempPath, s.jwtKeyPath); err != nil {
			if rollbackErr := pg.SetSetting(authPassKey, hash); rollbackErr != nil {
				log.Printf("[auth] 密码更新后会话密钥轮换失败，且密码回滚失败: %v", rollbackErr)
			}
			writeErr(w, 500, "更新会话失败")
			return
		}
	}
	s.jwtKey = newKey
	tok, err := signJWT(newKey)
	if err != nil {
		writeErr(w, 500, "token 生成失败")
		return
	}
	writeJSON(w, 200, map[string]any{"ok": true, "token": tok})
}

// POST /api/auth/login — validates username/password and returns a JWT.
func (s *Server) authLogin(w http.ResponseWriter, r *http.Request) {
	pg := s.pg(w)
	if pg == nil {
		return
	}
	var req struct {
		Username string `json:"username"`
		Password string `json:"password"`
	}
	if err := decode(r, &req); err != nil {
		writeErr(w, 400, "请求格式错误")
		return
	}
	if req.Username != "ARTEX" {
		writeErr(w, 401, "用户名或密码错误")
		return
	}
	if !s.loginLimiter.allow(time.Now()) {
		w.Header().Set("Retry-After", "10")
		writeErr(w, http.StatusTooManyRequests, "登录尝试过于频繁，请稍后重试")
		return
	}
	defer s.loginLimiter.done()
	// Keep password verification and token issuance on the same key generation.
	// Otherwise a login that checked the old password could receive a fresh token
	// after a concurrent password change rotated the key.
	s.jwtMu.RLock()
	defer s.jwtMu.RUnlock()
	hash, ok, err := pg.GetSetting(authPassKey)
	if err != nil {
		writeErr(w, 503, errDataSourceUnavailable)
		return
	}
	if !ok || hash == "" {
		writeErr(w, 403, "密码未初始化，请先设置密码")
		return
	}
	if err := bcrypt.CompareHashAndPassword([]byte(hash), []byte(req.Password)); err != nil {
		writeErr(w, 401, "用户名或密码错误")
		return
	}
	tok, err := signJWT(s.jwtKey)
	if err != nil {
		writeErr(w, 500, "token 生成失败")
		return
	}
	writeJSON(w, 200, map[string]any{"token": tok})
}
