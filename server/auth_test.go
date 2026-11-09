package server

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/golang-jwt/jwt/v5"
)

func TestAuthenticationBoundary(t *testing.T) {
	key := []byte("test-only-key-for-auth-boundary")
	s := &Server{jwtKey: key}
	token, err := signJWT(key)
	if err != nil {
		t.Fatal(err)
	}
	handler := s.requireAuth(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusNoContent)
	}))
	for _, tc := range []struct {
		method, path, token string
		want                int
	}{
		{"GET", "/api/health", "", 204},
		{"GET", "/api/auth/status", "", 204},
		{"POST", "/api/auth/login", "", 204},
		{"POST", "/api/auth/init", "", 204},
		{"GET", "/api/tasks", "", 401},
		{"GET", "/api/llm/profiles", "", 401},
		{"GET", "/api/settings", "", 401},
		{"GET", "/api/auth/future-sensitive-endpoint", "", 401},
		{"GET", "/api/auth/status/extra", "", 401},
		{"GET", "/api/auth/%73tatus", "", 401},
		{"GET", "/api/auth/../tasks", "", 401},
		{"POST", "/api/auth/status", "", 401},
		{"POST", "/api/auth/change-password", "", 401},
		{"GET", "/api/tasks", "invalid", 401},
		{"GET", "/api/tasks", token, 204},
	} {
		t.Run(tc.method+" "+tc.path, func(t *testing.T) {
			r := httptest.NewRequest(tc.method, tc.path, nil)
			if tc.token != "" {
				r.Header.Set("Authorization", "Bearer "+tc.token)
			}
			w := httptest.NewRecorder()
			handler.ServeHTTP(w, r)
			if w.Code != tc.want {
				t.Fatalf("status=%d, want %d", w.Code, tc.want)
			}
		})
	}
	for _, source := range []string{"cookie", "query"} {
		t.Run(source, func(t *testing.T) {
			path := "/api/tasks"
			if source == "query" {
				path += "?token=" + token
			}
			r := httptest.NewRequest(http.MethodGet, path, nil)
			if source == "cookie" {
				r.AddCookie(&http.Cookie{Name: "artex_token", Value: token})
			}
			w := httptest.NewRecorder()
			handler.ServeHTTP(w, r)
			if w.Code != http.StatusNoContent {
				t.Fatalf("status=%d, want %d", w.Code, http.StatusNoContent)
			}
		})
	}
	for _, tc := range []struct {
		name, origin, referer, fetchSite string
		cookie, query                    bool
		want                             int
	}{
		{"same-origin cookie", "http://example.com", "", "same-origin", true, false, 204},
		{"cookie without browser headers", "", "", "", true, false, 204},
		{"same-origin referer", "", "http://example.com/tools", "", true, false, 204},
		{"different port", "http://example.com:8788", "", "", true, false, 403},
		{"different host", "https://untrusted.example.test", "", "cross-site", true, false, 403},
		{"null origin", "null", "", "", true, false, 403},
		{"different referer", "", "http://untrusted.example.test/tools", "", true, false, 403},
		{"same-site fetch metadata", "", "", "same-site", true, false, 403},
		{"query only", "", "", "", false, true, 401},
	} {
		t.Run(tc.name, func(t *testing.T) {
			path := "/api/tools/custom/test"
			if tc.query {
				path += "?token=" + token
			}
			r := httptest.NewRequest(http.MethodPost, path, strings.NewReader(`{"kind":"script"}`))
			if tc.origin != "" {
				r.Header.Set("Origin", tc.origin)
			}
			if tc.referer != "" {
				r.Header.Set("Referer", tc.referer)
			}
			if tc.fetchSite != "" {
				r.Header.Set("Sec-Fetch-Site", tc.fetchSite)
			}
			if tc.cookie {
				r.AddCookie(&http.Cookie{Name: "artex_token", Value: token})
			}
			w := httptest.NewRecorder()
			handler.ServeHTTP(w, r)
			if w.Code != tc.want {
				t.Fatalf("cookie mutation status=%d, want %d", w.Code, tc.want)
			}
		})
	}
	t.Run("mutation_with_bearer", func(t *testing.T) {
		r := httptest.NewRequest(http.MethodPost, "/api/tools/custom/test", nil)
		r.Header.Set("Authorization", "Bearer "+token)
		w := httptest.NewRecorder()
		handler.ServeHTTP(w, r)
		if w.Code != http.StatusNoContent {
			t.Fatalf("bearer mutation status=%d, want 204", w.Code)
		}
	})
}

