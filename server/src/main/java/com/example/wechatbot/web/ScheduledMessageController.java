package com.example.wechatbot.web;

import com.example.wechatbot.domain.ScheduledMessage;
import com.example.wechatbot.service.ScheduledMessageService;
import com.example.wechatbot.web.dto.AdminDtos;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * 定时消息：预约时间发送，到点自动转入 outbox 投递链路。
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
                input.botId(), input.chatId(), input.chatName(), input.content(), input.scheduledAt()));
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
                message.getStatus().name(),
                message.getAttempts(),
                message.getLastError(),
                message.getCreatedAt(),
                message.getSentAt()
        );
    }
}
