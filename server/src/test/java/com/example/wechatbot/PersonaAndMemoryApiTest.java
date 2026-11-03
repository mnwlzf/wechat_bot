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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 人设 / 共享记忆 / 定时消息三个新接口的端到端冒烟：真实走 Spring MVC + JPA(H2)，
 * 覆盖「字段名对不对」「默认人设唯一」「编辑与重复规则存得下」这些光看代码容易漏的点。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PersonaAndMemoryApiTest {
    private static final String AUTH = "Bearer test-admin";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private BotConfigRepository botRepository;

    @Autowired
    private ConversationRepository conversationRepository;

    /** 测试库是 create-drop，没有 Flyway 的种子数据；定时消息需要至少一个启用的机器人。 */
    @BeforeEach
    void seedBot() {
        if (botRepository.count() == 0) {
            BotConfig bot = new BotConfig();
            bot.setName("冒烟机器人");
            bot.setEnabled(true);
            bot.setSystemPrompt("你是冒烟测试机器人");
            botRepository.save(bot);
        }
    }

    @Test
    void personaCanBeCreatedEditedAndSwitchedAsDefault() throws Exception {
        String first = createPersona("测试人设一", "你是测试一");
        String second = createPersona("测试人设二", "你是测试二");

        mockMvc.perform(post("/api/admin/prompt-templates/{id}/default", second).header("Authorization", AUTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(second))
                .andExpect(jsonPath("$.defaultPersona").value(true));

        // 默认人设全局唯一
        List<JsonNode> defaults = new ArrayList<>();
        for (JsonNode node : listJson("/api/admin/prompt-templates")) {
            if (node.path("defaultPersona").asBoolean()) {
                defaults.add(node);
            }
        }
        assertThat(defaults).hasSize(1);
        assertThat(defaults.get(0).path("id").asText()).isEqualTo(second);

        mockMvc.perform(put("/api/admin/prompt-templates/{id}", first)
                        .header("Authorization", AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"测试人设一改","description":"改过","content":"你是改过的测试一"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("测试人设一改"))
                .andExpect(jsonPath("$.content").value("你是改过的测试一"));

        mockMvc.perform(delete("/api/admin/prompt-templates/default").header("Authorization", AUTH))
                .andExpect(status().isOk());
        for (JsonNode node : listJson("/api/admin/prompt-templates")) {
            assertThat(node.path("defaultPersona").asBoolean()).isFalse();
        }

        mockMvc.perform(delete("/api/admin/prompt-templates/{id}", first).header("Authorization", AUTH))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/admin/prompt-templates/{id}", second).header("Authorization", AUTH))
                .andExpect(status().isOk());
    }

    @Test
    void memoryNoteIsScopedToItsChatAndCanBeDisabled() throws Exception {
        String chatId = "smoke-" + System.nanoTime() + "@chatroom";
        String created = mockMvc.perform(post("/api/admin/memories")
                        .header("Authorization", AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"chatId":"%s","chatName":"冒烟群","content":"周五下午三点开例会"}
                                """.formatted(chatId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("NOTE"))
                .andExpect(jsonPath("$.source").value("MANUAL"))
                .andExpect(jsonPath("$.enabled").value(true))
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(created).path("id").asText();

        mockMvc.perform(get("/api/admin/memories").param("chatId", chatId).header("Authorization", AUTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].content").value("周五下午三点开例会"));

        // 别的会话不应该看到这条记忆
        mockMvc.perform(get("/api/admin/memories").param("chatId", chatId + "-other").header("Authorization", AUTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        mockMvc.perform(put("/api/admin/memories/{id}", id)
                        .header("Authorization", AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"改成周五下午四点","enabled":false}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("改成周五下午四点"))
                .andExpect(jsonPath("$.enabled").value(false));

        mockMvc.perform(delete("/api/admin/memories/{id}", id).header("Authorization", AUTH))
                .andExpect(status().isOk());
    }

    @Test
    void scheduledMessageKeepsRepeatRuleAndCanBeEdited() throws Exception {
        String chatId = "smoke-sched-" + System.nanoTime() + "@chatroom";
        String scheduledAt = OffsetDateTime.now().plusHours(3).toString();
        String created = mockMvc.perform(post("/api/admin/scheduled-messages")
                        .header("Authorization", AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"chatId":"%s","chatName":"冒烟群","content":"第一行\\n第二行",
                                 "scheduledAt":"%s","repeatRule":"DAILY","splitLines":true}
                                """.formatted(chatId, scheduledAt)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repeatRule").value("DAILY"))
                .andExpect(jsonPath("$.splitLines").value(true))
                .andExpect(jsonPath("$.sentCount").value(0))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(created).path("id").asText();

        mockMvc.perform(put("/api/admin/scheduled-messages/{id}", id)
                        .header("Authorization", AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"chatId":"%s","content":"改过的内容","scheduledAt":"%s","repeatRule":"WEEKLY"}
                                """.formatted(chatId, OffsetDateTime.now().plusDays(2).toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("改过的内容"))
                .andExpect(jsonPath("$.repeatRule").value("WEEKLY"))
                .andExpect(jsonPath("$.splitLines").value(false));

        mockMvc.perform(post("/api/admin/scheduled-messages/{id}/cancel", id).header("Authorization", AUTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        mockMvc.perform(delete("/api/admin/scheduled-messages/{id}", id).header("Authorization", AUTH))
                .andExpect(status().isOk());
    }

    @Test
    void personaCanBeBoundToASingleConversation() throws Exception {
        // 先造一个默认人设：解绑后页面要能看出回退到了谁
        String defaultPersona = createPersona("默认人设", "你是默认人设");
        mockMvc.perform(post("/api/admin/prompt-templates/{id}/default", defaultPersona)
                        .header("Authorization", AUTH))
                .andExpect(status().isOk());

        String persona = createPersona("群专用人设", "你是这个群专属的人设");
        UUID conversationId = seedConversation("binding@chatroom", "绑定测试群");

        // 绑定：会话列表里应立刻显示这条人设
        mockMvc.perform(post("/api/admin/conversations/{id}/persona", conversationId)
                        .header("Authorization", AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"promptTemplateId\":\"" + persona + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.promptTemplateId").value(persona))
                .andExpect(jsonPath("$.promptTemplateName").value("群专用人设"));

        MvcResult listResult = mockMvc.perform(get("/api/admin/conversations").header("Authorization", AUTH))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode bound = objectMapper.readTree(listResult.getResponse().getContentAsString())
                .path("content").get(0);
        assertThat(bound.path("promptTemplateId").asText()).isEqualTo(persona);

        // 解绑：回退到默认人设，页面要能看出走的是哪个默认
        MvcResult cleared = mockMvc.perform(post("/api/admin/conversations/{id}/persona", conversationId)
                        .header("Authorization", AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"promptTemplateId\":null}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode clearedNode = objectMapper.readTree(cleared.getResponse().getContentAsString());
        // 解绑后回落到默认人设：promptTemplateId 被清空，生效人设显示默认名字
        assertThat(clearedNode.path("promptTemplateId").isMissingNode()
                || clearedNode.path("promptTemplateId").isNull()).isTrue();
        assertThat(clearedNode.path("effectivePersonaName").asText())
                .as("解绑后应显示默认人设名，实际响应：%s", cleared.getResponse().getContentAsString())
                .contains("默认");
    }

    /** 绑定人设需要一个真实会话；测试库是 create-drop，这里自己造一条。 */
    private UUID seedConversation(String chatId, String chatName) {
        BotConfig bot = botRepository.findAll().getFirst();
        Conversation conversation = new Conversation();
        conversation.setBotId(bot.getId());
        conversation.setChatId(chatId);
        conversation.setChatName(chatName);
        conversation.setChatType(ChatType.GROUP);
        conversation.setLastMessage("");
        conversation.setLastMessageAt(OffsetDateTime.now());
        return conversationRepository.save(conversation).getId();
    }

    private String createPersona(String name, String content) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/admin/prompt-templates")
                        .header("Authorization", AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s","description":"冒烟测试","content":"%s"}
                                """.formatted(name, content)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value(name))
                .andExpect(jsonPath("$.builtin").value(false))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("id").asText();
    }

    private List<JsonNode> listJson(String path) throws Exception {
        MvcResult result = mockMvc.perform(get(path).header("Authorization", AUTH))
                .andExpect(status().isOk())
                .andReturn();
        List<JsonNode> nodes = new ArrayList<>();
        objectMapper.readTree(result.getResponse().getContentAsString()).forEach(nodes::add);
        return nodes;
    }
}
