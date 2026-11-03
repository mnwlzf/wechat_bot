package com.example.wechatbot.service;

import com.example.wechatbot.common.ApiException;
import com.example.wechatbot.disguise.DisguiseProperties;
import com.example.wechatbot.disguise.SendPolicyResolver;
import com.example.wechatbot.domain.SendPolicy;
import com.example.wechatbot.repository.SendPolicyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 发信策略：把一份全局配置变成「全局默认 + 按群覆盖」两层，未覆盖的字段逐项回落到下一层。
 *
 * <p>合并顺序：环境变量 {@code app.disguise.*} → 默认策略（chat_id 为空）→ 该群的策略。
 * 结果是一份只读的 {@link DisguiseProperties} 快照，伪装层各组件按它来决定
 * 延迟、分条、限流和文风，因此「给某个客户群发言慢一点」不需要改全局、也不需要重启。</p>
 *
 * <p>快照按 chatId 缓存，任何一次写入都会让缓存整体失效。</p>
 */
@Service
public class SendPolicyService implements SendPolicyResolver {
    private static final Logger log = LoggerFactory.getLogger(SendPolicyService.class);

    private final SendPolicyRepository repository;
    private final DisguiseProperties global;
    private final Map<String, Snapshot> cache = new ConcurrentHashMap<>();
    private volatile long version = 0;

    public SendPolicyService(SendPolicyRepository repository, DisguiseProperties global) {
        this.repository = repository;
        this.global = global;
    }

    private record Snapshot(long version, DisguiseProperties policy) {}

    /** 页面用的可编辑字段；null 表示沿用下一层。 */
    public record Values(Integer readDelayMinMs, Integer readDelayMaxMs, Integer maxDelayMs,
                         Boolean splitEnabled, Integer maxParts, Integer maxPartChars,
                         Integer perMinute, Integer per10Minutes, Integer perHour, Integer perDay,
                         Integer minGapMs, Integer emojiLimit, Double dropRate, Boolean promptEnabled,
                         Boolean enabled) {}

    /** 合并后的生效值，附带「这个群是否自定义过」。 */
    public record Effective(int readDelayMinMs, int readDelayMaxMs, int maxDelayMs,
                            boolean splitEnabled, int maxParts, int maxPartChars,
                            int perMinute, int per10Minutes, int perHour, int perDay,
                            int minGapMs, int emojiLimit, double dropRate, boolean promptEnabled,
                            boolean inherited) {}

    @Override
    public DisguiseProperties forChat(String chatId) {
        String key = chatId == null ? "" : chatId.trim();
        long current = version;
        Snapshot cached = cache.get(key);
        if (cached != null && cached.version() == current) {
            return cached.policy();
        }
        DisguiseProperties policy = build(key);
        cache.put(key, new Snapshot(current, policy));
        return policy;
    }

    /** 生效值 + 是否继承默认（页面展示用）。 */
    public Effective effective(String chatId) {
        String key = chatId == null ? "" : chatId.trim();
        DisguiseProperties policy = forChat(key);
        boolean inherited = key.isEmpty() || repository.findByChatId(key).filter(SendPolicy::isEnabled).isEmpty();
        return new Effective(
                policy.getReadDelayMinMs(), policy.getReadDelayMaxMs(), policy.getMaxDelayMs(),
                policy.isSplitEnabled(), policy.getMaxParts(), policy.getMaxPartChars(),
                policy.getPerMinute(), policy.getPer10Minutes(), policy.getPerHour(), policy.getPerDay(),
                policy.getMinGapMs(), policy.getEmojiLimit(), policy.getDropRate(), policy.isPromptEnabled(),
                inherited);
    }

    public Optional<SendPolicy> defaultPolicy() {
        return repository.findByChatIdIsNull();
    }

    public Optional<SendPolicy> override(String chatId) {
        return chatId == null || chatId.isBlank()
                ? Optional.empty()
                : repository.findByChatId(chatId.trim());
    }

