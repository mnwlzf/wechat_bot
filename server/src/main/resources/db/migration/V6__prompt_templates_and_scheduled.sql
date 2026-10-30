-- 提示词模板：可复用的系统提示词，按会话绑定人格
CREATE TABLE prompt_template (
    id uuid PRIMARY KEY,
    name varchar(120) NOT NULL,
    description varchar(300) NOT NULL DEFAULT '',
    content text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE conversation ADD COLUMN prompt_template_id uuid;
CREATE INDEX idx_conversation_prompt_template ON conversation (prompt_template_id);

-- 定时消息：到点后转入 outbox，由既有的投递链路发送
CREATE TABLE scheduled_message (
    id uuid PRIMARY KEY,
    bot_id uuid NOT NULL,
    conversation_id uuid,
    chat_id varchar(180) NOT NULL,
    chat_name varchar(180) NOT NULL DEFAULT '',
    content text NOT NULL,
    scheduled_at timestamptz NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'PENDING',
    attempts integer NOT NULL DEFAULT 0,
    last_error varchar(1000),
    outbox_id uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    sent_at timestamptz
);

CREATE INDEX idx_scheduled_message_due ON scheduled_message (status, scheduled_at);
