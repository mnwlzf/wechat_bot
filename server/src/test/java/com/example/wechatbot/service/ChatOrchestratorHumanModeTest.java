package com.example.wechatbot.service;

import com.example.wechatbot.ai.ChatMessage;
import com.example.wechatbot.ai.LlmGateway;
import com.example.wechatbot.ai.LlmReply;
import com.example.wechatbot.ai.SearchAugmenter;
import com.example.wechatbot.asr.VoiceTranscriber;
import com.example.wechatbot.disguise.DisguiseProperties;
import com.example.wechatbot.disguise.DisguiseService;
import com.example.wechatbot.domain.BotConfig;
import com.example.wechatbot.domain.ChatType;
import com.example.wechatbot.domain.Conversation;
import com.example.wechatbot.domain.MessageDirection;
import com.example.wechatbot.domain.MessageLog;
import com.example.wechatbot.domain.MessageStatus;
import com.example.wechatbot.domain.ReplyMode;
import com.example.wechatbot.domain.ReplyPolicy;
import com.example.wechatbot.repository.BotConfigRepository;
import com.example.wechatbot.repository.ConversationRepository;
import com.example.wechatbot.repository.MessageLogRepository;
import com.example.wechatbot.repository.OutboxMessageRepository;
import com.example.wechatbot.repository.ReplyPolicyRepository;
import com.example.wechatbot.wechat.WeChatImageResolver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 拟人模式：模型自己决定要不要回，判定为「不回」时不发送、不占配额。
 */
class ChatOrchestratorHumanModeTest {
    private static final String GROUP = "123@chatroom";

    private final BotConfigRepository botRepository = mock(BotConfigRepository.class);
    private final ConversationRepository conversationRepository = mock(ConversationRepository.class);
    private final MessageLogRepository messageRepository = mock(MessageLogRepository.class);
    private final OutboxMessageRepository outboxRepository = mock(OutboxMessageRepository.class);
    private final LlmGateway llmGateway = mock(LlmGateway.class);
    private final SearchAugmenter searchAugmenter = mock(SearchAugmenter.class);
    private final DisguiseService disguise = mock(DisguiseService.class);
    private final DisguiseProperties disguiseProperties = new DisguiseProperties();
    private final AutoReplyService autoReply = mock(AutoReplyService.class);
    private final ContentFilterService contentFilter = mock(ContentFilterService.class);
    private final PromptTemplateService personaService = mock(PromptTemplateService.class);
    private final ChatMemoryService memoryService = mock(ChatMemoryService.class);
    private final WeChatImageResolver imageResolver = mock(WeChatImageResolver.class);
    private final VoiceTranscriber voiceTranscriber = mock(VoiceTranscriber.class);
    private final ReplyPolicyRepository replyPolicyRepository = mock(ReplyPolicyRepository.class);

    private final ChatOrchestrator orchestrator = new ChatOrchestrator(
            botRepository, conversationRepository, messageRepository, outboxRepository,
            llmGateway, searchAugmenter, disguise, disguiseProperties, autoReply, contentFilter,
            personaService, memoryService, imageResolver, voiceTranscriber,
            new ReplyPolicyService(replyPolicyRepository), new HumanReplyGuard());

    private MessageLog inbound;
    private BotConfig bot;
    private Conversation conversation;

