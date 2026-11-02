package com.example.wechatbot.wechat.db;

import com.example.wechatbot.config.WeChatDbProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 自动截获微信数据库密钥。
 *
 * <p>密钥只在微信「打开数据库」那一刻可用，所以走的是和 {@code D:\WeChatAgent-main} 一样的路子：
 * 调 {@code wx_key.dll} 的 {@code InitializeHook(pid)} + {@code PollKeyData()}，等微信登录/开库时
 * 把口令截下来，再拿它去验证本地库——验证通过才写进密钥文件。</p>
 *
 * <p>实现在 {@code scripts/capture-wechat-db-key.ps1}（内含 P/Invoke 的 C# 片段），
 * 这样不用给 Java 侧加 JNA 之类的依赖，出问题也能单独跑脚本排查。</p>
 */
@Component
public class WeChatKeyCapture {
    private static final Logger log = LoggerFactory.getLogger(WeChatKeyCapture.class);
    private static final Pattern OK = Pattern.compile("RESULT=OK\\s+key=([0-9a-fA-F]{64})");
    private static final Pattern FAIL = Pattern.compile("RESULT=(?:FAIL|NONE)\\s*(.*)");

    private final WeChatDbProperties properties;
    private final WeChatDbKeyStore keyStore;
    private final WeChatDataDirs dirs;
    private final AtomicBoolean running = new AtomicBoolean();

    public WeChatKeyCapture(WeChatDbProperties properties, WeChatDbKeyStore keyStore, WeChatDataDirs dirs) {
        this.properties = properties;
        this.keyStore = keyStore;
        this.dirs = dirs;
    }

    /** @param ok 是否截获并验证通过；{@code message} 是给人看的原因 */
    public record Result(boolean ok, String maskedKey, String message) {}

    public boolean running() {
        return running.get();
    }

    /** 脚本、DLL、微信进程三者都在才可能成功。 */
    public boolean available() {
        return Files.isRegularFile(scriptPath())
                && Files.isRegularFile(dllPath())
                && weChatProcess().isPresent();
    }

    /** 截获一次（会阻塞到成功、超时或失败）。 */
    public Result capture(int timeoutSeconds) {
        if (!running.compareAndSet(false, true)) {
            return new Result(false, "", "已经有一次截获在进行中");
        }
        try {
            return doCapture(Math.max(10, timeoutSeconds));
        } finally {
            running.set(false);
        }
    }

    private Result doCapture(int timeoutSeconds) {
        if (!Files.isRegularFile(scriptPath())) {
            return new Result(false, "", "没找到截获脚本: " + scriptPath().toAbsolutePath());
        }
        if (!Files.isRegularFile(dllPath())) {
            return new Result(false, "", "没找到 wx_key.dll: " + dllPath().toAbsolutePath());
        }
        Optional<ProcessHandle> wechat = weChatProcess();
        if (wechat.isEmpty()) {
            return new Result(false, "", "微信（Weixin.exe）没在运行，先登录微信再试");
        }
        List<String> command = new ArrayList<>();
        command.add(powerShell());
        command.addAll(List.of("-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                "-File", scriptPath().toAbsolutePath().toString(),
                "-DllPath", dllPath().toAbsolutePath().toString(),
                "-KeyFile", keyStore.keyFile().toAbsolutePath().toString(),
                "-TimeoutSeconds", String.valueOf(timeoutSeconds)));
        dirs.sampleDatabase().ifPresent(db -> {
            command.add("-DbPath");
            command.add(db.toString());
        });
        log.info("开始自动截获微信数据库密钥（pid={}，最多 {} 秒）", wechat.get().pid(), timeoutSeconds);
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = readAll(process, timeoutSeconds + 60L);
            int exit = process.exitValue();
            Matcher ok = OK.matcher(output);
            if (ok.find()) {
                String key = ok.group(1).toLowerCase(java.util.Locale.ROOT);
                try {
                    keyStore.save(key);
                } catch (IOException ex) {
                    return new Result(false, "", "截获成功但写密钥文件失败: " + ex.getMessage());
                }
                keyStore.invalidate();
                WeChatDbKeyStore.Check check = keyStore.check();
                if (!check.valid()) {
                    return new Result(false, "", "截获到密钥但校验没通过: " + check.error());
                }
                return new Result(true, check.maskedKey(), "截获成功，密钥已写入 " + keyStore.keyFile());
            }
            Matcher fail = FAIL.matcher(output);
            String reason = fail.find() ? fail.group(1).trim() : lastLines(output, 3);
            return new Result(false, "", "没截获到密钥（exit=" + exit + "）: " + reason);
        } catch (IOException ex) {
            return new Result(false, "", "启动截获脚本失败: " + ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new Result(false, "", "截获被中断");
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private String readAll(Process process, long timeoutSeconds) throws IOException, InterruptedException {
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append('\n');
            }
        }
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            return builder + "\n(超时)";
        }
        return builder.toString();
    }

    private static String lastLines(String output, int count) {
        String[] lines = output.trim().split("\\R");
        StringBuilder builder = new StringBuilder();
        for (int i = Math.max(0, lines.length - count); i < lines.length; i++) {
            if (!lines[i].isBlank()) {
                builder.append(lines[i].trim()).append(" | ");
            }
        }
        return builder.length() == 0 ? "(无输出)" : builder.toString();
    }

    /** 微信主进程（有子进程的那个），Hook 必须装在它上面。 */
    public Optional<ProcessHandle> weChatProcess() {
        List<ProcessHandle> candidates = ProcessHandle.allProcesses()
                .filter(handle -> handle.info().command()
                        .map(path -> path.toLowerCase(java.util.Locale.ROOT).contains("weixin"))
                        .orElse(false))
                .toList();
        return candidates.stream()
                .filter(handle -> candidates.stream().anyMatch(other -> other.parent()
                        .map(parent -> parent.pid() == handle.pid()).orElse(false)))
                .findFirst()
                .or(() -> candidates.stream().findFirst());
    }

    private Path scriptPath() {
        return Path.of(properties.getCaptureScript());
    }

    private Path dllPath() {
        return Path.of(properties.getKeyDll());
    }

    private static String powerShell() {
        String windir = System.getenv().getOrDefault("WINDIR", "C:\\Windows");
        return windir + "\\System32\\WindowsPowerShell\\v1.0\\powershell.exe";
    }
}
