package com.example.wechatbot.wechat.db;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

/**
 * 微信 4.x 的 SQLCipher 4 数据库读取器（只读、逐页解密）。
 *
 * <p>页格式（页大小 4096，SQLCipher 4 默认参数）：</p>
 * <pre>
 * 第 1 页： [salt 16][cipher 4000][iv 16][hmac-sha512 64]
 * 其余页：        [cipher 4016][iv 16][hmac-sha512 64]
 * </pre>
 * <p>HMAC 覆盖 {@code cipher || iv || 页号(LE32)}，密钥派生：</p>
 * <pre>
 * key     = PBKDF2-HMAC-SHA512(passphrase, salt, 256000, 32)
 * mac_key = PBKDF2-HMAC-SHA512(key, salt ^ 0x3a, 2, 32)
 * </pre>
 * <p>第 1 页的前 16 字节在文件里被 salt 顶掉了，明文解密后要把
 * {@code "SQLite format 3\0"} 补回去——它是 SQLite 头的前 16 字节。</p>
 */
public final class SqlCipherFile {
    private static final byte[] SQLITE_MAGIC = "SQLite format 3\0".getBytes(StandardCharsets.US_ASCII);
    private static final int KEY_BYTES = 32;
    private static final int SALT_BYTES = 16;
    private static final int IV_BYTES = 16;
    private static final int HMAC_BYTES = 64;
    /** SQLCipher 固定留在页尾的字节数：IV 16 + HMAC-SHA512 64。 */
    private static final int CIPHER_RESERVE = IV_BYTES + HMAC_BYTES;
    private static final int PBKDF2_ITERATIONS = 256000;
    private static final int MAC_ITERATIONS = 2;

    private final byte[] data;
    private final byte[] key;
    private final byte[] macKey;
    private final int pageSize;
    /** SQLite 头里声明的「每页保留字节数」（微信 4.x 是 80，等于 SQLCipher 的 IV+HMAC）。 */
    private int reserve = CIPHER_RESERVE;
    private final Map<Integer, byte[]> pageCache = new HashMap<>();

    private SqlCipherFile(byte[] data, byte[] key, byte[] macKey, int pageSize) {
        this.data = data;
        this.key = key;
        this.macKey = macKey;
        this.pageSize = pageSize;
    }

    /** 打开并校验第 1 页（HMAC 不对就说明密钥不匹配）。 */
    public static SqlCipherFile open(Path path, String passphraseHex, int pageSize) throws IOException {
        byte[] data = Files.readAllBytes(path);
        if (data.length < pageSize) {
            throw new IOException("数据库太小: " + data.length + " 字节");
        }
        byte[] passphrase = hex(passphraseHex);
        byte[] salt = new byte[SALT_BYTES];
        System.arraycopy(data, 0, salt, 0, SALT_BYTES);
        byte[] macSalt = new byte[SALT_BYTES];
        for (int i = 0; i < SALT_BYTES; i++) {
            macSalt[i] = (byte) (salt[i] ^ 0x3a);
        }
        byte[] key = pbkdf2(passphrase, salt, PBKDF2_ITERATIONS, KEY_BYTES);
        byte[] macKey = pbkdf2(key, macSalt, MAC_ITERATIONS, KEY_BYTES);
        SqlCipherFile file = new SqlCipherFile(data, key, macKey, pageSize);
        byte[] first;
        try {
            // SQLCipher 的加密布局固定按 80 字节保留区算，先按它解开第 1 页
            first = file.page(1);
        } catch (RuntimeException ex) {
            throw new IOException("打不开第 1 页（密钥不对或页大小不是 " + pageSize + "）: " + ex.getMessage(), ex);
        }
        if (first.length < SQLITE_MAGIC.length
                || !MessageDigest.isEqual(SQLITE_MAGIC, java.util.Arrays.copyOf(first, SQLITE_MAGIC.length))) {
            throw new IOException("解密后的第 1 页不是 SQLite 头，密钥可能不对");
        }
        // 头部第 20 字节声明每页保留多少字节（SQLite 的 usableSize = 页大小 - 保留字节）
        int declared = first[20] & 0xff;
        if (pageSize - declared < 480) {
            throw new IOException("SQLite 头里的保留字节数不合法: " + declared);
        }
        if (declared != file.reserve) {
            file.reserve = declared;
            file.pageCache.clear();
            first = file.page(1);
        }
        return file;
    }

    public int pageCount() {
        return data.length / pageSize;
    }

    public int usableSize() {
        return pageSize - reserve;
    }

