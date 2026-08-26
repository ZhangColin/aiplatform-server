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
 * {@link ChatAgentResumeGate}（#48）：提交/关闸/复活口径——deny cap 终止后提交被拒
 * （排队中的续跑被丢弃）、同会话新 run 承接即复活、生产缝（线程池）在飞任务被
 * close 取消。
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
}
