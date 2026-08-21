package com.aieducenter.aiplatform.business.project.infrastructure;

import org.springframework.stereotype.Component;

import com.cartisan.core.stereotype.Adapter;
import com.cartisan.core.stereotype.PortType;

import com.aieducenter.aiplatform.business.project.domain.port.OpenBugQueryPort;

/**
 * 未关闭 Bug 查询占位适配（票 #23 片5b）：task 上下文未建，恒无 Bug——
 * G3 门禁的业务谓词半边自第一天接入，行为上等同放行。A4（票 #26）落地
 * {@code tsk_bugs} 后以真实现替换本类（同 bean 类型注入，调用方零改动）。
 */
@Component
@Adapter(PortType.CLIENT)
public class NoopOpenBugQueryAdapter implements OpenBugQueryPort {

    @Override
    public boolean hasOpenBugs(Long projectId) {
        return false;
    }
}
