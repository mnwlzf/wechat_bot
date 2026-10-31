package com.example.wechatbot.asr;

import com.example.wechatbot.config.AsrProperties;
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
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * SILK → WAV。微信语音是 SILK v3 编码，ASR 接口只收 wav / mp3，所以中间要转一道。
 *
 * <p>Java 侧没有现成的 SILK 解码器，这里走「外部命令」桥接，两种协议都支持：</p>
 * <ul>
 *   <li><b>JSON 进 JSON 出</b>：stdin {@code {"b64":"<silk>"}} → stdout {@code {"b64":"<wav>"}}，
 *       与 WeChatAgent 的 {@code scripts/decode-wechat-voice.mjs} 一致；</li>
 *   <li><b>文件模式</b>：命令里带 {@code {input}} / {@code {output}} 占位符，例如
 *       {@code ffmpeg -y -i {input} {output}}。</li>
 * </ul>
 *
 * <p>没配置也没自动探测到时，语音消息保持原来的「只能看到时长」行为，并在日志里说明原因。</p>
 */
@Service
public class SilkDecoder {
    private static final Logger log = LoggerFactory.getLogger(SilkDecoder.class);
    private static final byte[] SILK_MAGIC = "#!SILK_V3".getBytes(StandardCharsets.US_ASCII);
    private static final long TIMEOUT_SECONDS = 60;

    private final AsrProperties properties;
    private final ObjectMapper objectMapper;

    private volatile List<String> cachedCommand;
    private volatile boolean resolved;