func TestHandlerRejectsAnonymousSensitiveRoutes(t *testing.T) {
	s := &Server{jwtKey: []byte("test-only-key-for-auth-boundary")}
	h := s.Handler()
	for _, path := range []string{
		"/api/tasks", "/api/tasks/1", "/api/settings", "/api/llm/profiles",
		"/api/llm/records", "/api/workspace/read?path=jwt.key",
		"/api/auth%2ffuture-sensitive-endpoint", "/api/auth/%2e%2e/tasks",
	} {
		t.Run(path, func(t *testing.T) {
			w := httptest.NewRecorder()
			h.ServeHTTP(w, httptest.NewRequest(http.MethodGet, path, nil))
			if w.Code != http.StatusUnauthorized && w.Code != http.StatusMovedPermanently {
				t.Fatalf("anonymous request status=%d, body=%s", w.Code, w.Body.String())
			}
		})
	}
}

func TestVerifyJWTRequiresExpectedClaimsAndAlgorithm(t *testing.T) {
	key := []byte("test-only-key-for-auth-boundary")
	for _, tc := range []struct {
		name   string
		method jwt.SigningMethod
		claims jwt.MapClaims
		want   bool
	}{
		{"valid", jwt.SigningMethodHS256, jwt.MapClaims{"sub": "ARTEX", "exp": time.Now().Add(time.Hour).Unix()}, true},
		{"no expiry", jwt.SigningMethodHS256, jwt.MapClaims{"sub": "ARTEX"}, false},
		{"expired", jwt.SigningMethodHS256, jwt.MapClaims{"sub": "ARTEX", "exp": time.Now().Add(-time.Hour).Unix()}, false},
		{"wrong subject", jwt.SigningMethodHS256, jwt.MapClaims{"sub": "other", "exp": time.Now().Add(time.Hour).Unix()}, false},
		{"wrong algorithm", jwt.SigningMethodHS384, jwt.MapClaims{"sub": "ARTEX", "exp": time.Now().Add(time.Hour).Unix()}, false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			token, err := jwt.NewWithClaims(tc.method, tc.claims).SignedString(key)
			if err != nil {
				t.Fatal(err)
			}
			if got := verifyJWT(token, key); got != tc.want {
				t.Fatalf("verifyJWT() = %v, want %v", got, tc.want)
			}
		})
	}
}

func TestLoginRateLimiterBoundsBcryptWork(t *testing.T) {
	var limiter loginRateLimiter
	now := time.Unix(1_000, 0)
	if !limiter.allow(now) || !limiter.allow(now) || limiter.allow(now) {
		t.Fatal("only two bcrypt checks may run concurrently")
	}
	limiter.done()
	limiter.done()
	for i := 0; i < 3; i++ {
		if !limiter.allow(now) {
			t.Fatalf("burst attempt %d was rejected", i)
		}
		limiter.done()
	}
	if limiter.allow(now) {
		t.Fatal("the sixth login attempt should be rate limited")
	}
	if !limiter.allow(now.Add(10 * time.Second)) {
		t.Fatal("one login attempt should be restored after ten seconds")
	}
	limiter.done()
}

