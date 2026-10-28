package com.example.wechatbot.web;

import com.example.wechatbot.service.BotAdminService;
import com.example.wechatbot.web.dto.AdminDtos;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/bots")
public class BotController {
    private final BotAdminService service;

    public BotController(BotAdminService service) {
        this.service = service;
    }

    @GetMapping
    public List<AdminDtos.BotView> list() {
        return service.list();
    }

    @PutMapping("/{id}")
    public AdminDtos.BotView update(@PathVariable UUID id, @Valid @RequestBody AdminDtos.BotUpdate request) {
        return service.update(id, request);
    }
}
