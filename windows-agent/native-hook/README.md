# Native Hook Overlay

该目录是 `aixed/WeChat-Hook` 的版本锁定覆盖层，目标客户端固定为微信 `4.1.10.27`。

在参考项目 `E:\code\WeChatAgent\scripts\native-hook` 基础上做了以下调整：

- 保留 `/SendTextMsg` 原生发送
- 保留 `/WeChatAgent/health` 的版本、账号、初始化状态检查
- 新增 `/WeChatAgent/databases`，返回微信进程中已经打开的数据库
- 新增 `/WeChatAgent/query`，只允许单条只读 SELECT / PRAGMA TABLE_INFO
- 新增 `/WeChatAgent/self`，优先返回 SelfInfo.wxid，缺失时回退到唯一账号目录
- HTTP 服务仅绑定 `127.0.0.1`
- 所有路由必须提供 `Authorization: Bearer <64位token>`
- 发送、查询和健康接口都校验 `X-WeChatAgent-Account`

`wechatagent_hook.cpp` 中的 `DatabaseMgr` 直接使用微信进程已经打开的 SQLCipher 数据库句柄，不导出数据库密钥，不修改消息表。

编译和安装请查看 `windows-agent/README.md`。
