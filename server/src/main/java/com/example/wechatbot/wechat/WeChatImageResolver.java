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
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 把微信图片消息变成模型能吃的 {@code data:image/jpeg;base64,...}。
 *
 * <p>微信收到的图片在本地是加密的 {@code .dat}（见 {@link WeChatDatDecoder}），路径形如
 * {@code xwechat_files/<wxid>/msg/attach/<会话 hash>/<yyyy-MM>/Img/<md5>.dat}。
 * 定位靠消息 XML 里的 {@code md5}，解密靠 {@code aeskey}：</p>
 * <ol>
 *   <li>先请 Hook 的 {@code /Decode_Pic} 解码（微信自己的解密例程，最准）；</li>
 *   <li>Hook 没产出文件时，用 {@link WeChatDatDecoder} 在本地 AES 解密兜底；</li>
 *   <li>两条路都失败就返回空，让调用方退化成 {@code [图片]} 占位文本。</li>
 * </ol>
 *
 * <p>任何异常都不外抛：图片识别属于增强能力，不能因为它失败而中断回复流程。</p>
 */
@Component
public class WeChatImageResolver {
    private static final Logger log = LoggerFactory.getLogger(WeChatImageResolver.class);
    private static final int MAX_CACHED_PATHS = 512;

    private final WeChatHookClient hookClient;
    private final WeChatHookProperties hookProperties;
    private final VisionProperties vision;

