# sandbox 联调工具 (chatbot 插件)

不进生产, 只在本机对着 `arona-core/sandbox` 跑的 JVM 用. 依赖: `python3`, `requests`, `cryptography`, `pymongo`.
沙箱路径默认 `/mnt/d/code/java/arona-reborn/arona/arona-core/sandbox/config.yaml`, 用环境变量 `ARONA_SANDBOX_CONFIG` 覆盖.

| 脚本 | 作用 |
|---|---|
| `fake_webhook.py` | 伪造腾讯 webhook 群消息 (Ed25519 签名与平台一致, 密钥从 config.yaml 的 `bot.secret` 派生). 可控 @ / 时间戳 / msg_id / 并发 / 图片 |
| `fake_llm.py` | 本机假 OpenAI `/chat/completions`, 由同目录 `llm_mode.txt` 切换 reply/empty/invalid/silent/error/slow/hang/block/noaudit/slowaudit/bigusage/sticker; 摘要请求 (无 response_format 且含「新摘要」) 固定回纯文本假摘要; 带图请求按 `llm_sticker.txt` (meme/nomeme/nsfw/badjson) 回表情打标, 看图对话回「看到图了喵」 |
| `fake_image.py` | 本机假图床 (18081), 标准库生成 PNG: `/meme.png` `/meme2.png` 方图, `/long.png` `/wide.png` 超尺寸, `/huge.bin` `/text.html` 非图片 |
| `chatbot_state.py` | 查看/直写测试群的 chatbot 配置, 列出 `chatContext` (含图片描述) / `chatMemory` / `chatSticker` / `chatNoop` 与 Redis `chatbot.noop.<gid>` 计数 |
| `chatbot_web.py` | 打 webui 后端的图库运营端点 (`/chatbot/sticker/*`, 仅超管): 往 Redis 塞 `token.<随机>` 冒充登录用户 (默认取 config.yaml `superAdminUid` 第一个), list (`--gid` 过滤)/update/delete, `--probe` 顺带 GET 每个 url (冒充 nginx 的静态服务) |
| `round2.sh` / `round3.sh` / `round4.sh` / `round5.sh` / `round6.sh` | 2026-08-23 验证用的场景脚本 (round4 = P1 记忆压缩; round5 = P2 看图 + 表情库: 抓取/去重/尺寸拦截/rejected/看图回复/配图发送; round6 = P3 运营端点: pending→审核→配图→隐藏→删除与鉴权负向), 作范例 |

第五轮 (P2) 还需要 `stickerAutoApprove: true` 并启动 `fake_image.py`; 表情文件落在 `sandbox/data/com.diyigemt.arona.chatbot/sticker/`, `chatbot_state.py --clear` 只删 Mongo 行, 文件手动清.
第六轮 (P3) 在第五轮基础上改 `stickerAutoApprove: false` (走人工审核), chatbot `config.yml` 填 `stickerPublicBaseUrl: http://127.0.0.1:18082` 并用 `python3 -m http.server 18082 --directory <sticker 目录>` 冒充 nginx (`--probe` 才 GET 得到), 沙箱 core jar 也要是含 `pluginUser` 的新版 (`:arona-core:jar` 后复制到 `sandbox/arona-core-<版本>.jar`; 2026-08-27 起须 ≥2.3.0, 薄插件依赖 `sandbox/libraries/`); `superAdminUid` 第一个要有 User 文档 (webui 鉴权会查).
第二/三轮 (模型异常、审核、限流) 需要: `config.yaml` `shadow: true`、chatbot `baseUrl` 指向假模型、用 `arona-plugin/audit-stub` 替换 content-audit.
**注意**: `AutoSavePluginData` 会定时回写插件 `config.yml`, 运行中改文件会被覆盖, 必须停机后改. chat-command `debugging: true` 时只处理 `ignoreGroup` 里的群.
