package com.aieducenter.aiplatform.business.identity.application;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.aieducenter.aiplatform.business.identity.application.dto.response.AccountResponse;
import com.aieducenter.aiplatform.business.identity.domain.aggregate.Account;
import com.aieducenter.aiplatform.business.identity.domain.repository.AccountRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 账号查询（A4 §6 指派下拉源 + task BC 消费面）：全量清单（建档顺序）、
 * 存在性、批量显示名（不存在的 id 不入 Map）。
 */
@SpringBootTest
class AccountAppServiceTest {

    @Autowired
    private AccountAppService appService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM idn_accounts");
    }

    @Test
    void given_accounts_when_list_then_creation_order_with_string_ids() {
        Long first = persistedAccount("sub-1", "开发甲");
        Long second = persistedAccount("sub-2", "测试乙");

        List<AccountResponse> accounts = appService.list();

        assertThat(accounts).hasSize(2);
        assertThat(accounts).extracting(AccountResponse::accountId)
                .containsExactly(first.toString(), second.toString()); // 建档顺序稳定
        assertThat(accounts).extracting(AccountResponse::displayName)
                .containsExactly("开发甲", "测试乙");
    }

    @Test
    void given_accounts_when_exists_then_by_id_only() {
        Long existing = persistedAccount("sub-1", "开发甲");

        assertThat(appService.exists(existing)).isTrue();
        assertThat(appService.exists(-1L)).isFalse();
        assertThat(appService.exists(null)).isFalse(); // task BC 指派校验的入参形态
    }

    @Test
    void given_accounts_when_names_by_ids_then_missing_skipped() {
        Long existing = persistedAccount("sub-1", "开发甲");

        assertThat(appService.namesByIds(List.of(existing, -1L)))
                .containsEntry(existing, "开发甲")
                .hasSize(1); // 不存在的 id 不入 Map
        assertThat(appService.namesByIds(null)).isEmpty();
        assertThat(appService.namesByIds(List.of())).isEmpty();
    }

    private Long persistedAccount(String externalId, String displayName) {
        return accountRepository.save(Account.register(externalId, displayName)).getId();
    }
}
