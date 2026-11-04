-- 人设切换时间：换人设后，对话窗口里还留着旧口吻的回复，模型会照着学。
-- 记下切换时间，就能在提示词里点名「这些是旧人设说的，只当事实、不学语气」；
-- 等窗口里的消息全部晚于这个时间点，这句提醒自然不再生效，不需要额外清理。
alter table conversation add column if not exists persona_switched_at timestamptz;

comment on column conversation.persona_switched_at is
    '上一次换人设的时间；用于在提示词里标记「窗口内的旧回复不要模仿语气」';
