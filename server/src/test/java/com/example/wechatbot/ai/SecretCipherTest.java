package com.example.wechatbot.ai;

import com.example.wechatbot.config.AppProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SecretCipherTest {
    @Test
    void encryptsAndDecryptsWithRandomIv() {
        AppProperties properties = new AppProperties();
        properties.getSecurity().setSecretKey("unit-test-secret-key-with-enough-entropy");
        SecretCipher cipher = new SecretCipher(properties);

        String first = cipher.encrypt("sk-deepseek-demo");
        String second = cipher.encrypt("sk-deepseek-demo");

        assertThat(first).isNotEqualTo(second);
        assertThat(cipher.decrypt(first)).isEqualTo("sk-deepseek-demo");
        assertThat(cipher.decrypt(second)).isEqualTo("sk-deepseek-demo");
    }
}
