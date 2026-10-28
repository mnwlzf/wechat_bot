package com.example.wechatbot.repository;

import com.example.wechatbot.domain.Conversation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface ConversationRepository extends JpaRepository<Conversation, UUID> {
    Optional<Conversation> findByBotIdAndChatId(UUID botId, String chatId);

    @Query("""
            select c from Conversation c
            where :query is null
               or lower(c.chatName) like lower(concat('%', :query, '%'))
               or lower(c.chatId) like lower(concat('%', :query, '%'))
            order by c.lastMessageAt desc
            """)
    Page<Conversation> search(@Param("query") String query, Pageable pageable);
}
