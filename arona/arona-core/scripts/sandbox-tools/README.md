# sandbox 联调工具 (chatbot 插件)

不进生产, 只在本机对着 `arona-core/sandbox` 跑的 JVM 用. 依赖: `python3`, `requests`, `cryptography`, `pymongo`.
沙箱路径默认 `/mnt/d/code/java/arona-reborn/arona/arona-core/sandbox/config.yaml`, 用环境变量 `ARONA_SANDBOX_CONFIG` 覆盖.

| 脚本 | 作用 |
|---|---|
| `fake_webhook.py` | 伪造腾讯 webhook 群消息 (Ed25519 签名与平台一致, 密钥从 config.yaml 的 `bot.secret` 派生). 可控 @ / 时间戳 / msg_id / 并发 / 图片 |
| `fake_llm.py` | 本机假 OpenAI `/chat/completions`, 由同目录 `llm_mode.txt` 切换 reply/empty/invalid/silent/error/slow/hang/block/noaudit/slowaudit/bigusage; 摘要请求 (无 response_format 且含「新摘要」) 固定回纯文本假摘要 |
| `chatbot_state.py` | 查看/直写测试群的 chatbot 配置, 列出 `chatContext` / `chatNoop` 与 Redis `chatbot.noop.<gid>` 计数 |
| `round2.sh` / `round3.sh` / `round4.sh` | 2026-08-23 验证用的场景脚本 (round4 = P1 记忆压缩: 行数触发 / 摘要进 prompt / token 触发), 作范例 |

第二/三轮 (模型异常、审核、限流) 需要: `config.yaml` `shadow: true`、chatbot `baseUrl` 指向假模型、用 `arona-plugin/audit-stub` 替换 content-audit.
**注意**: `AutoSavePluginData` 会定时回写插件 `config.yml`, 运行中改文件会被覆盖, 必须停机后改. chat-command `debugging: true` 时只处理 `ignoreGroup` 里的群.
