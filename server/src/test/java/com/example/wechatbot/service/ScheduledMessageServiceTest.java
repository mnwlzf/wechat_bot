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
    private final com.example.wechatbot.disguise.DisguiseClock clock =
            mock(com.example.wechatbot.disguise.DisguiseClock.class);
    private final com.example.wechatbot.config.ReminderProperties reminderProperties =
            new com.example.wechatbot.config.ReminderProperties();

    private final ScheduledMessageService service = new ScheduledMessageService(
            repository, outboxRepository, conversationRepository, botRepository, contentFilter,
            clock, reminderProperties);

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

    @Test
    void patchOnlyTouchesTheFieldsItCarries() {
        ScheduledMessage message = scheduled("打卡");
        message.setKind(ScheduledKind.REMINDER);
        message.setTitle("打卡");
        message.setIntervalMinutes(5);
        message.setMaxTimes(6);
        message.setStopWords("已打卡,好了");
        message.setScheduledAt(OffsetDateTime.now().plusHours(3));
        when(repository.findById(message.getId())).thenReturn(Optional.of(message));
        when(repository.save(any(ScheduledMessage.class))).thenAnswer(inv -> inv.getArgument(0));

        service.patch(message.getId(), new ScheduledMessageService.ReminderPatch(
                null, null, 10, 3, null, null, null, null, null, null, null));

        assertThat(message.getIntervalMinutes()).isEqualTo(10);
        assertThat(message.getMaxTimes()).isEqualTo(3);
        // 没传的字段一律保持原样，否则「改成十分钟」会把停止词和标题清空
        assertThat(message.getTitle()).isEqualTo("打卡");
        assertThat(message.getStopWords()).isEqualTo("已打卡,好了");
        assertThat(message.getScheduledAt()).isAfter(OffsetDateTime.now().plusHours(2));
    }

    @Test
    void patchClampsOutOfRangeValuesAndRefusesPastTimes() {
        ScheduledMessage message = scheduled("打卡");
        message.setKind(ScheduledKind.REMINDER);
        message.setScheduledAt(OffsetDateTime.now().plusHours(3));
        when(repository.findById(message.getId())).thenReturn(Optional.of(message));
        when(repository.save(any(ScheduledMessage.class))).thenAnswer(inv -> inv.getArgument(0));

        service.patch(message.getId(), new ScheduledMessageService.ReminderPatch(
                null, null, 1, 99, null, null, null, null, null, null, null));

        assertThat(message.getIntervalMinutes()).isGreaterThanOrEqualTo(5);
        assertThat(message.getMaxTimes()).isLessThanOrEqualTo(20);

        assertThatThrownBy(() -> service.patch(message.getId(),
                new ScheduledMessageService.ReminderPatch(null, OffsetDateTime.now().minusHours(2),
                        null, null, null, null, null, null, null, null, null)))
                .hasMessageContaining("已经过去");
    }

    @Test
    void patchRefusesTasksThatAreNoLongerWaiting() {
        ScheduledMessage message = scheduled("打卡");
        message.setStatus(ScheduledStatus.EXPIRED);
        when(repository.findById(message.getId())).thenReturn(Optional.of(message));

        assertThatThrownBy(() -> service.patch(message.getId(),
                new ScheduledMessageService.ReminderPatch(null, null, 10, null,
                        null, null, null, null, null, null, null)))
                .hasMessageContaining("不在等待中");
    }

    @Test
    void dailyRepeatStaysPendingAndAdvancesToNextDay() {
        ScheduledMessage message = scheduled("每天九点提醒");
        message.setRepeatRule(ScheduledRepeat.DAILY);
        when(repository.findByStatusAndScheduledAtLessThanEqualOrderByScheduledAtAsc(
                eq(ScheduledStatus.PENDING), any(), any(Pageable.class))).thenReturn(List.of(message));
        when(contentFilter.apply(any())).thenAnswer(inv -> ContentFilterService.Result.pass(inv.getArgument(0)));
        when(outboxRepository.save(any(OutboxMessage.class))).thenAnswer(inv -> {
            OutboxMessage outbox = inv.getArgument(0);
            outbox.setId(UUID.randomUUID());
            return outbox;
        });
        when(repository.save(any(ScheduledMessage.class))).thenAnswer(inv -> inv.getArgument(0));

        service.dispatchDue();

        // 重复消息不结束：继续排队，时间推到未来最近的一次
        assertThat(message.getStatus()).isEqualTo(ScheduledStatus.PENDING);
        assertThat(message.getScheduledAt()).isAfter(OffsetDateTime.now());
        assertThat(message.getSentCount()).isEqualTo(1);
    }

    @Test
    void weeklyRepeatStopsAfterRepeatUntil() {
        ScheduledMessage message = scheduled("每周例会");
        message.setRepeatRule(ScheduledRepeat.WEEKLY);
        message.setScheduledAt(OffsetDateTime.now().minusMinutes(1));
        message.setRepeatUntil(OffsetDateTime.now().plusHours(1));
        when(repository.findByStatusAndScheduledAtLessThanEqualOrderByScheduledAtAsc(
                eq(ScheduledStatus.PENDING), any(), any(Pageable.class))).thenReturn(List.of(message));
        when(contentFilter.apply(any())).thenAnswer(inv -> ContentFilterService.Result.pass(inv.getArgument(0)));
        when(outboxRepository.save(any(OutboxMessage.class))).thenAnswer(inv -> {
            OutboxMessage outbox = inv.getArgument(0);
            outbox.setId(UUID.randomUUID());
            return outbox;
        });
        when(repository.save(any(ScheduledMessage.class))).thenAnswer(inv -> inv.getArgument(0));

        service.dispatchDue();

        // 下一次（一周后）已经超过截止时间，所以这条到此结束
        assertThat(message.getStatus()).isEqualTo(ScheduledStatus.SENT);
        assertThat(message.getSentCount()).isEqualTo(1);
    }

    @Test
    void cronRepeatAdvancesToTheNextMatchingTime() {
        ScheduledMessage message = scheduled("工作日九点提醒");
        message.setRepeatRule(ScheduledRepeat.CRON);
        message.setCronExpression("0 9 * * 1-5");
        message.setScheduledAt(OffsetDateTime.now().minusMinutes(1));
        when(repository.findByStatusAndScheduledAtLessThanEqualOrderByScheduledAtAsc(
                eq(ScheduledStatus.PENDING), any(), any(Pageable.class))).thenReturn(List.of(message));
        when(contentFilter.apply(any())).thenAnswer(inv -> ContentFilterService.Result.pass(inv.getArgument(0)));
        when(outboxRepository.save(any(OutboxMessage.class))).thenAnswer(inv -> {
            OutboxMessage outbox = inv.getArgument(0);
            outbox.setId(UUID.randomUUID());
            return outbox;
        });
        when(repository.save(any(ScheduledMessage.class))).thenAnswer(inv -> inv.getArgument(0));

        service.dispatchDue();

        // 依然排队，下一次触发按 cron 算：工作日 9 点
        assertThat(message.getStatus()).isEqualTo(ScheduledStatus.PENDING);
        assertThat(message.getScheduledAt()).isAfter(OffsetDateTime.now());
        assertThat(message.getScheduledAt().getHour()).isEqualTo(9);
        assertThat(message.getScheduledAt().getDayOfWeek().getValue()).isLessThanOrEqualTo(5);
        assertThat(message.getSentCount()).isEqualTo(1);
    }

    @Test
    void invalidCronIsRejectedWithAHint() {
        assertThatThrownBy(() -> service.create(null, "123@chatroom", "群", "内容",
                OffsetDateTime.now().plusHours(1), ScheduledRepeat.CRON, null, false, "这不是 cron"))
                .hasMessageContaining("cron");
    }

    @Test
    void cronWithoutAUsableStartTimeSnapsToTheNextTrigger() {
        when(botRepository.findFirstByEnabledTrueOrderByCreatedAtAsc()).thenReturn(Optional.empty());
        when(botRepository.findById(any())).thenReturn(Optional.empty());
        // 没有机器人配置时，先校验 cron 本身：表达式合法、时间在过去也会被顺延
        assertThatThrownBy(() -> service.create(null, "123@chatroom", "群", "内容",
                OffsetDateTime.now().minusDays(1), ScheduledRepeat.CRON, null, false, "0 9 * * *"))
                .hasMessageContaining("机器人");
    }

    @Test
    void splitLinesQueuesOneOutboxRowPerNonBlankLine() {
        ScheduledMessage message = scheduled("第一行\n\n  第二行  \n第三行");
        message.setSplitLines(true);
        when(repository.findByStatusAndScheduledAtLessThanEqualOrderByScheduledAtAsc(
                eq(ScheduledStatus.PENDING), any(), any(Pageable.class))).thenReturn(List.of(message));
        when(contentFilter.apply(any())).thenAnswer(inv -> ContentFilterService.Result.pass(inv.getArgument(0)));
        when(outboxRepository.save(any(OutboxMessage.class))).thenAnswer(inv -> {
            OutboxMessage outbox = inv.getArgument(0);
            outbox.setId(UUID.randomUUID());
            return outbox;
        });
        when(repository.save(any(ScheduledMessage.class))).thenAnswer(inv -> inv.getArgument(0));

        service.dispatchDue();

        verify(outboxRepository, times(3)).save(any(OutboxMessage.class));
        assertThat(message.getStatus()).isEqualTo(ScheduledStatus.SENT);
    }

    @Test
    void splitKeepsWholeContentWhenNotSplitting() {
        assertThat(ScheduledMessageService.split(" 一整段话 \n换行也保留 ", false))
                .containsExactly("一整段话 \n换行也保留");
        assertThat(ScheduledMessageService.split("a\n\nb", true)).containsExactly("a", "b");
        assertThat(ScheduledMessageService.split("   ", true)).isEmpty();
    }
}
