package com.aieducenter.aiplatform.business.identity.infrastructure.session;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * BFF 内存会话存储（A2 §2 表 2：内存 Map 自管，进程重启即丢 = 一次 SSO 弹回重登；
 * identity 侧 SSO 会话仍在，不输密码）。key = 不透明 sessionId（业务 cookie 值）。
 *
 * <p>升级触发：多实例部署 / 会话跨重启成为硬需求 → PG 表 {@code idn_sessions}
 * 换 store 实现，cookie 契约不动。</p>
 */
@Component
public class BffSessionStore {

    private final ConcurrentHashMap<String, BffSession> store = new ConcurrentHashMap<>();

    public void put(String sessionId, BffSession session) {
        store.put(sessionId, session);
    }

    public Optional<BffSession> get(String sessionId) {
        return sessionId == null ? Optional.empty() : Optional.ofNullable(store.get(sessionId));
    }

    public void remove(String sessionId) {
        if (sessionId != null) {
            store.remove(sessionId);
        }
    }
}
