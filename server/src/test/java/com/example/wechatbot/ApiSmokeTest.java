package com.example.wechatbot;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApiSmokeTest {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void rejectsAdminRequestWithoutToken() throws Exception {
        mockMvc.perform(get("/api/admin/ping"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void acceptsAdminRequestWithBearerToken() throws Exception {
        mockMvc.perform(get("/api/admin/ping").header("Authorization", "Bearer test-admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    void acceptsAgentRequestWithAgentToken() throws Exception {
        mockMvc.perform(get("/api/agent/outbox").header("X-Agent-Token", "test-agent"))
                .andExpect(status().isOk());
    }

    @Test
    void rejectsAgentRequestWithAdminToken() throws Exception {
        mockMvc.perform(get("/api/agent/outbox").header("X-Agent-Token", "test-admin"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listsConversationsWithAndWithoutKeyword() throws Exception {
        mockMvc.perform(get("/api/admin/conversations").header("Authorization", "Bearer test-admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.totalElements").exists());

        mockMvc.perform(get("/api/admin/conversations").param("query", "wxid")
                        .header("Authorization", "Bearer test-admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    void returnsNotFoundForUnknownConversationMessages() throws Exception {
        mockMvc.perform(get("/api/admin/conversations/{id}/messages", UUID.randomUUID())
                        .header("Authorization", "Bearer test-admin"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    void exposesProviderBootstrapStatus() throws Exception {
        mockMvc.perform(get("/api/admin/providers/bootstrap")
                        .header("Authorization", "Bearer test-admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.providers").isArray())
                .andExpect(jsonPath("$.warnings").isArray());
    }
}
