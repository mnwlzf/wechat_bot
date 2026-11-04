package com.example.wechatbot.service;

import com.example.wechatbot.config.MemoryProperties;
import com.example.wechatbot.domain.BotConfig;
import com.example.wechatbot.domain.Conversation;
import com.example.wechatbot.repository.BotConfigRepository;
import com.example.wechatbot.repository.ConversationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * 给「聊完就静下来的会话」补摘要。
 *
 * <p>摘要原来只在回复之后触发，而一段对话聊完就没人再触发了：上午聊过的事会一直卡在
 * 「已经滚出上下文窗口、又还没进摘要」的中间地带，下午对方回来问一句，机器人就答不上来。
 * 这个任务每隔几分钟把安静的会话挑出来压一遍，等于在两次对话之间把记忆补上。</p>
 *
 * <p>成本：只有真的存在窗口外未压缩消息时才会调模型（其余都是几次索引查询），
 * 每轮最多看 {@code sweepBatch} 个会话，聊得最久的先处理。</p>
 */
@Component
public class MemorySweepJob {
    private static final Logger log = LoggerFactory.getLogger(MemorySweepJob.class);
    /** 太老的历史不再补摘要：省一次模型调用，也没什么人还会翻回去问。 */
    private static final int LOOKBACK_HOURS = 48;

    private final ConversationRepository conversationRepository;
    private final BotConfigRepository botRepository;
    private final ChatMemoryService memoryService;
    private final MemoryProperties properties;

    public MemorySweepJob(ConversationRepository conversationRepository,
                          BotConfigRepository botRepository,
                          ChatMemoryService memoryService,
                          MemoryProperties properties) {
        this.conversationRepository = conversationRepository;
        this.botRepository = botRepository;
        this.memoryService = memoryService;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${app.memory.sweep-interval-ms:300000}", initialDelay = 90_000)
    public void sweep() {
        try {
            sweepOnce();
        } catch (Exception ex) {
            log.warn("补记忆摘要失败: {}", ex.getMessage());
        }
    }

    /** 扫一轮，返回处理了几个会话（成功或失败都算），方便测试与观察。 */
    public int sweepOnce() {
        if (!properties.isEnabled() || !properties.isSummaryEnabled() || properties.getIdleMinutes() <= 0) {
            return 0;
        }
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime idleBefore = now.minusMinutes(properties.getIdleMinutes());
        OffsetDateTime horizon = now.minusHours(LOOKBACK_HOURS);
        int batch = Math.max(1, properties.getSweepBatch());
        List<Conversation> candidates = conversationRepository
                .findByLastMessageAtBeforeAndLastMessageAtAfterOrderByLastMessageAtDesc(
                        idleBefore, horizon, PageRequest.of(0, batch));
        if (candidates.isEmpty()) {
            return 0;
        }
        Map<UUID, BotConfig> bots = StreamSupport
                .stream(botRepository.findAllById(
                        candidates.stream().map(Conversation::getBotId).distinct().toList()).spliterator(), false)
                .filter(BotConfig::isEnabled)
                .collect(Collectors.toMap(BotConfig::getId, Function.identity()));
        int handled = 0;
        for (Conversation conversation : candidates) {
            BotConfig bot = bots.get(conversation.getBotId());
            if (bot == null) {
                continue;
            }
            try {
                if (memoryService.summarizeIfIdle(bot, conversation)) {
                    handled++;
                }
            } catch (Exception ex) {
                // 一个会话失败不能影响别的会话
                log.warn("补记忆摘要失败: chat={} err={}", conversation.getChatId(), ex.getMessage());
            }
        }
        if (handled > 0) {
            log.debug("补记忆摘要：本轮处理 {} 个安静会话", handled);
        }
        return handled;
    }
}
