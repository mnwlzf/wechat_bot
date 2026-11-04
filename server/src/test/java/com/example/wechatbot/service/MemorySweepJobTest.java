package com.example.wechatbot.service;

import com.example.wechatbot.config.MemoryProperties;
import com.example.wechatbot.domain.BotConfig;
import com.example.wechatbot.domain.ChatType;
import com.example.wechatbot.domain.Conversation;
import com.example.wechatbot.repository.BotConfigRepository;
import com.example.wechatbot.repository.ConversationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 后台补记忆：把「聊完就静下来的会话」挑出来压摘要。
 *
 * <p>这条链路断了，表现就是「上午说的事下午就忘」——窗口外的历史既不在上下文里、
 * 也没进摘要，要等到下次聊够条数才补上，那时已经晚了。</p>
 */
class MemorySweepJobTest {
    private final ConversationRepository conversations = mock(ConversationRepository.class);
    private final BotConfigRepository bots = mock(BotConfigRepository.class);
    private final ChatMemoryService memoryService = mock(ChatMemoryService.class);
    private final MemoryProperties properties = new MemoryProperties();

    private final MemorySweepJob job =
            new MemorySweepJob(conversations, bots, memoryService, properties);

    private Conversation conversation(String chatId, UUID botId, OffsetDateTime lastMessageAt) {
        Conversation conversation = new Conversation();
        conversation.setId(UUID.randomUUID());
        conversation.setBotId(botId);
        conversation.setChatId(chatId);
        conversation.setChatName(chatId);
        conversation.setChatType(ChatType.PRIVATE);
        conversation.setLastMessageAt(lastMessageAt);
        return conversation;
    }

    private BotConfig bot() {
        BotConfig bot = new BotConfig();
        bot.setId(UUID.randomUUID());
        bot.setName("大肥鱼");
        bot.setContextWindow(20);
        bot.setEnabled(true);
        return bot;
    }

    @Test
    void summarizesQuietConversations() {
        BotConfig bot = bot();
        Conversation quiet = conversation("wxid_quiet", bot.getId(), OffsetDateTime.now().minusHours(5));
        when(conversations.findByLastMessageAtBeforeAndLastMessageAtAfterOrderByLastMessageAtDesc(
                any(), any(), any(Pageable.class))).thenReturn(List.of(quiet));
        when(bots.findAllById(any())).thenReturn(List.of(bot));
        when(memoryService.summarizeIfIdle(bot, quiet)).thenReturn(true);

        assertThat(job.sweepOnce()).isEqualTo(1);
        verify(memoryService).summarizeIfIdle(bot, quiet);
    }

    @Test
    void oneFailingChatDoesNotStopTheOthers() {
        BotConfig bot = bot();
        Conversation broken = conversation("wxid_broken", bot.getId(), OffsetDateTime.now().minusHours(5));
        Conversation healthy = conversation("wxid_ok", bot.getId(), OffsetDateTime.now().minusHours(4));
        when(conversations.findByLastMessageAtBeforeAndLastMessageAtAfterOrderByLastMessageAtDesc(
                any(), any(), any(Pageable.class))).thenReturn(List.of(broken, healthy));
        when(bots.findAllById(any())).thenReturn(List.of(bot));
        when(memoryService.summarizeIfIdle(bot, broken)).thenThrow(new IllegalStateException("模型超时"));
        when(memoryService.summarizeIfIdle(bot, healthy)).thenReturn(true);

        assertThat(job.sweepOnce()).isEqualTo(1);
        verify(memoryService).summarizeIfIdle(bot, healthy);
    }

    @Test
    void doesNothingWhenMemoryIsDisabled() {
        properties.setEnabled(false);

        assertThat(job.sweepOnce()).isZero();
        verify(conversations, never())
                .findByLastMessageAtBeforeAndLastMessageAtAfterOrderByLastMessageAtDesc(any(), any(), any());
    }

    @Test
    void skipsConversationsWhoseBotIsGone() {
        Conversation orphan = conversation("wxid_orphan", UUID.randomUUID(), OffsetDateTime.now().minusHours(3));
        when(conversations.findByLastMessageAtBeforeAndLastMessageAtAfterOrderByLastMessageAtDesc(
                any(), any(), any(Pageable.class))).thenReturn(List.of(orphan));
        when(bots.findAllById(any())).thenReturn(List.of());

        assertThat(job.sweepOnce()).isZero();
        verify(memoryService, never()).summarizeIfIdle(any(), eq(orphan));
    }
}
