package com.example.wechatbot.service;

import com.example.wechatbot.repository.ConversationRepository;
import com.example.wechatbot.repository.MessageLogRepository;
import com.example.wechatbot.repository.ModelProviderRepository;
import com.example.wechatbot.repository.OutboxMessageRepository;
import com.example.wechatbot.repository.PromptTemplateRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会话列表在 PostgreSQL 上曾因 "where :query is null" 报
 * {@code function lower(bytea) does not exist}（NULL 参数无法推断类型）。
 * 这里锁定：无关键词走独立查询，有关键词走检索并去掉首尾空白。
 */
class AdminQueryServiceTest {

    private final ConversationRepository conversationRepository = mock(ConversationRepository.class);
    private final AdminQueryService service = new AdminQueryService(
            conversationRepository,
            mock(MessageLogRepository.class),
            mock(OutboxMessageRepository.class),
            mock(ModelProviderRepository.class),
            mock(PromptTemplateRepository.class));

    @Test
    void blankQueryUsesPlainListingInsteadOfNullableSearch() {
        when(conversationRepository.findAllByOrderByLastMessageAtDesc(any(Pageable.class)))
                .thenReturn(Page.empty());

        assertTrue(service.conversations(null, 0, 20).isEmpty());
        assertTrue(service.conversations("   ", 0, 20).isEmpty());

        verify(conversationRepository, never()).search(anyString(), any(Pageable.class));
    }

    @Test
    void keywordQueryUsesSearchWithTrimmedValue() {
        when(conversationRepository.search(anyString(), any(Pageable.class))).thenReturn(Page.empty());

        service.conversations("  wxid_alice  ", 0, 20);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(conversationRepository).search(captor.capture(), any(Pageable.class));
        assertEquals("wxid_alice", captor.getValue());
        verify(conversationRepository, never()).findAllByOrderByLastMessageAtDesc(any(Pageable.class));
    }
}
