create table model_provider (
    id uuid primary key,
    name varchar(80) not null,
    base_url varchar(500) not null,
    api_key_encrypted text not null default '',
    model varchar(120) not null,
    enabled boolean not null default true,
    priority integer not null default 100,
    timeout_seconds integer not null default 60,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null
);

create table bot_config (
    id uuid primary key,
    name varchar(80) not null,
    enabled boolean not null default true,
    model_provider_id uuid references model_provider(id) on delete set null,
    system_prompt text not null,
    reply_private boolean not null default true,
    reply_group boolean not null default false,
    group_wake_words varchar(500) not null default '@机器人',
    context_window integer not null default 20,
    temperature double precision not null default 0.70,
    max_tokens integer not null default 800,
    rate_limit_per_minute integer not null default 20,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null
);

create table conversation (
    id uuid primary key,
    bot_id uuid not null references bot_config(id) on delete cascade,
    chat_id varchar(180) not null,
    chat_name varchar(180) not null,
    chat_type varchar(20) not null,
    last_message varchar(500) not null default '',
    last_message_at timestamp with time zone not null,
    created_at timestamp with time zone not null,
    constraint uk_conversation_bot_chat unique (bot_id, chat_id)
);

create table message_log (
    id uuid primary key,
    conversation_id uuid not null references conversation(id) on delete cascade,
    direction varchar(20) not null,
    sender_id varchar(180),
    sender_name varchar(180),
    content text not null,
    provider_name varchar(120),
    model varchar(120),
    status varchar(30) not null,
    error_message varchar(1000),
    prompt_tokens integer,
    completion_tokens integer,
    latency_ms bigint,
    created_at timestamp with time zone not null
);

create table outbox_message (
    id uuid primary key,
    bot_id uuid not null references bot_config(id) on delete cascade,
    conversation_id uuid not null references conversation(id) on delete cascade,
    chat_id varchar(180) not null,
    content text not null,
    status varchar(30) not null,
    attempts integer not null default 0,
    next_attempt_at timestamp with time zone not null,
    lease_until timestamp with time zone,
    last_error varchar(1000),
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null
);

create index idx_message_log_conversation_created on message_log(conversation_id, created_at desc);
create index idx_outbox_pickup on outbox_message(status, next_attempt_at, created_at);
create index idx_conversation_last_message on conversation(last_message_at desc);

insert into bot_config (
    id, name, enabled, model_provider_id, system_prompt,
    reply_private, reply_group, group_wake_words, context_window,
    temperature, max_tokens, rate_limit_per_minute, created_at, updated_at
) values (
    '00000000-0000-0000-0000-000000000001',
    '默认微信机器人',
    true,
    null,
    '你是一个友好、简洁、可靠的微信助手。回答时优先使用中文，不要编造事实；不确定时明确说明，并给出可执行的下一步。',
    true,
    false,
    '@机器人',
    20,
    0.70,
    800,
    20,
    now(),
    now()
);


