package com.example.wechatbot.repository;

import com.example.wechatbot.domain.ReplyPolicy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReplyPolicyRepository extends JpaRepository<ReplyPolicy, UUID> {

    /** 全局默认回复策略（最多一条）。 */
    Optional<ReplyPolicy> findByChatIdIsNull();

    Optional<ReplyPolicy> findByChatId(String chatId);

    List<ReplyPolicy> findAllByChatIdIsNotNullOrderByUpdatedAtDesc();
}