    /** md5 → 本地 .dat 路径，省掉重复的目录遍历。 */
    private final Map<String, Path> pathCache = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Path> eldest) {
                    return size() > MAX_CACHED_PATHS;
                }
            });

    public WeChatImageResolver(WeChatHookClient hookClient,
                               WeChatHookProperties hookProperties,
                               VisionProperties vision) {
        this.hookClient = hookClient;
        this.hookProperties = hookProperties;
        this.vision = vision;
    }

    public boolean enabled() {
        return vision.isEnabled();
    }

    /**
     * @param md5    消息 XML 里的图片 md5
     * @param aeskey 消息 XML 里的 aeskey
     * @return 可直接放进请求体的 data URL；拿不到图片时返回空
     */
    public Optional<String> toDataUrl(String md5, String aeskey) {
        if (!vision.isEnabled() || md5 == null || md5.isBlank()) {
            return Optional.empty();
        }
        try {
            Path cached = cacheFile(md5, aeskey);
            if (Files.isRegularFile(cached)) {
                return Optional.of(dataUrl(Files.readAllBytes(cached)));
            }
            Path dat = locate(md5).orElse(null);
            if (dat == null) {
                log.info("未找到图片本地文件，按占位文本处理: md5={}", md5);
                return Optional.empty();
            }
            byte[] image = decode(dat, aeskey);
            if (image == null) {
                log.info("图片解码失败，按占位文本处理: {}", dat.getFileName());
                return Optional.empty();
            }
            byte[] jpeg = shrink(image);
            if (jpeg == null) {
                log.info("图片无法重新编码，按占位文本处理: {}", dat.getFileName());
                return Optional.empty();
            }
            Files.createDirectories(cached.getParent());
            Files.write(cached, jpeg);
            log.info("图片已解码并缓存: md5={} {}KB -> {}KB", md5,
                    image.length / 1024, jpeg.length / 1024);
            return Optional.of(dataUrl(jpeg));
        } catch (Exception ex) {
            log.warn("图片准备失败（降级为占位文本）: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    /** 按 md5 在微信数据目录里找 {@code Img/<md5>.dat}。 */
    public Optional<Path> locate(String md5) {
        if (md5 == null || !md5.toLowerCase(Locale.ROOT).matches("[0-9a-f]{32}")) {
            return Optional.empty();
        }
        Path cached = pathCache.get(md5);
        if (cached != null && Files.isRegularFile(cached)) {
            return Optional.of(cached);
        }
        String fileName = md5.toLowerCase(Locale.ROOT) + ".dat";
        for (Path root : roots()) {
            Optional<Path> found = findUnder(root, fileName);
            if (found.isPresent()) {
                pathCache.put(md5, found.get());
                return found;
            }
        }
        return Optional.empty();
    }

    private Optional<Path> findUnder(Path root, String fileName) {
        if (!Files.isDirectory(root)) {
            return Optional.empty();
        }
        List<Path> attachDirs = new ArrayList<>();
        Path direct = root.resolve("msg").resolve("attach");
        if (Files.isDirectory(direct)) {
            attachDirs.add(direct);
        }
        try (Stream<Path> children = Files.list(root)) {
            children.filter(Files::isDirectory)
                    .map(child -> child.resolve("msg").resolve("attach"))
                    .filter(Files::isDirectory)
                    .forEach(attachDirs::add);
        } catch (Exception ex) {
            log.debug("遍历微信数据目录失败: {} ({})", root, ex.getMessage());
        }
        for (Path attach : attachDirs) {
            try (Stream<Path> walk = Files.walk(attach, 4)) {
                Optional<Path> hit = walk
                        .filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().equalsIgnoreCase(fileName))
                        .findFirst();
                if (hit.isPresent()) {
                    return hit;
                }
            } catch (Exception ex) {
                log.debug("扫描图片目录失败: {} ({})", attach, ex.getMessage());
            }
        }
        return Optional.empty();
    }

    private List<Path> roots() {
        String configured = hookProperties.getImageRoot();
        if (configured != null && !configured.isBlank()) {
            return List.of(Path.of(configured.trim()));
        }
        String home = System.getProperty("user.home", "");
        List<Path> defaults = new ArrayList<>();
        if (!home.isBlank()) {
            defaults.add(Path.of(home, "Documents", "xwechat_files"));
            defaults.add(Path.of(home, "Documents", "WeChat Files"));
        }
        return defaults;
    }

    /** Hook 优先，本地解密兜底。 */
    private byte[] decode(Path dat, String aeskey) {
        byte[] viaHook = decodeWithHook(dat);
        if (viaHook != null) {
            return viaHook;
        }
        try {
            return WeChatDatDecoder.decode(Files.readAllBytes(dat), aeskey);
        } catch (Exception ex) {
            log.debug("读取图片文件失败: {} ({})", dat, ex.getMessage());
            return null;
        }
    }

    private byte[] decodeWithHook(Path dat) {
        Path tmp = null;
        try {
            tmp = Files.createTempFile("wx-image-", ".jpg");
            Files.deleteIfExists(tmp);
            hookClient.decodePicture(dat.toString(), tmp.toString());
            long deadline = System.currentTimeMillis() + Math.max(0, vision.getDecodeWaitMs());
            while (System.currentTimeMillis() < deadline) {
                if (Files.isRegularFile(tmp) && Files.size(tmp) > 0) {
                    byte[] bytes = Files.readAllBytes(tmp);
                    if (WeChatDatDecoder.looksLikeImage(bytes)) {
                        return bytes;
                    }
                    return null;
                }
                Thread.sleep(100);
            }
            return null;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception ex) {
            log.debug("Hook 解码图片不可用: {}", ex.getMessage());
            return null;
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (Exception ignored) {
                    // 临时文件清不掉不影响主流程
                }
            }
        }
    }

    /** 缩放到长边上限内并统一转成 JPEG，控制请求体积。 */
    private byte[] shrink(byte[] image) {
        BufferedImage source;
        try {
            source = ImageIO.read(new ByteArrayInputStream(image));
        } catch (Exception ex) {
            source = null;
        }
        if (source == null) {
            // 解不出像素（少见格式），原样透传，让模型自己判断
            return image.length <= vision.getMaxBytes() ? image : null;
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

    private Path cacheFile(String md5, String aeskey) {
        return Path.of(vision.getCacheDir(), "img-" + sha1(md5 + "|" + (aeskey == null ? "" : aeskey)) + ".jpg");
    }

    private String dataUrl(byte[] bytes) {
        String mime = mimeOf(bytes);
        return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(bytes);
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

    private String sha1(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(40);
            for (byte b : digest) {
                builder.append(String.format(Locale.ROOT, "%02x", b));
            }
            return builder.toString();
        } catch (Exception ex) {
            return Integer.toHexString(value.hashCode());
        }
    }
}
