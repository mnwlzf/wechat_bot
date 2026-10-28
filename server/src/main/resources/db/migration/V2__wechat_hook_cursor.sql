create table wechat_hook_cursor (
    id uuid primary key,
    account varchar(180) not null,
    chat_id varchar(180) not null,
    anchor varchar(30) not null,
    cursor_value bigint not null default 0,
    initialized boolean not null default false,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    constraint uk_wechat_hook_cursor_account_chat unique (account, chat_id)
);

create index idx_wechat_hook_cursor_account on wechat_hook_cursor(account);
