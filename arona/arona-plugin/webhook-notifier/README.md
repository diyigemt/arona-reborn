# webhook-notifier — webhook 转发到群

开一个独立 HTTP 端口接收其他应用的 webhook, 把请求体作为主动 Markdown 消息发到固定的群, 并 @ 固定的成员。需要 bot 有自定义 Markdown 权限。

## 配置

`config/com.diyigemt.arona.webhook.notifier/config.yml`, 首次启动自动生成。必须**停机后修改**: 运行中改的内容会被自动保存覆盖。

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `authorization` | 空 | 必填。与 `Authorization` 请求头整体比较的完整值, 含 scheme, 如 `Bearer xxx`, 只支持 ASCII |
| `groupId` | 空 | 必填。目标群的 `group_openid`, 不是群号 |
| `userId` | 空 | 要 @ 的成员的 `member_openid`, 不是 QQ 号; 留空则只发到群里 |
| `host` | `127.0.0.1` | 监听地址。对外提供时应经反向代理加 TLS, 否则 `authorization` 会明文传输 |
| `port` | `12356` | 监听端口, **不能与 core 的 `web.port` 相同**: 插件先绑定成功, core 随后会启动失败 |
| `maxBodyBytes` | `65536` | 请求体上限, 超出返回 413 |
| `maxContentChars` | `2000` | 消息正文上限, 超出截断并加标记, 仍然发送 |
| `rateLimitPerMinute` | `20` | 全局令牌桶: 可一次突发这么多条, 之后按每分钟这么多条恢复 |
| `sendTimeoutMillis` | `10000` | 等待平台回执的超时, 须小于 30000 |

配置有误 (必填项为空、数值越界) 时只打告警, 不启动监听。端口被占用同样只影响本插件。

## 调用

```bash
curl -X POST http://127.0.0.1:12356/webhook \
  -H 'Authorization: Bearer xxx' \
  --data-binary '磁盘空间不足'
```

| 状态码 | 含义 | 调用方该怎么做 |
| --- | --- | --- |
| 200 | 平台已确认 | - |
| 400 | 请求体为空或只有空白 | 修正请求 |
| 401 | `Authorization` 不匹配 | 修正配置 |
| 408 | 请求体迟迟传不完 (30 秒无数据) | 可重试 |
| 413 | 请求体超限 | 修正请求 |
| 429 | 超出限流 | 退避后重试 |
| 502 | 未确认送达: 发送失败、超时, 或 bot 还没上线 | 退避后重试 |

- 502 是"结果未知", 不是"一定没发": 平台可能已收到只是回执超时, 所以重试可能产生重复消息。
- 请求体按 `Content-Type` 声明的 charset 解码, 缺省或无法识别时按 UTF-8。
- 请求体是 JSON 对象或数组时放进代码块, 内容不变, 只有字符串里连续三个反引号会写成 `\u0060` 以免闭合代码块。不依赖 `Content-Type`, 按内容判断。
- 其余请求体不做转义, 直接作为 Markdown 内容: `#`、`*`、`_` 等字符会被渲染, 其中的 `<qqbot-at-user />` 标签也会真的 @ 到人。
- 发送中途调用方断开连接会取消本次发送; 没传完的请求体不会被转发。
