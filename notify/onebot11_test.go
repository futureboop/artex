package notify

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"unicode/utf8"
)

func TestOneBot11GroupAndPrivateTargets(t *testing.T) {
	for _, tc := range []struct {
		name       string
		targetType string
		path       string
		key        string
		token      string
	}{
		{"group", "group", "/onebot/v11/send_group_msg", "group_id", "secret-for-test"},
		{"private", "private", "/onebot/v11/send_private_msg", "user_id", ""},
	} {
		t.Run(tc.name, func(t *testing.T) {
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if r.Method != http.MethodPost || r.URL.Path != tc.path {
					t.Errorf("请求路由错误: %s %s", r.Method, r.URL.Path)
				}
				wantAuth := ""
				if tc.token != "" {
					wantAuth = "Bearer " + tc.token
				}
				if got := r.Header.Get("Authorization"); got != wantAuth {
					t.Errorf("Bearer Token 错误: %q", got)
				}
				var body map[string]any
				if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
					t.Errorf("解码请求失败: %v", err)
				}
				if body[tc.key] != float64(123456) {
					t.Errorf("QQ 目标未作为 JSON 数字发送: %#v", body[tc.key])
				}
				other := "user_id"
				if tc.key == "user_id" {
					other = "group_id"
				}
				if _, present := body[other]; present {
					t.Errorf("错误的目标字段被同时发送: %s", other)
				}
				if body["auto_escape"] != true {
					t.Errorf("必须启用 auto_escape，避免 CQ 码执行: %#v", body)
				}
				if text, _ := body["message"].(string); !strings.Contains(text, "[CQ:at,qq=all]") {
					t.Errorf("消息正文未保留纯文本 CQ 字符: %q", text)
				}
				_, _ = io.WriteString(w, `{"status":"ok","retcode":0,"data":{"message_id":42}}`)
			}))
			defer srv.Close()
			cfg := map[string]any{
				"base_url": srv.URL + "/onebot/v11", "target_type": tc.targetType,
				"target_id": "123456", "access_token": tc.token,
			}
			m := Message{Items: []Item{{Severity: "high", Name: "报告 [CQ:at,qq=all] 发现"}}}
			kept, err := (oneBot11Channel{}).Send(context.Background(), cfg, m)
			if err != nil || kept != 1 {
				t.Fatalf("投递失败: kept=%d err=%v", kept, err)
			}
		})
	}
}

func TestOneBot11BusinessFailureAndAsyncReceipt(t *testing.T) {
	failed := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		_, _ = io.WriteString(w, `{"status":"failed","retcode":100,"wording":"group not found"}`)
	}))
	defer failed.Close()
	cfg := map[string]any{"base_url": failed.URL, "target_type": "group", "target_id": "123456"}
	_, err := (oneBot11Channel{}).Send(context.Background(), cfg, singleMsg())
	if err == nil || !IsPermanent(err) || !strings.Contains(err.Error(), "100") {
		t.Fatalf("HTTP 200 的 OneBot 业务错误应是永久失败: %v", err)
	}

	accepted := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		_, _ = io.WriteString(w, `{"status":"async","retcode":1}`)
	}))
	defer accepted.Close()
	cfg["base_url"] = accepted.URL
	if kept, err := (oneBot11Channel{}).Send(context.Background(), cfg, singleMsg()); err != nil || kept != 1 {
		t.Fatalf("async+1 应视为已接收: kept=%d err=%v", kept, err)
	}
}

func TestOneBot11NeverFollowsRedirectWithBearer(t *testing.T) {
	var sinkHit bool
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/sink" {
			sinkHit = true
			return
		}
		http.Redirect(w, r, "/sink", http.StatusTemporaryRedirect)
	}))
	defer srv.Close()
	cfg := map[string]any{"base_url": srv.URL, "target_type": "group", "target_id": "123456", "access_token": "secret"}
	_, err := (oneBot11Channel{}).Send(context.Background(), cfg, singleMsg())
	if err == nil || !IsPermanent(err) || sinkHit {
		t.Fatalf("带 Bearer 的 OneBot 请求不得跟随重定向: err=%v sinkHit=%v", err, sinkHit)
	}
}

