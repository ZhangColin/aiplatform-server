package com.aieducenter.aiplatform.business.identity.application;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import com.aieducenter.aiplatform.business.identity.application.dto.response.AccountResponse;
import com.aieducenter.aiplatform.business.identity.domain.aggregate.Account;
import com.aieducenter.aiplatform.business.identity.domain.repository.AccountRepository;

/**
 * 账号查询用例（A4 §6 指派下拉的源）：全量账号清单（v1 无成员页，任务指派
 * 下拉是首个消费方）+ 跨上下文校验/显示名批查（business.task 指派校验与
 * 任务面板渲染）。量小不分页、建档顺序稳定。
 */
@Service
public class AccountAppService {

    private final AccountRepository accountRepository;

    public AccountAppService(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    /** 全量账号（建档顺序）——指派下拉。 */
    public List<AccountResponse> list() {
        return accountRepository.findAll(Sort.by(Sort.Direction.ASC, "createdAt")).stream()
                .map(AccountAppService::toResponse)
                .toList();
    }

    /** 账号存在性（task BC 建任务指派校验）。 */
    public boolean exists(Long accountId) {
        return accountId != null && accountRepository.existsById(accountId);
    }

    /** 批量显示名（task 面板渲染；不存在的 id 不入 Map）。 */
    public Map<Long, String> namesByIds(Collection<Long> accountIds) {
        if (accountIds == null || accountIds.isEmpty()) {
            return Map.of();
        }
        return accountRepository.findAllById(accountIds).stream()
                .collect(Collectors.toMap(Account::getId, Account::getDisplayName));
    }

    private static AccountResponse toResponse(Account account) {
        return new AccountResponse(account.getId().toString(), account.getDisplayName());
    }
}
