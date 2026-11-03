package com.example.wechatbot.web;

import com.example.wechatbot.common.ApiException;
import com.example.wechatbot.domain.BotConfig;
import com.example.wechatbot.domain.ReplyMode;
import com.example.wechatbot.domain.ReplyPolicy;
import com.example.wechatbot.repository.BotConfigRepository;
import com.example.wechatbot.service.ReplyPolicyService;
import com.example.wechatbot.web.dto.AdminDtos;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;

/**
 * 回复策略：主动回复 / 随机回复 / 关键词唤醒，全局默认 + 按群覆盖。
 *
 * <p>没配过的群直接用默认策略（默认策略又沿用机器人配置里的唤醒词），
 * 配过之后该群按自己的模式判断，删掉即回退默认。</p>
 */
@RestController
@RequestMapping("/api/admin/reply-policies")
public class ReplyPolicyController {

    private final ReplyPolicyService service;
    private final BotConfigRepository botRepository;

    public ReplyPolicyController(ReplyPolicyService service, BotConfigRepository botRepository) {
        this.service = service;
        this.botRepository = botRepository;
    }

    @GetMapping
    public AdminDtos.ReplyPolicyOverview overview() {
        BotConfig bot = currentBot();
        List<AdminDtos.ReplyPolicyView> overrides = service.overrides().stream()
                .map(this::view)
                .toList();
        return new AdminDtos.ReplyPolicyOverview(
                effective(service.forChat(null, bot)),
                service.defaultPolicy().map(this::view).orElse(null),
                overrides,
                bot == null ? "" : bot.getGroupWakeWords());
    }

    /** 某个群的生效回复策略 + 它自己的覆盖行（没配过则为 null）。 */
    @GetMapping("/{chatId}")
    public AdminDtos.ChatReplyPolicy chat(@PathVariable String chatId) {
        return new AdminDtos.ChatReplyPolicy(
                effective(service.forChat(chatId, currentBot())),
                service.override(chatId).map(this::view).orElse(null));
    }

    @PutMapping("/default")
    public AdminDtos.ReplyPolicyEffective saveDefault(@RequestBody AdminDtos.ReplyPolicyInput input) {
        service.saveDefault(values(input));
        return effective(service.forChat(null, currentBot()));
    }

    @PutMapping("/{chatId}")
    public AdminDtos.ReplyPolicyView saveChat(@PathVariable String chatId,
                                              @RequestParam(required = false) String chatName,
                                              @RequestBody AdminDtos.ReplyPolicyInput input) {
        return view(service.saveForChat(chatId, chatName, values(input)));
    }

    /** 删除按群策略，该群立即回退到默认策略。 */
    @DeleteMapping("/{chatId}")
    public void delete(@PathVariable String chatId) {
        service.deleteForChat(chatId);
    }

    private BotConfig currentBot() {
        return botRepository.findFirstByEnabledTrueOrderByCreatedAtAsc().orElse(null);
    }

    private ReplyPolicyService.Values values(AdminDtos.ReplyPolicyInput input) {
        return new ReplyPolicyService.Values(
                mode(input.mode()), input.wakeWords(), input.randomRate(),
                input.humanCooldownSeconds(), input.enabled());
    }

    /** 模式用字符串收，非法值给一句人话，而不是让 Jackson 抛出难以理解的 400。 */
    private ReplyMode mode(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return ReplyMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest("回复模式只能是 ALWAYS（主动回复）/ RANDOM（随机回复）/ WAKE（关键词唤醒）");
        }
    }

    private AdminDtos.ReplyPolicyView view(ReplyPolicy policy) {
        return new AdminDtos.ReplyPolicyView(
                policy.getId(), policy.getChatId(), policy.getChatName(),
                policy.isDefaultPolicy(), policy.isEnabled(),
                policy.getMode() == null ? null : policy.getMode().name(),
                policy.getWakeWords(), policy.getRandomRate(), policy.getHumanCooldownSeconds(),
                policy.getUpdatedAt());
    }

    private AdminDtos.ReplyPolicyEffective effective(ReplyPolicyService.Effective value) {
        return new AdminDtos.ReplyPolicyEffective(
                value.mode().name(), value.wakeWords(), value.randomRate(), value.humanCooldownSeconds(),
                value.source(), value.inherited());
    }
}
