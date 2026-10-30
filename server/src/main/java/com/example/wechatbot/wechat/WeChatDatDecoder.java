package com.example.wechatbot.wechat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 微信 4.x 本地图片（{@code .dat}）解密。
 *
 * <p>格式来自微信 4.x 的 V2 容器（与 weflow-cli 的实现一致）：</p>
 * <pre>
 * 偏移 0   ：魔数 07 08 56 32 08 07
 * 偏移 6   ：aes_size  (uint32 LE)  —— 前面这段走 AES
 * 偏移 10  ：xor_size  (uint32 LE)  —— 尾部这段只做单字节 XOR
 * 偏移 14  ：填充 1 字节
 * 偏移 15  ：ciphertext，长度 aes_size + 16 - aes_size % 16（PKCS5 补齐）
 * 之后     ：剩余明文字节，最后 xor_size 字节与 xorKey 异或
 * </pre>
 *
 * <p>关键点：**密钥不是消息报文里的 {@code aeskey}**。报文里的 aeskey 只用于从腾讯 CDN 下载，
 * 本机文件用的是每台机器/每个账号派生的密钥：
 * {@code md5(code + wxid)} 的十六进制前 16 位（ASCII 即 16 字节 AES-128 密钥），
 * 其中 {@code code} 来自 {@code %APPDATA%\Tencent\xwechat\net\kvcomm\key_<code>_*.statistic}
 * 的文件名，{@code wxid} 是去掉 {@code _dc7e} 这类后缀的账号 wxid；
 * 同一 {@code code} 的低 8 位就是 XOR 密钥。</p>
 *
 * <p>解码结果有两种：普通 JPEG/PNG（多为 {@code _t.dat} 缩略图），
 * 或微信自有的 {@code wxgf}（HEVC）容器（多为高清 {@code .dat}）。
 * 后者 JDK 无法解码，调用方应回退到缩略图。</p>
 */
public final class WeChatDatDecoder {
    private static final Logger log = LoggerFactory.getLogger(WeChatDatDecoder.class);

    private static final byte[] V2_MAGIC = {0x07, 0x08, 0x56, 0x32, 0x08, 0x07};
    private static final int V2_HEADER = 15;
    private static final Pattern KV_DIGITS = Pattern.compile("\\d{6,}");
    private static final Pattern WXID_SUFFIX = Pattern.compile("_[0-9a-z]{4}$", Pattern.CASE_INSENSITIVE);

    private WeChatDatDecoder() {}

    /** 一份本机密钥：{@code aesKey} 用于前段 AES，{@code xorKey} 用于尾部异或。 */
    public record Key(long code, byte[] aesKey, int xorKey) {}

