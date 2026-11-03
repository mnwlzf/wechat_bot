package com.example.wechatbot.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 拟人模式的判定节流：同一个群在冷却时间内只问一次模型。
 */
class HumanReplyGuardTest {

    @Test
    void allowsOnceThenBlocksWithinCooldown() {
        HumanReplyGuard guard = new HumanReplyGuard();

        assertThat(guard.acquire("123@chatroom", 60, 1_000L)).isTrue();
        // 冷却期内（59 秒）不再判定
        assertThat(guard.acquire("123@chatroom", 60, 60_000L)).isFalse();
        // 冷却结束后重新允许
        assertThat(guard.acquire("123@chatroom", 60, 61_000L)).isTrue();
    }

    @Test
    void chatsDoNotAffectEachOther() {
        HumanReplyGuard guard = new HumanReplyGuard();

        assertThat(guard.acquire("a@chatroom", 60, 0L)).isTrue();
        assertThat(guard.acquire("b@chatroom", 60, 1_000L)).isTrue();
        assertThat(guard.acquire("a@chatroom", 60, 2_000L)).isFalse();
    }

    @Test
    void zeroCooldownAlwaysAllows() {
        HumanReplyGuard guard = new HumanReplyGuard();

        assertThat(guard.acquire("a@chatroom", 0, 0L)).isTrue();
        assertThat(guard.acquire("a@chatroom", 0, 0L)).isTrue();
    }

    @Test
    void resetClearsState() {
        HumanReplyGuard guard = new HumanReplyGuard();
        assertThat(guard.acquire("a@chatroom", 60, 0L)).isTrue();
        assertThat(guard.acquire("a@chatroom", 60, 1_000L)).isFalse();

        guard.reset();

        assertThat(guard.acquire("a@chatroom", 60, 2_000L)).isTrue();
    }
}
