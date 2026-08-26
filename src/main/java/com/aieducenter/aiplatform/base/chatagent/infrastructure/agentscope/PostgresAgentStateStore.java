package com.aieducenter.aiplatform.base.chatagent.infrastructure.agentscope;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.ListHashUtil;
import io.agentscope.core.state.State;
import io.agentscope.core.util.JsonUtils;

/**
 * AgentScope {@link AgentStateStore} 的 PostgreSQL 实现（#48 会话恢复）：对话智能体
 * 的 AgentState（对话历史/压缩摘要/权限规则/Plan Mode/tool state）落平台主库
 * cat_agent_state 表，按 (userId, sessionId) 槽位寻址——平台重启后同一会话标识
 * 恢复续跑。agentscope 2.0.1 自带实现只有 MySQL 方言（LONGTEXT/ON UPDATE），PG 版
 * 语义对齐之：单值 item_index=0 一行；列表逐项一行增量 append，
 * {@code {key}:_hash} 辅助行做变更检测（hash 变/缩短 → 全量重写，纯增长 → 只插
 * 新项）；userId 折进槽位列（匿名桶 {@code __anon__}）。
 *
 * <p>写操作经 {@link TransactionTemplate}（append 检测与插入的原子性）；序列化走
 * AgentScope {@link JsonUtils}（与框架内建 store 同一 codec，Msg 等 State 子类可
 * 无损往返）。</p>
 */
@Component
public class PostgresAgentStateStore implements AgentStateStore {

    private static final String TABLE = "cat_agent_state";
    /** 单值/哈希行的 UPSERT（PG 方言：冲突即覆写 + 刷新时间戳）。 */
    private static final String UPSERT_SQL =
            "INSERT INTO " + TABLE + " (user_id, session_id, state_key, item_index,"
                    + " state_data) VALUES (?, ?, ?, ?, ?)"
                    + " ON CONFLICT (user_id, session_id, state_key, item_index)"
                    + " DO UPDATE SET state_data = EXCLUDED.state_data,"
                    + " updated_at = CURRENT_TIMESTAMP";
    private static final String HASH_KEY_SUFFIX = ":_hash";
    private static final int SINGLE_STATE_INDEX = 0;
    private static final String ANON_USER = "__anon__";

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    @Autowired
    public PostgresAgentStateStore(JdbcTemplate jdbcTemplate,
                                   TransactionTemplate transactionTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
    }

