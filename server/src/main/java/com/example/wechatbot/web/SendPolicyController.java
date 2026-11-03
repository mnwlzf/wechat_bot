package com.example.wechatbot.web;

import com.example.wechatbot.domain.SendPolicy;
import com.example.wechatbot.service.SendPolicyService;
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

/**
 * 发信策略：全局默认策略 + 按群覆盖。
 *
 * <p>没配过的会话直接用默认策略，页面上显示为「继承默认」；
 * 配过之后该群按自己的参数发，删掉即回退默认。</p>
 */
@RestController
@RequestMapping("/api/admin/send-policies")
public class SendPolicyController {

    private final SendPolicyService service;

    public SendPolicyController(SendPolicyService service) {
        this.service = service;
    }

    @GetMapping
    public AdminDtos.SendPolicyOverview overview() {
        List<AdminDtos.SendPolicyView> overrides = service.overrides().stream()
                .map(this::view)
                .toList();
        return new AdminDtos.SendPolicyOverview(
                effective(service.effective(null)),
                service.defaultPolicy().map(this::view).orElse(null),
                overrides);
    }

    /** 某个会话的生效策略 + 它自己的覆盖行（没配过则为 null）。 */
    @GetMapping("/{chatId}")
    public AdminDtos.ChatSendPolicy chat(@PathVariable String chatId) {
        return new AdminDtos.ChatSendPolicy(
                effective(service.effective(chatId)),
                service.override(chatId).map(this::view).orElse(null));
    }

    @PutMapping("/default")
    public AdminDtos.SendPolicyEffective saveDefault(@RequestBody AdminDtos.SendPolicyInput input) {
        service.saveDefault(values(input));
        return effective(service.effective(null));
    }

    @PutMapping("/{chatId}")
    public AdminDtos.SendPolicyView saveChat(@PathVariable String chatId,
                                             @RequestParam(required = false) String chatName,
                                             @RequestBody AdminDtos.SendPolicyInput input) {
        return view(service.saveForChat(chatId, chatName, values(input)));
    }

    /** 删除按群策略，该群立即回退到默认策略。 */
    @DeleteMapping("/{chatId}")
    public void delete(@PathVariable String chatId) {
        service.deleteForChat(chatId);
    }

    private SendPolicyService.Values values(AdminDtos.SendPolicyInput input) {
        return new SendPolicyService.Values(
                input.readDelayMinMs(), input.readDelayMaxMs(), input.maxDelayMs(),
                input.splitEnabled(), input.maxParts(), input.maxPartChars(),
                input.perMinute(), input.per10Minutes(), input.perHour(), input.perDay(),
                input.minGapMs(), input.emojiLimit(), input.dropRate(), input.promptEnabled(),
                input.enabled());
    }

    private AdminDtos.SendPolicyView view(SendPolicy policy) {
        return new AdminDtos.SendPolicyView(
                policy.getId(), policy.getChatId(), policy.getChatName(),
                policy.isDefaultPolicy(), policy.isEnabled(),
                policy.getReadDelayMinMs(), policy.getReadDelayMaxMs(), policy.getMaxDelayMs(),
                policy.getSplitEnabled(), policy.getMaxParts(), policy.getMaxPartChars(),
                policy.getPerMinute(), policy.getPer10Minutes(), policy.getPerHour(), policy.getPerDay(),
                policy.getMinGapMs(), policy.getEmojiLimit(), policy.getDropRate(), policy.getPromptEnabled(),
                policy.getUpdatedAt());
    }

    private AdminDtos.SendPolicyEffective effective(SendPolicyService.Effective value) {
        return new AdminDtos.SendPolicyEffective(
                value.readDelayMinMs(), value.readDelayMaxMs(), value.maxDelayMs(),
                value.splitEnabled(), value.maxParts(), value.maxPartChars(),
                value.perMinute(), value.per10Minutes(), value.perHour(), value.perDay(),
                value.minGapMs(), value.emojiLimit(), value.dropRate(), value.promptEnabled(),
                value.inherited());
    }
}
