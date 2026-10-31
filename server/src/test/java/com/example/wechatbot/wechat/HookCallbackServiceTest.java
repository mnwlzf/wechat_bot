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
import static org.mockito.Mockito.never;
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
        when(inboxService.accept(any(), any()))
                .thenReturn(new AgentDtos.EventAccepted("ACCEPTED", "ok", null));
        AppSettingRepository settingsRepository = mock(AppSettingRepository.class);
        when(settingsRepository.findAll()).thenReturn(List.of());
        settings = new RuntimeSettingsService(settingsRepository, properties, new AppProperties());
        settings.reload();
        service = new HookCallbackService(properties, new ChatAccessPolicy(settings), inboxService);
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
        verify(inboxService).accept(captor.capture(), any());
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
        properties.setAllowedChats(List.of("123@chatroom"));
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
        verify(inboxService).accept(captor.capture(), any());
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

    /** 公众号、机器人、系统会话永远不回复，哪怕白名单里写了它们。 */
    @Test
    void ignoresOfficialAccountsBotsAndSystemChats() {
        List<String> ids = List.of(
                "gh_62c4ce184e73", "gh_e7d3fced2894",
                "mmo9cq80-a3JRPTWAxH_8kqXy-llVU@weclaw",
                "wxid_openim@openim",
                "filehelper", "weixin", "service_officialaccounts");
        settings.updateChatPolicy(ids, true, true);

        long msgId = 60;
        for (String id : ids) {
            Map<String, Object> payload = Map.of(
                    "event_type", 1001,
                    "msgid", msgId++,
                    "type", 1,
                    "wxid", id,
                    "sender", id,
                    "roomid", "",
                    "content", "公众号推送");
            assertThat(service.handle(payload).status()).as(id).isEqualTo("IGNORED");
        }
        verify(inboxService, never()).accept(any(), any());
    }

    /** 群聊不做「全量接收」：白名单为空时即使开了全量接收也不回，必须逐个加群。 */
    @Test
    void groupChatsMustBeWhitelistedEvenWithAcceptAll() {
        settings.updateChatPolicy(List.of(), true, true);
        Map<String, Object> payload = Map.of(
                "event_type", 1001,
                "msgid", 70L,
                "type", 1,
                "wxid", "999@chatroom",
                "sender", "wxid_member",
                "roomid", "999@chatroom",
                "content", "@机器人 在吗");

        assertThat(service.handle(payload).status()).isEqualTo("IGNORED");
        verify(inboxService, never()).accept(any(), any());

        settings.updateChatPolicy(List.of("999@chatroom"), true, true);
        assertThat(service.handle(payload).status()).isEqualTo("ACCEPTED");
        verify(inboxService).accept(any(), any());
    }

    /** 私聊白名单非空时，只有名单内的私聊会被回复；全量接收不再兜底。 */
    @Test
    void privateWhitelistWinsOverAcceptAll() {
        settings.updateChatPolicy(List.of("wxid_allowed"), true, false);
        Map<String, Object> payload = Map.of(
                "event_type", 1001,
                "msgid", 71L,
                "type", 1,
                "wxid", "wxid_stranger",
                "sender", "wxid_stranger",
                "roomid", "",
                "content", "在吗");

        assertThat(service.handle(payload).status()).isEqualTo("IGNORED");
        verify(inboxService, never()).accept(any(), any());
    }

    @Test
    void ignoresNonMessageEventsButAcceptsMediaPayloads() {        assertThat(service.handle(Map.of(
                "event_type", 10002, "msgid", 47L, "type", 1, "wxid", "w", "content", "x")).status())
                .isEqualTo("IGNORED");

        ArgumentCaptor<AgentDtos.AgentEventRequest> captor =
                ArgumentCaptor.forClass(AgentDtos.AgentEventRequest.class);
        assertThat(service.handle(Map.of(
                "event_type", 1001, "msgid", 48L, "type", 47, "wxid", "w", "content", "")).status())
                .isEqualTo("ACCEPTED");
        verify(inboxService).accept(captor.capture(), any());
        assertThat(captor.getValue().mediaKind()).isEqualTo("STICKER");
        assertThat(captor.getValue().content()).isEqualTo("[表情]");
        assertThat(captor.getValue().rawType()).isEqualTo(47);
    }

    /** 位置、链接、未知类型都要能落到事件里，而不是一句「[消息 type=48]」。 */
    @Test
    void rendersCardAndUnknownTypesInsteadOfRawXml() {
        ArgumentCaptor<AgentDtos.AgentEventRequest> captor =
                ArgumentCaptor.forClass(AgentDtos.AgentEventRequest.class);

        service.handle(Map.of(
                "event_type", 1001, "msgid", 51L, "type", 48, "wxid", "w",
                "content", "<msg><location x=\"39.9\" y=\"116.3\" label=\"北京市海淀区中关村大街1号\""
                        + " poiname=\"中关村\" /></msg>"));

        service.handle(Map.of(
                "event_type", 1001, "msgid", 52L, "type", 49, "wxid", "w",
                "content", "<msg><appmsg><title>销冠在哪里都是销冠</title><des /><type>5</type>"
                        + "<url>https://www.xiaohongshu.com/discovery/item/6ae3</url>"
                        + "<appattach><totallen>0</totallen><fileext /></appattach></appmsg></msg>"));

        service.handle(Map.of(
                "event_type", 1001, "msgid", 53L, "type", 9999, "wxid", "w", "content", ""));

        verify(inboxService, org.mockito.Mockito.times(3)).accept(captor.capture(), any());
        List<AgentDtos.AgentEventRequest> events = captor.getAllValues();

        assertThat(events.get(0).mediaKind()).isEqualTo("LOCATION");
        assertThat(events.get(0).content()).isEqualTo("[位置] 中关村 · 北京市海淀区中关村大街1号");
        assertThat(events.get(1).mediaKind()).isEqualTo("LINK");
        assertThat(events.get(1).content()).startsWith("[链接] 销冠在哪里都是销冠");
        assertThat(events.get(2).mediaKind()).isEqualTo("OTHER");
        assertThat(events.get(2).content()).isEqualTo("[消息 type=9999]");
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
        verify(inboxService).accept(any(), any());
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
