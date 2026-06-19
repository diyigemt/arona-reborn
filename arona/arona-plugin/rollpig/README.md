# rollpig — 今日小猪

仿 [nonebot-plugin-rollpig](https://github.com/Bearlele/nonebot-plugin-rollpig):
「今日小猪」为每个用户按**北京时间日期**抽取当天固定的一只小猪(当天重复触发返回同一只, 次日重抽);
「随机小猪」则每次随机一只, 不锁当日、不落库。

- 指令:
  - `/今日小猪`(别名 `/本日小猪`、`/当日小猪`): 当天固定结果, 取自**本地预生成卡片**。
  - `/随机小猪`: 每次随机一只(单张), 实时取自 [pighub.top](https://pighub.top) 图片列表。
- 两条指令结果均以 Markdown 发送, 并在同一行附「我是猪」(→`/今日小猪`)、「随机小猪」(→`/随机小猪`)两枚按钮。
- 「今日小猪」卡片是**离线预生成的静态图**, 运行期以 Markdown 引用 CDN 图
  `https://arona.cdn.diyigemt.com/image/rollpig/<id>.png`, 不再上传图片字节。
- 「随机小猪」从 `GET https://pighub.top/api/all-images` 拉取列表(内存缓存 1h, 失败沿用旧快照),
  直接以 pighub 原图 URL(`https://pighub.top/data/<file>`)作为 Markdown 图片; 因原图尺寸不一,
  显示尺寸取固定方形, 可能有轻微变形。卡片在图片上方一并显示该图的 pighub 标题(title)。

## 数据存放(框架 dataFolder, 不在 jar 内)

卡片与索引从插件 **dataFolder** 读取, 换图/更新无需重新打包 jar。目录为
`<arona 进程工作目录>/data/com.diyigemt.arona.rollpig/`(本地 sandbox 为
`arona-core/sandbox/data/com.diyigemt.arona.rollpig/`), 启动日志会打印其绝对路径。

```text
data/com.diyigemt.arona.rollpig/
├─ rollpig.db        # 插件自动创建, 记录每个用户每日抽取结果
├─ index.json        # {"pigs":[{"id":"human","name":"人类"}, ...]}
└─ cards/
   └─ <id>.png       # 与 index 中 id 对应的 800x800 卡片
```

缺少 `index.json` 或猪池为空时, 指令只回维护提示, 不影响其他插件。

## 生成与部署

1. 用 [`tools/rollpig-generator`](../../tools/rollpig-generator/README.md) 生成 `index.json` 与 `cards/*.png`
   (下载上游素材时经本地 `127.0.0.1:12355` 代理), 生成器默认会把卡片上传到 COS:
   `image/rollpig/<id>.png`。
2. 部署顺序(避免半更新状态被读到):
   - 先把**新卡片**全部复制进 `cards/`(同 id 换图直接覆盖), 并确认已同步到 CDN;
   - **最后**替换 `index.json`;
   - 暂不要删除旧卡片 —— 当天已抽到旧猪的用户仍要发它, 至少保留到次日。
3. **重启插件/进程**使其重新加载(内存猪池不热更新)。

> id 仅允许 `[A-Za-z0-9_-]`(与生成器、运行期校验一致), 否则该只会被判为非法。

## TODO

- [ ] 管理员指令 `/今日小猪重载`: 免重启热更新数据。重载时全量校验新 `index.json` 与卡片,
      **校验通过才整体替换内存猪池**。比文件监听更可控, 可规避「index 已换、cards 未拷完」的半更新竞态。
      (换图后 CDN 仍可能缓存旧图, 必要时按 COS/CDN 流程刷新缓存或用版本化路径。)
