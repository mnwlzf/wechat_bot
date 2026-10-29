package com.example.wechatbot.wechat;

import com.example.wechatbot.config.AppProperties;
import com.example.wechatbot.config.WeChatHookProperties;
import com.example.wechatbot.domain.ChatType;
import com.example.wechatbot.repository.AppSettingRepository;
import com.example.wechatbot.service.InboxService;
import com.example.wechatbot.service.RuntimeSettingsService;
import com.example.wechatbot.web.dto.AgentDtos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class HookCallbackServiceTest {

    private WeChatHookProperties properties;
    private InboxService inboxService;
    private RuntimeSettingsService settings;
    private HookCallbackService service;

    @BeforeEach
    void setUp() {
        properties = new WeChatHookProperties();
        properties.setCallbackEnabled(true);
        inboxService = mock(InboxService.class);
        when(inboxService.accept(any()))
                .thenReturn(new AgentDtos.EventAccepted("ACCEPTED", "ok", null));
        AppSettingRepository settingsRepository = mock(AppSettingRepository.class);
        when(settingsRepository.findAll()).thenReturn(List.of());
        settings = new RuntimeSettingsService(settingsRepository, properties, new AppProperties());
        settings.reload();
        service = new HookCallbackService(properties, settings, inboxService);
    }

    @Test
    void acceptsPrivateTextMessage() {
        Map<String, Object> payload = Map.of(
                "event_type", 1001,
                "msgid", 42L,
                "type", 1,
                "timestamp", 1793267914L,
                "wxid", "wxid_friend",
                "sender", "wxid_friend",
                "roomid", "",
                "content", "你好");

        HookCallbackService.Result result = service.handle(payload);

        assertThat(result.status()).isEqualTo("ACCEPTED");
        ArgumentCaptor<AgentDtos.AgentEventRequest> captor =
                ArgumentCaptor.forClass(AgentDtos.AgentEventRequest.class);
        verify(inboxService).accept(captor.capture());
        AgentDtos.AgentEventRequest event = captor.getValue();
        assertThat(event.eventId()).isEqualTo("hook_42");
        assertThat(event.chatId()).isEqualTo("wxid_friend");
        assertThat(event.chatType()).isEqualTo(ChatType.PRIVATE);
        assertThat(event.senderId()).isEqualTo("wxid_friend");
        assertThat(event.content()).isEqualTo("你好");
        assertThat(event.fromSelf()).isFalse();
    }

    @Test
    void usesRoomIdForGroupMessages() {
        properties.setCallbackGroupsEnabled(true);
        Map<String, Object> payload = Map.of(
                "event_type", 1001,
                "msgid", 43L,
                "type", 1,
                "wxid", "123@chatroom",
                "sender", "wxid_member",
                "roomid", "123@chatroom",
                "content", "群消息");

        HookCallbackService.Result result = service.handle(payload);

        assertThat(result.status()).isEqualTo("ACCEPTED");
        ArgumentCaptor<AgentDtos.AgentEventRequest> captor =
                ArgumentCaptor.forClass(AgentDtos.AgentEventRequest.class);
        verify(inboxService).accept(captor.capture());
        assertThat(captor.getValue().chatId()).isEqualTo("123@chatroom");
        assertThat(captor.getValue().chatType()).isEqualTo(ChatType.GROUP);
        assertThat(captor.getValue().senderId()).isEqualTo("wxid_member");
    }

    @Test
    void ignoresSelfMessages() {
        properties.setSelfWxid("wxid_me");
        Map<String, Object> payload = Map.of(
                "event_type", 1001,
                "msgid", 44L,
                "type", 1,
                "wxid", "wxid_me",
                "sender", "wxid_me",
                "roomid", "",
                "content", "自己发的");

        assertThat(service.handle(payload).status()).isEqualTo("IGNORED");
        verifyNoInteractions(inboxService);
    }

    @Test
    void ignoresGroupMessagesByDefault() {
        Map<String, Object> payload = Map.of(
                "event_type", 1001,
                "msgid", 45L,
                "type", 1,
                "wxid", "123@chatroom",
                "sender", "wxid_member",
                "roomid", "123@chatroom",
                "content", "hi");

        assertThat(service.handle(payload).status()).isEqualTo("IGNORED");
        verifyNoInteractions(inboxService);
    }

    @Test
    void respectsAllowList() {
        properties.setAllowedChats(List.of("wxid_allowed"));
        Map<String, Object> payload = Map.of(
                "event_type", 1001,
                "msgid", 46L,
                "type", 1,
                "wxid", "wxid_other",
                "sender", "wxid_other",
                "roomid", "",
                "content", "hi");

        assertThat(service.handle(payload).status()).isEqualTo("IGNORED");
        verifyNoInteractions(inboxService);
    }

    @Test
    void ignoresNonMessageEventsAndNonTextPayloads() {
        assertThat(service.handle(Map.of(
                "event_type", 10002, "msgid", 47L, "type", 1, "wxid", "w", "content", "x")).status())
                .isEqualTo("IGNORED");
        assertThat(service.handle(Map.of(
                "event_type", 1001, "msgid", 48L, "type", 47, "wxid", "w", "content", "x")).status())
                .isEqualTo("IGNORED");
        verifyNoInteractions(inboxService);
    }

    @Test
    void runtimeAllowListTakesEffectWithoutRestart() {
        properties.setAllowedChats(List.of("wxid_other"));
        settings.updateChatPolicy(List.of("wxid_live"), true, false);
        Map<String, Object> payload = Map.of(
                "event_type", 1001,
                "msgid", 50L,
                "type", 1,
                "wxid", "wxid_live",
                "sender", "wxid_live",
                "roomid", "",
                "content", "hi");

        assertThat(service.handle(payload).status()).isEqualTo("ACCEPTED");
        verify(inboxService).accept(any());
    }

    @Test
    void ignoresWhenCallbackDisabled() {
        properties.setCallbackEnabled(false);
        Map<String, Object> payload = Map.of(
                "event_type", 1001, "msgid", 49L, "type", 1, "wxid", "wxid_a", "content", "x");

        assertThat(service.handle(payload).status()).isEqualTo("IGNORED");
        verifyNoInteractions(inboxService);
    }
}
