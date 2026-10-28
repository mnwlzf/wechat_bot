package com.example.wechatbot;

import com.example.wechatbot.ai.LlmReply;
import com.example.wechatbot.ai.SecretCipher;
import com.example.wechatbot.domain.BotConfig;
import com.example.wechatbot.domain.ChatType;
import com.example.wechatbot.domain.Conversation;
import com.example.wechatbot.domain.MessageDirection;
import com.example.wechatbot.domain.MessageLog;
import com.example.wechatbot.domain.MessageStatus;
import com.example.wechatbot.domain.ModelProvider;
import com.example.wechatbot.repository.BotConfigRepository;
import com.example.wechatbot.repository.ConversationRepository;
import com.example.wechatbot.repository.MessageLogRepository;
import com.example.wechatbot.repository.ModelProviderRepository;
import com.example.wechatbot.repository.OutboxMessageRepository;
import com.example.wechatbot.service.ChatOrchestrator;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class ChatPipelineIntegrationTest {
    @Autowired private ModelProviderRepository providerRepository;
    @Autowired private BotConfigRepository botRepository;
    @Autowired private ConversationRepository conversationRepository;
    @Autowired private MessageLogRepository messageRepository;
    @Autowired private OutboxMessageRepository outboxRepository;
    @Autowired private SecretCipher secretCipher;
    @Autowired private ChatOrchestrator chatOrchestrator;

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void generatesReplyAndWritesOutboxThroughOpenAiCompatibleApi() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            String body = """
                    {"choices":[{"message":{"role":"assistant","content":"你好，我是测试机器人。"}}],
                     "usage":{"prompt_tokens":12,"completion_tokens":8}}
                    """;
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();

        ModelProvider provider = new ModelProvider();
        provider.setName("Mock OpenAI");
        provider.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        provider.setModel("mock-chat");
        provider.setApiKeyEncrypted(secretCipher.encrypt("sk-test"));
        provider.setEnabled(true);
        provider.setPriority(10);
        provider.setTimeoutSeconds(5);
        provider = providerRepository.save(provider);

        BotConfig bot = new BotConfig();
        bot.setName("测试机器人");
        bot.setModelProviderId(provider.getId());
        bot.setSystemPrompt("你是测试助手");
        bot.setContextWindow(10);
        bot.setTemperature(0.7);
        bot.setMaxTokens(100);
        bot.setRateLimitPerMinute(20);
        bot = botRepository.save(bot);

        Conversation conversation = new Conversation();
        conversation.setBotId(bot.getId());
        conversation.setChatId("test-chat");
        conversation.setChatName("测试用户");
        conversation.setChatType(ChatType.PRIVATE);
        conversation.setLastMessage("你好");
        conversation.setLastMessageAt(OffsetDateTime.now());
        conversation = conversationRepository.save(conversation);

        MessageLog inbound = new MessageLog();
        inbound.setConversationId(conversation.getId());
        inbound.setDirection(MessageDirection.INBOUND);
        inbound.setSenderId("test-chat");
        inbound.setSenderName("测试用户");
        inbound.setContent("你好");
        inbound.setStatus(MessageStatus.RECEIVED);
        inbound = messageRepository.save(inbound);

        LlmReply reply = chatOrchestrator.generateReply(bot.getId(), conversation.getId(), inbound.getId());

        assertThat(reply.content()).isEqualTo("你好，我是测试机器人。");
        assertThat(reply.providerName()).isEqualTo("Mock OpenAI");
        assertThat(messageRepository.count()).isEqualTo(2);
        assertThat(outboxRepository.count()).isEqualTo(1);
        assertThat(outboxRepository.findAll().getFirst().getContent()).isEqualTo("你好，我是测试机器人。");

        MessageLog debugInbound = new MessageLog();
        debugInbound.setConversationId(conversation.getId());
        debugInbound.setDirection(MessageDirection.INBOUND);
        debugInbound.setSenderId("admin");
        debugInbound.setSenderName("管理员");
        debugInbound.setContent("在线调试");
        debugInbound.setStatus(MessageStatus.RECEIVED);
        debugInbound = messageRepository.save(debugInbound);

        chatOrchestrator.generateReply(bot.getId(), conversation.getId(), debugInbound.getId(), false);
        assertThat(outboxRepository.count()).isEqualTo(1);
    }
}

