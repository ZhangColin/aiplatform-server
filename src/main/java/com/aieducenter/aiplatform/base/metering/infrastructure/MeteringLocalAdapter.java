package com.aieducenter.aiplatform.base.metering.infrastructure;

import java.time.Instant;

import org.springframework.stereotype.Component;

import com.cartisan.core.stereotype.Adapter;
import com.cartisan.core.stereotype.PortType;

import com.aieducenter.aiplatform.base.metering.application.MeteringAppService;
import com.aieducenter.aiplatform.base.metering.domain.model.UsageEvent;
import com.aieducenter.aiplatform.base.metering.domain.model.UsageSummary;
import com.aieducenter.aiplatform.base.metering.domain.port.UsageEventSink;
import com.aieducenter.aiplatform.base.metering.domain.port.UsageQueryPort;

/**
 * 计量端口进程内适配器（平台内起步，A1 §2.1）：sink 与 query 两端口同落
 * {@link MeteringAppService}——迁出独立计量服务时按端口各自换 REST 适配器
 * （上报/查询可分步迁），调用方（agentengine / 业务编排）不动（端口即边界）。
 */
@Component
@Adapter(PortType.CLIENT)
public class MeteringLocalAdapter implements UsageEventSink, UsageQueryPort {

    private final MeteringAppService meteringAppService;

    public MeteringLocalAdapter(MeteringAppService meteringAppService) {
        this.meteringAppService = meteringAppService;
    }

    @Override
    public void report(UsageEvent event) {
        meteringAppService.report(event);
    }

    @Override
    public UsageSummary bySubject(String subject, Instant from, Instant to) {
        return meteringAppService.bySubject(subject, from, to);
    }
}
