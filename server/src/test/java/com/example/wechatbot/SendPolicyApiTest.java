package com.example.wechatbot;

import com.example.wechatbot.repository.SendPolicyRepository;
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
 * 发信策略接口冒烟：默认策略能改、按群能覆盖、删掉能回退、越界参数会被拦。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SendPolicyApiTest {
    private static final String AUTH = "Bearer test-admin";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SendPolicyRepository repository;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    void defaultPolicyIsEditableAndChatOverrideWins() throws Exception {
        // 初始：全部继承环境变量
        mockMvc.perform(get("/api/admin/send-policies").header("Authorization", AUTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defaultPolicy.inherited").value(true))
                .andExpect(jsonPath("$.defaultPolicy.perMinute").value(3))
                .andExpect(jsonPath("$.overrides").isArray())
                .andExpect(jsonPath("$.overrides.length()").value(0));

        // 改默认策略：每分钟 1 条、最多 2 个气泡
        mockMvc.perform(put("/api/admin/send-policies/default")
                        .header("Authorization", AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"perMinute":1,"maxParts":2}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.perMinute").value(1))
                .andExpect(jsonPath("$.maxParts").value(2))
                // 没写的字段继续沿用环境变量
                .andExpect(jsonPath("$.splitEnabled").value(true))
                .andExpect(jsonPath("$.readDelayMinMs").value(1200));

        // 给某个群单独放宽频率
        mockMvc.perform(put("/api/admin/send-policies/{chatId}", "123@chatroom").param("chatName", "测试群")
                        .header("Authorization", AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"readDelayMinMs":5000,"readDelayMaxMs":6000,"perMinute":20}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chatId").value("123@chatroom"))
                .andExpect(jsonPath("$.chatName").value("测试群"))
                .andExpect(jsonPath("$.isDefault").value(false))
                .andExpect(jsonPath("$.perMinute").value(20));

        // 该群：自己的频率 + 默认策略的分条数
        mockMvc.perform(get("/api/admin/send-policies/{chatId}", "123@chatroom").header("Authorization", AUTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effective.perMinute").value(20))
                .andExpect(jsonPath("$.effective.maxParts").value(2))
                .andExpect(jsonPath("$.effective.readDelayMinMs").value(5000))
                .andExpect(jsonPath("$.effective.inherited").value(false));

        // 别的群：完整继承默认策略
        mockMvc.perform(get("/api/admin/send-policies/{chatId}", "456@chatroom").header("Authorization", AUTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effective.perMinute").value(1))
                .andExpect(jsonPath("$.effective.inherited").value(true))
                .andExpect(jsonPath("$.override").doesNotExist());

        // 删掉按群策略 → 立即回退默认
        mockMvc.perform(delete("/api/admin/send-policies/{chatId}", "123@chatroom").header("Authorization", AUTH))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/admin/send-policies/{chatId}", "123@chatroom").header("Authorization", AUTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effective.perMinute").value(1))
                .andExpect(jsonPath("$.effective.readDelayMinMs").value(1200))
                .andExpect(jsonPath("$.effective.inherited").value(true));
    }

    @Test
    void rejectsOutOfRangeValues() throws Exception {
        mockMvc.perform(put("/api/admin/send-policies/{chatId}", "123@chatroom")
                        .header("Authorization", AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"readDelayMinMs":9000,"readDelayMaxMs":1000}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        mockMvc.perform(put("/api/admin/send-policies/{chatId}", "123@chatroom")
                        .header("Authorization", AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"maxParts":50}
                                """))
                .andExpect(status().isBadRequest());
    }
}
