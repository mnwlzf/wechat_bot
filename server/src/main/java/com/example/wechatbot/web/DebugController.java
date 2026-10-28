package com.example.wechatbot.web;

import com.example.wechatbot.service.DebugChatService;
import com.example.wechatbot.web.dto.AdminDtos;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/debug")
public class DebugController {
    private final DebugChatService service;

    public DebugController(DebugChatService service) {
        this.service = service;
    }

    @PostMapping("/chat")
    public AdminDtos.DebugResponse chat(@Valid @RequestBody AdminDtos.DebugRequest request) {
        return service.chat(request);
    }
}