func TestPreparedJWTKeyRevokesOldToken(t *testing.T) {
	oldKey := []byte("old-test-key-with-at-least-32-characters")
	oldToken, err := signJWT(oldKey)
	if err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(t.TempDir(), jwtKeyFilename)
	key, tempPath, err := prepareJWTKey(path)
	if err != nil {
		t.Fatal(err)
	}
	defer os.Remove(tempPath)
	if len(key) < 32 || tempPath == "" || verifyJWT(oldToken, key) {
		t.Fatal("a replacement key must invalidate tokens signed by the old key")
	}
	newToken, err := signJWT(key)
	if err != nil || !verifyJWT(newToken, key) {
		t.Fatalf("replacement key did not sign a valid token: %v", err)
	}
}

// GetSetting 对"键不存在"和"读取出错"的返回值只差一个 error：两种情况 value 都是
// 空串。密码相关的 handler 一旦把 error 当成"还没设置密码"，就会在数据库抖动期间
// 敞开初始化入口——authInit 会放行一个未认证请求去覆盖已有的管理员密码，
// authStatus 则会把前端直接送到 /setup 去照着做这件事。
//
// 这两个测试把 handler 的连接池关掉来制造读取失败，断言两处都 fail closed。
func TestAuthStatusFailsClosedWhenDataSourceUnavailable(t *testing.T) {
	m, err := NewManager(t.TempDir(), "")
	if err != nil {
		t.Skipf("postgres unavailable (%v)", err)
	}
	defer m.Close()
	// 关掉池子，让后续 GetSetting 返回 error 而不是 sql.ErrNoRows。
	if err := m.pg.Close(); err != nil {
		t.Fatal(err)
	}

	s := &Server{m: m}
	w := httptest.NewRecorder()
	s.authStatus(w, httptest.NewRequest("GET", "/api/auth/status", nil))

	if w.Code != 503 {
		t.Fatalf("status=%d want 503 (读失败被当成未初始化会把用户送去 /setup 覆盖密码); body=%s", w.Code, w.Body.String())
	}
	var payload struct {
		Initialized *bool `json:"initialized"`
	}
	if err := json.Unmarshal(w.Body.Bytes(), &payload); err == nil && payload.Initialized != nil {
		t.Fatalf("读失败时不应回答 initialized，得到 %v", *payload.Initialized)
	}
}

func TestAuthInitFailsClosedWhenDataSourceUnavailable(t *testing.T) {
	m, err := NewManager(t.TempDir(), "")
	if err != nil {
		t.Skipf("postgres unavailable (%v)", err)
	}
	defer m.Close()
	if err := m.pg.Close(); err != nil {
		t.Fatal(err)
	}

	s := &Server{m: m}
	w := httptest.NewRecorder()
	body := strings.NewReader(`{"password":"correct horse battery"}`)
	s.authInit(w, httptest.NewRequest("POST", "/api/auth/init", body))

	if w.Code != 503 {
		t.Fatalf("status=%d want 503 (读失败时放行会让未认证请求覆盖已有密码); body=%s", w.Code, w.Body.String())
	}
	if strings.Contains(w.Body.String(), "token") {
		t.Fatalf("读失败时不应签发 token: %s", w.Body.String())
	}
}

func TestValidatePassword(t *testing.T) {
	for _, tc := range []struct {
		name, pw string
		wantErr  bool
	}{
		{"空", "", true},
		{"七位", "1234567", true},
		{"八位", "12345678", false},
		{"八个汉字按字符数而非字节数计", "密码密码密码密码", false},
		{"三个汉字够 9 字节但只有 3 个字符", "密码强", true},
		{"72 字节", strings.Repeat("a", 72), false},
		{"73 字节超出 bcrypt 上限", strings.Repeat("a", 73), true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			if got := validatePassword(tc.pw); (got != "") != tc.wantErr {
				t.Fatalf("validatePassword(%q)=%q, wantErr=%v", tc.pw, got, tc.wantErr)
			}
		})
	}
}
