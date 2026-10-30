-- 人工接管标记：会话被接管期间不自动回复
ALTER TABLE conversation ADD COLUMN bot_paused boolean NOT NULL DEFAULT false;

-- 关键词 / 正则自动回复规则
CREATE TABLE auto_reply_rule (
    id uuid PRIMARY KEY,
    name varchar(120) NOT NULL,
    match_type varchar(20) NOT NULL,
    pattern varchar(500) NOT NULL,
    reply text NOT NULL,
    scope varchar(20) NOT NULL DEFAULT 'ALL',
    priority integer NOT NULL DEFAULT 100,
    enabled boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_auto_reply_rule_enabled ON auto_reply_rule (enabled, priority);
