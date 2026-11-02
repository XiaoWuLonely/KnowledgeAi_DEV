package com.knowflow.backend.chat.service;

import com.knowflow.backend.chat.dto.request.SendMessageRequest;
import com.knowflow.backend.chat.dto.response.ChatMessageResponse;
import com.knowflow.backend.chat.dto.response.ChatSessionResponse;
import com.knowflow.backend.chat.dto.response.ChatSourceResponse;
import com.knowflow.backend.chat.dto.response.ChatStreamDeltaResponse;
import com.knowflow.backend.chat.dto.response.ChatStreamDoneResponse;
import com.knowflow.backend.chat.dto.response.ChatStreamErrorResponse;
import com.knowflow.backend.chat.entity.ChatMessage;
import com.knowflow.backend.chat.entity.ChatMessageSource;
import com.knowflow.backend.chat.entity.ChatSession;
import com.knowflow.backend.chat.model.ChatModelClient;
import com.knowflow.backend.chat.model.PromptBuilder;
import com.knowflow.backend.chat.repository.ChatMessageRepository;
import com.knowflow.backend.chat.repository.ChatMessageSourceRepository;
import com.knowflow.backend.chat.repository.ChatSessionRepository;
import com.knowflow.backend.document.dto.response.SearchResultResponse;
import com.knowflow.backend.knowledgebase.service.KnowledgeBaseAccessService;
import com.knowflow.backend.settings.entity.UserRagSettings;
import com.knowflow.backend.settings.service.SettingsService;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import static com.knowflow.backend.common.model.ModelProviderErrors.safeMessage;

/**
 * 聊天流服务
 *
 * @Desc 处理聊天流相关的操作，如发送消息、接收消息、处理错误等。
 */

@Service
public class ChatStreamingService {
    private static final long SSE_TIMEOUT_MS = 10 * 60 * 1000L;
    private static final int DEFAULT_LIMIT = 5;
    private static final int MAX_LIMIT = 20;
    private static final int MAX_QUESTION_CHARS = 4000;

    private final ChatSessionRepository chatSessionRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ChatMessageSourceRepository chatMessageSourceRepository;
    private final KnowledgeBaseAccessService accessService;
    private final SettingsService settingsService;
    private final ChatDocumentContextService chatDocumentContextService;
    private final PromptBuilder promptBuilder;
    private final ChatModelClient chatModelClient;
    private final ChatService chatService;
    private final TransactionTemplate transactionTemplate;
    private final TaskExecutor taskExecutor;

    public ChatStreamingService(
            ChatSessionRepository chatSessionRepository,
            ChatMessageRepository chatMessageRepository,
            ChatMessageSourceRepository chatMessageSourceRepository,
            KnowledgeBaseAccessService accessService,
            SettingsService settingsService,
            ChatDocumentContextService chatDocumentContextService,
            PromptBuilder promptBuilder,
            ChatModelClient chatModelClient,
            ChatService chatService,
            PlatformTransactionManager transactionManager,
            TaskExecutor taskExecutor) {
        this.chatSessionRepository = chatSessionRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.chatMessageSourceRepository = chatMessageSourceRepository;
        this.accessService = accessService;
        this.settingsService = settingsService;
        this.chatDocumentContextService = chatDocumentContextService;
        this.promptBuilder = promptBuilder;
        this.chatModelClient = chatModelClient;
        this.chatService = chatService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.taskExecutor = taskExecutor;
    }

    /**
     * @param sessionId Chat session ID
     * @param userId    当前 JWT 用户 ID
     * @param request   用户本轮发送请求
     * @return SSE emitter，事件包括 session、user_message、assistant_message、delta、sources、done、error
     * @Desc 阶段 21 流式接口采用 POST + SseEmitter。它和非流式接口共用权限、RAG、模型配置和 activeGenerationId，
     * 但会在模型 delta 到达时持续更新同一条 ASSISTANT 消息，刷新页面可恢复已生成内容。
     */
    public SseEmitter streamMessage(Long sessionId, Long userId, SendMessageRequest request) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        ChatSession session = getSessionOr404(sessionId, userId);

