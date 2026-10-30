package com.example.wechatbot.web;

import com.example.wechatbot.service.UsageService;
import com.example.wechatbot.web.dto.AdminDtos;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用量统计：收发量、token、耗时与模型分布。
 */
@RestController
@RequestMapping("/api/admin/usage")
public class UsageController {
    private final UsageService usageService;

    public UsageController(UsageService usageService) {
        this.usageService = usageService;
    }

    @GetMapping
    public AdminDtos.UsageView summary(@RequestParam(defaultValue = "7") int days) {
        return usageService.summary(days);
    }
}
