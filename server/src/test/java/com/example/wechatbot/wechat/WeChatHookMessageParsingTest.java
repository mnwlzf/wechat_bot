package com.example.wechatbot.wechat;

import com.example.wechatbot.config.WeChatHookProperties;
import com.example.wechatbot.repository.WeChatHookCursorRepository;
import com.example.wechatbot.service.InboxService;
import com.example.wechatbot.web.dto.AgentDtos;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class WeChatHookMessageParsingTest {
    private final WeChatHookInboxPoller poller = new WeChatHookInboxPoller(
            new WeChatHookProperties(),
            mock(WeChatHookClient.class),
            mock(WeChatHookCursorRepository.class),
            mock(InboxService.class),
            mock(com.example.wechatbot.service.RuntimeSettingsService.class));

    @Test
    void recoversAndStripsGroupSenderPrefixWithoutName2IdMapping() {
        AgentDtos.AgentEventRequest event = poller.convertRow(
                "123@chatroom",
                "message_1.db",
                Map.of(
                        "local_id", "13",
                        "local_type", "1",
                        "real_sender_id", "0",
                        "create_time", "1760000000",
                        "message_content", "wxid_sender: 群消息",
                        "content_type", "text"),
                Map.of(),
                "wxid_me");

        assertThat(event).isNotNull();
        assertThat(event.chatType().name()).isEqualTo("GROUP");
        assertThat(event.senderId()).isEqualTo("wxid_sender");
        assertThat(event.content()).isEqualTo("群消息");
    }

    @Test
    void ignoresSelfMessage() {
        AgentDtos.AgentEventRequest event = poller.convertRow(
                "wxid_me",
                "message_0.db",
                Map.of(
                        "local_id", "14",
                        "local_type", "1",
                        "real_sender_id", "3",
                        "create_time", "1760000000",
                        "message_content", "自己发的",
                        "content_type", "text"),
                Map.of("3", "wxid_me"),
                "wxid_me");

        assertThat(event).isNull();
    }
}
