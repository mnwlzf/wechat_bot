package com.example.wechatbot.wechat;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChatIdentityTest {

    @Test
    void recognisesGroupsAndHumanPrivateChats() {
        assertThat(ChatIdentity.kindOf("49878296302@chatroom")).isEqualTo(ChatIdentity.Kind.GROUP);
        assertThat(ChatIdentity.kindOf("wxid_rsje67b7hozm22")).isEqualTo(ChatIdentity.Kind.PRIVATE);
        assertThat(ChatIdentity.kindOf("wxid_8xvt0oaqbmwi22")).isEqualTo(ChatIdentity.Kind.PRIVATE);
    }

    @Test
    void treatsOfficialAccountsAndBotsAsNonHuman() {
        assertThat(ChatIdentity.kindOf("gh_62c4ce184e73")).isEqualTo(ChatIdentity.Kind.OFFICIAL);
        assertThat(ChatIdentity.kindOf("gh_e7d3fced2894")).isEqualTo(ChatIdentity.Kind.OFFICIAL);
        assertThat(ChatIdentity.kindOf("mmo9cq80-a3JRPTWAxH_8kqXy-llVU@weclaw"))
                .isEqualTo(ChatIdentity.Kind.OFFICIAL);
        assertThat(ChatIdentity.kindOf("wxid_abc@openim")).isEqualTo(ChatIdentity.Kind.OFFICIAL);
        assertThat(ChatIdentity.kindOf("wxid_wi_1d142z0zdj03")).isEqualTo(ChatIdentity.Kind.OFFICIAL);
        assertThat(ChatIdentity.kindOf("service_officialaccounts")).isEqualTo(ChatIdentity.Kind.OFFICIAL);
    }

    @Test
    void treatsWeChatSystemAccountsAsSystemChats() {
        assertThat(ChatIdentity.kindOf("filehelper")).isEqualTo(ChatIdentity.Kind.SYSTEM);
        assertThat(ChatIdentity.kindOf("weixin")).isEqualTo(ChatIdentity.Kind.SYSTEM);
        assertThat(ChatIdentity.kindOf("newsapp")).isEqualTo(ChatIdentity.Kind.SYSTEM);
        assertThat(ChatIdentity.kindOf("qqmail")).isEqualTo(ChatIdentity.Kind.SYSTEM);
        assertThat(ChatIdentity.kindOf("")).isEqualTo(ChatIdentity.Kind.SYSTEM);
        assertThat(ChatIdentity.kindOf(null)).isEqualTo(ChatIdentity.Kind.SYSTEM);
        assertThat(ChatIdentity.kindOf("wxid_abc@stranger")).isEqualTo(ChatIdentity.Kind.SYSTEM);
    }

    @Test
    void isNeverReplyCoversOfficialAndSystemOnly() {
        assertThat(ChatIdentity.isNeverReply("gh_62c4ce184e73")).isTrue();
        assertThat(ChatIdentity.isNeverReply("filehelper")).isTrue();
        assertThat(ChatIdentity.isNeverReply("wxid_friend")).isFalse();
        assertThat(ChatIdentity.isNeverReply("123@chatroom")).isFalse();
    }

    @Test
    void labelsAndReasonsAreHumanReadable() {
        assertThat(ChatIdentity.labelOf("123@chatroom")).isEqualTo("群聊");
        assertThat(ChatIdentity.labelOf("wxid_friend")).isEqualTo("私聊");
        assertThat(ChatIdentity.rejectReason("gh_62c4ce184e73")).contains("公众号");
        assertThat(ChatIdentity.rejectReason("wxid_friend")).isEmpty();
    }
}
