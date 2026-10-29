package com.example.wechatbot.repository;

import com.example.wechatbot.domain.ModelProvider;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ModelProviderRepository extends JpaRepository<ModelProvider, UUID> {
    List<ModelProvider> findAllByOrderByPriorityAscCreatedAtAsc();
    List<ModelProvider> findByEnabledTrueOrderByPriorityAscCreatedAtAsc();
    Optional<ModelProvider> findFirstByName(String name);
}
