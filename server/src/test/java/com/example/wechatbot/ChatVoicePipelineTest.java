package com.example.wechatbot;

import com.example.wechatbot.ai.SecretCipher;
import com.example.wechatbot.asr.VoiceTranscriber;
import com.example.wechatbot.domain.BotConfig;
import com.example.wechatbot.domain.ChatType;
import com.example.wechatbot.domain.Conversation;
import com.example.wechatbot.domain.MediaKind;
import com.example.wechatbot.domain.MessageDirection;
import com.example.wechatbot.domain.MessageLog;
import com.example.wechatbot.domain.MessageStatus;
import com.example.wechatbot.domain.ModelProvider;
import com.example.wechatbot.repository.BotConfigRepository;
import com.example.wechatbot.repository.ConversationRepository;
import com.example.wechatbot.repository.MessageLogRepository;
import com.example.wechatbot.repository.ModelProviderRepository;
import com.example.wechatbot.service.ChatOrchestrator;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 语音消息进模型的那一段：转写成功要替换占位文本，转写失败要老实说「听不到」。
 */
@SpringBootTest
@ActiveProfiles("test")
class ChatVoicePipelineTest {
    @Autowired private ModelProviderRepository providerRepository;
    @Autowired private BotConfigRepository botRepository;
    @Autowired private ConversationRepository conversationRepository;
    @Autowired private MessageLogRepository messageRepository;
    @Autowired private SecretCipher secretCipher;
    @Autowired private ChatOrchestrator chatOrchestrator;
    @MockitoBean private VoiceTranscriber voiceTranscriber;

    private final AtomicReference<String> lastPrompt = new AtomicReference<>("");
    private HttpServer server;

    @BeforeEach
    void startLlmStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            lastPrompt.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"好的\"}}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopLlmStub() {
        if (server != null) {
            server.stop(0);
        }
    }

    private MessageLog voiceInbound(String content) {
        ModelProvider provider = new ModelProvider();
        provider.setName("Mock OpenAI Voice");
        provider.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        provider.setModel("mock-chat");
        provider.setApiKeyEncrypted(secretCipher.encrypt("sk-test"));
        provider.setEnabled(true);
        provider.setPriority(10);
        provider.setTimeoutSeconds(5);
        provider = providerRepository.save(provider);

        BotConfig bot = new BotConfig();
        bot.setName("语音机器人");
        bot.setModelProviderId(provider.getId());
        bot.setSystemPrompt("你是测试助手");
        bot.setContextWindow(10);
        bot.setTemperature(0.7);
        bot.setMaxTokens(100);
        bot.setRateLimitPerMinute(20);
        bot = botRepository.save(bot);

        Conversation conversation = new Conversation();
        conversation.setBotId(bot.getId());
        conversation.setChatId("voice-chat");
        conversation.setChatName("测试用户");
        conversation.setChatType(ChatType.PRIVATE);
        conversation.setLastMessage(content);
        conversation.setLastMessageAt(OffsetDateTime.now());
        conversation = conversationRepository.save(conversation);

        MessageLog inbound = new MessageLog();
        inbound.setConversationId(conversation.getId());
        inbound.setDirection(MessageDirection.INBOUND);
        inbound.setSenderId("voice-chat");
        inbound.setSenderName("测试用户");
        inbound.setContent(content);
        inbound.setMediaKind(MediaKind.VOICE);
        inbound.setStatus(MessageStatus.RECEIVED);
        inbound = messageRepository.save(inbound);

        chatOrchestrator.generateReply(bot.getId(), conversation.getId(), inbound.getId(), false);
        return inbound;
    }

    @Test
    @DisplayName("转写成功：占位文本被替换成 [语音转写]，并告诉模型别再说听不到")
    void usesTranscriptAsContent() {
        when(voiceTranscriber.transcribe(any(), any())).thenReturn(Optional.of("明天上午十点开会"));

        voiceInbound("[语音 3″]");

        assertThat(lastPrompt.get()).contains("[语音转写] 明天上午十点开会");
        assertThat(lastPrompt.get()).contains("已经转成文字");
        assertThat(lastPrompt.get()).doesNotContain("听不到内容");
    }

    @Test
    @DisplayName("没转写成功：仍然只给时长，并明确告诉模型看不到内容")
    void keepsDurationOnlyWhenTranscriptMissing() {
        when(voiceTranscriber.transcribe(any(), any())).thenReturn(Optional.empty());

        voiceInbound("[语音 3″]");

        assertThat(lastPrompt.get()).contains("[语音 3″]");
        assertThat(lastPrompt.get()).contains("听不到内容");
        assertThat(lastPrompt.get()).doesNotContain("[语音转写]");
    }
}
