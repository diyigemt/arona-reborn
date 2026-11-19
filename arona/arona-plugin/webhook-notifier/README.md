# webhook-notifier — webhook 转发到群

开一个独立 HTTP 端口接收其他应用的 webhook, 按路径里的别名把请求体作为主动 Markdown 消息发到配置好的群, 可 @ 固定成员。需要 bot 有自定义 Markdown 权限。

只能推送到配置里列出的群: 调用方不能自己指定群, 一个目标的凭证泄露也只影响这一个群。

## 配置

`config/com.diyigemt.arona.webhook.notifier/config.yml`, 首次启动自动生成。必须**停机后修改**: 运行中改的内容会被自动保存覆盖。

```yaml
host: "127.0.0.1"
port: 12356
targets:
  ops:                                # 别名, 调用路径为 /webhook/ops
    groupId: "群的 group_openid"
    userId: "成员的 member_openid"     # 可省略, 省略则不 @
    authorization: "Bearer REPLACE-WITH-RANDOM-TOKEN-1"
    allowedIps: ["203.0.113.0/24", "2001:db8::/32"]
    rateLimitPerMinute: 10
  ci:
    groupId: "另一个群的 group_openid"
    authorization: "Bearer REPLACE-WITH-RANDOM-TOKEN-2"
    allowedIps: ["*"]                 # 不限来源
trustedProxies: ["127.0.0.1", "::1"]
```

全局配置:

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `targets` | 空 | 必填。别名 → 目标, 见下表。别名只能由字母、数字、`_`、`-` 组成, 最长 32, 区分大小写 |
| `host` | `127.0.0.1` | 监听地址。对外提供时应经反向代理加 TLS, 否则凭证会明文传输 |
| `port` | `12356` | 监听端口, **不能与 core 的 `web.port` 相同**: 插件先绑定成功, core 随后会启动失败 |
| `trustedProxies` | `127.0.0.1`, `::1` | 受信的反向代理 IP 或 CIDR, 见"来源 IP"。不经反代直接对外时设为 `[]` |
| `maxBodyBytes` | `65536` | 请求体上限, 超出返回 413, 最大 16 MiB |
| `maxContentChars` | `2000` | 消息正文上限, 超出截断并加标记, 仍然发送 |
| `rateLimitPerMinute` | `20` | 全局令牌桶, 所有目标共用: 可一次突发这么多条, 之后按每分钟这么多条恢复; 上限 10000 |
| `sendTimeoutMillis` | `10000` | 等待平台回执的超时, 须小于 30000 |

每个目标:

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `groupId` | 空 | 必填。目标群的 `group_openid`, 不是群号 |
| `userId` | 空 | 要 @ 的成员的 `member_openid`, 不是 QQ 号; 留空则只发到群里 |
| `authorization` | 空 | 必填。与 `Authorization` 请求头整体比较的完整值, 形如 `Bearer xxx` (scheme 与 token 之间恰好一个空格) 或单个 token, 只能含可见 ASCII。各目标的 token 不能重复, 只换 scheme 也算重复 |
| `allowedIps` | 空 | 必填。允许的来源 IP 或 CIDR (IPv4/IPv6), 写 `["*"]` 表示不限; `*` 不能和具体地址混写。只接受 IP 字面量, 不接受域名 |
| `rateLimitPerMinute` | `20` | 本目标的令牌桶, 与全局桶同时生效; 上限 10000 |

- 配置按宽松模式加载, **键名写错会被静默忽略并取默认值**。所以必填项的默认值都会被校验拒绝, 尤其 `allowedIps` 拼错时会变成空列表而报错, 不会悄悄变成不限来源。
- 配置有误 (必填项为空、别名非法、凭证重复、IP 写错、数值越界) 时只打告警, 不启动监听。YAML 语法或字段类型错误不在此列: 那是 core 加载配置时就抛出的错误。端口被占用同样只影响本插件。启动成功后日志会列出每个目标的来源限制, 部署后核对一遍。
- 每个打到 `/webhook/{别名}` 的请求结束时记一条 info 日志: 别名、来源 IP、实际读到的请求体字节数和结果 (状态码, 或"连接断开或读取超时")。路径不对的 404 不记。

