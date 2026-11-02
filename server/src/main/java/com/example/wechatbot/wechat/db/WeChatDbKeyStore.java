package com.example.wechatbot.wechat.db;

import com.example.wechatbot.config.WeChatDbProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
 * 微信数据库密钥的来源与校验。
 *
 * <p>优先级：密钥文件（{@code WECHAT_DB_KEY_FILE}，默认 {@code data/wechat-db-key.txt}）>
 * 环境变量 / {@code .env} 的 {@code WECHAT_DB_KEY}。文件优先是为了「自动截获的新密钥
 * 永远覆盖旧值」——密钥变了不需要人去改 {@code .env}，重写文件就够了。</p>
 *
 * <p>密钥与账号绑定、一次安装内不变（微信不会为改密钥去重新加密整个库），
 * 只有换账号、重装、清数据这类操作才需要重新截获。</p>
 */
@Component
public class WeChatDbKeyStore {
    private static final Logger log = LoggerFactory.getLogger(WeChatDbKeyStore.class);
    private static final long CHECK_TTL_MILLIS = 60_000L;

    private final WeChatDbProperties properties;
    private final WeChatDataDirs dirs;

    private volatile String cachedFileKey;
    private volatile long cachedFileModified = -1L;
    private volatile Check cachedCheck;
    private volatile long cachedCheckAt;

    public WeChatDbKeyStore(WeChatDbProperties properties, WeChatDataDirs dirs) {
        this.properties = properties;
        this.dirs = dirs;
    }

    /** @param source {@code file} / {@code env} / {@code none} */
    public record Check(boolean configured, boolean valid, String source, String maskedKey,
                        String sampleDatabase, String error) {}

    public Path keyFile() {
        String configured = properties.getKeyFile();
        return Path.of(configured == null || configured.isBlank()
                ? "data/wechat-db-key.txt" : configured.trim());
    }

    /** 当前生效的密钥（没配就是空串）。 */
    public String key() {
        String fromFile = fileKey();
        if (isValidKey(fromFile)) {
            return fromFile;
        }
        String fromEnv = properties.getKey();
        return isValidKey(fromEnv) ? fromEnv : "";
    }

    public String source() {
        if (isValidKey(fileKey())) {
            return "file";
        }
        return isValidKey(properties.getKey()) ? "env" : "none";
    }

    public boolean configured() {
        return !key().isEmpty();
    }

    public String masked() {
        String value = key();
        if (value.isEmpty()) {
            return "";
        }
        return value.substring(0, 4) + "****" + value.substring(value.length() - 4);
    }

    /** 把截获到的密钥写到文件里（原子替换，避免半截文件）。 */
    public void save(String key) throws IOException {
        if (!isValidKey(key)) {
            throw new IllegalArgumentException("不是 64 位 hex 密钥");
        }
        Path file = keyFile().toAbsolutePath();
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, key.trim() + System.lineSeparator(), StandardCharsets.US_ASCII);
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        cachedFileKey = key.trim();
        cachedFileModified = Files.getLastModifiedTime(file).toMillis();
        cachedCheck = null;
        log.info("微信数据库密钥已写入 {}（{}）", file, masked());
    }

    /** 校验：能不能真的打开一个库。结果缓存 1 分钟，避免每条语音都读一遍文件。 */
    public Check check() {
        Check cached = cachedCheck;
        if (cached != null && System.currentTimeMillis() - cachedCheckAt < CHECK_TTL_MILLIS) {
            return cached;
        }
        Check result = doCheck();
        cachedCheck = result;
        cachedCheckAt = System.currentTimeMillis();
        return result;
    }

    public void invalidate() {
        cachedCheck = null;
        cachedFileModified = -1L;
    }

    private Check doCheck() {
        String key = key();
        if (key.isEmpty()) {
            return new Check(false, false, "none", "", sample(), "还没配置密钥");
        }
        Optional<Path> sample = dirs.sampleDatabase();
        if (sample.isEmpty()) {
            // 找不到库就没法判断对错：微信还没登录过，不算错
            return new Check(true, true, source(), masked(), "", "");
        }
        try {
            SqlCipherFile.open(sample.get(), key, properties.getPageSize());
            return new Check(true, true, source(), masked(), sample.get().toString(), "");
        } catch (IOException | RuntimeException ex) {
            return new Check(true, false, source(), masked(), sample.get().toString(),
                    "密钥打不开 " + sample.get().getFileName() + ": " + ex.getMessage());
        }
    }

    private String sample() {
        return dirs.sampleDatabase().map(Path::toString).orElse("");
    }

    /** 读密钥文件；按 mtime 缓存，文件被重写后自动重新加载。 */
    private String fileKey() {
        Path file = keyFile();
        try {
            if (!Files.isRegularFile(file)) {
                return "";
            }
            long modified = Files.getLastModifiedTime(file).toMillis();
            if (cachedFileKey != null && modified == cachedFileModified) {
                return cachedFileKey;
            }
            String content = Files.readString(file, StandardCharsets.US_ASCII).trim();
            cachedFileKey = content;
            cachedFileModified = modified;
            if (!isValidKey(content) && !content.isEmpty()) {
                log.warn("密钥文件 {} 内容不是 64 位 hex，已忽略", file);
            }
            return content;
        } catch (IOException ex) {
            log.debug("读密钥文件失败: {} ({})", file, ex.getMessage());
            return "";
        }
    }

    public static boolean isValidKey(String value) {
        return WeChatDbProperties.isValidKey(value);
    }
}
