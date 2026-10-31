package com.example.wechatbot.service;

import com.example.wechatbot.domain.ChatType;
import com.example.wechatbot.repository.BotConfigRepository;
import com.example.wechatbot.repository.ConversationRepository;
import com.example.wechatbot.repository.MessageLogRepository;
import com.example.wechatbot.web.dto.AgentDtos;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class InboxServiceTest {
    private final InboxService service = new InboxService(
            mock(BotConfigRepository.class),
            mock(ConversationRepository.class),
            mock(MessageLogRepository.class),
            mock(DedupeService.class),
            mock(RateLimitService.class),
            mock(ChatOrchestrator.class),
            mock(RuntimeSettingsService.class)
    );

    @Test
    void supportsMultipleWakeWords() {
        assertThat(service.matchesWakeWord("@机器人,小助手", "小助手 你好")).isTrue();
        assertThat(service.matchesWakeWord("@机器人，小助手", "@机器人 在吗")).isTrue();
        assertThat(service.matchesWakeWord("@机器人", "普通群消息")).isFalse();
    }

    @Test
    void blankWakeWordsMeansAllGroupMessages() {
        assertThat(service.matchesWakeWord("", "任意消息")).isTrue();
    }

    @Test
    void neverRepliesToOfficialAccountsBotsOrSystemChats() {
        for (String chatId : new String[]{"gh_62c4ce184e73", "xx@weclaw", "filehelper", "weixin"}) {
            AgentDtos.EventAccepted accepted = service.accept(AgentDtos.AgentEventRequest.text(
                    "evt_" + chatId, null, "message", chatId, chatId,
                    ChatType.PRIVATE, chatId, chatId, "推送", null, false));
            assertThat(accepted.status()).as(chatId).isEqualTo("IGNORED");
        }
    }
}
