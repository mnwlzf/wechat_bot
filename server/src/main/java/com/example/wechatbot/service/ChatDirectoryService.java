package com.example.wechatbot.service;

import com.example.wechatbot.config.WeChatHookProperties;
import com.example.wechatbot.domain.ChatAlias;
import com.example.wechatbot.domain.Conversation;
import com.example.wechatbot.repository.ChatAliasRepository;
import com.example.wechatbot.repository.ConversationRepository;
import com.example.wechatbot.wechat.ChatIdentity;
import com.example.wechatbot.wechat.HookCallbackBuffer;
import com.example.wechatbot.wechat.WeChatHookClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 会话目录：给管理端「会话白名单」提供按昵称 / 备注 / 群名称搜索的能力。
 *
 * <p>参考 WeChatAgent 的添加逻辑：不在会话列表里翻，而是拿联系人目录按名称搜，再按私聊 / 群聊过滤，
 * 最后把 ID 写进白名单。数据源按可信度合并，缺一个不影响其它：</p>
 * <ol>
 *   <li>本机会话备注（{@code chat_alias}）：唯一在微信数据库读不出来时也能用的名字来源；</li>
 *   <li>外部联系人读取服务（可选，{@code WECHAT_READER_URL}）：对齐 WeChatAgent 的
 *       {@code GET /api/v1/contacts?kind=&keyword=&limit=}，装了 WeFlow 一类 reader 就有真实昵称；</li>
 *   <li>微信联系人（{@code contact.db}，备注 &gt; 昵称 &gt; 微信号）：需要 Hook 的 QueryDB 可用；</li>
 *   <li>本地会话库（聊过的会话，带最后一条消息，方便认出是哪个群）；</li>
 *   <li>最近的 Hook 回调（连没回复的群 / 公众号也算，方便把群加进白名单）。</li>
 * </ol>
 *
 * <p>公众号、机器人、系统会话仍然会出现在结果里，但标记为不可添加，
 * 避免“搜到了顺手加进白名单、结果还是收不到回复”的困惑。</p>
 */
@Service
public class ChatDirectoryService {
    private static final Logger log = LoggerFactory.getLogger(ChatDirectoryService.class);
    private static final int MAX_CONTACTS = 5_000;
    private static final int MAX_CONVERSATIONS = 500;

    private final WeChatHookProperties properties;
    private final WeChatHookClient client;
    private final ConversationRepository conversationRepository;
    private final ChatAliasRepository aliasRepository;
    private final HookCallbackBuffer callbackBuffer;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    public ChatDirectoryService(WeChatHookProperties properties,
                                WeChatHookClient client,
                                ConversationRepository conversationRepository,
                                ChatAliasRepository aliasRepository,
                                HookCallbackBuffer callbackBuffer,
                                ObjectMapper objectMapper) {
        this.properties = properties;
        this.client = client;
        this.conversationRepository = conversationRepository;
        this.aliasRepository = aliasRepository;
        this.callbackBuffer = callbackBuffer;
        this.objectMapper = objectMapper;
    }

    /** 搜索结果；{@code kind} 为 PRIVATE / GROUP / OFFICIAL / SYSTEM。 */
    public record Candidate(String chatId, String name, String kind, boolean group,
                            boolean blocked, String blockedReason, String source,
                            String lastMessage, OffsetDateTime lastActiveAt) {}

    /**
     * @param kind    {@code private} 只看私聊，{@code groups} 只看群聊，其它值返回全部
     * @param keyword 昵称、备注、微信号或群名称片段；空串表示不过滤
     * @param limit   最多返回条数
     */
    public List<Candidate> search(String kind, String keyword, int limit) {
        String normalizedKind = kind == null ? "" : kind.trim().toLowerCase(Locale.ROOT);
        String needle = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        int safeLimit = Math.max(1, Math.min(limit, 500));

        Map<String, Entry> directory = new LinkedHashMap<>();
        // 顺序即优先级：备注 > 外部 reader > 微信联系人 > 本地会话 > 最近回调
        loadAliases(directory);
        loadReader(directory);
        loadContacts(directory);
        loadConversations(directory);
        loadSessions(directory);
        loadRecentlySeen(directory);

        String self = selfWxid();
        List<Candidate> result = new ArrayList<>();
        for (Entry entry : directory.values()) {
            String chatId = entry.chatId();
            if (chatId.equalsIgnoreCase(self)) {
                continue;
            }
            ChatIdentity.Kind identity = ChatIdentity.kindOf(chatId);
            boolean group = identity == ChatIdentity.Kind.GROUP;
            if ("private".equals(normalizedKind) && identity != ChatIdentity.Kind.PRIVATE) {
                continue;
            }
            if ("groups".equals(normalizedKind) && !group) {
                continue;
            }
            String name = entry.name().isBlank() ? chatId : entry.name();
            // 没有名字的会话（只有 wxid / 群 ID）允许按最近一条消息搜，方便把群认出来
            String searchableMessage = name.equals(chatId) ? entry.lastMessage() : "";
            if (!needle.isEmpty() && !matches(chatId, name, entry.alias(), searchableMessage, needle)) {
                continue;
            }
            boolean blocked = identity == ChatIdentity.Kind.OFFICIAL || identity == ChatIdentity.Kind.SYSTEM;
            result.add(new Candidate(chatId, name, identity.name(), group, blocked,
                    blocked ? ChatIdentity.rejectReason(chatId) : "", entry.source(),
                    entry.lastMessage(), entry.lastActiveAt()));
        }

        result.sort(Comparator
                .comparingInt((Candidate item) -> score(item, needle))
                .thenComparing(Candidate::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Candidate::chatId));
        return result.size() > safeLimit ? List.copyOf(result.subList(0, safeLimit)) : List.copyOf(result);
    }

