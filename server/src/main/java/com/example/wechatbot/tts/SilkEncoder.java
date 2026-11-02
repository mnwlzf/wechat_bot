package com.example.wechatbot.tts;

import com.example.wechatbot.config.AsrProperties;
import com.example.wechatbot.config.TtsProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * WAV → 微信语音用的 SILK v3。
 *
 * <p>微信的语音消息是 16 kHz 单声道 SILK，文件以 {@code 0x02} 开头 + {@code "#!SILK_V3"}，
 * 每帧前面 2 字节小端长度。Java 侧没有现成的编码器，所以和 {@code SilkDecoder} 一样走
 * 「JSON 进 JSON 出」的外部命令：{@code node scripts/encode-wechat-voice.mjs}（内部用 silk-wasm 的 wasm）。</p>
 *
 * <p>现在 Hook 还没有发语音的接口，这一步是先把「能发出去的字节」准备好：
 * 换到带 {@code /SendVoiceMsg} 的 Hook 时，直接把这支 {@code .silk} 交出去即可。</p>
 */
@Service
public class SilkEncoder {
    private static final Logger log = LoggerFactory.getLogger(SilkEncoder.class);
    private static final long TIMEOUT_SECONDS = 120;

    private final TtsProperties properties;
    private final AsrProperties asrProperties;
    private final ObjectMapper objectMapper;

    private volatile List<String> cachedCommand;
    private volatile boolean resolved;

    public SilkEncoder(TtsProperties properties, AsrProperties asrProperties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.asrProperties = asrProperties;
        this.objectMapper = objectMapper;
    }

    /** @param silk 带 {@code 0x02 #!SILK_V3} 头的完整内容 */
    public record Encoded(byte[] silk, int frames, int durationMs, int sampleRate) {}

    public List<String> command() {
        if (resolved) {
            return cachedCommand;
        }
        synchronized (this) {
            if (resolved) {
                return cachedCommand;
            }
            cachedCommand = resolveCommand();
            resolved = true;
            if (cachedCommand.isEmpty()) {
                log.info("未找到 WAV→SILK 编码器，合成结果只落 WAV；可配置 TTS_SILK_ENCODER 指定编码命令");
            } else {
                log.info("SILK 编码器就绪: {}", String.join(" ", cachedCommand));
            }
            return cachedCommand;
        }
    }

    public boolean available() {
        return !command().isEmpty();
    }

    /** 供管理端展示。 */
    public String backend() {
        return String.join(" ", command());
    }

    public Encoded encode(byte[] wav, int sampleRate) {
        List<String> command = command();
        if (command.isEmpty()) {
            throw new TtsException("没有可用的 WAV→SILK 编码器（可配置 TTS_SILK_ENCODER）");
        }
        if (wav == null || wav.length == 0) {
            throw new TtsException("音频为空，无法编码 SILK");
        }
        String payload = "{\"b64\":\"" + Base64.getEncoder().encodeToString(wav)
                + "\",\"sampleRate\":" + sampleRate + "}";
        Process process = null;
        try {
            process = new ProcessBuilder(command).start();
            try (OutputStream stdin = process.getOutputStream()) {
                stdin.write(payload.getBytes(StandardCharsets.UTF_8));
            }
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new TtsException("SILK 编码超时（" + TIMEOUT_SECONDS + "s）");
            }
            if (process.exitValue() != 0) {
                throw new TtsException("SILK 编码失败: " + abbreviate(stderr.isBlank() ? stdout : stderr, 200));
            }
            JsonNode node = objectMapper.readTree(stdout.isBlank() ? "{}" : stdout);
            String b64 = node.path("b64").asText("");
            if (b64.isBlank()) {
                throw new TtsException("SILK 编码器没有返回内容: " + abbreviate(stdout, 200));
            }
            return new Encoded(Base64.getDecoder().decode(b64), node.path("frames").asInt(0),
                    node.path("durationMs").asInt(-1), node.path("sampleRate").asInt(sampleRate));
        } catch (IOException ex) {
            throw new TtsException("SILK 编码命令无法执行: " + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new TtsException("SILK 编码被中断", ex);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    // ------------------------------------------------------------------ 命令解析

    private List<String> resolveCommand() {
        List<String> configured = split(properties.getSilkEncoder());
        if (!configured.isEmpty()) {
            if (runnable(configured)) {
                return configured;
            }
            log.warn("配置的 SILK 编码命令跑不起来（路径被转义吃掉了？建议改用正斜杠）: {}",
                    String.join(" ", configured));
        }
        Path script = Path.of("scripts", "encode-wechat-voice.mjs").toAbsolutePath().normalize();
        if (!Files.isRegularFile(script)) {
            log.debug("没找到编码脚本: {}", script);
            return List.of();
        }
        String node = nodeFromDecoder();
        if (node == null) {
            node = findOnPath("node");
        }
        if (node == null) {
            log.debug("没找到 node，跳过 SILK 编码");
            return List.of();
        }
        return List.of(node, script.toString());
    }

    /** 借用 ASR 解码器配好的那个 node（同一台机器上通常只装了一份）。 */
    private String nodeFromDecoder() {
        List<String> decoder = split(asrProperties.getSilkDecoder());
        if (decoder.isEmpty()) {
            return null;
        }
        String candidate = decoder.get(0);
        String lower = candidate.toLowerCase(java.util.Locale.ROOT);
        if ((lower.endsWith("node.exe") || lower.endsWith("node")) && Files.isRegularFile(Path.of(candidate))) {
            return candidate;
        }
        return null;
    }

    private static boolean runnable(List<String> command) {
        String first = command.get(0).replace('\\', '/');
        if (first.contains("/")) {
            return Files.isRegularFile(Path.of(first));
        }
        return findOnPath(first) != null;
    }

    static String findOnPath(String executable) {
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return null;
        }
        for (String entry : path.split(java.io.File.pathSeparator)) {
            if (entry.isBlank()) {
                continue;
            }
            for (String name : List.of(executable, executable + ".exe", executable + ".cmd")) {
                Path candidate = Path.of(entry, name);
                if (Files.isRegularFile(candidate)) {
                    return candidate.toString();
                }
            }
        }
        return null;
    }

    private static List<String> split(String command) {
        if (command == null || command.isBlank()) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        for (String token : command.trim().split("\\s+")) {
            if (!token.isBlank()) {
                tokens.add(token.trim());
            }
        }
        return tokens;
    }

    private static String abbreviate(String value, int max) {
        String text = value == null ? "" : value.replaceAll("\\s+", " ");
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
