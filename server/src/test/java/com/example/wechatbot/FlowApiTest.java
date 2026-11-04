package com.example.wechatbot;

import com.example.wechatbot.flow.FlowTraceService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 实时流水接口：登录才能看，返回结构对得上前端，增量游标能只取新事件。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FlowApiTest {
    private static final String AUTH = "Bearer test-admin";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private FlowTraceService flowTrace;

    @Test
    void requiresAdminToken() throws Exception {
        mockMvc.perform(get("/api/admin/flow")).andExpect(status().isUnauthorized());
    }

    @Test
    void returnsCountersWaitingAndEvents() throws Exception {
        flowTrace.setWaiting(FlowTraceService.Stage.QUOTA, "wxid_flow", "流水测试群",
                "每分钟上限 3 条", Instant.now().plusSeconds(8));
        flowTrace.record(FlowTraceService.Stage.QUOTA, "wxid_flow", "流水测试群",
                "配额排队：每分钟上限 3 条", null, 8000L, null);

        MvcResult result = mockMvc.perform(get("/api/admin/flow").header("Authorization", AUTH))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsByteArray());

        assertThat(body.path("cursor").asLong()).isPositive();
        assertThat(body.path("counters").path("QUOTA").asLong()).isPositive();
        JsonNode waiting = body.path("waiting");
        assertThat(waiting.isArray()).isTrue();
        assertThat(waiting.toString()).contains("流水测试群").contains("每分钟上限");
        JsonNode events = body.path("events");
        assertThat(events.isArray()).isTrue();
        JsonNode last = events.get(events.size() - 1);
        assertThat(last.path("stage").asText()).isEqualTo("QUOTA");
        assertThat(last.path("stageLabel").asText()).isEqualTo("配额排队");
        assertThat(last.path("waitMs").asLong()).isEqualTo(8000L);
    }

    @Test
    void sinceCursorSkipsAlreadySeenEvents() throws Exception {
        flowTrace.record(FlowTraceService.Stage.RECEIVED, "wxid_flow", null, "旧事件", null, null, null);
        long cursor = flowTrace.snapshot(0, 10).cursor();

        flowTrace.record(FlowTraceService.Stage.SENT, "wxid_flow", null, "新事件", null, null, null);

        MvcResult result = mockMvc.perform(get("/api/admin/flow")
                        .param("since", String.valueOf(cursor))
                        .header("Authorization", AUTH))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode events = objectMapper.readTree(result.getResponse().getContentAsByteArray())
                .path("events");

        assertThat(events).hasSize(1);
        assertThat(events.get(0).path("detail").asText()).isEqualTo("新事件");
    }
}