    /** 保存（或清除，名称留空时）会话备注。 */
    @Transactional
    public String saveAlias(String chatId, String name) {
        String id = chatId == null ? "" : chatId.trim();
        if (id.isEmpty()) {
            throw new IllegalArgumentException("会话 ID 不能为空");
        }
        String display = name == null ? "" : name.trim();
        if (display.isEmpty()) {
            aliasRepository.deleteById(id);
            return "";
        }
        String clipped = display.length() > 180 ? display.substring(0, 180) : display;
        aliasRepository.save(new ChatAlias(id, clipped, OffsetDateTime.now()));
        return clipped;
    }

    private static boolean matches(String chatId, String name, String alias, String lastMessage, String needle) {
        return chatId.toLowerCase(Locale.ROOT).contains(needle)
                || name.toLowerCase(Locale.ROOT).contains(needle)
                || alias.toLowerCase(Locale.ROOT).contains(needle)
                || lastMessage.toLowerCase(Locale.ROOT).contains(needle);
    }

    /** 名称 / ID 与关键词完全匹配的排前面，其次是前缀匹配。 */
    private static int score(Candidate item, String needle) {
        if (needle.isEmpty()) {
            return 0;
        }
        String name = item.name().toLowerCase(Locale.ROOT);
        String id = item.chatId().toLowerCase(Locale.ROOT);
        if (name.equals(needle) || id.equals(needle)) {
            return 0;
        }
        if (name.startsWith(needle) || id.startsWith(needle)) {
            return 1;
        }
        return 2;
    }

    private void loadAliases(Map<String, Entry> directory) {
        try {
            for (ChatAlias alias : aliasRepository.findAll()) {
                String chatId = alias.getChatId();
                if (chatId == null || chatId.isBlank()) {
                    continue;
                }
                directory.put(chatId, new Entry(chatId, alias.getDisplayName(), alias.getDisplayName(),
                        "alias", null, alias.getUpdatedAt()));
            }
        } catch (RuntimeException ex) {
            log.debug("读取会话备注失败: {}", ex.getMessage());
        }
    }

