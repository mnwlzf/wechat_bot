package com.example.wechatbot.service;

import com.example.wechatbot.ai.LlmGateway;
import com.example.wechatbot.ai.LlmReply;
import com.example.wechatbot.config.ReminderProperties;
import com.example.wechatbot.domain.BotConfig;
import com.example.wechatbot.domain.ChatType;
import com.example.wechatbot.domain.Conversation;
import com.example.wechatbot.domain.ScheduledKind;
import com.example.wechatbot.domain.ScheduledMessage;
import com.example.wechatbot.domain.ScheduledStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

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
 * 回执识别：先关键词、后模型，而且只在真有提醒在等的时候才动脑子。
 *
 * <p>这条链路的两个失败方向都很糟糕：漏判 = 一直催下去；误判 = 对方随口一句「好了」
 * 就把提醒关了，明天该打卡没人提醒。</p>
 */
class ReminderAckServiceTest {
    private final ReminderProperties properties = new ReminderProperties();
    private final ScheduledMessageService scheduled = mock(ScheduledMessageService.class);
    private final LlmGateway llmGateway = mock(LlmGateway.class);

    private final ReminderAckService service =
            new ReminderAckService(properties, scheduled, llmGateway, new ObjectMapper());

    private BotConfig bot() {
        BotConfig bot = new BotConfig();
        bot.setId(UUID.randomUUID());
        bot.setName("大肥鱼");
        return bot;
    }

    private Conversation chat() {
        Conversation conversation = new Conversation();
        conversation.setId(UUID.randomUUID());
        conversation.setChatId("wxid_demo");
        conversation.setChatType(ChatType.PRIVATE);
        return conversation;
    }

    private ScheduledMessage task(String title, String stopWords, int sentCount) {
        ScheduledMessage message = new ScheduledMessage();
        message.setId(UUID.randomUUID());
        message.setChatId("wxid_demo");
        message.setKind(ScheduledKind.REMINDER);
        message.setTitle(title);
        message.setStopWords(stopWords);
        message.setSentCount(sentCount);
        message.setSentAt(OffsetDateTime.now().minusMinutes(3));
        message.setStatus(ScheduledStatus.PENDING);
        return message;
    }

    @Test
    void doesNothingWithoutAnyWaitingReminder() {
        when(scheduled.waitingReminders("wxid_demo")).thenReturn(List.of());

        assertThat(service.handle(bot(), chat(), "已打卡")).isEmpty();
        verify(llmGateway, never()).chat(any(), anyList());
    }

    @Test
    void ignoresMessagesBeforeTheFirstNudge() {
        // 还没催过就不认回执：一句普通的「好了」不该把没到点的提醒毙掉
        when(scheduled.waitingReminders("wxid_demo")).thenReturn(List.of(task("打卡", "已打卡", 0)));

        assertThat(service.handle(bot(), chat(), "好了")).isEmpty();
    }

    @Test
    void stopWordHitsWithoutCallingTheModel() {
        ScheduledMessage pending = task("打卡", "已打卡,打完了", 2);
        when(scheduled.waitingReminders("wxid_demo")).thenReturn(List.of(pending));
        when(scheduled.ack(pending, "已打卡")).thenReturn(true);

        assertThat(service.handle(bot(), chat(), "已打卡").orElse("")).contains("不催了");
        verify(scheduled).ack(pending, "已打卡");
        verify(llmGateway, never()).chat(any(), anyList());
    }

    @Test
    void stopWordSurvivesSpacesAndPunctuation() {
        ScheduledMessage pending = task("打卡", "已打卡", 1);
        when(scheduled.waitingReminders("wxid_demo")).thenReturn(List.of(pending));
        when(scheduled.ack(any(), any())).thenReturn(true);

        assertThat(service.handle(bot(), chat(), "已 打卡。").orElse("")).contains("不催了");
        verify(scheduled).ack(pending, "已 打卡。");
    }

    @Test
    void naturalLanguageAckIsClassifiedByTheModel() {
        ScheduledMessage pending = task("打卡", "已打卡", 3);
        when(scheduled.waitingReminders("wxid_demo")).thenReturn(List.of(pending));
        when(llmGateway.chat(any(), anyList())).thenReturn(LlmReply.rule("{\"ack\":true,\"stop\":false}"));
        when(scheduled.ack(any(), any())).thenReturn(true);

        assertThat(service.handle(bot(), chat(), "刚弄完了").orElse("")).contains("不催了");
        verify(scheduled).ack(pending, "刚弄完了");
    }

    @Test
    void chatThatIsNotAnAckFallsThroughToTheNormalReply() {
        ScheduledMessage pending = task("打卡", "已打卡", 2);
        when(scheduled.waitingReminders("wxid_demo")).thenReturn(List.of(pending));
        when(llmGateway.chat(any(), anyList())).thenReturn(LlmReply.rule("{\"ack\":false,\"stop\":false}"));

        assertThat(service.handle(bot(), chat(), "今天天气不错")).isEmpty();
        verify(scheduled, never()).ack(any(), any());
    }

    @Test
    void askingToStopCancelsInsteadOfAcking() {
        ScheduledMessage pending = task("打卡", "已打卡", 1);
        when(scheduled.waitingReminders("wxid_demo")).thenReturn(List.of(pending));
        when(llmGateway.chat(any(), anyList())).thenReturn(LlmReply.rule("{\"ack\":false,\"stop\":true}"));

        assertThat(service.handle(bot(), chat(), "别催了，我这两天不打卡").orElse("")).contains("不提醒了");
        verify(scheduled).stopWaiting("wxid_demo");
        verify(scheduled, never()).ack(any(), any());
    }

    @Test
    void keywordMatchStillWorksWhenModelAckIsDisabled() {
        properties.setNlAck(false);
        ScheduledMessage pending = task("打卡", "已打卡", 1);
        when(scheduled.waitingReminders("wxid_demo")).thenReturn(List.of(pending));
        when(scheduled.ack(any(), any())).thenReturn(true);

        assertThat(service.handle(bot(), chat(), "已打卡")).isPresent();
        assertThat(service.handle(bot(), chat(), "弄好了")).isEmpty();
        verify(llmGateway, never()).chat(any(), anyList());
    }
}
