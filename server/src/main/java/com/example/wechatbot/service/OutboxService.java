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

    /**
     * 直接作废：给非真人会话（公众号 / 机器人 / 系统号）用。
     *
     * <p>这类会话永远不该收到回复，入队前已经拦过一次，这里是投递前的最后一道防线：
     * 直接标记 FAILED 并写明原因，不重试，免得同一条消息反复排队。</p>
     */
    @Transactional
    public void cancel(UUID id, String reason) {
        OutboxMessage message = repository.findById(id)
                .orElseThrow(() -> ApiException.notFound("回复消息不存在"));
        if (message.getStatus() == OutboxStatus.SENT) {
            return;
        }
        message.setStatus(OutboxStatus.FAILED);
        message.setLeaseUntil(null);
        message.setLastError(abbreviate(reason == null ? "已作废" : reason, 1000));
        repository.save(message);
    }

    private String abbreviate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }

    /** 诊断页用：最近失败的投递任务。 */
    public List<OutboxMessage> failed(int limit) {
        return repository.findByStatusOrderByCreatedAtDesc(
                OutboxStatus.FAILED, PageRequest.of(0, Math.max(1, Math.min(limit, 100))));
    }

    public long failedCount() {
        return repository.countByStatus(OutboxStatus.FAILED);
    }

    /** 手动重试：清空重试次数与租约，让它重新进入投递队列。 */
    @Transactional
    public OutboxMessage retry(UUID id) {
        OutboxMessage message = repository.findById(id)
                .orElseThrow(() -> ApiException.notFound("回复消息不存在"));
        if (message.getStatus() == OutboxStatus.SENT) {
            throw ApiException.badRequest("该消息已发送成功，无需重试");
        }
        message.setStatus(OutboxStatus.PENDING);
        message.setAttempts(0);
        message.setLeaseUntil(null);
        message.setLastError(null);
        message.setNextAttemptAt(OffsetDateTime.now());
        return repository.save(message);
    }
}
