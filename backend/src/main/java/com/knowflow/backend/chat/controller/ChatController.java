package com.knowflow.backend.chat.controller;

import java.util.List;

import com.knowflow.backend.chat.dto.request.UpdateChatSessionRequest;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import com.knowflow.backend.chat.dto.request.CreateChatSessionRequest;
import com.knowflow.backend.chat.dto.request.SendMessageRequest;
import com.knowflow.backend.chat.dto.response.ChatMessageResponse;
import com.knowflow.backend.chat.dto.response.ChatSessionResponse;
import com.knowflow.backend.chat.dto.response.ChatUsageTodayResponse;
import com.knowflow.backend.chat.dto.response.SendMessageResponse;
import com.knowflow.backend.chat.service.ChatService;
import com.knowflow.backend.chat.service.ChatStreamingService;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static com.knowflow.backend.common.utils.AuthUtils.getCurrentUserId;

/**
 * @class 聊天控制器
 */

@RestController
@RequestMapping("/api")
public class ChatController {
    private final ChatService chatService;
    private final ChatStreamingService chatStreamingService;

    public ChatController(ChatService chatService, ChatStreamingService chatStreamingService) {
        this.chatService = chatService;
        this.chatStreamingService = chatStreamingService;
    }

    /**
     * @param knowledgeBaseId
     * @param request
     * @param jwt
     * @return
     * @Desc 创建聊天会话
     */
    @PostMapping("/knowledge-bases/{knowledgeBaseId}/chat/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    public ChatSessionResponse createSession(
            @PathVariable Long knowledgeBaseId,
            @RequestBody(required = false) CreateChatSessionRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return chatService.createSession(knowledgeBaseId, getCurrentUserId(jwt), request);
    }

    /**
     * @param knowledgeBaseId
     * @param jwt
     * @return
     * @Desc 获取知识库下的所有聊天会话
     */
    @GetMapping("/knowledge-bases/{knowledgeBaseId}/chat/sessions")
    public List<ChatSessionResponse> listSessions(
            @PathVariable Long knowledgeBaseId,
            @AuthenticationPrincipal Jwt jwt) {
        return chatService.listSessions(knowledgeBaseId, getCurrentUserId(jwt));
    }

    /**
     * @param sessionId
     * @param jwt
     * @return
     * @Desc 获取聊天会话下的所有消息
     */
    @GetMapping("/chat/sessions/{sessionId}/messages")
    public List<ChatMessageResponse> listMessages(
            @PathVariable Long sessionId,
            @AuthenticationPrincipal Jwt jwt) {
        return chatService.listMessages(sessionId, getCurrentUserId(jwt));
    }


    /**
     * @param sessionId
     * @param request
     * @param jwt
     * @return
     * @Desc 更新聊天会话
     */
    @PatchMapping("/chat/sessions/{sessionId}")
    public ChatSessionResponse updateSession(
            @PathVariable Long sessionId,
            @RequestBody UpdateChatSessionRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return chatService.updateSession(sessionId, getCurrentUserId(jwt), request);
    }

    /**
     * @param sessionId
     * @param jwt
     * @Desc 删除聊天会话
     */
    @DeleteMapping("/chat/sessions/{sessionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSession(
            @PathVariable Long sessionId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        chatService.deleteSession(sessionId, getCurrentUserId(jwt));
    }


    /**
     * @param sessionId
     * @param request
     * @param jwt
     * @return
     * @Desc 发送消息到聊天会话下的消息
     */
    @PostMapping("/chat/sessions/{sessionId}/messages")
    public SendMessageResponse sendMessage(
            @PathVariable Long sessionId,
            @RequestBody SendMessageRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return chatService.sendMessage(sessionId, getCurrentUserId(jwt), request);
    }

    /**
     * @param sessionId 当前用户自己的会话 ID
     * @param request   Chat 发送请求，支持 content/model/ragEnabled/mentionedDocumentIds
     * @param jwt       当前登录用户
     * @return text/event-stream，事件包含 user_message、assistant_message、delta、sources、done、error
     * @Desc 阶段 21 新增 POST SSE 流式接口。因为前端需要携带 JWT header 和 JSON body，
     * 这里使用 POST + SseEmitter，而不是只能 GET 的 EventSource 契约。
     */
    @PostMapping(value = "/chat/sessions/{sessionId}/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamMessage(
            @PathVariable Long sessionId,
            @RequestBody SendMessageRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return chatStreamingService.streamMessage(sessionId, getCurrentUserId(jwt), request);
    }

    /**
     * @param sessionId
     * @param jwt
     * @return
     * @Desc 取消聊天会话下的消息生成
     */
    @PostMapping("/chat/sessions/{sessionId}/cancel")
    public ChatSessionResponse cancelGeneration(
            @PathVariable Long sessionId,
            @AuthenticationPrincipal Jwt jwt) {
        return chatService.cancelGeneration(sessionId, getCurrentUserId(jwt));
    }

    /**
     * @param timezone
     * @Desc 获取聊天会话下的消息生成统计
     */
    @GetMapping("/chat/usage/today")
    public ChatUsageTodayResponse getTodayUsage(
            @RequestParam(required = false) String timezone,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return chatService.getTodayUsage(getCurrentUserId(jwt), timezone);
    }
}
