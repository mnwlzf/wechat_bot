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
    /** 「把打卡改到九点」这类改期 / 改频率的说法。 */
    private static final List<String> MODIFY_WORDS = List.of(
            "改成", "改到", "改一下", "改个时间", "换个时间", "换成", "修改", "调整", "推迟", "延后",
            "提前到", "提前半", "调成", "调晚", "调早", "多催", "少催", "催得", "催多", "催少",
            "次数改", "间隔改", "别催那么");
    /** 「删掉打卡」——删掉和停掉对用户是一回事，都会让它不再响。 */
    private static final List<String> DELETE_WORDS = List.of(
            "删掉", "删除", "删了", "去掉这个提醒", "不要这个提醒", "这个提醒不要了");
    /** 判断「改」是不是在说任务：光有「改」不算（「改天聊」天天都有）。 */
    private static final List<String> TASK_NOUNS = List.of(
            "提醒", "定时", "闹钟", "任务", "催", "时间");

    /** 预筛用的动词：和下面的名词同时出现，才值得去问模型。 */
    private static final List<String> TASK_VERBS = List.of(
            "改", "修改", "调整", "换", "调", "推迟", "提前", "延后", "删", "去掉", "取消", "停",
            "多催", "少催", "建", "设", "加个", "加一个");

    private static final String SYSTEM = """
            你把用户的一句话解析成「提醒任务」的操作。只输出 JSON，不要解释，不要 Markdown。
            字段：
            {
              "intent": "create | update | delete | list | chat",
              "target": "打卡",                                 // update/delete：指哪一条，抄用户提到的关键词；没提就 null
              "targetIndex": 2,                                 // 用户用序号指代时填（从 1 开始），否则 null
              "change": {                                        // 仅 update：只放用户明确要改的字段
                "firstAt": "2026-11-04T18:00:00+08:00",
                "everyMinutes": 10,
                "maxTimes": 3,
                "stopWords": ["已提交"],
                "title": "打卡",
                "repeat": "NONE | DAILY | WEEKLY | CRON",
                "weekdays": [1,2,3,4,5]
              },
              "title": "打卡",                                  // 仅 create
              "firstAt": "2026-11-04T18:00:00+08:00",           // 仅 create，必须带 +08:00
              "repeat": "NONE | DAILY | WEEKLY | CRON",         // 仅 create，只在用户明确说「每天/每周/工作日」时非 NONE
              "weekdays": [1,2,3,4,5],                          // 仅 create，1=周一 … 7=周日
              "reminder": {                                     // 仅 create，只在用户要求「没回就再催」时 enabled=true
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
            2. create 时，用户没说「没回就再提醒」「每 N 分钟催一次」时，reminder.enabled 必须是 false，
               这种人只是要一条定时消息，不要替他加催命循环。
            3. create 时用户没说提醒次数就留 6；没说间隔就留 5。间隔最小 5 分钟、次数最多 20，超了就按上限填。
            4. stopWords 放「表示做完了」的短词，最多 3 个（例如「已打卡」「打完了」「好了」）。
            5. title 写事情本身，不要写「提醒」两个字。
            6. 改已有任务用 update：「改成九点」「推迟到明天」「提前半小时」「换成每十分钟催一次」「多催几次」
               「少催两次」「停止词改成已提交」都算。change 里**只放用户真正要改的字段**，
               没提到的字段一律不要出现（不要写 null，也不要写默认值），否则会把原来的设置覆盖掉。
            7. 删掉任务用 delete：「删掉打卡」「这个不要了」「不用再提醒了」。用户指所有任务时 target 留 null。
            8. 完全不像在安排提醒（闲聊、问句、聊别的）时 intent 填 chat，其余留空。
            9. create 缺时间就 askBack 反问，别猜。
            """;

    private final ReminderProperties properties;
    private final ScheduledMessageService scheduled;
    private final ReminderTextService textService;
    private final LlmGateway llmGateway;
    private final ObjectMapper objectMapper;
    private final com.example.wechatbot.repository.BotConfigRepository botRepository;

    /** chatId → 追问中的原始指令（5 分钟后过期）。 */
    private final Map<String, PendingAsk> pending = new ConcurrentHashMap<>();

    /**
     * 追问状态。
     *
     * @param instruction 原始指令（补时间后重新解析用）
     * @param draft       有值时表示「多个任务重名，等对方回序号」，回一个数字就直接落到那条任务上
     */
    private record PendingAsk(String instruction, Instant expiresAt, Draft draft) {}

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
        if (containsAny(text, LIST_WORDS) || containsAny(text, STOP_WORDS) || containsAny(text, DELETE_WORDS)) {
            return true;
        }
        // 「提醒我打卡」这种没带时间的也算：缺时间本来就该反问一句
        if (containsAny(text, ACTION_WORDS)) {
            return true;
        }
        // 改期 / 改频率：光有「改」不算（「改天聊」），得带上任务名词或时间点
        if (containsAny(text, MODIFY_WORDS)
                && (containsAny(text, TASK_NOUNS) || containsAny(text, TIME_WORDS))) {
            return true;
        }
        // 「修改打卡时间」这种：动词 + 名词就够。多问模型一次的代价，比漏掉它、
        // 让模型自己编一句「改好了」小得多
        if (containsAny(text, TASK_VERBS) && containsAny(text, TASK_NOUNS)) {
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
     * 正在等对方回序号（要改 / 删哪一个）。
     *
     * <p>这时要先让序号落到任务上：否则「2」会被回执逻辑当成一句回复，
     * 顺手把正在催的那条提醒收掉——用户只是想说改第二条。</p>
     */
    public boolean awaitingChoice(String chatId) {
        PendingAsk ask = chatId == null ? null : pending.get(chatId);
        return ask != null && ask.draft() != null && ask.expiresAt().isAfter(Instant.now());
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
                pending.remove(chatId);
                // 刚才问了「要改哪个」，对方回一个序号：直接落到那条任务上，不用再过一遍模型
                Integer choice = ask.draft() == null ? null : parseChoice(text);
                if (choice != null) {
                    return Optional.of(applyChoice(bot, conversation, ask.draft(), choice));
                }
                // 否则把「下午六点」接到刚才那句指令后面，一起重新解析
                combined = ask.instruction() + " " + text;
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
        // 时间没说清 / 说的时间已经过去：先反问，别硬改也别硬建
        if (needsAskBack(draft)) {
            pending.put(chatId, new PendingAsk(combined,
                    Instant.now().plusSeconds(Math.max(30, properties.getAskBackSeconds())), null));
            return Optional.of(draft.askBack() == null || draft.askBack().isBlank()
                    ? "什么时候提醒你？说个时间点，比如「明天下午三点」。"
                    : draft.askBack().trim());
        }
        if (draft.intent() == Intent.DELETE) {
            return Optional.of(remove(conversation, draft, combined));
        }
        if (draft.intent() == Intent.UPDATE) {
            return Optional.of(update(bot, conversation, draft, combined));
        }
        return Optional.of(create(bot, conversation, draft, text));
    }

    /** 建任务要时间；改任务只在「用户提到了新时间」时要求时间可用。 */
    private static boolean needsAskBack(Draft draft) {
        if (draft.intent() == Intent.CREATE) {
            return draft.firstAt() == null || draft.askBack() != null;
        }
        return draft.askBack() != null;
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
        if (draft.intent() == Intent.CANCEL || draft.intent() == Intent.LIST
                || draft.intent() == Intent.UPDATE || draft.intent() == Intent.DELETE) {
            return new com.example.wechatbot.web.dto.AdminDtos.ReminderParseView(false, null, null, null,
                    null, null, null, null, null, null, "这是「查 / 改 / 删」的说法，请到列表里操作");
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
        // 没要求「没回就再催」的人只该收到一条：之前这里一律给 6 次，
        // 结果「明天十点提醒我开会」变成了每 5 分钟催一遍
        int maxTimes = draft.reminderEnabled() ? clampMaxTimes(draft.maxTimes()) : 1;

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
                    .append(titleOf(item))
                    .append("（")
                    .append(item.getScheduledAt().atZoneSameInstant(ZONE).format(HUMAN))
                    .append("，已催 ").append(item.getSentCount()).append("/")
                    .append(item.getMaxTimes() == null ? "-" : item.getMaxTimes()).append(" 次）\n");
        }
        text.append("要改就说「把打卡改到九点」，要删就说「删掉打卡」。");
        return text.toString().trim();
    }

    // ------------------------------------------------------------------ 改 / 删

    /**
     * 聊天里能动哪些任务：这个会话、由聊天建的、还在等回执的提醒。
     *
     * <p>后台建的任务不给动——那是运营配的，聊天对象一句「改到九点」就把它改了，
     * 出了事没人说得清是谁改的。要停可以，说「别再催了」走全局停止。</p>
     */
    private List<ScheduledMessage> chatOwned(String chatId) {
        return scheduled.waitingReminders(chatId).stream()
                .filter(item -> item.getSource() == ScheduleSource.CHAT)
                .filter(item -> item.getCreatorChatId() == null || item.getCreatorChatId().equals(chatId))
                .toList();
    }

    private String update(BotConfig bot, Conversation conversation, Draft draft, String combined) {
        List<ScheduledMessage> own = chatOwned(conversation.getChatId());
        if (own.isEmpty()) {
            return "现在没有能改的提醒。要建就说「每天六点提醒我打卡」。";
        }
        ScheduledMessage target = pick(draft.target(), draft.targetIndex(), own);
        if (target == null) {
            return askWhich(conversation.getChatId(), own, draft, combined, "改");
        }
        if (draft.change() == null || draft.change().isEmpty()) {
            // 「修改打卡时间」只说改哪项没说改成什么：记下这句，等对方补一句时间再一起解析
            pending.put(conversation.getChatId(), new PendingAsk(combined,
                    Instant.now().plusSeconds(Math.max(30, properties.getAskBackSeconds())), null));
            return "要改成什么？时间、间隔、次数、停止词都能改，比如「改成明天九点」。";
        }
        return applyUpdate(bot, conversation, target, draft);
    }

    private String remove(Conversation conversation, Draft draft, String combined) {
        List<ScheduledMessage> own = chatOwned(conversation.getChatId());
        if (own.isEmpty()) {
            return "现在没有能删的提醒。";
        }
        ScheduledMessage target = pick(draft.target(), draft.targetIndex(), own);
        if (target == null) {
            return askWhich(conversation.getChatId(), own, draft, combined, "删");
        }
        return applyRemove(target);
    }

    /** 对方回了序号：落到第 N 条任务上，按原来那句指令的意图改或删。 */
    private String applyChoice(BotConfig bot, Conversation conversation, Draft draft, int index) {
        List<ScheduledMessage> own = chatOwned(conversation.getChatId());
        if (own.isEmpty()) {
            return "现在没有在等的提醒了。";
        }
        if (index < 1 || index > own.size()) {
            return "只有 " + own.size() + " 个在等的提醒，回 1 到 " + own.size() + " 之间的数字。";
        }
        ScheduledMessage target = own.get(index - 1);
        return draft.intent() == Intent.DELETE ? applyRemove(target) : applyUpdate(bot, conversation, target, draft);
    }

    private String applyRemove(ScheduledMessage target) {
        try {
            scheduled.stop(target.getId(), "聊天里让人删掉");
        } catch (RuntimeException ex) {
            log.warn("删提醒失败: id={} err={}", target.getId(), ex.getMessage());
            return "没删成，去管理端看一下。";
        }
        return "好，「" + titleOf(target) + "」删掉了，不会再提醒你。";
    }

    private String applyUpdate(BotConfig bot, Conversation conversation, ScheduledMessage target, Draft draft) {
        Change change = draft.change();
        if (change == null || change.isEmpty()) {
            return "要改什么？时间、间隔、次数、停止词都能改，比如「把打卡改到九点」。";
        }
        String title = change.title() == null || change.title().isBlank()
                ? titleOf(target) : change.title().trim();
        List<String> stopWords = change.stopWords() == null || change.stopWords().isEmpty()
                ? null : change.stopWords();
        int maxTimes = change.maxTimes() == null
                ? clampMaxTimes(target.getMaxTimes()) : clampMaxTimes(change.maxTimes());
        String cron = cronFor(change.firstAt(), change.weekdays());
        ScheduledRepeat repeat = change.repeat();
        if (repeat == null && !cron.isEmpty()) {
            repeat = ScheduledRepeat.CRON;
        }

        String variants = null;
        String finalMessage = null;
        // 停止词 / 标题 / 次数改了就得重写文案：原来那句「回「已打卡」我就不催了」会变成假话
        if (stopWords != null || change.title() != null || change.maxTimes() != null) {
            String stopWord = stopWords == null ? firstStopWord(target) : stopWords.getFirst();
            try {
                ReminderTextService.Script script =
                        textService.write(bot, conversation, title, stopWord, maxTimes);
                variants = script.variants();
                finalMessage = properties.isFinalMessage() ? script.finalMessage() : null;
            } catch (RuntimeException ex) {
                log.warn("改提醒时重写文案失败: id={} err={}", target.getId(), ex.getMessage());
            }
        }

        ScheduledMessageService.ReminderPatch patch = new ScheduledMessageService.ReminderPatch(
                change.title(), change.firstAt(), change.everyMinutes(), change.maxTimes(),
                stopWords == null ? null : String.join(",", stopWords),
                null, variants, finalMessage, repeat, cron.isEmpty() ? null : cron, null);
        try {
            ScheduledMessage saved = scheduled.patch(target.getId(), patch);
            return readback(saved == null ? target : saved);
        } catch (ApiException ex) {
            log.info("改提醒没成: id={} reason={}", target.getId(), ex.getMessage());
            return "没改成：" + ex.getMessage();
        }
    }

    /** 改完把最终状态读一遍——对方能立刻发现「改错了」还是「没改」。 */
    private String readback(ScheduledMessage task) {
        String when = task.getScheduledAt().atZoneSameInstant(ZONE).format(HUMAN);
        ScheduledRepeat repeat = task.getRepeatRule() == null ? ScheduledRepeat.NONE : task.getRepeatRule();
        StringBuilder text = new StringBuilder("好，「").append(titleOf(task)).append("」改成 ");
        if (repeat == ScheduledRepeat.DAILY) {
            text.append("每天 ").append(task.getScheduledAt().atZoneSameInstant(ZONE).format(CLOCK)).append(" 起，");
        } else if (repeat == ScheduledRepeat.WEEKLY || repeat == ScheduledRepeat.CRON) {
            text.append(when).append(" 起按重复规则排，");
        } else {
            text.append(when).append(" 起，");
        }
        int maxTimes = clampMaxTimes(task.getMaxTimes());
        if (maxTimes <= 1) {
            return text.append("到点提醒你一次。").toString();
        }
        return text.append("每 ").append(clampInterval(task.getIntervalMinutes())).append(" 分钟催一次，最多 ")
                .append(maxTimes).append(" 次，回「").append(firstStopWord(task)).append("」我就不催了。")
                .toString();
    }

    /** 重名时让对挑：记下这条指令，对方回数字就直接落。 */
    private String askWhich(String chatId, List<ScheduledMessage> own, Draft draft,
                            String combined, String verb) {
        pending.put(chatId, new PendingAsk(combined,
                Instant.now().plusSeconds(Math.max(30, properties.getAskBackSeconds())), draft));
        StringBuilder text = new StringBuilder("你有 " + own.size() + " 个提醒在等，要" + verb + "哪个？");
        int index = 1;
        for (ScheduledMessage item : own) {
            text.append("\n").append(index++).append(". ").append(titleOf(item))
                    .append("（").append(item.getScheduledAt().atZoneSameInstant(ZONE).format(HUMAN)).append("）");
        }
        return text.append("\n回个数字就行。").toString();
    }

    /** 按序号 / 关键词定位任务；定位不到（或命中多个）返回 null，交给反问。 */
    private static ScheduledMessage pick(String target, Integer index, List<ScheduledMessage> own) {
        if (index != null && index >= 1 && index <= own.size()) {
            return own.get(index - 1);
        }
        if (target != null && !target.isBlank()) {
            String needle = normalizeTitle(target);
            List<ScheduledMessage> hits = own.stream()
                    .filter(item -> titleMatches(needle, titleOf(item)))
                    .toList();
            if (hits.size() == 1) {
                return hits.getFirst();
            }
            if (!hits.isEmpty()) {
                return null;
            }
        }
        return own.size() == 1 ? own.getFirst() : null;
    }

    private static boolean titleMatches(String needle, String title) {
        String hay = normalizeTitle(title);
        return !needle.isEmpty() && !hay.isEmpty() && (hay.contains(needle) || needle.contains(hay));
    }

    private static String normalizeTitle(String value) {
        return value == null ? "" : value.replaceAll("[\\s「」『』\"'，,。.、]", "").replace("提醒", "");
    }

    private static String titleOf(ScheduledMessage message) {
        return message.getTitle() == null || message.getTitle().isBlank()
                ? "提醒" : message.getTitle().trim();
    }

    private static String firstStopWord(ScheduledMessage message) {
        String words = message.getStopWords();
        if (words == null || words.isBlank()) {
            return "好了";
        }
        String first = words.split("[,，;；]")[0].trim();
        return first.isEmpty() ? "好了" : first;
    }

    /** 「2」「第 2 个」「第二个」都认；其余一律当普通聊天，不硬猜。 */
    static Integer parseChoice(String text) {
        if (text == null) {
            return null;
        }
        String value = text.trim().replaceAll("[\\s。！!，,.、]", "");
        if (value.isEmpty() || value.length() > 8) {
            return null;
        }
        if (value.matches("第?[0-9]{1,2}[个条号]?")) {
            try {
                return Integer.parseInt(value.replaceAll("[^0-9]", ""));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        String chinese = value.replaceAll("^第", "").replaceAll("[个条号]$", "");
        int index = List.of("一", "二", "三", "四", "五", "六", "七", "八", "九", "十").indexOf(chinese);
        return index >= 0 ? index + 1 : null;
    }

    // ------------------------------------------------------------------ 解析

    private enum Intent { CREATE, UPDATE, DELETE, CANCEL, LIST, CHAT }

    /** 改任务时只带要改的字段；null = 这一项没提到，保持原样。 */
    private record Change(OffsetDateTime firstAt, Integer everyMinutes, Integer maxTimes,
                          List<String> stopWords, String title, ScheduledRepeat repeat,
                          List<Integer> weekdays) {
        boolean isEmpty() {
            return firstAt == null && everyMinutes == null && maxTimes == null
                    && stopWords == null && title == null && repeat == null
                    && (weekdays == null || weekdays.isEmpty());
        }
    }

    private record Draft(Intent intent, String title, OffsetDateTime firstAt, ScheduledRepeat repeat,
                         List<Integer> weekdays, boolean reminderEnabled, Integer everyMinutes,
                         Integer maxTimes, List<String> stopWords, String askBack, double confidence,
                         String target, Integer targetIndex, Change change) {}

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
            case "update" -> Intent.UPDATE;
            case "delete" -> Intent.DELETE;
            case "cancel" -> Intent.CANCEL;
            case "list" -> Intent.LIST;
            default -> Intent.CHAT;
        };
        String target = node.hasNonNull("target") ? node.path("target").asText("").trim() : null;
        Integer targetIndex = node.hasNonNull("targetIndex") ? node.path("targetIndex").asInt(0) : null;
        if (targetIndex != null && targetIndex < 1) {
            targetIndex = null;
        }
        if (intent != Intent.CREATE && intent != Intent.UPDATE) {
            return new Draft(intent, null, null, ScheduledRepeat.NONE, List.of(), false,
                    null, null, List.of(), null, 0, target, targetIndex, null);
        }
        if (intent == Intent.UPDATE) {
            return updateDraft(node, target, targetIndex, now);
        }
        OffsetDateTime firstAt = parseTime(node.path("firstAt").asText(""));
        // 时间在过去（或压根没给）时不硬猜：交给追问逻辑处理
        if (firstAt != null && firstAt.isBefore(OffsetDateTime.now().minusMinutes(2))) {
            firstAt = null;
        }
        ScheduledRepeat repeat = ScheduledRepeat.parse(node.path("repeat").asText("NONE"));
        List<Integer> weekdays = weekdaysOf(node.path("weekdays"));
        JsonNode reminder = node.path("reminder");
        boolean reminderEnabled = reminder.path("enabled").asBoolean(false);
        List<String> stopWords = stopWordsOf(reminder.path("stopWords"));
        return new Draft(intent,
                node.path("title").asText(""),
                firstAt,
                repeat,
                weekdays,
                reminderEnabled,
                reminder.path("everyMinutes").asInt(0),
                reminder.path("maxTimes").asInt(0),
                stopWords,
                askBackOf(node),
                node.path("confidence").asDouble(0.8),
                target, targetIndex, null);
    }

    /** 改任务：只读 change 里真正出现过的键，没出现的保持原样。 */
    private Draft updateDraft(JsonNode node, String target, Integer targetIndex, ZonedDateTime now) {
        JsonNode changeNode = node.path("change");
        OffsetDateTime changeAt = changeNode.hasNonNull("firstAt")
                ? parseTime(changeNode.path("firstAt").asText("")) : null;
        String askBack = askBackOf(node);
        if (changeAt != null && changeAt.isBefore(OffsetDateTime.now().minusMinutes(2))) {
            // 说的时间已经过去了：不硬改，反问一句让他补个之后的时间
            changeAt = null;
            if (askBack == null || askBack.isBlank()) {
                askBack = "这个时间已经过去了，说个之后的？";
            }
        }
        List<Integer> changeDays = changeNode.has("weekdays") ? weekdaysOf(changeNode.path("weekdays")) : null;
        List<String> changeStops = changeNode.has("stopWords") ? stopWordsOf(changeNode.path("stopWords")) : null;
        Change change = new Change(
                changeAt,
                changeNode.hasNonNull("everyMinutes") ? changeNode.path("everyMinutes").asInt(0) : null,
                changeNode.hasNonNull("maxTimes") ? changeNode.path("maxTimes").asInt(0) : null,
                changeStops,
                changeNode.hasNonNull("title") ? changeNode.path("title").asText("").trim() : null,
                changeNode.hasNonNull("repeat") ? ScheduledRepeat.parse(changeNode.path("repeat").asText("")) : null,
                changeDays);
        return new Draft(Intent.UPDATE, null, null, ScheduledRepeat.NONE, List.of(), false,
                null, null, List.of(), askBack, node.path("confidence").asDouble(0.8),
                target, targetIndex, change.isEmpty() ? null : change);
    }

    private static List<Integer> weekdaysOf(JsonNode days) {
        List<Integer> values = new ArrayList<>();
        for (JsonNode day : days) {
            int value = day.asInt(0);
            if (value >= 1 && value <= 7) {
                values.add(value);
            }
        }
        return values;
    }

    private static List<String> stopWordsOf(JsonNode words) {
        List<String> values = new ArrayList<>();
        for (JsonNode word : words) {
            String text = word.asText("").trim();
            if (!text.isEmpty() && text.length() <= 12) {
                values.add(text);
            }
        }
        return values;
    }

    /** 模型没给 / 给了 null / 给了空串，都算「不用追问」。 */
    private static String askBackOf(JsonNode node) {
        if (!node.hasNonNull("askBack")) {
            return null;
        }
        String value = node.path("askBack").asText("").trim();
        return value.isEmpty() ? null : value;
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
        return cronFor(draft.firstAt(), draft.weekdays());
    }

    private static String cronFor(OffsetDateTime firstAt, List<Integer> weekdays) {
        if (weekdays == null || weekdays.isEmpty() || firstAt == null) {
            return "";
        }
        ZonedDateTime at = firstAt.atZoneSameInstant(ZONE);
        String days = weekdays.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("1-5");
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
