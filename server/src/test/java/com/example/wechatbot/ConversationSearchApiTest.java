package com.example.wechatbot;

import com.example.wechatbot.domain.BotConfig;
import com.example.wechatbot.domain.ChatType;
import com.example.wechatbot.domain.Conversation;
import com.example.wechatbot.repository.BotConfigRepository;
import com.example.wechatbot.repository.ConversationRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 会话检索：会话表里只存了 ID 的群 / 好友，也要能按群名称、好友昵称、本机备注搜到。
 *
 * <p>这是「管理端搜不到客户群」那条反馈的回归测试：微信回调没带名字时
 * {@code chat_name} 就是个 {@code xxx@chatroom}，直接 LIKE 会话表永远匹配不到中文群名，
 * 所以排序后还要到会话目录（备注 / 昵称 / 群名称）里再搜一遍。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ConversationSearchApiTest {
    private static final String AUTH = "Bearer test-admin";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private BotConfigRepository botRepository;

    @Autowired
    private ConversationRepository conversationRepository;

    private BotConfig bot;

    @BeforeEach
    void seed() {
        bot = botRepository.findFirstByEnabledTrueOrderByCreatedAtAsc().orElseGet(() -> {
            BotConfig created = new BotConfig();
            created.setName("检索机器人");
            created.setEnabled(true);
            created.setSystemPrompt("你是检索测试机器人");
            return botRepository.save(created);
        });
    }

    private Conversation conversation(String chatId, String chatName, ChatType type) {
        Conversation conversation = new Conversation();
        conversation.setBotId(bot.getId());
        conversation.setChatId(chatId);
        conversation.setChatName(chatName);
        conversation.setChatType(type);
        conversation.setLastMessage("最后一条");
        conversation.setLastMessageAt(OffsetDateTime.now());
        return conversationRepository.save(conversation);
    }

    private void alias(String chatId, String name) throws Exception {
        mockMvc.perform(put("/api/admin/wechat/chats/{chatId}/alias", chatId)
                        .header("Authorization", AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isOk());
    }

    private JsonNode search(String query) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/admin/conversations")
                        .param("query", query)
                        .param("page", "0")
                        .param("size", "20")
                        .header("Authorization", AUTH))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    @Test
    void groupWithOnlyAnIdIsFoundByItsRealName() throws Exception {
        Conversation group = conversation("49878296302@chatroom", "49878296302@chatroom", ChatType.GROUP);
        alias(group.getChatId(), "城南客户群");

        JsonNode page = search("城南客户群");

        assertThat(page.path("totalElements").asInt()).isEqualTo(1);
        JsonNode hit = page.path("content").get(0);
        assertThat(hit.path("chatId").asText()).isEqualTo(group.getChatId());
        // 显示名用目录里的名字，不再是那串 @chatroom
        assertThat(hit.path("chatName").asText()).isEqualTo("城南客户群");
    }

    @Test
    void privateChatIsFoundByNickname() throws Exception {
        Conversation friend = conversation("wxid_abc123", "wxid_abc123", ChatType.PRIVATE);
        alias(friend.getChatId(), "张三");

        JsonNode page = search("张三");

        assertThat(page.path("totalElements").asInt()).isEqualTo(1);
        assertThat(page.path("content").get(0).path("chatName").asText()).isEqualTo("张三");
    }

    @Test
    void storedNameStillWinsWhenItIsNotJustTheId() throws Exception {
        Conversation group = conversation("777@chatroom", "老群名", ChatType.GROUP);

        JsonNode page = search("老群名");

        assertThat(page.path("totalElements").asInt()).isEqualTo(1);
        assertThat(page.path("content").get(0).path("chatId").asText()).isEqualTo(group.getChatId());
        assertThat(page.path("content").get(0).path("chatName").asText()).isEqualTo("老群名");
    }

    @Test
    void unrelatedKeywordReturnsNothing() throws Exception {
        conversation("888@chatroom", "888@chatroom", ChatType.GROUP);

        JsonNode page = search("完全不存在的群名");

        assertThat(page.path("totalElements").asInt()).isZero();
    }
}
