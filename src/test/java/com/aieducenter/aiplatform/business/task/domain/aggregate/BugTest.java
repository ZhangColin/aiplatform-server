package com.aieducenter.aiplatform.business.task.domain.aggregate;

import org.junit.jupiter.api.Test;

import com.cartisan.core.exception.DomainException;

import com.aieducenter.aiplatform.business.task.domain.enums.BugSeverity;
import com.aieducenter.aiplatform.business.task.domain.enums.BugStatus;
import com.aieducenter.aiplatform.business.task.domain.error.TaskMessage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Bug 聚合三态（票 #26，A4 §1/§3）：确认时入库 OPEN；复测 pass=true → VERIFIED
 * （唯一关闭态）、pass=false → 退回 OPEN（修复再派发随 #27）；bogus 手工关闭 =
 * VERIFIED + closed_reason 的别名动作（端点随 #27）。v1 本票只到入库与复测翻态。
 */
class BugTest {

    private static final Long PROJECT_ID = 8002L;
    private static final Long TASK_ID = 8102L;

    @Test
    void given_confirmed_payload_bug_when_open_then_status_open() {
        Bug bug = Bug.openOf(PROJECT_ID, TASK_ID, "登录 500", "提交后 500",
                "1. 打开登录页", BugSeverity.CRITICAL);

        assertThat(bug.getStatus()).isEqualTo(BugStatus.OPEN);
        assertThat(bug.getProjectId()).isEqualTo(PROJECT_ID);
        assertThat(bug.getSourceTaskId()).isEqualTo(TASK_ID);
        assertThat(bug.getFixRunId()).isNull();
        assertThat(bug.getClosedReason()).isNull();
    }

    @Test
    void given_blank_title_when_open_then_task_001_incomplete() {
        assertThatThrownBy(() -> Bug.openOf(PROJECT_ID, TASK_ID, " ", "描述", "步骤",
                BugSeverity.MINOR))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining(TaskMessage.TASK_FIELDS_INCOMPLETE.message());
        assertThatThrownBy(() -> Bug.openOf(null, TASK_ID, "标题", "描述", "步骤",
                BugSeverity.MINOR))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining(TaskMessage.TASK_FIELDS_INCOMPLETE.message());
    }

    @Test
    void given_open_bug_when_retest_pass_then_verified() {
        Bug bug = openBug();

        bug.applyRetestResult(true);

        assertThat(bug.getStatus()).isEqualTo(BugStatus.VERIFIED);
    }

    @Test
    void given_fixed_bug_when_retest_fail_then_back_to_open() {
        // FIXED（修复链乐观翻转，#27）复测不过：退回 OPEN 待再派发
        Bug bug = fixedBug();

        bug.applyRetestResult(false);

        assertThat(bug.getStatus()).isEqualTo(BugStatus.OPEN);
    }

    @Test
    void given_fixed_bug_when_retest_pass_then_verified() {
        Bug bug = fixedBug();

        bug.applyRetestResult(true);

        assertThat(bug.getStatus()).isEqualTo(BugStatus.VERIFIED);
    }

    @Test
    void given_verified_bug_when_retest_then_task_002_illegal() {
        // VERIFIED 是终态（唯一关闭态）：复测结果不可再翻
        Bug bug = openBug();
        bug.applyRetestResult(true);

        assertThatThrownBy(() -> bug.applyRetestResult(false))
                .isInstanceOfSatisfying(DomainException.class, e ->
                        assertThat(e.getCodeMessage().code()).isEqualTo("TASK_002"))
                .hasMessageContaining("VERIFIED");
    }

    // ---------- 测试数据 ----------

    private static Bug openBug() {
        return Bug.openOf(PROJECT_ID, TASK_ID, "登录 500", "提交后 500", "1. 打开登录页",
                BugSeverity.CRITICAL);
    }

    /** 已翻 FIXED 的 Bug（#27 修复链产物；本票谓词与复测翻态先就位）。 */
    private static Bug fixedBug() {
        return Bug.fixedOf(PROJECT_ID, TASK_ID, "登录 500", "run-9", "已修复登录拦截");
    }
}
