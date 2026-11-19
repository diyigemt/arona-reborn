# agent-test: 让 AI agent 在真实测试群里测 bot

test 插件 (≥0.2.0) 在 bot 进程内注入一条伪造的群消息, 收集 bot 发往该群的出站回复, 经 `/api/v1/test/agent/*` 返回. 依赖 arona-core ≥2.6.0 的注入窗口 (`communication/inject/GroupInjection.kt`).

## 部署

1. 上传 core 2.6.0 与 test 插件 0.2.0 (按薄包流程, 见 plugin-libraries).
2. 停机后编辑 `config/com.diyigemt.arona.test/agent.yml` (首次启动后生成; AutoSavePluginData 会定时回写, 运行中改会被覆盖):

   ```yaml
   enabled: true
   groups:
     test:                      # 群别名
       groupId: <group_openid>
       members:
         me: <member_openid>    # 成员别名
   idleMs: 3000
   timeoutMs: 30000
   maxTimeoutMs: 120000
   announce: false             # send 模式下先发一条 "[agent 测试] 注入: ..." 提示
   imageHosts: []              # 允许作图片附件的 https 主机; 空 = 不接受附件 (plana 等会用生产机真实下载)
   captureTailSeconds: 60      # capture 窗口关闭后继续截获该群出站的秒数
   ```

   只登记专用测试群: 注入窗口期间, 该群的 **全部** 出站都按注入模式处理, 群里真人触发的回复也会被截获或收集.
3. 反向代理 `proxy_read_timeout` 要大于 `maxTimeoutMs` (默认 120s, 建议 150s), 且不要对该 POST 做重试.

## 使用

```bash
tools/agent-test/agent_test.py login --api https://<host>/api/v1   # 超管在 QQ 里对 bot 发 /登录 <验证码>
tools/agent-test/agent_test.py targets
tools/agent-test/agent_test.py inject -g test -m me -M capture "/帮助"
tools/agent-test/agent_test.py inject -g test -m me -M send --idle 6000 "阿罗娜 今天吃什么"
```

token 1 小时无访问即失效, 失效后重新 login. 凭据与 API 地址成对保存在 `~/.config/arona-agent-test/session.json`; 也可以成对设置环境变量 `ARONA_API` / `ARONA_TOKEN`. API 地址必须是 https (本机除外), 客户端拒绝重定向.

返回的 `messages[]`: `offsetMs` 距注入的毫秒数; `replyToInjection` 是否带注入 id 的回复 (false 表示插件另起协程主动发的消息); `receiptId` 在 capture 下以 `arona-capture-` 开头; `elements` 是结构化的文本/markdown/键盘/图片; `error` 是发送失败原因 (含平台错误码). `finishedBy`: `idle` 最后一条后静默够久, `timeout` 到总时限 (没有任何回复时一定是它), `limit` 条数到上限, `dispatch_error` 分发本身抛异常. 总时限从请求开始算, 覆盖群提示与分发.

send 模式在 bot 处于 shadow 时直接拒绝.

## 两种模式

- **capture**: 窗口内及关闭后 `captureTailSeconds` 内, 发往该群的消息与富媒体上传都不出网, core 在本地造回执. 适合反复调试, 不占群配额. 余波期是为了兜住插件慢任务 (如审图) 在收集结束后才另发的消息; 余波期内可以开新窗口.
- **send**: 回复去掉伪 msg_id, 以群主动消息真实发出. 用来验收客户端实际渲染 (markdown/键盘/@/图片) 和平台报错.

## 测不到 / 要注意的

- 被动回复链路 (msg_id 5 分钟窗口与次数、msg_seq 去重) 测不到: 注入回复一律走主动消息.
- 按钮回调、单聊、频道不支持注入.
- capture 只拦该群的 **发消息** 与 **富媒体上传** 两类 OpenAPI. 撤回窗口内真人消息、插件自己的外部调用 (大模型、审核、下载附件) 和数据库写入都照常发生. 测试群若开了 chatbot, 注入消息会落进它的上下文; user-recorder 会把测试成员计入 DAU (可用 core 的 `TencentGroupMessageEvent.isInjected` 排除).
- 注入 id 与 capture 回执 id 不是平台 id, 撤回会被本地拒绝.
