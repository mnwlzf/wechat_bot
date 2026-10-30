package com.example.wechatbot.service;

import com.example.wechatbot.domain.*;
import com.example.wechatbot.repository.BotConfigRepository;
import com.example.wechatbot.repository.ConversationRepository;
import com.example.wechatbot.repository.MessageLogRepository;
import com.example.wechatbot.web.dto.AgentDtos;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class InboxServiceMediaPolicyTest {
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

    private void stubCommon(BotConfig bot) {
        when(botRepository.findById(bot.getId())).thenReturn(Optional.of(bot));
        Conversation conversation = new Conversation();
        conversation.setId(UUID.randomUUID());
        conversation.setBotId(bot.getId());
        conversation.setChatId("wxid_friend");
        conversation.setChatName("张三");
        conversation.setChatType(ChatType.PRIVATE);
        when(conversationRepository.findByBotIdAndChatId(bot.getId(), "wxid_friend"))
                .thenReturn(Optional.of(conversation));
        when(conversationRepository.save(any(Conversation.class))).thenAnswer(inv -> inv.getArgument(0));
        when(messageRepository.save(any(MessageLog.class))).thenAnswer(inv -> {
            MessageLog log = inv.getArgument(0);
            log.setId(UUID.randomUUID());
            return log;
        });
        when(dedupeService.firstSeen(any())).thenReturn(true);
        when(rateLimitService.allow(any(), any(), anyInt())).thenReturn(true);
        when(settings.mediaEnabled()).thenReturn(true);
    }

    private AgentDtos.AgentEventRequest imageEvent(UUID botId) {
        return new AgentDtos.AgentEventRequest(
                "evt-img", botId, "message", "wxid_friend", "张三", ChatType.PRIVATE,
                "wxid_friend", "张三", "[图片]", null, false,
                MediaKind.IMAGE.name(), null, null, null, 3);
    }

    @Test
    void ignoreModeRecordsButDoesNotReply() {
        BotConfig bot = bot();
        stubCommon(bot);
        when(settings.mediaReplyMode()).thenReturn("ignore");

        AgentDtos.EventAccepted result = service.accept(imageEvent(bot.getId()));

        assertThat(result.status()).isEqualTo("IGNORED");
        assertThat(result.reason()).contains("仅记录");
        verifyNoInteractions(chatOrchestrator);

        ArgumentCaptor<MessageLog> captor = ArgumentCaptor.forClass(MessageLog.class);
        verify(messageRepository, atLeastOnce()).save(captor.capture());
        MessageLog stored = captor.getAllValues().get(0);
        assertThat(stored.getMediaKind()).isEqualTo(MediaKind.IMAGE);
        assertThat(stored.getRawType()).isEqualTo(3);
        assertThat(stored.getStatus()).isEqualTo(MessageStatus.IGNORED);
    }

    @Test
    void ackModeSendsFixedText() {
        BotConfig bot = bot();
        stubCommon(bot);
        when(settings.mediaReplyMode()).thenReturn("ack");
        when(settings.mediaAckText()).thenReturn("收到，我看下～");

        AgentDtos.EventAccepted result = service.accept(imageEvent(bot.getId()));

        assertThat(result.status()).isEqualTo("ACCEPTED");
        verify(chatOrchestrator).generateFixedReplyAsync(
                eq(bot.getId()), any(UUID.class), any(UUID.class), eq("收到，我看下～"));
        verify(chatOrchestrator, never()).generateReplyAsync(any(), any(), any());
    }

    @Test
    void llmModeFeedsPlaceholderToModel() {
        BotConfig bot = bot();
        stubCommon(bot);
        when(settings.mediaReplyMode()).thenReturn("llm");

        AgentDtos.EventAccepted result = service.accept(imageEvent(bot.getId()));

        assertThat(result.status()).isEqualTo("ACCEPTED");
        verify(chatOrchestrator).generateReplyAsync(eq(bot.getId()), any(UUID.class), any(UUID.class));
        verify(chatOrchestrator, never()).generateFixedReplyAsync(any(), any(), any(), any());
    }

    @Test
    void defersReplyUntilAfterCommitWhenTransactionSynchronizationActive() {
        BotConfig bot = bot();
        stubCommon(bot);
        when(settings.mediaReplyMode()).thenReturn("ack");
        when(settings.mediaAckText()).thenReturn("收到，我看下～");

        TransactionSynchronizationManager.initSynchronization();
        try {
            AgentDtos.EventAccepted result = service.accept(imageEvent(bot.getId()));

            assertThat(result.status()).isEqualTo("ACCEPTED");
            // 事务提交前不应产生任何回复调用
            verifyNoInteractions(chatOrchestrator);

            assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);
            TransactionSynchronization synchronization =
                    TransactionSynchronizationManager.getSynchronizations().get(0);
            synchronization.afterCommit();

            verify(chatOrchestrator).generateFixedReplyAsync(
                    eq(bot.getId()), any(UUID.class), any(UUID.class), eq("收到，我看下～"));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void mediaDisabledSkipsEverything() {
        BotConfig bot = bot();
        stubCommon(bot);
        when(settings.mediaEnabled()).thenReturn(false);

        AgentDtos.EventAccepted result = service.accept(imageEvent(bot.getId()));

        assertThat(result.status()).isEqualTo("IGNORED");
        assertThat(result.reason()).contains("媒体消息处理未启用");
        verify(messageRepository, never()).save(any());
    }

    @Test
    void quotedReplyIsTreatedAsTextWithQuoteRecorded() {
        BotConfig bot = bot();
        stubCommon(bot);

        AgentDtos.AgentEventRequest event = new AgentDtos.AgentEventRequest(
                "evt-quote", bot.getId(), "message", "wxid_friend", "张三", ChatType.PRIVATE,
                "wxid_friend", "张三", "可以，我十点到", null, false,
                null, null, null, null, 49);

        AgentDtos.EventAccepted result = service.accept(event, new HookExtras("张三", "[语音 4″]", null, null));

        assertThat(result.status()).isEqualTo("ACCEPTED");
        verify(chatOrchestrator).generateReplyAsync(eq(bot.getId()), any(UUID.class), any(UUID.class));

        ArgumentCaptor<MessageLog> captor = ArgumentCaptor.forClass(MessageLog.class);
        verify(messageRepository, atLeastOnce()).save(captor.capture());
        MessageLog stored = captor.getAllValues().get(0);
        assertThat(stored.getContent()).isEqualTo("可以，我十点到");
        assertThat(stored.getQuoteAuthor()).isEqualTo("张三");
        assertThat(stored.getQuoteText()).isEqualTo("[语音 4″]");
        assertThat(stored.getMediaKind()).isNull();
        assertThat(stored.getRawType()).isEqualTo(49);
    }
}
