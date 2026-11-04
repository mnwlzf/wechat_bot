package com.example.wechatbot.service;

import com.example.wechatbot.domain.ChatType;
import com.example.wechatbot.domain.Conversation;
import com.example.wechatbot.repository.ConversationRepository;
import com.example.wechatbot.repository.MessageLogRepository;
import com.example.wechatbot.repository.ModelProviderRepository;
import com.example.wechatbot.repository.OutboxMessageRepository;
import com.example.wechatbot.repository.PromptTemplateRepository;
import com.example.wechatbot.web.dto.AdminDtos;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

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
 * 这里锁定：无关键词走独立查询，有关键词走检索并去掉首尾空白；
 * 会话表里只有 ID 时，再用会话目录按名字补搜一次。
 */
class AdminQueryServiceTest {

    private final ConversationRepository conversationRepository = mock(ConversationRepository.class);
    private final ChatDirectoryService directoryService = mock(ChatDirectoryService.class);
    private final AdminQueryService service = new AdminQueryService(
            conversationRepository,
            mock(MessageLogRepository.class),
            mock(OutboxMessageRepository.class),
            mock(ModelProviderRepository.class),
            mock(PromptTemplateRepository.class),
            directoryService);

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

    @Test
    void fallsBackToDirectoryWhenStoredNameIsJustAnId() {
        // 会话表里 chat_name 就是那串 @chatroom，直接 LIKE 中文群名搜不到
        when(conversationRepository.search(anyString(), any(Pageable.class))).thenReturn(Page.empty());
        Conversation group = new Conversation();
        group.setId(UUID.randomUUID());
        group.setChatId("123@chatroom");
        group.setChatName("123@chatroom");
        group.setChatType(ChatType.GROUP);
        group.setLastMessageAt(OffsetDateTime.now());
        when(directoryService.search("all", "客户群", 200)).thenReturn(List.of(
                new ChatDirectoryService.Candidate("123@chatroom", "城南客户群", "GROUP", true,
                        false, "", "alias", "", OffsetDateTime.now())));
        when(conversationRepository.findByChatIdIn(any())).thenReturn(List.of(group));

        Page<AdminDtos.ConversationView> result = service.conversations("客户群", 0, 20);

        assertEquals(1, result.getTotalElements());
        // 显示名换成目录里的备注名，而不是那串 ID
        assertEquals("城南客户群", result.getContent().getFirst().chatName());
    }

    @Test
    void storedNameIsKeptWhenItIsAlreadyMeaningful() {
        when(conversationRepository.search(anyString(), any(Pageable.class))).thenReturn(Page.empty());
        Conversation group = new Conversation();
        group.setId(UUID.randomUUID());
        group.setChatId("456@chatroom");
        group.setChatName("老群名");
        group.setChatType(ChatType.GROUP);
        group.setLastMessageAt(OffsetDateTime.now());
        when(directoryService.search("all", "新备注", 200)).thenReturn(List.of(
                new ChatDirectoryService.Candidate("456@chatroom", "新备注", "GROUP", true,
                        false, "", "alias", "", OffsetDateTime.now())));
        when(conversationRepository.findByChatIdIn(any())).thenReturn(List.of(group));

        Page<AdminDtos.ConversationView> result = service.conversations("新备注", 0, 20);

        assertEquals(1, result.getTotalElements());
        assertEquals("老群名", result.getContent().getFirst().chatName());
    }
}
