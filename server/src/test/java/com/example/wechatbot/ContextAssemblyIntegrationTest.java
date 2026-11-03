package com.example.wechatbot;

import com.example.wechatbot.ai.SecretCipher;
import com.example.wechatbot.domain.BotConfig;
import com.example.wechatbot.domain.ChatMemory;
import com.example.wechatbot.domain.ChatType;
import com.example.wechatbot.domain.Conversation;
import com.example.wechatbot.domain.MemoryKind;
import com.example.wechatbot.domain.MemorySource;
import com.example.wechatbot.domain.MessageDirection;
import com.example.wechatbot.domain.MessageLog;
import com.example.wechatbot.domain.MessageStatus;
import com.example.wechatbot.domain.ModelProvider;
import com.example.wechatbot.domain.PromptTemplate;
import com.example.wechatbot.repository.BotConfigRepository;
import com.example.wechatbot.repository.ChatMemoryRepository;
import com.example.wechatbot.repository.ConversationRepository;
import com.example.wechatbot.repository.MessageLogRepository;
import com.example.wechatbot.repository.ModelProviderRepository;
import com.example.wechatbot.repository.PromptTemplateRepository;
import com.example.wechatbot.service.ChatOrchestrator;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 上下文拼装端到端验证：真的发一次模型请求，把请求体抓下来看里面装了什么。
 *
 * <p>锁定三件事：未指定人设的群走默认人设、群里的共享记忆会被注入、
 * 单独指定人设后默认人设不再出现（顺序反了就是「按群指定不生效」）。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ContextAssemblyIntegrationTest {
    @Autowired private ModelProviderRepository providerRepository;
    @Autowired private BotConfigRepository botRepository;
    @Autowired private ConversationRepository conversationRepository;
    @Autowired private MessageLogRepository messageRepository;
    @Autowired private PromptTemplateRepository promptTemplateRepository;
    @Autowired private ChatMemoryRepository memoryRepository;
    @Autowired private SecretCipher secretCipher;
    @Autowired private ChatOrchestrator chatOrchestrator;

    private final List<String> requests = new CopyOnWriteArrayList<>();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void defaultPersonaAndSharedMemoryAreInjectedThenBoundPersonaWins() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = """
                    {"choices":[{"message":{"role":"assistant","content":"收到"}}],
                     "usage":{"prompt_tokens":10,"completion_tokens":2}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        ModelProvider provider = new ModelProvider();
        provider.setName("Context Mock");
        provider.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        provider.setModel("mock-chat");
        provider.setApiKeyEncrypted(secretCipher.encrypt("sk-test"));
        provider.setEnabled(true);
        provider.setPriority(1);
        provider.setTimeoutSeconds(5);
        provider = providerRepository.save(provider);

        BotConfig bot = new BotConfig();
        bot.setName("上下文测试机器人");
        bot.setModelProviderId(provider.getId());
        bot.setSystemPrompt("MARKER_BOT_PROMPT");
        bot.setContextWindow(10);
        bot.setRateLimitPerMinute(20);
        bot = botRepository.save(bot);

        Conversation conversation = new Conversation();
        conversation.setBotId(bot.getId());
        conversation.setChatId("ctx-" + System.nanoTime() + "@chatroom");
        conversation.setChatName("上下文测试群");
        conversation.setChatType(ChatType.GROUP);
        conversation.setLastMessage("大家好");
        conversation.setLastMessageAt(OffsetDateTime.now());
        conversation = conversationRepository.save(conversation);

        promptTemplateRepository.clearDefaultFlags();
        PromptTemplate defaultPersona = new PromptTemplate();
        defaultPersona.setName("默认人设");
        defaultPersona.setContent("MARKER_DEFAULT_PERSONA");
        defaultPersona.setDefault(true);
        defaultPersona = promptTemplateRepository.save(defaultPersona);

        PromptTemplate boundPersona = new PromptTemplate();
        boundPersona.setName("群专用人设");
        boundPersona.setContent("MARKER_BOUND_PERSONA");
        boundPersona = promptTemplateRepository.save(boundPersona);

        ChatMemory summary = new ChatMemory();
        summary.setChatId(conversation.getChatId());
        summary.setChatName(conversation.getChatName());
        summary.setKind(MemoryKind.SUMMARY);
        summary.setSource(MemorySource.AUTO);
        summary.setContent("MARKER_MEMORY_SUMMARY");
        memoryRepository.save(summary);

        ChatMemory note = new ChatMemory();
        note.setChatId(conversation.getChatId());
        note.setChatName(conversation.getChatName());
        note.setKind(MemoryKind.NOTE);
        note.setSource(MemorySource.MANUAL);
        note.setContent("MARKER_MEMORY_NOTE");
        memoryRepository.save(note);

        // 未指定人设：走默认人设 + 共享记忆
        chatOrchestrator.generateReply(bot.getId(), conversation.getId(),
                inbound(conversation, "今天聊点啥").getId(), false);

        assertThat(requests).hasSize(1);
        String inherited = requests.get(0);
        assertThat(inherited).contains("MARKER_DEFAULT_PERSONA");
        assertThat(inherited).contains("MARKER_MEMORY_SUMMARY");
        assertThat(inherited).contains("MARKER_MEMORY_NOTE");
        assertThat(inherited).doesNotContain("MARKER_BOUND_PERSONA");
        assertThat(inherited).doesNotContain("MARKER_BOT_PROMPT");

        // 给这个群单独指定人设：默认人设让位，共享记忆仍然在
        conversation.setPromptTemplateId(boundPersona.getId());
        conversationRepository.save(conversation);
        chatOrchestrator.generateReply(bot.getId(), conversation.getId(),
                inbound(conversation, "换个说法").getId(), false);

        assertThat(requests).hasSize(2);
        String bound = requests.get(1);
        assertThat(bound).contains("MARKER_BOUND_PERSONA");
        assertThat(bound).doesNotContain("MARKER_DEFAULT_PERSONA");
        assertThat(bound).contains("MARKER_MEMORY_SUMMARY");
    }

    private MessageLog inbound(Conversation conversation, String content) {
        MessageLog inbound = new MessageLog();
        inbound.setConversationId(conversation.getId());
        inbound.setDirection(MessageDirection.INBOUND);
        inbound.setSenderId("wxid_ctx");
        inbound.setSenderName("群友");
        inbound.setContent(content);
        inbound.setStatus(MessageStatus.RECEIVED);
        return messageRepository.save(inbound);
    }
}
