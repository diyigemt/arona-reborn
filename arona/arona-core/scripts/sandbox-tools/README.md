# sandbox 联调工具 (chatbot 插件)

不进生产, 只在本机对着 `arona-core/sandbox` 跑的 JVM 用. 依赖: `python3`, `requests`, `cryptography`, `pymongo`.
沙箱路径默认 `/mnt/d/code/java/arona-reborn/arona/arona-core/sandbox/config.yaml`, 用环境变量 `ARONA_SANDBOX_CONFIG` 覆盖.

| 脚本 | 作用 |
|---|---|
| `fake_webhook.py` | 伪造腾讯 webhook 群消息 (Ed25519 签名与平台一致, 密钥从 config.yaml 的 `bot.secret` 派生). 可控 @ / 时间戳 / msg_id / 并发 / 图片 |
| `fake_llm.py` | 本机假 OpenAI `/chat/completions`, 由同目录 `llm_mode.txt` 切换 reply/empty/invalid/silent/error/slow/hang/block/noaudit/slowaudit/bigusage/sticker; 摘要请求 (无 response_format 且含「新摘要」) 固定回纯文本假摘要; 带图请求按 `llm_sticker.txt` (meme/nomeme/nsfw/badjson) 回表情打标, 看图对话回「看到图了喵」 |
| `fake_image.py` | 本机假图床 (18081), 标准库生成 PNG: `/meme.png` `/meme2.png` 方图, `/long.png` `/wide.png` 超尺寸, `/huge.bin` `/text.html` 非图片 |
| `chatbot_state.py` | 查看/直写测试群的 chatbot 配置, 列出 `chatContext` (含图片描述) / `chatMemory` / `chatSticker` / `chatNoop` 与 Redis `chatbot.noop.<gid>` 计数 |
| `round2.sh` / `round3.sh` / `round4.sh` / `round5.sh` | 2026-08-23 验证用的场景脚本 (round4 = P1 记忆压缩; round5 = P2 看图 + 表情库: 抓取/去重/尺寸拦截/rejected/看图回复/配图发送), 作范例 |

第五轮 (P2) 还需要 chatbot `config.yml` 填 COS 凭据 (可从 plana 的 config.yml 抄, 前缀用 `chatbot-sandbox/sticker`)、`stickerAutoApprove: true`, 并启动 `fake_image.py`; 跑完记得把 COS 上 `chatbot-sandbox/` 前缀的对象清掉.
第二/三轮 (模型异常、审核、限流) 需要: `config.yaml` `shadow: true`、chatbot `baseUrl` 指向假模型、用 `arona-plugin/audit-stub` 替换 content-audit.
**注意**: `AutoSavePluginData` 会定时回写插件 `config.yml`, 运行中改文件会被覆盖, 必须停机后改. chat-command `debugging: true` 时只处理 `ignoreGroup` 里的群.
