-- 定时消息支持自定义 cron：d=日、m=月、dow=周几，接受 5 段（分 时 日 月 周）或 6 段（带秒）
ALTER TABLE scheduled_message ADD COLUMN cron_expression varchar(120);
