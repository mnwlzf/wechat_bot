-- 引用消息（type=49 / appmsg 子类型 57）与被引用的原文
ALTER TABLE message_log ADD COLUMN quote_author varchar(180);
ALTER TABLE message_log ADD COLUMN quote_text varchar(1000);

-- 图片识别：定位本地 .dat 与解密所需信息（微信 V2 图片格式用 aeskey 解密）
ALTER TABLE message_log ADD COLUMN media_md5 varchar(64);
ALTER TABLE message_log ADD COLUMN media_aeskey varchar(64);

CREATE INDEX IF NOT EXISTS idx_message_log_media_md5 ON message_log (media_md5);
