package com.example.wechatbot.repository;

import com.example.wechatbot.domain.ChatMemory;
import com.example.wechatbot.domain.MemoryKind;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChatMemoryRepository extends JpaRepository<ChatMemory, UUID> {
    List<ChatMemory> findAllByOrderByUpdatedAtDesc();

    List<ChatMemory> findByChatIdOrderByKindAscUpdatedAtDesc(String chatId);

    List<ChatMemory> findByChatIdAndKindAndEnabledTrueOrderByUpdatedAtDesc(String chatId, MemoryKind kind);

    Optional<ChatMemory> findFirstByChatIdAndKind(String chatId, MemoryKind kind);

    long countByChatIdAndKind(String chatId, MemoryKind kind);
}
