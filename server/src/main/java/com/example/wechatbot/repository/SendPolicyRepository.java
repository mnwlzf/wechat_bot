package com.example.wechatbot.repository;

import com.example.wechatbot.domain.SendPolicy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SendPolicyRepository extends JpaRepository<SendPolicy, UUID> {

    Optional<SendPolicy> findByChatIdIsNull();

    Optional<SendPolicy> findByChatId(String chatId);

    boolean existsByChatId(String chatId);

    List<SendPolicy> findAllByChatIdIsNotNullOrderByUpdatedAtDesc();
}
