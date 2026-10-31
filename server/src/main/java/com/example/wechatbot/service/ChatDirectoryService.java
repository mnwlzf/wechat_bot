package com.example.wechatbot.service;

import com.example.wechatbot.config.WeChatHookProperties;
import com.example.wechatbot.domain.Conversation;
import com.example.wechatbot.repository.ConversationRepository;
import com.example.wechatbot.wechat.ChatIdentity;
import com.example.wechatbot.wechat.WeChatHookClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 会话目录：给管理端「会话白名单」提供按昵称 / 备注 / 群名称搜索的能力。
 *
 * <p>参考 WeChatAgent 的添加逻辑：不在会话列表里翻，而是拿微信的联系人目录
 * （{@code contact.db}）按名称搜索，再按私聊 / 群聊过滤，最后把 ID 写进白名单。
 * 三个数据源按可信度合并：</p>
 * <ol>
 *   <li>微信联系人（备注 &gt; 昵称 &gt; 微信号），名称最准；</li>
 *   <li>本地会话库（已经聊过的会话），Hook 只读查询不可用时仍能搜；</li>
 *   <li>微信会话列表（{@code session.db}），只用来补齐没有联系人的会话。</li>
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

    public ChatDirectoryService(WeChatHookProperties properties,
                                WeChatHookClient client,
                                ConversationRepository conversationRepository) {
        this.properties = properties;
        this.client = client;
        this.conversationRepository = conversationRepository;
    }

    /** 搜索结果；{@code kind} 为 PRIVATE / GROUP / OFFICIAL / SYSTEM。 */
    public record Candidate(String chatId, String name, String kind, boolean group,
                            boolean blocked, String blockedReason, String source) {}

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
        // 顺序即优先级：联系人覆盖会话名，会话名只用来补齐
        loadContacts(directory);
        loadConversations(directory);
        loadSessions(directory);

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
            if (!needle.isEmpty() && !matches(chatId, name, entry.alias(), needle)) {
                continue;
            }
            boolean blocked = identity == ChatIdentity.Kind.OFFICIAL || identity == ChatIdentity.Kind.SYSTEM;
            result.add(new Candidate(chatId, name, identity.name(), group, blocked,
                    blocked ? ChatIdentity.rejectReason(chatId) : "", entry.source()));
        }

        result.sort(Comparator
                .comparingInt((Candidate item) -> score(item, needle))
                .thenComparing(Candidate::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Candidate::chatId));
        return result.size() > safeLimit ? List.copyOf(result.subList(0, safeLimit)) : List.copyOf(result);
    }

    private static boolean matches(String chatId, String name, String alias, String needle) {
        return chatId.toLowerCase(Locale.ROOT).contains(needle)
                || name.toLowerCase(Locale.ROOT).contains(needle)
                || alias.toLowerCase(Locale.ROOT).contains(needle);
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
                if (username.isBlank()) {
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
                directory.put(username, new Entry(username, name, alias, "contact"));
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
                if (chatId == null || chatId.isBlank() || directory.containsKey(chatId)) {
                    continue;
                }
                String name = conversation.getChatName();
                directory.put(chatId, new Entry(chatId,
                        name == null || name.isBlank() ? chatId : name, "", "conversation"));
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
                    directory.put(username, new Entry(username, username, "", "session"));
                }
            }
        } catch (RuntimeException ex) {
            log.debug("读取微信会话列表失败: {}", ex.getMessage());
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

    private record Entry(String chatId, String name, String alias, String source) {}
}
