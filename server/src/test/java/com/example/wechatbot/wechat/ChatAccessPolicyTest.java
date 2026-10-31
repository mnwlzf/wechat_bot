package com.example.wechatbot.wechat;

import com.example.wechatbot.service.RuntimeSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatAccessPolicyTest {

    private RuntimeSettingsService settings;
    private ChatAccessPolicy policy;

    @BeforeEach
    void setUp() {
        settings = mock(RuntimeSettingsService.class);
        policy = new ChatAccessPolicy(settings);
    }

    @Test
    void officialAndSystemChatsAreAlwaysRejected() {
        when(settings.acceptAll()).thenReturn(true);
        when(settings.groupsEnabled()).thenReturn(true);
        when(settings.allowedPrivateChats()).thenReturn(List.of("gh_62c4ce184e73"));
        when(settings.allowedGroupChats()).thenReturn(List.of());

        assertThat(policy.decide("gh_62c4ce184e73").allowed()).isFalse();
        assertThat(policy.decide("filehelper").allowed()).isFalse();
        assertThat(policy.decide("mmo9cq80-a3JRPTWAxH_8kqXy-llVU@weclaw").allowed()).isFalse();
        assertThat(policy.decide("gh_62c4ce184e73").reason()).contains("公众号");
    }

    @Test
    void groupChatNeedsSwitchAndWhitelist() {
        when(settings.allowedGroupChats()).thenReturn(List.of("123@chatroom"));

        when(settings.groupsEnabled()).thenReturn(false);
        assertThat(policy.decide("123@chatroom").allowed()).isFalse();
        assertThat(policy.decide("123@chatroom").reason()).isEqualTo("群聊消息未启用");

        when(settings.groupsEnabled()).thenReturn(true);
        assertThat(policy.decide("123@chatroom").allowed()).isTrue();
        assertThat(policy.decide("456@chatroom").allowed()).isFalse();
        assertThat(policy.decide("456@chatroom").reason()).isEqualTo("群聊不在白名单");
    }

    @Test
    void privateChatFallsBackToAcceptAllOnlyWhenWhitelistEmpty() {
        when(settings.allowedPrivateChats()).thenReturn(List.of());
        when(settings.acceptAll()).thenReturn(false);
        assertThat(policy.decide("wxid_friend").allowed()).isFalse();

        when(settings.acceptAll()).thenReturn(true);
        assertThat(policy.decide("wxid_friend").allowed()).isTrue();

        when(settings.allowedPrivateChats()).thenReturn(List.of("wxid_allowed"));
        assertThat(policy.decide("wxid_allowed").allowed()).isTrue();
        assertThat(policy.decide("wxid_other").allowed()).isFalse();
        assertThat(policy.decide("wxid_other").reason()).isEqualTo("会话不在私聊白名单");
    }

    @Test
    void decisionCarriesTheChatKind() {
        when(settings.allowedPrivateChats()).thenReturn(List.of());
        when(settings.acceptAll()).thenReturn(true);
        when(settings.groupsEnabled()).thenReturn(true);
        when(settings.allowedGroupChats()).thenReturn(List.of("123@chatroom"));

        assertThat(policy.decide("wxid_friend").kind()).isEqualTo(ChatIdentity.Kind.PRIVATE);
        assertThat(policy.decide("123@chatroom").kind()).isEqualTo(ChatIdentity.Kind.GROUP);
    }
}
