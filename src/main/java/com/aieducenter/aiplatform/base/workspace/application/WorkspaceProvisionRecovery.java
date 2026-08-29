package com.aieducenter.aiplatform.base.workspace.application;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 置备重启自愈（#64）：进程重启后对遗留的 PROVISIONING 工作区续置备或标 failed
 * 收口，不静默悬置。启动扫描只在上下文就绪后跑一次，续置备本身转
 * {@link WorkspaceProvisionAppService#recoverPendingProvisions()}——docker
 * createWorkspace 幂等预清同名残留，续置备安全；失败由置备器既有重试→markFailed
 * 收敛（「续置备或标 failed」自然覆盖）。
 */
@Component
public class WorkspaceProvisionRecovery implements ApplicationRunner {

    private final WorkspaceProvisionAppService provisioner;

    public WorkspaceProvisionRecovery(WorkspaceProvisionAppService provisioner) {
        this.provisioner = provisioner;
    }

    @Override
    public void run(ApplicationArguments args) {
        provisioner.recoverPendingProvisions();
    }
}
