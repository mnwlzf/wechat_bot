package com.example.wechatbot.wechat;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.util.Locale;

/**
 * 微信本地图片 {@code .dat} 的解码器（V1 未加密 / V2 AES-128-ECB 两种格式）。
 *
 * <p>V2 文件形如：{@code 07 08 56 32 08 07} 开头，随后是长度、标志等头部字节，正文是
 * 用消息 XML 里 {@code <img aeskey="...">} 给出的 16 字节密钥做的 AES-128-ECB 密文。
 * 头部到底多少字节随版本变化，所以这里不写死偏移：把每个可能对齐的偏移都试一遍，
 * 只接受「解出来开头是合法图片魔数」的结果，解不开就返回 {@code null} 由调用方降级。</p>
 */
public final class WeChatDatDecoder {
    /** 头部最多这么长；再往后就不是头部而是正文了。 */
    private static final int MAX_HEADER = 64;
    private static final byte[] MAGIC_JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] MAGIC_PNG = {(byte) 0x89, 0x50, 0x4E, 0x47};
    private static final byte[] MAGIC_GIF = {0x47, 0x49, 0x46};
    private static final byte[] MAGIC_BMP = {0x42, 0x4D};
    private static final byte[] MAGIC_WEBP = {0x52, 0x49, 0x46, 0x46};

    private WeChatDatDecoder() {}

    /** 是否是图片字节（用于判断解码结果是否可信）。 */
    public static boolean looksLikeImage(byte[] data) {
        return imageMagic(data) != null;
    }

    /**
     * 把 {@code .dat} 内容解成图片字节。
     *
     * @param dat       文件原始字节
     * @param aesKeyHex 消息 XML 里的 {@code aeskey}（32 位 hex）；为空时只能处理未加密文件
     * @return 图片字节；解不出来返回 {@code null}
     */
    public static byte[] decode(byte[] dat, String aesKeyHex) {
        if (dat == null || dat.length < 8) {
            return null;
        }
        if (looksLikeImage(dat)) {
            return dat;
        }
        byte[] key = parseKey(aesKeyHex);
        if (key == null) {
            return null;
        }
        for (int offset = 0; offset <= MAX_HEADER && offset < dat.length; offset++) {
            int payloadLength = dat.length - offset;
            if (payloadLength <= 0 || payloadLength % 16 != 0) {
                continue;
            }
            byte[] plain = decryptEcb(dat, offset, payloadLength, key);
            if (plain != null && looksLikeImage(plain)) {
                return trimJpegPadding(plain);
            }
        }
        return null;
    }

    private static byte[] decryptEcb(byte[] data, int offset, int length, byte[] key) {
        try {
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"));
            return cipher.doFinal(data, offset, length);
        } catch (Exception ex) {
            return null;
        }
    }

    /** 有些版本的密文尾部会带额外填充，JPEG 以 FFD9 结束，按最后一个 FFD9 截断最稳。 */
    private static byte[] trimJpegPadding(byte[] data) {
        if (imageMagic(data) != MAGIC_JPEG) {
            return data;
        }
        for (int i = data.length - 2; i > 0; i--) {
            if ((data[i] & 0xFF) == 0xFF && (data[i + 1] & 0xFF) == 0xD9) {
                if (i + 2 == data.length) {
                    return data;
                }
                byte[] trimmed = new byte[i + 2];
                System.arraycopy(data, 0, trimmed, 0, i + 2);
                return trimmed;
            }
        }
        return data;
    }

    private static byte[] imageMagic(byte[] data) {
        if (data == null) {
            return null;
        }
        if (startsWith(data, MAGIC_JPEG)) {
            return MAGIC_JPEG;
        }
        if (startsWith(data, MAGIC_PNG)) {
            return MAGIC_PNG;
        }
        if (startsWith(data, MAGIC_GIF)) {
            return MAGIC_GIF;
        }
        if (startsWith(data, MAGIC_BMP)) {
            return MAGIC_BMP;
        }
        if (data.length >= 12 && startsWith(data, MAGIC_WEBP)
                && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P') {
            return MAGIC_WEBP;
        }
        return null;
    }

    private static boolean startsWith(byte[] data, byte[] magic) {
        if (data.length < magic.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if (data[i] != magic[i]) {
                return false;
            }
        }
        return true;
    }

    private static byte[] parseKey(String aesKeyHex) {
        if (aesKeyHex == null) {
            return null;
        }
        String hex = aesKeyHex.trim().toLowerCase(Locale.ROOT);
        if (!hex.matches("[0-9a-f]{16,64}") || hex.length() % 2 != 0) {
            return null;
        }
        byte[] key = new byte[hex.length() / 2];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return switch (key.length) {
            case 16, 24, 32 -> key;
            default -> null;
        };
    }

    /** 仅供测试：把明文按给定密钥加密成 V2 风格的文件内容。 */
    static byte[] encodeForTest(byte[] plain, byte[] key, int headerLength) {
        try {
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
            byte[] encrypted = cipher.doFinal(plain);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] header = new byte[headerLength];
            header[0] = 0x07;
            header[1] = 0x08;
            header[2] = 'V';
            header[3] = '2';
            header[4] = 0x08;
            header[5] = 0x07;
            out.write(header, 0, header.length);
            out.write(encrypted, 0, encrypted.length);
            return out.toByteArray();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
