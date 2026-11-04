package com.example.wechatbot.service;

import com.example.wechatbot.domain.BotConfig;
import com.example.wechatbot.domain.ChatType;
import com.example.wechatbot.domain.Conversation;
import com.example.wechatbot.domain.MessageLog;
import com.example.wechatbot.domain.MessageStatus;
import com.example.wechatbot.domain.ReplyMode;
import com.example.wechatbot.domain.ReplyPolicy;
import com.example.wechatbot.repository.BotConfigRepository;
import com.example.wechatbot.repository.ConversationRepository;
import com.example.wechatbot.repository.MessageLogRepository;
import com.example.wechatbot.repository.ReplyPolicyRepository;
import com.example.wechatbot.web.dto.AgentDtos;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 群聊回复判定：主动回复 / 随机回复 / 关键词唤醒，以及按群覆盖默认策略。
 */
class InboxServiceReplyPolicyTest {
    private static final String GROUP = "123@chatroom";

    private final BotConfigRepository botRepository = mock(BotConfigRepository.class);
    private final ConversationRepository conversationRepository = mock(ConversationRepository.class);
    private final MessageLogRepository messageRepository = mock(MessageLogRepository.class);
    private final DedupeService dedupeService = mock(DedupeService.class);
    private final RateLimitService rateLimitService = mock(RateLimitService.class);
    private final ChatOrchestrator chatOrchestrator = mock(ChatOrchestrator.class);
    private final RuntimeSettingsService settings = mock(RuntimeSettingsService.class);
    private final ReplyPolicyRepository policyRepository = mock(ReplyPolicyRepository.class);
    private final PersonaSwitchService personaSwitch = mock(PersonaSwitchService.class);

    private final InboxService service = new InboxService(
            botRepository, conversationRepository, messageRepository,
            dedupeService, rateLimitService, chatOrchestrator, settings,
            new ReplyPolicyService(policyRepository), new InlineReplyDispatcher(),
            personaSwitch, mock(ReminderAckService.class), mock(ReminderIntentService.class));

    private BotConfig bot() {
        BotConfig bot = new BotConfig();
        bot.setId(UUID.randomUUID());
        bot.setName("小助手");
        bot.setEnabled(true);
        bot.setReplyPrivate(true);
        bot.setReplyGroup(true);
        bot.setGroupWakeWords("@机器人");
        bot.setRateLimitPerMinute(30);
        return bot;
    }

    private ReplyPolicy row(String chatId, ReplyMode mode, Double rate, boolean enabled) {
        ReplyPolicy policy = new ReplyPolicy();
        policy.setChatId(chatId);
        policy.setMode(mode);
        policy.setRandomRate(rate);
        policy.setEnabled(enabled);
        return policy;
    }

    private void stubCommon(BotConfig bot) {
        when(botRepository.findById(bot.getId())).thenReturn(Optional.of(bot));
        Conversation conversation = new Conversation();
        conversation.setId(UUID.randomUUID());
        conversation.setBotId(bot.getId());
        conversation.setChatId(GROUP);
        conversation.setChatType(ChatType.GROUP);
        when(conversationRepository.findByBotIdAndChatId(bot.getId(), GROUP))
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
        when(settings.mediaReplyMode()).thenReturn("llm");
    }

    private AgentDtos.AgentEventRequest event(UUID botId, String content) {
        return AgentDtos.AgentEventRequest.text(
                "evt_" + Math.abs(content.hashCode()), botId, "message", GROUP, "测试群",
                ChatType.GROUP, "wxid_member", "张三", content, null, false);
    }

    @Test
    void wakeModeIgnoresOrdinaryGroupMessageWithoutStoringIt() {
        BotConfig bot = bot();
        stubCommon(bot);

        AgentDtos.EventAccepted result = service.accept(event(bot.getId(), "今天下午开会"));

        assertThat(result.status()).isEqualTo("IGNORED");
        assertThat(result.reason()).contains("唤醒词");
        verify(messageRepository, never()).save(any());
        verify(chatOrchestrator, never()).generateReply(any(), any(), any());
    }

    @Test
    void wakeModeRepliesWhenKeywordMatched() {
        BotConfig bot = bot();
        stubCommon(bot);

        AgentDtos.EventAccepted result = service.accept(event(bot.getId(), "@机器人 下午几点开会？"));

        assertThat(result.status()).isEqualTo("ACCEPTED");
        verify(chatOrchestrator).generateReply(eq(bot.getId()), any(UUID.class), any(UUID.class));
    }

