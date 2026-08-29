package com.aieducenter.aiplatform.base.chatagent.infrastructure.agentscope;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

/**
 * {@link ChatAgentResumeGate}（#48 / #59）：提交/关闸/复活口径——deny cap 终止后
 * 提交被拒（排队中的续跑被丢弃）、同会话新 run 承接即复活、生产缝（线程池）在飞
 * 任务被 close 取消；并发化后同会话仍严格串行、跨会话并行（生产缝实测）。
 */
class ChatAgentResumeGateTest {

    @Test
    void given_open_gate_when_submit_then_task_executed() {
        List<String> ran = new ArrayList<>();
        ChatAgentResumeGate gate = new ChatAgentResumeGate((Executor) task -> {
            task.run();
            ran.add("executed");
        });

        assertThat(gate.submit("s-1", () -> ran.add("task"))).isTrue();

        assertThat(ran).containsExactly("task", "executed");
    }

    @Test
    void given_closed_gate_when_submit_then_rejected() {
        List<String> ran = new ArrayList<>();
        ChatAgentResumeGate gate = new ChatAgentResumeGate((Executor) task -> {
            task.run();
            ran.add("executed");
        });

        gate.close("s-1");

        assertThat(gate.submit("s-1", () -> ran.add("task"))).isFalse();
        assertThat(ran).isEmpty();
    }

    @Test
    void given_reopened_gate_when_submit_then_accepted_again() {
        // deny cap 终止只作用于 run：同会话承接新 run（新对话轮）即复活
        ChatAgentResumeGate gate = new ChatAgentResumeGate((Executor) Runnable::run);
        gate.close("s-1");

        gate.reopen("s-1");

        assertThat(gate.submit("s-1", () -> { })).isTrue();
    }

    @Test
    void given_inflight_resume_when_closed_then_cancelled() throws Exception {
        // 生产缝（异步线程池）：排队/在飞的续跑被 close 取消——不执行
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<String> completed = new ArrayList<>();
        ChatAgentResumeGate gate = new ChatAgentResumeGate();

        gate.submit("s-1", () -> {
            started.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
                completed.add("done");
            }
            catch (InterruptedException e) {
                completed.add("interrupted");
            }
        });
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        gate.close("s-1");
        // 在飞任务被中断（deny cap 终止的取消面）；同会话后续提交被拒
        Thread.sleep(200);
        assertThat(gate.submit("s-1", () -> { })).isFalse();

        gate.destroy();
        assertThat(completed).containsExactly("interrupted");
    }

    @Test
    void given_same_session_tasks_when_submitted_then_run_serially() throws Exception {
        // #59：跨会话并行化的不变量——同会话任务严格串行（第二个须等第一个完成），
        // 否则同一会话两轮续跑并发写同一 AgentState 槽位
        ChatAgentResumeGate gate = new ChatAgentResumeGate();
        try {
            CountDownLatch firstStarted = new CountDownLatch(1);
            CountDownLatch releaseFirst = new CountDownLatch(1);
            CountDownLatch secondDone = new CountDownLatch(1);
            List<String> order = new ArrayList<>();

            gate.submit("s-1", () -> {
                firstStarted.countDown();
                try {
                    releaseFirst.await(5, TimeUnit.SECONDS);
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                order.add("first");
            });
            gate.submit("s-1", () -> {
                order.add("second");
                secondDone.countDown();
            });

            assertThat(firstStarted.await(5, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(200); // 留出「第二个违规抢跑」的窗口——它应仍在队里
            assertThat(order).isEmpty();
            releaseFirst.countDown();

            assertThat(secondDone.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(order).containsExactly("first", "second");
        }
        finally {
            gate.destroy();
        }
    }

    @Test
    void given_different_sessions_when_submitted_then_run_in_parallel() throws Exception {
        // #59：跨会话并行——B 会话任务在 A 会话任务阻塞期间完成，不再全局排队
        ChatAgentResumeGate gate = new ChatAgentResumeGate();
        try {
            String sessionA = "ses-parallel";
            String sessionB = peerOnOtherStripe(sessionA);
            CountDownLatch aStarted = new CountDownLatch(1);
            CountDownLatch releaseA = new CountDownLatch(1);
            CountDownLatch bDone = new CountDownLatch(1);

            gate.submit(sessionA, () -> {
                aStarted.countDown();
                try {
                    releaseA.await(5, TimeUnit.SECONDS);
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            gate.submit(sessionB, bDone::countDown);

            assertThat(aStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(bDone.await(5, TimeUnit.SECONDS)).isTrue();
            releaseA.countDown();
        }
        finally {
            gate.destroy();
        }
    }

    @Test
    void given_queued_task_when_close_lands_after_predecessor_done_then_dropped() throws Exception {
        // inFlight 只存最新 Future：前任务完成即移走排队任务的登记，close 随后落空——
        // 排队任务是否执行以「起跑时」的闸状态为准（提交时过关 ≠ 起跑时过关）
        ChatAgentResumeGate gate = new ChatAgentResumeGate();
        try {
            CountDownLatch firstStarted = new CountDownLatch(1);
            CountDownLatch releaseFirst = new CountDownLatch(1);
            CountDownLatch firstDone = new CountDownLatch(1);
            List<String> ran = new ArrayList<>();

            gate.submit("s-1", () -> {
                firstStarted.countDown();
                try {
                    releaseFirst.await(5, TimeUnit.SECONDS);
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                firstDone.countDown();
            });
            gate.submit("s-1", () -> ran.add("second"));

            assertThat(firstStarted.await(5, TimeUnit.SECONDS)).isTrue();
            releaseFirst.countDown();
            firstDone.await(5, TimeUnit.SECONDS); // 等前任务（含在飞登记移除）收尾
            gate.close("s-1"); // 落在登记已被移走之后——cancel 无的放矢

            Thread.sleep(200); // 给排队任务留出「违规起跑」的窗口
            assertThat(ran).isEmpty();
        }
        finally {
            gate.destroy();
        }
    }

    /** 挑一个与 base 落不同 stripe 的对端会话 id（同 stripe 会串行——那测不出并行）。 */
    private static String peerOnOtherStripe(String base) {
        for (int i = 0; i < 1000; i++) {
            String candidate = base + "-peer-" + i;
            if (ChatAgentResumeGate.stripeIndex(candidate) != ChatAgentResumeGate.stripeIndex(base)) {
                return candidate;
            }
        }
        throw new IllegalStateException("找不到不同 stripe 的对端会话 id");
    }
}
