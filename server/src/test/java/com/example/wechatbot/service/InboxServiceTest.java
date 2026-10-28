package com.example.wechatbot.service;

import com.example.wechatbot.repository.BotConfigRepository;
import com.example.wechatbot.repository.ConversationRepository;
import com.example.wechatbot.repository.MessageLogRepository;
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
            mock(ChatOrchestrator.class)
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
}
