package com.knowflow.backend.chat.service;

import com.knowflow.backend.document.dto.response.SearchResultResponse;
import com.knowflow.backend.document.entity.Document;
import com.knowflow.backend.document.rag.DocumentRetrievalService;
import com.knowflow.backend.document.repository.DocumentChunkRepository;
import com.knowflow.backend.document.repository.DocumentRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.ArrayList;
import java.util.regex.Pattern;

/**
 * @Desc 知识库上下文服务
 */
@Service
public class ChatDocumentContextService {
    private static final Pattern EXTENSION_PATTERN = Pattern.compile("\\.(docx|doc|pdf|txt|md|markdown|html|htm)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern COPY_SUFFIX_PATTERN = Pattern.compile("\\(\\d+\\)$");
    private static final Pattern LEADING_META_PATTERN = Pattern.compile("^(\\d{6,}|[a-z0-9-]{6,})");
    private static final int MIN_TITLE_MATCH_LENGTH = 6;

    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository documentChunkRepository;
    private final DocumentRetrievalService documentRetrievalService;

    public ChatDocumentContextService(
            DocumentRepository documentRepository,
            DocumentChunkRepository documentChunkRepository,
            DocumentRetrievalService documentRetrievalService) {
        this.documentRepository = documentRepository;
        this.documentChunkRepository = documentChunkRepository;
        this.documentRetrievalService = documentRetrievalService;
    }

    /**
     * @Desc 检索知识库上下文
     * @param knowledgeBaseId
     * @param userId
     * @param question
     * @param mentionedDocumentIds
     * @param limit
     * @return
     */
    public List<SearchResultResponse> resolveContextChunks(
            Long knowledgeBaseId,
            Long userId,
            String question,
            List<Long> mentionedDocumentIds,
            Integer limit
    ) {
        List<Long> explicitDocumentIds = normalizeMentionedDocumentIds(mentionedDocumentIds);
        if (!explicitDocumentIds.isEmpty()) {
            ensureDocumentsAccessible(knowledgeBaseId, userId, explicitDocumentIds);
            return resolveRankedOrSampled(knowledgeBaseId, userId, question, explicitDocumentIds, limit);
        }

        List<Long> titleMatchedDocumentIds = findTitleMatchedDocumentIds(knowledgeBaseId, userId, question);
        if (!titleMatchedDocumentIds.isEmpty()) {
            return resolveRankedOrSampled(knowledgeBaseId, userId, question, titleMatchedDocumentIds, limit);
        }

        return documentRetrievalService.search(knowledgeBaseId, userId, question, limit);
    }

    /**
     * @Desc 指定文档后优先按问题相关度排序；无相关信号（例如 FULLTEXT 模式且问题不含正文关键词）时
     * 回退到文档内均匀采样，避免长文档只返回开头 chunk。
     */
    private List<SearchResultResponse> resolveRankedOrSampled(
            Long knowledgeBaseId,
            Long userId,
            String question,
            List<Long> documentIds,
            Integer limit
    ) {
        List<SearchResultResponse> ranked = documentRetrievalService.searchByDocumentIds(
                knowledgeBaseId, userId, question, documentIds, limit);
        if (!ranked.isEmpty()) {
            return ranked;
        }
        return documentChunkRepository.findSampledIndexedChunksByDocumentIds(
                knowledgeBaseId,
                userId,
                DocumentRetrievalService.toBigIntArrayLiteral(documentIds),
                limit);
    }

    /**
     * @Desc 归一化提及的文档ID
     * @param mentionedDocumentIds
     * @return
     */
    private List<Long> normalizeMentionedDocumentIds(List<Long> mentionedDocumentIds) {
        if (mentionedDocumentIds == null || mentionedDocumentIds.isEmpty()) {
            return List.of();
        }
        Set<Long> ids = new LinkedHashSet<>();
        for (Long id : mentionedDocumentIds) {
            if (id != null && id > 0) {
                ids.add(id);
            }
        }
        return ids.stream().limit(10).toList();
    }

    /**
     * @Desc 确保文档可访问
     * @param knowledgeBaseId
     * @param userId
     * @param documentIds
     */
    private void ensureDocumentsAccessible(Long knowledgeBaseId, Long userId, List<Long> documentIds) {
        List<Document> accessibleDocuments = documentRepository.findAccessibleByKnowledgeBaseId(knowledgeBaseId, userId);
        Set<Long> accessibleIds = accessibleDocuments.stream()
                .filter(document -> "INDEXED".equals(document.getStatus()))
                .map(Document::getId)
                .collect(java.util.stream.Collectors.toSet());
        if (!accessibleIds.containsAll(documentIds)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found");
        }
    }

    /**
     * @Desc 查找标题匹配的文档ID
     * @param knowledgeBaseId
     * @param userId
     * @param question
     * @return
     */
    private List<Long> findTitleMatchedDocumentIds(Long knowledgeBaseId, Long userId, String question) {
        String normalizedQuestion = normalizeTitleText(question);
        if (normalizedQuestion.length() < MIN_TITLE_MATCH_LENGTH) {
            return List.of();
        }
        return documentRepository.findAccessibleByKnowledgeBaseId(knowledgeBaseId, userId)
                .stream()
                .filter(document -> "INDEXED".equals(document.getStatus()))
                .filter(document -> titleMatches(normalizedQuestion, document.getOriginalFilename()))
                .map(Document::getId)
                .limit(5)
                .toList();
    }

    /**
     * @Desc 匹配标题是否与用户问题匹配
     * @param normalizedQuestion
     * @param originalFilename
     * @return
     */
    private boolean titleMatches(String normalizedQuestion, String originalFilename) {
        for (String normalizedTitle : normalizedTitleCandidates(originalFilename)) {
            if (normalizedTitle.length() >= MIN_TITLE_MATCH_LENGTH
                    && (normalizedQuestion.contains(normalizedTitle) || normalizedTitle.contains(normalizedQuestion))) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param originalFilename 上传时保存的原始文件名
     * @return 可用于匹配的完整标题和分隔片段标题
     * @Desc 很多课程文件会带学号、姓名、复制编号等前缀，例如“202502_张三_《实验》报告 (2).docx”。
     * 用户通常只说核心标题，所以除了完整规范化标题，还要把下划线/连字符后的片段作为候选。
     */
    private List<String> normalizedTitleCandidates(String originalFilename) {
        if (originalFilename == null) {
            return List.of();
        }
        String withoutExtension = EXTENSION_PATTERN.matcher(
                Normalizer.normalize(originalFilename, Normalizer.Form.NFKC)
                        .toLowerCase(Locale.ROOT)
                        .trim()
        ).replaceFirst("");
        withoutExtension = COPY_SUFFIX_PATTERN.matcher(withoutExtension).replaceFirst("");

        List<String> candidates = new ArrayList<>();
        candidates.add(normalizeTitleText(withoutExtension));
        for (String part : withoutExtension.split("[_\\-]+")) {
            candidates.add(normalizeTitleText(part));
        }
        return candidates.stream()
                .filter(candidate -> !candidate.isBlank())
                .distinct()
                .toList();
    }

    /**
     * @Desc 归一化标题文本
     * @param value
     * @return
     */
    private String normalizeTitleText(String value) {
        if (value == null) {
            return "";
        }
        String text = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .trim();
        text = EXTENSION_PATTERN.matcher(text).replaceFirst("");
        text = COPY_SUFFIX_PATTERN.matcher(text).replaceFirst("");
        text = text.replaceAll("[《》“”\"'`]", "");
        text = text.replaceAll("[_\\-]+", " ");
        text = LEADING_META_PATTERN.matcher(text).replaceFirst("");
        text = text.replaceAll("^[\\s\\p{Punct}]+", "");
        text = text.replaceAll("[\\s\\p{Punct}]+", "");
        return text;
    }
}
