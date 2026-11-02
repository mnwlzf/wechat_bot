package com.example.wechatbot.wechat.db;

import com.example.wechatbot.config.WeChatDbProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 用一份自己加密出来的 SQLCipher 库当样本（{@code src/test/resources/wechat}）：
 * 页格式、密钥派生、B-tree 解析、按 {@code svr_id} 取语音都走真实代码路径。
 */
class WeChatDbTest {
    private static final String KEY = "0f1e2d3c4b5a69788796a5b4c3d2e1f00f1e2d3c4b5a69788796a5b4c3d2e1f0";
    private static final String WRONG_KEY = "ffeeddccbbaa99887766554433221100ffeeddccbbaa99887766554433221100";

    @TempDir
    Path tempDir;

    private Path root;

    @BeforeEach
    void copyFixture() throws IOException {
        Path messageDir = tempDir.resolve("db_storage").resolve("message");
        Files.createDirectories(messageDir);
        try (InputStream in = getClass().getResourceAsStream("/wechat/db_storage/message/media_0.db")) {
            assertThat(in).as("测试样本缺失").isNotNull();
            Files.copy(in, messageDir.resolve("media_0.db"), StandardCopyOption.REPLACE_EXISTING);
        }
        root = tempDir;
    }

    private Path shard() {
        return root.resolve("db_storage").resolve("message").resolve("media_0.db");
    }

    private WeChatDbProperties properties(String key) {
        WeChatDbProperties properties = new WeChatDbProperties();
        properties.setEnabled(true);
        properties.setKey(key);
        properties.setDataDir(root.toString());
        return properties;
    }

    @Test
    @DisplayName("密钥对就能解开第 1 页，密钥错直接报错而不是给出乱码")
    void opensOnlyWithTheRightKey() throws IOException {
        SqlCipherFile file = SqlCipherFile.open(shard(), KEY, 4096);
        assertThat(file.pageCount()).isEqualTo(4);
        // 测试样本是普通 sqlite 库（头部保留字节 0），所以 usableSize = 4096；
        // 微信自己的库头部写 80，usableSize 就是 4016。
        assertThat(file.usableSize()).isEqualTo(4096);

        assertThatThrownBy(() -> SqlCipherFile.open(shard(), WRONG_KEY, 4096))
                .isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("能读出 VoiceInfo 的列名和全部行")
    void readsColumnsAndRows() throws IOException {
        SqliteReader reader = new SqliteReader(SqlCipherFile.open(shard(), KEY, 4096));

        assertThat(reader.columns("VoiceInfo"))
                .containsExactly("chat_name_id", "create_time", "local_id", "svr_id", "voice_data", "data_index");
        assertThat(reader.tables()).containsKeys("VoiceInfo", "Name2Id");

        List<Object[]> rows = reader.rows("VoiceInfo");
        assertThat(rows).hasSize(3);
        List<String> columns = reader.columns("VoiceInfo");
        Object svrId = SqliteReader.value(columns, rows.get(0), "svr_id");
        assertThat(svrId).isInstanceOf(Long.class);
    }

    @Test
    @DisplayName("按 svr_id（= 回调 msgid）精确定位到语音字节")
    void findsVoiceBySvrId() {
        WeChatVoiceStore store = new WeChatVoiceStore(properties(KEY));

        Optional<WeChatVoiceStore.Voice> voice = store.findBySvrId(8007476072464367941L);

        assertThat(voice).isPresent();
        assertThat(voice.get().bytes()).hasSize(119);
        assertThat(new String(voice.get().bytes(), 0, 9)).isEqualTo("#!SILK_V3");
        assertThat(voice.get().shard()).isEqualTo("media_0.db");
    }

    @Test
    @DisplayName("找不到 svr_id 时按消息时间在时间窗内兜底")
    void fallsBackToCreateTime() {
        WeChatVoiceStore store = new WeChatVoiceStore(properties(KEY));

        assertThat(store.findBySvrId(1L)).isEmpty();
        assertThat(store.findByCreateTime(1793547648L, 60)).isPresent();
        assertThat(store.findByCreateTime(1793547648L, 60).orElseThrow().bytes()).hasSize(450);
        assertThat(store.findByCreateTime(1L, 60)).isEmpty();
    }

    @Test
    @DisplayName("没配密钥时整体不可用，也不会去读文件")
    void disabledWithoutKey() {
        WeChatVoiceStore store = new WeChatVoiceStore(properties(""));

        assertThat(store.enabled()).isFalse();
        assertThat(store.findBySvrId(8007476072464367941L)).isEmpty();
        assertThat(store.shards()).hasSize(1);
    }

    @Test
    @DisplayName("自检信息里密钥只露头尾，能看到分片数与语音条数")
    void diagnosticsMasksKey() {
        WeChatVoiceStore store = new WeChatVoiceStore(properties(KEY));

        Map<String, Object> info = store.diagnostics();

        assertThat(info.get("configured")).isEqualTo(true);
        assertThat(info.get("maskedKey")).isEqualTo("0f1e****e1f0");
        assertThat(info.get("shards")).isEqualTo(1);
        assertThat(info.get("voiceRows")).isEqualTo(3);
        assertThat(info.get("errors")).asList().isEmpty();
    }

    @Test
    @DisplayName("CREATE TABLE 里的列名解析（含括号默认值、约束关键字）")
    void parsesColumnNames() {
        assertThat(SqliteReader.parseColumns(
                "CREATE TABLE t (a INTEGER PRIMARY KEY, b TEXT DEFAULT 'x,y', c BLOB, PRIMARY KEY (a))"))
                .containsExactly("a", "b", "c");
    }
}
