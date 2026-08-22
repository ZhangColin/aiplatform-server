package com.aieducenter.aiplatform.business.task.infrastructure;

import org.springframework.stereotype.Component;

import com.cartisan.core.stereotype.Adapter;
import com.cartisan.core.stereotype.PortType;

import com.aieducenter.aiplatform.business.project.domain.port.DeferredTaskPort;
import com.aieducenter.aiplatform.business.task.application.TaskLifecycleAppService;
import com.aieducenter.aiplatform.business.task.application.dto.command.CreateTaskCommand;
import com.aieducenter.aiplatform.business.task.application.dto.response.TaskResponse;

/**
 * 转任务建任务真实现（A1 §3.1/#27）：端口在 business.project（消费方定义），
 * 实现归 task BC（任务用例的持有方）——守卫链（owner/指派/advance）与 SSE
 * task-updated 经 {@link TaskLifecycleAppService#createFromWait} 全继承。
 */
@Component
@Adapter(PortType.CLIENT)
public class TaskDeferredTaskAdapter implements DeferredTaskPort {

    private final TaskLifecycleAppService lifecycleAppService;

    public TaskDeferredTaskAdapter(TaskLifecycleAppService lifecycleAppService) {
        this.lifecycleAppService = lifecycleAppService;
    }

    @Override
    public String createFromWait(Long projectId, String waitId, String title, String content,
                                 Long assigneeAccountId) {
        TaskResponse task = lifecycleAppService.createFromWait(projectId, waitId,
                new CreateTaskCommand(title, content, assigneeAccountId));
        return task.taskId();
    }
}
