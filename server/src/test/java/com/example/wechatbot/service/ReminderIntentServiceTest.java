package com.example.wechatbot.service;

import com.example.wechatbot.ai.LlmGateway;
import com.example.wechatbot.ai.LlmReply;
import com.example.wechatbot.config.ReminderProperties;
import com.example.wechatbot.domain.BotConfig;
import com.example.wechatbot.domain.ChatType;
import com.example.wechatbot.domain.Conversation;
import com.example.wechatbot.domain.ScheduleSource;
import com.example.wechatbot.domain.ScheduledKind;
import com.example.wechatbot.domain.ScheduledMessage;
import com.example.wechatbot.domain.ScheduledRepeat;
import com.example.wechatbot.domain.ScheduledStatus;
import com.example.wechatbot.repository.BotConfigRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
 * 聊天里一句话建提醒：预筛、解析、追问、建任务、回读确认。
 *
 * <p>最容易出事的两处：把闲聊当成指令（每次都多花一次模型调用，还可能凭空建出任务），
 * 以及时间没说清楚就硬猜一个——所以「缺时间必须追问」这里是硬要求。</p>
 */
class ReminderIntentServiceTest {
    private final ReminderProperties properties = new ReminderProperties();
    private final ScheduledMessageService scheduled = mock(ScheduledMessageService.class);
    private final ReminderTextService textService = mock(ReminderTextService.class);
    private final LlmGateway llmGateway = mock(LlmGateway.class);
    private final BotConfigRepository botRepository = mock(BotConfigRepository.class);

    private final ReminderIntentService service = new ReminderIntentService(
            properties, scheduled, textService, llmGateway, new ObjectMapper(), botRepository);

    private BotConfig bot() {
        BotConfig bot = new BotConfig();
        bot.setId(UUID.randomUUID());
        bot.setName("大肥鱼");
        bot.setEnabled(true);
        return bot;
    }

    private Conversation chat() {
        Conversation conversation = new Conversation();
        conversation.setId(UUID.randomUUID());
        conversation.setChatId("wxid_demo");
        conversation.setChatName("测试用户");
        conversation.setChatType(ChatType.PRIVATE);
        return conversation;
    }

    private String iso(int hour, int minute) {
        return OffsetDateTime.now().plusDays(1).withHour(hour).withMinute(minute)
                .withSecond(0).withNano(0).toString();
    }

    @Test
    void onlyDirectiveLookingSentencesReachTheModel() {
        assertThat(service.mightBeInstruction("每天下午六点提醒我打卡")).isTrue();
        assertThat(service.mightBeInstruction("提醒我明天下午三点开会")).isTrue();
        assertThat(service.mightBeInstruction("别提醒我了")).isTrue();
        assertThat(service.mightBeInstruction("我有哪些提醒")).isTrue();

        assertThat(service.mightBeInstruction("记得早点睡")).isFalse();
        assertThat(service.mightBeInstruction("今天开会吗")).isFalse();
        assertThat(service.mightBeInstruction("你好呀")).isFalse();
    }

    @Test
    void chatterNeverCallsTheModel() {
        assertThat(service.handle(bot(), chat(), "记得早点睡")).isEmpty();
        verify(llmGateway, never()).chat(any(), anyList());
    }

