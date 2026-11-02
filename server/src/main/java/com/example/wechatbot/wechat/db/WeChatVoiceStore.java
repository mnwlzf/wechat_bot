package com.example.wechatbot.wechat.db;

import com.example.wechatbot.config.WeChatDbProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 从微信 4.x 的 {@code media_*.db} 里按消息取语音字节。
 *
 * <p>微信收到的语音不存在磁盘文件里：报文只有时长和一段加密块，音频本身在
 * {@code db_storage/message/media_*.db} 的 {@code VoiceInfo.voice_data}（SILK）。这里的
 * 匹配键是 {@code svr_id}——它等于回调报文里的 {@code msgid}，实测一一对应；没有
 * {@code svr_id} 的老消息退回按 {@code create_time} 在时间窗内找。</p>
 */
@Component
public class WeChatVoiceStore {
    private static final Logger log = LoggerFactory.getLogger(WeChatVoiceStore.class);
    private static final int MAX_CACHED_FILES = 8;
    private static final int MAX_SILK_BYTES = 2 * 1024 * 1024;

    private final WeChatDbProperties properties;
    private final Map<String, Cached> cache = new HashMap<>();

    public WeChatVoiceStore(WeChatDbProperties properties) {
        this.properties = properties;
    }

    private record Cached(long size, long modified, SqlCipherFile file, SqliteReader reader) {}

    /** @param bytes SILK 原始字节（可能带前导 0x02） */
    public record Voice(byte[] bytes, String shard) {}

    public boolean enabled() {
        return properties.isConfigured();
    }

    /** 按 {@code svr_id}（= 回调 msgid）取语音。 */
    public Optional<Voice> findBySvrId(long svrId) {
        if (!enabled() || svrId <= 0) {
            return Optional.empty();
        }
        return search("svr_id", svrId, null, 0);
    }

    /** 没有 svr_id 的老消息：按消息时间在时间窗内找最近的一条。 */
    public Optional<Voice> findByCreateTime(long epochSeconds, int windowSeconds) {
        if (!enabled() || epochSeconds <= 0) {
            return Optional.empty();
        }
        return search("create_time", epochSeconds, epochSeconds, Math.max(1, windowSeconds));
    }

    private Optional<Voice> search(String column, long value, Long epochSeconds, int windowSeconds) {
        for (Path shard : shards()) {
            try {
                SqliteReader reader = readerOf(shard);
                if (reader == null) {
                    continue;
                }
                List<String> columns = reader.columns("VoiceInfo");
                if (columns.isEmpty()) {
                    continue;
                }
                int keyIndex = SqliteReader.indexOf(columns, column);
                int dataIndex = SqliteReader.indexOf(columns, "voice_data");
                int timeIndex = SqliteReader.indexOf(columns, "create_time");
                if (keyIndex < 0 || dataIndex < 0) {
                    continue;
                }
                byte[] best = null;
                long bestDistance = Long.MAX_VALUE;
                for (Object[] row : reader.rows("VoiceInfo")) {
                    Object data = keyIndex < row.length ? row[keyIndex] : null;
                    if (!(data instanceof Long candidate)) {
                        continue;
                    }
                    if (windowSeconds <= 0) {
                        if (candidate != value) {
                            continue;
                        }
                    } else {
                        long distance = Math.abs(candidate - value);
                        if (distance > windowSeconds) {
                            continue;
                        }
                        if (timeIndex >= 0 && epochSeconds != null && timeIndex < row.length
                                && row[timeIndex] instanceof Long createTime) {
                            distance = Math.min(distance, Math.abs(createTime - epochSeconds));
                        }
                        if (distance >= bestDistance) {
                            continue;
                        }
                        bestDistance = distance;
                    }
                    Object blob = dataIndex < row.length ? row[dataIndex] : null;
                    if (blob instanceof byte[] bytes && bytes.length > 0 && bytes.length <= MAX_SILK_BYTES) {
                        best = bytes;
                        if (windowSeconds <= 0) {
                            return Optional.of(new Voice(best, shard.getFileName().toString()));
                        }
                    }
                }
                if (best != null) {
                    return Optional.of(new Voice(best, shard.getFileName().toString()));
                }
            } catch (IOException | RuntimeException ex) {
                log.debug("读取语音库失败: {} ({})", shard, ex.getMessage());
            }
        }
        return Optional.empty();
    }

    /** {@code media_*.db} 分片（按名字排序）。 */
    public List<Path> shards() {
        List<Path> shards = new ArrayList<>();
        for (Path dir : messageDirs()) {
            try (Stream<Path> list = Files.list(dir)) {
                list.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().matches("media_\\d+\\.db"))
                        .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                        .forEach(shards::add);
            } catch (IOException ex) {
                log.debug("列出语音库失败: {} ({})", dir, ex.getMessage());
            }
        }
        return shards;
    }

    /** 所有找到 {@code db_storage/message} 的账号目录。 */
    public List<Path> messageDirs() {
        List<Path> dirs = new ArrayList<>();
        for (Path root : roots()) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            Path direct = root.resolve("db_storage").resolve("message");
            if (Files.isDirectory(direct)) {
                dirs.add(direct);
                continue;
            }
            try (Stream<Path> children = Files.list(root)) {
                children.filter(Files::isDirectory)
                        .map(child -> child.resolve("db_storage").resolve("message"))
                        .filter(Files::isDirectory)
                        .forEach(dirs::add);
            } catch (IOException ex) {
                log.debug("扫描微信数据目录失败: {} ({})", root, ex.getMessage());
            }
        }
        return dirs;
    }

    private List<Path> roots() {
        String configured = properties.getDataDir();
        if (configured != null && !configured.isBlank()) {
            return List.of(Path.of(configured.trim()));
        }
        String home = System.getProperty("user.home", "");
        if (home.isBlank()) {
            return List.of();
        }
        return List.of(Path.of(home, "Documents", "xwechat_files"));
    }

    private SqliteReader readerOf(Path shard) throws IOException {
        long size = Files.size(shard);
        long modified = Files.getLastModifiedTime(shard).toMillis();
        String key = shard.toAbsolutePath().toString();
        Cached cached = cache.get(key);
        if (cached != null && cached.size() == size && cached.modified() == modified) {
            return cached.reader();
        }
        SqlCipherFile file = SqlCipherFile.open(shard, properties.getKey(), properties.getPageSize());
        SqliteReader reader = new SqliteReader(file);
        if (cache.size() >= MAX_CACHED_FILES) {
            cache.clear();
        }
        cache.put(key, new Cached(size, modified, file, reader));
        return reader;
    }

    /** 管理端自检：配置是否齐全、找到几个分片、里面有几条语音。 */
    public Map<String, Object> diagnostics() {
        Map<String, Object> info = new java.util.LinkedHashMap<>();
        info.put("enabled", properties.isEnabled());
        info.put("configured", properties.isConfigured());
        info.put("maskedKey", properties.maskedKey());
        info.put("dataDir", properties.getDataDir() == null || properties.getDataDir().isBlank()
                ? "(自动)" : properties.getDataDir());
        List<Path> shards = shards();
        info.put("shards", shards.size());
        int voices = 0;
        List<String> errors = new ArrayList<>();
        for (Path shard : shards) {
            try {
                SqliteReader reader = readerOf(shard);
                if (reader == null) {
                    continue;
                }
                voices += reader.rows("VoiceInfo").size();
            } catch (IOException | RuntimeException ex) {
                errors.add(shard.getFileName() + ": " + ex.getMessage());
            }
        }
        info.put("voiceRows", voices);
        info.put("errors", errors);
        return info;
    }
}
