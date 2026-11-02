-- 语音消息定位：报文里的 msgid（微信服务器消息 ID）存下来，
-- 用来在本地 media_*.db 的 VoiceInfo.svr_id 上精确取到音频字节。
ALTER TABLE message_log ADD COLUMN IF NOT EXISTS svr_id BIGINT;

CREATE INDEX IF NOT EXISTS idx_message_log_svr_id ON message_log (svr_id);
