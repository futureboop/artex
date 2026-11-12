package notify

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"
)

// OneBot 11's HTTP API accepts plain text messages at /send_group_msg and
// /send_private_msg. auto_escape prevents findings copied from an untrusted
// target from being interpreted as CQ codes by the bot implementation.
type oneBot11Channel struct{}

const oneBot11TextLimit = 1800 // conservative QQ text size; split digest batches instead of dropping items

var oneBot11HTTPClient = &http.Client{
	Timeout:   15 * time.Second,
	Transport: notifyTransport,
	CheckRedirect: func(_ *http.Request, _ []*http.Request) error {
		return http.ErrUseLastResponse
	},
}

func (oneBot11Channel) Kind() string { return KindOneBot11 }

func (oneBot11Channel) DefaultRatePerMin() int { return 20 }

func (oneBot11Channel) SecretKeys() []string { return []string{"access_token"} }

func (oneBot11Channel) DestinationKeys() []string {
	return []string{"base_url", "target_type", "target_id"}
}

func (oneBot11Channel) Validate(cfg map[string]any) error {
	base := cfgString(cfg, "base_url")
	if base == "" {
		return errors.New("缺少 OneBot 11 HTTP API 地址")
	}
	if err := validateHTTPURL(base); err != nil {
		return fmt.Errorf("OneBot 11 HTTP API 地址无效: %w", err)
	}
	u, err := url.Parse(base)
	if err != nil { // validateHTTPURL already parsed, keep this branch defensive.
		return errors.New("OneBot 11 HTTP API 地址无法解析")
	}
	if u.User != nil || u.RawQuery != "" || u.ForceQuery || strings.Contains(base, "#") || u.Opaque != "" {
		return errors.New("OneBot 11 HTTP API 地址不能包含账号密码、查询参数或片段")
	}
	switch cfgString(cfg, "target_type") {
	case "group", "private":
	default:
		return errors.New("OneBot 11 目标类型只能是 group（群聊）或 private（个人 QQ）")
	}
	if _, err := oneBot11TargetID(cfgString(cfg, "target_id")); err != nil {
		return err
	}
	if IsMasked(cfgString(cfg, "access_token")) {
		return errors.New("OneBot 11 Access Token 不能是掩码占位符")
	}
	if token, ok := cfg["access_token"].(string); ok && strings.ContainsAny(token, "\r\n") {
		return errors.New("OneBot 11 Access Token 不能包含换行")
	}
	return nil
}

func oneBot11TargetID(raw string) (int64, error) {
	if raw == "" || raw[0] == '0' {
		return 0, errors.New("请填写正整数 QQ 号或群号")
	}
	for _, ch := range raw {
		if ch < '0' || ch > '9' {
			return 0, errors.New("QQ 号或群号只能包含数字")
		}
	}
	id, err := strconv.ParseInt(raw, 10, 64)
	if err != nil || id <= 0 {
		return 0, errors.New("QQ 号或群号超出有效范围")
	}
	return id, nil
}

