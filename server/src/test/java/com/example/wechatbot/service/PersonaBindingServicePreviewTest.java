package com.example.wechatbot.service;

import com.example.wechatbot.domain.BotConfig;
import com.example.wechatbot.domain.ChatType;
import com.example.wechatbot.domain.Conversation;
import com.example.wechatbot.domain.MessageDirection;
import com.example.wechatbot.domain.MessageLog;
import com.example.wechatbot.disguise.DisguiseService;
import com.example.wechatbot.repository.BotConfigRepository;
import com.example.wechatbot.repository.ConversationRepository;
import com.example.wechatbot.repository.MessageLogRepository;
import com.example.wechatbot.repository.PromptTemplateRepository;
import com.example.wechatbot.web.dto.AdminDtos;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 「它到底收到了什么提示词」。
 *
 * <p>人设没生效这类问题，聊天记录说不清——它只证明机器人说了什么，不证明它被告知了什么。
 * 这个预览页要用真实生成时同一批函数拼出那几段系统提示，还得主动指出「人设太短」「窗口里还有旧口吻」
 * 这些一眼看不出来、但确实会让语气不变的坑。</p>
 */
class PersonaBindingServicePreviewTest {
    private final ConversationRepository conversationRepository = mock(ConversationRepository.class);
    private final BotConfigRepository botConfigRepository = mock(BotConfigRepository.class);
    private final PromptTemplateRepository promptTemplateRepository = mock(PromptTemplateRepository.class);
    private final MessageLogRepository messageRepository = mock(MessageLogRepository.class);
    private final PromptTemplateService personaService = mock(PromptTemplateService.class);
    private final ChatMemoryService memoryService = mock(ChatMemoryService.class);
    private final DisguiseService disguise = mock(DisguiseService.class);
    private final AdminQueryService adminQueryService = mock(AdminQueryService.class);

    private final PersonaBindingService service = new PersonaBindingService(conversationRepository,
            botConfigRepository, promptTemplateRepository, messageRepository, personaService,
            memoryService, disguise, adminQueryService);

    private Conversation chat(OffsetDateTime switchedAt) {
        Conversation conversation = new Conversation();
        conversation.setId(UUID.randomUUID());
        conversation.setBotId(UUID.randomUUID());
        conversation.setChatId("wxid_demo");
        conversation.setChatName("深海");
        conversation.setChatType(ChatType.PRIVATE);
        conversation.setPersonaSwitchedAt(switchedAt);
        return conversation;
    }

    private BotConfig bot() {
        BotConfig bot = new BotConfig();
        bot.setId(UUID.randomUUID());
        bot.setContextWindow(48);
        return bot;
    }

    @Test
    void showsThePersonaThatIsReallyUsedAndWarnsAboutAThinOne() {
        Conversation conversation = chat(null);
        conversation.setPromptTemplateId(UUID.randomUUID());
        when(conversationRepository.findById(conversation.getId())).thenReturn(Optional.of(conversation));
        when(botConfigRepository.findById(conversation.getBotId())).thenReturn(Optional.of(bot()));
        when(personaService.resolve(any(), eq(conversation)))
                .thenReturn(new PromptTemplateService.Persona(UUID.randomUUID(), "高冷", "少说话", false, false));
        when(memoryService.promptBlock(any(), any())).thenReturn("【关于这个人的记忆】\n摘要：对方叫深海");
        when(disguise.promptSuffix(anyString())).thenReturn("微信聊天格式约束");

        AdminDtos.PersonaPreview view = service.preview(conversation.getId());

        assertThat(view.personaName()).isEqualTo("高冷");
        assertThat(view.personaSource()).isEqualTo("chat");
        assertThat(view.personaChars()).isEqualTo(3);
        assertThat(view.personaThin()).isTrue();
        assertThat(view.warnings()).anyMatch(w -> w.contains("只有 3 个字"));
        assertThat(view.memoryBlock()).contains("深海");
        assertThat(view.styleBlock()).contains("格式约束");
    }

    @Test
    void botFallbackIsLabelledAndMissingMemoryIsCalledOut() {
        Conversation conversation = chat(null);
        when(conversationRepository.findById(conversation.getId())).thenReturn(Optional.of(conversation));
        when(botConfigRepository.findById(conversation.getBotId())).thenReturn(Optional.of(bot()));
        when(personaService.resolve(any(), eq(conversation)))
                .thenReturn(new PromptTemplateService.Persona(null, "机器人默认提示词",
                        "你是一个友好、简洁、可靠的微信助手，回答要自然口语化，不要写成客服腔，也别长篇大论。", false, false));
        when(memoryService.promptBlock(any(), any())).thenReturn("");
        when(disguise.promptSuffix(anyString())).thenReturn("");

        AdminDtos.PersonaPreview view = service.preview(conversation.getId());

        assertThat(view.personaSource()).isEqualTo("bot");
        assertThat(view.personaThin()).isFalse();
        assertThat(view.warnings()).anyMatch(w -> w.contains("还没有记忆摘要"));
    }

    @Test
    void switchNoteAppearsWhileTheWindowStillHoldsOldReplies() {
        OffsetDateTime now = OffsetDateTime.now();
        Conversation conversation = chat(now.minusMinutes(5));
        when(conversationRepository.findById(conversation.getId())).thenReturn(Optional.of(conversation));
        when(botConfigRepository.findById(conversation.getBotId())).thenReturn(Optional.of(bot()));
        when(personaService.resolve(any(), eq(conversation)))
                .thenReturn(new PromptTemplateService.Persona(UUID.randomUUID(), "高冷",
                        "你话少，句子短，不用感叹号，也不解释自己，更不会主动找话题。", false, false));
        when(memoryService.promptBlock(any(), any())).thenReturn("摘要：对方叫深海");
        when(disguise.promptSuffix(anyString())).thenReturn("格式约束");

        MessageLog old = new MessageLog();
        old.setId(UUID.randomUUID());
        old.setDirection(MessageDirection.OUTBOUND);
        old.setContent("啊这，我给自己起的是大肥鱼啊");
        old.setCreatedAt(now.minusHours(1));
        when(messageRepository.findByConversationIdOrderByCreatedAtDesc(eq(conversation.getId()), any(Pageable.class)))
                .thenReturn(List.of(old));

        AdminDtos.PersonaPreview view = service.preview(conversation.getId());

        assertThat(view.switchNote()).isNotNull().contains("旧人设");
        assertThat(view.warnings()).anyMatch(w -> w.contains("别学旧语气"));
    }
}
