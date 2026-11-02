package com.example.wechatbot.asr;

/** 测试用的最小 WAV：44 字节头 + 裸 PCM。 */
final class WavFixture {

    private WavFixture() {
    }

    static byte[] wav(int sampleRate, byte[] pcm) {
        byte[] out = new byte[44 + pcm.length];
        write(out, 0, "RIFF");
        writeInt(out, 4, 36 + pcm.length);
        write(out, 8, "WAVE");
        write(out, 12, "fmt ");
        writeInt(out, 16, 16);
        out[20] = 1;
        out[21] = 0;
        out[22] = 1;
        out[23] = 0;
        writeInt(out, 24, sampleRate);
        writeInt(out, 28, sampleRate * 2);
        out[32] = 2;
        out[33] = 0;
        out[34] = 16;
        out[35] = 0;
        write(out, 36, "data");
        writeInt(out, 40, pcm.length);
        System.arraycopy(pcm, 0, out, 44, pcm.length);
        return out;
    }

    private static void write(byte[] target, int offset, String ascii) {
        byte[] bytes = ascii.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, target, offset, bytes.length);
    }

    private static void writeInt(byte[] target, int offset, int value) {
        target[offset] = (byte) (value & 0xFF);
        target[offset + 1] = (byte) ((value >> 8) & 0xFF);
        target[offset + 2] = (byte) ((value >> 16) & 0xFF);
        target[offset + 3] = (byte) ((value >> 24) & 0xFF);
    }
}
