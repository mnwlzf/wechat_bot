package com.example.wechatbot;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"app.disguise.per10-minutes=5"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DisguiseEndpointTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void exposesScheduleAndQuota() throws Exception {
        mockMvc.perform(get("/api/admin/disguise").header("Authorization", "Bearer test-admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.state").exists())
                .andExpect(jsonPath("$.nextActiveAt").exists())
                .andExpect(jsonPath("$.perMinute").value(3))
                .andExpect(jsonPath("$.per10Minutes").value(5))
                .andExpect(jsonPath("$.perDay").value(120));
    }

    @Test
    void previewCleansStyleWithoutConsumingQuota() throws Exception {
        String body = """
                {"chatId":"wxid_demo","chatType":"PRIVATE","text":"**好的**！首先，我为您整理如下：`code`"}
                """;

        mockMvc.perform(post("/api/admin/disguise/preview")
                        .header("Authorization", "Bearer test-admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("SEND"))
                .andExpect(jsonPath("$.dropped").value(false))
                .andExpect(jsonPath("$.aiScore").isNumber())
                .andExpect(jsonPath("$.parts[0].text", containsString("好的")))
                .andExpect(jsonPath("$.parts[0].text", containsString("code")));
    }

    @Test
    void updatesManualAwayBlocks() throws Exception {
        String body = """
                {"enabled":true,"blocks":["15:00-16:00","09:00-09:30"]}
                """;

        mockMvc.perform(put("/api/admin/disguise/away-blocks")
                        .header("Authorization", "Bearer test-admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.awayBlocksEnabled").value(true))
                .andExpect(jsonPath("$.awayBlocks[0]").value("09:00-09:30"))
                .andExpect(jsonPath("$.awayBlocks[1]").value("15:00-16:00"));
    }

    @Test
    void rejectsInvalidAwayBlocks() throws Exception {
        String body = """
                {"enabled":true,"blocks":["10:00-10:00"]}
                """;

        mockMvc.perform(put("/api/admin/disguise/away-blocks")
                        .header("Authorization", "Bearer test-admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void requiresAdminToken() throws Exception {
        mockMvc.perform(get("/api/admin/disguise"))
                .andExpect(status().isUnauthorized());
    }
}
