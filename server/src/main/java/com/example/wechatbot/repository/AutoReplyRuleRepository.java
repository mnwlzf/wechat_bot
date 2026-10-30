package com.example.wechatbot.repository;

import com.example.wechatbot.domain.AutoReplyRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AutoReplyRuleRepository extends JpaRepository<AutoReplyRule, UUID> {
    List<AutoReplyRule> findAllByOrderByPriorityAscCreatedAtAsc();

    List<AutoReplyRule> findByEnabledTrueOrderByPriorityAscCreatedAtAsc();
}