### 来源 IP

socket 对端不在 `trustedProxies` 内时, 来源就是 socket 对端, `X-Forwarded-For` 被忽略。在内时说明经过了反向代理, 从 `X-Forwarded-For` 的右端往左跳过受信代理, 取第一个不受信的地址。左边的条目是客户端自己填的, 不会被采信。

`X-Forwarded-For` 缺失、含非法条目或全部是受信代理时, 来源记为未知: 限制了 IP 的目标返回 403, `["*"]` 的目标照常放行。因此:

- 反向代理必须设置 `X-Forwarded-For`, 如 nginx 的 `proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;`。
- 默认配置下本机直接调用 (不经反代) 的来源是未知, 只能用 `["*"]` 的目标。所有调用都不经反代时把 `trustedProxies` 设为 `[]`。
- 能连上监听端口的本机进程都可以伪造 `X-Forwarded-For`, 它们本来就在信任边界内。

## 调用

```bash
# 本机直连: 来源是未知, 只能调不限来源的目标
curl -X POST http://127.0.0.1:12356/webhook/ci \
  -H 'Authorization: Bearer REPLACE-WITH-RANDOM-TOKEN-2' \
  -H 'Content-Type: text/markdown' \
  --data-binary '**磁盘空间不足**'
```

限制了来源的 `ops` 要从 `allowedIps` 内的机器经反向代理调用, 如 `https://<反代域名>/webhook/ops`。

请求体按 `Content-Type` 渲染, 不看内容:

| Content-Type | 渲染方式 |
| --- | --- |
| `text/markdown`、缺省、其他类型 | 原样作为 Markdown, 不转义: `#`、`*`、`_` 会被渲染, `<qqbot-at-user id="..." />` 也会真的 @ 到人 |
| `application/json`、`*/*+json` | 放进 `json` 代码块, 不校验是否合法 JSON。字符串里连续三个反引号会写成 unicode 转义, 以免闭合代码块 |
| `text/plain` | 放进无语言代码块按字面显示。围栏比正文里最长的连续反引号多一个 |

- `curl --data-binary` 默认发 `application/x-www-form-urlencoded`, 按 Markdown 渲染, 不做表单解码。
- 请求体按 `Content-Type` 声明的 charset 解码, 缺省或无法识别时按 UTF-8。

| 状态码 | 含义 | 调用方该怎么做 |
| --- | --- | --- |
| 200 | 平台已确认 | - |
| 400 | 请求体为空或只有空白 | 修正请求 |
| 401 | 别名不存在、`Authorization` 缺失/重复/不匹配 (不区分是哪一种) | 修正配置 |
| 403 | 凭证正确, 但来源 IP 不在该目标的 `allowedIps` 内或无法确定 | 修正 `allowedIps` 或反代配置 |
| 404 | 路径不对, 如 0.1.x 的 `/webhook` | 改用 `/webhook/{别名}` |
| 408 | 请求体迟迟传不完 (30 秒无数据) | 可重试 |
| 413 | 请求体超限 | 修正请求 |
| 429 | 超出该目标或全局的限流 | 退避后重试 |
| 502 | 未确认送达: 发送失败、超时, 或 bot 还没上线 | 退避后重试 |

- 502 是"结果未知", 不是"一定没发": 平台可能已收到只是回执超时, 所以重试可能产生重复消息。
- 先扣目标桶再扣全局桶: 全局桶拒绝时, 目标桶的这一枚不退还。
- 发送中途调用方断开连接会取消本次发送; 没传完的请求体不会被转发。

## 从 0.1.x 升级

停机, 备份 `config.yml`, 把顶层的 `groupId`、`userId`、`authorization` 移到 `targets.<别名>` 下并补上 `allowedIps`, 再把调用方的 URL 改成 `/webhook/<别名>`。旧的顶层字段不再读取, 下次自动保存时会被删掉。
