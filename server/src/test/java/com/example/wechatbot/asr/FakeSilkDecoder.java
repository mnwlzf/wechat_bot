package com.example.wechatbot.asr;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 测试用的假 SILK 解码器，代替 node / ffmpeg：
 * <ul>
 *   <li>无参数：JSON 进 JSON 出（把 stdin 的 b64 原样回显）；</li>
 *   <li>两个参数：文件模式（把 input 原样复制成 output）。</li>
 * </ul>
 */
public final class FakeSilkDecoder {
    private FakeSilkDecoder() {}

    public static void main(String[] args) throws Exception {
        if (args.length >= 2) {
            Files.copy(Path.of(args[0]), Path.of(args[1]), StandardCopyOption.REPLACE_EXISTING);
            return;
        }
        String json = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
        String b64 = json.replaceAll("(?s).*\"b64\"\\s*:\\s*\"([^\"]+)\".*", "$1");
        System.out.write(("{\"b64\":\"" + b64 + "\"}").getBytes(StandardCharsets.UTF_8));
        System.out.flush();
    }
}
