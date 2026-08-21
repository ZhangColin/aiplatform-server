package com.aieducenter.aiplatform.business.identity.domain.model;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;

/**
 * OAuth 往返事务（A2 §2 表 1/2 行 + #32 returnTo 增量）。
 *
 * <p>登录发起时签发（随机 state/nonce + 净化后的 returnTo），序列化为
 * {@code oauth_txn} cookie（{@code state:nonce:<urlencode(returnTo)>}，600s）；
 * 回调时解析回对象，state 与 identity 回带值精确比对（防 CSRF），nonce 供
 * id_token 验签（防重放），returnTo 决定登录后回跳。</p>
 *
 * <p>returnTo 是开放重定向防线的正主（#32）：只接受单个 {@code /} 开头的同源
 * 相对路径——拒绝 {@code //}（协议相对）、{@code /\}（浏览器规范化后等同协议
 * 相对）、绝对 URL 与一切控制字符（Location 头注入）；非法一律回落 {@code /}。
 * 签发与解析两侧都过同一防线（cookie 可被用户改写，纵深防御）。</p>
 */
public record OauthTransaction(String state, String nonce, String returnTo) {

    private static final SecureRandom RANDOM = new SecureRandom();

    public OauthTransaction {
        if (state == null || state.isBlank() || nonce == null || nonce.isBlank()) {
            throw new IllegalArgumentException("state/nonce 不能为空");
        }
        returnTo = sanitizeReturnTo(returnTo);
    }

    /**
     * 签发新事务（登录发起）：随机 state/nonce + 净化 returnTo。
     */
    public static OauthTransaction issue(String requestedReturnTo) {
        return new OauthTransaction(randomToken(), randomToken(), requestedReturnTo);
    }

    /**
     * 序列化为 cookie 值；returnTo 段 URL 编码（cookie 值安全字符 + 冒号切分不误伤）。
     */
    public String cookieValue() {
        return state + ":" + nonce + ":"
                + URLEncoder.encode(returnTo, StandardCharsets.UTF_8);
    }

    /**
     * 从 cookie 值解析；格式非法 / 编码损坏 / 字段缺失 → empty（调用方按 state_mismatch 兜底）。
     */
    public static Optional<OauthTransaction> parse(String cookieValue) {
        if (cookieValue == null || cookieValue.isBlank()) {
            return Optional.empty();
        }
        String[] parts = cookieValue.split(":", 3);
        if (parts.length != 3) {
            return Optional.empty();
        }
        try {
            return Optional.of(new OauthTransaction(parts[0], parts[1],
                    URLDecoder.decode(parts[2], StandardCharsets.UTF_8)));
        } catch (IllegalArgumentException e) {
            // 空白 state/nonce 或损坏的 URL 编码
            return Optional.empty();
        }
    }

    /**
     * returnTo 净化（开放重定向防线，签发 / 解析共用）：只放行单个 {@code /} 开头、
     * 无控制字符的同源相对路径，其余一律回落 {@code /}。
     */
    public static String sanitizeReturnTo(String candidate) {
        if (candidate == null) {
            return "/";
        }
        String trimmed = candidate.trim();
        boolean safeRelativePath = trimmed.startsWith("/")
                && !trimmed.startsWith("//")
                && !trimmed.startsWith("/\\")
                && trimmed.codePoints().noneMatch(Character::isISOControl);
        return safeRelativePath ? trimmed : "/";
    }

    /**
     * 不透明随机串（state / nonce / sessionId / logout state 共用，照 identity demo）。
     */
    public static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
