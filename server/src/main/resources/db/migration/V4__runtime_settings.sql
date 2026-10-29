-- 运行时配置：会话白名单与 LLM 策略，改完立即生效，无需重启
CREATE TABLE IF NOT EXISTS app_setting (
    setting_key   varchar(120) PRIMARY KEY,
    setting_value text         NOT NULL,
    updated_at    timestamptz  NOT NULL DEFAULT now()
);

COMMENT ON TABLE app_setting IS '管理端可实时修改的策略项；缺省时回落到环境变量';
COMMENT ON COLUMN app_setting.setting_key IS '如 wechat.allowedChats / llm.fallbackEnabled';
