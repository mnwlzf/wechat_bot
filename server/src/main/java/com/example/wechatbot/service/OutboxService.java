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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class OutboxService {
    private static final int MAX_ATTEMPTS = 5;
    private final OutboxMessageRepository repository;

    public OutboxService(OutboxMessageRepository repository) {
        this.repository = repository;
    }

    /**
     * 租约领取。
     *
     * <p>同一会话在同一时刻只允许一条消息在途：分条回复必须一条一条发，
     * 不能因为调度批量领取而变成"两条同时弹出"，那是最明显的机器特征。
     * 同会话的其他消息保持 PENDING，下一轮再取。</p>
     */
    @Transactional
    public List<AgentDtos.OutboxView> lease(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 50));
        OffsetDateTime now = OffsetDateTime.now();
        int fetch = Math.min(200, Math.max(safeLimit * 4, safeLimit));
        List<OutboxMessage> candidates = repository.findReadyForDelivery(
                OutboxStatus.PENDING, OutboxStatus.PROCESSING, now, PageRequest.of(0, fetch));

        Set<String> busyChats = new HashSet<>();
        Set<String> inFlightChats = new HashSet<>(repository.findActiveLeaseChatIds(OutboxStatus.PROCESSING, now));
        List<OutboxMessage> leased = new ArrayList<>(safeLimit);
        List<OutboxMessage> postponed = new ArrayList<>();

        for (OutboxMessage message : candidates) {
            if (leased.size() >= safeLimit) {
                break;
            }
            if (!busyChats.add(message.getChatId())) {
                postponed.add(message);
                continue;
            }
            if (inFlightChats.contains(message.getChatId())) {
                postponed.add(message);
                continue;
            }
            message.setStatus(OutboxStatus.PROCESSING);
            message.setLeaseUntil(now.plusSeconds(30));
            message.setAttempts(message.getAttempts() + 1);
            leased.add(message);
            inFlightChats.add(message.getChatId());
        }

        for (OutboxMessage message : postponed) {
            if (message.getStatus() == OutboxStatus.PENDING) {
                message.setNextAttemptAt(now.plusSeconds(2 + (long) (Math.random() * 2)));
            }
        }
        if (!postponed.isEmpty()) {
            repository.saveAll(postponed);
        }
        repository.saveAll(leased);
        return leased.stream().map(message -> new AgentDtos.OutboxView(
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
