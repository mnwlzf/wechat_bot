-- 定时消息升级成「任务」：支持等回执的间隔提醒
--
-- MESSAGE ：到点发一条 / 按周期重复（原有行为，默认值保证老数据不变）
-- REMINDER：到点开始催，每 interval_minutes 催一次，对方回话（回执）就停，
--           最多催 max_times 次；文案与收尾都由当前人设生成。

alter table scheduled_message
    add column kind             varchar(20) not null default 'MESSAGE',
    add column title            varchar(120),
    add column interval_minutes integer,
    add column max_times        integer,
    add column stop_words       varchar(500),
    add column variants         text,
    add column final_message    text,
    add column ack_at           timestamptz,
    add column ack_content      varchar(500),
    add column source           varchar(20) not null default 'ADMIN',
    add column creator_chat_id  varchar(180),
    add column raw_instruction  text;

-- 提醒允许只给标题、由人设生成文案；内容列因此不再强制非空
alter table scheduled_message alter column content drop not null;

-- 回执查找：按会话 + 状态 + 类型
create index idx_scheduled_chat_status_kind on scheduled_message (chat_id, status, kind);

comment on column scheduled_message.kind is 'MESSAGE=定时消息，REMINDER=等回执的提醒';
comment on column scheduled_message.variants is '提醒文案（每行一句，按次数依次使用），给人设生成留位';
comment on column scheduled_message.final_message is '次数用完后的收尾话术';
comment on column scheduled_message.source is 'ADMIN=管理端创建，CHAT=聊天里自然语言创建';
