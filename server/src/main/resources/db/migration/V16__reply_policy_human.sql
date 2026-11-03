-- 拟人回复：把「要不要回、回什么」交给模型自己判断。
--
-- 群里几秒钟就好几条消息，逐条判定既贵又不像人，所以加一个冷却时间：
-- 冷却期内的消息只入库、不判定；冷却结束后用最近几条对话判一次。
-- 与其它字段一样可空，NULL 表示沿用下一层（默认 60 秒）。

ALTER TABLE reply_policy ADD COLUMN human_cooldown_seconds integer;
