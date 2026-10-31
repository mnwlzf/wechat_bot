package com.example.wechatbot.asr;

import com.example.wechatbot.config.AsrProperties;
import com.example.wechatbot.config.WeChatHookProperties;
import com.example.wechatbot.wechat.WeChatDatDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 在本地微信数据目录里找语音文件。
 *
 * <p>微信把收到的语音放在 {@code msg/attach/.../Voice/*.dat}（加密）或 {@code *.silk}。
 * 报文里有 md5 就按文件名找，没有就按消息时间找最接近的那个——和图片那套定位思路一致。
 * 找到后解密，只接受 SILK / WAV 内容，避免把别的附件当成语音。</p>
 */
@Component
public class VoiceFileResolver {
    private static final Logger log = LoggerFactory.getLogger(VoiceFileResolver.class);
    private static final List<String> VOICE_DIRS = List.of("voice", "audio");
    private static final int MAX_FILES = 4000;

    private final WeChatHookProperties hookProperties;
    private final AsrProperties asrProperties;
    private final Map<String, List<WeChatDatDecoder.Key>> keyCache = new HashMap<>();

    public VoiceFileResolver(WeChatHookProperties hookProperties, AsrProperties asrProperties) {
        this.hookProperties = hookProperties;
        this.asrProperties = asrProperties;
    }

    /** @param bytes 解密后的音频（SILK 或 WAV 原始字节） */
    public record Voice(byte[] bytes, Path path) {}

    public Optional<Voice> resolve(String md5, OffsetDateTime sentAt) {
        for (Location location : locations()) {
            for (Path dir : voiceDirs(location.root())) {
                Optional<Voice> hit = pick(dir, location.accountWxid(), md5, sentAt);
                if (hit.isPresent()) {
                    return hit;
                }
            }
        }
        return Optional.empty();
    }

    private record Location(Path root, String accountWxid) {}

    private List<Location> locations() {
        List<Location> locations = new ArrayList<>();
        for (Path root : roots()) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> children = Files.list(root)) {
                children.filter(Files::isDirectory)
                        .filter(path -> Files.isDirectory(path.resolve("msg")))
                        .forEach(path -> locations.add(new Location(path, path.getFileName().toString())));
            } catch (IOException ex) {
                log.debug("扫描微信目录失败: {} ({})", root, ex.getMessage());
            }
        }
        return locations;
    }

    private List<Path> roots() {
        String configured = hookProperties.getImageRoot();
        if (configured != null && !configured.isBlank()) {
            return List.of(Path.of(configured.trim()));
        }
        String home = System.getProperty("user.home", "");
        if (home.isBlank()) {
            return List.of();
        }
        return List.of(
                Path.of(home, "Documents", "xwechat_files"),
                Path.of(home, "Documents", "WeChat Files"));
    }

    /** {@code msg/attach/...} 下所有叫 Voice / Audio 的目录。 */
    private List<Path> voiceDirs(Path accountDir) {
        Path attach = accountDir.resolve("msg").resolve("attach");
        if (!Files.isDirectory(attach)) {
            return List.of();
        }
        List<Path> dirs = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(attach, 6)) {
            walk.filter(Files::isDirectory)
                    .filter(path -> VOICE_DIRS.contains(path.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .forEach(dirs::add);
        } catch (IOException ex) {
            log.debug("遍历语音目录失败: {} ({})", attach, ex.getMessage());
        }
        return dirs;
    }

    private Optional<Voice> pick(Path dir, String accountWxid, String md5, OffsetDateTime sentAt) {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(dir, 4)) {
            walk.filter(Files::isRegularFile)
                    .filter(this::isAudioCandidate)
                    .limit(MAX_FILES)
                    .forEach(files::add);
        } catch (IOException ex) {
            log.debug("列出语音文件失败: {} ({})", dir, ex.getMessage());
            return Optional.empty();
        }
        if (files.isEmpty()) {
            return Optional.empty();
        }
        List<WeChatDatDecoder.Key> keys = keysOf(accountWxid);
        String needle = md5 == null ? "" : md5.toLowerCase(Locale.ROOT);
        long target = sentAt == null ? 0L : sentAt.toInstant().toEpochMilli();
        long windowMillis = Math.max(30, asrProperties.getVoiceMatchWindowSeconds()) * 1000L;

        List<Path> ordered = new ArrayList<>(files);
        ordered.sort(Comparator.comparingLong((Path path) -> rank(path, needle, target, windowMillis))
                .thenComparingLong(VoiceFileResolver::lastModified));
        for (Path path : ordered) {
            if (rank(path, needle, target, windowMillis) > windowMillis) {
                break;
            }
            byte[] audio = readAudio(path, keys);
            if (audio != null) {
                return Optional.of(new Voice(audio, path));
            }
        }
        return Optional.empty();
    }

    private boolean isAudioCandidate(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".dat") || name.endsWith(".silk") || name.endsWith(".wav");
    }

    private static long rank(Path path, String md5, long target, long windowMillis) {
        if (!md5.isEmpty() && path.getFileName().toString().toLowerCase(Locale.ROOT).contains(md5)) {
            return 0L;
        }
        if (target == 0L) {
            return windowMillis;
        }
        return Math.abs(lastModified(path) - target);
    }

    private byte[] readAudio(Path path, List<WeChatDatDecoder.Key> keys) {
        byte[] raw;
        try {
            raw = Files.readAllBytes(path);
        } catch (IOException ex) {
            return null;
        }
        if (SilkDecoder.isSilk(raw) || SilkDecoder.isWav(raw)) {
            return raw;
        }
        if (!WeChatDatDecoder.isV2(raw) || keys.isEmpty()) {
            return null;
        }
        for (WeChatDatDecoder.Key key : keys) {
            byte[] plain = WeChatDatDecoder.decode(raw, key);
            if (plain != null && (SilkDecoder.isSilk(plain) || SilkDecoder.isWav(plain))) {
                return plain;
            }
        }
        return null;
    }

    private List<WeChatDatDecoder.Key> keysOf(String accountWxid) {
        if (accountWxid == null || accountWxid.isBlank()) {
            return List.of();
        }
        return keyCache.computeIfAbsent(accountWxid, wxid -> {
            List<WeChatDatDecoder.Key> keys = WeChatDatDecoder.localKeys(kvcommDir(), wxid);
            if (keys.isEmpty()) {
                log.debug("未能从 kvcomm 推出语音解密密钥: wxid={}", wxid);
            }
            return keys;
        });
    }

    private Path kvcommDir() {
        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isBlank()) {
            return Path.of(appData, "Tencent", "xwechat", "net", "kvcomm");
        }
        String home = System.getProperty("user.home", "");
        return home.isBlank()
                ? Path.of("kvcomm")
                : Path.of(home, "AppData", "Roaming", "Tencent", "xwechat", "net", "kvcomm");
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException ex) {
            return 0L;
        }
    }
}
