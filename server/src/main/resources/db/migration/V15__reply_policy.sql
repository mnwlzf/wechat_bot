-- 回复策略：决定「这个群到底要不要回」——主动回复 / 随机回复 / 关键词唤醒。
--
-- 优先级：该群的策略 → 全局默认策略（chat_id 为 NULL）→ 机器人配置里的唤醒词（group_wake_words）。
-- 每个字段都可空，NULL 表示「沿用下一层」；chat_id 为空的默认策略最多一条（唯一索引保证）。
-- 只作用于群聊；私聊保持「收到就回」，除非单独给该会话加一条策略。

CREATE TABLE reply_policy (
    id uuid PRIMARY KEY,
    chat_id varchar(180),
    chat_name varchar(180) NOT NULL DEFAULT '',
    enabled boolean NOT NULL DEFAULT true,
    -- ALWAYS（每条都回）/ RANDOM（按概率回）/ WAKE（命中唤醒词才回）
    mode varchar(16),
    wake_words varchar(500),
    random_rate double precision,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uk_reply_policy_chat ON reply_policy (chat_id) WHERE chat_id IS NOT NULL;
-- 常量表达式索引：保证「默认策略」（chat_id IS NULL）也最多一条
CREATE UNIQUE INDEX uk_reply_policy_default ON reply_policy ((1)) WHERE chat_id IS NULL;
CREATE INDEX idx_reply_policy_updated ON reply_policy (updated_at DESC);
