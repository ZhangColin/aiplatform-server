package com.aieducenter.aiplatform.base.metering.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import cn.hutool.core.collection.CollUtil;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.aieducenter.aiplatform.base.metering.domain.model.TokenUsage;
import com.aieducenter.aiplatform.base.metering.domain.model.UsageSummary;
import com.aieducenter.aiplatform.base.metering.domain.repository.UsageEventAggregations;

/**
 * 读侧聚合实现（JdbcTemplate 原生 SQL）：分维度聚合要走
 * {@code jsonb_each_text} 展开，JPQL 表达不了。三条查询共用同一 subject/时间窗
 * 过滤与五档 SUM 清单（半开区间 {@code [from, to)}，null 侧不限），同处一个只读
 * 事务（应用服务层），三组数字自洽（总量 = 各分模型之和 = 各分维度之和）。
 */
@Component
public class UsageEventAggregationsImpl implements UsageEventAggregations {

    /** 五档 SUM 清单（表别名恒为 e，三条查询共用）。 */
    private static final String TOKEN_SUMS =
            "SUM(e.input), SUM(e.output), SUM(e.cache_read), SUM(e.cache_write), SUM(e.reasoning)";

    private final JdbcTemplate jdbcTemplate;

    public UsageEventAggregationsImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public UsageSummary aggregateBySubject(String subject, Instant from, Instant to) {
        List<Object> args = windowArgs(subject, from, to);
        String where = whereClause(from, to);
        TokenUsage total = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(e.input), 0), COALESCE(SUM(e.output), 0), "
                        + "COALESCE(SUM(e.cache_read), 0), COALESCE(SUM(e.cache_write), 0), "
                        + "COALESCE(SUM(e.reasoning), 0) FROM met_usage_events e" + where,
                args.toArray(), (rs, rowNum) -> readTokens(rs, 1));
        List<UsageSummary.ModelUsage> byModel = jdbcTemplate.query(
                "SELECT e.provider, e.model, " + TOKEN_SUMS
                        + " FROM met_usage_events e" + where
                        + " GROUP BY e.provider, e.model ORDER BY e.provider, e.model",
                args.toArray(), (rs, rowNum) -> new UsageSummary.ModelUsage(
                        rs.getString(1), rs.getString(2), readTokens(rs, 3)));
        // jsonb_each_text 展开事件 dims：每个 (key, value) 各成一桶；无维度行不参与
        List<UsageSummary.DimUsage> byDims = jdbcTemplate.query(
                "SELECT kv.dim_key, kv.dim_value, " + TOKEN_SUMS
                        + " FROM met_usage_events e CROSS JOIN LATERAL jsonb_each_text(e.dims)"
                        + " AS kv(dim_key, dim_value)" + where
                        + " GROUP BY kv.dim_key, kv.dim_value ORDER BY kv.dim_key, kv.dim_value",
                args.toArray(), (rs, rowNum) -> new UsageSummary.DimUsage(
                        rs.getString(1), rs.getString(2), readTokens(rs, 3)));
        return new UsageSummary(subject, from, to, total, byModel, byDims);
    }

    private static TokenUsage readTokens(ResultSet rs, int base) throws SQLException {
        return new TokenUsage(rs.getLong(base), rs.getLong(base + 1), rs.getLong(base + 2),
                rs.getLong(base + 3), rs.getLong(base + 4));
    }

    private static List<Object> windowArgs(String subject, Instant from, Instant to) {
        List<Object> args = CollUtil.newArrayList(subject);
        if (from != null) {
            args.add(Timestamp.from(from));
        }
        if (to != null) {
            args.add(Timestamp.from(to));
        }
        return args;
    }

    /** subject + 半开时间窗过滤（from/to 为 null 的侧不加条件；占位符与 args 同序）。 */
    private static String whereClause(Instant from, Instant to) {
        StringBuilder where = new StringBuilder(" WHERE e.subject = ?");
        if (from != null) {
            where.append(" AND e.ts >= ?");
        }
        if (to != null) {
            where.append(" AND e.ts < ?");
        }
        return where.toString();
    }
}
