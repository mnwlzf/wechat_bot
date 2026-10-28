package com.example.wechatbot.service;

import com.example.wechatbot.common.ApiException;
import com.example.wechatbot.domain.OutboxMessage;
import com.example.wechatbot.domain.OutboxStatus;
import com.example.wechatbot.repository.OutboxMessageRepository;
import com.example.wechatbot.web.dto.AgentDtos;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class OutboxService {
    private static final int MAX_ATTEMPTS = 5;
    private final OutboxMessageRepository repository;

    public OutboxService(OutboxMessageRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public List<AgentDtos.OutboxView> lease(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 50));
        OffsetDateTime now = OffsetDateTime.now();
        List<OutboxMessage> messages = repository.findReadyForDelivery(
                OutboxStatus.PENDING, OutboxStatus.PROCESSING, now, PageRequest.of(0, safeLimit));
        for (OutboxMessage message : messages) {
            message.setStatus(OutboxStatus.PROCESSING);
            message.setLeaseUntil(now.plusSeconds(30));
            message.setAttempts(message.getAttempts() + 1);
        }
        repository.saveAll(messages);
        return messages.stream().map(message -> new AgentDtos.OutboxView(
                message.getId(),
                message.getConversationId(),
                message.getChatId(),
                message.getContent(),
                message.getAttempts(),
                message.getCreatedAt()
        )).toList();
    }

    @Transactional
    public void ack(UUID id, boolean success, String error) {
        OutboxMessage message = repository.findById(id)
                .orElseThrow(() -> ApiException.notFound("回复消息不存在"));
        if (message.getStatus() == OutboxStatus.SENT) {
            return;
        }
        if (success) {
            message.setStatus(OutboxStatus.SENT);
            message.setLeaseUntil(null);
            message.setLastError(null);
            message.setNextAttemptAt(OffsetDateTime.now());
        } else {
            message.setLastError(abbreviate(error == null ? "发送失败" : error, 1000));
            message.setLeaseUntil(null);
            if (message.getAttempts() >= MAX_ATTEMPTS) {
                message.setStatus(OutboxStatus.FAILED);
            } else {
                message.setStatus(OutboxStatus.PENDING);
                long delaySeconds = Math.min(300, 10L * (1L << Math.max(0, message.getAttempts() - 1)));
                message.setNextAttemptAt(OffsetDateTime.now().plusSeconds(delaySeconds));
            }
        }
        repository.save(message);
    }

    private String abbreviate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }
}
