package com.example.wechatbot.wechat;

import com.example.wechatbot.config.VisionProperties;
import com.example.wechatbot.config.WeChatHookProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 把微信图片消息变成模型能吃的 {@code data:image/jpeg;base64,...}。
 *
 * <p>微信收到的图片在本地是加密的 {@code .dat}，路径固定为</p>
 * <pre>
 * xwechat_files/&lt;wxid&gt;/msg/attach/&lt;md5(会话 id)&gt;/&lt;yyyy-MM&gt;/Img/&lt;文件名&gt;.dat
 * </pre>
 *
 * <p>「文件名」用的是报文里某个 32 位 md5（原图 {@code md5} 或 {@code cdnthumbmd5}，不固定），
 * 因此按「原图 md5 → 缩略图 md5 → 同会话时间窗内的任何 .dat」三级回退定位；
 * 解密用 {@link WeChatDatDecoder} 的本机密钥（不是报文里的 aeskey）。
 * 高清副本可能是 {@code wxgf}（HEVC）容器，JDK 解不了，此时自动回退到 {@code _t.dat} 缩略图。</p>
 *
 * <p>任何异常都不外抛：图片识别属于增强能力，不能因为它失败而中断回复流程。</p>
 */
@Component
public class WeChatImageResolver {
    private static final Logger log = LoggerFactory.getLogger(WeChatImageResolver.class);
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");
    /** 同一张图在本地的几种副本，高清优先。 */
    private static final List<String> SUFFIXES = List.of(".dat", "_h.dat", "_t.dat");

    private final WeChatHookProperties hookProperties;
    private final VisionProperties vision;
    private final Map<String, List<WeChatDatDecoder.Key>> keyCache = new HashMap<>();

    public WeChatImageResolver(WeChatHookProperties hookProperties, VisionProperties vision) {
        this.hookProperties = hookProperties;
        this.vision = vision;
    }

    public boolean enabled() {
        return vision.isEnabled();
    }

    /**
     * 一张可以塞进模型请求的图片。
     *
     * @param dataUrl   {@code data:image/jpeg;base64,...}
     * @param thumbnail 是否只有缩略图（高清副本是 wxgf 解不了，或本地只有 {@code _t.dat}）
     */
    public record Attachment(String dataUrl, boolean thumbnail) {}

