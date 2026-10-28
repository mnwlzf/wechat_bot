package com.example.wechatbot.service;

import com.example.wechatbot.common.ApiException;
import com.example.wechatbot.domain.BotConfig;
import com.example.wechatbot.domain.ModelProvider;
import com.example.wechatbot.repository.BotConfigRepository;
import com.example.wechatbot.repository.ModelProviderRepository;
import com.example.wechatbot.web.dto.AdminDtos;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class BotAdminService {
    private final BotConfigRepository botRepository;
    private final ModelProviderRepository providerRepository;

    public BotAdminService(BotConfigRepository botRepository, ModelProviderRepository providerRepository) {
        this.botRepository = botRepository;
        this.providerRepository = providerRepository;
    }

    public List<AdminDtos.BotView> list() {
        return botRepository.findAllByOrderByCreatedAtAsc().stream().map(this::toView).toList();
    }

    public AdminDtos.BotView update(UUID id, AdminDtos.BotUpdate request) {
        BotConfig bot = botRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("机器人配置不存在"));
        if (request.modelProviderId() != null) {
            providerRepository.findById(request.modelProviderId())
                    .orElseThrow(() -> ApiException.badRequest("所选模型提供方不存在"));
        }
        bot.setName(request.name().trim());
        bot.setEnabled(request.enabled());
        bot.setModelProviderId(request.modelProviderId());
        bot.setSystemPrompt(request.systemPrompt());
        bot.setReplyPrivate(request.replyPrivate());
        bot.setReplyGroup(request.replyGroup());
        bot.setGroupWakeWords(request.groupWakeWords() == null ? "" : request.groupWakeWords());
        bot.setContextWindow(request.contextWindow());
        bot.setTemperature(request.temperature());
        bot.setMaxTokens(request.maxTokens());
        bot.setRateLimitPerMinute(request.rateLimitPerMinute());
        return toView(botRepository.save(bot));
    }

    private AdminDtos.BotView toView(BotConfig bot) {
        String providerName = bot.getModelProviderId() == null ? null : providerRepository.findById(bot.getModelProviderId())
                .map(ModelProvider::getName)
                .orElse(null);
        return new AdminDtos.BotView(
                bot.getId(),
                bot.getName(),
                bot.isEnabled(),
                bot.getModelProviderId(),
                providerName,
                bot.getSystemPrompt(),
                bot.isReplyPrivate(),
                bot.isReplyGroup(),
                bot.getGroupWakeWords(),
                bot.getContextWindow(),
                bot.getTemperature(),
                bot.getMaxTokens(),
                bot.getRateLimitPerMinute(),
                bot.getUpdatedAt()
        );
    }
}
