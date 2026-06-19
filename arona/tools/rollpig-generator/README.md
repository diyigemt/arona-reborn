# rollpig 卡片生成器

离线下载上游小猪数据与头像, 用 Playwright/Chromium 把每只猪渲染成 800×800 PNG 卡片,
并默认上传到 COS CDN(`image/rollpig/<id>.png`)供插件运行期以 Markdown 引用。
**仅用于资源预处理**, 产物同时放进插件 dataFolder(见下文「产物与安装」), 不打进 jar。

## 环境准备

需要 Python 3.11+:

```shell
python -m venv .venv
.venv\Scripts\activate          # Windows; *nix 用 source .venv/bin/activate
pip install -r requirements.txt
playwright install chromium
```

放置一份**允许分发**的 CJK 字体, 默认路径:

```text
fonts/NotoSansCJKsc-Regular.otf
```

也可用 `--font` 指定其它本地字体。不内置字体, 是为了规避字体许可与平台默认字体差异
导致的渲染不一致(缺字、换行位置、伪粗体)。

## 生成

默认经本地 `http://127.0.0.1:12355` 代理访问上游(中国大陆访问 pighub/felislab 需代理):

```shell
python generate.py
```

完整参数:

```shell
python generate.py \
  --base-url https://pig.felislab.cc/resources/rollpig \
  --proxy http://127.0.0.1:12355 \
  --font fonts/NotoSansCJKsc-Regular.otf \
  --output output
```

代理为 SOCKS5 时:

```shell
python generate.py --proxy socks5://127.0.0.1:12355
```

不走代理: `--proxy ""`。

下载会自动重试; GIF 头像固定取第一帧, 头像统一做 EXIF 方向修正并转静态 PNG;
文本过长时按阶梯缩小字号, 仍溢出则记入报告(退出码非 0)。

## 上传 COS

卡片生成成功后**默认**上传到腾讯云 COS, 对象键为 `image/rollpig/<id>.png`, 对应插件运行期
Markdown 引用的 `https://arona.cdn.diyigemt.com/image/rollpig/<id>.png`。同 id 直接覆盖, 不压缩、
不生成 `/s/` 缩略图。

凭证沿用 arona 既有约定, 从 `~/.ssh/arona-cos.json` 读取(字段 `COS_ID`/`COS_NAME`/
`SECRET_RAW_ID`/`SECRET_RAW_KEY`, Bucket = `COS_NAME-COS_ID`, 默认地域 `ap-shanghai`):

```shell
python generate.py --no-upload                       # 只生成本地产物, 不上传
python generate.py --cos-prefix image/rollpig        # 自定义对象键前缀
python generate.py --cos-credentials /path/to/cos.json --cos-region ap-shanghai
```

凭证缺失或个别卡片上传失败都会记入报告的 `uploadFailures` 并使退出码非 0(本地产物仍保留)。

## 产物与安装

```text
output/
├─ cards/<id>.png          # 800x800 卡片
├─ index.json              # {"pigs":[{"id","name"}, ...]}
└─ generation-report.json  # 每只猪的 sha256/字号/溢出、失败清单与 uploadFailures
```

校验通过(退出码 0)后, 把产物放进插件的 **dataFolder**(框架外置存储, 不进 jar):
`<arona 进程工作目录>/data/com.diyigemt.arona.rollpig/`(本地 sandbox 为
`arona-core/sandbox/data/com.diyigemt.arona.rollpig/`; 插件启动日志会打印该绝对路径)。

推荐部署顺序, 避免读到半更新状态:

```text
1. output/cards/*  ->  data/com.diyigemt.arona.rollpig/cards/   (先放卡片, 同 id 直接覆盖)
2. output/index.json -> data/com.diyigemt.arona.rollpig/index.json  (最后替换索引)
3. 旧卡片暂不删除(当天已抽到的用户仍需发送), 至少保留到次日
4. 确认 COS 上已存在 image/rollpig/<id>.png(生成器默认已上传)
5. 重启插件/进程使其重新加载(内存猪池不热更新)
```

## 注意

- 上游数据/头像/字体的版权与可再分发性, 由使用者自行确认; 建议保留出处与许可信息。
- `id` 仅允许 `[A-Za-z0-9_-]`, 与插件 `PigPool` 校验一致, 否则该只猪会被判为非法。
