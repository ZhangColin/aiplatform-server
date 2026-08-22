package com.aieducenter.aiplatform.base.metering.application;

import java.time.Instant;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cartisan.core.exception.ApplicationException;

import lombok.extern.slf4j.Slf4j;

import com.aieducenter.aiplatform.base.metering.domain.aggregate.UsageEventEntry;
import com.aieducenter.aiplatform.base.metering.domain.error.MeteringMessage;
import com.aieducenter.aiplatform.base.metering.domain.model.UsageEvent;
import com.aieducenter.aiplatform.base.metering.domain.model.UsageSummary;
import com.aieducenter.aiplatform.base.metering.domain.repository.UsageEventAggregations;
import com.aieducenter.aiplatform.base.metering.domain.repository.UsageEventRepository;

/**
 * 计量用例（票 #16 + #29）：用量事件幂等采集 + 按 subject 聚合查询（总量/分模型/
 * 分维度 + 平台成本换算与未配价标注，A6 §2 查询侧现算）。
 *
 * <p>幂等 = first-write-wins：eventId 为主键，重复上报（含并发撞主键）静默吸收、
 * 不抛错——调用方按「上报可安全重试」语义使用（A1 §2.1）。采集与聚合只记 token
 * 不记钱；金额换算（平台成本）不落库，每次查询按事件时点生效单价现算。</p>
 */
@Service
@Slf4j
public class MeteringAppService {

    private final UsageEventRepository usageEventRepository;
    private final UsageEventAggregations usageEventAggregations;

    public MeteringAppService(UsageEventRepository usageEventRepository,
                              UsageEventAggregations usageEventAggregations) {
        this.usageEventRepository = usageEventRepository;
        this.usageEventAggregations = usageEventAggregations;
    }

    /**
     * 上报一条用量事件（append-only）。同 eventId 已存在（先到或并发先落）则
     * 幂等跳过；撞主键但行仍不可见时以完整性异常上抛。
     *
     * <p>刻意不加 {@code @Transactional}：单行插入由仓储自带事务保证；并发撞键的
     * 吸收（catch 后再查 existsById）要求 save 的事务已独立结束，不处在外层事务中。</p>
     */
    public void report(UsageEvent event) {
        UsageEventEntry entry = UsageEventEntry.of(event);
        if (usageEventRepository.existsById(entry.getEventId())) {
            log.debug("用量事件 {} 重复上报，幂等跳过", entry.getEventId());
            return;
        }
        try {
            usageEventRepository.save(entry);
        } catch (DataIntegrityViolationException e) {
            // 并发同 eventId 撞主键：后落者吸收（first-write-wins）
            if (usageEventRepository.existsById(entry.getEventId())) {
                log.info("用量事件 {} 并发重复上报，幂等吸收", entry.getEventId());
                return;
            }
            throw e;
        }
    }

    /**
     * 按 subject 聚合（总量 + 平台成本 + 未配价 + 分模型 + 分维度），时间窗半开区间，
     * null 侧不限。只读事务快照：五条聚合 SQL 落在同一一致性视图，并发上报不破坏
     * 总量 = Σ分模型 = Σ分维度、cost = 窗口内已配价分量和 的自洽。
     */
    @Transactional(readOnly = true)
    public UsageSummary bySubject(String subject, Instant from, Instant to) {
        if (subject == null || subject.isBlank()) {
            throw new ApplicationException(MeteringMessage.USAGE_SUBJECT_REQUIRED);
        }
        return usageEventAggregations.aggregateBySubject(subject, from, to);
    }
}
