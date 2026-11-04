package com.example.wechatbot.service;

import com.example.wechatbot.ai.ChatMessage;
import com.example.wechatbot.ai.LlmGateway;
import com.example.wechatbot.ai.LlmReply;
import com.example.wechatbot.common.ApiException;
import com.example.wechatbot.config.ReminderProperties;
import com.example.wechatbot.domain.BotConfig;
import com.example.wechatbot.domain.Conversation;
import com.example.wechatbot.domain.ScheduleSource;
import com.example.wechatbot.domain.ScheduledMessage;
import com.example.wechatbot.domain.ScheduledRepeat;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 聊天里用一句话建 / 停 / 查提醒。
 *
 * <p>两道闸门，先便宜后昂贵：</p>
 * <ol>
 *   <li>本地预筛（0 token）：句子里得有人称 + 时间/提醒动词的组合，否则直接当普通聊天
 *       ——「记得早点睡」这种句子每天都有，不该每次都去问模型；</li>
 *   <li>模型解析成 JSON，再由后端二次校验（时间在过去就顺延、间隔抬到下限、次数夹到上限），
 *       不确定就反问一句，绝不硬造任务。</li>
 * </ol>
 *
 * <p>只服务私聊：群里谁都能说话，提醒会变成对所有人的广播，也判不清该谁回执。</p>
 */
