package com.aieducenter.aiplatform.business.task.infrastructure;

import org.springframework.stereotype.Component;

import com.cartisan.core.stereotype.Adapter;
import com.cartisan.core.stereotype.PortType;

import com.aieducenter.aiplatform.business.project.domain.port.OpenBugQueryPort;
import com.aieducenter.aiplatform.business.task.domain.aggregate.Bug;
import com.aieducenter.aiplatform.business.task.domain.enums.BugStatus;
import com.aieducenter.aiplatform.business.task.domain.repository.BugRepository;

/**
 * 未关闭 Bug 查询真实现（A4 §5，替换片5b 的 Noop 占位）：G3「开发完成确认」
 * 业务谓词——open = status ≠ VERIFIED（含已修复待复测：复测通过是唯一关闭态）。
 * 端口在 business.project（消费方定义），实现归 task BC（Bug 事实的持有方）。
 */
@Component
@Adapter(PortType.CLIENT)
public class TaskOpenBugQueryAdapter implements OpenBugQueryPort {

    private final BugRepository bugRepository;

    public TaskOpenBugQueryAdapter(BugRepository bugRepository) {
        this.bugRepository = bugRepository;
    }

    @Override
    public boolean hasOpenBugs(Long projectId) {
        return bugRepository.existsByProjectIdAndStatusNot(projectId, BugStatus.VERIFIED);
    }
}
