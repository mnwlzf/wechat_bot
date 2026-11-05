package com.example.wechatbot.service;

import com.example.wechatbot.ai.ChatMessage;
import com.example.wechatbot.ai.LlmGateway;
import com.example.wechatbot.ai.LlmReply;
import com.example.wechatbot.config.ReminderProperties;
import com.example.wechatbot.domain.BotConfig;
import com.example.wechatbot.domain.Conversation;
import com.example.wechatbot.domain.ScheduledMessage;
import com.example.wechatbot.domain.ScheduledStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * 回执识别：对方回话了，就把等着的那条提醒收掉。
 *
 * <p>先关键词、后模型：绝大多数「已打卡」这类回应零成本命中；剩下「弄完了」「搞定了」这种
 * 说法交给模型判断，但**只在真有提醒在等的时候才调**，所以闲聊不会多花一分钱。</p>
 *
 * <p>只认已经催过的提醒（{@code sentCount > 0}）：一句普通的「好了」不该把还没到点的提醒
 * 提前毙掉。想提前取消得明说「别提醒了」，那是停止而不是回执。</p>
 */
@Service
public class ReminderAckService {
    private static final Logger log = LoggerFactory.getLogger(ReminderAckService.class);

    private static final String SYSTEM = """
            判断用户这句话是不是在回应一条提醒。只输出 JSON：{"ack": true|false, "stop": true|false}
            - ack：表示「提醒的事已经做完了」（已打卡 / 打完了 / 弄好了 / 搞定了 / 刚弄完）
            - stop：表示「不要再提醒了」（别催了 / 取消提醒 / 不用了）
            - 只是聊天、问问题、聊别的事，两个都填 false。拿不准就填 false。
            """;

    private final ReminderProperties properties;
    private final ScheduledMessageService scheduled;
    private final LlmGateway llmGateway;
    private final ObjectMapper objectMapper;

    public ReminderAckService(ReminderProperties properties,
                             ScheduledMessageService scheduled,
                             LlmGateway llmGateway,
                             ObjectMapper objectMapper) {
        this.properties = properties;
        this.scheduled = scheduled;
        this.llmGateway = llmGateway;
        this.objectMapper = objectMapper;
    }

    /**
     * @return 要回给对方的确认语；empty 表示这不是回执，按正常聊天处理
     */
    public Optional<String> handle(BotConfig bot, Conversation conversation, String content) {
        if (!properties.isEnabled() || conversation == null || content == null || content.isBlank()) {
            return Optional.empty();
        }
        String chatId = conversation.getChatId();
        List<ScheduledMessage> waiting = scheduled.waitingReminders(chatId);
        if (waiting.isEmpty()) {
            return Optional.empty();
        }
        List<ScheduledMessage> nudged = waiting.stream().filter(item -> item.getSentCount() > 0).toList();
        if (nudged.isEmpty()) {
            return Optional.empty();
        }

        String text = content.trim();
        String normalized = normalize(text);
        for (ScheduledMessage task : nudged) {
            if (matchesStopWord(task, normalized)) {
                scheduled.ack(task, text);
                return Optional.of(done(task));
            }
        }
        if (!properties.isNlAck() || bot == null) {
            return Optional.empty();
        }
        return classify(bot, nudged, text);
    }

    private Optional<String> classify(BotConfig bot, List<ScheduledMessage> nudged, String text) {
        ScheduledMessage target = nudged.stream()
                .max((a, b) -> compare(a, b))
                .orElse(nudged.getFirst());
        try {
            String user = "提醒的事：" + (target.getTitle() == null ? "这件事" : target.getTitle())
                    + "\n用户这句话：" + text;
            LlmReply reply = llmGateway.chat(bot, List.of(
                    ChatMessage.system(SYSTEM), ChatMessage.user(user)));
            JsonNode node = readJson(reply.content());
            if (node == null) {
                return Optional.empty();
            }
            if (node.path("stop").asBoolean(false)) {
                scheduled.stopWaiting(target.getChatId());
                return Optional.of("好，不提醒了。");
            }
            if (node.path("ack").asBoolean(false)) {
                scheduled.ack(target, text);
                return Optional.of(done(target));
            }
            return Optional.empty();
        } catch (RuntimeException ex) {
            log.warn("回执判断失败，按普通消息处理: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    private static int compare(ScheduledMessage a, ScheduledMessage b) {
        if (a.getSentAt() == null) {
            return b.getSentAt() == null ? 0 : -1;
        }
        return b.getSentAt() == null ? 1 : a.getSentAt().compareTo(b.getSentAt());
    }

    private static String done(ScheduledMessage task) {
        String title = task.getTitle() == null || task.getTitle().isBlank() ? "" : "「" + task.getTitle() + "」";
        String text = "好，" + title + "不催了。";
        // 每天/每周的任务下一轮还会来：把回到的那个钟点说清楚。
        // 这里读的是 ack 重排后的时间——它会回到最初要求的 18:00，而不是催出来的 18:25
        if (task.getStatus() == ScheduledStatus.PENDING && task.getScheduledAt() != null) {
            text += "下次 " + task.getScheduledAt()
                    .atZoneSameInstant(java.time.ZoneId.of("Asia/Shanghai"))
                    .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm")) + "。";
        }
        return text;
    }

    /** 停止词命中：去掉空格和标点再比，避免「已 打卡」这种分段写法漏掉。 */
    static boolean matchesStopWord(ScheduledMessage task, String normalizedContent) {
        if (normalizedContent == null || normalizedContent.isEmpty() || task.getStopWords() == null) {
            return false;
        }
        for (String raw : task.getStopWords().split("[,，;；、]")) {
            String word = normalize(raw);
            if (word.isEmpty()) {
                continue;
            }
            if (normalizedContent.contains(word)) {
                return true;
            }
        }
        return false;
    }

    static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("[\\s，。！？、,.!?~～…\"'“”‘’]", "").toLowerCase();
    }

    private JsonNode readJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            return objectMapper.readTree(raw.substring(start, end + 1));
        } catch (Exception ex) {
            return null;
        }
    }
}
