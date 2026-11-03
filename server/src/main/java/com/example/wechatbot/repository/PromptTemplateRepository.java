package com.example.wechatbot.repository;

import com.example.wechatbot.domain.PromptTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PromptTemplateRepository extends JpaRepository<PromptTemplate, UUID> {
    List<PromptTemplate> findAllByOrderByIsDefaultDescUpdatedAtDesc();

    List<PromptTemplate> findAllByOrderByUpdatedAtDesc();

    Optional<PromptTemplate> findFirstByIsDefaultTrue();

    Optional<PromptTemplate> findByBuiltinKey(String builtinKey);

    /**
     * 清掉默认标记。用批量更新而不是逐条 save：{@code is_default} 上有唯一索引，
     * 逐条改会在 flush 时撞上「同时存在两条默认」的中间状态。
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update PromptTemplate t set t.isDefault = false where t.isDefault = true")
    int clearDefaultFlags();
}
