package com.example.wechatbot.tts;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** PCM（16-bit 小端、单声道）加个 WAV 头，方便浏览器 / ffmpeg / 微信直接播。 */
public final class WavFormat {

    private WavFormat() {
    }

    public static byte[] wrapPcm16(byte[] pcm, int sampleRate, int channels) {
        int dataSize = pcm == null ? 0 : pcm.length;
        int blockAlign = Math.max(1, channels) * 2;
        ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        header.put("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        header.putInt(36 + dataSize);
        header.put("WAVE".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        header.put("fmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        header.putInt(16);                       // fmt 块长度
        header.putShort((short) 1);              // PCM
        header.putShort((short) Math.max(1, channels));
        header.putInt(sampleRate);
        header.putInt(sampleRate * blockAlign);  // 字节率
        header.putShort((short) blockAlign);     // 块对齐
        header.putShort((short) 16);             // 位深
        header.put("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        header.putInt(dataSize);

        ByteArrayOutputStream out = new ByteArrayOutputStream(44 + dataSize);
        out.writeBytes(header.array());
        if (dataSize > 0) {
            out.writeBytes(pcm);
        }
        return out.toByteArray();
    }

    /** PCM 语音时长（毫秒）。 */
    public static int durationMillis(int pcmBytes, int sampleRate, int channels) {
        int bytesPerSecond = Math.max(1, sampleRate) * Math.max(1, channels) * 2;
        return (int) Math.round(pcmBytes * 1000.0 / bytesPerSecond);
    }
}
