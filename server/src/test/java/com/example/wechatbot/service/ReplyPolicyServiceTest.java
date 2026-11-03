package com.example.wechatbot.service;

import com.example.wechatbot.common.ApiException;
import com.example.wechatbot.domain.BotConfig;
import com.example.wechatbot.domain.ReplyMode;
import com.example.wechatbot.domain.ReplyPolicy;
import com.example.wechatbot.repository.ReplyPolicyRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 回复策略的三层合并：机器人配置 → 默认策略 → 按群策略。
 */
class ReplyPolicyServiceTest {

    private final ReplyPolicyRepository repository = mock(ReplyPolicyRepository.class);
    private final ReplyPolicyService service = new ReplyPolicyService(repository);

    private BotConfig bot(String wakeWords) {
        BotConfig bot = new BotConfig();
        bot.setName("小助手");
        bot.setGroupWakeWords(wakeWords);
        return bot;
    }

    private ReplyPolicy row(String chatId, ReplyMode mode, String wakeWords, Double rate, boolean enabled) {
        ReplyPolicy policy = new ReplyPolicy();
        policy.setChatId(chatId);
        policy.setMode(mode);
        policy.setWakeWords(wakeWords);
        policy.setRandomRate(rate);
        policy.setEnabled(enabled);
        return policy;
    }

    @Test
    void blankWakeWordsMeansAlwaysReply() {
        ReplyPolicyService.Effective effective = service.forChat("123@chatroom", bot(""));

        assertThat(effective.mode()).isEqualTo(ReplyMode.ALWAYS);
        assertThat(effective.source()).isEqualTo("bot");
        assertThat(effective.inherited()).isTrue();
    }

    @Test
    void wakeWordsInBotConfigMeansWakeMode() {
        ReplyPolicyService.Effective effective = service.forChat("123@chatroom", bot("@机器人"));

        assertThat(effective.mode()).isEqualTo(ReplyMode.WAKE);
        assertThat(effective.wakeWords()).isEqualTo("@机器人");
        assertThat(effective.randomRate()).isEqualTo(ReplyPolicyService.DEFAULT_RANDOM_RATE);
    }

    @Test
    void defaultPolicyOverridesBotLayer() {
        when(repository.findByChatIdIsNull())
                .thenReturn(Optional.of(row(null, ReplyMode.RANDOM, null, 0.5, true)));

        ReplyPolicyService.Effective effective = service.forChat("123@chatroom", bot("@机器人"));

        assertThat(effective.mode()).isEqualTo(ReplyMode.RANDOM);
        assertThat(effective.randomRate()).isEqualTo(0.5);
        // 没填唤醒词就沿用机器人配置
        assertThat(effective.wakeWords()).isEqualTo("@机器人");
        assertThat(effective.source()).isEqualTo("default");
        assertThat(effective.inherited()).isTrue();
    }

    @Test
    void chatPolicyWinsButKeepsInheritedFields() {
        when(repository.findByChatIdIsNull())
                .thenReturn(Optional.of(row(null, ReplyMode.RANDOM, null, 0.2, true)));
        when(repository.findByChatId("123@chatroom"))
                .thenReturn(Optional.of(row("123@chatroom", ReplyMode.ALWAYS, null, null, true)));

        ReplyPolicyService.Effective effective = service.forChat("123@chatroom", bot("@机器人"));

        assertThat(effective.mode()).isEqualTo(ReplyMode.ALWAYS);
        assertThat(effective.randomRate()).isEqualTo(0.2);
        assertThat(effective.source()).isEqualTo("chat");
        assertThat(effective.inherited()).isFalse();
    }

