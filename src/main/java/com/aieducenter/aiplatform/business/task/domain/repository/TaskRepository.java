package com.aieducenter.aiplatform.business.task.domain.repository;

import java.util.Collection;
import java.util.List;

import com.cartisan.data.jpa.repository.BaseRepository;

import com.aieducenter.aiplatform.business.task.domain.aggregate.Task;
import com.aieducenter.aiplatform.business.task.domain.enums.TaskStatus;

/**
 * 任务仓储（{@code tsk_tasks}）。dev 项目任务全量 / opc 指派清单 / 待办谓词
 * （四型投影源，量小全量读内存裁决——同 ProjectQueryAppService 口径）。
 */
public interface TaskRepository extends BaseRepository<Task, Long> {

    List<Task> findByProjectId(Long projectId);

    List<Task> findByAssigneeAccountId(Long assigneeAccountId);

    /** 按状态集合取全量（四型待办投影源：进行中集合 / SUBMITTED / PUBLISHED 等）。 */
    List<Task> findByStatusIn(Collection<TaskStatus> statuses);
}