    public SilkDecoder(AsrProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /** 报文是不是 SILK（有些版本前面多一个 0x02 字节）。 */
    public static boolean isSilk(byte[] data) {
        if (data == null) {
            return false;
        }
        int start = data.length > 0 && data[0] == 2 ? 1 : 0;
        if (data.length < start + SILK_MAGIC.length) {
            return false;
        }
        for (int i = 0; i < SILK_MAGIC.length; i++) {
            if (data[start + i] != SILK_MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    public static boolean isWav(byte[] data) {
        return data != null && data.length >= 12
                && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F';
    }

    public static boolean isMp3(byte[] data) {
        return data != null && data.length > 2
                && (data[0] & 0xFF) == 0xFF && (data[1] & 0xE0) == 0xE0;
    }

    /** 可用的解码命令；空表示不可用。 */
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
                log.info("未找到 SILK 解码器，语音消息将只显示时长；可配置 ASR_SILK_DECODER 指定解码命令");
            } else {
                log.info("SILK 解码器就绪: {}", String.join(" ", cachedCommand));
            }
            return cachedCommand;
        }
    }

    public boolean available() {
        return !command().isEmpty();
    }

    /** 供管理端展示：解析出来的解码命令，或空串。 */
    public String backend() {
        return String.join(" ", command());
    }

    /** SILK 字节 → WAV 字节。 */
    public byte[] toWav(byte[] silk) {
        if (silk == null || silk.length == 0) {
            throw new AsrException("语音内容为空");
        }
        if (isWav(silk)) {
            return silk;
        }
        if (!isSilk(silk)) {
            throw new AsrException("不是 SILK / WAV 音频，无法转写");
        }
        List<String> command = command();
        if (command.isEmpty()) {
            throw new AsrException("语音是 SILK 编码，但没找到解码器（可配置 ASR_SILK_DECODER）");
        }
        return usesFileMode(command) ? runFileMode(command, silk) : runJsonMode(command, silk);
    }

    // ------------------------------------------------------------------ 实现

    private static boolean usesFileMode(List<String> command) {
        return command.stream().anyMatch(token -> token.contains("{input}") || token.contains("{output}"));
    }

    private byte[] runJsonMode(List<String> command, byte[] silk) {
        String payload = "{\"b64\":\"" + Base64.getEncoder().encodeToString(silk) + "\"}";
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
                throw new AsrException("SILK 解码超时（" + TIMEOUT_SECONDS + "s）");
            }
            if (process.exitValue() != 0) {
                throw new AsrException("SILK 解码失败: " + abbreviate(stderr.isBlank() ? stdout : stderr, 200));
            }
            JsonNode node = objectMapper.readTree(stdout.isBlank() ? "{}" : stdout);
            String b64 = node.path("b64").asText("");
            if (b64.isBlank()) {
                throw new AsrException("SILK 解码器没有返回音频: " + abbreviate(stdout, 200));
            }
            return Base64.getDecoder().decode(b64);
        } catch (IOException ex) {
            throw new AsrException("SILK 解码命令无法执行: " + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AsrException("SILK 解码被中断", ex);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private byte[] runFileMode(List<String> command, byte[] silk) {
        Path input = null;
        Path output = null;
        try {
            input = Files.createTempFile("wechat-voice-", ".silk");
            output = Files.createTempFile("wechat-voice-", ".wav");
            Files.write(input, silk);
            List<String> resolved = new ArrayList<>(command.size());
            for (String token : command) {
                resolved.add(token.replace("{input}", input.toString()).replace("{output}", output.toString()));
            }
            Process process = new ProcessBuilder(resolved).start();
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new AsrException("SILK 解码超时（" + TIMEOUT_SECONDS + "s）");
            }
            if (process.exitValue() != 0) {
                throw new AsrException("SILK 解码失败: " + abbreviate(stderr, 200));
            }
            byte[] wav = Files.readAllBytes(output);
            if (wav.length == 0) {
                throw new AsrException("SILK 解码没有输出音频");
            }
            return wav;
        } catch (IOException ex) {
            throw new AsrException("SILK 解码命令无法执行: " + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AsrException("SILK 解码被中断", ex);
        } finally {
            deleteQuietly(input);
            deleteQuietly(output);
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 临时文件删不掉不影响功能
        }
    }

    private List<String> resolveCommand() {
        List<String> configured = split(properties.getSilkDecoder());
        if (!configured.isEmpty()) {
            if (looksRunnable(configured)) {
                return configured;
            }
            // 常见坑：.env / properties 会把反斜杠当转义吃掉，路径变成 C:Userszf... 这种
            log.warn("配置的 SILK 解码命令跑不起来（路径被转义吃掉了？建议改用正斜杠）: {}",
                    String.join(" ", configured));
        }
        Path script = findScript(".mjs");
        if (script != null) {
            String node = findOnPath("node");
            if (node != null) {
                return List.of(node, script.toString());
            }
        }
        Path pythonScript = findScript(".py");
        if (pythonScript != null) {
            String python = findOnPath("python");
            if (python != null) {
                return List.of(python, pythonScript.toString());
            }
        }
        return List.of();
    }

    /** 第一个 token 是命令本身：要么是存在的可执行文件，要么能在 PATH 里找到。 */
    private static boolean looksRunnable(List<String> command) {
        if (command.isEmpty()) {
            return false;
        }
        String exe = command.get(0);
        Path path = Path.of(exe);
        if (path.isAbsolute() || exe.contains("/") || exe.contains("\\")) {
            return Files.isRegularFile(path);
        }
        return findOnPath(exe) != null;
    }

    /** 找 WeChatAgent 的解码脚本（只做探测，不复制、不打包别人的产物）。 */
    private Path findScript(String suffix) {
        List<Path> candidates = new ArrayList<>();
        String agentHome = System.getenv("WECHAT_AGENT_HOME");
        if (agentHome != null && !agentHome.isBlank()) {
            candidates.add(Path.of(agentHome.trim()));
        }
        Path cwd = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath();
        candidates.add(cwd.resolve("../WeChatAgent-main"));
        candidates.add(cwd.resolve("WeChatAgent-main"));
        String home = System.getProperty("user.home", "");
        if (!home.isBlank()) {
            candidates.add(Path.of(home, "WeChatAgent-main"));
        }
        candidates.add(Path.of("D:/WeChatAgent-main"));
        for (Path candidate : candidates) {
            Path script = candidate.resolve("scripts").resolve("decode-wechat-voice" + suffix).normalize();
            if (Files.isRegularFile(script)) {
                return script;
            }
        }
        return null;
    }

    private static String findOnPath(String executable) {
        String override = System.getenv("ASR_" + executable.toUpperCase(Locale.ROOT));
        if (override != null && !override.isBlank() && Files.isRegularFile(Path.of(override.trim()))) {
            return override.trim();
        }
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return null;
        }
        for (String dir : path.split(";")) {
            if (dir.isBlank()) {
                continue;
            }
            for (String name : List.of(executable + ".exe", executable + ".cmd", executable)) {
                Path candidate = Path.of(dir.trim(), name);
                if (Files.isRegularFile(candidate)) {
                    return candidate.toString();
                }
            }
        }
        return null;
    }

    /** 按空格切分命令，支持双引号包住带空格的路径。 */
    static List<String> split(String command) {
        if (command == null || command.isBlank()) {
            return List.of();
        }
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (char ch : command.trim().toCharArray()) {
            if (ch == '"') {
                quoted = !quoted;
                continue;
            }
            if (Character.isWhitespace(ch) && !quoted) {
                if (current.length() > 0) {
                    parts.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }
            current.append(ch);
        }
        if (current.length() > 0) {
            parts.add(current.toString());
        }
        return parts;
    }

    private static String abbreviate(String value, int max) {
        String text = value == null ? "" : value.trim().replaceAll("\\s+", " ");
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
