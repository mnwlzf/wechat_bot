package com.example.wechatbot.service;

import com.example.wechatbot.domain.*;
import com.example.wechatbot.repository.BotConfigRepository;
import com.example.wechatbot.repository.ConversationRepository;
import com.example.wechatbot.repository.OutboxMessageRepository;
import com.example.wechatbot.repository.ScheduledMessageRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ScheduledMessageServiceTest {
    private final ScheduledMessageRepository repository = mock(ScheduledMessageRepository.class);
    private final OutboxMessageRepository outboxRepository = mock(OutboxMessageRepository.class);
    private final ConversationRepository conversationRepository = mock(ConversationRepository.class);
    private final BotConfigRepository botRepository = mock(BotConfigRepository.class);
    private final ContentFilterService contentFilter = mock(ContentFilterService.class);

    private final ScheduledMessageService service = new ScheduledMessageService(
            repository, outboxRepository, conversationRepository, botRepository, contentFilter);

    private BotConfig bot() {
        BotConfig bot = new BotConfig();
        bot.setId(UUID.randomUUID());
        bot.setName("小助手");
        bot.setEnabled(true);
        return bot;
    }

    private ScheduledMessage scheduled(String content) {
        ScheduledMessage message = new ScheduledMessage();
        message.setId(UUID.randomUUID());
        message.setBotId(UUID.randomUUID());
        message.setChatId("wxid_demo");
        message.setContent(content);
        message.setScheduledAt(OffsetDateTime.now().minusMinutes(1));
        message.setStatus(ScheduledStatus.PENDING);
        return message;
    }

    @Test
    void dueMessageIsQueuedIntoOutbox() {
        ScheduledMessage message = scheduled("明早十点开会");
        when(repository.findByStatusAndScheduledAtLessThanEqualOrderByScheduledAtAsc(
                eq(ScheduledStatus.PENDING), any(), any(Pageable.class))).thenReturn(List.of(message));
        when(contentFilter.apply(any())).thenReturn(ContentFilterService.Result.pass("明早十点开会"));
        when(outboxRepository.save(any(OutboxMessage.class))).thenAnswer(inv -> {
            OutboxMessage outbox = inv.getArgument(0);
            outbox.setId(UUID.randomUUID());
            return outbox;
        });
        when(repository.save(any(ScheduledMessage.class))).thenAnswer(inv -> inv.getArgument(0));

        service.dispatchDue();

        assertThat(message.getStatus()).isEqualTo(ScheduledStatus.SENT);
        assertThat(message.getOutboxId()).isNotNull();
        assertThat(message.getSentAt()).isNotNull();
        verify(outboxRepository).save(any(OutboxMessage.class));
    }

    @Test
    void blockedContentMarksFailedWithoutOutbox() {
        ScheduledMessage message = scheduled("含违禁词");
        when(repository.findByStatusAndScheduledAtLessThanEqualOrderByScheduledAtAsc(
                eq(ScheduledStatus.PENDING), any(), any(Pageable.class))).thenReturn(List.of(message));
        when(contentFilter.apply(any()))
                .thenReturn(new ContentFilterService.Result("含违禁词", true, "命中敏感词，已拦截发送"));
        when(repository.save(any(ScheduledMessage.class))).thenAnswer(inv -> inv.getArgument(0));

        service.dispatchDue();

        assertThat(message.getStatus()).isEqualTo(ScheduledStatus.FAILED);
        assertThat(message.getLastError()).contains("敏感词");
        verifyNoInteractions(outboxRepository);
    }

    @Test
    void createRejectsPastTime() {
        when(botRepository.findFirstByEnabledTrueOrderByCreatedAtAsc()).thenReturn(Optional.of(bot()));

        assertThatThrownBy(() -> service.create(null, "wxid_demo", "张三", "内容",
                OffsetDateTime.now().minusHours(1)))
                .hasMessageContaining("不能早于当前时间");
    }

    @Test
    void createResolvesConversationNameAndBot() {
        BotConfig bot = bot();
        when(botRepository.findFirstByEnabledTrueOrderByCreatedAtAsc()).thenReturn(Optional.of(bot));
        Conversation conversation = new Conversation();
        conversation.setId(UUID.randomUUID());
        conversation.setBotId(bot.getId());
        conversation.setChatId("wxid_demo");
        conversation.setChatName("张三");
        conversation.setChatType(ChatType.PRIVATE);
        when(conversationRepository.findByBotIdAndChatId(bot.getId(), "wxid_demo"))
                .thenReturn(Optional.of(conversation));
        when(repository.save(any(ScheduledMessage.class))).thenAnswer(inv -> inv.getArgument(0));

        ScheduledMessage created = service.create(null, " wxid_demo ", null, " 内容 ",
                OffsetDateTime.now().plusMinutes(5));

        assertThat(created.getChatId()).isEqualTo("wxid_demo");
        assertThat(created.getChatName()).isEqualTo("张三");
        assertThat(created.getContent()).isEqualTo("内容");
        assertThat(created.getConversationId()).isEqualTo(conversation.getId());
    }

    @Test
    void cancelOnlyAllowedForPending() {
        ScheduledMessage message = scheduled("内容");
        message.setStatus(ScheduledStatus.SENT);
        when(repository.findById(message.getId())).thenReturn(Optional.of(message));

        assertThatThrownBy(() -> service.cancel(message.getId()))
                .hasMessageContaining("只有待发送");
    }
}
