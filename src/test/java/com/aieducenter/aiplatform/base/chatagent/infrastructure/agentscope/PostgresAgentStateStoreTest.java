package com.aieducenter.aiplatform.base.chatagent.infrastructure.agentscope;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import io.agentscope.core.state.State;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 对话智能体会话状态落库（#48 验收：平台重启后按会话标识恢复）：AgentState 的
 * PostgreSQL 槽位寻址——(userId, sessionId) 隔离、单值往返、列表增量 append 与
 * 变更全量重写、会话删除、跨实例（模拟重启）读回。
 */
@SpringBootTest
class PostgresAgentStateStoreTest {

    /** 测试用 State 载体（AgentScope 推荐 record 形态）。 */
    private record Probe(String text, int seq) implements State {
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private PostgresAgentStateStore store;

    @BeforeEach
    void setUp() {
        store = new PostgresAgentStateStore(jdbcTemplate);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM cat_agent_state WHERE session_id LIKE 'ses-t%'");
    }

    @Test
    void given_single_state_when_saved_then_roundtrip_and_user_slots_isolated() {
        store.save("alice", "ses-t1", "agent_state", new Probe("访谈中", 1));

        assertThat(store.get("alice", "ses-t1", "agent_state", Probe.class))
                .contains(new Probe("访谈中", 1));
        // 同 sessionId 异 userId 不串读（匿名桶同理隔离）
        assertThat(store.get("bob", "ses-t1", "agent_state", Probe.class)).isEmpty();
        assertThat(store.get(null, "ses-t1", "agent_state", Probe.class)).isEmpty();
        assertThat(store.exists("alice", "ses-t1")).isTrue();
        assertThat(store.exists("bob", "ses-t1")).isFalse();
    }

    @Test
    void given_append_only_list_when_saved_twice_then_only_new_items_written() {
        store.save("alice", "ses-t2", "memory_messages",
                List.of(new Probe("m0", 0), new Probe("m1", 1)));
        int rowsAfterFirst = countItems("alice", "ses-t2", "memory_messages");

        store.save("alice", "ses-t2", "memory_messages",
                List.of(new Probe("m0", 0), new Probe("m1", 1), new Probe("m2", 2)));

        // 纯增长：只追加一项（不重写前两项——行数差 1，首行内容不动）
        assertThat(countItems("alice", "ses-t2", "memory_messages"))
                .isEqualTo(rowsAfterFirst + 1);
        assertThat(store.getList("alice", "ses-t2", "memory_messages", Probe.class))
                .containsExactly(new Probe("m0", 0), new Probe("m1", 1), new Probe("m2", 2));
    }

    @Test
    void given_mutated_list_when_saved_then_full_rewrite() {
        store.save("alice", "ses-t3", "memory_messages",
                List.of(new Probe("m0", 0), new Probe("m1", 1)));
        // 首元素变化（hash 变）→ 全量重写
        store.save("alice", "ses-t3", "memory_messages",
                List.of(new Probe("CHANGED", 0), new Probe("m1", 1)));

        assertThat(store.getList("alice", "ses-t3", "memory_messages", Probe.class))
                .containsExactly(new Probe("CHANGED", 0), new Probe("m1", 1));
        // 缩短 → 全量重写（残留行不幽灵复现）
        store.save("alice", "ses-t3", "memory_messages", List.of(new Probe("only", 0)));
        assertThat(store.getList("alice", "ses-t3", "memory_messages", Probe.class))
                .containsExactly(new Probe("only", 0));
    }

    @Test
    void given_session_state_when_deleted_then_slot_gone_but_others_untouched() {
        store.save("alice", "ses-t5", "agent_state", new Probe("a", 1));
        store.save("alice", "ses-t6", "agent_state", new Probe("b", 2));
        store.save("bob", "ses-t5", "agent_state", new Probe("c", 3));

        store.delete("alice", "ses-t5");

        assertThat(store.exists("alice", "ses-t5")).isFalse();
        assertThat(store.exists("alice", "ses-t6")).isTrue();
        assertThat(store.exists("bob", "ses-t5")).isTrue();
        assertThat(store.listSessionIds("alice")).containsExactly("ses-t6");
        assertThat(store.listSessionIds("bob")).containsExactly("ses-t5");
    }

    @Test
    void given_state_written_when_store_rebuilt_then_restored_from_database() {
        // 平台重启 = 进程内无状态、新 store 实例接同一库：按会话标识原样读回
        store.save("alice", "ses-t7", "agent_state", new Probe("跨重启", 7));
        store.save("alice", "ses-t7", "memory_messages",
                List.of(new Probe("m0", 0), new Probe("m1", 1)));

        PostgresAgentStateStore restarted = new PostgresAgentStateStore(jdbcTemplate);

        assertThat(restarted.get("alice", "ses-t7", "agent_state", Probe.class))
                .contains(new Probe("跨重启", 7));
        assertThat(restarted.getList("alice", "ses-t7", "memory_messages", Probe.class))
                .hasSize(2);
        assertThat(restarted.listSessionIds("alice")).contains("ses-t7");
    }

    private int countItems(String userId, String sessionId, String key) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM cat_agent_state WHERE user_id = ? AND session_id = ?"
                        + " AND state_key = ?",
                Integer.class, userId, sessionId, key);
        return count == null ? 0 : count;
    }
}
