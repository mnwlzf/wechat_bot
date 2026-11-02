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
 * <p>开了 {@code app.tts.silk}（默认开）还会顺手生成一个 {@code .silk}——那是微信语音消息
 * 真正要发的东西，等 Hook 支持发语音时直接用它。开了 SILK 就按 16 kHz 合成，
 * 免得再重采样一次。</p>
 */
@Service
public class TtsService {
    private static final Logger log = LoggerFactory.getLogger(TtsService.class);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    /** 只保留最近这么多条，避免 data 目录无限长。 */
    private static final int KEEP_FILES = 50;

    private final TtsProperties properties;
    private final QwenTtsClient client;
    private final SilkEncoder silkEncoder;

    public TtsService(TtsProperties properties, QwenTtsClient client, SilkEncoder silkEncoder) {
        this.properties = properties;
        this.client = client;
        this.silkEncoder = silkEncoder;
    }

    /**
     * @param durationMs PCM 时长；非 PCM 格式为 -1（时长要从容器里读，这里不解析）
     * @param silkName   SILK 文件名，没生成时为 null
     */
    public record Speech(String name, Path file, int bytes, String format, int sampleRate,
                         int durationMs, int chunks, long latencyMs, String voice, String model,
                         String silkName, int silkBytes, int silkFrames, String silkError) {}

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
        boolean wantSilk = properties.isSilk() && silkEncoder.available();
        // 要 SILK 就按微信的 16 kHz / 裸 PCM 合成，省一次重采样
        int rate = sampleRate != null && sampleRate > 0
                ? sampleRate
                : (wantSilk ? properties.getSilkSampleRate() : properties.getSampleRate());
        String requested = format == null || format.isBlank()
                ? (wantSilk ? "pcm" : properties.getFormat())
                : format;

        QwenTtsClient.Result result = client.synthesize(text, voice, requested, rate);
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
        String base = "tts-" + LocalDateTime.now().format(STAMP) + "-"
                + UUID.randomUUID().toString().substring(0, 6);
        String name = base + extension;
        Path directory = outputDir();
        Path file = directory.resolve(name);
        try {
            Files.createDirectories(directory);
            Files.write(file, audio);
        } catch (IOException ex) {
            throw new TtsException("写音频文件失败: " + ex.getMessage(), ex);
        }

        String silkName = null;
        int silkBytes = 0;
        int silkFrames = 0;
        String silkError = null;
        if (wantSilk) {
            try {
                SilkEncoder.Encoded encoded = silkEncoder.encode(audio, result.sampleRate());
                silkName = base + ".silk";
                Files.write(directory.resolve(silkName), encoded.silk());
                silkBytes = encoded.silk().length;
                silkFrames = encoded.frames();
            } catch (TtsException | IOException ex) {
                silkError = ex.getMessage();
                silkName = null;
                log.warn("生成 SILK 失败（WAV 仍然可用）: {}", ex.getMessage());
            }
        }
        prune();
        log.info("TTS 合成完成: {} ({} 字节, {} ms, voice={}){}", name, audio.length, durationMs,
                voice == null || voice.isBlank() ? properties.getVoice() : voice,
                silkName == null ? "" : " + " + silkName);
        return new Speech(name, file, audio.length, encoding, result.sampleRate(), durationMs,
                result.chunks(), result.latencyMs(),
                voice == null || voice.isBlank() ? properties.getVoice() : voice.trim(),
                properties.getModel(), silkName, silkBytes, silkFrames, silkError);
    }

    public boolean silkAvailable() {
        return properties.isSilk() && silkEncoder.available();
    }

    public String silkBackend() {
        return silkEncoder.backend();
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
