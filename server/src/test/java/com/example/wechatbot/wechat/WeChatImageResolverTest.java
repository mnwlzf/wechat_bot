package com.example.wechatbot.wechat;

import com.example.wechatbot.config.VisionProperties;
import com.example.wechatbot.config.WeChatHookProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WeChatImageResolverTest {

    private static final String CHAT_ID = "wxid_rsje67b7hozm22";
    private static final String MD5 = "afff3bac26f7e382439d4eba57a9f191";
    private static final String THUMB_MD5 = "89d861652a29abb6c5c9e8c188d62105";
    private static final String ACCOUNT_DIR = "wxid_me_dc7e";
    private static final long KV_CODE = 2067883758L;
    private static final String KV_FILE = "key_2067883758_4065597979_1_1793341387_22263_3600_input.statistic";
    private static final OffsetDateTime SENT_AT =
            OffsetDateTime.of(2026, 10, 30, 14, 39, 49, 0, ZoneOffset.ofHours(8));

    @TempDir
    Path root;

    private WeChatImageResolver resolver;
    private VisionProperties vision;
    private Path imgDir;

    @BeforeEach
    void setUp() throws Exception {
        WeChatHookProperties hookProperties = new WeChatHookProperties();
        hookProperties.setImageRoot(root.toString());
        vision = new VisionProperties();
        vision.setCacheDir(root.resolve("cache").toString());
        Path kvcomm = root.resolve("kvcomm");
        Files.createDirectories(kvcomm);
        Files.writeString(kvcomm.resolve(KV_FILE), "x");
        vision.setKvcommDir(kvcomm.toString());
        resolver = new WeChatImageResolver(hookProperties, vision);
        imgDir = root.resolve(ACCOUNT_DIR).resolve("msg").resolve("attach")
                .resolve(md5Hex(CHAT_ID)).resolve("2026-10").resolve("Img");
        Files.createDirectories(imgDir);
    }

    @Test
    void findsFileNamedAfterOriginalMd5() throws Exception {
        Path file = touch(MD5 + ".dat", SENT_AT);

        assertThat(resolver.candidates(CHAT_ID, SENT_AT, List.of(MD5))).containsExactly(file);
        assertThat(resolver.imageDirs(CHAT_ID, SENT_AT)).containsExactly(imgDir);
    }

    @Test
    void fallsBackToThumbMd5WhenFileNameUsesIt() throws Exception {
        // 实测：本地文件名用的是 cdnthumbmd5，不是 <img md5="...">
        Path file = touch(THUMB_MD5 + ".dat", SENT_AT);

        assertThat(resolver.candidates(CHAT_ID, SENT_AT, List.of(MD5, THUMB_MD5))).containsExactly(file);
    }

    @Test
    void findsThumbAndHdVariantOfSameImage() throws Exception {
        Path thumb = touch(THUMB_MD5 + "_t.dat", SENT_AT);
        Path hd = touch(THUMB_MD5 + "_h.dat", SENT_AT);

        assertThat(resolver.candidates(CHAT_ID, SENT_AT, List.of(THUMB_MD5))).containsExactly(hd, thumb);
    }

    @Test
    void matchesByTimeWindowWhenNoMd5Fits() throws Exception {
        touch("0123456789abcdef0123456789abcdef.dat", SENT_AT.minusSeconds(2));

        List<Path> found = resolver.candidates(CHAT_ID, SENT_AT, List.of(MD5));

        assertThat(found).hasSize(1);
        assertThat(found.get(0).getFileName().toString()).isEqualTo("0123456789abcdef0123456789abcdef.dat");
    }

    @Test
    void breaksTiesByPreferringHdCopyOverThumb() throws Exception {
        Path thumb = touch(THUMB_MD5 + "_t.dat", SENT_AT);
        Path full = touch(THUMB_MD5 + ".dat", SENT_AT);

        List<Path> found = resolver.candidates(CHAT_ID, SENT_AT, List.of(MD5));

        assertThat(found).containsExactly(full, thumb);
    }

    @Test
    void closerFileWinsOverDistantHdCopy() throws Exception {
        // 时间接近度优先：更远的 .dat 更可能是别的图片
        Path thumb = touch(THUMB_MD5 + "_t.dat", SENT_AT.minusSeconds(1));
        touch(THUMB_MD5 + ".dat", SENT_AT.minusSeconds(95));

        List<Path> found = resolver.candidates(CHAT_ID, SENT_AT, List.of(MD5));

        assertThat(found).hasSize(2);
        assertThat(found.get(0)).isEqualTo(thumb);
    }

    @Test
    void ignoresFilesOutsideTheWindow() throws Exception {
        touch("0123456789abcdef0123456789abcdef.dat", SENT_AT.minusDays(3));

        assertThat(resolver.candidates(CHAT_ID, SENT_AT, List.of(MD5))).isEmpty();
    }

    @Test
    void unknownChatHasNoDirectories() {
        assertThat(resolver.imageDirs("wxid_nobody", SENT_AT)).isEmpty();
        assertThat(resolver.candidates("wxid_nobody", SENT_AT, List.of(MD5))).isEmpty();
    }

    @Test
    void producesDataUrlForEncryptedImage() throws Exception {
        writeEncrypted(MD5 + ".dat", jpegBytes(160, 90));

        var dataUrl = resolver.toDataUrl(CHAT_ID, SENT_AT, List.of(MD5));

        assertThat(dataUrl).isPresent();
        assertThat(dataUrl.get().dataUrl()).startsWith("data:image/jpeg;base64,");
        byte[] decoded = Base64.getDecoder().decode(dataUrl.get().dataUrl().substring(dataUrl.get().dataUrl().indexOf(',') + 1));
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(decoded));
        assertThat(image).isNotNull();
        assertThat(image.getWidth()).isEqualTo(160);
        assertThat(image.getHeight()).isEqualTo(90);
    }

    @Test
    void skipsWxgfHdCopyAndFallsBackToThumbnail() throws Exception {
        // 高清是 wxgf(HEVC) 容器，JDK 解不了；缩略图是普通 JPEG
        byte[] wxgf = "wxgf\u0013\u0000\u0002\u0005payload".getBytes(StandardCharsets.ISO_8859_1);
        writeEncrypted(MD5 + ".dat", wxgf);
        writeEncrypted(MD5 + "_t.dat", jpegBytes(120, 80));

        var dataUrl = resolver.toDataUrl(CHAT_ID, SENT_AT, List.of(MD5));

        assertThat(dataUrl).isPresent();
        byte[] decoded = Base64.getDecoder().decode(dataUrl.get().dataUrl().substring(dataUrl.get().dataUrl().indexOf(',') + 1));
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(decoded));
        assertThat(image).isNotNull();
        assertThat(image.getWidth()).isEqualTo(120);
    }

    @Test
    void returnsEmptyWhenFileCannotBeDecoded() throws Exception {
        // 8 字节假文件：解不出来就安静降级，不抛异常
        touch(MD5 + ".dat", SENT_AT);

        assertThat(resolver.toDataUrl(CHAT_ID, SENT_AT, List.of(MD5))).isEmpty();
    }

    @Test
    void returnsEmptyWhenNoLocalKeysAvailable() throws Exception {
        vision.setKvcommDir(root.resolve("no-kvcomm").toString());
        Files.deleteIfExists(root.resolve("kvcomm").resolve(KV_FILE));
        writeEncrypted(MD5 + ".dat", jpegBytes(80, 60));

        assertThat(resolver.toDataUrl(CHAT_ID, SENT_AT, List.of(MD5))).isEmpty();
    }

    @Test
    void secondCallHitsTheDiskCache() throws Exception {
        writeEncrypted(MD5 + ".dat", jpegBytes(64, 48));

        assertThat(resolver.toDataUrl(CHAT_ID, SENT_AT, List.of(MD5))).isPresent();
        assertThat(resolver.toDataUrl(CHAT_ID, SENT_AT, List.of(MD5))).isPresent();
        try (var cached = Files.list(root.resolve("cache"))) {
            assertThat(cached.filter(path -> path.getFileName().toString().endsWith(".jpg")).count()).isEqualTo(1);
        }
    }

    @Test
    void prefersBubbleCopyOverThumbnail() throws Exception {
        // Img 下是 wxgf 高清 + _t 缩略图；cache 的气泡副本是更大的 JPEG
        writeEncrypted(MD5 + ".dat", "wxgf\u0013\u0000\u0002\u0005payload".getBytes(StandardCharsets.ISO_8859_1));
        writeEncrypted(MD5 + "_t.dat", jpegBytes(120, 80));
        Path bubbleDir = root.resolve(ACCOUNT_DIR).resolve("cache").resolve("2026-10").resolve("Message")
                .resolve(md5Hex(CHAT_ID)).resolve("Bubble");
        Files.createDirectories(bubbleDir);
        writeEncrypted(bubbleDir.resolve(MD5 + "_b.dat"), jpegBytes(500, 469));

        var dataUrl = resolver.toDataUrl(CHAT_ID, SENT_AT, List.of(MD5));

        assertThat(dataUrl).isPresent();
        assertThat(dataUrl.get().thumbnail()).isFalse();
        byte[] decoded = Base64.getDecoder().decode(dataUrl.get().dataUrl()
                .substring(dataUrl.get().dataUrl().indexOf(',') + 1));
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(decoded));
        assertThat(image).isNotNull();
        assertThat(image.getWidth()).isEqualTo(500);
    }

    @Test
    void thumbnailOnlyIsFlagged() throws Exception {
        writeEncrypted(MD5 + "_t.dat", jpegBytes(120, 80));

        var dataUrl = resolver.toDataUrl(CHAT_ID, SENT_AT, List.of(MD5));

        assertThat(dataUrl).isPresent();
        assertThat(dataUrl.get().thumbnail()).isTrue();
    }

    private Path touch(String name, OffsetDateTime at) throws Exception {
        Path file = imgDir.resolve(name);
        Files.write(file, new byte[]{0x07, 0x08, 0x56, 0x32, 0x08, 0x07, 0, 0});
        Files.setLastModifiedTime(file, FileTime.from(at.toInstant()));
        return file;
    }

    /** 用「测试 kvcomm 里那把密钥」按真实 V2 容器格式写一个加密文件。 */
    private void writeEncrypted(String name, byte[] plain) throws Exception {
        writeEncrypted(imgDir.resolve(name), plain);
    }

    private void writeEncrypted(Path target, byte[] plain) throws Exception {
        String keyHex = md5Hex(KV_CODE + ACCOUNT_DIR).substring(0, 16);
        byte[] aesKey = keyHex.getBytes(StandardCharsets.US_ASCII);
        Files.write(target, WeChatDatDecoder.encodeForTest(plain, aesKey, (int) (KV_CODE & 0xFF)));
    }

    private static byte[] jpegBytes(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.ORANGE);
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }

    private static String md5Hex(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder builder = new StringBuilder();
        for (byte b : digest) {
            builder.append(String.format("%02x", b));
        }
        return builder.toString();
    }
}
