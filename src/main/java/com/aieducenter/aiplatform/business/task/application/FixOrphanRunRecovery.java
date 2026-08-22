package com.aieducenter.aiplatform.business.task.application;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * 孤儿修复 run 重启恢复（A4 §4，#27）：链是进程内 sink 回调（不建 campaign 表，
 * 底座无 run 状态落库），进程重启则链必已死——启动时扫描 fix_run_id 非空 ∧
 * OPEN 的 Bug 置 NULL 回可派发池（下次触发点重派；重跑一次修复 = 无害冗余）。
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
