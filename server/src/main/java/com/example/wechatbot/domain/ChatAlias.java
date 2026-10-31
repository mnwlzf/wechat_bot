package com.example.wechatbot.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 会话备注：给微信客户端里读不到名字的会话（群聊、未同步的联系人）起一个本机名字。
 *
 * <p>微信 4.x 的 {@code contact.db} 是 SQLCipher 加密的，预编译 Hook 的 {@code QueryDB}
 * 又是空的（源码里 {@code g_IsLogin} 恒为 0），所以“按昵称搜索”必须允许人工补名字：
 * 存一次，之后搜备注、搜名字都能命中，白名单里也显示得出来。</p>
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "chat_alias")
public class ChatAlias {
    @Id
    @Column(name = "chat_id", length = 180)
    private String chatId;

    @Column(name = "display_name", nullable = false, length = 180)
    private String displayName;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public ChatAlias(String chatId, String displayName, OffsetDateTime updatedAt) {
        this.chatId = chatId;
        this.displayName = displayName;
        this.updatedAt = updatedAt;
    }
}