        // 检查用户是否有权限访问知识库
        accessService.requireMember(session.getKnowledgeBaseId(), userId);
        String question = normalizeContent(request == null ? null : request.getContent());
        String requestedModel = normalizeOptionalModel(request == null ? null : request.getModel());
        boolean ragEnabled = request == null || request.getRagEnabled() == null || request.getRagEnabled();
        int requestedLimit = normalizeLimit(request == null ? null : request.getLimit());
        if (requestedModel != null && settingsService.hasCompleteUserModelSettings(userId)) {
            settingsService.updateCurrentModelFromChat(userId, requestedModel);
        }

        // 加载历史消息
        List<ChatMessage> historyMessages = chatService.loadPromptHistoryForStreaming(session, userId);
        String generationId = UUID.randomUUID().toString();

        // 插入用户消息
        ChatMessage userMessage = transactionTemplate.execute(status -> {
            ChatMessage message = new ChatMessage();
            message.setSessionId(session.getId());
            message.setRole("USER");
            message.setContent(question);
            chatMessageRepository.insert(message);
            // 受控更新：会话已在生成中时返回 0 行，抛 409 回滚本次 USER 消息。
            int generationRows = chatSessionRepository.beginGeneration(session.getId(), userId, generationId);
            if (generationRows != 1) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "该会话正在生成回答，请等待完成或先打断");
            }
            return message;
        });

        // 发送用户消息和会话信息
        send(emitter, "user_message", new ChatMessageResponse(userMessage, List.of()));
        send(emitter, "session", new ChatSessionResponse(getSessionOr404(session.getId(), userId)));

        // 提交到 Spring 管理的线程池执行，避免每个流式请求都创建一个裸线程。
        taskExecutor.execute(
                () -> runStream(emitter, session, userId, question, historyMessages, requestedModel, ragEnabled,
                        requestedLimit,
                        request == null ? null : request.getMentionedDocumentIds(), generationId));
        emitter.onCompletion(() -> cancelIfStillGenerating(sessionId, userId, generationId));
        emitter.onTimeout(() -> cancelIfStillGenerating(sessionId, userId, generationId));
        emitter.onError(error -> cancelIfStillGenerating(sessionId, userId, generationId));
        return emitter;
    }

    /**
     * @param emitter
     * @param session
     * @param userId
     * @param question
     * @param historyMessages
     * @param requestedModel
     * @param ragEnabled
     * @param requestedLimit
     * @param mentionedDocumentIds
     * @param generationId
     * @Desc 运行聊天流
     */
    private void runStream(
            SseEmitter emitter,
            ChatSession session,
            Long userId,
            String question,
            List<ChatMessage> historyMessages,
            String requestedModel,
            boolean ragEnabled,
            int requestedLimit,
            List<Long> mentionedDocumentIds,
            String generationId
    ) {
        try {
            UserRagSettings ragSettings = settingsService.getEffectiveRagSettings(userId);
            List<SearchResultResponse> retrievedChunks = ragEnabled
                    ? chatDocumentContextService.resolveContextChunks(
                    session.getKnowledgeBaseId(),
                    userId,
                    question,
                    mentionedDocumentIds,
                    requestedLimit
            ) : List.of();

            // 限制上下文块数量
            List<SearchResultResponse> contextChunks = limitContextChunks(
                    retrievedChunks,
                    Math.min(requestedLimit, ragSettings.getMaxContextChunks())
            );
            String prompt = contextChunks.isEmpty()
                    ? promptBuilder.buildWithoutSources(question, historyMessages)// 没有可引用知识库片段时使用的 prompt
                    : promptBuilder.build(question, contextChunks, historyMessages);// 有可引用知识库片段时使用的 prompt
            // 启动聊天模型流
            StreamingAccumulator accumulator = new StreamingAccumulator(emitter, session.getId(), userId, generationId);
            chatModelClient.stream(userId, prompt, ragSettings.getTemperature(), requestedModel, accumulator::append);
            ChatMessageResponse finalMessage = accumulator.finish(contextChunks);// 完成聊天流
            ChatSessionResponse finalSession = new ChatSessionResponse(getSessionOr404(session.getId(), userId));// 获取最终会话信息
            // 发送最终消息和会话信息
            send(emitter, "sources", finalMessage.getSources());
            send(emitter, "done", new ChatStreamDoneResponse(finalMessage, finalSession));
            emitter.complete();

        } catch (RuntimeException exception) {
            if (isConnectionClosed(exception)) {
                // 客户端断开/刷新只释放生成状态、保留 partial，不能误标 FAILED；也不推 error（连接已不存在）。
                chatSessionRepository.clearGenerationIfActive(session.getId(), userId, generationId);
                return;
            }
            if (!chatSessionRepository.isActiveGeneration(session.getId(), userId, generationId)) {
                // 用户已主动打断，cancelGeneration 已清理状态和 partial，这里什么都不做。
                return;
            }
            String safeMessage = safeMessage(exception);
            transactionTemplate.executeWithoutResult(status ->
                    chatSessionRepository.finishGeneration(session.getId(), userId, generationId, "FAILED", safeMessage, true)
            );
            send(emitter, "error", new ChatStreamErrorResponse(safeMessage));
            emitter.complete();
        }
    }

    /**
     * @param exception 流式链路抛出的异常
     * @return true 表示失败源于 SSE 连接被客户端关闭，而不是模型调用/落库失败
     * @Desc 连接关闭不应把会话标成 FAILED，也不应落库“AI model call failed”这类误导性错误。
     */
    private boolean isConnectionClosed(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof ResponseStatusException responseStatusException
                    && "SSE connection closed".equals(responseStatusException.getReason())) {
                return true;
            }
            if (current instanceof IOException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * @Desc 聊天流累加器
     * emitter
     * sessionId
     * userId
     * generationId
     */
    private final class StreamingAccumulator {
        private final SseEmitter emitter;
        private final Long sessionId;
        private final Long userId;
        private final String generationId;
        private final StringBuilder content = new StringBuilder();
        private ChatMessage assistantMessage;


        private StreamingAccumulator(SseEmitter emitter, Long sessionId, Long userId, String generationId) {
            this.emitter = emitter;
            this.sessionId = sessionId;
            this.userId = userId;
            this.generationId = generationId;
        }

        /**
         * @Desc 累加聊天流 delta
         * @param delta
         */
        private void append(String delta) {
            // 忽略空 delta
            if (delta == null || delta.isEmpty()) {
                return;
            }
            // 检查生成是否已取消
            if (!chatSessionRepository.isActiveGeneration(sessionId, userId, generationId)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Generation canceled");
            }
            // 累加 delta 到内容
            content.append(delta);
            // 更新助手消息内容
            ChatMessage savedMessage = transactionTemplate.execute(status -> {
                // 检查生成是否已取消
                if (!chatSessionRepository.isActiveGeneration(sessionId, userId, generationId)) {
                    return null;
                }
                // 更新助手消息内容
                ChatMessage message = ensureAssistantMessage();
                chatMessageRepository.updateAssistantContent(message.getId(), sessionId, content.toString());
                return message;
            });
            // 检查是否成功更新助手消息内容
            if (savedMessage == null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Generation canceled");
            }
            send(emitter, "delta", new ChatStreamDeltaResponse(savedMessage.getId(), delta, content.toString()));
        }

        /**
         * @Desc 确保助手消息存在
         * @return
         */
        private ChatMessage ensureAssistantMessage() {
            if (assistantMessage != null) {
                return assistantMessage;
            }
            ChatMessage message = new ChatMessage();
            message.setSessionId(sessionId);
            message.setRole("ASSISTANT");
            message.setContent("");
            message.setGenerationId(generationId);
            // 流式 partial 绑定当前 generationId，用户主动打断时只删除本次未完成助手回答，不影响历史回答。
            chatMessageRepository.insertStreamingAssistant(message);
            assistantMessage = message;
            send(emitter, "assistant_message", new ChatMessageResponse(message, List.of()));
            return message;
        }

        /**
         * @Desc 完成聊天流
         * @param contextChunks
         * @return
         */
        private ChatMessageResponse finish(List<SearchResultResponse> contextChunks) {
            ChatMessage message = transactionTemplate.execute(status -> {
                int updated = chatSessionRepository.finishGeneration(sessionId, userId, generationId, "IDLE", null, true);
                if (updated == 0) {
                    return null;
                }
                ChatMessage savedMessage = ensureAssistantMessage();
                if (!contextChunks.isEmpty()) {
                    saveSources(savedMessage.getId(), contextChunks);
                }
                return savedMessage;
            });
            if (message == null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Generation canceled");
            }
            List<ChatSourceResponse> sources = chatMessageSourceRepository.findAllByMessageId(message.getId())
                    .stream()
                    .map(ChatSourceResponse::new)
                    .toList();
            message.setContent(content.toString());
            return new ChatMessageResponse(message, sources);
        }
    }

    /**
     * @Desc 取消正在生成的聊天流
     * @param sessionId
     * @param userId
     * @param generationId
     */
    private void cancelIfStillGenerating(Long sessionId, Long userId, String generationId) {
        if (chatSessionRepository.isActiveGeneration(sessionId, userId, generationId)) {
            // 连接异常/刷新/超时只释放生成状态，不删除 partial。
            // 这样页面刷新后仍可恢复已生成内容；只有用户显式调用 cancel 接口才清理本次 ASSISTANT partial。
            chatSessionRepository.clearGenerationIfActive(sessionId, userId, generationId);
        }
    }

    /**
     * @Desc 获取聊天会话
     * @param sessionId
     * @param userId
     * @return
     */
    private ChatSession getSessionOr404(Long sessionId, Long userId) {
        return chatSessionRepository.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Chat session not found"));
    }

    /**
     * @Desc 归一化内容
     * @param content
     * @return
     */
    private String normalizeContent(String content) {
        if (content == null || content.trim().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "content is empty");
        }
        String normalized = content.trim();
        if (normalized.length() > MAX_QUESTION_CHARS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "content is too long");
        }
        return normalized;
    }

    /**
     * @Desc 归一化模型
     * @param model
     * @return
     */
    private String normalizeOptionalModel(String model) {
        if (model == null || model.trim().isBlank()) {
            return null;
        }
        return model.trim();
    }

    /**
     * @param limit 请求体中的候选 chunk 数量限制；为空时采用旧接口默认值 5。
     * @return 1 到 20 之间的安全 limit
     * @Desc 流式接口与非流式接口保持相同 limit 语义，避免前端传入 limit 后只在非流式路径生效。
     */
    private int normalizeLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        if (limit < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be >= 1");
        }
        return Math.min(limit, MAX_LIMIT);
    }

    /**
     * @Desc 限制上下 chunk 数量
     * @param chunks
     * @param maxContextChunks
     * @return
     */
    private List<SearchResultResponse> limitContextChunks(List<SearchResultResponse> chunks, int maxContextChunks) {
        if (chunks.size() <= maxContextChunks) {
            return chunks;
        }
        return chunks.stream().limit(maxContextChunks).toList();
    }

    /**
     * @Desc 保存上下文 chunk 到数据库
     * @param messageId
     * @param chunks
     */
    private void saveSources(Long messageId, List<SearchResultResponse> chunks) {
        for (SearchResultResponse chunk : chunks) {
            ChatMessageSource source = new ChatMessageSource();
            source.setMessageId(messageId);
            source.setDocumentId(chunk.getDocumentId());
            source.setDocumentName(chunk.getDocumentName());
            source.setChunkId(chunk.getChunkId());
            source.setChunkIndex(chunk.getChunkIndex());
            source.setContent(chunk.getContent());
            source.setScore(chunk.getScore());
            source.setHybridScore(chunk.getHybridScore());
            source.setFulltextScore(chunk.getFulltextScore());
            source.setSemanticScore(chunk.getSemanticScore());
            source.setRetrievalMode(chunk.getRetrievalMode());
            chatMessageSourceRepository.insert(source);
        }
    }

    /**
     * @Desc 发送 SSE 事件
     * @param emitter
     * @param eventName
     * @param data
     */
    private void send(SseEmitter emitter, String eventName, Object data) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "SSE connection closed");
        }
    }
}