    @Test
    void createsAReminderAndReadsTheKeyFieldsBack() {
        String when = iso(18, 0);
        when(llmGateway.chat(any(), anyList())).thenReturn(LlmReply.rule("""
                {"intent":"create","title":"打卡","firstAt":"%s","repeat":"NONE",
                 "weekdays":[],"reminder":{"enabled":true,"everyMinutes":5,"maxTimes":6,
                 "stopWords":["已打卡"]},"askBack":null,"confidence":0.9}
                """.formatted(when)));
        when(textService.write(any(), any(), eq("打卡"), eq("已打卡"), eq(6)))
                .thenReturn(new ReminderTextService.Script("该打卡了", "算了，6 次都没回"));
        when(scheduled.createReminder(any(), any(), any(), any())).thenAnswer(inv -> {
            ScheduledMessageService.ReminderSpec spec = inv.getArgument(3);
            ScheduledMessage task = new ScheduledMessage();
            task.setId(UUID.randomUUID());
            task.setKind(ScheduledKind.REMINDER);
            task.setTitle(spec.title());
            task.setScheduledAt(spec.firstAt());
            task.setIntervalMinutes(spec.intervalMinutes());
            task.setMaxTimes(spec.maxTimes());
            task.setRepeatRule(spec.repeatRule());
            return task;
        });

        Optional<String> reply = service.handle(bot(), chat(), "每天下午六点提醒我打卡，没回就五分钟催一次");

        assertThat(reply).isPresent();
        assertThat(reply.get()).contains("18:00").contains("每 5 分钟").contains("最多 6 次").contains("已打卡");

        ArgumentCaptor<ScheduledMessageService.ReminderSpec> captor =
                ArgumentCaptor.forClass(ScheduledMessageService.ReminderSpec.class);
        verify(scheduled).createReminder(any(), eq("wxid_demo"), any(), captor.capture());
        assertThat(captor.getValue().source()).isEqualTo(ScheduleSource.CHAT);
        assertThat(captor.getValue().variants()).isEqualTo("该打卡了");
        assertThat(captor.getValue().finalMessage()).isEqualTo("算了，6 次都没回");
        assertThat(captor.getValue().rawInstruction()).contains("每天下午六点");
    }