    public List<SendPolicy> overrides() {
        return repository.findAllByChatIdIsNotNullOrderByUpdatedAtDesc();
    }

    @Transactional
    public SendPolicy saveDefault(Values values) {
        SendPolicy policy = repository.findByChatIdIsNull().orElseGet(SendPolicy::new);
        policy.setChatId(null);
        policy.setChatName("");
        policy.setEnabled(true);
        apply(policy, validate(values));
        SendPolicy saved = repository.save(policy);
        invalidate();
        log.info("发信策略：默认策略已更新");
        return saved;
    }

    @Transactional
    public SendPolicy saveForChat(String chatId, String chatName, Values values) {
        if (chatId == null || chatId.isBlank()) {
            throw ApiException.badRequest("请选择要配置的会话/群");
        }
        String key = chatId.trim();
        SendPolicy policy = repository.findByChatId(key).orElseGet(SendPolicy::new);
        policy.setChatId(key);
        policy.setChatName(chatName == null ? "" : chatName.trim());
        policy.setEnabled(values.enabled() == null || values.enabled());
        apply(policy, validate(values));
        SendPolicy saved = repository.save(policy);
        invalidate();
        log.info("发信策略：{} 已更新", key);
        return saved;
    }

    /** 删除按群策略，该群立即回到默认策略。 */
    @Transactional
    public void deleteForChat(String chatId) {
        String key = chatId == null ? "" : chatId.trim();
        SendPolicy policy = repository.findByChatId(key)
                .orElseThrow(() -> ApiException.notFound("该会话没有自定义发信策略"));
        repository.delete(policy);
        invalidate();
        log.info("发信策略：{} 已恢复默认", key);
    }

    private DisguiseProperties build(String chatId) {
        DisguiseProperties policy = new DisguiseProperties();
        BeanUtils.copyProperties(global, policy);
        repository.findByChatIdIsNull()
                .filter(SendPolicy::isEnabled)
                .ifPresent(row -> apply(policy, row));
        if (!chatId.isEmpty()) {
            repository.findByChatId(chatId)
                    .filter(SendPolicy::isEnabled)
                    .ifPresent(row -> apply(policy, row));
        }
        return policy;
    }

    private void invalidate() {
        version++;
        cache.clear();
    }

    private void apply(DisguiseProperties policy, SendPolicy row) {
        if (row.getReadDelayMinMs() != null) {
            policy.setReadDelayMinMs(row.getReadDelayMinMs());
        }
        if (row.getReadDelayMaxMs() != null) {
            policy.setReadDelayMaxMs(row.getReadDelayMaxMs());
        }
        if (row.getMaxDelayMs() != null) {
            policy.setMaxDelayMs(row.getMaxDelayMs());
        }
        if (row.getSplitEnabled() != null) {
            policy.setSplitEnabled(row.getSplitEnabled());
        }
        if (row.getMaxParts() != null) {
            policy.setMaxParts(row.getMaxParts());
        }
        if (row.getMaxPartChars() != null) {
            policy.setMaxPartChars(row.getMaxPartChars());
        }
        if (row.getPerMinute() != null) {
            policy.setPerMinute(row.getPerMinute());
        }
        if (row.getPer10Minutes() != null) {
            policy.setPer10Minutes(row.getPer10Minutes());
        }
        if (row.getPerHour() != null) {
            policy.setPerHour(row.getPerHour());
        }
        if (row.getPerDay() != null) {
            policy.setPerDay(row.getPerDay());
        }
        if (row.getMinGapMs() != null) {
            policy.setMinGapMs(row.getMinGapMs());
        }
        if (row.getEmojiLimit() != null) {
            policy.setEmojiLimit(row.getEmojiLimit());
        }
        if (row.getDropRate() != null) {
            policy.setDropRate(row.getDropRate());
        }
        if (row.getPromptEnabled() != null) {
            policy.setPromptEnabled(row.getPromptEnabled());
        }
    }

