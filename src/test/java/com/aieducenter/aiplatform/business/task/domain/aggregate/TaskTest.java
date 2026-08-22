package com.aieducenter.aiplatform.business.task.domain.aggregate;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import com.cartisan.core.exception.DomainException;

import com.aieducenter.aiplatform.business.task.domain.enums.TaskStatus;
import com.aieducenter.aiplatform.business.task.domain.enums.TaskType;
import com.aieducenter.aiplatform.business.task.domain.error.TaskMessage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 任务聚合状态机（票 #26 验收主面，A4 §2）：全迁移 + 守卫——已发布→执行中→
 * 已提交→已确认（终态）/已取消（终态）；start/submit 载荷与驳回清空、驳回
 * reason 必填、已提交只能驳回不能取消；非法迁移 DomainException TASK_002。
 */
class TaskTest {

    private static final Long PROJECT_ID = 8001L;
    private static final Long ASSIGNEE = 42L;

    @Test
    void given_valid_fields_when_publish_then_created_published_with_invariants() {
        Task task = Task.publish(PROJECT_ID, TaskType.TEST, "首页回归测试", "全量回归",
                ASSIGNEE, null);

        assertThat(task.getStatus()).isEqualTo(TaskStatus.PUBLISHED);
        assertThat(task.getProjectId()).isEqualTo(PROJECT_ID);
        assertThat(task.getAssigneeAccountId()).isEqualTo(ASSIGNEE);
        assertThat(task.getWaitId()).isNull();
        assertThat(task.getSubmittedPayload()).isNull();
        assertThat(task.getRejectedAt()).isNull();
    }

    @Test
    void given_wait_source_when_publish_then_kept_as_opaque_reference() {
        // 转任务来源：waitId 不透明引用原样保存（回填续跑随 #27）
        Task task = Task.publish(PROJECT_ID, TaskType.TEST, "转任务", "内容", ASSIGNEE, "wait-1");

        assertThat(task.getWaitId()).isEqualTo("wait-1");
    }

    @Test
    void given_blank_title_or_assignee_when_publish_then_task_001_incomplete() {
        assertThatThrownBy(() -> Task.publish(PROJECT_ID, TaskType.TEST, " ", "内容", ASSIGNEE, null))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining(TaskMessage.TASK_FIELDS_INCOMPLETE.message());
        assertThatThrownBy(() -> Task.publish(PROJECT_ID, TaskType.TEST, "标题", "内容", null, null))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining(TaskMessage.TASK_FIELDS_INCOMPLETE.message());
    }

    // ---------- 迁移正面 ----------

    @Test
    void given_published_when_start_then_in_progress() {
        Task task = published();

        task.start();

        assertThat(task.getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
    }

    @Test
    void given_in_progress_when_submit_then_submitted_with_payload_and_rejection_cleared() {
        Task task = rejectedOnce();

        task.submit("{\"report\":\"报告\"}");

        assertThat(task.getStatus()).isEqualTo(TaskStatus.SUBMITTED);
        assertThat(task.getSubmittedPayload()).isEqualTo("{\"report\":\"报告\"}");
        // 驳回字段重新提交清空（TASK_REJECTED 待办判定离开，A4 §2 表）
        assertThat(task.getRejectReason()).isNull();
        assertThat(task.getRejectedAt()).isNull();
    }

    @Test
    void given_submitted_when_confirm_then_terminal_confirmed_with_time() {
        Task task = submitted();

        task.confirm();

        assertThat(task.getStatus()).isEqualTo(TaskStatus.CONFIRMED);
        assertThat(task.getConfirmedAt()).isNotNull();
    }

    @Test
    void given_submitted_when_reject_then_back_in_progress_with_reason_and_time() {
        Task task = submitted();

        task.reject("报告缺登录用例");

        assertThat(task.getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(task.getRejectReason()).isEqualTo("报告缺登录用例");
        assertThat(task.getRejectedAt()).isNotNull();
    }

    @Test
    void given_published_or_in_progress_when_cancel_then_cancelled() {
        Task published = published();
        published.cancel();
        assertThat(published.getStatus()).isEqualTo(TaskStatus.CANCELLED);

        Task inProgress = published();
        inProgress.start();
        inProgress.cancel();
        assertThat(inProgress.getStatus()).isEqualTo(TaskStatus.CANCELLED);
    }

    // ---------- 守卫 ----------

    @Test
    void given_submitted_when_cancel_then_task_002_not_cancellable() {
        // 已提交不能取消只能驳回（docs/11 原样）
        Task task = submitted();

        assertThatThrownBy(task::cancel)
                .isInstanceOfSatisfying(DomainException.class, e ->
                        assertThat(e.getCodeMessage().code()).isEqualTo("TASK_002"))
                .hasMessageContaining("SUBMITTED")
                .hasMessageContaining("CANCELLED");
        assertThat(task.getStatus()).isEqualTo(TaskStatus.SUBMITTED);
    }

    @Test
    void given_blank_reason_when_reject_then_task_003() {
        Task task = submitted();

        assertThatThrownBy(() -> task.reject(" "))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining(TaskMessage.REJECT_REASON_REQUIRED.message());
        assertThat(task.getStatus()).isEqualTo(TaskStatus.SUBMITTED);
    }

    @Test
    void given_non_assignee_when_require_assignee_then_task_004() {
        Task task = published();

        assertThatThrownBy(() -> task.requireAssignee(999L))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining(TaskMessage.NOT_ASSIGNEE.message());
    }

    @Test
    void given_terminal_confirmed_when_any_transition_then_task_002() {
        Task task = submitted();
        task.confirm();

        assertThatThrownBy(task::start).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> task.submit("{}")).isInstanceOf(DomainException.class);
        assertThatThrownBy(task::confirm).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> task.reject("理由")).isInstanceOf(DomainException.class);
        assertThatThrownBy(task::cancel).isInstanceOf(DomainException.class);
    }

    @Test
    void given_illegal_jump_when_start_or_confirm_then_task_002_with_positions() {
        // 已发布直接 confirm / 已取消 start：非法迁移带 from→to 细节
        Task published = published();
        assertThatThrownBy(published::confirm)
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("PUBLISHED")
                .hasMessageContaining("CONFIRMED");

        Task cancelled = published();
        cancelled.cancel();
        assertThatThrownBy(cancelled::start)
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("CANCELLED");
    }

    // ---------- 测试数据 ----------

    private static Task published() {
        return Task.publish(PROJECT_ID, TaskType.TEST, "回归测试", "全量回归", ASSIGNEE, null);
    }

    /** 已驳回过一次的任务：reject 字段非空，重新提交应清空。 */
    private static Task rejectedOnce() {
        Task task = submitted();
        task.reject("首轮驳回");
        return task;
    }

    private static Task submitted() {
        Task task = published();
        task.start();
        task.submit("{\"report\":\"报告\"}");
        return task;
    }
}
