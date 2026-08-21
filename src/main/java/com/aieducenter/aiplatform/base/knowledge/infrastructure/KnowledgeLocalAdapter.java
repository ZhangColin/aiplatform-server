package com.aieducenter.aiplatform.base.knowledge.infrastructure;

import java.util.List;

import org.springframework.stereotype.Component;

import com.cartisan.core.stereotype.Adapter;
import com.cartisan.core.stereotype.PortType;

import com.aieducenter.aiplatform.base.knowledge.application.KnowledgeAppService;
import com.aieducenter.aiplatform.base.knowledge.domain.model.KnowledgeHit;
import com.aieducenter.aiplatform.base.knowledge.domain.model.KnowledgeSpec;
import com.aieducenter.aiplatform.base.knowledge.domain.port.KnowledgePort;

/**
 * 知识端口进程内适配器（照 {@code MeteringLocalAdapter} 先例）：业务层（A5 挂钩
 * 编排，票 #28）只依赖 {@link KnowledgePort}；向量库迁腾讯云 / 拆独立服务时
 * 本适配器换实现，调用方不动（B0 蓝图 §3，端口即边界）。
 */
@Component
@Adapter(PortType.CLIENT)
public class KnowledgeLocalAdapter implements KnowledgePort {

    private final KnowledgeAppService knowledgeAppService;

    public KnowledgeLocalAdapter(KnowledgeAppService knowledgeAppService) {
        this.knowledgeAppService = knowledgeAppService;
    }

    @Override
    public void index(KnowledgeSpec spec) {
        knowledgeAppService.index(spec);
    }

    @Override
    public List<KnowledgeHit> retrieve(String query, int topK) {
        return knowledgeAppService.retrieve(query, topK);
    }

    @Override
    public void purgeByProject(String projectId) {
        knowledgeAppService.purgeByProject(projectId);
    }
}
