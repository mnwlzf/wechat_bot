package com.example.wechatbot.wechat;

import com.example.wechatbot.config.WeChatHookProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Component
public class WeChatHookClient {
    private static final Logger log = LoggerFactory.getLogger(WeChatHookClient.class);
    private static final String TARGET_VERSION = "4.1.10.27";
    private static final String INTEGRATION = "WeChatBot-1";
    private static final long VERIFY_CACHE_MILLIS = 10_000;
    private static final int MAX_MESSAGE_BYTES = 16_000;

    private final WeChatHookProperties properties;
    private final RestClient http;
    private final String token;
    private final Object verifyLock = new Object();

    private volatile Protocol protocol = Protocol.AUTO;
    private volatile HookHealth cachedHealth;
    private volatile long verifiedAt;

    public WeChatHookClient(WeChatHookProperties properties) {
        this.properties = properties;
        this.token = validateToken(properties.resolvedToken());
        validateBaseUrl(properties.getBaseUrl());
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(toTimeoutMillis(properties.getConnectTimeout()));
        requestFactory.setReadTimeout(toTimeoutMillis(properties.getReadTimeout()));
        this.http = RestClient.builder()
                .baseUrl(properties.getBaseUrl().replaceAll("/+$", ""))
                .requestFactory(requestFactory)
                .build();
    }

    public HookHealth verify(boolean force) {
        long now = System.currentTimeMillis();
        HookHealth cached = cachedHealth;
        if (!force && cached != null && now - verifiedAt < VERIFY_CACHE_MILLIS) {
            return cached;
        }
        synchronized (verifyLock) {
            now = System.currentTimeMillis();
            cached = cachedHealth;
            if (!force && cached != null && now - verifiedAt < VERIFY_CACHE_MILLIS) {
                return cached;
            }
            try {
                HookHealth verified = protocol == Protocol.UPSTREAM_PREBUILT
                        ? verifyUpstream()
                        : verifyOverlayOrFallback();
                cachedHealth = verified;
                verifiedAt = now;
                return verified;
            } catch (RuntimeException ex) {
                cachedHealth = null;
                verifiedAt = 0;
                if (ex instanceof WeChatHookException hookException) {
                    throw hookException;
                }
                throw new WeChatHookException("微信 Hook 校验失败: " + ex.getMessage(), ex);
            }
        }
    }

    public List<String> databases() {
        ensureProtocol();
        if (protocol == Protocol.OVERLAY) {
            List<Map<String, Object>> rows = getList("/WeChatAgent/databases");
            return databaseNames(rows);
        }
        List<Map<String, Object>> rows = postList("/QueryDB/GetAllDBName", Map.of());
        return databaseNames(rows);
    }

    public Map<String, Object> selfProfile() {
        ensureProtocol();
        return protocol == Protocol.OVERLAY
                ? getMap("/WeChatAgent/self")
                : postMap("/GetSelfProfile", Map.of());
    }

    public List<Map<String, Object>> query(String database, String sql) {
        ensureProtocol();
        Map<String, Object> response;
        if (protocol == Protocol.OVERLAY) {
            response = postMap("/WeChatAgent/query", Map.of("db", database, "sql", sql));
        } else {
            response = postMap("/QueryDB/execute", Map.of("optDbName", database, "SQL", sql));
        }
        int status = intValue(response.get("status"), -1);
        if (status != 0) {
            throw new WeChatHookException("Hook 查询失败 (" + status + "): " + stringValue(response.get("desc")));
        }
        Object data = response.get("data");
        if (!(data instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> rows = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> row = new LinkedHashMap<>();
                map.forEach((key, value) -> row.put(String.valueOf(key), value));
                rows.add(row);
            }
        }
        return rows;
    }

    public void sendText(String chatId, String content) {
        if (chatId == null || chatId.isBlank()) {
            throw new WeChatHookException("拒绝发送消息：chatId 为空");
        }
        if (content == null || content.isBlank()) {
            throw new WeChatHookException("拒绝发送空消息");
        }
        try {
            verify(false);
        } catch (WeChatHookException ex) {
            // 预编译 Hook 的 IsLogin 常为 0，但 /SendTextMsg 仍可用；只要端口可达就继续发送
            if (!reachable()) {
                throw new WeChatHookException("无法连接微信 Hook: " + properties.getBaseUrl(), ex);
            }
            log.debug("Hook 未报告登录状态，仍尝试发送: {}", ex.getMessage());
        }
        String text = truncateUtf8(content.trim(), MAX_MESSAGE_BYTES);
        Map<String, Object> response = postMap("/SendTextMsg", Map.of("wxidorgid", chatId, "msg", text));
        int ret = intValue(response.get("ret"), -1);
        if (ret != 0) {
            String error = firstNonBlank(
                    stringValue(response.get("error")),
                    stringValue(response.get("retmsg")),
                    stringValue(response.get("msg")),
                    response.toString());
            throw new WeChatHookException("微信原生发送失败: " + error);
        }
    }

