package com.example.wechatbot.asr;

import com.example.wechatbot.config.AsrProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 配置前缀守卫：{@code app.asr.*} 必须能绑到 {@link AsrProperties}。
 *
 * <p>这类错误（前缀写错、把 asr 塞进 app.llm 下面）不会编译失败，只会让密钥悄悄变成空字符串，
 * 所以用一个快速上下文测试钉住。</p>
 */
class AsrPropertiesTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Binding.class)
            .withPropertyValues(
                    "app.asr.enabled=true",
                    "app.asr.api-key=sk-test-abc",
                    "app.asr.model=XingChenAGI/XingChenASR-V3.2-Ultra",
                    "app.asr.transcribe-voice=true",
                    "app.asr.silk-decoder=node script.mjs");

    @Test
    @DisplayName("app.asr.* 绑定到 AsrProperties，密钥不是空串")
    void bindsFromAppAsrPrefix() {
        runner.run(context -> {
            AsrProperties properties = context.getBean(AsrProperties.class);
            assertThat(properties.getApiKey()).isEqualTo("sk-test-abc");
            assertThat(properties.getModel()).isEqualTo("XingChenAGI/XingChenASR-V3.2-Ultra");
            assertThat(properties.isTranscribeVoice()).isTrue();
            assertThat(properties.getSilkDecoder()).isEqualTo("node script.mjs");
            assertThat(properties.isConfigured()).isTrue();
        });
    }

    @Test
    @DisplayName("默认值：模型名与接口地址开箱可用")
    void hasSensibleDefaults() {
        AsrProperties properties = new AsrProperties();
        assertThat(properties.getBaseUrl()).isEqualTo("https://api.siliconflow.cn/v1");
        assertThat(properties.getModel()).isEqualTo("XingChenAGI/XingChenASR-V3.2-Ultra");
        assertThat(properties.isConfigured()).isFalse();
    }

    @Test
    @DisplayName("日志/接口里只回显打码后的密钥")
    void masksApiKey() {
        AsrProperties properties = new AsrProperties();
        properties.setApiKey("sk-anxjkfbraktogljnhikavqxhkcfcjjhrxmaayfmvjaoiwtge");
        assertThat(properties.maskedKey()).isEqualTo("sk-a****wtge").hasSize(12);
        properties.setApiKey("");
        assertThat(properties.maskedKey()).isEmpty();
    }

    @Configuration
    @EnableConfigurationProperties(AsrProperties.class)
    static class Binding {
    }
}
