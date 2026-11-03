package com.example.wechatbot.service;

import com.example.wechatbot.common.ApiException;
import com.example.wechatbot.domain.BotConfig;
import com.example.wechatbot.domain.ReplyMode;
import com.example.wechatbot.domain.ReplyPolicy;
import com.example.wechatbot.repository.ReplyPolicyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 回复策略：决定「这个群到底要不要回」——主动回复 / 随机回复 / 关键词唤醒。
 *
 * <p>合并顺序（逐项覆盖，空值沿用下一层）：</p>
 * <ol>
 *   <li>机器人配置：{@code group_wake_words} 为空 → 主动回复，否则 → 关键词唤醒；</li>
 *   <li>默认策略（{@code chat_id IS NULL}）；</li>
 *   <li>该群的策略（{@code chat_id = 当前会话}）。</li>
 * </ol>
 *
 * <p>只作用于群聊：私聊保持「收到就回」，除非专门给那个会话加了一条策略。
 * 每次判定都直接查库（两条带索引的小查询），换来的是改完立即生效、不需要缓存失效逻辑。</p>
 */
@Service
public class ReplyPolicyService {
    private static final Logger log = LoggerFactory.getLogger(ReplyPolicyService.class);

    /** 切到随机回复但没填概率时用的默认命中率。 */
    public static final double DEFAULT_RANDOM_RATE = 0.3;
    /** 拟人模式下两次判定的默认最小间隔（秒）：群里连发几条时只问一次模型。 */
    public static final int DEFAULT_HUMAN_COOLDOWN_SECONDS = 60;
    /** 唤醒词字段长度上限，与 bot_config.group_wake_words 保持一致。 */
    public static final int MAX_WAKE_WORDS = 500;

    private final ReplyPolicyRepository repository;

    public ReplyPolicyService(ReplyPolicyRepository repository) {
        this.repository = repository;
    }

    /** 页面提交的可编辑字段；null 表示沿用下一层。 */
    public record Values(ReplyMode mode, String wakeWords, Double randomRate,
                         Integer humanCooldownSeconds, Boolean enabled) {}

    /**
     * 合并后的生效策略。
     *
     * @param source   mode 等字段最终来自哪一层：{@code bot} / {@code default} / {@code chat}
     * @param inherited 该群自己没配过策略（完全走默认）
     */
    public record Effective(ReplyMode mode, String wakeWords, double randomRate, int humanCooldownSeconds,
                            boolean inherited, String source) {}

    public Effective forChat(String chatId, BotConfig bot) {
        Effective effective = base(bot);
        Optional<ReplyPolicy> defaultRow = repository.findByChatIdIsNull().filter(ReplyPolicy::isEnabled);
        if (defaultRow.isPresent()) {
            effective = apply(effective, defaultRow.get(), "default", true);
        }
        if (chatId == null || chatId.isBlank()) {
            return effective;
        }
        Effective merged = effective;
        return repository.findByChatId(chatId.trim())
                .filter(ReplyPolicy::isEnabled)
                .map(row -> apply(merged, row, "chat", false))
                .orElse(merged);
    }

    public Optional<ReplyPolicy> defaultPolicy() {
        return repository.findByChatIdIsNull();
    }

    public Optional<ReplyPolicy> override(String chatId) {
        return chatId == null || chatId.isBlank()
                ? Optional.empty()
                : repository.findByChatId(chatId.trim());
    }

    public List<ReplyPolicy> overrides() {
        return repository.findAllByChatIdIsNotNullOrderByUpdatedAtDesc();
    }

    @Transactional
    public ReplyPolicy saveDefault(Values values) {
        ReplyPolicy policy = repository.findByChatIdIsNull().orElseGet(ReplyPolicy::new);
        policy.setChatId(null);
        policy.setChatName("");
        policy.setEnabled(true);
        apply(policy, validate(values));
        ReplyPolicy saved = repository.save(policy);
        log.info("回复策略：默认策略已更新（mode={}）", saved.getMode());
        return saved;
    }

