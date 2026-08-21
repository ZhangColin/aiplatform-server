package com.aieducenter.aiplatform.base.knowledge;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.aieducenter.aiplatform.base.knowledge.domain.model.KnowledgeHit;
import com.aieducenter.aiplatform.base.knowledge.domain.model.KnowledgeSpec;
import com.aieducenter.aiplatform.base.knowledge.domain.port.KnowledgePort;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 知识语义命中验收（票 #17：分块入库 → retrieve 语义命中）：真 embedding 服务
 * （:9091）+ 真库全链路——bge-small-zh 的真实向量相似度，非 mock 构造。
 *
 * <p>服务未启动时整类跳过（Assumption，不失败）：本类是语义验收的增量，
 * 入库/检索/清理的编排与 SQL 行为在 {@code KnowledgeAppServiceTest}（mock embed）
 * 已覆盖。启动方式见 docs/guide/本机依赖启动.md。</p>
 */
@SpringBootTest
class KnowledgePortSemanticTest {

    private static final String EMBED_HEALTH_URL = "http://127.0.0.1:9091/health";

    @Autowired
    private KnowledgePort knowledgePort;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void requireEmbeddingService() throws Exception {
        HttpResponse<String> health;
        try {
            health = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(EMBED_HEALTH_URL)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            health = null;
        }
        Assumptions.assumeTrue(health != null && health.statusCode() == 200,
                "embedding 服务未启动（" + EMBED_HEALTH_URL + "），跳过语义命中验收");
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM knw_chunks");
    }

    @Test
    void given_indexed_topical_chunks_when_retrieve_then_related_chunk_ranked_first() {
        knowledgePort.index(new KnowledgeSpec("ARTIFACT", "p1:BA:PRD.md", "p1", "电商系统",
                "PRD.md",
                List.of("用户登录模块采用手机号验证码登录，密码使用 bcrypt 加密存储。",
                        "订单支付支持支付宝与微信支付，支付回调需验签。"),
                null));
        knowledgePort.index(new KnowledgeSpec("BUG", "bug-9", "p2", "物流系统", "备份失败",
                List.of("数据库每日全量备份任务在凌晨三点执行，失败后重试两次并告警。"),
                null));

        List<KnowledgeHit> hits = knowledgePort.retrieve("密码应该怎么加密存储", 5);

        // 语义命中：与 query 同题的块排首位（跨项目全局检索，另一项目的 Bug 块不入首位）
        assertThat(hits).isNotEmpty();
        KnowledgeHit first = hits.get(0);
        assertThat(first.chunk()).contains("bcrypt");
        assertThat(first.kind()).isEqualTo("ARTIFACT");
        assertThat(first.sourceProjectName()).isEqualTo("电商系统");
        assertThat(first.title()).isEqualTo("PRD.md");
        // topK 生效
        assertThat(knowledgePort.retrieve("密码应该怎么加密存储", 1)).hasSize(1);
    }

    @Test
    void given_english_query_when_retrieve_then_translation_invariant_hit() {
        // bge-small-zh 对中英混排的鲁棒性（agent 任务 prompt 常为英文）
        knowledgePort.index(new KnowledgeSpec("QA", "wait-7", "p1", "电商系统", "问答纪要",
                List.of("问：接口超时怎么处理？\n答：网关侧设置 10 秒超时，超时返回 504 并记录审计日志。"),
                null));

        List<KnowledgeHit> hits = knowledgePort.retrieve("gateway timeout handling", 5);

        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).chunk()).contains("超时");
    }
}
