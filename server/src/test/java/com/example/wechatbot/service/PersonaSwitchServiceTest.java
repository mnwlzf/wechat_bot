package com.example.wechatbot.service;

import com.example.wechatbot.ai.LlmReply;
import com.example.wechatbot.config.PersonaSwitchProperties;
import com.example.wechatbot.domain.Conversation;
import com.example.wechatbot.domain.PromptTemplate;
import com.example.wechatbot.repository.ConversationRepository;
import com.example.wechatbot.repository.PromptTemplateRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 聊天对象自己挑人设：只回名称、选完立刻绑定、回别的话就当没这回事。 */
class PersonaSwitchServiceTest {

    private final PromptTemplateRepository repository = mock(PromptTemplateRepository.class);
    private final ConversationRepository conversationRepository = mock(ConversationRepository.class);
    private final com.example.wechatbot.ai.LlmGateway llmGateway =
            mock(com.example.wechatbot.ai.LlmGateway.class);
    private final PersonaSwitchProperties properties = new PersonaSwitchProperties();
    private final PersonaSwitchService service =
            new PersonaSwitchService(repository, conversationRepository, properties, llmGateway);

    private static PromptTemplate persona(String name, String content, boolean isDefault) {
        PromptTemplate template = new PromptTemplate();
        template.setId(UUID.randomUUID());
        template.setName(name);
        template.setContent(content);
        template.setDefault(isDefault);
        return template;
    }

    private static Conversation conversation(String chatId) {
        Conversation conversation = new Conversation();
        conversation.setId(UUID.randomUUID());
        conversation.setChatId(chatId);
        return conversation;
    }

    private static com.example.wechatbot.domain.BotConfig bot() {
        com.example.wechatbot.domain.BotConfig bot = new com.example.wechatbot.domain.BotConfig();
        bot.setId(UUID.randomUUID());
        bot.setName("大肥鱼");
        return bot;
    }

    @Test
    void listShowsOnlyNamesAndChoosingOneTakesEffectImmediately() {
        PromptTemplate fish = persona("大肥鱼", "你是大肥鱼，嘴贫心软", true);
        PromptTemplate cs = persona("客服语气", "你是客服，回答问题要克制", false);
        when(repository.findAllByOrderByIsDefaultDescUpdatedAtDesc()).thenReturn(List.of(fish, cs));
        Conversation chat = conversation("123@chatroom");

        Optional<String> list = service.handle(null, chat, "换人设");

        assertThat(list).isPresent();
        assertThat(list.get()).contains("1. 大肥鱼").contains("2. 客服语气").contains("回数字");
        // 只发名称，绝不能把系统提示词正文漏给聊天对象
        assertThat(list.get()).doesNotContain("嘴贫心软").doesNotContain("克制");

        Optional<String> picked = service.handle(null, chat, "2");

        assertThat(picked).isPresent();
        assertThat(picked.get()).contains("客服语气");
        assertThat(chat.getPromptTemplateId()).isEqualTo(cs.getId());
        verify(conversationRepository).save(chat);
    }

    @Test
    void naturalLanguageCanNameAPersonaDirectly() {
        PromptTemplate fish = persona("大肥鱼", "你是大肥鱼", true);
        PromptTemplate cs = persona("客服语气", "你是客服", false);
        when(repository.findAllByOrderByIsDefaultDescUpdatedAtDesc()).thenReturn(List.of(fish, cs));
        Conversation chat = conversation("wxid_friend");

        // 没有先要名单，直接说想换成谁
        Optional<String> reply = service.handle(null, chat, "帮我换成客服语气吧");

        assertThat(reply).isPresent();
        assertThat(reply.get()).contains("客服语气");
        assertThat(chat.getPromptTemplateId()).isEqualTo(cs.getId());
    }

    @Test
    void askingForTheListInPlainWordsWorksToo() {
        when(repository.findAllByOrderByIsDefaultDescUpdatedAtDesc())
                .thenReturn(List.of(persona("大肥鱼", "你是大肥鱼", true)));
        Conversation chat = conversation("wxid_friend");

        Optional<String> reply = service.handle(null, chat, "现在都有哪些人设呀");

        assertThat(reply).isPresent();
        assertThat(reply.get()).contains("1. 大肥鱼");
    }

    @Test
    void namingThePersonaInsteadOfTheNumberAlsoSelects() {
        PromptTemplate fish = persona("大肥鱼", "你是大肥鱼", true);
        PromptTemplate cs = persona("客服语气", "你是客服", false);
        when(repository.findAllByOrderByIsDefaultDescUpdatedAtDesc()).thenReturn(List.of(fish, cs));
        Conversation chat = conversation("wxid_friend");

        service.handle(null, chat, "换人设");
        Optional<String> picked = service.handle(null, chat, "就客服语气吧");

        assertThat(picked).isPresent();
        assertThat(chat.getPromptTemplateId()).isEqualTo(cs.getId());
    }

