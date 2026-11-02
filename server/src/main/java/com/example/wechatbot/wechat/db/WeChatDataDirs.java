package com.example.wechatbot.wechat.db;

import com.example.wechatbot.config.WeChatDbProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 找微信 4.x 的数据目录：{@code <root>/<wxid>/db_storage/...}。
 *
 * <p>root 默认是 {@code %USERPROFILE%\Documents\xwechat_files}，可以用
 * {@code WECHAT_DB_DATA_DIR} 指定（填 root 或某个账号目录都行）。</p>
 */
@Component
public class WeChatDataDirs {
    private static final Logger log = LoggerFactory.getLogger(WeChatDataDirs.class);

    private final WeChatDbProperties properties;

    public WeChatDataDirs(WeChatDbProperties properties) {
        this.properties = properties;
    }

    /** 所有账号目录（含 {@code db_storage}）。 */
    public List<Path> accounts() {
        List<Path> accounts = new ArrayList<>();
        for (Path root : roots()) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            if (Files.isDirectory(root.resolve("db_storage"))) {
                accounts.add(root);
                continue;
            }
            try (Stream<Path> children = Files.list(root)) {
                children.filter(Files::isDirectory)
                        .filter(child -> Files.isDirectory(child.resolve("db_storage")))
                        .forEach(accounts::add);
            } catch (IOException ex) {
                log.debug("扫描微信数据目录失败: {} ({})", root, ex.getMessage());
            }
        }
        return accounts;
    }

    /** {@code db_storage/message} 目录（语音库就在这里）。 */
    public List<Path> messageDirs() {
        List<Path> dirs = new ArrayList<>();
        for (Path account : accounts()) {
            Path dir = account.resolve("db_storage").resolve("message");
            if (Files.isDirectory(dir)) {
                dirs.add(dir);
            }
        }
        return dirs;
    }

    /** {@code media_*.db} 分片。 */
    public List<Path> voiceShards() {
        List<Path> shards = new ArrayList<>();
        for (Path dir : messageDirs()) {
            try (Stream<Path> list = Files.list(dir)) {
                list.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().matches("media_\\d+\\.db"))
                        .sorted()
                        .forEach(shards::add);
            } catch (IOException ex) {
                log.debug("列出语音库失败: {} ({})", dir, ex.getMessage());
            }
        }
        return shards;
    }

    /** 拿一个库来验证密钥：优先语音库，其次通讯录。 */
    public Optional<Path> sampleDatabase() {
        List<Path> shards = voiceShards();
        if (!shards.isEmpty()) {
            return Optional.of(shards.get(0));
        }
        for (Path account : accounts()) {
            Path contact = account.resolve("db_storage").resolve("contact").resolve("contact.db");
            if (Files.isRegularFile(contact)) {
                return Optional.of(contact);
            }
        }
        return Optional.empty();
    }

    private List<Path> roots() {
        String configured = properties.getDataDir();
        if (configured != null && !configured.isBlank()) {
            return List.of(Path.of(configured.trim()));
        }
        String home = System.getProperty("user.home", "");
        if (home.isBlank()) {
            return List.of();
        }
        return List.of(Path.of(home, "Documents", "xwechat_files"));
    }
}
