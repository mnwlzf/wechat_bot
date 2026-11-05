-- 重复提醒的「时间锚点」：催命循环会把 scheduled_at 一路推到 18:10，
-- 下一轮如果拿它加一天，就会变成每天 18:10、18:15……越来越晚。
-- 存下这一系列本来该在的钟点（18:00），每轮结束都从它算下一次。
alter table scheduled_message add column anchor_at timestamptz;

-- 历史数据：把当前时间当作锚点，至少不会从明天开始更晚
update scheduled_message set anchor_at = scheduled_at
where repeat_rule <> 'NONE' and anchor_at is null;
