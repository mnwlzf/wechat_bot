package com.example.wechatbot.wechat;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WeChatDatDecoderTest {
    private static final String AES_KEY_HEX = "00112233445566778899aabbccddeeff";

    @Test
    void decryptsV2StyleFileWithAesKey() {
        byte[] plain = jpegStub(600);
        byte[] dat = WeChatDatDecoder.encodeForTest(plain, hex(AES_KEY_HEX), 16);

        assertThat(WeChatDatDecoder.decode(dat, AES_KEY_HEX)).isEqualTo(plain);
    }

    @Test
    void toleratesUnknownHeaderLength() {
        byte[] plain = pngStub(400);
        byte[] dat = WeChatDatDecoder.encodeForTest(plain, hex(AES_KEY_HEX), 40);

        assertThat(WeChatDatDecoder.decode(dat, AES_KEY_HEX)).isEqualTo(plain);
    }

    @Test
    void returnsNullForWrongKey() {
        byte[] dat = WeChatDatDecoder.encodeForTest(jpegStub(300), hex(AES_KEY_HEX), 16);

        assertThat(WeChatDatDecoder.decode(dat, "ffeeddccbbaa99887766554433221100")).isNull();
    }

    @Test
    void returnsNullWhenKeyMissing() {
        byte[] dat = WeChatDatDecoder.encodeForTest(jpegStub(300), hex(AES_KEY_HEX), 16);

        assertThat(WeChatDatDecoder.decode(dat, null)).isNull();
        assertThat(WeChatDatDecoder.decode(dat, "")).isNull();
    }

    @Test
    void passesThroughUnencryptedImage() {
        byte[] plain = jpegStub(200);

        assertThat(WeChatDatDecoder.decode(plain, AES_KEY_HEX)).isEqualTo(plain);
        assertThat(WeChatDatDecoder.looksLikeImage(plain)).isTrue();
    }

    @Test
    void garbageIsNotAnImage() {
        assertThat(WeChatDatDecoder.looksLikeImage(new byte[]{0x07, 0x08, 0x56, 0x32})).isFalse();
    }

    private static byte[] jpegStub(int length) {
        byte[] data = new byte[Math.max(8, length)];
        data[0] = (byte) 0xFF;
        data[1] = (byte) 0xD8;
        data[2] = (byte) 0xFF;
        for (int i = 3; i < data.length - 2; i++) {
            data[i] = (byte) (i % 251);
        }
        data[data.length - 2] = (byte) 0xFF;
        data[data.length - 1] = (byte) 0xD9;
        return data;
    }

    private static byte[] pngStub(int length) {
        byte[] data = new byte[Math.max(8, length)];
        data[0] = (byte) 0x89;
        data[1] = 0x50;
        data[2] = 0x4E;
        data[3] = 0x47;
        for (int i = 4; i < data.length; i++) {
            data[i] = (byte) (i % 241);
        }
        return data;
    }

    private static byte[] hex(String value) {
        byte[] bytes = new byte[value.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }
}