    @Test
    void plainTimedMessageDoesNotBecomeACountdown() {
        // 「六点提醒我开会」只是要一条定时消息，不该自作主张加催命循环
        when(llmGateway.chat(any(), anyList())).thenReturn(LlmReply.rule("""
                {"intent":"create","title":"开会","firstAt":"%s","repeat":"NONE","weekdays":[],
                 "reminder":{"enabled":false},"askBack":null,"confidence":0.9}
                """.formatted(iso(15, 30))));
        when(scheduled.createReminder(any(), any(), any(), any())).thenAnswer(inv -> {
            ScheduledMessageService.ReminderSpec spec = inv.getArgument(3);
            ScheduledMessage task = new ScheduledMessage();
            task.setId(UUID.randomUUID());
            task.setTitle(spec.title());
            task.setScheduledAt(spec.firstAt());
            task.setRepeatRule(spec.repeatRule());
            return task;
        });

        Optional<String> reply = service.handle(bot(), chat(), "明天下午三点半提醒我开会");

        assertThat(reply).isPresent();
        assertThat(reply.get()).contains("提醒你「开会」").doesNotContain("分钟催");
        verify(textService, never()).write(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void missingTimeAsksBackAndThenUsesTheAnswer() {
        when(llmGateway.chat(any(), anyList()))
                .thenReturn(LlmReply.rule("""
                        {"intent":"create","title":"打卡","firstAt":null,"repeat":"NONE","weekdays":[],
                         "reminder":{"enabled":false},"askBack":"几点提醒你？","confidence":0.5}
                        """))
                .thenReturn(LlmReply.rule("""
                        {"intent":"create","title":"打卡","firstAt":"%s","repeat":"NONE","weekdays":[],
                         "reminder":{"enabled":false},"askBack":null,"confidence":0.9}
                        """.formatted(iso(18, 0))));
        when(scheduled.createReminder(any(), any(), any(), any())).thenAnswer(inv -> {
            ScheduledMessageService.ReminderSpec spec = inv.getArgument(3);
            ScheduledMessage task = new ScheduledMessage();
            task.setId(UUID.randomUUID());
            task.setTitle(spec.title());
            task.setScheduledAt(spec.firstAt());
            return task;
        });

        Optional<String> first = service.handle(bot(), chat(), "提醒我打卡");
        assertThat(first.orElse("")).contains("几点提醒你");
        assertThat(service.hasPendingAsk("wxid_demo")).isTrue();

        Optional<String> second = service.handle(bot(), chat(), "下午六点");

        assertThat(second).isPresent();
        assertThat(second.get()).contains("18:00");
        assertThat(service.hasPendingAsk("wxid_demo")).isFalse();
    }

    @Test
    void listsWaitingReminders() {
        ScheduledMessage task = new ScheduledMessage();
        task.setId(UUID.randomUUID());
        task.setTitle("打卡");
        task.setScheduledAt(OffsetDateTime.now().plusHours(1));
        task.setSentCount(2);
        task.setMaxTimes(6);
        task.setStatus(ScheduledStatus.PENDING);
        when(scheduled.waitingReminders("wxid_demo")).thenReturn(List.of(task));

        Optional<String> reply = service.handle(bot(), chat(), "我有哪些提醒");

        assertThat(reply).isPresent();
        assertThat(reply.get()).contains("打卡").contains("2/6");
    }

    @Test
    void stopIntentCancelsTheMatchingReminder() {
        ScheduledMessage task = new ScheduledMessage();
        task.setId(UUID.randomUUID());
        task.setTitle("打卡");
        task.setStatus(ScheduledStatus.PENDING);
        when(scheduled.waitingReminders("wxid_demo")).thenReturn(List.of(task));

        Optional<String> reply = service.handle(bot(), chat(), "把打卡那个提醒停掉");

        assertThat(reply.orElse("")).contains("不提醒了");
        verify(scheduled).stop(task.getId(), "聊天里让人设停掉");
    }

    @Test
    void modelFailureFallsBackToNormalChat() {
        when(llmGateway.chat(any(), anyList())).thenThrow(new IllegalStateException("模型超时"));

        assertThat(service.handle(bot(), chat(), "提醒我明天早上八点吃药")).isEmpty();
    }

    @Test
    void rejectsEverythingWhenTheFeatureIsOff() {
        properties.setCreateInChat(false);

        assertThat(service.handle(bot(), chat(), "每天六点提醒我打卡")).isEmpty();
        verify(llmGateway, never()).chat(any(), anyList());
    }

    @Test
    void adminPreviewReturnsADraftWithoutSaving() {
        when(llmGateway.chat(any(), anyList())).thenReturn(LlmReply.rule("""
                {"intent":"create","title":"打卡","firstAt":"%s","repeat":"NONE","weekdays":[],
                 "reminder":{"enabled":true,"everyMinutes":5,"maxTimes":6,"stopWords":["已打卡"]},
                 "askBack":null,"confidence":0.9}
                """.formatted(iso(18, 0))));
        when(textService.write(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new ReminderTextService.Script("该打卡了", "算了"));
        when(botRepository.findFirstByEnabledTrueOrderByCreatedAtAsc()).thenReturn(Optional.of(bot()));

        var draft = service.preview(null, "每天下午六点提醒我打卡，没回就催");

        assertThat(draft.ok()).isTrue();
        assertThat(draft.kind()).isEqualTo("REMINDER");
        assertThat(draft.title()).isEqualTo("打卡");
        assertThat(draft.variants()).isEqualTo("该打卡了");
        verify(scheduled, never()).createReminder(any(), any(), any(), any());
    }

    @Test
    void weeklyWordsBecomeACronExpression() {
        when(llmGateway.chat(any(), anyList())).thenReturn(LlmReply.rule("""
                {"intent":"create","title":"看邮件","firstAt":"%s","repeat":"WEEKLY",
                 "weekdays":[1,2,3,4,5],"reminder":{"enabled":false},"askBack":null,"confidence":0.9}
                """.formatted(iso(9, 0))));
        when(scheduled.createReminder(any(), any(), any(), any())).thenAnswer(inv -> {
            ScheduledMessageService.ReminderSpec spec = inv.getArgument(3);
            ScheduledMessage task = new ScheduledMessage();
            task.setId(UUID.randomUUID());
            task.setTitle(spec.title());
            task.setScheduledAt(spec.firstAt());
            task.setRepeatRule(spec.repeatRule());
            return task;
        });

        assertThat(service.handle(bot(), chat(), "工作日早上九点提醒我看邮件")).isPresent();

        ArgumentCaptor<ScheduledMessageService.ReminderSpec> captor =
                ArgumentCaptor.forClass(ScheduledMessageService.ReminderSpec.class);
        verify(scheduled).createReminder(any(), any(), any(), captor.capture());
        assertThat(captor.getValue().repeatRule()).isEqualTo(ScheduledRepeat.CRON);
        assertThat(captor.getValue().cronExpression()).isEqualTo("0 0 9 * * 1,2,3,4,5");
    }
}
