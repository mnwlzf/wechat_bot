package com.example.wechatbot.asr;

import com.example.wechatbot.config.AsrProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SilkDecoderTest {

    private static AsrProperties properties(String decoder) {
        AsrProperties properties = new AsrProperties();
        properties.setSilkDecoder(decoder);
        return properties;
    }

    private static String javaCommand(String extra) {
        String home = System.getProperty("java.home");
        String exe = home + java.io.File.separator + "bin" + java.io.File.separator + "java"
                + (System.getProperty("os.name", "").toLowerCase().contains("win") ? ".exe" : "");
        String classpath = classpath();
        return "\"" + exe + "\" -cp \"" + classpath + "\" com.example.wechatbot.asr.FakeSilkDecoder" + extra;
    }

    private static String classpath() {
        try {
            return Path.of(FakeSilkDecoder.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                    .toString();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static byte[] silkBytes() {
        byte[] magic = "#!SILK_V3".getBytes(StandardCharsets.US_ASCII);
        byte[] payload = new byte[]{0x02, 0x00, 0x10, 0x20};
        byte[] all = new byte[magic.length + payload.length];
        System.arraycopy(magic, 0, all, 0, magic.length);
        System.arraycopy(payload, 0, all, magic.length, payload.length);
        return all;
    }

    @Test
    @DisplayName("识别 SILK（含 0x02 前缀）/ WAV / MP3")
    void detectsFormats() {
        assertThat(SilkDecoder.isSilk(silkBytes())).isTrue();
        assertThat(SilkDecoder.isSilk(new byte[]{2, '#', '!', 'S', 'I', 'L', 'K', '_', 'V', '3'})).isTrue();
        assertThat(SilkDecoder.isSilk("RIFFxxxx".getBytes(StandardCharsets.US_ASCII))).isFalse();
        assertThat(SilkDecoder.isWav("RIFF____WAVE".getBytes(StandardCharsets.US_ASCII))).isTrue();
        assertThat(SilkDecoder.isMp3(new byte[]{(byte) 0xFF, (byte) 0xFB, 0x10})).isTrue();
    }

    @Test
    @DisplayName("命令拆分支持带空格的引号路径")
    void splitsQuotedCommand() {
        assertThat(SilkDecoder.split("\"C:\\Program Files\\nodejs\\node.exe\" script.mjs"))
                .containsExactly("C:\\Program Files\\nodejs\\node.exe", "script.mjs");
        assertThat(SilkDecoder.split("   ")).isEmpty();
        assertThat(SilkDecoder.split(null)).isEmpty();
    }

    @Test
    @DisplayName("WAV 原样透传，不再解码")
    void passesWavThrough() {
        byte[] wav = "RIFF____WAVEfmt ".getBytes(StandardCharsets.US_ASCII);
        SilkDecoder decoder = new SilkDecoder(properties(""), new ObjectMapper());

        assertThat(decoder.toWav(wav)).isEqualTo(wav);
    }

    @Test
    @DisplayName("JSON 进 JSON 出的解码器：SILK 进去，WAV 出来")
    void decodesWithJsonProtocol() {
        AsrProperties properties = properties(javaCommand(""));
        SilkDecoder decoder = new SilkDecoder(properties, new ObjectMapper());

        byte[] wav = decoder.toWav(silkBytes());

        assertThat(wav).isEqualTo(silkBytes());
        assertThat(decoder.backend()).contains("FakeSilkDecoder");
    }

    @Test
    @DisplayName("文件模式：{input}/{output} 占位符被替换成真实临时文件")
    void decodesWithFileMode() {
        AsrProperties properties = properties(javaCommand(" {input} {output}"));
        SilkDecoder decoder = new SilkDecoder(properties, new ObjectMapper());

        assertThat(decoder.toWav(silkBytes())).isEqualTo(silkBytes());
    }

    @Test
    @DisplayName("解码命令不可用时给出清楚的 SILK 相关错误，而不是抛 NPE")
    void reportsMissingCommand() {
        SilkDecoder decoder = new SilkDecoder(properties("definitely-not-a-real-binary-xyz"), new ObjectMapper());

        assertThatThrownBy(() -> decoder.toWav(silkBytes()))
                .isInstanceOf(AsrException.class)
                .hasMessageContaining("SILK");
    }

    @Test
    @DisplayName("不是 SILK 也不是 WAV 的字节直接判定不支持")
    void rejectsUnknownAudio() {
        SilkDecoder decoder = new SilkDecoder(properties(""), new ObjectMapper());

        assertThatThrownBy(() -> decoder.toWav("hello".getBytes(StandardCharsets.US_ASCII)))
                .isInstanceOf(AsrException.class)
                .hasMessageContaining("不是 SILK");
    }
}
