package com.example.wechatbot.repository;

import com.example.wechatbot.domain.BotConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BotConfigRepository extends JpaRepository<BotConfig, UUID> {
    List<BotConfig> findAllByOrderByCreatedAtAsc();
    Optional<BotConfig> findFirstByEnabledTrueOrderByCreatedAtAsc();
    boolean existsByModelProviderId(UUID modelProviderId);
}