    /**
     * @param chatId        会话 id（wxid / xxx@chatroom），用来算出本地目录名
     * @param sentAt        消息时间，用于时间窗兜底匹配
     * @param md5Candidates 报文里出现的所有图片 md5（原图优先，其次缩略图）
     * @return 可直接放进请求体的 data URL；拿不到图片时返回空
     */
    public Optional<Attachment> toDataUrl(String chatId,
                                          OffsetDateTime sentAt,
                                          List<String> md5Candidates) {
        if (!vision.isEnabled()) {
            return Optional.empty();
        }
        try {
            List<Location> locations = locations(chatId, sentAt);
            if (locations.isEmpty()) {
                log.info("未找到图片目录，按占位文本处理: chat={} md5={} 根目录={}", chatId, md5Candidates, roots());
                return Optional.empty();
            }
            for (Location location : locations) {
                List<Path> files = candidatesIn(location.dir(), sentAt, md5Candidates);
                if (files.isEmpty()) {
                    continue;
                }
                log.info("图片候选文件 {} 个（{}）: {}", files.size(), location.dir(), files);
                List<WeChatDatDecoder.Key> keys = keysOf(location.accountWxid());
                for (Path dat : files) {
                    Path cached = cacheFile(dat);
                    if (Files.isRegularFile(cached)) {
                        return Optional.of(new Attachment(dataUrl(Files.readAllBytes(cached)), isThumb(dat)));
                    }
                    byte[] image = decode(dat, keys);
                    if (image == null) {
                        continue;
                    }
                    byte[] jpeg = shrink(image);
                    if (jpeg == null) {
                        log.info("图片无法转成 JPEG，跳过: {}", dat.getFileName());
                        continue;
                    }
                    Files.createDirectories(cached.getParent());
                    Files.write(cached, jpeg);
                    log.info("图片已解码并缓存: {} {}KB -> {}KB", dat.getFileName(),
                            image.length / 1024, jpeg.length / 1024);
                    return Optional.of(new Attachment(dataUrl(jpeg), isThumb(dat)));
                }
            }
            log.info("图片全部候选解码失败，按占位文本处理: chat={} md5={}", chatId, md5Candidates);
            return Optional.empty();
        } catch (Exception ex) {
            log.warn("图片准备失败（降级为占位文本）: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------------ 定位

    /** 会话对应的图片目录 + 该目录所属账号（用于推本机密钥）。 */
    record Location(Path dir, String accountWxid) {}

    /**
     * 按 md5 快路径 + 同会话时间窗兜底，挑出可能要试的 {@code .dat}。
     *
     * <p>package-private 便于单测直接注入临时目录验证。</p>
     */
    List<Path> candidates(String chatId, OffsetDateTime sentAt, List<String> md5Candidates) {
        LinkedHashSet<Path> found = new LinkedHashSet<>();
        for (Location location : locations(chatId, sentAt)) {
            found.addAll(candidatesIn(location.dir(), sentAt, md5Candidates));
        }
        return new ArrayList<>(found);
    }

    private List<Path> candidatesIn(Path dir, OffsetDateTime sentAt, List<String> md5Candidates) {
        LinkedHashSet<Path> found = new LinkedHashSet<>();
        if (md5Candidates != null) {
            for (String md5 : md5Candidates) {
                if (md5 == null || !md5.toLowerCase(Locale.ROOT).matches("[0-9a-f]{32}")) {
                    continue;
                }
                for (String suffix : SUFFIXES) {
                    Path path = dir.resolve(md5.toLowerCase(Locale.ROOT) + suffix);
                    if (Files.isRegularFile(path)) {
                        found.add(path);
                    }
                }
            }
        }
        if (found.isEmpty()) {
            found.addAll(byTimeWindow(dir, sentAt));
        }
        return new ArrayList<>(found);
    }

    /**
     * 文件名对不上时，退而求其次：同一会话目录里，消息时间前后 {@code matchWindowSeconds}
     * 内写盘的 {@code .dat}，按时间接近程度排序，同样接近时高清副本优先。
     */
    private List<Path> byTimeWindow(Path dir, OffsetDateTime sentAt) {
        long target = (sentAt == null ? OffsetDateTime.now() : sentAt).toInstant().toEpochMilli();
        long window = Math.max(30, vision.getMatchWindowSeconds()) * 1000L;
        List<Path> hits = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return hits;
        }
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".dat"))
                    .filter(path -> Math.abs(lastModified(path) - target) <= window)
                    .forEach(hits::add);
        } catch (Exception ex) {
            log.debug("扫描图片目录失败: {} ({})", dir, ex.getMessage());
        }
        hits.sort((left, right) -> {
            long deltaLeft = Math.abs(lastModified(left) - target);
            long deltaRight = Math.abs(lastModified(right) - target);
            if (deltaLeft != deltaRight) {
                return Long.compare(deltaLeft, deltaRight);
            }
            return Integer.compare(rank(left), rank(right));
        });
        return hits;
    }

    /** 高清副本排前面：{@code .dat} / {@code _h.dat} 优于 {@code _t.dat}。 */
    private static int rank(Path path) {
        return isThumb(path) ? 1 : 0;
    }

    private static boolean isThumb(Path path) {
        return path.getFileName().toString().endsWith("_t.dat");
    }

    /** 会话目录：{@code <root>/<wxid>/msg/attach/<md5(chatId)>/<yyyy-MM>/Img}，跨月时多看一个月。 */
    List<Path> imageDirs(String chatId, OffsetDateTime sentAt) {
        return locations(chatId, sentAt).stream().map(Location::dir).toList();
    }

    List<Location> locations(String chatId, OffsetDateTime sentAt) {
        List<Location> dirs = new ArrayList<>();
        if (chatId == null || chatId.isBlank()) {
            return dirs;
        }
        String hash = md5Hex(chatId);
        OffsetDateTime base = sentAt == null ? OffsetDateTime.now() : sentAt;
        List<String> months = List.of(base.format(MONTH), base.minusMonths(1).format(MONTH));
        for (Path root : roots()) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            List<Path> accounts = new ArrayList<>();
            if (Files.isDirectory(root.resolve("msg").resolve("attach"))) {
                accounts.add(root);
            }
            try (Stream<Path> children = Files.list(root)) {
                children.filter(Files::isDirectory).forEach(accounts::add);
            } catch (Exception ex) {
                log.debug("遍历微信数据目录失败: {} ({})", root, ex.getMessage());
            }
            for (Path account : accounts) {
                Path attach = account.resolve("msg").resolve("attach").resolve(hash);
                if (!Files.isDirectory(attach)) {
                    continue;
                }
                for (String month : months) {
                    Path img = attach.resolve(month).resolve("Img");
                    if (Files.isDirectory(img)) {
                        dirs.add(new Location(img, account.getFileName().toString()));
                    }
                }
            }
        }
        return dirs;
    }

    private List<Path> roots() {
        String configured = hookProperties.getImageRoot();
        if (configured != null && !configured.isBlank()) {
            return List.of(Path.of(configured.trim()));
        }
        String home = System.getProperty("user.home", "");
        if (home.isBlank()) {
            return List.of();
        }
        List<Path> defaults = new ArrayList<>();
        defaults.add(Path.of(home, "Documents", "xwechat_files"));
        defaults.add(Path.of(home, "Documents", "WeChat Files"));
        return defaults;
    }

    // ------------------------------------------------------------------ 解密

    /** 本机密钥按账号缓存：一次派生多把候选（不同 kvcomm code）。 */
    private List<WeChatDatDecoder.Key> keysOf(String accountWxid) {
        if (accountWxid == null || accountWxid.isBlank()) {
            return List.of();
        }
        return keyCache.computeIfAbsent(accountWxid, wxid -> {
            List<WeChatDatDecoder.Key> keys = WeChatDatDecoder.localKeys(kvcommDir(), wxid);
            if (keys.isEmpty()) {
                log.warn("未能从 kvcomm 推出图片解密密钥，图片将只保留占位文本: wxid={} kvcomm={}",
                        wxid, kvcommDir());
            } else {
                log.info("图片解密密钥就绪: wxid={} 候选 {} 把", wxid, keys.size());
            }
            return keys;
        });
    }

    private Path kvcommDir() {
        String configured = vision.getKvcommDir();
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured.trim());
        }
        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isBlank()) {
            return Path.of(appData, "Tencent", "xwechat", "net", "kvcomm");
        }
        String home = System.getProperty("user.home", "");
        return home.isBlank()
                ? Path.of("kvcomm")
                : Path.of(home, "AppData", "Roaming", "Tencent", "xwechat", "net", "kvcomm");
    }

    /** 用本机密钥解密；{@code wxgf}（HEVC）解不出来时返回 null，交给下一个候选（缩略图）。 */
    private byte[] decode(Path dat, List<WeChatDatDecoder.Key> keys) {
        byte[] raw;
        try {
            raw = Files.readAllBytes(dat);
        } catch (Exception ex) {
            log.debug("读取图片文件失败: {} ({})", dat, ex.getMessage());
            return null;
        }
        if (!WeChatDatDecoder.isV2(raw)) {
            return WeChatDatDecoder.looksLikeImage(raw) ? raw : null;
        }
        if (keys.isEmpty()) {
            return null;
        }
        for (WeChatDatDecoder.Key key : keys) {
            byte[] plain = WeChatDatDecoder.decode(raw, key);
            if (plain == null) {
                continue;
            }
            if (WeChatDatDecoder.looksLikeImage(plain)) {
                return plain;
            }
            if (WeChatDatDecoder.isWxgf(plain)) {
                log.info("图片是 wxgf(HEVC) 高清格式，JDK 无法解码，改用缩略图: {}", dat.getFileName());
                return null;
            }
        }
        log.info("图片解密失败（{} 把密钥都试过）: {}", keys.size(), dat.getFileName());
        return null;
    }

    /** 缩放到长边上限内并统一转成 JPEG；ImageIO 读不出来（如 wxgf）时返回 null。 */
    private byte[] shrink(byte[] image) {
        BufferedImage source;
        try {
            source = ImageIO.read(new ByteArrayInputStream(image));
        } catch (Exception ex) {
            source = null;
        }
        if (source == null) {
            return null;
        }
        BufferedImage scaled = scale(source);
        int maxBytes = Math.max(64_000, vision.getMaxBytes());
        for (float quality : new float[]{0.85f, 0.7f, 0.55f}) {
            byte[] jpeg = writeJpeg(scaled, quality);
            if (jpeg != null && jpeg.length <= maxBytes) {
                return jpeg;
            }
        }
        return null;
    }

    private BufferedImage scale(BufferedImage source) {
        int maxEdge = Math.max(128, vision.getMaxEdge());
        int width = source.getWidth();
        int height = source.getHeight();
        if (width <= maxEdge && height <= maxEdge) {
            return flatten(source);
        }
        double ratio = Math.min((double) maxEdge / width, (double) maxEdge / height);
        int targetWidth = Math.max(1, (int) Math.round(width * ratio));
        int targetHeight = Math.max(1, (int) Math.round(height * ratio));
        BufferedImage target = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, targetWidth, targetHeight);
            graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }

    /** JPEG 不支持透明通道，先铺白底，避免 PNG 透明区域变黑。 */
    private BufferedImage flatten(BufferedImage source) {
        if (source.getType() == BufferedImage.TYPE_INT_RGB) {
            return source;
        }
        BufferedImage target = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, source.getWidth(), source.getHeight());
            graphics.drawImage(source, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }

    private byte[] writeJpeg(BufferedImage image, float quality) {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            return null;
        }
        ImageWriter writer = writers.next();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ImageOutputStream output = ImageIO.createImageOutputStream(buffer)) {
            writer.setOutput(output);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(quality);
            }
            writer.write(null, new IIOImage(image, null, null), param);
            return buffer.toByteArray();
        } catch (Exception ex) {
            log.debug("JPEG 编码失败: {}", ex.getMessage());
            return null;
        } finally {
            writer.dispose();
        }
    }

    // ------------------------------------------------------------------ 杂项

    private Path cacheFile(Path dat) {
        String key = dat.getFileName() + "|" + fileSize(dat) + "|" + lastModified(dat);
        return Path.of(vision.getCacheDir(), "img-" + sha1(key) + ".jpg");
    }

    private static long fileSize(Path path) {
        try {
            return Files.size(path);
        } catch (Exception ex) {
            return -1;
        }
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (Exception ex) {
            return 0;
        }
    }

    private String dataUrl(byte[] bytes) {
        return "data:" + mimeOf(bytes) + ";base64," + Base64.getEncoder().encodeToString(bytes);
    }

    private String mimeOf(byte[] bytes) {
        if (bytes.length >= 4 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') {
            return "image/png";
        }
        if (bytes.length >= 3 && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F') {
            return "image/gif";
        }
        if (bytes.length >= 2 && bytes[0] == 'B' && bytes[1] == 'M') {
            return "image/bmp";
        }
        return "image/jpeg";
    }

    private static String md5Hex(String value) {
        return digest("MD5", value);
    }

    private static String sha1(String value) {
        return digest("SHA-1", value);
    }

    private static String digest(String algorithm, String value) {
        try {
            byte[] bytes = MessageDigest.getInstance(algorithm).digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                builder.append(String.format(Locale.ROOT, "%02x", b));
            }
            return builder.toString();
        } catch (Exception ex) {
            return Integer.toHexString(value.hashCode());
        }
    }
}
