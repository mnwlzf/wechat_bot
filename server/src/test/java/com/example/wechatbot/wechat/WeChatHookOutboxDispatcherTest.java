package com.example.wechatbot.wechat;

import com.example.wechatbot.config.WeChatHookProperties;
import com.example.wechatbot.service.OutboxService;
import com.example.wechatbot.web.dto.AgentDtos;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WeChatHookOutboxDispatcherTest {

    private final WeChatHookClient client = mock(WeChatHookClient.class);
    private final OutboxService outboxService = mock(OutboxService.class);
    private final WeChatHookProperties properties = new WeChatHookProperties();
    private final WeChatHookOutboxDispatcher dispatcher =
            new WeChatHookOutboxDispatcher(client, outboxService, properties);

    @Test
    void neverSendsToOfficialAccountsBotsOrSystemChats() {
        properties.setEnabled(true);
        UUID official = UUID.randomUUID();
        UUID bot = UUID.randomUUID();
        UUID friend = UUID.randomUUID();
        when(outboxService.lease(anyInt())).thenReturn(List.of(
                view(official, "gh_62c4ce184e73"),
                view(bot, "mmo9cq80-a3JRPTWAxH_8kqXy-llVU@weclaw"),
                view(friend, "wxid_rsje67b7hozm22")));

        dispatcher.dispatch();

        verify(outboxService).cancel(eq(official), contains("公众号"));
        verify(outboxService).cancel(eq(bot), anyString());
        verify(client, never()).sendText(eq("gh_62c4ce184e73"), anyString());
        verify(client, never()).sendText(eq("mmo9cq80-a3JRPTWAxH_8kqXy-llVU@weclaw"), anyString());
        verify(client).sendText("wxid_rsje67b7hozm22", "你好");
        verify(outboxService).ack(friend, true, null);
    }

    private static AgentDtos.OutboxView view(UUID id, String chatId) {
        return new AgentDtos.OutboxView(id, UUID.randomUUID(), chatId, "你好", 1, OffsetDateTime.now());
    }
}
