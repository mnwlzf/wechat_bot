package com.example.wechatbot.web;

import com.example.wechatbot.domain.ScheduledMessage;
import com.example.wechatbot.domain.ScheduledRepeat;
import com.example.wechatbot.service.ScheduledMessageService;
import com.example.wechatbot.web.dto.AdminDtos;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * 定时消息：预约时间发送，到点自动转入 outbox 投递链路。
 *
 * <p>时间点、接收人/群、内容全部由管理端填写；支持编辑待发送的消息、每天/每周重复，
 * 以及把内容按行拆成多条依次发送。</p>
 */
@RestController
@RequestMapping("/api/admin/scheduled-messages")
public class ScheduledMessageController {
    private final ScheduledMessageService service;

    public ScheduledMessageController(ScheduledMessageService service) {
        this.service = service;
    }

    @GetMapping
    public Page<AdminDtos.ScheduledMessageView> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.list(page, size).map(this::toView);
    }

    @PostMapping
    public AdminDtos.ScheduledMessageView create(@RequestBody AdminDtos.ScheduledMessageInput input) {
        return toView(service.create(
                input.botId(), input.chatId(), input.chatName(), input.content(), input.scheduledAt(),
                ScheduledRepeat.parse(input.repeatRule()), input.repeatUntil(),
                Boolean.TRUE.equals(input.splitLines())));
    }

    @PutMapping("/{id}")
    public AdminDtos.ScheduledMessageView update(@PathVariable UUID id,
                                                 @RequestBody AdminDtos.ScheduledMessageInput input) {
        return toView(service.update(id,
                input.botId(), input.chatId(), input.chatName(), input.content(), input.scheduledAt(),
                ScheduledRepeat.parse(input.repeatRule()), input.repeatUntil(),
                Boolean.TRUE.equals(input.splitLines())));
    }

    @PostMapping("/{id}/cancel")
    public AdminDtos.ScheduledMessageView cancel(@PathVariable UUID id) {
        return toView(service.cancel(id));
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
                message.isSplitLines(),
                message.getSentCount(),
                message.getStatus().name(),
                message.getAttempts(),
                message.getLastError(),
                message.getCreatedAt(),
                message.getSentAt()
        );
    }
}