func (c oneBot11Channel) Send(ctx context.Context, cfg map[string]any, m Message) (int, error) {
	if err := c.Validate(cfg); err != nil {
		return 0, Permanent(err)
	}
	if len(m.Items) == 0 {
		return 0, Permanent(errors.New("OneBot 11 消息内容为空"))
	}
	id, _ := oneBot11TargetID(cfgString(cfg, "target_id"))
	action, targetKey := "send_group_msg", "group_id"
	if cfgString(cfg, "target_type") == "private" {
		action, targetKey = "send_private_msg", "user_id"
	}
	endpoint := strings.TrimRight(cfgString(cfg, "base_url"), "/") + "/" + action
	message, kept := oneBot11Text(m)
	payload := map[string]any{targetKey: id, "message": message, "auto_escape": true}
	headers := map[string]string{}
	if token := cfgString(cfg, "access_token"); token != "" {
		headers["Authorization"] = "Bearer " + token
	}
	raw, err := doJSONWithClient(ctx, oneBot11HTTPClient, http.MethodPost, endpoint, headers, payload)
	if err != nil {
		return 0, err
	}
	var res struct {
		Status  string `json:"status"`
		RetCode *int   `json:"retcode"`
		Msg     string `json:"msg"`
		Wording string `json:"wording"`
	}
	if err := json.Unmarshal(raw, &res); err != nil || res.RetCode == nil {
		return 0, fmt.Errorf("解析 OneBot 11 响应失败")
	}
	if (res.Status == "ok" && *res.RetCode == 0) || (res.Status == "async" && *res.RetCode == 1) {
		return kept, nil
	}
	wording := OneLine(res.Wording, 160)
	if wording == "" {
		wording = OneLine(res.Msg, 160)
	}
	return 0, Permanent(fmt.Errorf("OneBot 11 返回错误 %d (%s): %s", *res.RetCode, res.Status, wording))
}

func oneBot11Text(m Message) (string, int) {
	if !m.Batch {
		it := m.Items[0]
		var b strings.Builder
		fmt.Fprintf(&b, "ARTEX 漏洞通知\n%s · %s", SeverityLabel(it.Severity), OneLine(it.Title(), 180))
		if it.IsStatusChange() {
			fmt.Fprintf(&b, "\n状态：%s → %s", StatusLabel(it.FromStatus), StatusLabel(it.ToStatus))
		}
		if it.VulnClass != "" && it.VulnClass != it.Title() {
			fmt.Fprintf(&b, "\n类型：%s", OneLine(it.VulnClass, 100))
		}
		if assets := assetLine(it.Assets, maxAssetsShown); assets != "" {
			fmt.Fprintf(&b, "\n资产：%s", OneLine(assets, 150))
		}
		if summary := OneLine(it.Summary, maxSummaryRunes); summary != "" {
			fmt.Fprintf(&b, "\n摘要：%s", summary)
		}
		if link := OneLine(it.DetailURL, 512); link != "" {
			fmt.Fprintf(&b, "\n详情：%s", link)
		}
		return TruncateRunes(b.String(), oneBot11TextLimit), 1
	}
	// Keep complete findings together. The notifier marks only the first kept
	// events delivered; the remaining events are sent in subsequent batches.
	footer := ""
	if link := OneLine(m.HomeURL, 512); link != "" {
		footer = "\n查看全部：" + link
	}
	lines := make([]string, 0, len(m.Items))
	used := 0
	for i, it := range m.Items {
		line := oneBot11DigestLine(it, i+1)
		if runeSize(oneBot11DigestHeader(m, i+1))+used+runeSize(line)+runeSize(footer) > oneBot11TextLimit && i > 0 {
			break
		}
		lines = append(lines, line)
		used += runeSize(line)
	}
	return TruncateRunes(oneBot11DigestHeader(m, len(lines))+strings.Join(lines, "")+footer, oneBot11TextLimit), len(lines)
}

func oneBot11DigestHeader(m Message, kept int) string {
	var b strings.Builder
	if m.WindowMinutes > 0 {
		fmt.Fprintf(&b, "ARTEX · 近 %d 分钟新增 %d 个漏洞", m.WindowMinutes, len(m.Items))
	} else {
		fmt.Fprintf(&b, "ARTEX · 新增 %d 个漏洞", len(m.Items))
	}
	if left := len(m.Items) - kept; left > 0 {
		fmt.Fprintf(&b, "（本条 %d 个，余下 %d 个继续推送）", kept, left)
	}
	return b.String()
}

func oneBot11DigestLine(it Item, index int) string {
	line := fmt.Sprintf("\n%d. %s · %s", index, SeverityLabel(it.Severity), OneLine(it.Title(), 150))
	if assets := assetLine(it.Assets, maxAssetsShown); assets != "" {
		line += " · " + OneLine(assets, 100)
	}
	return line
}
