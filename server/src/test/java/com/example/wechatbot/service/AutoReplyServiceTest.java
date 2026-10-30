package com.example.wechatbot.service;

import com.example.wechatbot.domain.AutoReplyRule;
import com.example.wechatbot.domain.ChatType;
import com.example.wechatbot.repository.AutoReplyRuleRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AutoReplyServiceTest {
    private final AutoReplyRuleRepository repository = mock(AutoReplyRuleRepository.class);
    private final AutoReplyService service = new AutoReplyService(repository);

    private AutoReplyRule rule(String name, String type, String pattern, String scope, int priority) {
        AutoReplyRule rule = new AutoReplyRule();
        rule.setName(name);
        rule.setMatchType(type);
        rule.setPattern(pattern);
        rule.setReply(name + "-回复");
        rule.setScope(scope);
        rule.setPriority(priority);
        rule.setEnabled(true);
        return rule;
    }

    @Test
    void matchesExactContainsAndRegex() {
        AutoReplyRule exact = rule("exact", "EXACT", "在吗", "ALL", 10);
        assertThat(service.matches(exact, "  在吗 ")).isTrue();
        assertThat(service.matches(exact, "在吗？")).isFalse();

        AutoReplyRule contains = rule("contains", "CONTAINS", "价格", "ALL", 20);
        assertThat(service.matches(contains, "请问价格是多少")).isTrue();
        assertThat(service.matches(contains, "PRICE")).isFalse();

        AutoReplyRule regex = rule("regex", "REGEX", "(发票|开票)", "ALL", 30);
        assertThat(service.matches(regex, "可以开发票吗")).isTrue();
        assertThat(service.matches(regex, "可以开收据吗")).isFalse();
    }

    @Test
    void invalidRegexNeverMatches() {
        AutoReplyRule broken = rule("broken", "REGEX", "([", "ALL", 1);
        assertThat(service.matches(broken, "任意内容")).isFalse();
    }

    @Test
    void honorsScopeAndPriority() {
        AutoReplyRule groupOnly = rule("group", "CONTAINS", "报名", "GROUP", 1);
        when(repository.findByEnabledTrueOrderByPriorityAscCreatedAtAsc()).thenReturn(List.of(groupOnly));

        assertThat(service.match(ChatType.GROUP, "我要报名")).isPresent();
        assertThat(service.match(ChatType.PRIVATE, "我要报名")).isEmpty();

        AutoReplyRule first = rule("first", "CONTAINS", "你好", "ALL", 1);
        when(repository.findByEnabledTrueOrderByPriorityAscCreatedAtAsc())
                .thenReturn(List.of(first, groupOnly));
        Optional<AutoReplyRule> matched = service.match(ChatType.GROUP, "你好，报名");
        assertThat(matched).map(AutoReplyRule::getName).contains("first");
    }

    @Test
    void ignoresBlankContent() {
        assertThat(service.match(ChatType.GROUP, "   ")).isEmpty();
        assertThat(service.match(ChatType.PRIVATE, null)).isEmpty();
    }
}
