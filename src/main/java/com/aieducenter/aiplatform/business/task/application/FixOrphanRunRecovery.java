package com.aieducenter.aiplatform.business.task.application;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * 孤儿修复 run 重启恢复（A4 §4，#27）：链是进程内 sink 回调（不建 campaign 表，
 * 底座无 run 状态落库），本进程重启则本进程的链必已死——启动时扫描 fix_run_id
 * 非空 ∧ OPEN 的 Bug，仅宽限外的陈旧标记置 NULL 回可派发池（#36：新鲜标记可能
 * 是共享库他实例的在飞 run；本进程死链残留的新鲜标记由链前进步期满回收）。
 * 重跑一次修复 = 无害冗余。
 */
@Component
@Slf4j
public class FixOrphanRunRecovery implements ApplicationRunner {

    private final FixDispatchAppService fixDispatchAppService;

    public FixOrphanRunRecovery(FixDispatchAppService fixDispatchAppService) {
        this.fixDispatchAppService = fixDispatchAppService;
    }

    @Override
    public void run(ApplicationArguments args) {
        fixDispatchAppService.recoverOrphanedRuns();
    }
}
