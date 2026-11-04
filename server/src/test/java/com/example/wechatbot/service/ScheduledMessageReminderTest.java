package com.example.wechatbot.service;

import com.example.wechatbot.config.ReminderProperties;
import com.example.wechatbot.disguise.DisguiseClock;
import com.example.wechatbot.domain.BotConfig;
import com.example.wechatbot.domain.ChatType;
import com.example.wechatbot.domain.Conversation;
import com.example.wechatbot.domain.OutboxMessage;
import com.example.wechatbot.domain.ScheduleSource;
import com.example.wechatbot.domain.ScheduledKind;
import com.example.wechatbot.domain.ScheduledMessage;
import com.example.wechatbot.domain.ScheduledRepeat;
import com.example.wechatbot.domain.ScheduledStatus;
import com.example.wechatbot.repository.BotConfigRepository;
import com.example.wechatbot.repository.ConversationRepository;
import com.example.wechatbot.repository.OutboxMessageRepository;
import com.example.wechatbot.repository.ScheduledMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 提醒任务的调度语义：按间隔催、回话就停、次数有上限、免打扰时段不烧次数。
 *
 * <p>这几条错了，表现是「催了六遍没反应就不催了」变成「催一辈子」，
 * 或者半夜三点被催完六次、早上起来发现已经放弃了。</p>
 */
class ScheduledMessageReminderTest {
    private final ScheduledMessageRepository repository = mock(ScheduledMessageRepository.class);
    private final OutboxMessageRepository outboxRepository = mock(OutboxMessageRepository.class);
    private final ConversationRepository conversationRepository = mock(ConversationRepository.class);
    private final BotConfigRepository botRepository = mock(BotConfigRepository.class);
    private final ContentFilterService contentFilter = mock(ContentFilterService.class);
    private final DisguiseClock clock = mock(DisguiseClock.class);
    private final ReminderProperties properties = new ReminderProperties();

    private final ScheduledMessageService service = new ScheduledMessageService(
            repository, outboxRepository, conversationRepository, botRepository, contentFilter,
            clock, properties);

    private final List<String> sent = new ArrayList<>();

