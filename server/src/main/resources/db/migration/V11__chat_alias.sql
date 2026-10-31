-- 会话备注：微信 4.x 的 contact.db 是 SQLCipher 加密的，预编译 Hook 的 QueryDB 又拿不到数据，
-- 所以让用户给会话起一个本机名字，之后按名字 / 备注就能搜到，不用记 wxid 和 @chatroom。
CREATE TABLE chat_alias (
    chat_id      varchar(180) PRIMARY KEY,
    display_name varchar(180) NOT NULL,
    updated_at   timestamptz  NOT NULL DEFAULT now()
);
