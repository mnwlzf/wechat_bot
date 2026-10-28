package com.example.wechatbot.repository;

import com.example.wechatbot.domain.WeChatHookCursor;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface WeChatHookCursorRepository extends JpaRepository<WeChatHookCursor, UUID> {
    Optional<WeChatHookCursor> findByAccountAndChatId(String account, String chatId);
}
