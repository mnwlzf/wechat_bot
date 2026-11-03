package com.example.wechatbot.service;

import com.example.wechatbot.domain.BotConfig;
import com.example.wechatbot.domain.ChatType;
import com.example.wechatbot.domain.Conversation;
import com.example.wechatbot.domain.PromptTemplate;
import com.example.wechatbot.repository.PromptTemplateRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 人设优先级：会话单独指定 → 全局默认人设 → 机器人自身提示词。
 * 这层顺序错了，就会出现「按群指定了却不生效」或者「默认人设被忽略」。
 */
class PromptTemplateServiceTest {
    private final PromptTemplateRepository repository = mock(PromptTemplateRepository.class);
    private final PromptTemplateService service = new PromptTemplateService(repository);

    private BotConfig bot() {
        BotConfig bot = new BotConfig();
        bot.setId(UUID.randomUUID());
        bot.setName("小助手");
        bot.setSystemPrompt("机器人默认提示词");
        return bot;
    }

    private Conversation group(UUID templateId) {
        Conversation conversation = new Conversation();
        conversation.setId(UUID.randomUUID());
        conversation.setChatId("123@chatroom");
        conversation.setChatName("摸鱼群");
        conversation.setChatType(ChatType.GROUP);
        conversation.setPromptTemplateId(templateId);
        return conversation;
    }

    private PromptTemplate template(String name, String content, boolean isDefault) {
        PromptTemplate template = new PromptTemplate();
        template.setId(UUID.randomUUID());
        template.setName(name);
        template.setContent(content);
        template.setDefault(isDefault);
        return template;
    }

    @Test
    void boundPersonaWinsOverDefault() {
        PromptTemplate bound = template("客服语气", "你是客服", false);
        when(repository.findById(bound.getId())).thenReturn(Optional.of(bound));
        when(repository.findFirstByIsDefaultTrue()).thenReturn(Optional.of(template("大肥鱼", "你是胖鱼", true)));

        PromptTemplateService.Persona persona = service.resolve(bot(), group(bound.getId()));

        assertThat(persona.content()).isEqualTo("你是客服");
        assertThat(persona.name()).isEqualTo("客服语气");
    }

    @Test
    void unboundConversationFallsBackToDefaultPersona() {
        when(repository.findFirstByIsDefaultTrue())
                .thenReturn(Optional.of(template("大肥鱼", "你是胖鱼", true)));

        PromptTemplateService.Persona persona = service.resolve(bot(), group(null));

        assertThat(persona.content()).isEqualTo("你是胖鱼");
        assertThat(persona.defaultPersona()).isTrue();
    }

    @Test
    void withoutDefaultPersonaUsesBotPrompt() {
        when(repository.findFirstByIsDefaultTrue()).thenReturn(Optional.empty());

        PromptTemplateService.Persona persona = service.resolve(bot(), group(null));

        assertThat(persona.content()).isEqualTo("机器人默认提示词");
        assertThat(persona.templateId()).isNull();
    }

    @Test
    void boundTemplateThatWasDeletedFallsBackToDefault() {
        UUID missing = UUID.randomUUID();
        when(repository.findById(missing)).thenReturn(Optional.empty());
        when(repository.findFirstByIsDefaultTrue())
                .thenReturn(Optional.of(template("大肥鱼", "你是胖鱼", true)));

        assertThat(service.resolve(bot(), group(missing)).content()).isEqualTo("你是胖鱼");
    }

    @Test
    void setDefaultClearsOtherFlagsFirst() {
        PromptTemplate target = template("大肥鱼", "你是胖鱼", false);
        when(repository.findById(target.getId())).thenReturn(Optional.of(target));
        when(repository.save(any(PromptTemplate.class))).thenAnswer(inv -> inv.getArgument(0));

        PromptTemplate saved = service.setDefault(target.getId());

        verify(repository).clearDefaultFlags();
        assertThat(saved.isDefault()).isTrue();
    }

    @Test
    void effectivePersonaNameMarksInheritedDefault() {
        when(repository.findFirstByIsDefaultTrue())
                .thenReturn(Optional.of(template("大肥鱼", "你是胖鱼", true)));

        assertThat(service.effectivePersonaName(group(null))).isEqualTo("大肥鱼（默认）");
    }
}
