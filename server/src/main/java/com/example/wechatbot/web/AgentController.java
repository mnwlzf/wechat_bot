package com.example.wechatbot.web;

import com.example.wechatbot.service.InboxService;
import com.example.wechatbot.service.OutboxService;
import com.example.wechatbot.web.dto.AgentDtos;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/agent")
public class AgentController {
    private final InboxService inboxService;
    private final OutboxService outboxService;

    public AgentController(InboxService inboxService, OutboxService outboxService) {
        this.inboxService = inboxService;
        this.outboxService = outboxService;
    }

    @PostMapping("/events")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public AgentDtos.EventAccepted acceptEvent(@Valid @RequestBody AgentDtos.AgentEventRequest request) {
        return inboxService.accept(request);
    }

    @GetMapping("/outbox")
    public List<AgentDtos.OutboxView> leaseOutbox(@RequestParam(defaultValue = "20") int limit) {
        return outboxService.lease(limit);
    }

    @PostMapping("/outbox/{id}/ack")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void ack(@PathVariable UUID id, @Valid @RequestBody AgentDtos.OutboxAck request) {
        outboxService.ack(id, request.success(), request.error());
    }
}
