-- 发信策略：把「怎么发」从一份全局配置，变成「全局默认 + 按群覆盖」两层。
--
-- 优先级：会话自己的策略 → 全局默认策略（chat_id 为 NULL）→ 环境变量 app.disguise.*
-- 每个字段都可空，NULL 表示「沿用下一层」，所以只改想改的那几项就行。
-- chat_id 为空的默认策略最多一条（唯一索引保证），按群的每群最多一条。

CREATE TABLE send_policy (
    id uuid PRIMARY KEY,
    chat_id varchar(180),
    chat_name varchar(180) NOT NULL DEFAULT '',
    enabled boolean NOT NULL DEFAULT true,
    read_delay_min_ms integer,
    read_delay_max_ms integer,
    max_delay_ms integer,
    split_enabled boolean,
    max_parts integer,
    max_part_chars integer,
    per_minute integer,
    per_10_minutes integer,
    per_hour integer,
    per_day integer,
    min_gap_ms integer,
    emoji_limit integer,
    drop_rate double precision,
    prompt_enabled boolean,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uk_send_policy_chat ON send_policy (chat_id) WHERE chat_id IS NOT NULL;
-- 常量表达式索引：保证「默认策略」（chat_id IS NULL）也最多一条
CREATE UNIQUE INDEX uk_send_policy_default ON send_policy ((1)) WHERE chat_id IS NULL;
CREATE INDEX idx_send_policy_updated ON send_policy (updated_at DESC);