    /**
     * 调用 Hook 的 {@code /Decode_Pic} 把本地加密图片（{@code .dat}）解成普通图片文件。
     *
     * <p>实测该接口固定返回 {@code ret=0} 却不落盘（4.1.10.27），图片解密因此改由
     * {@link WeChatDatDecoder} 在本地完成；这里保留调用入口，便于换 Hook 版本时验证。</p>
     */
    public void decodePicture(String srcPath, String dstPath) {
        if (srcPath == null || srcPath.isBlank() || dstPath == null || dstPath.isBlank()) {
            throw new WeChatHookException("拒绝解码图片：路径为空");
        }
        Map<String, Object> response = postMap("/Decode_Pic", Map.of("src_path", srcPath, "dst_path", dstPath));
        int ret = intValue(response.get("ret"), -1);
        if (ret != 0) {
            throw new WeChatHookException("Hook 解码图片失败: "
                    + firstNonBlank(stringValue(response.get("retmsg")), response.toString()));
        }
    }

    public Protocol protocol() {
        return protocol;
    }

    /** Raw {@code /QueryDB/status} payload; used for diagnostics and instance fingerprinting. */
    public Map<String, Object> status() {
        return getMap("/QueryDB/status");
    }

    private HookHealth verifyOverlayOrFallback() {
        Map<String, Object> health;
        try {
            health = getMap("/WeChatAgent/health");
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 404) {
                protocol = Protocol.UPSTREAM_PREBUILT;
                return verifyUpstream();
            }
            throw new WeChatHookException("Hook 健康接口返回 HTTP " + ex.getStatusCode().value(), ex);
        } catch (ResourceAccessException ex) {
            throw new WeChatHookException("无法连接微信 Hook: " + properties.getBaseUrl(), ex);
        }

        if (!"wechat-hook".equals(stringValue(health.get("backend")))
                || !INTEGRATION.equals(stringValue(health.get("integration")))) {
            throw new WeChatHookException("不兼容的 Hook 构建，需要 WeChatBot-1 覆盖层或 4.1.10.27 预编译版");
        }
        String version = stringValue(health.get("version"));
        if (!TARGET_VERSION.equals(version)) {
            throw new WeChatHookException("Hook 微信版本不匹配: " + version + "，需要 " + TARGET_VERSION);
        }
        if (!"ready".equals(stringValue(health.get("startupState")))) {
            throw new WeChatHookException("Hook 尚未就绪: " + stringValue(health.get("startupState")));
        }
        if (!booleanValue(health.get("nativeQuery"))) {
            throw new WeChatHookException("Hook 未开放原生只读查询接口");
        }

        List<String> accounts = stringList(health.get("databaseAccounts"));
        String expectedAccount = normalize(properties.getAccount());
        if (!expectedAccount.isEmpty()) {
            List<String> normalized = accounts.stream().map(this::normalize).toList();
            if (!normalized.contains(expectedAccount)) {
                throw new WeChatHookException("Hook 账号不匹配，报告为 " + normalized + "，配置为 " + expectedAccount);
            }
        }
        if (accounts.size() > 1) {
            throw new WeChatHookException("检测到多个微信账号目录，拒绝自动操作: " + accounts);
        }

