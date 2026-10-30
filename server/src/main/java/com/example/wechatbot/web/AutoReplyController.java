package com.example.wechatbot.web;

import com.example.wechatbot.domain.AutoReplyRule;
import com.example.wechatbot.service.AutoReplyService;
import com.example.wechatbot.web.dto.AdminDtos;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * 关键词 / 正则自动回复规则：命中后秒回，不消耗模型额度。
 */
@RestController
@RequestMapping("/api/admin/auto-replies")
public class AutoReplyController {
    private final AutoReplyService service;

    public AutoReplyController(AutoReplyService service) {
        this.service = service;
    }

    @GetMapping
    public List<AdminDtos.AutoReplyRuleView> list() {
        return service.list().stream().map(this::toView).toList();
    }

    @PostMapping
    public AdminDtos.AutoReplyRuleView create(@RequestBody AdminDtos.AutoReplyRuleInput input) {
        return toView(service.create(toEntity(input)));
    }

    @PutMapping("/{id}")
    public AdminDtos.AutoReplyRuleView update(@PathVariable UUID id,
                                              @RequestBody AdminDtos.AutoReplyRuleInput input) {
        return toView(service.update(id, toEntity(input)));
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }

    private AutoReplyRule toEntity(AdminDtos.AutoReplyRuleInput input) {
        AutoReplyRule rule = new AutoReplyRule();
        rule.setName(input.name());
        rule.setMatchType(input.matchType());
        rule.setPattern(input.pattern());
        rule.setReply(input.reply());
        rule.setScope(input.scope());
        rule.setPriority(input.priority());
        rule.setEnabled(input.enabled());
        return rule;
    }

    private AdminDtos.AutoReplyRuleView toView(AutoReplyRule rule) {
        return new AdminDtos.AutoReplyRuleView(
                rule.getId(),
                rule.getName(),
                rule.getMatchType(),
                rule.getPattern(),
                rule.getReply(),
                rule.getScope(),
                rule.getPriority(),
                rule.isEnabled(),
                rule.getUpdatedAt()
        );
    }
}
