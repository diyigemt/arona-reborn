# rollpig — 今日小猪

仿 [nonebot-plugin-rollpig](https://github.com/Bearlele/nonebot-plugin-rollpig) 的「今日小猪」:
为每个用户按**北京时间日期**抽取当天固定的一只小猪, 当天重复触发返回同一只, 次日重抽。

- 指令: `/今日小猪`(别名 `/本日小猪`、`/当日小猪`)。
- 卡片是**离线预生成的静态图**, 运行期不下载上游资源(仅发图所需的腾讯上传走网络)。

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
   (下载上游素材时经本地 `127.0.0.1:12355` 代理)。
2. 部署顺序(避免半更新状态被读到):
   - 先把**新卡片**全部复制进 `cards/`(同 id 换图直接覆盖);
   - **最后**替换 `index.json`;
   - 暂不要删除旧卡片 —— 当天已抽到旧猪的用户仍要发它, 至少保留到次日。
3. **重启插件/进程**使其重新加载(内存猪池与图片上传缓存均不热更新)。

> id 仅允许 `[A-Za-z0-9_-]`(与生成器、运行期校验一致), 否则该只会被判为非法。

## TODO

- [ ] 管理员指令 `/今日小猪重载`: 免重启热更新数据。重载时全量校验新 `index.json` 与卡片,
      **校验通过才整体替换内存猪池**, 并显式清空 [CardImageService] 的上传缓存(否则同 id 换图后
      仍会命中旧上传产物直到 TTL 失效)。比文件监听更可控, 可规避「index 已换、cards 未拷完」的半更新竞态。
