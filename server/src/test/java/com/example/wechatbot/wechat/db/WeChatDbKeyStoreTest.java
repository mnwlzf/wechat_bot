package com.example.wechatbot.wechat.db;

import com.example.wechatbot.config.WeChatDbProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WeChatDbKeyStoreTest {
    private static final String KEY = "0f1e2d3c4b5a69788796a5b4c3d2e1f00f1e2d3c4b5a69788796a5b4c3d2e1f0";
    private static final String OTHER = "ffeeddccbbaa99887766554433221100ffeeddccbbaa99887766554433221100";

    @TempDir
    Path tempDir;

    private WeChatDbProperties properties;

    @BeforeEach
    void setUp() {
        properties = new WeChatDbProperties();
        properties.setKeyFile(tempDir.resolve("wechat-db-key.txt").toString());
        // 没有微信数据目录：check() 不该因为「找不到库」而报错
        properties.setDataDir(tempDir.resolve("no-such-dir").toString());
    }

    private WeChatDbKeyStore store() {
        return new WeChatDbKeyStore(properties, new WeChatDataDirs(properties));
    }

    @Test
    @DisplayName("没配密钥时不可用，但也不算「坏了」")
    void nothingConfigured() {
        WeChatDbKeyStore store = store();

        assertThat(store.configured()).isFalse();
        assertThat(store.source()).isEqualTo("none");
        assertThat(store.masked()).isEmpty();
        WeChatDbKeyStore.Check check = store.check();
        assertThat(check.configured()).isFalse();
        assertThat(check.error()).isNotBlank();
    }

    @Test
    @DisplayName("只配了环境变量时以 env 为源")
    void usesEnvKey() {
        properties.setKey(KEY);
        WeChatDbKeyStore store = store();

        assertThat(store.key()).isEqualTo(KEY);
        assertThat(store.source()).isEqualTo("env");
        assertThat(store.masked()).isEqualTo("0f1e****e1f0");
    }

    @Test
    @DisplayName("密钥文件优先于环境变量，重写文件后立刻生效（不用重启）")
    void fileWinsAndHotReloads() throws IOException {
        properties.setKey(OTHER);
        WeChatDbKeyStore store = store();
        assertThat(store.source()).isEqualTo("env");

        store.save(KEY);
        assertThat(store.source()).isEqualTo("file");
        assertThat(store.key()).isEqualTo(KEY);

        // 外部把文件改掉（模拟 watchdog 写入）：mtime 变了就该重新加载
        Path file = store.keyFile();
        Files.writeString(file, OTHER + "\n");
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(
                System.currentTimeMillis() + 5_000L));
        assertThat(store.key()).isEqualTo(OTHER);
    }

    @Test
    @DisplayName("文件内容不是 64 位 hex 时忽略，退回环境变量")
    void ignoresBrokenFile() throws IOException {
        properties.setKey(KEY);
        Files.writeString(Path.of(properties.getKeyFile()), "not-a-key\n");
        WeChatDbKeyStore store = store();

        assertThat(store.key()).isEqualTo(KEY);
        assertThat(store.source()).isEqualTo("env");
    }

    @Test
    @DisplayName("save() 只接受 64 位 hex")
    void rejectsInvalidKey() {
        WeChatDbKeyStore store = store();

        assertThatThrownBy(() -> store.save("abc")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.save(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("保存后的文件权限内容就是一行密钥")
    void savesSingleLine() throws IOException {
        WeChatDbKeyStore store = store();
        store.save(KEY);

        assertThat(Files.readString(store.keyFile()).trim()).isEqualTo(KEY);
        assertThat(store.keyFile()).exists();
    }
}
