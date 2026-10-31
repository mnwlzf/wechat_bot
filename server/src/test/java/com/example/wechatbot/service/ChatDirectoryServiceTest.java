package com.example.wechatbot.service;

import com.example.wechatbot.config.WeChatHookProperties;
import com.example.wechatbot.domain.ChatType;
import com.example.wechatbot.domain.Conversation;
import com.example.wechatbot.repository.ConversationRepository;
import com.example.wechatbot.wechat.WeChatHookClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatDirectoryServiceTest {

    private ChatDirectoryService service;

    @BeforeEach
    void setUp() {
        WeChatHookProperties properties = new WeChatHookProperties();
        properties.setSelfWxid("wxid_me");
        // 测试环境不连微信：联系人 / 会话表都拿不到，只剩本地会话库
        properties.setEnabled(false);

        ConversationRepository repository = mock(ConversationRepository.class);
        when(repository.findAllByOrderByLastMessageAtDesc(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(
                        conversation("wxid_friend", "张三", ChatType.PRIVATE),
                        conversation("123@chatroom", "项目群", ChatType.GROUP),
                        conversation("gh_62c4ce184e73", "某某服务号", ChatType.PRIVATE),
                        conversation("wxid_me", "我自己", ChatType.PRIVATE))));

        service = new ChatDirectoryService(properties, mock(WeChatHookClient.class), repository);
    }

    @Test
    void searchesPrivateChatsByNicknameAndHidesGroups() {
        List<ChatDirectoryService.Candidate> result = service.search("private", "张", 50);

        assertThat(result).extracting(ChatDirectoryService.Candidate::chatId).containsExactly("wxid_friend");
        assertThat(result.getFirst().name()).isEqualTo("张三");
        assertThat(result.getFirst().group()).isFalse();
        assertThat(result.getFirst().blocked()).isFalse();
    }

    @Test
    void searchesGroupsByGroupName() {
        List<ChatDirectoryService.Candidate> result = service.search("groups", "项目", 50);

        assertThat(result).extracting(ChatDirectoryService.Candidate::chatId).containsExactly("123@chatroom");
        assertThat(result.getFirst().group()).isTrue();
        assertThat(result.getFirst().kind()).isEqualTo("GROUP");
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

    private static Conversation conversation(String chatId, String name, ChatType type) {
        Conversation conversation = new Conversation();
        conversation.setChatId(chatId);
        conversation.setChatName(name);
        conversation.setChatType(type);
        conversation.setLastMessageAt(OffsetDateTime.now());
        return conversation;
    }
}
