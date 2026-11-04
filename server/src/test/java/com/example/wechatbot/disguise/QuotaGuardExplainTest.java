package com.example.wechatbot.disguise;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 配额排队要能说清「是谁把这条压住的」。
 *
 * <p>{@code earliestAllowed} 只给一个时间点，管理端「实时流水」需要原因：
 * 每分钟上限、最小间隔、连发冷却，还是账号级全局上限。这里锁住这段解释逻辑。</p>
 */
class QuotaGuardExplainTest {

    private static DisguiseProperties policy() {
        DisguiseProperties policy = new DisguiseProperties();
        policy.setMinGapMs(0);
        policy.setBurstSize(100);
        policy.setPerMinute(1);
        policy.setPer10Minutes(0);
        policy.setPerHour(0);
        policy.setPerDay(0);
        return policy;
    }

    @Test
    void idleChatNeedsNoQueueing() {
        QuotaGuard guard = new QuotaGuard(policy());
        long now = System.currentTimeMillis();

        QuotaGuard.Explanation explanation = guard.explain(policy(), "wxid_a", now);

        assertThat(explanation.waitMs()).isZero();
        assertThat(explanation.reason()).isEqualTo("无需排队");
        assertThat(explanation.earliest()).isEqualTo(now);
    }

    @Test
    void namesTheMinuteWindowWhenItIsTheBlocker() {
        QuotaGuard guard = new QuotaGuard(policy());
        long now = System.currentTimeMillis();
        guard.record("wxid_a", now);

        QuotaGuard.Explanation explanation = guard.explain(policy(), "wxid_a", now);

        assertThat(explanation.waitMs()).isGreaterThan(50_000L);
        assertThat(explanation.reason()).contains("每分钟上限");
        assertThat(explanation.limit()).isEqualTo("每分钟上限");
        // 解释出来的时间点必须和真正排期用的一致，否则界面上「还有几秒」就是假的
        assertThat(explanation.earliest()).isEqualTo(guard.earliestAllowed(policy(), "wxid_a", now));
    }

    @Test
    void separateChatsAreNotBlockedByEachOther() {
        QuotaGuard guard = new QuotaGuard(policy());
        long now = System.currentTimeMillis();
        guard.record("wxid_a", now);

        assertThat(guard.explain(policy(), "wxid_b", now).waitMs()).isZero();
    }
}
