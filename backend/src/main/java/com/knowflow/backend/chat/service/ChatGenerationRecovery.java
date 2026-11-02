package com.knowflow.backend.chat.service;

import com.knowflow.backend.chat.repository.ChatSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 启动时兜底清理“陈旧生成中”会话。
 *
 * <p>beginGeneration 增加了并发守卫后，如果后端在 GENERATING 期间崩溃/重启，会留下无法再发送的
 * 死会话。这里在应用就绪后把超过 10 分钟未更新的 GENERATING 会话置回 IDLE，保证用户能重新提问。
 * 该清理是幂等的，正常流程中 10 分钟已超过 SSE 超时，不会误伤正在进行的健康生成。</p>
 */
@Component
public class ChatGenerationRecovery {
    private static final Logger log = LoggerFactory.getLogger(ChatGenerationRecovery.class);

    private final ChatSessionRepository chatSessionRepository;

    public ChatGenerationRecovery(ChatSessionRepository chatSessionRepository) {
        this.chatSessionRepository = chatSessionRepository;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void resetStaleGenerations() {
        try {
            int reset = chatSessionRepository.resetStaleGenerations();
            if (reset > 0) {
                log.info("Reset {} stale GENERATING chat session(s) to IDLE after startup", reset);
            }
        } catch (RuntimeException exception) {
            log.warn("Failed to reset stale chat generations: errorType={}",
                    exception.getClass().getSimpleName());
        }
    }
}
