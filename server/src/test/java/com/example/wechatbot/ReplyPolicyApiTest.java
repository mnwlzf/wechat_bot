package com.example.wechatbot;

import com.example.wechatbot.domain.BotConfig;
import com.example.wechatbot.repository.BotConfigRepository;
import com.example.wechatbot.repository.ReplyPolicyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 回复策略接口冒烟：默认策略能改、按群能覆盖、删掉能回退、非法模式会被拦。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReplyPolicyApiTest {
    private static final String AUTH = "Bearer test-admin";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ReplyPolicyRepository repository;

    @Autowired
    private BotConfigRepository botRepository;

    @BeforeEach
    void seed() {
        repository.deleteAll();
        botRepository.deleteAll();
        BotConfig bot = new BotConfig();
        bot.setName("小助手");
        bot.setEnabled(true);
        bot.setSystemPrompt("你是小助手");
        bot.setGroupWakeWords("@机器人");
        bot.setReplyGroup(true);
        botRepository.save(bot);
    }

    @Test
    void defaultPolicyIsEditableAndChatOverrideWins() throws Exception {
        // 初始：没有任何策略行，基线来自机器人配置 → 关键词唤醒
        mockMvc.perform(get("/api/admin/reply-policies").header("Authorization", AUTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defaultPolicy.mode").value("WAKE"))
                .andExpect(jsonPath("$.defaultPolicy.source").value("bot"))
                .andExpect(jsonPath("$.defaultPolicy.inherited").value(true))
                .andExpect(jsonPath("$.botWakeWords").value("@机器人"))
                .andExpect(jsonPath("$.overrides.length()").value(0));

        // 改默认策略：按 35% 概率随机回复
        mockMvc.perform(put("/api/admin/reply-policies/default")
                        .header("Authorization", AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mode":"RANDOM","randomRate":0.35}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("RANDOM"))
                .andExpect(jsonPath("$.randomRate").value(0.35))
                // 没写的字段继续沿用机器人配置
                .andExpect(jsonPath("$.wakeWords").value("@机器人"));

        // 某个群改成主动回复
        mockMvc.perform(put("/api/admin/reply-policies/{chatId}", "123@chatroom").param("chatName", "测试群")
                        .header("Authorization", AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mode":"ALWAYS"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chatId").value("123@chatroom"))
                .andExpect(jsonPath("$.mode").value("ALWAYS"))
                .andExpect(jsonPath("$.isDefault").value(false));

        // 该群：自己的模式 + 默认策略的概率
        mockMvc.perform(get("/api/admin/reply-policies/{chatId}", "123@chatroom").header("Authorization", AUTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effective.mode").value("ALWAYS"))
                .andExpect(jsonPath("$.effective.randomRate").value(0.35))
                .andExpect(jsonPath("$.effective.source").value("chat"))
                .andExpect(jsonPath("$.effective.inherited").value(false));

        // 别的群：完整继承默认策略
        mockMvc.perform(get("/api/admin/reply-policies/{chatId}", "456@chatroom").header("Authorization", AUTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effective.mode").value("RANDOM"))
                .andExpect(jsonPath("$.effective.inherited").value(true))
                .andExpect(jsonPath("$.override").doesNotExist());

        // 删掉按群策略 → 立即回退默认
        mockMvc.perform(delete("/api/admin/reply-policies/{chatId}", "123@chatroom").header("Authorization", AUTH))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/admin/reply-policies/{chatId}", "123@chatroom").header("Authorization", AUTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effective.mode").value("RANDOM"))
                .andExpect(jsonPath("$.effective.inherited").value(true));
    }

    @Test
    void rejectsInvalidModeAndWakeWatchers() throws Exception {
        mockMvc.perform(put("/api/admin/reply-policies/{chatId}", "123@chatroom")
                        .header("Authorization", AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mode":"SOMETIMES"}
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put("/api/admin/reply-policies/{chatId}", "123@chatroom")
                        .header("Authorization", AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mode":"WAKE","wakeWords":"  "}
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put("/api/admin/reply-policies/default")
                        .header("Authorization", AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mode":"RANDOM","randomRate":1.8}
                                """))
                .andExpect(status().isBadRequest());
    }
}
