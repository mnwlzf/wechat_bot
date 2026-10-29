package com.example.wechatbot.ai;

import com.example.wechatbot.config.LlmBootstrapProperties;
import com.example.wechatbot.repository.BotConfigRepository;
import com.example.wechatbot.repository.ModelProviderRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LlmBootstrapSkipTest {

    @Test
    void skipsWhenApiKeyMissing() {
        LlmBootstrapProperties properties = new LlmBootstrapProperties();
        properties.getPrimary().setBaseUrl("https://api.deepseek.com");
        properties.getPrimary().setModel("deepseek-chat");
        properties.getPrimary().setApiKey("");

        ModelProviderRepository providers = mock(ModelProviderRepository.class);
        BotConfigRepository bots = mock(BotConfigRepository.class);
        SecretCipher cipher = mock(SecretCipher.class);
        when(providers.findFirstByName(anyString())).thenReturn(Optional.empty());
        when(cipher.decrypt(any())).thenReturn("");

        LlmProviderBootstrap bootstrap = new LlmProviderBootstrap(properties, providers, bots, cipher);
        bootstrap.run();

        verify(providers, never()).save(any());
        assertThat(bootstrap.status().warnings()).isNotEmpty();
        assertThat(bootstrap.status().providers()).hasSize(1);
        assertThat(bootstrap.status().providers().getFirst().action()).isEqualTo("skipped");
    }

    @Test
    void skipsWhenBaseUrlIsNotHttp() {
        LlmBootstrapProperties properties = new LlmBootstrapProperties();
        properties.getPrimary().setBaseUrl("api.deepseek.com");
        properties.getPrimary().setModel("deepseek-chat");
        properties.getPrimary().setApiKey("sk-x");

        ModelProviderRepository providers = mock(ModelProviderRepository.class);
        BotConfigRepository bots = mock(BotConfigRepository.class);
        SecretCipher cipher = mock(SecretCipher.class);

        LlmProviderBootstrap bootstrap = new LlmProviderBootstrap(properties, providers, bots, cipher);
        bootstrap.run();

        verify(providers, never()).save(any());
        assertThat(bootstrap.status().warnings())
                .anyMatch(message -> message.contains("http://"));
    }
}