    @Test
    void negationOrPlainChatterDoesNotSwitchAnything() {
        when(repository.findAllByOrderByIsDefaultDescUpdatedAtDesc())
                .thenReturn(List.of(persona("大肥鱼", "你是大肥鱼", true)));
        Conversation chat = conversation("wxid_friend");

        assertThat(service.handle(null, chat, "不要换成大肥鱼")).isEmpty();
        assertThat(service.handle(null, chat, "你这个人设挺有意思的")).isEmpty();
        assertThat(chat.getPromptTemplateId()).isNull();
    }

    @Test
    void otherMessagesDropThePendingListInsteadOfBeingSwallowed() {
        when(repository.findAllByOrderByIsDefaultDescUpdatedAtDesc())
                .thenReturn(List.of(persona("大肥鱼", "你是大肥鱼", true)));
        Conversation chat = conversation("wxid_friend");

        service.handle(null, chat, "换人设");
        // 回的不是编号：放弃这次选择，这句话要继续当普通消息处理
        assertThat(service.handle(null, chat, "今天天气不错")).isEmpty();
        // 名单已经失效，再回数字不会误绑
        assertThat(service.handle(null, chat, "1")).isEmpty();
        assertThat(chat.getPromptTemplateId()).isNull();
    }

    @Test
    void outOfRangeNumberIsIgnoredAndNobodyGetsBound() {
        when(repository.findAllByOrderByIsDefaultDescUpdatedAtDesc())
                .thenReturn(List.of(persona("大肥鱼", "你是大肥鱼", true)));
        Conversation chat = conversation("wxid_friend");

        service.handle(null, chat, "换人设");

        assertThat(service.handle(null, chat, "9")).isEmpty();
        assertThat(chat.getPromptTemplateId()).isNull();
    }

    @Test
    void switchCanBeTurnedOff() {
        properties.setSwitchEnabled(false);
        Conversation chat = conversation("wxid_friend");

        assertThat(service.handle(null, chat, "换人设")).isEmpty();
        assertThat(service.enabled()).isFalse();
    }

    @Test
    void plainChatterIsNeverTreatedAsACommand() {
        when(repository.findAllByOrderByIsDefaultDescUpdatedAtDesc())
                .thenReturn(List.of(persona("大肥鱼", "你是大肥鱼", true)));
        Conversation chat = conversation("wxid_friend");

        assertThat(service.handle(null, chat, "你这个人设挺有意思的")).isEmpty();
        // 提到了名字但只是随口一问，不是要换人设
        assertThat(service.handle(null, chat, "大肥鱼在吗")).isEmpty();
        assertThat(service.handle(null, chat, "今天下午开会吗")).isEmpty();
        assertThat(chat.getPromptTemplateId()).isNull();
    }

    @Test
    void confirmationIsSpokenInTheNewVoiceInsteadOfACannedLine() {
        // 固定模板「好，从现在开始用「X」。」是系统口吻：刚换完人设听到的还是原来那个腔调，
        // 用户自然会觉得「没生效」。确认语要按新人设说。
        PromptTemplate cold = persona("高冷宠溺人设", "你话少，句子短，不用感叹号", false);
        when(repository.findAllByOrderByIsDefaultDescUpdatedAtDesc()).thenReturn(List.of(cold));
        when(llmGateway.chat(any(), anyList())).thenReturn(LlmReply.rule("行。听你的。"));
        Conversation chat = conversation("wxid_friend");

        Optional<String> reply = service.handle(bot(), chat, "换成高冷宠溺人设");

        assertThat(reply).contains("行。听你的。");
        assertThat(reply.get()).doesNotContain("从现在开始用");
        verify(llmGateway).chat(any(), anyList());
    }

    @Test
    void confirmationFallsBackToTheTemplateWhenTheModelMisbehaves() {
        PromptTemplate cold = persona("高冷宠溺人设", "你话少，句子短", false);
        when(repository.findAllByOrderByIsDefaultDescUpdatedAtDesc()).thenReturn(List.of(cold));
        Conversation chat = conversation("wxid_friend");

        when(llmGateway.chat(any(), anyList())).thenReturn(LlmReply.rule("[[NO_REPLY]]"));
        assertThat(service.handle(bot(), chat, "换成高冷宠溺人设").orElse("")).contains("高冷宠溺人设");

        when(llmGateway.chat(any(), anyList())).thenThrow(new IllegalStateException("模型挂了"));
        assertThat(service.handle(bot(), chat, "换成高冷宠溺人设").orElse("")).contains("高冷宠溺人设");
    }

    @Test
    void switchingRecordsTheTimeSoOldRepliesCanBeFlagged() {
        PromptTemplate cold = persona("高冷宠溺人设", "你话少", false);
        when(repository.findAllByOrderByIsDefaultDescUpdatedAtDesc()).thenReturn(List.of(cold));
        Conversation chat = conversation("wxid_friend");

        service.handle(null, chat, "换成高冷宠溺人设");

        assertThat(chat.getPersonaSwitchedAt()).isNotNull();
    }
}