@Service
public class ReminderIntentService {
    private static final Logger log = LoggerFactory.getLogger(ReminderIntentService.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter HUMAN = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

    /** 明确的动作词：这些出现时基本可以确定是在让人干活。 */
    private static final List<String> ACTION_WORDS = List.of(
            "提醒我", "提醒一下我", "叫我", "叫醒", "催我", "别让我忘", "别忘了", "记得提醒", "给我提醒");
    /** 时间线索：有动作词 + 时间线索才值得问模型。 */
    private static final List<String> TIME_WORDS = List.of(
            "点", "明天", "后天", "今天", "今晚", "早上", "上午", "中午", "下午", "晚上", "凌晨", "每天", "每周",
            "工作日", "周末", "分钟后", "小时后", "半小时", "闹钟");
    private static final List<String> LIST_WORDS = List.of(
            "有哪些提醒", "我的提醒", "提醒列表", "有什么提醒", "还有哪些提醒", "查一下提醒", "看看提醒");
    private static final List<String> STOP_WORDS = List.of(
            "别提醒", "不要提醒", "取消提醒", "不用提醒", "停掉提醒", "停止提醒", "把提醒停", "提醒停掉", "别再催");

    private static final String SYSTEM = """
            你把用户的一句话解析成「提醒任务」。只输出 JSON，不要解释，不要 Markdown。
            字段：
            {
              "intent": "create | cancel | list | chat",
              "title": "打卡",                                  // 提醒什么，不超过 12 字
              "firstAt": "2026-11-04T18:00:00+08:00",           // 第一次提醒的绝对时间，必须带 +08:00
              "repeat": "NONE | DAILY | WEEKLY | CRON",         // 只在用户明确说「每天/每周/工作日」时非 NONE
              "weekdays": [1,2,3,4,5],                          // 仅 WEEKLY/工作日：1=周一 … 7=周日
              "reminder": {                                     // 只在用户要求「没回就再催」时 enabled=true
                "enabled": false,
                "everyMinutes": 5,
                "maxTimes": 6,
                "stopWords": ["已打卡"]
              },
              "askBack": "几点提醒你？",                          // 缺关键信息时给一句反问，否则 null
              "confidence": 0.9
            }
            规则：
            1. 时间必须换算成绝对时间。用户说「六点」且现在已过六点，就按明天算；说「明天」就是明天的同一时刻；
               「半小时后」「两小时后」按相对时间算。
            2. 用户没说「没回就再提醒」「每 N 分钟催一次」时，reminder.enabled 必须是 false，
               这种人只是要一条定时消息，不要替他加催命循环。
            3. 用户没说提醒次数就留 6；没说间隔就留 5。间隔最小 5 分钟、次数最多 20，超了就按上限填。
            4. stopWords 放「表示做完了」的短词，最多 3 个（例如「已打卡」「打完了」「好了」）。
            5. title 写事情本身，不要写「提醒」两个字。
            6. 完全不像在安排提醒（闲聊、问句、聊别的）时 intent 填 chat，其余留空。
            7. 缺时间就 askBack 反问，别猜。
            """;

    private final ReminderProperties properties;
    private final ScheduledMessageService scheduled;
    private final ReminderTextService textService;
    private final LlmGateway llmGateway;
    private final ObjectMapper objectMapper;
    private final com.example.wechatbot.repository.BotConfigRepository botRepository;

    /** chatId → 追问中的原始指令（5 分钟后过期）。 */
    private final Map<String, PendingAsk> pending = new ConcurrentHashMap<>();

    private record PendingAsk(String instruction, Instant expiresAt) {}

    public ReminderIntentService(ReminderProperties properties,
                                 ScheduledMessageService scheduled,
                                 ReminderTextService textService,
                                 LlmGateway llmGateway,
                                 ObjectMapper objectMapper,
                                 com.example.wechatbot.repository.BotConfigRepository botRepository) {
        this.properties = properties;
        this.scheduled = scheduled;
        this.textService = textService;
        this.llmGateway = llmGateway;
        this.objectMapper = objectMapper;
        this.botRepository = botRepository;
    }

    public boolean enabled() {
        return properties.isEnabled() && properties.isCreateInChat();
    }

    /** 0 成本的预筛：命中才值得去问模型。 */
    public boolean mightBeInstruction(String content) {
        if (content == null || content.isBlank()) {
            return false;
        }
        String text = content.trim();
        if (text.length() > 200) {
            return false;
        }
        if (containsAny(text, LIST_WORDS) || containsAny(text, STOP_WORDS)) {
            return true;
        }
        // 「提醒我打卡」这种没带时间的也算：缺时间本来就该反问一句
        if (containsAny(text, ACTION_WORDS)) {
            return true;
        }
        return text.contains("提醒") && containsAny(text, TIME_WORDS);
    }

    /** 有没有在等这个人补充信息（追问状态）。 */
    public boolean hasPendingAsk(String chatId) {
        PendingAsk ask = chatId == null ? null : pending.get(chatId);
        return ask != null && ask.expiresAt().isAfter(Instant.now());
    }

    /**
     * 处理一条来消息。
     *
     * @return 要回给对方的文本；empty 表示这不是提醒指令，按正常聊天走
     */
    public Optional<String> handle(BotConfig bot, Conversation conversation, String content) {
        if (!enabled() || bot == null || conversation == null || content == null || content.isBlank()) {
            return Optional.empty();
        }
        String chatId = conversation.getChatId();
        String text = content.trim();
        String combined = text;

        PendingAsk ask = pending.get(chatId);
        if (ask != null) {
            if (ask.expiresAt().isBefore(Instant.now())) {
                pending.remove(chatId);
            } else {
                // 把「下午六点」接到刚才那句指令后面，一起重新解析
                combined = ask.instruction() + " " + text;
                pending.remove(chatId);
            }
        } else if (!mightBeInstruction(text)) {
            return Optional.empty();
        }

        if (isStopIntent(combined)) {
            return Optional.of(stop(chatId, combined));
        }
        if (isListIntent(combined)) {
            return Optional.of(list(chatId));
        }

        Draft draft;
        try {
            draft = parse(bot, combined);
        } catch (RuntimeException ex) {
            log.warn("提醒指令解析失败: chat={} err={}", chatId, ex.getMessage());
            return Optional.empty();
        }
        if (draft == null || draft.intent() == Intent.CHAT) {
            return Optional.empty();
        }
        if (draft.intent() == Intent.CANCEL) {
            return Optional.of(stop(chatId, combined));
        }
        if (draft.intent() == Intent.LIST) {
            return Optional.of(list(chatId));
        }
        if (draft.firstAt() == null || draft.askBack() != null) {
            pending.put(chatId, new PendingAsk(combined,
                    Instant.now().plusSeconds(Math.max(30, properties.getAskBackSeconds()))));
            return Optional.of(draft.askBack() == null || draft.askBack().isBlank()
                    ? "什么时候提醒你？说个时间点，比如「明天下午三点」。"
                    : draft.askBack().trim());
        }
        return Optional.of(create(bot, conversation, draft, text));
    }

    /**
     * 管理端「一句话创建」：只解析成草案，不落库。
     *
     * <p>解析结果直接填进表单，人确认后才真正保存——误解析的成本因此是零。</p>
     */
    public com.example.wechatbot.web.dto.AdminDtos.ReminderParseView preview(UUID botId, String instruction) {
        if (instruction == null || instruction.isBlank()) {
            return new com.example.wechatbot.web.dto.AdminDtos.ReminderParseView(false, null, null, null,
                    null, null, null, null, null, null, "先写一句话，比如「每天下午六点提醒我打卡」");
        }
        if (!properties.isEnabled()) {
            return new com.example.wechatbot.web.dto.AdminDtos.ReminderParseView(false, null, null, null,
                    null, null, null, null, null, null, "提醒功能已关闭");
        }
        BotConfig bot = botId == null
                ? botRepository.findFirstByEnabledTrueOrderByCreatedAtAsc().orElse(null)
                : botRepository.findById(botId).orElse(null);
        if (bot == null) {
            return new com.example.wechatbot.web.dto.AdminDtos.ReminderParseView(false, null, null, null,
                    null, null, null, null, null, null, "没有可用的机器人配置");
        }
        Draft draft;
        try {
            draft = parse(bot, instruction.trim());
        } catch (RuntimeException ex) {
            log.warn("解析失败: {}", ex.getMessage());
            return new com.example.wechatbot.web.dto.AdminDtos.ReminderParseView(false, null, null, null,
                    null, null, null, null, null, null, "模型没返回结果，检查一下模型配置");
        }
        if (draft == null || draft.intent() == Intent.CHAT) {
            return new com.example.wechatbot.web.dto.AdminDtos.ReminderParseView(false, null, null, null,
                    null, null, null, null, null, null, "这句话不像在安排提醒，手动填表单吧");
        }
        if (draft.intent() == Intent.CANCEL || draft.intent() == Intent.LIST) {
            return new com.example.wechatbot.web.dto.AdminDtos.ReminderParseView(false, null, null, null,
                    null, null, null, null, null, null, "这是「查 / 停」的说法，请到列表里操作");
        }
        if (draft.firstAt() == null) {
            return new com.example.wechatbot.web.dto.AdminDtos.ReminderParseView(false, null, draft.title(),
                    null, draft.repeat() == null ? "NONE" : draft.repeat().name(),
                    clampInterval(draft.everyMinutes()), clampMaxTimes(draft.maxTimes()),
                    String.join(",", draft.stopWords()), null, null,
                    draft.askBack() == null || draft.askBack().isBlank()
                            ? "没说时间：补一个时间点，比如「明天下午三点」"
                            : draft.askBack());
        }
        String title = draft.title() == null || draft.title().isBlank() ? "这件事" : draft.title().trim();
        String stopWord = draft.stopWords() == null || draft.stopWords().isEmpty()
                ? "好了" : draft.stopWords().getFirst();
        int maxTimes = clampMaxTimes(draft.maxTimes());
        String variants = null;
        String finalMessage = null;
        String kind = "MESSAGE";
        if (draft.reminderEnabled()) {
            kind = "REMINDER";
            ReminderTextService.Script script = textService.write(bot, null, title, stopWord, maxTimes);
            variants = script.variants();
            finalMessage = properties.isFinalMessage() ? script.finalMessage() : null;
        }
        String cron = cronFor(draft);
        return new com.example.wechatbot.web.dto.AdminDtos.ReminderParseView(true, kind, title,
                draft.firstAt(),
                cron.isEmpty() ? (draft.repeat() == null ? "NONE" : draft.repeat().name()) : "CRON",
                draft.reminderEnabled() ? clampInterval(draft.everyMinutes()) : null,
                draft.reminderEnabled() ? maxTimes : null,
                String.join(",", draft.stopWords().isEmpty() ? List.of(stopWord) : draft.stopWords()),
                variants, finalMessage, "解析完成，请确认后保存");
    }

    /** 建任务并把关键字段回读一遍——解析错了对方能立刻发现。 */
    private String create(BotConfig bot, Conversation conversation, Draft draft, String raw) {
        String title = draft.title() == null || draft.title().isBlank() ? "这件事" : draft.title().trim();
        String stopWord = draft.stopWords() == null || draft.stopWords().isEmpty()
                ? "好了" : draft.stopWords().getFirst();
        int interval = clampInterval(draft.everyMinutes());
        int maxTimes = clampMaxTimes(draft.maxTimes());

        ScheduledRepeat repeat = draft.repeat() == null ? ScheduledRepeat.NONE : draft.repeat();
        String cron = cronFor(draft);
        if (!cron.isEmpty()) {
            repeat = ScheduledRepeat.CRON;
        }
        boolean reminderMode = draft.reminderEnabled();

        String variants = null;
        String finalMessage = null;
        String content = null;
        if (reminderMode) {
            ReminderTextService.Script script =
                    textService.write(bot, conversation, title, stopWord, maxTimes);
            variants = script.variants();
            finalMessage = properties.isFinalMessage() ? script.finalMessage() : null;
            content = variants == null ? null : variants.lines().findFirst().orElse(null);
        }

        ScheduledMessageService.ReminderSpec spec = new ScheduledMessageService.ReminderSpec(
                title, draft.firstAt(), interval, maxTimes,
                String.join(",", draft.stopWords() == null || draft.stopWords().isEmpty()
                        ? List.of(stopWord) : draft.stopWords()),
                content, variants, finalMessage, repeat, cron.isEmpty() ? null : cron, null,
                ScheduleSource.CHAT, conversation.getChatId(), raw);
        try {
            ScheduledMessage task = scheduled.createReminder(bot.getId(), conversation.getChatId(),
                    conversation.getChatName(), spec);
            return confirm(task, draft, reminderMode, interval, maxTimes, stopWord);
        } catch (ApiException ex) {
            log.info("提醒没建成: chat={} reason={}", conversation.getChatId(), ex.getMessage());
            return "这个提醒没建成：" + ex.getMessage();
        }
    }

    private String confirm(ScheduledMessage task, Draft draft, boolean reminderMode,
                          int interval, int maxTimes, String stopWord) {
        String when = task.getScheduledAt().atZoneSameInstant(ZONE).format(HUMAN);
        StringBuilder text = new StringBuilder("好，");
        ScheduledRepeat repeat = task.getRepeatRule() == null ? ScheduledRepeat.NONE : task.getRepeatRule();
        if (repeat == ScheduledRepeat.DAILY) {
            text.append("每天 ").append(task.getScheduledAt().atZoneSameInstant(ZONE).format(CLOCK)).append(" 起，");
        } else if (repeat == ScheduledRepeat.WEEKLY || repeat == ScheduledRepeat.CRON) {
            text.append("按").append(weekdayText(draft)).append(" ").append(when).append(" 起，");
        } else {
            text.append(when).append(" 起，");
        }
        if (reminderMode) {
            text.append("每 ").append(interval).append(" 分钟催一次，最多 ").append(maxTimes)
                    .append(" 次，回「").append(stopWord).append("」我就不催了。");
        } else {
            text.append("到点提醒你「").append(task.getTitle()).append("」。");
        }
        return text.toString();
    }

    private String stop(String chatId, String content) {
        List<ScheduledMessage> waiting = scheduled.waitingReminders(chatId);
        if (waiting.isEmpty()) {
            return "现在没有在等的提醒。";
        }
        List<ScheduledMessage> targets = waiting.stream()
                .filter(item -> item.getTitle() != null && !item.getTitle().isBlank()
                        && content.contains(item.getTitle().trim()))
                .toList();
        if (targets.isEmpty()) {
            targets = waiting;
        }
        int stopped = 0;
        for (ScheduledMessage item : targets) {
            try {
                scheduled.stop(item.getId(), "聊天里让人设停掉");
                stopped++;
            } catch (RuntimeException ex) {
                log.warn("停止提醒失败: id={} err={}", item.getId(), ex.getMessage());
            }
        }
        String names = targets.stream()
                .map(item -> item.getTitle() == null || item.getTitle().isBlank() ? "提醒" : item.getTitle())
                .distinct().reduce((a, b) -> a + "、" + b).orElse("提醒");
        return stopped == 0 ? "没能停掉，去管理端看一下。" : "好，「" + names + "」不提醒了。";
    }

    private String list(String chatId) {
        List<ScheduledMessage> waiting = scheduled.waitingReminders(chatId);
        if (waiting.isEmpty()) {
            return "现在没有在等的提醒。要建就说「每天六点提醒我打卡」这样。";
        }
        StringBuilder text = new StringBuilder("现在在等你的提醒：\n");
        int index = 1;
        for (ScheduledMessage item : waiting) {
            text.append(index++).append(". ")
                    .append(item.getTitle() == null || item.getTitle().isBlank() ? "提醒" : item.getTitle())
                    .append("（")
                    .append(item.getScheduledAt().atZoneSameInstant(ZONE).format(HUMAN))
                    .append("，已催 ").append(item.getSentCount()).append("/")
                    .append(item.getMaxTimes() == null ? "-" : item.getMaxTimes()).append(" 次）\n");
        }
        return text.toString().trim();
    }

    // ------------------------------------------------------------------ 解析

    private enum Intent { CREATE, CANCEL, LIST, CHAT }

    private record Draft(Intent intent, String title, OffsetDateTime firstAt, ScheduledRepeat repeat,
                         List<Integer> weekdays, boolean reminderEnabled, Integer everyMinutes,
                         Integer maxTimes, List<String> stopWords, String askBack, double confidence) {}

    private Draft parse(BotConfig bot, String instruction) {
        ZonedDateTime now = ZonedDateTime.now(ZONE);
        String user = "现在是 " + now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) + " "
                + weekdayName(now.getDayOfWeek().getValue()) + "（Asia/Shanghai）。\n"
                + "用户说：" + instruction;
        LlmReply reply = llmGateway.chat(bot, List.of(
                ChatMessage.system(SYSTEM), ChatMessage.user(user)));
        return toDraft(reply.content(), now);
    }

