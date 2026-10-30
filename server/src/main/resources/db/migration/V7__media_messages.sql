-- 媒体消息（图片 / 语音 / 视频 / 文件 / 链接 / 表情）元数据
ALTER TABLE message_log ADD COLUMN media_kind varchar(20);
ALTER TABLE message_log ADD COLUMN media_name varchar(255);
ALTER TABLE message_log ADD COLUMN media_size bigint;
ALTER TABLE message_log ADD COLUMN media_url varchar(1000);
ALTER TABLE message_log ADD COLUMN raw_type integer;

CREATE INDEX IF NOT EXISTS idx_message_log_media_kind ON message_log (media_kind);

-- 用量统计按时间聚合
CREATE INDEX IF NOT EXISTS idx_message_log_created_at ON message_log (created_at);
