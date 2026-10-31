package com.example.wechatbot.service;

import com.example.wechatbot.config.WeChatHookProperties;
import com.example.wechatbot.domain.ChatAlias;
import com.example.wechatbot.domain.ChatType;
import com.example.wechatbot.domain.Conversation;
import com.example.wechatbot.repository.ChatAliasRepository;
import com.example.wechatbot.repository.ConversationRepository;
import com.example.wechatbot.wechat.HookCallbackBuffer;
import com.example.wechatbot.wechat.WeChatHookClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatDirectoryServiceTest {

    private ChatAliasRepository aliasRepository;
    private HookCallbackBuffer callbackBuffer;
    private ChatDirectoryService service;

    @BeforeEach
    void setUp() {
        WeChatHookProperties properties = new WeChatHookProperties();
        properties.setSelfWxid("wxid_me");
        // 测试环境不连微信：联系人 / 会话表都拿不到，只剩本地会话库、备注与最近回调
        properties.setEnabled(false);

        ConversationRepository repository = mock(ConversationRepository.class);
        when(repository.findAllByOrderByLastMessageAtDesc(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(
                        conversation("wxid_friend", "张三", ChatType.PRIVATE, "晚上一起吃饭？"),
                        conversation("123@chatroom", "项目群", ChatType.GROUP, "明天十点开会"),
                        conversation("gh_62c4ce184e73", "某某服务号", ChatType.PRIVATE, "活动通知"),
                        conversation("wxid_me", "我自己", ChatType.PRIVATE, ""))));

        aliasRepository = mock(ChatAliasRepository.class);
        when(aliasRepository.findAll()).thenReturn(List.of(
                new ChatAlias("456@chatroom", "家人群", OffsetDateTime.now())));

        callbackBuffer = mock(HookCallbackBuffer.class);
        when(callbackBuffer.recent()).thenReturn(List.of(recent("789@chatroom", "周末聚餐")));

        service = new ChatDirectoryService(properties, mock(WeChatHookClient.class), repository,
                aliasRepository, callbackBuffer, new ObjectMapper());
    }

    @Test
    void searchesPrivateChatsByNicknameAndHidesGroups() {
        List<ChatDirectoryService.Candidate> result = service.search("private", "张", 50);

        assertThat(result).extracting(ChatDirectoryService.Candidate::chatId).containsExactly("wxid_friend");
        assertThat(result.getFirst().name()).isEqualTo("张三");
        assertThat(result.getFirst().group()).isFalse();
        assertThat(result.getFirst().blocked()).isFalse();
        assertThat(result.getFirst().lastMessage()).isEqualTo("晚上一起吃饭？");
    }

    @Test
    void searchesGroupsByGroupName() {
        List<ChatDirectoryService.Candidate> result = service.search("groups", "项目", 50);

        assertThat(result).extracting(ChatDirectoryService.Candidate::chatId).containsExactly("123@chatroom");
        assertThat(result.getFirst().group()).isTrue();
        assertThat(result.getFirst().kind()).isEqualTo("GROUP");
    }

    @Test
    void aliasNamesWinAndAreSearchable() {
        List<ChatDirectoryService.Candidate> groups = service.search("groups", "家人", 50);

        assertThat(groups).extracting(ChatDirectoryService.Candidate::chatId).containsExactly("456@chatroom");
        assertThat(groups.getFirst().name()).isEqualTo("家人群");
        assertThat(groups.getFirst().source()).isEqualTo("alias");
    }

    @Test
    void chatsSeenOnlyByHookAreListedSoTheyCanBeWhitelisted() {
        List<ChatDirectoryService.Candidate> groups = service.search("groups", "聚餐", 50);

        assertThat(groups).extracting(ChatDirectoryService.Candidate::chatId).containsExactly("789@chatroom");
        assertThat(groups.getFirst().source()).isEqualTo("hook");
        assertThat(groups.getFirst().lastMessage()).isEqualTo("周末聚餐");
    }

    @Test
    void marksOfficialAccountsAsBlockedAndNeverListsSelf() {
        List<ChatDirectoryService.Candidate> result = service.search("", "", 50);

        assertThat(result).extracting(ChatDirectoryService.Candidate::chatId)
                .contains("gh_62c4ce184e73")
                .doesNotContain("wxid_me");
        ChatDirectoryService.Candidate official = result.stream()
                .filter(item -> item.chatId().equals("gh_62c4ce184e73"))
                .findFirst().orElseThrow();
        assertThat(official.kind()).isEqualTo("OFFICIAL");
        assertThat(official.blocked()).isTrue();
        assertThat(official.blockedReason()).contains("公众号");
    }

    @Test
    void keywordAlsoMatchesChatId() {
        assertThat(service.search("private", "wxid_friend", 50))
                .extracting(ChatDirectoryService.Candidate::chatId)
                .containsExactly("wxid_friend");
        assertThat(service.search("private", "不存在的人", 50)).isEmpty();
    }

    @Test
    void savingAliasStoresTrimmedName() {
        String saved = service.saveAlias("123@chatroom", "  项目群 A  ");

        assertThat(saved).isEqualTo("项目群 A");
        org.mockito.ArgumentCaptor<ChatAlias> captor = org.mockito.ArgumentCaptor.forClass(ChatAlias.class);
        verify(aliasRepository).save(captor.capture());
        assertThat(captor.getValue().getChatId()).isEqualTo("123@chatroom");
        assertThat(captor.getValue().getDisplayName()).isEqualTo("项目群 A");
    }

    @Test
    void clearingAliasDeletesTheRow() {
        service.saveAlias("123@chatroom", "  ");

        verify(aliasRepository).deleteById("123@chatroom");
        verify(aliasRepository, org.mockito.Mockito.never()).save(any());
    }

    private static Map<String, Object> recent(String chatId, String content) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("chatId", chatId);
        item.put("payload", Map.of("content", content));
        item.put("receivedAt", OffsetDateTime.now());
        return item;
    }

    private static Conversation conversation(String chatId, String name, ChatType type, String lastMessage) {
        Conversation conversation = new Conversation();
        conversation.setChatId(chatId);
        conversation.setChatName(name);
        conversation.setChatType(type);
        conversation.setLastMessage(lastMessage);
        conversation.setLastMessageAt(OffsetDateTime.now());
        return conversation;
    }
}
