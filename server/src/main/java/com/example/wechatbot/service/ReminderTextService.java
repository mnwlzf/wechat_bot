package com.example.wechatbot.service;

import com.example.wechatbot.ai.ChatMessage;
import com.example.wechatbot.ai.LlmGateway;
import com.example.wechatbot.ai.LlmReply;
import com.example.wechatbot.domain.BotConfig;
import com.example.wechatbot.domain.Conversation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 提醒文案：用当前人设写，一次写好几句，别六次提醒都是同一句话。
 *
 * <p>为什么在创建时就生成：提醒是到点自动发的，那时候没人看着，模型调用失败就没人管了。
 * 建任务时一次性生成好整轮的话术，发送时只是取第 N 句；模型挂了就退回模板，
 * 任务照样能建成。</p>
 */
@Service
public class ReminderTextService {
    private static final Logger log = LoggerFactory.getLogger(ReminderTextService.class);
    /** 一次最多写几句（次数上限的硬顶是 20，没必要真写 20 句）。 */
    private static final int MAX_LINES = 8;

    private static final String INSTRUCTION = """

            现在让你发「提醒」消息：到点提醒对方去做一件还没做的事。
            要求：
            1. 保持上面的人设、称呼和语气，像真的在催人，不是客服播报。
            2. 每条都不超过 40 个字，不要编号，不要引号，不要说「这是第几条提醒」，不要解释为什么发。
            3. 几条之间要有点变化：越来越急一点，但别骂人、别阴阳怪气。
            4. 最后一条是「放弃」的收尾：$times 次都没等到回应，你不再催了，语气平淡，不要抱怨。
            5. 只输出 JSON：{"reminders": ["第一句", "第二句"], "final": "收尾那句话"}
            """;

    private final LlmGateway llmGateway;
    private final PromptTemplateService personaService;
    private final ObjectMapper objectMapper;

    public ReminderTextService(LlmGateway llmGateway,
                               PromptTemplateService personaService,
                               ObjectMapper objectMapper) {
        this.llmGateway = llmGateway;
        this.personaService = personaService;
        this.objectMapper = objectMapper;
    }

    /**
     * @param title    提醒什么（「打卡」）
     * @param stopWord 对方回哪个词就停（「已打卡」）
     * @param times    这一轮最多催几次
     */
    public Script write(BotConfig bot, Conversation conversation, String title, String stopWord, int times) {
        int lines = Math.min(Math.max(1, times), MAX_LINES);
        try {
            PromptTemplateService.Persona persona = personaService.resolve(bot, conversation);
            String system = persona.content() + "\n" + INSTRUCTION.replace("$times", String.valueOf(times));
            String user = "要提醒的事：" + title
                    + "\n对方回「" + stopWord + "」表示已经做完"
                    + "\n写 " + lines + " 句提醒，外加 1 句收尾。";
            LlmReply reply = llmGateway.chat(bot, List.of(
                    ChatMessage.system(system), ChatMessage.user(user)));
            List<String> reminders = parse(reply.content());
            if (reminders.isEmpty()) {
                return fallback(title, stopWord, times);
            }
            String finalMessage = "算了，" + times + " 次都没回，我先不催了，你自己记得。";
            return new Script(String.join("\n", reminders), finalMessage);
        } catch (RuntimeException ex) {
            log.warn("提醒文案生成失败，退回模板: {}", ex.getMessage());
            return fallback(title, stopWord, times);
        }
    }

    /** @param variants 每行一句，按次数依次使用 */
    public record Script(String variants, String finalMessage) {}

    private List<String> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return lines(raw);
        }
        try {
            JsonNode node = objectMapper.readTree(raw.substring(start, end + 1));
            List<String> reminders = new ArrayList<>();
            for (JsonNode item : node.path("reminders")) {
                String text = item.asText("").trim();
                if (!text.isEmpty() && text.length() <= 80) {
                    reminders.add(text);
                }
            }
            return reminders.size() > MAX_LINES ? reminders.subList(0, MAX_LINES) : reminders;
        } catch (Exception ex) {
            return lines(raw);
        }
    }

    private List<String> lines(String raw) {
        return raw.lines().map(String::trim)
                .filter(line -> !line.isEmpty() && line.length() <= 80)
                .limit(MAX_LINES)
                .toList();
    }

    static Script fallback(String title, String stopWord, int times) {
        List<String> lines = new ArrayList<>(List.of(
                "该" + title + "了，回「" + stopWord + "」我就不催了。",
                "还没" + title + "吧？记得弄一下。",
                "提醒你一下：" + title + "。",
                "别忘了" + title + "，我这儿记着呢。"));
        if (times > lines.size()) {
            for (int i = lines.size(); i < Math.min(times, MAX_LINES); i++) {
                lines.add("第 " + (i + 1) + " 遍提醒：" + title + "。");
            }
        }
        List<String> picked = lines.subList(0, Math.min(Math.max(1, times), lines.size()));
        return new Script(String.join("\n", picked),
                "算了，" + times + " 次都没回，我先不催了，你自己记得。");
    }
}
