package com.example.wechatbot.wechat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class WeChatDatDecoderTest {

    private static final byte[] AES_KEY = "d94aa3a46668dda5".getBytes(StandardCharsets.US_ASCII);
    private static final int XOR_KEY = 238;

    @TempDir
    Path temp;

    @Test
    void detectsV2Container() {
        byte[] dat = WeChatDatDecoder.encodeForTest(payload(64), AES_KEY, XOR_KEY);

        assertThat(WeChatDatDecoder.isV2(dat)).isTrue();
        assertThat(WeChatDatDecoder.isV2(new byte[]{1, 2, 3})).isFalse();
        assertThat(WeChatDatDecoder.isV2(payload(64))).isFalse();
    }

    @Test
    void roundTripsSmallPayload() {
        byte[] plain = payload(300);

        byte[] dat = WeChatDatDecoder.encodeForTest(plain, AES_KEY, XOR_KEY);

        assertThat(WeChatDatDecoder.decode(dat, new WeChatDatDecoder.Key(1, AES_KEY, XOR_KEY)))
                .isEqualTo(plain);
    }

    @Test
    void roundTripsPayloadSpanningAesAndXorSegments() {
        // 超过 1024 字节才会走到「尾部单字节 XOR」分支
        byte[] plain = payload(9_000);

        byte[] dat = WeChatDatDecoder.encodeForTest(plain, AES_KEY, XOR_KEY);
        byte[] decoded = WeChatDatDecoder.decode(dat, new WeChatDatDecoder.Key(1, AES_KEY, XOR_KEY));

        assertThat(decoded).isEqualTo(plain);
        assertThat(decoded).hasSize(9_000);
    }

    @Test
    void wrongKeyIsRejectedInsteadOfReturningGarbage() {
        byte[] dat = WeChatDatDecoder.encodeForTest(payload(2_000), AES_KEY, XOR_KEY);
        byte[] wrong = "0123456789abcdef".getBytes(StandardCharsets.US_ASCII);

        assertThat(WeChatDatDecoder.decode(dat, new WeChatDatDecoder.Key(1, wrong, XOR_KEY))).isNull();
    }

    @Test
    void v1PlaintextImagePassesThrough() {
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10, 'J', 'F', 'I', 'F'};

        assertThat(WeChatDatDecoder.decode(jpeg, null)).isEqualTo(jpeg);
        assertThat(WeChatDatDecoder.decode(new byte[]{1, 2, 3, 4, 5, 6, 7, 8}, null)).isNull();
    }

    @Test
    void recognisesImageMagicAndWxgf() {
        assertThat(WeChatDatDecoder.looksLikeImage(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0})).isTrue();
        assertThat(WeChatDatDecoder.looksLikeImage(new byte[]{(byte) 0x89, 'P', 'N', 'G'})).isTrue();
        assertThat(WeChatDatDecoder.looksLikeImage("wxgf\u0013\u0000\u0002\u0005".getBytes(StandardCharsets.ISO_8859_1)))
                .isFalse();
        assertThat(WeChatDatDecoder.isWxgf("wxgf\u0013\u0000\u0002\u0005".getBytes(StandardCharsets.ISO_8859_1)))
                .isTrue();
        assertThat(WeChatDatDecoder.isWxgf(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF})).isFalse();
    }

    @Test
    void keyCarriesCodeAndXorByte() throws Exception {
        Files.writeString(temp.resolve("key_2067883758_1.statistic"), "x");

        WeChatDatDecoder.Key key = WeChatDatDecoder.localKeys(temp, "wxid_ivk48u4eu9rs22").get(0);

        assertThat(key.code()).isEqualTo(2067883758L);
        assertThat(key.xorKey()).isEqualTo(2067883758 & 0xFF);
        assertThat(new String(key.aesKey(), StandardCharsets.US_ASCII))
                .isEqualTo(md5("2067883758wxid_ivk48u4eu9rs22").substring(0, 16));
    }

    @Test
    void derivesKeysEvenAfterKvcommFileRotation() throws Exception {
        // 实测 .statistic 会被轮转成 .monitor，甚至只剩 monitordata_<code>_…；
        // 所以不认死文件名格式，只要数字候选里包含真正的 code 即可
        Files.writeString(temp.resolve("key_2067883758_4065597979_1_1793345110_21248_600_input.monitor"), "x");
        Files.writeString(temp.resolve("monitordata_2067883758_20756"), "x");

        List<String> aesKeys = WeChatDatDecoder.localKeys(temp, "wxid_ivk48u4eu9rs22_dc7e").stream()
                .map(key -> new String(key.aesKey(), StandardCharsets.US_ASCII))
                .toList();

        assertThat(aesKeys)
                .contains(md5("2067883758wxid_ivk48u4eu9rs22").substring(0, 16))
                .contains(md5("2067883758wxid_ivk48u4eu9rs22_dc7e").substring(0, 16));
    }

    @Test
    void noKvcommMeansNoKeys() throws Exception {
        assertThat(WeChatDatDecoder.localKeys(temp, "wxid_any")).isEmpty();
        assertThat(WeChatDatDecoder.localKeys(temp.resolve("missing"), "wxid_any")).isEmpty();
        assertThat(WeChatDatDecoder.localKeys(temp, "")).isEmpty();
    }

    /** 用派生出来的密钥真实解密一遍，确认两端一致。 */
    @Test
    void derivedKeyDecryptsEncodedFile() throws Exception {
        Files.writeString(temp.resolve("key_2067883758_1.statistic"), "x");
        byte[] plain = payload(3_000);
        WeChatDatDecoder.Key key = WeChatDatDecoder.localKeys(temp, "wxid_ivk48u4eu9rs22").get(0);

        byte[] dat = WeChatDatDecoder.encodeForTest(plain, key.aesKey(), key.xorKey());

        assertThat(WeChatDatDecoder.decode(dat, key)).isEqualTo(plain);
    }

    private static byte[] payload(int size) {
        byte[] bytes = new byte[size];
        new Random(size).nextBytes(bytes);
        // 以 JPEG 魔数开头，贴近真实文件
        bytes[0] = (byte) 0xFF;
        bytes[1] = (byte) 0xD8;
        bytes[2] = (byte) 0xFF;
        return bytes;
    }

    private static String md5(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder builder = new StringBuilder();
        for (byte b : digest) {
            builder.append(String.format("%02x", b));
        }
        return builder.toString();
    }
}
