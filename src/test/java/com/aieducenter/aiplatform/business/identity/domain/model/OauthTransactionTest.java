package com.aieducenter.aiplatform.business.identity.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OAuth 往返事务值对象（A2 §2 表 1/2 行 + #32 returnTo 增量）：
 * cookie 编解码 round-trip、state/nonce 随机性、returnTo 开放重定向防线。
 */
class OauthTransactionTest {

    @Test
    void given_issue_when_roundtrip_cookie_value_then_state_nonce_returnTo_preserved() {
        OauthTransaction txn = OauthTransaction.issue("/projects/123?tab=2");

        OauthTransaction parsed = OauthTransaction.parse(txn.cookieValue()).orElseThrow();

        assertThat(parsed.state()).isEqualTo(txn.state());
        assertThat(parsed.nonce()).isEqualTo(txn.nonce());
        assertThat(parsed.returnTo()).isEqualTo("/projects/123?tab=2");
    }

    @Test
    void given_issue_when_generate_two_then_state_and_nonce_differ() {
        OauthTransaction first = OauthTransaction.issue("/");
        OauthTransaction second = OauthTransaction.issue("/");

        assertThat(first.state()).isNotEqualTo(second.state());
        assertThat(first.nonce()).isNotEqualTo(second.nonce());
    }

    @Test
    void given_returnTo_with_colon_and_unicode_when_roundtrip_then_preserved() {
        // returnTo 含冒号 / 中文 / & = —— cookie 值 URL 编码承载，按 limit 3 切分不误伤
        OauthTransaction txn = OauthTransaction.issue("/detail/12:34?a=1&b=/x&c=中文");

        assertThat(OauthTransaction.parse(txn.cookieValue()))
                .hasValueSatisfying(parsed -> assertThat(parsed.returnTo())
                        .isEqualTo("/detail/12:34?a=1&b=/x&c=中文"));
    }

    // -------- returnTo 安全校验（#32：只接受单个 / 开头的同源相对路径） --------

    @Test
    void given_absolute_url_when_issue_then_fallback_to_root() {
        assertThat(OauthTransaction.issue("https://evil.example.com/phish").returnTo()).isEqualTo("/");
    }

    @Test
    void given_protocol_relative_when_issue_then_fallback_to_root() {
        assertThat(OauthTransaction.issue("//evil.example.com").returnTo()).isEqualTo("/");
    }

    @Test
    void given_backslash_prefix_when_issue_then_fallback_to_root() {
        // 浏览器把 /\ 规范化为 //，等同协议相对跳转
        assertThat(OauthTransaction.issue("/\\evil.example.com").returnTo()).isEqualTo("/");
    }

    @Test
    void given_missing_or_blank_when_issue_then_fallback_to_root() {
        assertThat(OauthTransaction.issue(null).returnTo()).isEqualTo("/");
        assertThat(OauthTransaction.issue("").returnTo()).isEqualTo("/");
        assertThat(OauthTransaction.issue("   ").returnTo()).isEqualTo("/");
    }

    @Test
    void given_control_characters_when_issue_then_fallback_to_root() {
        // Location 头注入防线：CR/LF 等控制字符一律拒收
        assertThat(OauthTransaction.issue("/ok\nSet-Cookie: x=1").returnTo()).isEqualTo("/");
    }

    @Test
    void given_relative_path_when_issue_then_kept_as_is() {
        assertThat(OauthTransaction.issue("/").returnTo()).isEqualTo("/");
        assertThat(OauthTransaction.issue("/projects").returnTo()).isEqualTo("/projects");
    }

    @Test
    void given_tampered_cookie_with_unsafe_returnTo_when_parse_then_sanitized() {
        // cookie 可被用户改写——parse 侧同样过防线（纵深防御）
        String cookieValue = "state123:nonce456:" + java.net.URLEncoder.encode("//evil.example.com",
                java.nio.charset.StandardCharsets.UTF_8);

        assertThat(OauthTransaction.parse(cookieValue))
                .hasValueSatisfying(parsed -> assertThat(parsed.returnTo()).isEqualTo("/"));
    }

    // -------- parse 容错（非法 cookie 视为无事务 → state_mismatch） --------

    @Test
    void given_null_or_blank_cookie_when_parse_then_empty() {
        assertThat(OauthTransaction.parse(null)).isEmpty();
        assertThat(OauthTransaction.parse("")).isEmpty();
        assertThat(OauthTransaction.parse("   ")).isEmpty();
    }

    @Test
    void given_cookie_without_separator_when_parse_then_empty() {
        assertThat(OauthTransaction.parse("no-separator-here")).isEmpty();
    }

    @Test
    void given_cookie_with_blank_state_or_nonce_when_parse_then_empty() {
        assertThat(OauthTransaction.parse(":nonce:")).isEmpty();
        assertThat(OauthTransaction.parse("state::")).isEmpty();
    }

    @Test
    void given_malformed_url_encoding_when_parse_then_empty() {
        assertThat(OauthTransaction.parse("state:nonce:%zz")).isEmpty();
    }
}
