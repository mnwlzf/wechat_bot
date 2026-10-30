package com.example.wechatbot.repository;

import com.example.wechatbot.domain.PromptTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PromptTemplateRepository extends JpaRepository<PromptTemplate, UUID> {
    List<PromptTemplate> findAllByOrderByUpdatedAtDesc();
}
