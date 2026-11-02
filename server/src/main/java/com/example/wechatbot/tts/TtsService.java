package com.example.wechatbot.tts;

import com.example.wechatbot.config.TtsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 语音合成：文本 → 音频文件（落在 {@code data/tts/}），并在列表里保留最近一批。
 *
 * <p>微信这边的 Hook 只开放了文字和图片两个发送接口，没有发语音的接口，所以合成结果目前
 * 是「文件 + 管理端可播放」，给后面的语音回复用（见 {@code docs/api.md}）。</p>
 */
@Service
public class TtsService {
    private static final Logger log = LoggerFactory.getLogger(TtsService.class);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    /** 只保留最近这么多条，避免 data 目录无限长。 */
    private static final int KEEP_FILES = 50;

    private final TtsProperties properties;
    private final QwenTtsClient client;

    public TtsService(TtsProperties properties, QwenTtsClient client) {
        this.properties = properties;
        this.client = client;
    }

    /**
     * @param durationMs PCM 时长；非 PCM 格式为 -1（时长要从容器里读，这里不解析）
     */
    public record Speech(String name, Path file, int bytes, String format, int sampleRate,
                         int durationMs, int chunks, long latencyMs, String voice, String model) {}

    public boolean enabled() {
        return properties.isConfigured();
    }

    public Speech synthesize(String text) {
        return synthesize(text, null, null, null);
    }

    public Speech synthesize(String text, String voice, String format, Integer sampleRate) {
        if (!enabled()) {
            throw new TtsException("TTS 未配置：请在 .env 里填 DASHSCOPE_API_KEY");
        }
        QwenTtsClient.Result result = client.synthesize(text, voice, format, sampleRate == null ? 0 : sampleRate);
        byte[] audio = result.audio();
        String encoding = result.format().toLowerCase(Locale.ROOT);
        int durationMs = -1;
        if ("pcm".equals(encoding)) {
            durationMs = WavFormat.durationMillis(audio.length, result.sampleRate(), 1);
            audio = WavFormat.wrapPcm16(audio, result.sampleRate(), 1);
            encoding = "wav";
        }
        String extension = switch (encoding) {
            case "wav" -> ".wav";
            case "mp3" -> ".mp3";
            case "opus", "ogg" -> ".opus";
            default -> "." + encoding;
        };
        String name = "tts-" + LocalDateTime.now().format(STAMP) + "-"
                + UUID.randomUUID().toString().substring(0, 6) + extension;
        Path directory = outputDir();
        Path file = directory.resolve(name);
        try {
            Files.createDirectories(directory);
            Files.write(file, audio);
        } catch (IOException ex) {
            throw new TtsException("写音频文件失败: " + ex.getMessage(), ex);
        }
        prune();
        log.info("TTS 合成完成: {} ({} 字节, {} ms, voice={})", name, audio.length, durationMs,
                voice == null || voice.isBlank() ? properties.getVoice() : voice);
        return new Speech(name, file, audio.length, encoding, result.sampleRate(), durationMs,
                result.chunks(), result.latencyMs(),
                voice == null || voice.isBlank() ? properties.getVoice() : voice.trim(),
                properties.getModel());
    }

    public Path outputDir() {
        String configured = properties.getOutputDir();
        String dir = configured == null || configured.isBlank() ? "data/tts" : configured.trim();
        return Path.of(dir).toAbsolutePath().normalize();
    }

    /** 最近的合成结果，新的在前。 */
    public List<Map<String, Object>> files(int limit) {
        Path directory = outputDir();
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(directory)) {
            List<Path> all = new ArrayList<>(stream.filter(Files::isRegularFile).toList());
            all.sort(Comparator.comparing((Path path) -> path.getFileName().toString()).reversed());
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Path path : all.subList(0, Math.min(limit, all.size()))) {
                long size;
                try {
                    size = Files.size(path);
                } catch (IOException ex) {
                    size = -1;
                }
                rows.add(Map.of(
                        "name", path.getFileName().toString(),
                        "bytes", size,
                        "url", "/api/admin/tts/files/" + path.getFileName()));
            }
            return rows;
        } catch (IOException ex) {
            log.debug("列目录失败: {}", ex.getMessage());
            return List.of();
        }
    }

    /** 按文件名取合成结果；只允许 {@code data/tts} 下的普通文件。 */
    public Path file(String name) {
        if (name == null || name.isBlank() || name.contains("/") || name.contains("\\") || name.contains("..")) {
            throw new TtsException("文件名不合法: " + name);
        }
        Path path = outputDir().resolve(name).normalize();
        if (!path.startsWith(outputDir()) || !Files.isRegularFile(path)) {
            throw new TtsException("文件不存在: " + name);
        }
        return path;
    }

    private void prune() {
        Path directory = outputDir();
        try (Stream<Path> stream = Files.list(directory)) {
            List<Path> all = new ArrayList<>(stream.filter(Files::isRegularFile).toList());
            if (all.size() <= KEEP_FILES) {
                return;
            }
            all.sort(Comparator.comparing((Path path) -> path.getFileName().toString()));
            for (Path path : all.subList(0, all.size() - KEEP_FILES)) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ex) {
            log.debug("清理旧音频失败: {}", ex.getMessage());
        }
    }
}