    @Test
    void disabledChatPolicyFallsBackToDefault() {
        when(repository.findByChatIdIsNull())
                .thenReturn(Optional.of(row(null, ReplyMode.WAKE, "@小助手", null, true)));
        when(repository.findByChatId("123@chatroom"))
                .thenReturn(Optional.of(row("123@chatroom", ReplyMode.ALWAYS, null, null, false)));

        ReplyPolicyService.Effective effective = service.forChat("123@chatroom", bot("@机器人"));

        assertThat(effective.mode()).isEqualTo(ReplyMode.WAKE);
        assertThat(effective.wakeWords()).isEqualTo("@小助手");
        assertThat(effective.inherited()).isTrue();
    }

    @Test
    void otherChatsAreNotAffected() {
        when(repository.findByChatId("123@chatroom"))
                .thenReturn(Optional.of(row("123@chatroom", ReplyMode.ALWAYS, null, null, true)));
        when(repository.findByChatId("456@chatroom")).thenReturn(Optional.empty());

        assertThat(service.forChat("456@chatroom", bot("@机器人")).mode()).isEqualTo(ReplyMode.WAKE);
    }

    @Test
    void savingDefaultWritesOnlyTheGivenFields() {
        ReplyPolicy existing = row(null, null, null, null, true);
        when(repository.findByChatIdIsNull()).thenReturn(Optional.of(existing));
        when(repository.save(any(ReplyPolicy.class))).thenAnswer(inv -> inv.getArgument(0));

        ReplyPolicy saved = service.saveDefault(
                new ReplyPolicyService.Values(ReplyMode.RANDOM, null, 0.4, null, null));

        assertThat(saved.getMode()).isEqualTo(ReplyMode.RANDOM);
        assertThat(saved.getRandomRate()).isEqualTo(0.4);
        assertThat(saved.getChatId()).isNull();
        assertThat(saved.isDefaultPolicy()).isTrue();
    }

    @Test
    void deleteRemovesChatPolicy() {
        ReplyPolicy existing = row("123@chatroom", ReplyMode.ALWAYS, null, null, true);
        when(repository.findByChatId("123@chatroom")).thenReturn(Optional.of(existing));

        service.deleteForChat("123@chatroom");

        verify(repository).delete(existing);
    }

    @Test
    void rejectsWakeModeWithoutWordsAndInvalidRate() {
        assertThatThrownBy(() -> service.saveForChat("123@chatroom", "测试群",
                new ReplyPolicyService.Values(ReplyMode.WAKE, "  ", null, null, null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("唤醒词");

        assertThatThrownBy(() -> service.saveForChat("123@chatroom", "测试群",
                new ReplyPolicyService.Values(ReplyMode.RANDOM, null, 1.5, null, null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("0 到 1");
    }

    @Test
    void humanModeKeepsConfiguredCooldown() {
        ReplyPolicy policy = new ReplyPolicy();
        policy.setMode(ReplyMode.HUMAN);
        policy.setHumanCooldownSeconds(120);
        policy.setEnabled(true);
        when(repository.findByChatIdIsNull()).thenReturn(Optional.of(policy));

        ReplyPolicyService.Effective effective = service.forChat("123@chatroom", bot("@机器人"));

        assertThat(effective.mode()).isEqualTo(ReplyMode.HUMAN);
        assertThat(effective.humanCooldownSeconds()).isEqualTo(120);
        assertThat(effective.source()).isEqualTo("default");
    }

    @Test
    void humanCooldownFallsBackToSixtySeconds() {
        assertThat(service.forChat("123@chatroom", bot("@机器人")).humanCooldownSeconds())
                .isEqualTo(ReplyPolicyService.DEFAULT_HUMAN_COOLDOWN_SECONDS);
    }

    @Test
    void rejectsInvalidHumanCooldown() {
        assertThatThrownBy(() -> service.saveForChat("123@chatroom", "测试群",
                new ReplyPolicyService.Values(ReplyMode.HUMAN, null, null, 5000, null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("3600");
    }

    @Test
    void rejectsBlankChatId() {
        assertThatThrownBy(() -> service.saveForChat(" ", "测试群",
                new ReplyPolicyService.Values(ReplyMode.ALWAYS, null, null, null, null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("请选择");
        verify(repository, never()).save(any());
    }
}
