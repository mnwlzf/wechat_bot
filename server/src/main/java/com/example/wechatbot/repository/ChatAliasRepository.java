package com.example.wechatbot.repository;

import com.example.wechatbot.domain.ChatAlias;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatAliasRepository extends JpaRepository<ChatAlias, String> {
}
