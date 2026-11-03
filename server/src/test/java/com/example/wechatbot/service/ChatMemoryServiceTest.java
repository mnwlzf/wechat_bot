package com.example.wechatbot.service;

import com.example.wechatbot.ai.LlmGateway;
import com.example.wechatbot.ai.LlmReply;
import com.example.wechatbot.config.MemoryProperties;
import com.example.wechatbot.domain.BotConfig;
import com.example.wechatbot.domain.ChatMemory;
import com.example.wechatbot.domain.ChatType;
import com.example.wechatbot.domain.Conversation;
import com.example.wechatbot.domain.MemoryKind;
import com.example.wechatbot.domain.MessageDirection;
import com.example.wechatbot.domain.MessageLog;
import com.example.wechatbot.repository.ChatMemoryRepository;
import com.example.wechatbot.repository.MessageLogRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 群聊共享记忆：注入提示词的格式、摘要触发阈值、以及「只压缩窗口外的历史」。
 * 这几条错了，表现就是机器人要么忘事，要么把刚说过的话又总结一遍浪费 token。
 */
class ChatMemoryServiceTest {
    private final ChatMemoryRepository repository = mock(ChatMemoryRepository.class);
    private final MessageLogRepository messageRepository = mock(MessageLogRepository.class);
    private final LlmGateway llmGateway = mock(LlmGateway.class);
    private final MemoryProperties properties = new MemoryProperties();

    private final ChatMemoryService service =
            new ChatMemoryService(repository, messageRepository, llmGateway, properties);

    private BotConfig bot(int contextWindow) {
        BotConfig bot = new BotConfig();
        bot.setId(UUID.randomUUID());
        bot.setName("大肥鱼");
        bot.setContextWindow(contextWindow);
        return bot;
    }

    private Conversation group() {
        Conversation conversation = new Conversation();
        conversation.setId(UUID.randomUUID());
        conversation.setBotId(UUID.randomUUID());
        conversation.setChatId("123@chatroom");
        conversation.setChatName("摸鱼群");
        conversation.setChatType(ChatType.GROUP);
        return conversation;
    }

    private ChatMemory memory(MemoryKind kind, String content, boolean enabled) {
        ChatMemory memory = new ChatMemory();
        memory.setId(UUID.randomUUID());
        memory.setChatId("123@chatroom");
        memory.setKind(kind);
        memory.setContent(content);
        memory.setEnabled(enabled);
        return memory;
    }

    private MessageLog message(String content, OffsetDateTime at) {
        MessageLog log = new MessageLog();
        log.setId(UUID.randomUUID());
        log.setDirection(MessageDirection.INBOUND);
        log.setSenderName("张三");
        log.setContent(content);
        log.setCreatedAt(at);
        return log;
    }

    @Test
    void promptBlockContainsSummaryAndEnabledNotesOnly() {
        when(repository.findFirstByChatIdAndKind("123@chatroom", MemoryKind.SUMMARY))
                .thenReturn(Optional.of(memory(MemoryKind.SUMMARY, "上周定了周五聚餐", true)));
        when(repository.findByChatIdAndKindAndEnabledTrueOrderByUpdatedAtDesc("123@chatroom", MemoryKind.NOTE))
                .thenReturn(List.of(memory(MemoryKind.NOTE, "管张伟叫老张", true)));

        String block = service.promptBlock("123@chatroom", ChatType.GROUP);

        assertThat(block).contains("本群共享记忆");
        assertThat(block).contains("上周定了周五聚餐");
        assertThat(block).contains("管张伟叫老张");
    }

    @Test
    void promptBlockIsEmptyWithoutAnyMemory() {
        when(repository.findFirstByChatIdAndKind(any(), any())).thenReturn(Optional.empty());
        when(repository.findByChatIdAndKindAndEnabledTrueOrderByUpdatedAtDesc(any(), any()))
                .thenReturn(List.of());

        assertThat(service.promptBlock("123@chatroom", ChatType.GROUP)).isEmpty();
    }

    @Test
    void disabledSummaryIsNotInjected() {
        when(repository.findFirstByChatIdAndKind("123@chatroom", MemoryKind.SUMMARY))
                .thenReturn(Optional.of(memory(MemoryKind.SUMMARY, "旧摘要", false)));
        when(repository.findByChatIdAndKindAndEnabledTrueOrderByUpdatedAtDesc("123@chatroom", MemoryKind.NOTE))
                .thenReturn(List.of());

        assertThat(service.promptBlock("123@chatroom", ChatType.GROUP)).isEmpty();
    }

