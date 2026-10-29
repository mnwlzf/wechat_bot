package com.example.wechatbot.web;

import com.example.wechatbot.service.RuntimeSettingsService;
import com.example.wechatbot.web.dto.AdminDtos;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 运行时策略配置：改完立即生效，无需重启，也不依赖 .env。
 */
@RestController
@RequestMapping("/api/admin/settings")
public class SettingsController {
    private final RuntimeSettingsService settings;

    public SettingsController(RuntimeSettingsService settings) {
        this.settings = settings;
    }

    @GetMapping
    public Map<String, Object> view() {
        return settings.snapshot();
    }

    @PutMapping("/wechat")
    public Map<String, Object> updateWeChat(@RequestBody AdminDtos.WeChatPolicyUpdate request) {
        settings.updateChatPolicy(request.allowedChats(), request.acceptAll(), request.groupsEnabled());
        return settings.snapshot();
    }

    @PutMapping("/llm")
    public Map<String, Object> updateLlm(@RequestBody AdminDtos.LlmPolicyUpdate request) {
        settings.updateLlmPolicy(request.fallbackEnabled(), request.searchEnabled());
        return settings.snapshot();
    }
}