    @Transactional
    public ReplyPolicy saveForChat(String chatId, String chatName, Values values) {
        if (chatId == null || chatId.isBlank()) {
            throw ApiException.badRequest("请选择要配置的群");
        }
        String key = chatId.trim();
        ReplyPolicy policy = repository.findByChatId(key).orElseGet(ReplyPolicy::new);
        policy.setChatId(key);
        policy.setChatName(chatName == null ? "" : chatName.trim());
        policy.setEnabled(values == null || values.enabled() == null || values.enabled());
        apply(policy, validate(values));
        ReplyPolicy saved = repository.save(policy);
        log.info("回复策略：{} 已更新（mode={}）", key, saved.getMode());
        return saved;
    }

    /** 删除按群策略，该群立即回退到默认策略。 */
    @Transactional
    public void deleteForChat(String chatId) {
        String key = chatId == null ? "" : chatId.trim();
        ReplyPolicy policy = repository.findByChatId(key)
                .orElseThrow(() -> ApiException.notFound("该群没有自定义回复策略"));
        repository.delete(policy);
        log.info("回复策略：{} 已恢复默认", key);
    }

    /** 机器人配置这一层的基线：唤醒词为空就等于「每条都回」。 */
    private Effective base(BotConfig bot) {
        String words = bot == null || bot.getGroupWakeWords() == null ? "" : bot.getGroupWakeWords().trim();
        ReplyMode mode = words.isEmpty() ? ReplyMode.ALWAYS : ReplyMode.WAKE;
        return new Effective(mode, words, DEFAULT_RANDOM_RATE, DEFAULT_HUMAN_COOLDOWN_SECONDS, true, "bot");
    }

    private Effective apply(Effective base, ReplyPolicy row, String source, boolean inherited) {
        ReplyMode mode = row.getMode() == null ? base.mode() : row.getMode();
        String words = row.getWakeWords() == null ? base.wakeWords() : row.getWakeWords().trim();
        double rate = row.getRandomRate() == null ? base.randomRate() : row.getRandomRate();
        int cooldown = row.getHumanCooldownSeconds() == null
                ? base.humanCooldownSeconds() : row.getHumanCooldownSeconds();
        return new Effective(mode, words, rate, cooldown, inherited, source);
    }

    /** 只写非空字段，null 表示「这项继续沿用下一层」。 */
    private void apply(ReplyPolicy target, Values values) {
        if (values.mode() != null) {
            target.setMode(values.mode());
        }
        if (values.wakeWords() != null) {
            target.setWakeWords(values.wakeWords().trim());
        }
        if (values.randomRate() != null) {
            target.setRandomRate(values.randomRate());
        }
        if (values.humanCooldownSeconds() != null) {
            target.setHumanCooldownSeconds(values.humanCooldownSeconds());
        }
    }

    private Values validate(Values values) {
        if (values == null) {
            throw ApiException.badRequest("回复策略内容不能为空");
        }
        if (values.wakeWords() != null) {
            String words = values.wakeWords().trim();
            if (words.length() > MAX_WAKE_WORDS) {
                throw ApiException.badRequest("唤醒词最多 " + MAX_WAKE_WORDS + " 个字符");
            }
            if (words.isEmpty() && values.mode() == ReplyMode.WAKE) {
                throw ApiException.badRequest("关键词唤醒模式下需要至少填一个唤醒词");
            }
        }
        if (values.randomRate() != null && (values.randomRate() < 0 || values.randomRate() > 1)) {
            throw ApiException.badRequest("随机命中概率需要在 0 到 1 之间");
        }
        if (values.humanCooldownSeconds() != null
                && (values.humanCooldownSeconds() < 0 || values.humanCooldownSeconds() > 3600)) {
            throw ApiException.badRequest("拟人模式的判定间隔需要在 0 到 3600 秒之间");
        }
        return values;
    }
}
