package com.aieducenter.aiplatform.business.identity.infrastructure.session;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 内存会话存储：put/get/remove 语义（key = 不透明 sessionId）。
 */
class BffSessionStoreTest {

    private final BffSessionStore store = new BffSessionStore();

    @Test
    void given_stored_session_when_get_then_present() {
        BffSession session = new BffSession(1L, "张三", "idt", "at", "rt",
                Instant.now().plusSeconds(60));

        store.put("sid-1", session);

        assertThat(store.get("sid-1")).contains(session);
    }

    @Test
    void given_removed_or_unknown_session_when_get_then_empty() {
        store.put("sid-1", session());
        store.remove("sid-1");

        assertThat(store.get("sid-1")).isEmpty();
        assertThat(store.get("never-stored")).isEmpty();
        assertThat(store.get(null)).isEmpty();
    }

    private static BffSession session() {
        return new BffSession(1L, "张三", "idt", "at", "rt", Instant.now().plusSeconds(60));
    }
}
