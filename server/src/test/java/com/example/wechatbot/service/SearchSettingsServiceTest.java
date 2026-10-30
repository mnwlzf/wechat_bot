package com.example.wechatbot.service;

import com.example.wechatbot.ai.SecretCipher;
import com.example.wechatbot.config.AppProperties;
import com.example.wechatbot.domain.AppSetting;
import com.example.wechatbot.repository.AppSettingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SearchSettingsServiceTest {
    private AppSettingRepository repository;
    private AppProperties properties;
    private SecretCipher cipher;
    private SearchSettingsService service;

    @BeforeEach
    void setUp() {
        repository = mock(AppSettingRepository.class);
        properties = new AppProperties();
        cipher = new SecretCipher(properties);
        when(repository.findAll()).thenReturn(List.of());
        service = new SearchSettingsService(repository, properties, cipher);
    }

    @Test
    void fallsBackToEnvironmentDefaults() {
        service.reload();

        assertThat(service.view().enabled()).isTrue();
        assertThat(service.view().baseUrl()).isEqualTo("https://api.deepseek.com");
        assertThat(service.view().hasApiKey()).isFalse();
    }

    @Test
    void updatePersistsAndAppliesIndependentSearchConfig() {
        SearchSettingsService.SearchSettingsView view = service.update(
                new SearchSettingsService.SearchSettingsUpdate(
                        true,
                        "ALWAYS",
                        "https://search.example.com/v1/",
                        "sk-search",
                        "sk-backup",
                        false,
                        false,
                        8,
                        true,
                        6,
                        1800,
                        25));

        assertThat(view.mode()).isEqualTo("ALWAYS");
        assertThat(view.baseUrl()).isEqualTo("https://search.example.com/v1");
        assertThat(view.hasApiKey()).isTrue();
        assertThat(view.apiKeyHint()).isEqualTo("****arch");
        assertThat(properties.getLlm().getSearch().getApiKey()).isEqualTo("sk-search");
        assertThat(properties.getLlm().getSearch().getBackupApiKey()).isEqualTo("sk-backup");
        verify(repository, org.mockito.Mockito.atLeastOnce()).save(any(AppSetting.class));
    }

    @Test
    void reloadDecryptsStoredSearchKeys() {
        String encrypted = cipher.encrypt("sk-stored");
        when(repository.findAll()).thenReturn(List.of(
                new AppSetting("search.enabled", "true"),
                new AppSetting("search.mode", "HEURISTIC"),
                new AppSetting("search.baseUrl", "https://api.deepseek.com"),
                new AppSetting("search.apiKeyEncrypted", encrypted)));

        service.reload();

        assertThat(properties.getLlm().getSearch().getApiKey()).isEqualTo("sk-stored");
        assertThat(service.view().hasApiKey()).isTrue();
    }
}
