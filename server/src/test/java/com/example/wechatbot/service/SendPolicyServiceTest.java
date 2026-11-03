package com.example.wechatbot.service;

import com.example.wechatbot.common.ApiException;
import com.example.wechatbot.disguise.DisguiseProperties;
import com.example.wechatbot.domain.SendPolicy;
import com.example.wechatbot.repository.SendPolicyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 发信策略的合并规则：环境变量 → 默认策略 → 按群策略，逐字段覆盖，没配的字段继续沿用下一层。
 */
@ExtendWith(MockitoExtension.class)
class SendPolicyServiceTest {
    @Mock
    private SendPolicyRepository repository;

    private DisguiseProperties global;
    private SendPolicyService service;

    @BeforeEach
    void setUp() {
        global = new DisguiseProperties();
        global.setReadDelayMinMs(1200);
        global.setReadDelayMaxMs(7000);
        global.setPerMinute(3);
        global.setMaxParts(3);
        service = new SendPolicyService(repository, global);
    }

    @Test
    void inheritsGlobalWhenNothingConfigured() {
        when(repository.findByChatIdIsNull()).thenReturn(Optional.empty());
        when(repository.findByChatId("g1")).thenReturn(Optional.empty());

        DisguiseProperties policy = service.forChat("g1");

        assertThat(policy.getPerMinute()).isEqualTo(3);
        assertThat(policy.getReadDelayMinMs()).isEqualTo(1200);
        assertThat(service.effective("g1").inherited()).isTrue();
    }

    @Test
    void defaultPolicyOverridesOnlyConfiguredFields() {
        SendPolicy row = new SendPolicy();
        row.setPerMinute(1);
        when(repository.findByChatIdIsNull()).thenReturn(Optional.of(row));
        when(repository.findByChatId("g1")).thenReturn(Optional.empty());

        DisguiseProperties policy = service.forChat("g1");

        assertThat(policy.getPerMinute()).isEqualTo(1);
        // 没配的字段继续沿用环境变量
        assertThat(policy.getMaxParts()).isEqualTo(3);
        assertThat(policy.getReadDelayMinMs()).isEqualTo(1200);
    }

    @Test
    void chatPolicyWinsOverDefaultPolicy() {
        SendPolicy defaults = new SendPolicy();
        defaults.setPerMinute(1);
        defaults.setMaxParts(2);
        SendPolicy chat = new SendPolicy();
        chat.setChatId("g1");
        chat.setPerMinute(20);
        when(repository.findByChatIdIsNull()).thenReturn(Optional.of(defaults));
        when(repository.findByChatId("g1")).thenReturn(Optional.of(chat));

        DisguiseProperties policy = service.forChat("g1");

        assertThat(policy.getPerMinute()).isEqualTo(20);
        // 该群没配的字段从默认策略继承
        assertThat(policy.getMaxParts()).isEqualTo(2);
        assertThat(service.effective("g1").inherited()).isFalse();
    }

    @Test
    void disabledChatPolicyFallsBackToDefaults() {
        SendPolicy defaults = new SendPolicy();
        defaults.setPerMinute(4);
        SendPolicy chat = new SendPolicy();
        chat.setChatId("g1");
        chat.setEnabled(false);
        chat.setPerMinute(20);
        when(repository.findByChatIdIsNull()).thenReturn(Optional.of(defaults));
        when(repository.findByChatId("g1")).thenReturn(Optional.of(chat));

        assertThat(service.forChat("g1").getPerMinute()).isEqualTo(4);
        assertThat(service.effective("g1").inherited()).isTrue();
    }

    @Test
    void cacheIsDroppedAfterWrite() {
        when(repository.findByChatIdIsNull()).thenReturn(Optional.empty());
        when(repository.findByChatId("g1")).thenReturn(Optional.empty());
        DisguiseProperties first = service.forChat("g1");
        assertThat(service.forChat("g1")).isSameAs(first);

        SendPolicy saved = new SendPolicy();
        saved.setChatId("g1");
        saved.setPerMinute(9);
        when(repository.findByChatId("g1")).thenReturn(Optional.of(saved));
        when(repository.save(any(SendPolicy.class))).thenAnswer(inv -> inv.getArgument(0));

        service.saveForChat("g1", "测试群", new SendPolicyService.Values(
                null, null, null, null, null, null, 9, null, null, null, null, null, null, null, null));

        assertThat(service.forChat("g1").getPerMinute()).isEqualTo(9);
    }

    @Test
    void deleteRestoresDefaultPolicy() {
        SendPolicy chat = new SendPolicy();
        chat.setChatId("g1");
        when(repository.findByChatId("g1")).thenReturn(Optional.of(chat));
        when(repository.findByChatIdIsNull()).thenReturn(Optional.empty());

        service.deleteForChat("g1");

        verify(repository).delete(chat);
        assertThat(service.forChat("g1").getPerMinute()).isEqualTo(3);
    }

    @Test
    void rejectsInvalidDelayRange() {
        assertThatThrownBy(() -> service.saveForChat("g1", "", new SendPolicyService.Values(
                5000, 4000, null, null, null, null, null, null, null, null, null, null, null, null, null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("最长读消息延迟");
    }

    @Test
    void rejectsDropRateAboveOne() {
        assertThatThrownBy(() -> service.saveForChat("g1", "", new SendPolicyService.Values(
                null, null, null, null, null, null, null, null, null, null, null, null, 1.5, null, null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("不回复概率");
    }

    @Test
    void rejectsPerMinuteAbovePerHour() {
        assertThatThrownBy(() -> service.saveForChat("g1", "", new SendPolicyService.Values(
                null, null, null, null, null, null, 30, null, 10, null, null, null, null, null, null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("每分钟上限");
    }
}
