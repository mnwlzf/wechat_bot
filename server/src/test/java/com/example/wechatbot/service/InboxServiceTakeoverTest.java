package com.example.wechatbot.service;

import com.example.wechatbot.domain.BotConfig;
import com.example.wechatbot.domain.ChatType;
import com.example.wechatbot.domain.Conversation;
import com.example.wechatbot.domain.MessageLog;
import com.example.wechatbot.repository.BotConfigRepository;
import com.example.wechatbot.repository.ConversationRepository;
import com.example.wechatbot.repository.MessageLogRepository;
import com.example.wechatbot.web.dto.AgentDtos;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class InboxServiceTakeoverTest {
    private final BotConfigRepository botRepository = mock(BotConfigRepository.class);
    private final ConversationRepository conversationRepository = mock(ConversationRepository.class);
    private final MessageLogRepository messageRepository = mock(MessageLogRepository.class);
    private final DedupeService dedupeService = mock(DedupeService.class);
    private final RateLimitService rateLimitService = mock(RateLimitService.class);
    private final ChatOrchestrator chatOrchestrator = mock(ChatOrchestrator.class);
    private final RuntimeSettingsService settings = mock(RuntimeSettingsService.class);

    private final InboxService service = new InboxService(
            botRepository, conversationRepository, messageRepository,
            dedupeService, rateLimitService, chatOrchestrator, settings);

    private BotConfig bot() {
        BotConfig bot = new BotConfig();
        bot.setId(UUID.randomUUID());
        bot.setName("小助手");
        bot.setEnabled(true);
        bot.setReplyPrivate(true);
        bot.setReplyGroup(true);
        bot.setRateLimitPerMinute(30);
        return bot;
    }

    private AgentDtos.AgentEventRequest event(UUID botId) {
        return AgentDtos.AgentEventRequest.text(
                "evt-1", botId, "message", "wxid_friend", "张三",
                ChatType.PRIVATE, "wxid_friend", "张三", "你好", null, false);
    }

    @Test
    void pausedConversationSkipsAutoReply() {
        BotConfig bot = bot();
        when(botRepository.findById(bot.getId())).thenReturn(Optional.of(bot));

        Conversation paused = new Conversation();
        paused.setId(UUID.randomUUID());
        paused.setBotId(bot.getId());
        paused.setChatId("wxid_friend");
        paused.setChatName("张三");
        paused.setChatType(ChatType.PRIVATE);
        paused.setLastMessageAt(OffsetDateTime.now());
        paused.setBotPaused(true);
        when(conversationRepository.findByBotIdAndChatId(bot.getId(), "wxid_friend"))
                .thenReturn(Optional.of(paused));
        when(conversationRepository.save(any(Conversation.class))).thenAnswer(inv -> inv.getArgument(0));
        when(messageRepository.save(any(MessageLog.class))).thenAnswer(inv -> {
            MessageLog log = inv.getArgument(0);
            log.setId(UUID.randomUUID());
            return log;
        });
        when(dedupeService.firstSeen(any())).thenReturn(true);
        when(rateLimitService.allow(any(), any(), anyInt())).thenReturn(true);

        AgentDtos.EventAccepted result = service.accept(event(bot.getId()));

        assertThat(result.status()).isEqualTo("PAUSED");
        assertThat(result.reason()).contains("人工接管");
        verifyNoInteractions(chatOrchestrator);
    }
}