    Draft toDraft(String raw, ZonedDateTime now) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(raw.substring(start, end + 1));
        } catch (Exception ex) {
            return null;
        }
        Intent intent = switch (node.path("intent").asText("chat").toLowerCase()) {
            case "create" -> Intent.CREATE;
            case "cancel" -> Intent.CANCEL;
            case "list" -> Intent.LIST;
            default -> Intent.CHAT;
        };
        if (intent != Intent.CREATE) {
            return new Draft(intent, null, null, ScheduledRepeat.NONE, List.of(), false,
                    null, null, List.of(), null, 0);
        }
        OffsetDateTime firstAt = parseTime(node.path("firstAt").asText(""));
        // 时间在过去（或压根没给）时不硬猜：交给追问逻辑处理
        if (firstAt != null && firstAt.isBefore(OffsetDateTime.now().minusMinutes(2))) {
            firstAt = null;
        }
        ScheduledRepeat repeat = ScheduledRepeat.parse(node.path("repeat").asText("NONE"));
        List<Integer> weekdays = new ArrayList<>();
        for (JsonNode day : node.path("weekdays")) {
            int value = day.asInt(0);
            if (value >= 1 && value <= 7) {
                weekdays.add(value);
            }
        }
        JsonNode reminder = node.path("reminder");
        boolean reminderEnabled = reminder.path("enabled").asBoolean(false);
        List<String> stopWords = new ArrayList<>();
        for (JsonNode word : reminder.path("stopWords")) {
            String text = word.asText("").trim();
            if (!text.isEmpty() && text.length() <= 12) {
                stopWords.add(text);
            }
        }
        return new Draft(intent,
                node.path("title").asText(""),
                firstAt,
                repeat,
                weekdays,
                reminderEnabled,
                reminder.path("everyMinutes").asInt(0),
                reminder.path("maxTimes").asInt(0),
                stopWords,
                node.path("askBack").isNull() ? null : node.path("askBack").asText(""),
                node.path("confidence").asDouble(0.8));
    }

    private static OffsetDateTime parseTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value.trim());
        } catch (RuntimeException ex) {
            try {
                return ZonedDateTime.parse(value.trim()).toOffsetDateTime();
            } catch (RuntimeException ignored) {
                return null;
            }
        }
    }

    /** 工作日/指定星期 → cron；其余情况返回空串。 */
    private static String cronFor(Draft draft) {
        if (draft.weekdays() == null || draft.weekdays().isEmpty() || draft.firstAt() == null) {
            return "";
        }
        ZonedDateTime at = draft.firstAt().atZoneSameInstant(ZONE);
        String days = draft.weekdays().stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("1-5");
        return at.getSecond() + " " + at.getMinute() + " " + at.getHour() + " * * " + days;
    }

    private int clampInterval(int minutes) {
        int floor = Math.max(1, properties.getMinIntervalMinutes());
        int value = minutes <= 0 ? properties.getDefaultIntervalMinutes() : minutes;
        return Math.min(Math.max(value, floor), 24 * 60);
    }

    private int clampMaxTimes(int times) {
        int hard = Math.max(1, properties.getHardMaxTimes());
        int value = times <= 0 ? properties.getDefaultMaxTimes() : times;
        return Math.min(Math.max(value, 1), hard);
    }

    private static String weekdayText(Draft draft) {
        List<Integer> days = draft.weekdays();
        if (days == null || days.isEmpty()) {
            return "每周";
        }
        if (days.size() == 5 && days.containsAll(List.of(1, 2, 3, 4, 5))) {
            return "每个工作日";
        }
        return "每周" + days.stream().map(ReminderIntentService::weekdayName)
                .reduce((a, b) -> a + "、" + b).orElse("一");
    }

    private static String weekdayName(int isoDay) {
        return switch (isoDay) {
            case 1 -> "周一";
            case 2 -> "周二";
            case 3 -> "周三";
            case 4 -> "周四";
            case 5 -> "周五";
            case 6 -> "周六";
            default -> "周日";
        };
    }

    private static boolean containsAny(String text, List<String> words) {
        for (String word : words) {
            if (text.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isStopIntent(String text) {
        return containsAny(text, STOP_WORDS);
    }

    private static boolean isListIntent(String text) {
        return containsAny(text, LIST_WORDS);
    }
}
