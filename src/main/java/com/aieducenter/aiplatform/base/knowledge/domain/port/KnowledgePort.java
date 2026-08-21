package com.aieducenter.aiplatform.base.knowledge.domain.port;

import java.util.List;

import com.cartisan.core.stereotype.Port;
import com.cartisan.core.stereotype.PortType;

import com.aieducenter.aiplatform.base.knowledge.domain.model.KnowledgeHit;
import com.aieducenter.aiplatform.base.knowledge.domain.model.KnowledgeSpec;

/**
 * 知识入库/检索端口（B0 蓝图 §2 片3，base.knowledge 对业务层的唯一入口）：
 * 「哪个阶段产出什么、何时摄取」归业务编排（A5 挂钩），底座只管入库/检索/清理。
 *
 * <p>embedding 服务不可用时一律降级不炸：index 记日志跳过（旧块保留，丢失容忍，
 * A5 §1）、retrieve 返回空列表——不阻断调用方主流程。</p>
 *
 * <p>进程内适配器起步；向量库迁腾讯云 / 拆独立服务时换适配器，签名不动
 * （B0 蓝图 §3，端口即边界）。</p>
 */
@Port(PortType.CLIENT)
public interface KnowledgePort {

    /**
     * 素材分块入库（幂等）：按 {@code (kind, sourceRef)} 删后插——驳回重交再确认、
     * 产物重入库天然覆盖不重复（A5 §1）。
     */
    void index(KnowledgeSpec spec);

    /**
     * 语义检索：全局跨项目纯相似 topK（不过滤、不配额，A5 §3）；query 为任务
     * prompt 全文（超长截断由调用方处理）。
     */
    List<KnowledgeHit> retrieve(String query, int topK);

    /**
     * 按项目级联清理全部知识块（项目 DELETE 入口，A5 §5）。
     */
    void purgeByProject(String projectId);
}
