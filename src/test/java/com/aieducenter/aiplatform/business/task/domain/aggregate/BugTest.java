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

    // ---------- 修复链状态口（#27，A4 §4） ----------

    @Test
    void given_open_bug_when_mark_dispatched_then_in_flight_marker_set() {
        Bug bug = openBug();
        bug.markFixDispatched("run-1");

        assertThat(bug.getStatus()).isEqualTo(BugStatus.OPEN); // 派发不翻态
        assertThat(bug.getFixRunId()).isEqualTo("run-1");
    }

    @Test
    void given_marked_or_non_open_bug_when_mark_dispatched_then_task_002() {
        // 幂等门：已标记/非 OPEN 不可再派
        Bug marked = openBug();
        marked.markFixDispatched("run-1");
        assertThatThrownBy(() -> marked.markFixDispatched("run-2"))
                .isInstanceOfSatisfying(DomainException.class, e ->
                        assertThat(e.getCodeMessage().code()).isEqualTo("TASK_002"));

        Bug fixed = fixedBug();
        assertThatThrownBy(() -> fixed.markFixDispatched("run-2"))
                .isInstanceOfSatisfying(DomainException.class, e ->
                        assertThat(e.getCodeMessage().code()).isEqualTo("TASK_002"))
                .hasMessageContaining("FIXED");
    }

    @Test
    void given_in_flight_bug_when_mark_fixed_then_optimistic_flip_with_note() {
        Bug bug = openBug();
        bug.markFixDispatched("run-1");

        assertThat(bug.markFixed("run-1", "已修复登录拦截")).isTrue();
        assertThat(bug.getStatus()).isEqualTo(BugStatus.FIXED);
        assertThat(bug.getFixRunId()).isEqualTo("run-1");
        assertThat(bug.getFixNote()).isEqualTo("已修复登录拦截");
    }

    @Test
    void given_late_finish_when_bug_left_open_or_marker_replaced_then_no_flip() {
        // 迟到终态不覆盖后来状态：复测已裁决（VERIFIED）/ 手工已关闭 / 标记已换
        Bug verified = openBug();
        verified.applyRetestResult(true);
        assertThat(verified.markFixed("run-1", "已修复")).isFalse();
        assertThat(verified.getStatus()).isEqualTo(BugStatus.VERIFIED);

        Bug replaced = openBug();
        replaced.markFixDispatched("run-2");
        assertThat(replaced.markFixed("run-1", "已修复")).isFalse();
        assertThat(replaced.getStatus()).isEqualTo(BugStatus.OPEN);
    }

    @Test
    void given_in_flight_bug_when_abandon_then_back_to_dispatchable_pool() {
        Bug bug = openBug();
        bug.markFixDispatched("run-1");

        assertThat(bug.abandonFixRun("run-1")).isTrue();
        assertThat(bug.getStatus()).isEqualTo(BugStatus.OPEN);
        assertThat(bug.getFixRunId()).isNull();

        // runId 不匹配不清（迟到终态不清别人的标记）
        Bug others = openBug();
        others.markFixDispatched("run-2");
        assertThat(others.abandonFixRun("run-1")).isFalse();
        assertThat(others.getFixRunId()).isEqualTo("run-2");
    }

    @Test
    void given_fixed_bug_when_retest_fail_then_fix_fields_cleared() {
        // 退回 OPEN 即回可派发池：in-flight 判定归零（fix_run_id 清 NULL），旧结论作废
        Bug bug = fixedBug();

        bug.applyRetestResult(false);

        assertThat(bug.getStatus()).isEqualTo(BugStatus.OPEN);
        assertThat(bug.getFixRunId()).isNull();
        assertThat(bug.getFixNote()).isNull();
    }

    @Test
    void given_bogus_bug_when_close_manually_then_verified_with_reason() {
        Bug openBug = openBug();
        openBug.closeManually("需求如此，非缺陷");
        assertThat(openBug.getStatus()).isEqualTo(BugStatus.VERIFIED);
        assertThat(openBug.getClosedReason()).isEqualTo("需求如此，非缺陷");

        Bug fixedBug = fixedBug(); // 修复完但 bogus：同样可关
        fixedBug.closeManually("未重现，本地无法复现");
        assertThat(fixedBug.getStatus()).isEqualTo(BugStatus.VERIFIED);

        // reason 必填（TASK_010）+ VERIFIED 终态再关 TASK_002（不加第四态）
        assertThatThrownBy(() -> openBug().closeManually(" "))
                .hasMessageContaining(TaskMessage.BUG_CLOSE_REASON_REQUIRED.message());
        assertThatThrownBy(() -> openBug().closeManually(null))
                .hasMessageContaining(TaskMessage.BUG_CLOSE_REASON_REQUIRED.message());
        Bug closed = openBug();
        closed.closeManually("关了");
        assertThatThrownBy(() -> closed.closeManually("再关"))
                .isInstanceOfSatisfying(DomainException.class, e ->
                        assertThat(e.getCodeMessage().code()).isEqualTo("TASK_002"));
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