    @Test
    void personaCommandBypassesTheWakeWordGate() {
        BotConfig bot = bot();
        stubCommon(bot);
        when(personaSwitch.enabled()).thenReturn(true);
        when(personaSwitch.handle(any(BotConfig.class), any(Conversation.class), eq("换人设")))
                .thenReturn(Optional.of("1. 大肥鱼"));

        AgentDtos.EventAccepted result = service.accept(event(bot.getId(), "换人设"));

        // 关键词唤醒模式下没说暗号也能换人设：这条要交给固定话术回列表，而不是被丢掉
        assertThat(result.status()).isEqualTo("PERSONA");
        verify(personaSwitch).handle(any(BotConfig.class), any(Conversation.class), eq("换人设"));
    }

    @Test
    void alwaysModeRepliesToEveryGroupMessage() {
        BotConfig bot = bot();
        stubCommon(bot);
        when(policyRepository.findByChatId(GROUP))
                .thenReturn(Optional.of(row(GROUP, ReplyMode.ALWAYS, null, true)));

        AgentDtos.EventAccepted result = service.accept(event(bot.getId(), "今天下午开会"));

        assertThat(result.status()).isEqualTo("ACCEPTED");
        verify(chatOrchestrator).generateReply(eq(bot.getId()), any(UUID.class), any(UUID.class));
    }

    @Test
    void randomModeWithZeroRateKeepsHistoryButDoesNotReply() {
        BotConfig bot = bot();
        stubCommon(bot);
        when(policyRepository.findByChatIdIsNull())
                .thenReturn(Optional.of(row(null, ReplyMode.RANDOM, 0.0, true)));

        AgentDtos.EventAccepted result = service.accept(event(bot.getId(), "今天下午开会"));

        assertThat(result.status()).isEqualTo("IGNORED");
        assertThat(result.reason()).contains("随机未命中").contains("0%");
        verify(chatOrchestrator, never()).generateReply(any(), any(), any());

        // 随机模式要保留上下文，所以消息仍然入库（标记为忽略）
        ArgumentCaptor<MessageLog> captor = ArgumentCaptor.forClass(MessageLog.class);
        verify(messageRepository, atLeastOnce()).save(captor.capture());
        MessageLog stored = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertThat(stored.getStatus()).isEqualTo(MessageStatus.IGNORED);
        assertThat(stored.getContent()).isEqualTo("今天下午开会");
    }

    @Test
    void randomModeWithFullRateAlwaysReplies() {
        BotConfig bot = bot();
        stubCommon(bot);
        when(policyRepository.findByChatIdIsNull())
                .thenReturn(Optional.of(row(null, ReplyMode.RANDOM, 1.0, true)));

        AgentDtos.EventAccepted result = service.accept(event(bot.getId(), "今天下午开会"));

        assertThat(result.status()).isEqualTo("ACCEPTED");
    }

    @Test
    void chatPolicyOverridesRandomDefault() {
        BotConfig bot = bot();
        stubCommon(bot);
        when(policyRepository.findByChatIdIsNull())
                .thenReturn(Optional.of(row(null, ReplyMode.RANDOM, 0.0, true)));
        when(policyRepository.findByChatId(GROUP))
                .thenReturn(Optional.of(row(GROUP, ReplyMode.ALWAYS, null, true)));

        AgentDtos.EventAccepted result = service.accept(event(bot.getId(), "今天下午开会"));

        assertThat(result.status()).isEqualTo("ACCEPTED");
    }

    @Test
    void humanModeStoresMessageAndLetsTheModelDecide() {
        BotConfig bot = bot();
        stubCommon(bot);
        when(policyRepository.findByChatId(GROUP))
                .thenReturn(Optional.of(row(GROUP, ReplyMode.HUMAN, null, true)));

        AgentDtos.EventAccepted result = service.accept(event(bot.getId(), "今天下午开会"));

        // 拟人模式下消息一律入库并交给模型判断，不做本地关键词过滤
        assertThat(result.status()).isEqualTo("ACCEPTED");
        verify(chatOrchestrator).generateReply(eq(bot.getId()), any(UUID.class), any(UUID.class));
    }

    @Test
    void disabledChatPolicyFallsBackToDefault() {
        BotConfig bot = bot();
        stubCommon(bot);
        when(policyRepository.findByChatIdIsNull())
                .thenReturn(Optional.of(row(null, ReplyMode.ALWAYS, null, true)));
        when(policyRepository.findByChatId(GROUP))
                .thenReturn(Optional.of(row(GROUP, ReplyMode.WAKE, null, false)));

        AgentDtos.EventAccepted result = service.accept(event(bot.getId(), "今天下午开会"));

        assertThat(result.status()).isEqualTo("ACCEPTED");
    }
}
