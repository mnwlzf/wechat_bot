package com.example.wechatbot.service;

import com.example.wechatbot.config.AppProperties;
import com.example.wechatbot.config.WeChatHookProperties;
import com.example.wechatbot.domain.AppSetting;
import com.example.wechatbot.repository.AppSettingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeSettingsServiceTest {

    private AppSettingRepository repository;
    private WeChatHookProperties hookProperties;
    private AppProperties appProperties;
    private RuntimeSettingsService service;

    @BeforeEach
    void setUp() {
        repository = mock(AppSettingRepository.class);
        when(repository.findAll()).thenReturn(List.of());
        hookProperties = new WeChatHookProperties();
        appProperties = new AppProperties();
        service = new RuntimeSettingsService(repository, hookProperties, appProperties);
    }

    @Test
    void fallsBackToEnvironmentDefaults() {
        hookProperties.setAllowedChats(List.of("wxid_env"));
        hookProperties.setCallbackAcceptAll(false);
        hookProperties.setCallbackGroupsEnabled(false);

        service.reload();

        assertThat(service.allowedChats()).containsExactly("wxid_env");
        assertThat(service.acceptAll()).isFalse();
        assertThat(service.groupsEnabled()).isFalse();
        assertThat(service.llmFallbackEnabled()).isTrue();
    }

    @Test
    void databaseValuesWinOverEnvironment() {
        when(repository.findAll()).thenReturn(List.of(
                new AppSetting(RuntimeSettingsService.KEY_ALLOWED_CHATS, "wxid_a, wxid_b"),
                new AppSetting(RuntimeSettingsService.KEY_ACCEPT_ALL, "false"),
                new AppSetting(RuntimeSettingsService.KEY_GROUPS_ENABLED, "true"),
                new AppSetting(RuntimeSettingsService.KEY_LLM_FALLBACK, "false")
        ));
        hookProperties.setAllowedChats(List.of("wxid_env"));

        service.reload();

        assertThat(service.allowedChats()).containsExactly("wxid_a", "wxid_b");
        assertThat(service.acceptAll()).isFalse();
        assertThat(service.groupsEnabled()).isTrue();
        assertThat(service.llmFallbackEnabled()).isFalse();
    }

    @Test
    void updatesApplyImmediatelyAndPersist() {
        service.updateChatPolicy(List.of(" wxid_x ", "", "wxid_y", "wxid_x"), false, true);

        assertThat(service.allowedChats()).containsExactly("wxid_x", "wxid_y");
        assertThat(service.acceptAll()).isFalse();
        assertThat(service.groupsEnabled()).isTrue();
        verify(repository, org.mockito.Mockito.times(3)).save(any(AppSetting.class));

        service.updateLlmPolicy(false);
        assertThat(service.llmFallbackEnabled()).isFalse();
    }

    @Test
    void snapshotReportsSource() {
        Map<String, Object> before = service.snapshot();
        assertThat(source(before, "allowedChats")).isEqualTo("env");

        service.updateLlmPolicy(true);
        Map<String, Object> after = service.snapshot();
        assertThat(source(after, "fallbackEnabled")).isEqualTo("db");
        assertThat(source(after, "allowedChats")).isEqualTo("env");
    }

    @SuppressWarnings("unchecked")
    private static String source(Map<String, Object> snapshot, String key) {
        return String.valueOf(((Map<String, Object>) snapshot.get(key)).get("source"));
    }
}

