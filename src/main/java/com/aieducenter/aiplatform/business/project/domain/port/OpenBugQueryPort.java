package com.aieducenter.aiplatform.business.project.domain.port;

import com.cartisan.core.stereotype.Port;
import com.cartisan.core.stereotype.PortType;

/**
 * 未关闭 Bug 查询端口（A3 §2.4/§6 的缝）：G3「开发完成确认」的业务谓词
 * （无未关闭 Bug 才放行）与门就绪派生共用。消费方定义端口（business.project），
 * 实现归 business.task（A4，票 #26：Bug 三态项目级记录）。
 *
 * <p>片5b 只接缝不接实现：默认适配恒无 Bug（见 infrastructure 占位），#26 落地
 * 时以真实现替换——门禁语义自第一天完整（计数 ∧ 谓词）。</p>
 */
@Port(PortType.CLIENT)
public interface OpenBugQueryPort {

    /**
     * 项目当前是否存在未关闭的 Bug（G3 谓词：true = 门不放行）。
     */
    boolean hasOpenBugs(Long projectId);
}
