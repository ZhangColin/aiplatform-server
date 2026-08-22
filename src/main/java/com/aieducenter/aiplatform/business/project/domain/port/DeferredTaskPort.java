package com.aieducenter.aiplatform.business.project.domain.port;

import com.cartisan.core.stereotype.Port;
import com.cartisan.core.stereotype.PortType;

/**
 * 转任务建任务端口（A1 §3.1 第 1 步，#27 回填链起点）：settle(Deferred) 关等待点
 * 后由等待点桥接（project）调入——任务记录存 waitId 不透明引用，TaskCompleted
 * 据此续跑原会话。消费方定义端口（business.project），实现归 business.task
 * （任务事实的持有方，见 {@code TaskDeferredTaskAdapter}）。
 */
@Port(PortType.CLIENT)
public interface DeferredTaskPort {

    /**
     * 从等待点建任务（v1 type=TEST）：守卫链与人建任务同构（owner/指派/advance）。
     *
     * @return 创建的任务 id
     */
    String createFromWait(Long projectId, String waitId, String title, String content,
                          Long assigneeAccountId);
}
