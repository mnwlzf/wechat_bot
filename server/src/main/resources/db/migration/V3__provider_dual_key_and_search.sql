-- 双 Key 故障切换 + DeepSeek 联网搜索
ALTER TABLE model_provider
    ADD COLUMN IF NOT EXISTS api_key_backup_encrypted text NOT NULL DEFAULT '';

ALTER TABLE model_provider
    ADD COLUMN IF NOT EXISTS web_search_enabled boolean NOT NULL DEFAULT true;

COMMENT ON COLUMN model_provider.api_key_backup_encrypted IS '备用 API Key（AES-GCM 加密），主 Key 失效时自动切换';
COMMENT ON COLUMN model_provider.web_search_enabled IS '是否允许该提供方调用 DeepSeek 联网搜索';
