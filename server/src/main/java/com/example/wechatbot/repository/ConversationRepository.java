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

    Page<Conversation> findAllByOrderByLastMessageAtDesc(Pageable pageable);

    /**
     * 关键词检索。刻意不写成 {@code :query is null or ...} 的单条查询：
     * PostgreSQL 无法推断绑定为 NULL 的参数类型，会退化成 bytea 并报
     * {@code function lower(bytea) does not exist}，调用方需按关键词是否为空选择方法。
     */
    @Query("""
            select c from Conversation c
            where lower(c.chatName) like lower(concat('%', :query, '%'))
               or lower(c.chatId) like lower(concat('%', :query, '%'))
            order by c.lastMessageAt desc
            """)
    Page<Conversation> search(@Param("query") String query, Pageable pageable);
}