    private void stub(ReplyMode mode, String modelOutput) {
        bot = new BotConfig();
        bot.setId(UUID.randomUUID());
        bot.setName("小助手");
        bot.setEnabled(true);
        bot.setContextWindow(20);

        conversation = new Conversation();
        conversation.setId(UUID.randomUUID());
        conversation.setBotId(bot.getId());
        conversation.setChatId(GROUP);
        conversation.setChatType(ChatType.GROUP);

        inbound = new MessageLog();
        inbound.setId(UUID.randomUUID());
        inbound.setConversationId(conversation.getId());
        inbound.setDirection(MessageDirection.INBOUND);
        inbound.setSenderName("张三");
        inbound.setContent("今天下午开会");
        inbound.setStatus(MessageStatus.RECEIVED);

        ReplyPolicy policy = new ReplyPolicy();
        policy.setMode(mode);
        policy.setHumanCooldownSeconds(0);
        policy.setEnabled(true);

        when(replyPolicyRepository.findByChatIdIsNull()).thenReturn(Optional.of(policy));
        when(botRepository.findById(bot.getId())).thenReturn(Optional.of(bot));
        when(conversationRepository.findById(conversation.getId())).thenReturn(Optional.of(conversation));
        when(messageRepository.findById(inbound.getId())).thenReturn(Optional.of(inbound));
        when(autoReply.match(any(), any())).thenReturn(Optional.empty());
        when(messageRepository.findByConversationIdOrderByCreatedAtDesc(any(), any()))
                .thenReturn(List.of(inbound));
        when(disguise.promptSuffix(any())).thenReturn("");
        when(searchAugmenter.buildContext(any(), any())).thenReturn("");
        when(personaService.resolve(any(), any()))
                .thenReturn(new PromptTemplateService.Persona(null, "小助手", "你是群里的成员", false, true));
        when(memoryService.promptBlock(any(), any())).thenReturn("");
        when(llmGateway.chat(any(), any())).thenReturn(LlmReply.rule(modelOutput));
    }

    @Test
    void modelNoReplyMarksMessageIgnoredAndSendsNothing() {
        stub(ReplyMode.HUMAN, "[[NO_REPLY]]");

        LlmReply result = orchestrator.generateReply(bot.getId(), conversation.getId(), inbound.getId());

        assertThat(result).isNull();
        assertThat(inbound.getStatus()).isEqualTo(MessageStatus.IGNORED);
        assertThat(inbound.getErrorMessage()).contains("不需要回复");
        verify(outboxRepository, never()).save(any());
        verify(memoryService, never()).summarizeAsync(any(), any());
    }

    @Test
    void mergedMessageIsNeverRepliedOnItsOwn() {
        stub(ReplyMode.ALWAYS, "好的，马上到");
        // 连发时被合并掉的那条：只当上下文，不能再单独回一条，也不能再烧一次模型调用
        inbound.setStatus(MessageStatus.IGNORED);
        inbound.setErrorMessage("已合并到随后的回复（短时间内多条消息），只作为上下文");

        LlmReply result = orchestrator.generateReply(bot.getId(), conversation.getId(), inbound.getId());

        assertThat(result).isNull();
        verify(llmGateway, never()).chat(any(), any());
        verify(outboxRepository, never()).save(any());
        assertThat(inbound.getStatus()).isEqualTo(MessageStatus.IGNORED);
    }

    @Test
    void hintIsInsertedRightAfterThePersona() {
        List<ChatMessage> context = List.of(ChatMessage.system("你是群里的成员"), ChatMessage.user("今天下午开会"));

        List<ChatMessage> withHint = ChatOrchestrator.withHumanHint(context);

        assertThat(withHint).hasSize(3);
        assertThat(withHint.get(0).content()).isEqualTo("你是群里的成员");
        assertThat(withHint.get(1).content()).contains("拟人模式").contains("[[NO_REPLY]]");
        assertThat(withHint.get(2).content()).isEqualTo("今天下午开会");
        // 不改动调用方传进来的列表
        assertThat(context).hasSize(2);
    }

    @Test
    void noReplySentinelVariantsAreRecognised() {
        assertThat(ChatOrchestrator.isNoReply("[[NO_REPLY]]")).isTrue();
        assertThat(ChatOrchestrator.isNoReply("  [[ NO_REPLY ]]  ")).isTrue();
        assertThat(ChatOrchestrator.isNoReply("[[no_reply]]")).isTrue();
        assertThat(ChatOrchestrator.isNoReply("[[不回复]]")).isTrue();
        assertThat(ChatOrchestrator.isNoReply("[[NO_REPLY]] 但我想说一句")).isTrue();
        assertThat(ChatOrchestrator.isNoReply("在的，几点？")).isFalse();
        assertThat(ChatOrchestrator.isNoReply(null)).isTrue();
    }
}
