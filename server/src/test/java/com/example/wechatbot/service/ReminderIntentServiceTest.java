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
    void vagueWaysOfAskingToChangeATaskStillReachTheModel() {
        // 线上真事：用户说「修改打卡时间」，预筛没认出来，这句话直接进了聊天，
        // 模型回了句「行。9:40 起」——听着像改好了，其实一个字都没动
        assertThat(service.mightBeInstruction("修改打卡时间")).isTrue();
        assertThat(service.mightBeInstruction("把提醒时间改一下")).isTrue();
        assertThat(service.mightBeInstruction("调整一下打卡提醒")).isTrue();
        assertThat(service.mightBeInstruction("改时间")).isTrue();
        assertThat(service.mightBeInstruction("换个时间催我")).isTrue();
        assertThat(service.mightBeInstruction("删掉打卡")).isTrue();

        // 普通聊天还是要挡住：只有「改」没有任务名词
        assertThat(service.mightBeInstruction("改天聊")).isFalse();
        assertThat(service.mightBeInstruction("这个方案我改了一下")).isFalse();
        assertThat(service.mightBeInstruction("今天有点忙")).isFalse();
    }

    @Test
    void askingWhichPartToChangeKeepsTheConversationGoing() {
        ScheduledMessage task = task("企业微信打卡", OffsetDateTime.now().plusHours(8));
        when(scheduled.waitingReminders("wxid_demo")).thenReturn(List.of(task));
        stubPatch(task);
        when(llmGateway.chat(any(), anyList()))
                .thenReturn(LlmReply.rule("""
                        {"intent":"update","target":"打卡","change":{},"confidence":0.7}
                        """))
                .thenReturn(LlmReply.rule("""
                        {"intent":"update","target":"打卡","change":{"firstAt":"%s"},"confidence":0.9}
                        """.formatted(iso(9, 40))));

        Optional<String> ask = service.handle(bot(), chat(), "修改打卡时间");
        assertThat(ask.orElse("")).contains("要改成什么");
        assertThat(service.hasPendingAsk("wxid_demo")).isTrue();

        Optional<String> done = service.handle(bot(), chat(), "早上九点四十");

        assertThat(done.orElse("")).contains("09:40");
        assertThat(task.getScheduledAt().atZoneSameInstant(java.time.ZoneId.of("Asia/Shanghai")).getHour())
                .isEqualTo(9);
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
        // 没要求「没回就催」就只发一条：之前这里默认 6 次，等于替人加了催命循环
        ArgumentCaptor<ScheduledMessageService.ReminderSpec> captor =
                ArgumentCaptor.forClass(ScheduledMessageService.ReminderSpec.class);
        verify(scheduled).createReminder(any(), any(), any(), captor.capture());
        assertThat(captor.getValue().maxTimes()).isEqualTo(1);
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
    void weeklyWordsBecomeACronExpression() {        when(llmGateway.chat(any(), anyList())).thenReturn(LlmReply.rule("""
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

    // ------------------------------------------------------------------ 改 / 删

    private ScheduledMessage task(String title, OffsetDateTime at) {
        ScheduledMessage task = new ScheduledMessage();
        task.setId(UUID.randomUUID());
        task.setKind(ScheduledKind.REMINDER);
        task.setTitle(title);
        task.setScheduledAt(at);
        task.setIntervalMinutes(5);
        task.setMaxTimes(6);
        task.setStopWords("已打卡,好了");
        task.setStatus(ScheduledStatus.PENDING);
        task.setSource(ScheduleSource.CHAT);
        task.setCreatorChatId("wxid_demo");
        return task;
    }

    /** 让 mock 的 patch 真的把补丁落到对象上，回读才有东西可读。 */
    private void stubPatch(ScheduledMessage target) {
        when(scheduled.patch(eq(target.getId()), any())).thenAnswer(inv -> {
            ScheduledMessageService.ReminderPatch patch = inv.getArgument(1);
            if (patch.firstAt() != null) target.setScheduledAt(patch.firstAt());
            if (patch.intervalMinutes() != null) target.setIntervalMinutes(patch.intervalMinutes());
            if (patch.maxTimes() != null) target.setMaxTimes(patch.maxTimes());
            if (patch.stopWords() != null) target.setStopWords(patch.stopWords());
            if (patch.title() != null) target.setTitle(patch.title());
            if (patch.variants() != null) target.setVariants(patch.variants());
            return target;
        });
    }

    @Test
    void changesTheTimeAndReadsTheFinalStateBack() {
        ScheduledMessage task = task("打卡", OffsetDateTime.now().plusHours(3));
        when(scheduled.waitingReminders("wxid_demo")).thenReturn(List.of(task));
        stubPatch(task);
        when(llmGateway.chat(any(), anyList())).thenReturn(LlmReply.rule("""
                {"intent":"update","target":"打卡","change":{"firstAt":"%s"},"askBack":null,"confidence":0.9}
                """.formatted(iso(9, 0))));

        Optional<String> reply = service.handle(bot(), chat(), "把打卡改到明天早上九点");

        assertThat(reply.orElse("")).contains("打卡").contains("09:00").contains("最多 6 次");

        ArgumentCaptor<ScheduledMessageService.ReminderPatch> captor =
                ArgumentCaptor.forClass(ScheduledMessageService.ReminderPatch.class);
        verify(scheduled).patch(eq(task.getId()), captor.capture());
        assertThat(captor.getValue().firstAt()).isNotNull();
        // 只改时间，别的项一个都不能动
        assertThat(captor.getValue().intervalMinutes()).isNull();
        assertThat(captor.getValue().maxTimes()).isNull();
        assertThat(captor.getValue().stopWords()).isNull();
        assertThat(task.getIntervalMinutes()).isEqualTo(5);
        assertThat(task.getMaxTimes()).isEqualTo(6);
    }

    @Test
    void changesTheFrequencyAndRewritesTheWording() {
        ScheduledMessage task = task("打卡", OffsetDateTime.now().plusHours(3));
        when(scheduled.waitingReminders("wxid_demo")).thenReturn(List.of(task));
        stubPatch(task);
        when(textService.write(any(), any(), eq("打卡"), eq("已打卡"), eq(3)))
                .thenReturn(new ReminderTextService.Script("该打卡了", "算了，3 次都没回"));
        when(llmGateway.chat(any(), anyList())).thenReturn(LlmReply.rule("""
                {"intent":"update","target":"打卡","change":{"everyMinutes":10,"maxTimes":3},
                 "askBack":null,"confidence":0.9}
                """));

        Optional<String> reply = service.handle(bot(), chat(), "打卡改成每十分钟催一次，最多三次");

        assertThat(reply.orElse("")).contains("每 10 分钟").contains("最多 3 次");
        assertThat(task.getIntervalMinutes()).isEqualTo(10);
        assertThat(task.getMaxTimes()).isEqualTo(3);
        // 文案要跟着重写，否则「回「已打卡」我就不催了」会变成假话
        assertThat(task.getVariants()).isEqualTo("该打卡了");
    }

    @Test
    void deletesTheTask() {
        ScheduledMessage task = task("打卡", OffsetDateTime.now().plusHours(3));
        when(scheduled.waitingReminders("wxid_demo")).thenReturn(List.of(task));
        when(llmGateway.chat(any(), anyList())).thenReturn(LlmReply.rule("""
                {"intent":"delete","target":"打卡","confidence":0.9}
                """));

        Optional<String> reply = service.handle(bot(), chat(), "删掉打卡那个");

        assertThat(reply.orElse("")).contains("删掉了");
        verify(scheduled).stop(task.getId(), "聊天里让人删掉");
    }

    @Test
    void adminTasksAreNotEditableFromChat() {
        ScheduledMessage task = task("周报", OffsetDateTime.now().plusHours(3));
        task.setSource(ScheduleSource.ADMIN);
        task.setCreatorChatId(null);
        when(scheduled.waitingReminders("wxid_demo")).thenReturn(List.of(task));
        when(llmGateway.chat(any(), anyList())).thenReturn(LlmReply.rule("""
                {"intent":"update","target":"周报","change":{"everyMinutes":10},"confidence":0.9}
                """));

        Optional<String> reply = service.handle(bot(), chat(), "把周报提醒改成十分钟催一次");

        assertThat(reply.orElse("")).contains("没有能改的");
        verify(scheduled, never()).patch(any(), any());
    }

    @Test
    void severalTasksAskWhichOneThenTheNumberPicksIt() {
        ScheduledMessage first = task("打卡", OffsetDateTime.now().plusHours(2));
        ScheduledMessage second = task("交周报", OffsetDateTime.now().plusHours(4));
        when(scheduled.waitingReminders("wxid_demo")).thenReturn(List.of(first, second));
        stubPatch(second);
        when(llmGateway.chat(any(), anyList())).thenReturn(LlmReply.rule("""
                {"intent":"update","target":null,"change":{"everyMinutes":15},"confidence":0.6}
                """));

        Optional<String> ask = service.handle(bot(), chat(), "把提醒改成十五分钟催一次");

        assertThat(ask.orElse("")).contains("要改哪个").contains("1. 打卡").contains("2. 交周报");
        assertThat(service.hasPendingAsk("wxid_demo")).isTrue();
        // 等序号期间不能让回执逻辑先把「2」吃掉
        assertThat(service.awaitingChoice("wxid_demo")).isTrue();

        Optional<String> done = service.handle(bot(), chat(), "2");

        assertThat(done.orElse("")).contains("交周报").contains("每 15 分钟");
        assertThat(second.getIntervalMinutes()).isEqualTo(15);
        assertThat(first.getIntervalMinutes()).isEqualTo(5);
        assertThat(service.awaitingChoice("wxid_demo")).isFalse();
        // 序号回复不该再花一次模型调用
        verify(llmGateway).chat(any(), anyList());
    }

    @Test
    void numberRepliesAreOnlyReadAsChoicesWhenWeAsked() {
        assertThat(ReminderIntentService.parseChoice("2")).isEqualTo(2);
        assertThat(ReminderIntentService.parseChoice("第二个")).isEqualTo(2);
        assertThat(ReminderIntentService.parseChoice("第 3 个。")).isEqualTo(3);
        assertThat(ReminderIntentService.parseChoice("今天下午三点")).isNull();
        assertThat(ReminderIntentService.parseChoice("好的")).isNull();
    }

    @Test
    void nothingToChangeWhenNoTaskIsWaiting() {
        when(scheduled.waitingReminders("wxid_demo")).thenReturn(List.of());
        when(llmGateway.chat(any(), anyList())).thenReturn(LlmReply.rule("""
                {"intent":"update","target":"打卡","change":{"everyMinutes":10},"confidence":0.9}
                """));

        assertThat(service.handle(bot(), chat(), "把打卡改成十分钟催一次").orElse(""))
                .contains("没有能改的提醒");
    }

    @Test
    void aPastTimeIsAskedBackInsteadOfApplied() {
        ScheduledMessage task = task("打卡", OffsetDateTime.now().plusHours(3));
        when(scheduled.waitingReminders("wxid_demo")).thenReturn(List.of(task));
        when(llmGateway.chat(any(), anyList())).thenReturn(LlmReply.rule("""
                {"intent":"update","target":"打卡","change":{"firstAt":"%s"},"confidence":0.9}
                """.formatted(OffsetDateTime.now().minusDays(1).toString())));

        Optional<String> reply = service.handle(bot(), chat(), "把打卡改到昨天十点");

        assertThat(reply.orElse("")).contains("已经过去");
        verify(scheduled, never()).patch(any(), any());
        assertThat(service.hasPendingAsk("wxid_demo")).isTrue();
    }
}