    @Test
    void summarizeCoversOnlyMessagesOutsideTheRecentWindow() {
        Conversation conversation = group();
        BotConfig bot = bot(2);
        properties.setTriggerMessages(2);
        OffsetDateTime now = OffsetDateTime.now();
        MessageLog newest = message("最新一条", now);
        MessageLog recent = message("窗口内", now.minusMinutes(1));
        MessageLog older1 = message("窗口外 1", now.minusHours(3));
        MessageLog older2 = message("窗口外 2", now.minusHours(2));
        MessageLog older3 = message("窗口外 3", now.minusHours(1));

        when(repository.findFirstByChatIdAndKind("123@chatroom", MemoryKind.SUMMARY)).thenReturn(Optional.empty());
        when(messageRepository.findByConversationIdOrderByCreatedAtDesc(eq(conversation.getId()), any(Pageable.class)))
                .thenReturn(List.of(newest, recent));
        when(messageRepository.findByConversationIdAndCreatedAtLessThanOrderByCreatedAtAsc(
                eq(conversation.getId()), any(), any(Pageable.class)))
                .thenReturn(List.of(older1, older2, older3));
        when(llmGateway.chat(any(), anyList())).thenReturn(LlmReply.rule("合并后的摘要"));
        when(repository.save(any(ChatMemory.class))).thenAnswer(inv -> inv.getArgument(0));

        ChatMemory saved = service.summarize(bot, conversation, false);

        assertThat(saved).isNotNull();
        assertThat(saved.getKind()).isEqualTo(MemoryKind.SUMMARY);
        assertThat(saved.getContent()).isEqualTo("合并后的摘要");
        assertThat(saved.getMessageCount()).isEqualTo(3);
        assertThat(saved.getCoveredUntil()).isEqualTo(older3.getCreatedAt());
    }

    @Test
    void summarizeSkipsWhenTooFewNewMessages() {
        Conversation conversation = group();
        BotConfig bot = bot(2);
        properties.setTriggerMessages(10);
        OffsetDateTime now = OffsetDateTime.now();

        when(repository.findFirstByChatIdAndKind("123@chatroom", MemoryKind.SUMMARY)).thenReturn(Optional.empty());
        when(messageRepository.findByConversationIdOrderByCreatedAtDesc(eq(conversation.getId()), any(Pageable.class)))
                .thenReturn(List.of(message("最新", now)));
        when(messageRepository.findByConversationIdAndCreatedAtLessThanOrderByCreatedAtAsc(
                eq(conversation.getId()), any(), any(Pageable.class)))
                .thenReturn(List.of(message("旧消息", now.minusHours(1))));

        assertThat(service.summarize(bot, conversation, false)).isNull();
        verify(llmGateway, never()).chat(any(), anyList());
    }

    @Test
    void summarizeKeepsOldSummaryWhenModelFails() {
        Conversation conversation = group();
        BotConfig bot = bot(1);
        properties.setTriggerMessages(1);
        OffsetDateTime now = OffsetDateTime.now();
        ChatMemory existing = memory(MemoryKind.SUMMARY, "旧摘要还在", true);
        existing.setUpdatedAt(now.minusDays(1));

        when(repository.findFirstByChatIdAndKind("123@chatroom", MemoryKind.SUMMARY)).thenReturn(Optional.of(existing));
        when(messageRepository.findByConversationIdOrderByCreatedAtDesc(eq(conversation.getId()), any(Pageable.class)))
                .thenReturn(List.of(message("最新", now)));
        when(messageRepository.findByConversationIdAndCreatedAtLessThanOrderByCreatedAtAsc(
                eq(conversation.getId()), any(), any(Pageable.class)))
                .thenReturn(List.of(message("更早", now.minusHours(1))));
        when(llmGateway.chat(any(), anyList())).thenThrow(new IllegalStateException("模型不可用"));
        when(repository.save(any(ChatMemory.class))).thenAnswer(inv -> inv.getArgument(0));

        ChatMemory saved = service.summarize(bot, conversation, false);

        assertThat(saved).isNotNull();
        assertThat(saved.getContent()).isEqualTo("旧摘要还在");
    }

    @Test
    void summarizeIsSkippedWhenDisabled() {
        properties.setEnabled(false);

        assertThat(service.summarize(bot(2), group(), false)).isNull();
        verify(llmGateway, never()).chat(any(), anyList());
    }

    @Test
    void newNoteIsManualAndEnabledByDefault() {
        when(repository.save(any(ChatMemory.class))).thenAnswer(inv -> {
            ChatMemory memory = inv.getArgument(0);
            memory.setId(UUID.randomUUID());
            return memory;
        });

        ChatMemory note = service.createNote("123@chatroom", "摸鱼群", "  周五聚餐  ", null);

        ArgumentCaptor<ChatMemory> captor = ArgumentCaptor.forClass(ChatMemory.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getKind()).isEqualTo(MemoryKind.NOTE);
        assertThat(captor.getValue().getSource().name()).isEqualTo("MANUAL");
        assertThat(note.getContent()).isEqualTo("周五聚餐");
        assertThat(note.isEnabled()).isTrue();
    }
}