    /**
     * 第 {@code pageNo} 页的明文（1 起），长度为 {@link #usableSize()}。
     *
     * <p>SQLCipher 的加密布局固定留 80 字节（IV 16 + HMAC-SHA512 64）在页尾；SQLite 自己
     * 声明的保留区可能是别的值，不够 80 就在明文尾部补零，够就截断——这样单元格偏移
     * 始终落在返回的这段区间里。</p>
     */
    public byte[] page(int pageNo) {
        byte[] cached = pageCache.get(pageNo);
        if (cached != null) {
            return cached;
        }
        int offset = (pageNo - 1) * pageSize;
        int bodyStart = offset + (pageNo == 1 ? SALT_BYTES : 0);
        int bodyLength = pageSize - CIPHER_RESERVE - (pageNo == 1 ? SALT_BYTES : 0);
        int ivOffset = bodyStart + bodyLength;
        int macOffset = ivOffset + IV_BYTES;

        byte[] iv = java.util.Arrays.copyOfRange(data, ivOffset, ivOffset + IV_BYTES);
        byte[] expected = java.util.Arrays.copyOfRange(data, macOffset, macOffset + HMAC_BYTES);
        byte[] macMessage = new byte[bodyLength + IV_BYTES + 4];
        System.arraycopy(data, bodyStart, macMessage, 0, bodyLength + IV_BYTES);
        macMessage[bodyLength + IV_BYTES] = (byte) pageNo;
        macMessage[bodyLength + IV_BYTES + 1] = (byte) (pageNo >>> 8);
        macMessage[bodyLength + IV_BYTES + 2] = (byte) (pageNo >>> 16);
        macMessage[bodyLength + IV_BYTES + 3] = (byte) (pageNo >>> 24);

        byte[] actual = hmac(macKey, macMessage);
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new IllegalStateException("第 " + pageNo + " 页 HMAC 校验失败（密钥或页大小不对）");
        }

        byte[] plain = aesCbc(Cipher.DECRYPT_MODE, key, iv, java.util.Arrays.copyOfRange(data, bodyStart, ivOffset));
        byte[] result;
        if (pageNo == 1) {
            result = new byte[plain.length + SALT_BYTES];
            System.arraycopy(SQLITE_MAGIC, 0, result, 0, SALT_BYTES);
            System.arraycopy(plain, 0, result, SALT_BYTES, plain.length);
        } else {
            result = plain;
        }
        int target = usableSize();
        if (result.length != target) {
            result = java.util.Arrays.copyOf(result, target);
        }
        pageCache.put(pageNo, result);
        return result;
    }

    /** 只验第 1 页的 HMAC，用来确认一个密钥对不对（不抛异常）。 */
    public static boolean verify(Path path, String passphraseHex, int pageSize) {
        try {
            open(path, passphraseHex, pageSize);
            return true;
        } catch (IOException | RuntimeException ex) {
            return false;
        }
    }

    /**
     * PBKDF2-HMAC-SHA512。
     *
     * <p>不用 {@code SecretKeyFactory("PBKDF2WithHmacSHA512")}：它会把口令按 UTF-8 重新编码，
     * 而 SQLCipher 用的是口令的原始字节（二进制密钥里常有 {@code >= 0x80} 的字节），
     * 走 JDK 那套算出来的密钥对不上。</p>
     */
    static byte[] pbkdf2(byte[] password, byte[] salt, int iterations, int length) {
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(password, "HmacSHA512"));
            byte[] result = new byte[length];
            int blocks = (length + 63) / 64;
            byte[] seed = new byte[salt.length + 4];
            System.arraycopy(salt, 0, seed, 0, salt.length);
            for (int block = 1; block <= blocks; block++) {
                seed[salt.length] = (byte) (block >>> 24);
                seed[salt.length + 1] = (byte) (block >>> 16);
                seed[salt.length + 2] = (byte) (block >>> 8);
                seed[salt.length + 3] = (byte) block;
                byte[] u = mac.doFinal(seed);
                byte[] accumulated = u.clone();
                for (int iteration = 1; iteration < iterations; iteration++) {
                    u = mac.doFinal(u);
                    for (int i = 0; i < accumulated.length; i++) {
                        accumulated[i] ^= u[i];
                    }
                }
                int offset = (block - 1) * 64;
                System.arraycopy(accumulated, 0, result, offset, Math.min(64, length - offset));
            }
            return result;
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HmacSHA512 不可用", ex);
        }
    }

    static byte[] hmac(byte[] key, byte[] message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(key, "HmacSHA512"));
            return mac.doFinal(message);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HmacSHA512 不可用", ex);
        }
    }

    static byte[] aesCbc(int mode, byte[] key, byte[] iv, byte[] input) {
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(mode, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            return cipher.doFinal(input);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("AES-256-CBC 不可用", ex);
        }
    }

    static byte[] hex(String value) {
        if (value == null || value.length() % 2 != 0) {
            throw new IllegalArgumentException("不是合法的 hex 字符串");
        }
        byte[] bytes = new byte[value.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }
}
