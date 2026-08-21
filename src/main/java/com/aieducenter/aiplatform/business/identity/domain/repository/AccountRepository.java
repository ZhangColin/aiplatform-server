package com.aieducenter.aiplatform.business.identity.domain.repository;

import java.util.Optional;

import com.cartisan.data.jpa.repository.BaseRepository;

import com.aieducenter.aiplatform.business.identity.domain.aggregate.Account;

/**
 * 账号仓储（{@code idn_accounts}）。按外部 ID（OIDC sub）寻址——callback upsert 的查询面。
 */
public interface AccountRepository extends BaseRepository<Account, Long> {

    Optional<Account> findByExternalId(String externalId);
}