    /**
     * 可选的外部联系人读取服务，契约对齐 WeChatAgent 的 WeFlow reader：
     * {@code GET {url}/api/v1/contacts?kind=private|groups&limit=500} →
     * {@code {"contacts":[{"username":..,"remark":..,"displayName":..,"nickname":..}]}}。
     */
    private void loadReader(Map<String, Entry> directory) {
        String baseUrl = properties.getReaderUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            return;
        }
        String root = baseUrl.trim().replaceAll("/+$", "");
        for (String kind : List.of("private", "groups")) {
            try {
                String url = root + "/api/v1/contacts?kind=" + kind + "&keyword=&limit=500";
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(4))
                        .GET()
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() / 100 != 2) {
                    log.debug("联系人读取服务返回 {}: {}", response.statusCode(), url);
                    continue;
                }
                JsonNode rootNode = objectMapper.readTree(response.body());
                JsonNode rows = rootNode.path("contacts");
                if (!rows.isArray()) {
                    continue;
                }
                for (JsonNode row : rows) {
                    String username = text(row, "username");
                    if (username.isBlank() || directory.containsKey(username)) {
                        continue;
                    }
                    String name = firstNonBlank(
                            text(row, "remark"),
                            text(row, "displayName"),
                            text(row, "nickname"),
                            username);
                    directory.put(username, new Entry(username, name, text(row, "nickname"), "reader", null, null));
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception ex) {
                log.debug("联系人读取服务不可用（{}）: {}", root, ex.getMessage());
                return;
            }
        }
    }

    private static String text(JsonNode row, String field) {
        JsonNode value = row.get(field);
        return value == null || value.isNull() ? "" : value.asText("").trim();
    }

    private void loadContacts(Map<String, Entry> directory) {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            String database = findDatabase("contact.db");
            if (database.isBlank()) {
                return;
            }
            List<String> columns = columns(database, "contact");
            List<String> selected = new ArrayList<>();
            for (String candidate : List.of("username", "remark", "nick_name", "alias")) {
                if (columns.contains(candidate)) {
                    selected.add(candidate);
                }
            }
            if (!selected.contains("username")) {
                return;
            }
            List<Map<String, Object>> rows = client.query(database,
                    "SELECT " + quoted(selected) + " FROM \"contact\" LIMIT " + MAX_CONTACTS);
            for (Map<String, Object> row : rows) {
                String username = stringValue(valueIgnoreCase(row, "username"));
                if (username.isBlank() || directory.containsKey(username)) {
                    continue;
                }
                String name = firstNonBlank(
                        stringValue(valueIgnoreCase(row, "remark")),
                        stringValue(valueIgnoreCase(row, "nick_name")),
                        stringValue(valueIgnoreCase(row, "alias")),
                        username);
                String alias = firstNonBlank(
                        stringValue(valueIgnoreCase(row, "alias")),
                        stringValue(valueIgnoreCase(row, "nick_name")));
                directory.put(username, new Entry(username, name, alias, "contact", null, null));
            }
        } catch (RuntimeException ex) {
            // 预编译 Hook 的 QueryDB 常不可用，联系人目录只是“更好的名称来源”，失败就退回本地会话库
            log.debug("读取微信联系人失败，改为只用本地会话库: {}", ex.getMessage());
        }
    }

    private void loadConversations(Map<String, Entry> directory) {
        try {
            List<Conversation> rows = conversationRepository
                    .findAllByOrderByLastMessageAtDesc(PageRequest.of(0, MAX_CONVERSATIONS))
                    .getContent();
            for (Conversation conversation : rows) {
                String chatId = conversation.getChatId();
                if (chatId == null || chatId.isBlank()) {
                    continue;
                }
                String lastMessage = conversation.getLastMessage() == null ? "" : conversation.getLastMessage().trim();
                Entry existing = directory.get(chatId);
                if (existing != null) {
                    directory.put(chatId, existing.withActivity(lastMessage, conversation.getLastMessageAt()));
                    continue;
                }
                String name = conversation.getChatName();
                directory.put(chatId, new Entry(chatId,
                        name == null || name.isBlank() ? chatId : name, "", "conversation",
                        lastMessage, conversation.getLastMessageAt()));
            }
        } catch (RuntimeException ex) {
            log.debug("读取本地会话库失败: {}", ex.getMessage());
        }
    }

    private void loadSessions(Map<String, Entry> directory) {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            String database = findDatabase("session.db");
            if (database.isBlank()) {
                return;
            }
            List<String> columns = columns(database, "session");
            if (!columns.contains("username")) {
                return;
            }
            List<Map<String, Object>> rows = client.query(database,
                    "SELECT \"username\" FROM \"session\" LIMIT " + MAX_CONTACTS);
            for (Map<String, Object> row : rows) {
                String username = stringValue(valueIgnoreCase(row, "username"));
                if (!username.isBlank() && !directory.containsKey(username)) {
                    directory.put(username, new Entry(username, username, "", "session", null, null));
                }
            }
        } catch (RuntimeException ex) {
            log.debug("读取微信会话列表失败: {}", ex.getMessage());
        }
    }

    /** 最近回调过的会话：没进白名单、被拒的群和公众号也在这里，方便把群加进来。 */
    private void loadRecentlySeen(Map<String, Entry> directory) {
        for (Map<String, Object> item : callbackBuffer.recent()) {
            String chatId = stringValue(item.get("chatId"));
            if (chatId.isBlank() || directory.containsKey(chatId)) {
                continue;
            }
            Object receivedAt = item.get("receivedAt");
            OffsetDateTime at = receivedAt instanceof OffsetDateTime time ? time : null;
            String lastMessage = "";
            if (item.get("payload") instanceof Map<?, ?> payload && payload.get("content") instanceof String content) {
                lastMessage = content.trim();
            }
            directory.put(chatId, new Entry(chatId, chatId, "", "hook", lastMessage, at));
        }
    }

    private String selfWxid() {
        String configured = properties.getSelfWxid();
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        if (!properties.isEnabled()) {
            return "";
        }
        try {
            return client.verify(false).account();
        } catch (RuntimeException ex) {
            return "";
        }
    }

    private String findDatabase(String expected) {
        try {
            return client.databases().stream()
                    .filter(name -> expected.equalsIgnoreCase(name))
                    .findFirst()
                    .orElse("");
        } catch (RuntimeException ex) {
            return "";
        }
    }

    private List<String> columns(String database, String table) {
        return client.query(database, "PRAGMA TABLE_INFO(\"" + table + "\")").stream()
                .map(row -> stringValue(valueIgnoreCase(row, "name")).toLowerCase(Locale.ROOT))
                .filter(name -> !name.isBlank())
                .toList();
    }

    private static Object valueIgnoreCase(Map<String, Object> row, String key) {
        if (row.containsKey(key)) {
            return row.get(key);
        }
        return row.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(key))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    private static String quoted(List<String> names) {
        return names.stream().map(name -> "\"" + name + "\"").collect(java.util.stream.Collectors.joining(", "));
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private record Entry(String chatId, String name, String alias, String source,
                         String lastMessage, OffsetDateTime lastActiveAt) {
        Entry withActivity(String message, OffsetDateTime at) {
            return new Entry(chatId, name, alias, source,
                    message == null || message.isBlank() ? lastMessage : message,
                    at == null ? lastActiveAt : at);
        }
    }
}
