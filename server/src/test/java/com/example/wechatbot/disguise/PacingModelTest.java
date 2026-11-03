package com.example.wechatbot.disguise;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PacingModelTest {

    @Test
    void capsConfiguredDelayAtThirtySeconds() {
        DisguiseProperties properties = new DisguiseProperties();
        properties.setMaxDelayMs(90_000);
        properties.setLongPauseProbability(1);
        properties.setAwayProbability(1);
        properties.setAwayMinMs(240_000);
        properties.setAwayMaxMs(900_000);

        long delay = new PacingModel(properties).delayMs("wxid_delay", "收到，我看一下", false, Instant.now());

        assertEquals(30_000, delay);
    }

    @Test
    void keepsLowerConfiguredCap() {
        DisguiseProperties properties = new DisguiseProperties();
        properties.setMaxDelayMs(12_000);
        properties.setLongPauseProbability(1);

        long delay = new PacingModel(properties).delayMs("wxid_delay", "收到，我看一下", false, Instant.now());

        assertEquals(12_000, delay);
        assertEquals(30_000, PacingModel.effectiveMaxDelayMs(90_000));
    }
}