func TestOneBot11ValidationAndCredentialChange(t *testing.T) {
	valid := map[string]any{"base_url": "https://example.com/onebot/v11", "target_type": "group", "target_id": "123456", "access_token": "secret"}
	if err := (oneBot11Channel{}).Validate(valid); err != nil {
		t.Fatalf("有效 OneBot 配置被拒绝: %v", err)
	}
	for _, bad := range []map[string]any{
		{"base_url": "file:///etc/passwd", "target_type": "group", "target_id": "123456"},
		{"base_url": "https://u:p@example.com", "target_type": "group", "target_id": "123456"},
		{"base_url": "https://example.com/?key=value", "target_type": "group", "target_id": "123456"},
		{"base_url": "https://example.com/#fragment", "target_type": "group", "target_id": "123456"},
		{"base_url": "https://example.com", "target_type": "friend", "target_id": "123456"},
		{"base_url": "https://example.com", "target_type": "group", "target_id": "123.0"},
		{"base_url": "https://example.com", "target_type": "group", "target_id": "0"},
		{"base_url": "https://example.com", "target_type": "group", "target_id": "123456", "access_token": "tok\n"},
	} {
		if err := (oneBot11Channel{}).Validate(bad); err == nil {
			t.Errorf("非法配置应被拒: %#v", bad)
		}
	}
	masked := MaskConfig(KindOneBot11, valid)
	if !IsMasked(masked["access_token"].(string)) {
		t.Fatal("API 回显必须遮蔽 Access Token")
	}
	if _, err := PrepareConfigUpdate(KindOneBot11, valid, map[string]any{"base_url": "https://other.example.com"}); err == nil {
		t.Fatal("更换目标地址时不可静默复用旧 Token")
	}
	if _, err := PrepareConfigUpdate(KindOneBot11, valid, map[string]any{
		"target_id": "789", "access_token": masked["access_token"],
	}); err == nil {
		t.Fatal("更换 QQ 目标时不可通过掩码沿用旧 Token")
	}
	if merged, err := PrepareConfigUpdate(KindOneBot11, valid, map[string]any{
		"target_id": "789", "access_token": "",
	}); err != nil || merged["access_token"] != nil {
		t.Fatalf("显式清空可选 Token 应可更换目标: config=%#v err=%v", merged, err)
	}
}

func TestOneBot11DigestKeepsWholeItems(t *testing.T) {
	m := batchMsg(80)
	for i := range m.Items {
		m.Items[i].Name = strings.Repeat("长标题", 65)
	}
	text, kept := oneBot11Text(m)
	if kept <= 0 || kept >= len(m.Items) {
		t.Fatalf("应按条分批而非丢弃全部: kept=%d", kept)
	}
	if utf8.RuneCountInString(text) > oneBot11TextLimit {
		t.Fatalf("消息超长: %d", utf8.RuneCountInString(text))
	}
	if !strings.Contains(text, "余下") {
		t.Fatal("分批消息应提示还有未发送条目")
	}
}

func TestOneBot11LinksCannotInjectExtraLines(t *testing.T) {
	m := singleMsg()
	m.Items[0].DetailURL = "https://artex.example/item\n伪造下一条"
	text, _ := oneBot11Text(m)
	if strings.Contains(text, "\n伪造下一条") {
		t.Fatalf("详情链接包含的换行应被压平: %q", text)
	}
	m.Batch = true
	m.HomeURL = "https://artex.example/items\n伪造下一条"
	text, _ = oneBot11Text(m)
	if strings.Contains(text, "\n伪造下一条") {
		t.Fatalf("汇总链接包含的换行应被压平: %q", text)
	}
}

func TestOneBot11LoopbackUsesExistingSSRFGuard(t *testing.T) {
	var hit bool
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		hit = true
		_, _ = io.WriteString(w, `{"status":"ok","retcode":0}`)
	}))
	defer srv.Close()
	t.Setenv(AllowLocalTargetsEnv, "")
	cfg := map[string]any{"base_url": srv.URL, "target_type": "group", "target_id": "123456"}
	_, err := (oneBot11Channel{}).Send(context.Background(), cfg, singleMsg())
	if err == nil || hit || !strings.Contains(err.Error(), AllowLocalTargetsEnv) {
		t.Fatalf("默认应拒绝本机 OneBot，提示显式开关: hit=%v err=%v", hit, err)
	}
}
