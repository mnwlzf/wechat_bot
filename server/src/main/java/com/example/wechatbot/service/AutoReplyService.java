package com.example.wechatbot.service;

import com.example.wechatbot.common.ApiException;
import com.example.wechatbot.domain.AutoReplyRule;
import com.example.wechatbot.domain.ChatType;
import com.example.wechatbot.repository.AutoReplyRuleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 关键词 / 正则自动回复。命中后直接返回固定话术，不进入模型链路。
 */
@Service
public class AutoReplyService {
    private static final Logger log = LoggerFactory.getLogger(AutoReplyService.class);

    private final AutoReplyRuleRepository repository;

    public AutoReplyService(AutoReplyRuleRepository repository) {
        this.repository = repository;
    }

    /** 按优先级返回第一条命中的规则。 */
    public Optional<AutoReplyRule> match(ChatType chatType, String content) {
        if (content == null || content.isBlank()) {
            return Optional.empty();
        }
        for (AutoReplyRule rule : repository.findByEnabledTrueOrderByPriorityAscCreatedAtAsc()) {
            if (!scopeMatches(rule.getScope(), chatType)) {
                continue;
            }
            if (matches(rule, content)) {
                return Optional.of(rule);
            }
        }
        return Optional.empty();
    }

    private boolean scopeMatches(String scope, ChatType chatType) {
        if (scope == null || scope.isBlank() || "ALL".equalsIgnoreCase(scope)) {
            return true;
        }
        if ("GROUP".equalsIgnoreCase(scope)) {
            return chatType == ChatType.GROUP;
        }
        if ("PRIVATE".equalsIgnoreCase(scope)) {
            return chatType == ChatType.PRIVATE;
        }
        return true;
    }

    boolean matches(AutoReplyRule rule, String content) {
        String type = rule.getMatchType() == null ? "CONTAINS" : rule.getMatchType().toUpperCase(Locale.ROOT);
        String pattern = rule.getPattern();
        if (pattern == null || pattern.isBlank()) {
            return false;
        }
        return switch (type) {
            case "EXACT" -> content.trim().equalsIgnoreCase(pattern.trim());
            case "REGEX" -> {
                try {
                    yield Pattern.compile(pattern, Pattern.CASE_INSENSITIVE | Pattern.DOTALL)
                            .matcher(content)
                            .find();
                } catch (PatternSyntaxException ex) {
                    log.warn("自动回复规则正则非法，已跳过: rule={} pattern={} error={}",
                            rule.getName(), pattern, ex.getDescription());
                    yield false;
                }
            }
            default -> content.toLowerCase(Locale.ROOT).contains(pattern.toLowerCase(Locale.ROOT));
        };
    }

    public List<AutoReplyRule> list() {
        return repository.findAllByOrderByPriorityAscCreatedAtAsc();
    }

    @Transactional
    public AutoReplyRule create(AutoReplyRule input) {
        validate(input);
        input.setId(null);
        return repository.save(input);
    }

    @Transactional
    public AutoReplyRule update(UUID id, AutoReplyRule input) {
        AutoReplyRule rule = repository.findById(id)
                .orElseThrow(() -> ApiException.notFound("自动回复规则不存在"));
        validate(input);
        rule.setName(input.getName());
        rule.setMatchType(input.getMatchType());
        rule.setPattern(input.getPattern());
        rule.setReply(input.getReply());
        rule.setScope(input.getScope());
        rule.setPriority(input.getPriority());
        rule.setEnabled(input.isEnabled());
        return repository.save(rule);
    }

    @Transactional
    public void delete(UUID id) {
        if (!repository.existsById(id)) {
            throw ApiException.notFound("自动回复规则不存在");
        }
        repository.deleteById(id);
    }

    private void validate(AutoReplyRule input) {
        if (input.getName() == null || input.getName().isBlank()) {
            throw ApiException.badRequest("规则名称不能为空");
        }
        if (input.getPattern() == null || input.getPattern().isBlank()) {
            throw ApiException.badRequest("匹配内容不能为空");
        }
        if (input.getReply() == null || input.getReply().isBlank()) {
            throw ApiException.badRequest("回复内容不能为空");
        }
        String type = input.getMatchType() == null ? "CONTAINS" : input.getMatchType().toUpperCase(Locale.ROOT);
        if (!List.of("EXACT", "CONTAINS", "REGEX").contains(type)) {
            throw ApiException.badRequest("不支持的匹配方式: " + type);
        }
        input.setMatchType(type);
        String scope = input.getScope() == null ? "ALL" : input.getScope().toUpperCase(Locale.ROOT);
        if (!List.of("ALL", "PRIVATE", "GROUP").contains(scope)) {
            throw ApiException.badRequest("不支持的适用范围: " + scope);
        }
        input.setScope(scope);
        if ("REGEX".equals(type)) {
            try {
                Pattern.compile(input.getPattern());
            } catch (PatternSyntaxException ex) {
                throw ApiException.badRequest("正则表达式不合法: " + ex.getDescription());
            }
        }
    }
}