    @BeforeEach
    void setUp() {
        when(clock.canReply(any())).thenReturn(true);
        when(contentFilter.apply(anyString()))
                .thenAnswer(inv -> ContentFilterService.Result.pass(inv.getArgument(0)));
        when(outboxRepository.save(any(OutboxMessage.class))).thenAnswer(inv -> {
            OutboxMessage outbox = inv.getArgument(0);
            outbox.setId(UUID.randomUUID());
            sent.add(outbox.getContent());
            return outbox;
        });
        when(repository.save(any(ScheduledMessage.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private ScheduledMessage reminder(int sentCount, int maxTimes, int intervalMinutes) {
        ScheduledMessage task = new ScheduledMessage();
        task.setId(UUID.randomUUID());
        task.setBotId(UUID.randomUUID());
        task.setChatId("wxid_demo");
        task.setChatName("测试用户");
        task.setKind(ScheduledKind.REMINDER);
        task.setTitle("打卡");
        task.setStopWords("已打卡");
        task.setVariants("该打卡了，回「已打卡」我就不催了。\n还没打卡吧？");
        task.setFinalMessage("算了，6 次都没回，我先不催了。");
        task.setIntervalMinutes(intervalMinutes);
        task.setMaxTimes(maxTimes);
        task.setSentCount(sentCount);
        task.setStatus(ScheduledStatus.PENDING);
        task.setScheduledAt(OffsetDateTime.now().minusSeconds(5));
        return task;
    }

    private void dispatch(ScheduledMessage task) {
        when(repository.findByStatusAndScheduledAtLessThanEqualOrderByScheduledAtAsc(
                eq(ScheduledStatus.PENDING), any(), any(Pageable.class))).thenReturn(List.of(task));
        service.dispatchDue();
    }

    @Test
    void nudgeReschedulesByIntervalInsteadOfFinishing() {
        ScheduledMessage task = reminder(0, 6, 5);

        dispatch(task);

        assertThat(sent).containsExactly("该打卡了，回「已打卡」我就不催了。");
        assertThat(task.getSentCount()).isEqualTo(1);
        assertThat(task.getStatus()).isEqualTo(ScheduledStatus.PENDING);
        assertThat(task.getScheduledAt()).isAfter(OffsetDateTime.now().plusMinutes(4));
    }

    @Test
    void usesTheNextLineEachTimeSoItDoesNotRepeatItself() {
        ScheduledMessage task = reminder(1, 6, 5);

        dispatch(task);

        assertThat(sent).containsExactly("还没打卡吧？");
        assertThat(task.getSentCount()).isEqualTo(2);
    }

    @Test
    void hittingTheCapSendsTheClosingLineAndExpires() {
        ScheduledMessage task = reminder(1, 2, 5);

        dispatch(task);

        assertThat(sent).hasSize(2);
        assertThat(sent.get(1)).contains("我先不催了");
        assertThat(task.getStatus()).isEqualTo(ScheduledStatus.EXPIRED);
        assertThat(task.getScheduledAt()).isBefore(OffsetDateTime.now().plusMinutes(1));
    }

    @Test
    void quietHoursNeitherSendNorBurnACount() {
        ScheduledMessage task = reminder(2, 6, 5);
        when(clock.canReply(any())).thenReturn(false);
        Instant resume = Instant.now().plusSeconds(3600);
        when(clock.nextActiveAt(any())).thenReturn(resume);
        when(clock.resumeWithJitter(any())).thenAnswer(inv -> inv.getArgument(0));

        dispatch(task);

        assertThat(sent).isEmpty();
        assertThat(task.getSentCount()).isEqualTo(2);
        assertThat(task.getStatus()).isEqualTo(ScheduledStatus.PENDING);
        assertThat(task.getScheduledAt().toInstant()).isEqualTo(resume);
    }

    @Test
    void ackEndsTheReminder() {
        ScheduledMessage task = reminder(2, 6, 5);

        boolean acked = service.ack(task, "已打卡，刚弄完");

        assertThat(acked).isTrue();
        assertThat(task.getStatus()).isEqualTo(ScheduledStatus.ACKED);
        assertThat(task.getAckContent()).isEqualTo("已打卡，刚弄完");
        assertThat(task.getAckAt()).isNotNull();
    }

    @Test
    void ackOnADailyReminderOnlyEndsTodaysRound() {
        ScheduledMessage task = reminder(2, 6, 5);
        task.setRepeatRule(ScheduledRepeat.DAILY);

        boolean acked = service.ack(task, "已打卡");

        assertThat(acked).isTrue();
        assertThat(task.getStatus()).isEqualTo(ScheduledStatus.PENDING);
        assertThat(task.getSentCount()).isZero();
        assertThat(task.getScheduledAt()).isAfter(OffsetDateTime.now().plusHours(20));
    }

    @Test
    void stopWaitingCancelsEverythingLeftInTheChat() {
        ScheduledMessage first = reminder(1, 6, 5);
        ScheduledMessage second = reminder(0, 6, 5);
        when(repository.findByChatIdAndKindAndStatusOrderByScheduledAtAsc(
                "wxid_demo", ScheduledKind.REMINDER, ScheduledStatus.PENDING))
                .thenReturn(List.of(first, second));

        assertThat(service.stopWaiting("wxid_demo")).isEqualTo(2);
        assertThat(first.getStatus()).isEqualTo(ScheduledStatus.CANCELLED);
        assertThat(second.getStatus()).isEqualTo(ScheduledStatus.CANCELLED);
    }

    @Test
    void clampsIntervalAndTimesInsteadOfTrustingTheChat() {
        BotConfig bot = new BotConfig();
        bot.setId(UUID.randomUUID());
        bot.setName("大肥鱼");
        bot.setEnabled(true);
        bot.setContextWindow(48);
        when(botRepository.findById(bot.getId())).thenReturn(Optional.of(bot));
        Conversation conversation = new Conversation();
        conversation.setId(UUID.randomUUID());
        conversation.setChatId("wxid_demo");
        conversation.setChatType(ChatType.PRIVATE);
        when(conversationRepository.findByBotIdAndChatId(bot.getId(), "wxid_demo"))
                .thenReturn(Optional.of(conversation));
        when(repository.countByChatIdAndKindAndStatus(anyString(), any(), any())).thenReturn(0L);
        when(repository.countByChatIdAndKindAndCreatedAtAfter(anyString(), any(), any())).thenReturn(0L);
        when(repository.save(any(ScheduledMessage.class))).thenAnswer(inv -> inv.getArgument(0));

        ScheduledMessage task = service.createReminder(bot.getId(), "wxid_demo", null,
                new ScheduledMessageService.ReminderSpec("打卡", OffsetDateTime.now().plusMinutes(1),
                        1, 999, "已打卡", null, "该打卡了", "算了", ScheduledRepeat.NONE, null, null,
                        ScheduleSource.CHAT, "wxid_demo", "每分钟催我打卡，一万次"));

        assertThat(task.getIntervalMinutes()).isEqualTo(properties.getMinIntervalMinutes());
        assertThat(task.getMaxTimes()).isEqualTo(properties.getHardMaxTimes());
        assertThat(task.getKind()).isEqualTo(ScheduledKind.REMINDER);
    }

    @Test
    void groupChatsCannotHaveReminders() {
        BotConfig bot = new BotConfig();
        bot.setId(UUID.randomUUID());
        bot.setEnabled(true);
        when(botRepository.findById(bot.getId())).thenReturn(Optional.of(bot));
        Conversation group = new Conversation();
        group.setId(UUID.randomUUID());
        group.setChatId("123@chatroom");
        group.setChatType(ChatType.GROUP);
        when(conversationRepository.findByBotIdAndChatId(bot.getId(), "123@chatroom"))
                .thenReturn(Optional.of(group));

        assertThatThrownBy(() -> service.createReminder(bot.getId(), "123@chatroom", null,
                new ScheduledMessageService.ReminderSpec("打卡", OffsetDateTime.now().plusMinutes(1),
                        5, 3, "已打卡", null, null, null, ScheduledRepeat.NONE, null, null,
                        ScheduleSource.CHAT, "123@chatroom", "群里提醒我打卡")))
                .hasMessageContaining("群聊不支持提醒");
    }

    @Test
    void refusesWhenTooManyRemindersAreAlreadyWaiting() {
        BotConfig bot = new BotConfig();
        bot.setId(UUID.randomUUID());
        bot.setEnabled(true);
        when(botRepository.findById(bot.getId())).thenReturn(Optional.of(bot));
        when(conversationRepository.findByBotIdAndChatId(any(), anyString())).thenReturn(Optional.empty());
        when(repository.countByChatIdAndKindAndStatus(anyString(), any(), any()))
                .thenReturn((long) properties.getMaxWaitingPerChat());

        assertThatThrownBy(() -> service.createReminder(bot.getId(), "wxid_demo", null,
                new ScheduledMessageService.ReminderSpec("打卡", OffsetDateTime.now().plusMinutes(1),
                        5, 3, "已打卡", null, null, null, ScheduledRepeat.NONE, null, null,
                        ScheduleSource.CHAT, "wxid_demo", "提醒我打卡")))
                .hasMessageContaining("先停掉一个");
        verify(repository, never()).save(any(ScheduledMessage.class));
    }

    @Test
    void messagesKeepTheirOldBehaviour() {
        ScheduledMessage message = reminder(0, 0, 0);
        message.setKind(ScheduledKind.MESSAGE);
        message.setVariants(null);
        message.setContent("明早十点开会");
        message.setSplitLines(false);

        dispatch(message);

        assertThat(sent).containsExactly("明早十点开会");
        assertThat(message.getStatus()).isEqualTo(ScheduledStatus.SENT);
        verify(repository, never()).countByChatIdAndKindAndStatus(anyString(), any(), any());
        verify(contentFilter).apply(eq("明早十点开会"));
    }

    @Test
    void reminderWithoutVariantsFallsBackToContent() {
        ScheduledMessage task = reminder(0, 6, 5);
        task.setVariants(null);
        task.setContent("该交周报了");

        dispatch(task);

        assertThat(sent).hasSize(1);
        assertThat(sent.getFirst()).contains("该交周报了").contains("已打卡");
        verify(outboxRepository, org.mockito.Mockito.times(1)).save(any(OutboxMessage.class));
    }

    @Test
    void unusablePropertiesDoNotBreakDispatch() {
        properties.setFinalMessage(false);
        ScheduledMessage task = reminder(1, 2, 5);

        dispatch(task);

        assertThat(sent).hasSize(1);
        assertThat(task.getStatus()).isEqualTo(ScheduledStatus.EXPIRED);
    }

    @Test
    void intervalClampUsesConfiguredFloor() {
        properties.setMinIntervalMinutes(10);
        ScheduledMessage task = reminder(0, 6, 1);

        dispatch(task);

        assertThat(task.getScheduledAt()).isAfter(OffsetDateTime.now().plusMinutes(9));
    }

    @Test
    void todayCountUsesMidnightAsTheWindow() {
        BotConfig bot = new BotConfig();
        bot.setId(UUID.randomUUID());
        bot.setEnabled(true);
        when(botRepository.findById(bot.getId())).thenReturn(Optional.of(bot));
        when(conversationRepository.findByBotIdAndChatId(any(), anyString())).thenReturn(Optional.empty());
        when(repository.countByChatIdAndKindAndStatus(anyString(), any(), any())).thenReturn(0L);
        when(repository.countByChatIdAndKindAndCreatedAtAfter(anyString(), any(), any()))
                .thenReturn((long) properties.getMaxPerDayPerChat());

        assertThatThrownBy(() -> service.createReminder(bot.getId(), "wxid_demo", null,
                new ScheduledMessageService.ReminderSpec("打卡", OffsetDateTime.now().plusMinutes(1),
                        5, 3, "已打卡", null, null, null, ScheduledRepeat.NONE, null, null,
                        ScheduleSource.CHAT, "wxid_demo", "提醒我打卡")))
                .hasMessageContaining("明天再说");
    }

    @Test
    void ackingATaskTwiceOnlyWorksOnce() {
        ScheduledMessage task = reminder(1, 6, 5);

        assertThat(service.ack(task, "好了")).isTrue();
        assertThat(service.ack(task, "好了")).isFalse();
        verify(repository, org.mockito.Mockito.times(1)).save(task);
    }

    @Test
    void intervalNeverExceedsADay() {
        properties.setDefaultIntervalMinutes(5000);
        ScheduledMessage task = reminder(0, 6, 0);

        dispatch(task);

        assertThat(task.getScheduledAt()).isBefore(OffsetDateTime.now().plusMinutes(24 * 60 + 1));
    }

    @Test
    void noOutboxWriteWhenContentIsBlocked() {
        when(contentFilter.apply(anyString()))
                .thenReturn(new ContentFilterService.Result("该打卡了", true, "命中敏感词"));
        ScheduledMessage task = reminder(0, 6, 5);

        dispatch(task);

        assertThat(sent).isEmpty();
        assertThat(task.getStatus()).isEqualTo(ScheduledStatus.FAILED);
    }
}