        Map<String, Object> profile = getMap("/WeChatAgent/self");
        String account = firstNonBlank(stringValue(profile.get("wxid")), accounts.isEmpty() ? "" : accounts.getFirst());
        if (account.isBlank()) {
            throw new WeChatHookException("无法识别当前登录的微信账号");
        }
        protocol = Protocol.OVERLAY;
        return new HookHealth(true, "OVERLAY", version, account,
                stringValue(health.get("startupState")), accounts, Instant.now());
    }

    private HookHealth verifyUpstream() {
        Map<String, Object> status;
        try {
            status = getMap("/QueryDB/status");
        } catch (RestClientResponseException ex) {
            throw new WeChatHookException("预编译 Hook 状态接口返回 HTTP " + ex.getStatusCode().value(), ex);
        } catch (ResourceAccessException ex) {
            throw new WeChatHookException("无法连接微信 Hook: " + properties.getBaseUrl(), ex);
        }
        if (!booleanValue(status.get("IsLogin"))) {
            throw new WeChatHookException("微信尚未登录或 Hook 尚未完成初始化");
        }
        long moduleBase = longValue(status.get("hWeixin"), 0);
        if (moduleBase == 0) {
            throw new WeChatHookException("预编译 Hook 未加载微信模块");
        }

        Map<String, Object> profile = postMap("/GetSelfProfile", Map.of());
        String account = firstNonBlank(stringValue(profile.get("wxid")), properties.getAccount());
        if (account.isBlank()) {
            throw new WeChatHookException("无法识别当前登录的微信账号");
        }
        protocol = Protocol.UPSTREAM_PREBUILT;
        return new HookHealth(true, "UPSTREAM_PREBUILT", TARGET_VERSION, account,
                "ready", List.of(account), Instant.now());
    }

    private void ensureProtocol() {
        if (protocol == Protocol.AUTO) {
            verify(false);
        }
    }

    private boolean reachable() {
        try {
            getMap("/QueryDB/status");
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private List<String> databaseNames(List<Map<String, Object>> rows) {
        return rows.stream()
                .map(row -> stringValue(row.get("dbName")))
                .filter(name -> !name.isBlank())
                .distinct()
                .toList();
    }

    private Map<String, Object> getMap(String path) {
        Map<String, Object> result = http.get().uri(path).headers(this::applyHeaders).retrieve().body(mapType());
        return result == null ? Map.of() : result;
    }

    private Map<String, Object> postMap(String path, Map<String, Object> payload) {
        Map<String, Object> result = http.post().uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .headers(this::applyHeaders)
                .body(payload)
                .retrieve()
                .body(mapType());
        return result == null ? Map.of() : result;
    }

    private List<Map<String, Object>> getList(String path) {
        List<Map<String, Object>> result = http.get().uri(path).headers(this::applyHeaders).retrieve().body(listType());
        return result == null ? List.of() : result;
    }

    private List<Map<String, Object>> postList(String path, Map<String, Object> payload) {
        List<Map<String, Object>> result = http.post().uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .headers(this::applyHeaders)
                .body(payload)
                .retrieve()
                .body(listType());
        return result == null ? List.of() : result;
    }

    private ParameterizedTypeReference<Map<String, Object>> mapType() {
        return new ParameterizedTypeReference<>() {};
    }

    private ParameterizedTypeReference<List<Map<String, Object>>> listType() {
        return new ParameterizedTypeReference<>() {};
    }

    private void applyHeaders(HttpHeaders headers) {
        if (!token.isBlank()) {
            headers.setBearerAuth(token);
        }
        String account = properties.getAccount();
        if (account != null && !account.isBlank()) {
            headers.set("X-WeChatAgent-Account", account.trim());
        }
    }

    private static void validateBaseUrl(String baseUrl) {
        try {
            URI uri = URI.create(Objects.requireNonNullElse(baseUrl, ""));
            String host = uri.getHost();
            boolean loopback = "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host)
                    || "::1".equals(host) || "[::1]".equals(host);
            if (!"http".equalsIgnoreCase(uri.getScheme()) || !loopback || uri.getPort() <= 0) {
                throw new WeChatHookException("Hook URL 必须是 http://127.0.0.1:<port> 回环地址");
            }
            if ((uri.getPath() != null && !uri.getPath().isBlank() && !"/".equals(uri.getPath()))
                    || uri.getQuery() != null || uri.getFragment() != null) {
                throw new WeChatHookException("Hook URL 不允许包含路径、查询参数或片段");
            }
        } catch (IllegalArgumentException ex) {
            throw new WeChatHookException("Hook URL 格式无效: " + baseUrl, ex);
        }
    }

    private static String validateToken(String value) {
        String token = value == null ? "" : value.trim();
        if (!token.isEmpty() && !token.matches("[a-fA-F0-9]{64}")) {
            throw new WeChatHookException("Hook token 必须是 64 位十六进制字符串");
        }
        return token;
    }

    private static int toTimeoutMillis(java.time.Duration duration) {
        if (duration == null || duration.isNegative() || duration.isZero()) {
            return 5000;
        }
        return (int) Math.min(Integer.MAX_VALUE, duration.toMillis());
    }

    private static String truncateUtf8(String value, int maxBytes) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return value;
        }
        int end = maxBytes;
        while (end > 0 && (bytes[end] & 0xC0) == 0x80) {
            end--;
        }
        return new String(bytes, 0, end, StandardCharsets.UTF_8);
    }

    static String sha256Short(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(40);
            for (byte b : Arrays.copyOf(digest, 20)) {
                builder.append(String.format(Locale.ROOT, "%02x", b));
            }
            return builder.toString();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
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

    private static int intValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(stringValue(value));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static long longValue(Object value, long fallback) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(stringValue(value));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        return "true".equalsIgnoreCase(stringValue(value)) || "1".equals(stringValue(value))
                || "yes".equalsIgnoreCase(stringValue(value));
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        return values.stream().map(String::valueOf).toList();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    public enum Protocol {
        AUTO,
        OVERLAY,
        UPSTREAM_PREBUILT
    }

    public record HookHealth(
            boolean connected,
            String protocol,
            String version,
            String account,
            String startupState,
            List<String> databaseAccounts,
            Instant checkedAt
    ) {}
}
