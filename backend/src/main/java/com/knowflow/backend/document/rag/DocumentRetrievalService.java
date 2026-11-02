package com.knowflow.backend.document.rag;

import com.knowflow.backend.document.dto.response.SearchResultResponse;
import com.knowflow.backend.document.repository.DocumentChunkRepository;
import com.knowflow.backend.settings.entity.UserRagSettings;
import com.knowflow.backend.settings.service.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * Shared retrieval entry point for search APIs and Chat RAG.
 *
 * <p>Older code called PostgreSQL full-text search directly from multiple places. Stage 18
 * centralizes that path so semantic retrieval, score breakdowns, and fallback behavior are
 * identical for `/search` results, Chat prompt context, and saved Chat sources.</p>
 */
@Service
public class DocumentRetrievalService {
    private static final Logger log = LoggerFactory.getLogger(DocumentRetrievalService.class);

    private final DocumentChunkRepository documentChunkRepository;
    private final EmbeddingModelClient embeddingModelClient;
    private final SettingsService settingsService;

    public DocumentRetrievalService(
            DocumentChunkRepository documentChunkRepository,
            EmbeddingModelClient embeddingModelClient,
            SettingsService settingsService) {
        this.documentChunkRepository = documentChunkRepository;
        this.embeddingModelClient = embeddingModelClient;
        this.settingsService = settingsService;
    }

    /**
     * @param knowledgeBaseId knowledge base whose chunks may be searched
     * @param userId current JWT user; repository still checks membership to avoid cross-user leakage
     * @param query user search question
     * @param limit maximum rows returned to caller
     * @return ranked chunks with compatible `score` plus Stage 18 score breakdown fields
     */
    public List<SearchResultResponse> search(Long knowledgeBaseId, Long userId, String query, Integer limit) {
        return searchInDocuments(knowledgeBaseId, userId, query, null, limit);
    }

    /**
     * @param knowledgeBaseId knowledge base whose chunks may be searched
     * @param userId current JWT user; repository still checks membership to avoid cross-user leakage
     * @param query user search question
     * @param documentIds explicit documents to restrict retrieval to (e.g. @ mention or title match)
     * @param limit maximum rows returned to caller
     * @return chunks ranked by question relevance within the restricted document set
     * @Desc 修复“指定文档后只取文档开头前 N chunk”的问题：把文档限定与普通检索共用同一套
     * 语义 + 全文排序，而不是按 chunk_index 顺序取头部。
     */
    public List<SearchResultResponse> searchByDocumentIds(
            Long knowledgeBaseId,
            Long userId,
            String query,
            List<Long> documentIds,
            Integer limit) {
        return searchInDocuments(knowledgeBaseId, userId, query, toBigIntArrayLiteral(documentIds), limit);
    }

    private List<SearchResultResponse> searchInDocuments(
            Long knowledgeBaseId,
            Long userId,
            String query,
            String documentIdsLiteral,
            Integer limit) {
        UserRagSettings settings = settingsService.getEffectiveRagSettings(userId);
        if ("FULLTEXT".equals(settings.getRetrievalMode())) {
            // FULLTEXT mode deliberately avoids embedding calls. This gives users a predictable
            // low-cost fallback while preserving member-scoped PostgreSQL ranking.
            return documentChunkRepository.searchIndexedChunks(knowledgeBaseId, userId, query, documentIdsLiteral, limit);
        }
        if (!embeddingModelClient.isConfigured(userId)) {
            return documentChunkRepository.searchIndexedChunks(knowledgeBaseId, userId, query, documentIdsLiteral, limit);
        }
        try {
            List<List<Double>> embeddings = embeddingModelClient.embed(userId, List.of(query));
            if (embeddings.isEmpty()) {
                return documentChunkRepository.searchIndexedChunks(knowledgeBaseId, userId, query, documentIdsLiteral, limit);
            }
            List<SearchResultResponse> hybridResults = documentChunkRepository.searchHybridIndexedChunks(
                    knowledgeBaseId,
                    userId,
                    query,
                    toVectorLiteral(embeddings.getFirst()),
                    documentIdsLiteral,
                    limit,
                    settings.getSemanticWeight(),
                    settings.getFulltextWeight()
            );
            if (hasSemanticSignal(hybridResults)) {
                return hybridResults;
            }
            // If every candidate has semanticScore 0, the indexed chunks do not have usable
            // vectors for this query. Falling back keeps failed/skipped embedding documents
            // visibly full-text searchable instead of labeling a keyword-only result as HYBRID.
            return documentChunkRepository.searchIndexedChunks(knowledgeBaseId, userId, query, documentIdsLiteral, limit);
        } catch (RuntimeException exception) {
            log.warn("Semantic retrieval failed; falling back to full-text search: userId={}, errorType={}",
                    userId,
                    exception.getClass().getSimpleName());
            return documentChunkRepository.searchIndexedChunks(knowledgeBaseId, userId, query, documentIdsLiteral, limit);
        }
    }

    private boolean hasSemanticSignal(List<SearchResultResponse> results) {
        return results.stream()
                .anyMatch(result -> result.getSemanticScore() != null && result.getSemanticScore() > 0.0);
    }

    /**
     * @param vector embedding returned by the provider
     * @return pgvector literal such as [0.1,0.2]. Values are formatted with Locale.ROOT
     * to avoid comma decimal separators on non-English systems.
     */
    public static String toVectorLiteral(List<Double> vector) {
        if (vector == null || vector.isEmpty()) {
            throw new IllegalArgumentException("embedding vector is empty");
        }
        String joined = vector.stream()
                .map(value -> String.format(Locale.ROOT, "%.10f", value))
                .toList()
                .stream()
                .reduce((left, right) -> left + "," + right)
                .orElse("");
        return "[" + joined + "]";
    }

    /**
     * @param ids 非空文档 ID 列表；为空时返回空数组字面量
     * @return PostgreSQL bigint[] 字面量，例如 {1,2,3}
     * @Desc 与 toVectorLiteral 相同，用 Locale 无关的纯数字拼接，配合 SQL 里的 #{...}::bigint[] 使用，
     * 避免在 @Select 文本块里写 <foreach> 而触发 <=> 等操作符的 XML 转义问题。
     */
    public static String toBigIntArrayLiteral(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return "{}";
        }
        String joined = ids.stream()
                .map(String::valueOf)
                .reduce((left, right) -> left + "," + right)
                .orElse("");
        return "{" + joined + "}";
    }
}
