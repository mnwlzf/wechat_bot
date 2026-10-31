-- 引用图片：被引用那条消息本身是图片时，记下它的 md5 与发送时间，
-- 这样回复时可以顺着 md5 去本地 .dat 找图，让模型真的看到被引用的画面，
-- 而不是只拿到一句 [图片] 占位。
ALTER TABLE message_log ADD COLUMN quote_md5 varchar(64);
ALTER TABLE message_log ADD COLUMN quote_thumb_md5 varchar(64);
ALTER TABLE message_log ADD COLUMN quote_media_at timestamptz;
