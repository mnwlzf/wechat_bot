package com.example.wechatbot.ai;

import com.example.wechatbot.domain.BotConfig;
import com.example.wechatbot.domain.ModelProvider;
import com.example.wechatbot.repository.BotConfigRepository;
import com.example.wechatbot.repository.ModelProviderRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "app.llm.bootstrap.enabled=true",
        "app.llm.bootstrap.overwrite=true",
        "app.llm.bootstrap.assign-to-bot=true",
        "app.llm.bootstrap.primary.name=env-test",
        "app.llm.bootstrap.primary.base-url=https://api.deepseek.com",
        "app.llm.bootstrap.primary.api-key=sk-test-abcdef123456",
        "app.llm.bootstrap.primary.model=deepseek-chat",
        "app.llm.bootstrap.primary.priority=7"
})
@ActiveProfiles("test")
class LlmProviderBootstrapTest {

    @Autowired private LlmProviderBootstrap bootstrap;
    @Autowired private ModelProviderRepository providerRepository;
    @Autowired private BotConfigRepository botRepository;
    @Autowired private SecretCipher secretCipher;

    @Test
    void createsProviderFromEnvAndAssignsToBotWithoutModel() {
        ModelProvider provider = providerRepository.findFirstByName("env-test").orElseThrow();
        assertThat(provider.getBaseUrl()).isEqualTo("https://api.deepseek.com/v1");
        assertThat(provider.getModel()).isEqualTo("deepseek-chat");
        assertThat(provider.getPriority()).isEqualTo(7);
        assertThat(provider.isEnabled()).isTrue();
        assertThat(provider.getApiKeyEncrypted()).isNotEqualTo("sk-test-abcdef123456");
        assertThat(secretCipher.decrypt(provider.getApiKeyEncrypted())).isEqualTo("sk-test-abcdef123456");

        BotConfig bot = new BotConfig();
        bot.setName("bootstrap-test-bot");
        bot.setSystemPrompt("test");
        bot = botRepository.save(bot);

        bootstrap.run();

        BotConfig reloaded = botRepository.findById(bot.getId()).orElseThrow();
        assertThat(reloaded.getModelProviderId()).isEqualTo(provider.getId());
        assertThat(providerRepository.findAll().stream()
                .filter(item -> "env-test".equals(item.getName()))
                .count()).isEqualTo(1);
        assertThat(bootstrap.status().enabled()).isTrue();
        assertThat(bootstrap.status().providers()).hasSize(1);
        assertThat(bootstrap.status().providers().getFirst().baseUrl())
                .isEqualTo("https://api.deepseek.com/v1");
    }
}
