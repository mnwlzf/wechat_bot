package com.example.wechatbot.web;

import com.example.wechatbot.domain.ScheduleSource;
import com.example.wechatbot.domain.ScheduledKind;
import com.example.wechatbot.domain.ScheduledMessage;
import com.example.wechatbot.domain.ScheduledRepeat;
import com.example.wechatbot.service.ReminderIntentService;
import com.example.wechatbot.service.ScheduledMessageService;
import com.example.wechatbot.web.dto.AdminDtos;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * 定时任务：定时消息（到点发）与提醒（等回执、按间隔催）。
 *
 * <p>时间点、接收人、内容也可以在聊天里用一句话创建，那几个入口走
 * {@link ReminderIntentService}；这里负责管理端的手动编辑与「一句话创建」的草稿解析。</p>
 */
@RestController
@RequestMapping("/api/admin/scheduled-messages")
public class ScheduledMessageController {
    private final ScheduledMessageService service;
    private final ReminderIntentService reminderIntent;

    public ScheduledMessageController(ScheduledMessageService service, ReminderIntentService reminderIntent) {
        this.service = service;
        this.reminderIntent = reminderIntent;
    }

    @GetMapping
    public Page<AdminDtos.ScheduledMessageView> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String kind) {
        Page<ScheduledMessage> result = ScheduledKind.parse(kind) == ScheduledKind.REMINDER
                ? service.listReminders(page, size)
                : service.list(page, size);
        return result.map(this::toView);
    }

    @PostMapping
    public AdminDtos.ScheduledMessageView create(@RequestBody AdminDtos.ScheduledMessageInput input) {
        if (ScheduledKind.parse(input.kind()) == ScheduledKind.REMINDER) {
            return toView(service.createReminder(input.botId(), input.chatId(), input.chatName(),
                    new ScheduledMessageService.ReminderSpec(
                            input.title(), input.scheduledAt(), input.intervalMinutes(), input.maxTimes(),
                            input.stopWords(), input.content(), input.variants(), input.finalMessage(),
                            ScheduledRepeat.parse(input.repeatRule()), input.cronExpression(), input.repeatUntil(),
                            ScheduleSource.ADMIN, null, null)));
        }
        return toView(service.create(
                input.botId(), input.chatId(), input.chatName(), input.content(), input.scheduledAt(),
                ScheduledRepeat.parse(input.repeatRule()), input.repeatUntil(),
                Boolean.TRUE.equals(input.splitLines()), input.cronExpression()));
    }

    @PutMapping("/{id}")
    public AdminDtos.ScheduledMessageView update(@PathVariable UUID id,
                                                 @RequestBody AdminDtos.ScheduledMessageInput input) {
        return toView(service.update(id,
                input.botId(), input.chatId(), input.chatName(), input.content(), input.scheduledAt(),
                ScheduledRepeat.parse(input.repeatRule()), input.repeatUntil(),
                Boolean.TRUE.equals(input.splitLines()), input.cronExpression()));
    }

    @PostMapping("/{id}/cancel")
    public AdminDtos.ScheduledMessageView cancel(@PathVariable UUID id) {
        return toView(service.cancel(id));
    }

    /** 停止：待发的和正在等回执的提醒都能停。 */
    @PostMapping("/{id}/stop")
    public AdminDtos.ScheduledMessageView stop(@PathVariable UUID id) {
        return toView(service.stop(id, "管理端手动停止"));
    }

    /** 一句话创建（草稿）：只解析，不落库。 */
    @PostMapping("/parse")
    public AdminDtos.ReminderParseView parse(@RequestBody AdminDtos.ReminderParseInput input) {
        return reminderIntent.preview(input.botId(), input.instruction());
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }

    private AdminDtos.ScheduledMessageView toView(ScheduledMessage message) {
        return new AdminDtos.ScheduledMessageView(
                message.getId(),
                message.getBotId(),
                message.getChatId(),
                message.getChatName(),
                message.getContent(),
                message.getScheduledAt(),
                message.getRepeatRule() == null ? ScheduledRepeat.NONE.name() : message.getRepeatRule().name(),
                message.getRepeatUntil(),
                message.getCronExpression(),
                message.isSplitLines(),
                message.getSentCount(),
                message.getStatus() == null ? "PENDING" : message.getStatus().name(),
                message.getAttempts(),
                message.getLastError(),
                message.getCreatedAt(),
                message.getSentAt(),
                message.getKind() == null ? ScheduledKind.MESSAGE.name() : message.getKind().name(),
                message.getTitle(),
                message.getIntervalMinutes(),
                message.getMaxTimes(),
                message.getStopWords(),
                message.getVariants(),
                message.getFinalMessage(),
                message.getAckAt(),
                message.getAckContent(),
                message.getSource() == null ? ScheduleSource.ADMIN.name() : message.getSource().name(),
                message.getRawInstruction()
        );
    }
}