    public static boolean isV2(byte[] data) {
        if (data == null || data.length < V2_HEADER) {
            return false;
        }
        for (int i = 0; i < V2_MAGIC.length; i++) {
            if (data[i] != V2_MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    /** 微信自有 HEVC 容器：JDK 的 ImageIO 读不了，需要外部解码器。 */
    public static boolean isWxgf(byte[] data) {
        return data != null && data.length >= 4
                && (data[0] == 'w' || data[0] == 'W')
                && (data[1] == 'x' || data[1] == 'X')
                && (data[2] == 'g' || data[2] == 'G')
                && (data[3] == 'f' || data[3] == 'F');
    }

    /** JPEG / PNG / GIF / BMP / WEBP 的魔数判断。 */
    public static boolean looksLikeImage(byte[] data) {
        if (data == null || data.length < 4) {
            return false;
        }
        if ((data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8 && (data[2] & 0xFF) == 0xFF) {
            return true;
        }
        if ((data[0] & 0xFF) == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G') {
            return true;
        }
        if (data[0] == 'G' && data[1] == 'I' && data[2] == 'F') {
            return true;
        }
        if (data[0] == 'B' && data[1] == 'M') {
            return true;
        }
        return data.length >= 12 && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
                && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P';
    }

    /**
     * 解一段 {@code .dat}。
     *
     * @return 明文字节（可能是 JPEG，也可能是 {@code wxgf}）；无法识别时返回 {@code null}
     */
    public static byte[] decode(byte[] data, Key key) {
        if (data == null) {
            return null;
        }
        if (!isV2(data)) {
            // V1：早期版本直接存明文
            return looksLikeImage(data) ? data : null;
        }
        int aesSize = readIntLE(data, 6);
        int xorSize = readIntLE(data, 10);
        if (aesSize < 0 || xorSize < 0) {
            return null;
        }
        int encryptedSize = aesSize + 16 - aesSize % 16;
        if (V2_HEADER + encryptedSize > data.length || xorSize > data.length - V2_HEADER - encryptedSize) {
            return null;
        }
        byte[] head = new byte[0];
        if (encryptedSize > 0) {
            if (key == null || key.aesKey() == null) {
                return null;
            }
            try {
                Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
                cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key.aesKey(), "AES"));
                head = cipher.doFinal(data, V2_HEADER, encryptedSize);
            } catch (Exception ex) {
                return null;
            }
        }
        int restStart = V2_HEADER + encryptedSize;
        int restLength = data.length - restStart;
        byte[] plain = new byte[head.length + restLength];
        System.arraycopy(head, 0, plain, 0, head.length);
        int split = restLength - xorSize;
        System.arraycopy(data, restStart, plain, head.length, split);
        for (int i = split; i < restLength; i++) {
            plain[head.length + i] = (byte) (data[restStart + i] ^ key.xorKey());
        }
        // 密钥不对时 PKCS5 也有约 1/256 的概率「解出」东西，用魔数把垃圾挡掉
        return looksLikeImage(plain) || isWxgf(plain) ? plain : null;
    }

    /**
     * 从 {@code kvcomm} 目录推本机密钥候选。
     *
     * <p>微信把 {@code code} 写在 kvcomm 下若干文件名里，但文件名后缀会变
     * （实测 {@code key_<code>_…_input.statistic} 会被轮转成 {@code …_input.monitor}，
     * 甚至只留 {@code monitordata_<code>_…}），所以这里不认死格式：
     * 把所有文件名里 6 位以上的数字都当候选 {@code code}，
     * 真正的密钥由调用方用「解出来是不是图片」来验证。</p>
     *
     * @param kvcommDir   {@code %APPDATA%\Tencent\xwechat\net\kvcomm}
     * @param accountWxid 账号 wxid（可带 {@code _dc7e} 之类后缀，这里会去掉）
     */
    public static List<Key> localKeys(Path kvcommDir, String accountWxid) {
        List<Key> keys = new ArrayList<>();
        if (kvcommDir == null || accountWxid == null || accountWxid.isBlank() || !Files.isDirectory(kvcommDir)) {
            return keys;
        }
        LinkedHashSet<Long> codes = new LinkedHashSet<>();
        try (Stream<Path> files = Files.list(kvcommDir)) {
            files.map(path -> path.getFileName().toString())
                    .sorted()
                    .forEach(name -> {
                        Matcher matcher = KV_DIGITS.matcher(name);
                        while (matcher.find()) {
                            try {
                                codes.add(Long.parseLong(matcher.group()));
                            } catch (NumberFormatException ignored) {
                                // 超长数字不是我们要的 code
                            }
                        }
                    });
        } catch (Exception ex) {
            log.debug("读取 kvcomm 目录失败: {} ({})", kvcommDir, ex.getMessage());
            return keys;
        }
        for (String wxid : wxids(accountWxid)) {
            for (long code : codes) {
                String hex = md5Hex(code + wxid);
                if (hex.length() < 16) {
                    continue;
                }
                keys.add(new Key(code, hex.substring(0, 16).getBytes(StandardCharsets.US_ASCII), (int) (code & 0xFF)));
            }
        }
        return keys;
    }

    /** 账号 wxid 的两种写法：原样 + 去掉 {@code _dc7e} 这类 4 位后缀。 */
    private static List<String> wxids(String accountWxid) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        String trimmed = accountWxid.trim();
        values.add(trimmed);
        String cleaned = WXID_SUFFIX.matcher(trimmed).replaceAll("");
        if (!cleaned.isBlank()) {
            values.add(cleaned);
        }
        return new ArrayList<>(values);
    }

    private static int readIntLE(byte[] data, int offset) {
        return (data[offset] & 0xFF)
                | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16)
                | ((data[offset + 3] & 0xFF) << 24);
    }

    private static String md5Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                builder.append(String.format(Locale.ROOT, "%02x", b));
            }
            return builder.toString();
        } catch (Exception ex) {
            return "";
        }
    }

    // ------------------------------------------------------------------ 测试辅助

    /** 按 V2 容器格式造一个加密文件，仅供单测使用。 */
    static byte[] encodeForTest(byte[] plain, byte[] aesKey, int xorKey) {
        int aesSize = Math.min(1024, plain.length);
        aesSize -= aesSize % 16;
        byte[] encrypted = new byte[0];
        if (aesSize > 0) {
            try {
                Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
                cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aesKey, "AES"));
                encrypted = cipher.doFinal(plain, 0, aesSize);
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        }
        int restLength = plain.length - aesSize;
        byte[] out = new byte[V2_HEADER + encrypted.length + restLength];
        System.arraycopy(V2_MAGIC, 0, out, 0, V2_MAGIC.length);
        writeIntLE(out, 6, aesSize);
        writeIntLE(out, 10, restLength);
        out[14] = 0x01;
        System.arraycopy(encrypted, 0, out, V2_HEADER, encrypted.length);
        for (int i = 0; i < restLength; i++) {
            out[V2_HEADER + encrypted.length + i] = (byte) (plain[aesSize + i] ^ xorKey);
        }
        return out;
    }

    private static void writeIntLE(byte[] target, int offset, int value) {
        target[offset] = (byte) (value & 0xFF);
        target[offset + 1] = (byte) ((value >>> 8) & 0xFF);
        target[offset + 2] = (byte) ((value >>> 16) & 0xFF);
        target[offset + 3] = (byte) ((value >>> 24) & 0xFF);
    }
}
