package com.example.wechatbot.service;

import com.example.wechatbot.domain.ScheduledKind;
import com.example.wechatbot.domain.ScheduledMessage;
import com.example.wechatbot.domain.ScheduledStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 列表里「今天」和「回话」两列：今天发没发、催了几次、对方回没回。
 *
 * <p>这些都是现算的派生值，最容易错在跨天——昨天的回执不能算成今天回话，
 * 明天才发的任务不能显示成「待发送」。</p>
 */
class ScheduledTodayTest {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 今天某个钟点（Asia/Shanghai）。 */
    private static OffsetDateTime today(int hour, int minute) {
        return LocalDate.now(ZONE).atTime(hour, minute).atZone(ZONE).toOffsetDateTime();
    }

    private static ScheduledMessage task(ScheduledKind kind) {
        ScheduledMessage task = new ScheduledMessage();
        task.setId(UUID.randomUUID());
        task.setKind(kind);
        task.setChatId("wxid_demo");
        task.setChatName("测试用户");
        task.setStatus(ScheduledStatus.PENDING);
        task.setMaxTimes(6);
        task.setIntervalMinutes(5);
        return task;
    }

    @Test
    void beforeTodaysRoundSaysPending() {
        ScheduledMessage task = task(ScheduledKind.REMINDER);
        task.setScheduledAt(today(18, 0));

        ScheduledMessageService.Today today = ScheduledMessageService.today(task, OffsetDateTime.now());

        assertThat(today.status()).isEqualTo("PENDING");
        assertThat(today.text()).isEqualTo("待发送");
        assertThat(today.sentCount()).isZero();
        assertThat(today.replied()).isNull();
    }

    @Test
    void afterNudgesSaysHowManyAndThatNobodyAnswered() {
        ScheduledMessage task = task(ScheduledKind.REMINDER);
        task.setScheduledAt(today(18, 10));
        task.setSentAt(OffsetDateTime.now().minusMinutes(2));
        task.setSentCount(3);

        ScheduledMessageService.Today today = ScheduledMessageService.today(task, OffsetDateTime.now());

        assertThat(today.status()).isEqualTo("NUDGING");
        assertThat(today.text()).isEqualTo("已催 3 次，等回话");
        assertThat(today.sentCount()).isEqualTo(3);
        assertThat(today.replied()).isFalse();
        assertThat(today.repliedText()).isEqualTo("还没回");
    }

    @Test
    void aRepliedRoundShowsDoneToday() {
        // 每天的任务：回完话状态还是 PENDING，只是排到了明天
        ScheduledMessage task = task(ScheduledKind.REMINDER);
        task.setScheduledAt(today(18, 0).plusDays(1));
        task.setSentAt(OffsetDateTime.now().minusMinutes(3));
        task.setSentCount(1);
        task.setAckAt(OffsetDateTime.now().minusMinutes(2));
        task.setAckContent("已打卡");

        ScheduledMessageService.Today today = ScheduledMessageService.today(task, OffsetDateTime.now());

        assertThat(today.status()).isEqualTo("DONE");
        assertThat(today.text()).isEqualTo("今天已完成");
        assertThat(today.replied()).isTrue();
        assertThat(today.repliedText()).isEqualTo("今天回话了");
    }

    @Test
    void yesterdaysReplyNeverCountsAsTodays() {
        ScheduledMessage task = task(ScheduledKind.REMINDER);
        task.setScheduledAt(today(18, 0));
        task.setSentAt(OffsetDateTime.now().minusDays(1));
        task.setAckAt(OffsetDateTime.now().minusDays(1));
        task.setSentCount(0);

        ScheduledMessageService.Today today = ScheduledMessageService.today(task, OffsetDateTime.now());

        assertThat(today.replied()).isNull();
        assertThat(today.sentCount()).isZero();
        assertThat(today.status()).isEqualTo("PENDING");
    }

    @Test
    void aTaskWhoseNextRoundIsTomorrowSaysNothingToday() {
        ScheduledMessage task = task(ScheduledKind.MESSAGE);
        task.setScheduledAt(today(18, 0).plusDays(1));

        ScheduledMessageService.Today today = ScheduledMessageService.today(task, OffsetDateTime.now());

        assertThat(today.status()).isEqualTo("IDLE");
        assertThat(today.text()).isEqualTo("今天不发");
    }

    @Test
    void aSentOneShotMessageSaysSentToday() {
        ScheduledMessage task = task(ScheduledKind.MESSAGE);
        task.setScheduledAt(today(9, 0));
        task.setStatus(ScheduledStatus.SENT);
        task.setSentAt(today(9, 0));
        task.setSentCount(1);

        ScheduledMessageService.Today today = ScheduledMessageService.today(task, OffsetDateTime.now());

        assertThat(today.status()).isEqualTo("SENT");
        assertThat(today.replied()).isNull();
        assertThat(today.sentCount()).isEqualTo(1);
    }

    @Test
    void givingUpShowsTheCapAndThatNobodyReplied() {
        ScheduledMessage task = task(ScheduledKind.REMINDER);
        task.setScheduledAt(today(18, 30));
        task.setSentAt(OffsetDateTime.now().minusMinutes(1));
        task.setSentCount(6);
        task.setStatus(ScheduledStatus.EXPIRED);

        ScheduledMessageService.Today today = ScheduledMessageService.today(task, OffsetDateTime.now());

        assertThat(today.status()).isEqualTo("GAVE_UP");
        assertThat(today.text()).contains("催满 6 次");
        assertThat(today.replied()).isFalse();
        assertThat(today.repliedText()).isEqualTo("一直没回");
    }

    @Test
    void aStoppedTaskSaysStopped() {
        ScheduledMessage task = task(ScheduledKind.REMINDER);
        task.setScheduledAt(today(18, 0));
        task.setStatus(ScheduledStatus.CANCELLED);

        ScheduledMessageService.Today today = ScheduledMessageService.today(task, OffsetDateTime.now());

        assertThat(today.status()).isEqualTo("STOPPED");
        assertThat(today.text()).isEqualTo("已停止");
    }

    @Test
    void theDayBoundaryIsAShanghaiMidnight() {
        // 昨天 23:59 发的那次不算今天；今天 00:01 发的才算今天
        OffsetDateTime todayEarly = LocalDate.now(ZONE).atTime(0, 1).atZone(ZONE).toOffsetDateTime();
        OffsetDateTime yesterdayLate = LocalDate.now(ZONE).minusDays(1).atTime(23, 59).atZone(ZONE).toOffsetDateTime();

        ScheduledMessage lastNight = task(ScheduledKind.REMINDER);
        lastNight.setSentAt(yesterdayLate);
        lastNight.setSentCount(2);
        lastNight.setScheduledAt(today(9, 0));
        ScheduledMessage thisMorning = task(ScheduledKind.REMINDER);
        thisMorning.setSentAt(todayEarly);
        thisMorning.setSentCount(1);
        thisMorning.setScheduledAt(today(0, 6));

        assertThat(ScheduledMessageService.today(lastNight, todayEarly).status()).isEqualTo("PENDING");
        assertThat(ScheduledMessageService.today(thisMorning, todayEarly).status()).isEqualTo("NUDGING");
    }
}
