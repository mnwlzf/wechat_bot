package com.example.wechatbot.web;

import com.example.wechatbot.service.AdminQueryService;
import com.example.wechatbot.web.dto.AdminDtos;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/admin")
public class AdminQueryController {
    private final AdminQueryService service;

    public AdminQueryController(AdminQueryService service) {
        this.service = service;
    }

    @GetMapping("/ping")
    public PingResponse ping() {
        return new PingResponse(true);
    }

    @GetMapping("/dashboard")
    public AdminDtos.DashboardView dashboard() {
        return service.dashboard();
    }

    @GetMapping("/conversations")
    public Page<AdminDtos.ConversationView> conversations(
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.conversations(query, page, size);
    }

    @GetMapping("/conversations/{id}/messages")
    public Page<AdminDtos.MessageView> messages(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size) {
        return service.messages(id, page, size);
    }

    public record PingResponse(boolean ok) {}
}