    /** 只写非空字段，null 表示「这项继续沿用下一层」。 */
    private void apply(SendPolicy target, Values values) {
        if (values == null) {
            return;
        }
        if (values.readDelayMinMs() != null) {
            target.setReadDelayMinMs(values.readDelayMinMs());
        }
        if (values.readDelayMaxMs() != null) {
            target.setReadDelayMaxMs(values.readDelayMaxMs());
        }
        if (values.maxDelayMs() != null) {
            target.setMaxDelayMs(values.maxDelayMs());
        }
        if (values.splitEnabled() != null) {
            target.setSplitEnabled(values.splitEnabled());
        }
        if (values.maxParts() != null) {
            target.setMaxParts(values.maxParts());
        }
        if (values.maxPartChars() != null) {
            target.setMaxPartChars(values.maxPartChars());
        }
        if (values.perMinute() != null) {
            target.setPerMinute(values.perMinute());
        }
        if (values.per10Minutes() != null) {
            target.setPer10Minutes(values.per10Minutes());
        }
        if (values.perHour() != null) {
            target.setPerHour(values.perHour());
        }
        if (values.perDay() != null) {
            target.setPerDay(values.perDay());
        }
        if (values.minGapMs() != null) {
            target.setMinGapMs(values.minGapMs());
        }
        if (values.emojiLimit() != null) {
            target.setEmojiLimit(values.emojiLimit());
        }
        if (values.dropRate() != null) {
            target.setDropRate(values.dropRate());
        }
        if (values.promptEnabled() != null) {
            target.setPromptEnabled(values.promptEnabled());
        }
    }

    private Values validate(Values values) {
        if (values == null) {
            throw ApiException.badRequest("发信策略内容不能为空");
        }
        int readMin = values.readDelayMinMs() == null ? 0 : values.readDelayMinMs();
        int readMax = values.readDelayMaxMs() == null ? 0 : values.readDelayMaxMs();
        if (values.readDelayMinMs() != null) {
            requireRange("最短读消息延迟", readMin, 200, 600_000);
        }
        if (values.readDelayMaxMs() != null) {
            requireRange("最长读消息延迟", readMax, 200, 600_000);
        }
        if (values.readDelayMinMs() != null && values.readDelayMaxMs() != null && readMin >= readMax) {
            throw ApiException.badRequest("最长读消息延迟需要大于最短读消息延迟");
        }
        if (values.maxDelayMs() != null) {
            requireRange("单条最长延迟", values.maxDelayMs(), 1_000, 3_600_000);
        }
        if (values.maxParts() != null) {
            requireRange("最多分几条", values.maxParts(), 1, 10);
        }
        if (values.maxPartChars() != null) {
            requireRange("单条气泡字数", values.maxPartChars(), 20, 2_000);
        }
        if (values.perMinute() != null) {
            requireRange("每分钟上限", values.perMinute(), 0, 600);
        }
        if (values.per10Minutes() != null) {
            requireRange("每十分钟上限", values.per10Minutes(), 0, 6_000);
        }
        if (values.perHour() != null) {
            requireRange("每小时上限", values.perHour(), 0, 10_000);
        }
        if (values.perDay() != null) {
            requireRange("每天上限", values.perDay(), 0, 100_000);
        }
        if (values.minGapMs() != null) {
            requireRange("两条之间最小间隔", values.minGapMs(), 0, 600_000);
        }
        if (values.emojiLimit() != null) {
            requireRange("表情数量上限", values.emojiLimit(), 0, 20);
        }
        if (values.dropRate() != null && (values.dropRate() < 0 || values.dropRate() > 1)) {
            throw ApiException.badRequest("不回复概率需要在 0 到 1 之间");
        }
        if (values.perMinute() != null && values.perHour() != null
                && values.perMinute() > 0 && values.perHour() > 0 && values.perMinute() > values.perHour()) {
            throw ApiException.badRequest("每分钟上限不能大于每小时上限");
        }
        return values;
    }

    private void requireRange(String label, int value, int min, int max) {
        if (value < min || value > max) {
            throw ApiException.badRequest(label + "需要在 " + min + " 到 " + max + " 之间");
        }
    }
}