    /** 测试便利构造（自备数据源事务管理器，传播缺省）。 */
    PostgresAgentStateStore(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, new TransactionTemplate(
                new DataSourceTransactionManager(jdbcTemplate.getDataSource())));
    }

    @Override
    public void save(String userId, String sessionId, String key, State value) {
        String slotUser = normalizeUser(userId);
        requireSession(sessionId);
        transactionTemplate.executeWithoutResult(tx -> jdbcTemplate.update(
                UPSERT_SQL, slotUser, sessionId, key, SINGLE_STATE_INDEX, toJson(value)));
    }

    @Override
    public void save(String userId, String sessionId, String key, List<? extends State> values) {
        String slotUser = normalizeUser(userId);
        requireSession(sessionId);
        if (values.isEmpty()) {
            return;
        }
        String hashKey = key + HASH_KEY_SUFFIX;
        transactionTemplate.executeWithoutResult(tx -> {
            String currentHash = ListHashUtil.computeHash(values);
            String storedHash = storedHash(slotUser, sessionId, hashKey);
            int existingCount = listCount(slotUser, sessionId, key);
            if (ListHashUtil.needsFullRewrite(values, storedHash, existingCount)) {
                jdbcTemplate.update("DELETE FROM " + TABLE
                        + " WHERE user_id = ? AND session_id = ? AND state_key = ?",
                        slotUser, sessionId, key);
                insertItems(slotUser, sessionId, key, values, 0);
            } else if (values.size() > existingCount) {
                insertItems(slotUser, sessionId, key,
                        values.subList(existingCount, values.size()), existingCount);
            } else {
                return; // 无变化：跳过（hash 与行数都没动）
            }
            saveHash(slotUser, sessionId, hashKey, currentHash);
        });
    }

    @Override
    public <T extends State> Optional<T> get(String userId, String sessionId, String key,
                                             Class<T> type) {
        List<String> json = jdbcTemplate.queryForList(
                "SELECT state_data FROM " + TABLE
                        + " WHERE user_id = ? AND session_id = ? AND state_key = ?"
                        + " AND item_index = ?",
                String.class, normalizeUser(userId), sessionId, key, SINGLE_STATE_INDEX);
        return json.isEmpty() ? Optional.empty() : Optional.of(fromJson(json.get(0), type));
    }

    @Override
    public <T extends State> List<T> getList(String userId, String sessionId, String key,
                                             Class<T> itemType) {
        return jdbcTemplate.query(
                "SELECT state_data FROM " + TABLE + " WHERE user_id = ? AND session_id = ?"
                        + " AND state_key = ? ORDER BY item_index",
                (rs, i) -> fromJson(rs.getString("state_data"), itemType),
                normalizeUser(userId), sessionId, key);
    }

    @Override
    public boolean exists(String userId, String sessionId) {
        requireSession(sessionId);
        return !jdbcTemplate.queryForList(
                "SELECT 1 FROM " + TABLE + " WHERE user_id = ? AND session_id = ? LIMIT 1",
                Integer.class, normalizeUser(userId), sessionId).isEmpty();
    }

    @Override
    public void delete(String userId, String sessionId) {
        requireSession(sessionId);
        transactionTemplate.executeWithoutResult(tx -> jdbcTemplate.update(
                "DELETE FROM " + TABLE + " WHERE user_id = ? AND session_id = ?",
                normalizeUser(userId), sessionId));
    }

    @Override
    public Set<String> listSessionIds(String userId) {
        List<String> slots = jdbcTemplate.queryForList(
                "SELECT DISTINCT session_id FROM " + TABLE + " WHERE user_id = ?"
                        + " ORDER BY session_id",
                String.class, normalizeUser(userId));
        return new HashSet<>(slots);
    }

    // ---------- 内部 ----------

    private String storedHash(String slotUser, String sessionId, String hashKey) {
        List<String> found = jdbcTemplate.queryForList(
                "SELECT state_data FROM " + TABLE + " WHERE user_id = ? AND session_id = ?"
                        + " AND state_key = ? AND item_index = ?",
                String.class, slotUser, sessionId, hashKey, SINGLE_STATE_INDEX);
        return found.isEmpty() ? null : found.get(0);
    }

    private void saveHash(String slotUser, String sessionId, String hashKey, String hash) {
        jdbcTemplate.update(UPSERT_SQL, slotUser, sessionId, hashKey, SINGLE_STATE_INDEX, hash);
    }

    private int listCount(String slotUser, String sessionId, String key) {
        Integer max = jdbcTemplate.queryForObject(
                "SELECT MAX(item_index) FROM " + TABLE + " WHERE user_id = ? AND session_id = ?"
                        + " AND state_key = ?",
                Integer.class, slotUser, sessionId, key);
        return max == null ? 0 : max + 1;
    }

    private void insertItems(String slotUser, String sessionId, String key,
                             List<? extends State> items, int startIndex) {
        List<Object[]> batch = new ArrayList<>(items.size());
        int index = startIndex;
        for (State item : items) {
            batch.add(new Object[] {slotUser, sessionId, key, index, toJson(item)});
            index++;
        }
        jdbcTemplate.batchUpdate("INSERT INTO " + TABLE + " (user_id, session_id, state_key,"
                + " item_index, state_data) VALUES (?, ?, ?, ?, ?)", batch);
    }

    private static String normalizeUser(String userId) {
        return userId == null || userId.isBlank() ? ANON_USER : userId;
    }

    private static void requireSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
    }

    private static String toJson(State value) {
        return JsonUtils.getJsonCodec().toJson(value);
    }

    private static <T extends State> T fromJson(String json, Class<T> type) {
        return JsonUtils.getJsonCodec().fromJson(json, type);
    }
}
