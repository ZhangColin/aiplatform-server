package com.aieducenter.aiplatform.base.knowledge.domain.port;

import java.util.List;

import com.cartisan.core.stereotype.Port;
import com.cartisan.core.stereotype.PortType;

/**
 * 向量化端口（driven）：本机 fastembed 服务（BAAI/bge-small-zh-v1.5，512 维，
 * {@code scripts/embedding_server.py}）的抽象——换 embedding 提供方只换适配器。
 *
 * <p>降级契约：服务不可用（连不上/非 200/响应异常）一律返回<b>空列表</b>、不抛错，
 * 由调用方降级（B0 蓝图 §2 片3 口径）。</p>
 */
@Port(PortType.CLIENT)
public interface EmbeddingClient {

    /**
     * 批量向量化：返回向量与入参文本同序同量；服务不可用返回空列表。
     */
    List<float[]> embed(List<String> texts);
}
