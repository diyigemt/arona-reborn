#!/usr/bin/env python3
"""今日小猪卡片离线生成器。

下载上游 pig.json 与头像(经本地代理), 用 Playwright/Chromium 把每只猪渲染成
800x800 静态卡片 PNG, 产出可直接放进 arona rollpig 插件 resources 的资源包。

仅用于资源预处理, 不参与插件运行期。
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import sys
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from typing import Any
from urllib.parse import quote

import httpx
from PIL import Image, ImageOps
from playwright.sync_api import Page, sync_playwright

DEFAULT_BASE_URL = "https://pig.felislab.cc/resources/rollpig"
# DEFAULT_PROXY = "http://127.0.0.1:12355"
DEFAULT_PROXY = ""
# 与 Kotlin 侧 PigPool.SAFE_ID 保持一致, 防脏数据与资源路径穿越。
SAFE_ID = re.compile(r"^[A-Za-z0-9_-]+$")
IMAGE_EXTENSIONS = ("png", "jpg", "jpeg", "webp", "gif")
# 文本溢出时按此阶梯逐级缩小 (description, analysis) 字号; 仍溢出则记入报告。
FONT_STEPS = ((30, 28), (28, 26), (26, 24), (24, 22), (22, 20))
DOWNLOAD_ATTEMPTS = 3
# COS 上传默认参数。对象键 <prefix>/<id>.png 对应 CDN https://arona.cdn.diyigemt.com/<prefix>/<id>.png,
# 与 rollpig 插件运行期引用的 image/rollpig/<id>.png 一致。凭证沿用 arona 既有的 ~/.ssh/arona-cos.json。
COS_DEFAULT_REGION = "ap-shanghai"
COS_DEFAULT_PREFIX = "image/rollpig"
COS_DEFAULT_CREDENTIALS = Path.home() / ".ssh" / "arona-cos.json"
COS_UPLOAD_WORKERS = 8


def parse_args() -> argparse.Namespace:
    here = Path(__file__).resolve().parent
    parser = argparse.ArgumentParser(description="生成今日小猪 800x800 静态卡片")
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL, help="上游资源根, 取 <base>/pig.json 与 <base>/image/<id>.<ext>")
    parser.add_argument("--proxy", default=DEFAULT_PROXY, help="下载代理, 支持 http:// 或 socks5://; 传空字符串则不走代理")
    parser.add_argument("--output", type=Path, default=here / "output", help="产物目录(会被清空重建)")
    parser.add_argument("--font", type=Path, default=here / "ResourceHanRoundedCN-VF.otf", help="卡片使用的本地 CJK 字体")
    parser.add_argument("--no-upload", dest="upload", action="store_false", help="只生成本地产物, 不上传到 COS")
    parser.add_argument("--cos-prefix", default=COS_DEFAULT_PREFIX, help=f"COS 对象键前缀, 默认 {COS_DEFAULT_PREFIX}")
    parser.add_argument("--cos-region", default=COS_DEFAULT_REGION, help=f"COS 地域, 默认 {COS_DEFAULT_REGION}")
    parser.add_argument("--cos-credentials", type=Path, default=COS_DEFAULT_CREDENTIALS, help="COS 凭证 JSON 路径")
    parser.set_defaults(upload=True)
    return parser.parse_args()


def download(client: httpx.Client, url: str, *, allow_not_found: bool = False) -> bytes | None:
    """带重试的下载。allow_not_found 时 404 返回 None(用于探测头像扩展名)。"""
    last_error: Exception | None = None
    for attempt in range(1, DOWNLOAD_ATTEMPTS + 1):
        try:
            response = client.get(url)
            if allow_not_found and response.status_code == 404:
                return None
            response.raise_for_status()
            if not response.content:
                raise RuntimeError(f"下载结果为空: {url}")
            return response.content
        except (httpx.HTTPError, RuntimeError) as exc:
            last_error = exc
            if attempt < DOWNLOAD_ATTEMPTS:
                time.sleep(0.5 * attempt)
    raise RuntimeError(f"下载失败, 已重试 {DOWNLOAD_ATTEMPTS} 次: {url}") from last_error


def validate_pigs(raw: Any) -> list[dict[str, str]]:
    if not isinstance(raw, list):
        raise ValueError("pig.json 顶层必须是数组")
    seen: set[str] = set()
    pigs: list[dict[str, str]] = []
    for index, item in enumerate(raw):
        if not isinstance(item, dict):
            raise ValueError(f"pig.json 第 {index} 项不是对象")
        pig_id = item.get("id")
        name = item.get("name")
        description = item.get("description")
        analysis = item.get("analysis")
        if not isinstance(pig_id, str) or not SAFE_ID.fullmatch(pig_id):
            raise ValueError(f"非法小猪 id: {pig_id!r}")
        if pig_id in seen:
            raise ValueError(f"重复小猪 id: {pig_id}")
        if not isinstance(name, str) or not name.strip():
            raise ValueError(f"小猪名称无效: {pig_id}")
        if not isinstance(description, str) or not isinstance(analysis, str):
            raise ValueError(f"description/analysis 必须是字符串: {pig_id}")
        seen.add(pig_id)
        pigs.append({
            "id": pig_id,
            "name": name.strip(),
            "description": description.strip(),
            "analysis": analysis.strip(),
        })
    if not pigs:
        raise ValueError("pig.json 不得为空")
    return pigs


def fetch_avatar(client: httpx.Client, base_url: str, pig_id: str, raw_dir: Path) -> Path:
    """逐个扩展名探测并下载头像原图, 保存到 raw_dir。"""
    for ext in IMAGE_EXTENSIONS:
        content = download(client, f"{base_url}/images/{quote(pig_id)}.{ext}", allow_not_found=True)
        if content is None:
            continue
        path = raw_dir / f"{pig_id}.{ext}"
        path.write_bytes(content)
        return path
    raise RuntimeError(f"未找到头像(尝试扩展名 {IMAGE_EXTENSIONS}): {pig_id}")


def normalize_avatar(source: Path, destination: Path) -> None:
    """规范化头像: GIF 固定取第一帧, 修正 EXIF 方向, 统一存为静态 PNG。"""
    with Image.open(source) as image:
        if getattr(image, "is_animated", False):
            image.seek(0)
        normalized = ImageOps.exif_transpose(image)
        if normalized.mode not in ("RGB", "RGBA"):
            normalized = normalized.convert("RGBA")
        normalized.save(destination, format="PNG", optimize=True)


def prepare_template(template: Path, font: Path, work_dir: Path) -> Path:
    html = template.read_text(encoding="utf-8").replace("__FONT_URI__", font.resolve().as_uri())
    rendered = work_dir / "template.rendered.html"
    rendered.write_text(html, encoding="utf-8")
    return rendered


def render_card(page: Page, template_uri: str, pig: dict[str, str], avatar: Path, destination: Path) -> dict[str, Any]:
    page.goto(template_uri, wait_until="load")
    page.evaluate(
        "(payload) => window.setCard(payload)",
        {
            "name": pig["name"],
            "description": pig["description"],
            "analysis": pig["analysis"],
            "avatar": avatar.resolve().as_uri(),
        },
    )

    chosen = FONT_STEPS[-1]
    overflow = True
    for description_size, analysis_size in FONT_STEPS:
        page.evaluate(
            "([d, a]) => window.applyFontSizes(d, a)",
            [description_size, analysis_size],
        )
        page.wait_for_timeout(50)
        overflow = bool(page.evaluate("() => window.hasOverflow()"))
        chosen = (description_size, analysis_size)
        if not overflow:
            break

    page.locator("#card").screenshot(path=str(destination), type="png")
    return {"overflow": overflow, "descriptionFontSize": chosen[0], "analysisFontSize": chosen[1]}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def upload_cards_to_cos(
    cards_dir: Path,
    pigs: list[dict[str, str]],
    *,
    credentials_path: Path,
    region: str,
    prefix: str,
) -> list[dict[str, str]]:
    """把生成成功的卡片并发上传到腾讯云 COS, 对象键为 <prefix>/<id>.png。

    返回失败清单(每项含 id 与 error); 全部成功则为空。凭证文件/字段缺失或 SDK 未安装时抛
    RuntimeError, 由调用方决定是否中断整批。同名对象直接覆盖(换图), 不做压缩、不生成 /s/ 缩略图。
    """
    # 延迟导入: 仅在确实要上传时才依赖 COS SDK, 不拖累 --no-upload 的纯生成流程。
    try:
        from qcloud_cos import CosConfig, CosS3Client
    except ImportError as exc:
        raise RuntimeError("缺少 qcloud_cos, 请先 pip install -r requirements.txt") from exc

    if not credentials_path.is_file():
        raise RuntimeError(f"COS 凭证文件不存在: {credentials_path} (离线生成可加 --no-upload 跳过上传)")

    normalized_prefix = prefix.strip("/")
    if not normalized_prefix:
        raise RuntimeError(f"COS 对象键前缀不能为空: {prefix!r}")

    # 读取/解析失败收敛成 RuntimeError, 让上层与「凭证字段缺失」走同一条降级路径(写报告 + 非 0 退出),
    # 而不是在写完 index.json 后抛栈中断。
    try:
        credentials = json.loads(credentials_path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        raise RuntimeError(f"COS 凭证读取/解析失败: {credentials_path}: {exc}") from exc
    required = ("COS_ID", "COS_NAME", "SECRET_RAW_ID", "SECRET_RAW_KEY")
    missing = [key for key in required if not credentials.get(key)]
    if missing:
        raise RuntimeError(f"COS 凭证字段缺失: {', '.join(missing)}")

    bucket = f"{credentials['COS_NAME']}-{credentials['COS_ID']}"
    client = CosS3Client(
        CosConfig(Region=region, SecretId=credentials["SECRET_RAW_ID"], SecretKey=credentials["SECRET_RAW_KEY"])
    )

    def upload_one(pig: dict[str, str]) -> dict[str, str] | None:
        pig_id = pig["id"]
        local_file = cards_dir / f"{pig_id}.png"
        if not local_file.is_file():
            return {"id": pig_id, "error": f"卡片文件不存在: {local_file}"}
        try:
            client.upload_file(Bucket=bucket, Key=f"{normalized_prefix}/{pig_id}.png", LocalFilePath=str(local_file))
            return None
        except Exception as exc:  # noqa: BLE001 - 单图上传失败应汇总进报告, 不中断其余
            return {"id": pig_id, "error": f"{type(exc).__name__}: {exc}"}

    failures: list[dict[str, str]] = []
    with ThreadPoolExecutor(max_workers=COS_UPLOAD_WORKERS) as executor:
        for failure in executor.map(upload_one, pigs):
            if failure is not None:
                failures.append(failure)
                print(f"[上传失败] {failure['id']}: {failure['error']}", file=sys.stderr)
    return failures


def main() -> int:
    args = parse_args()
    here = Path(__file__).resolve().parent
    output_dir = args.output.resolve()
    cards_dir = output_dir / "cards"
    work_dir = output_dir / ".work"
    raw_dir = work_dir / "avatars-raw"
    normalized_dir = work_dir / "avatars-normalized"

    if not args.font.is_file():
        print(f"字体文件不存在: {args.font}\n请放置可分发的 CJK 字体, 或用 --font 指定。", file=sys.stderr)
        return 2

    # --output 会被整体清空重建, 防误删: 拒绝当前目录/家目录/脚本目录/盘根等危险路径。
    forbidden = {Path.cwd().resolve(), Path.home().resolve(), here, here.parent, Path(output_dir.anchor).resolve()}
    if output_dir in forbidden:
        print(f"拒绝将危险目录用作 --output(会被清空): {output_dir}", file=sys.stderr)
        return 2

    if output_dir.exists():
        shutil.rmtree(output_dir)
    for directory in (cards_dir, raw_dir, normalized_dir):
        directory.mkdir(parents=True)

    base_url = args.base_url.rstrip("/")
    proxy = args.proxy.strip() or None
    client_options: dict[str, Any] = {
        "follow_redirects": True,
        "timeout": httpx.Timeout(30.0, connect=15.0),
        "headers": {"User-Agent": "arona-rollpig-generator/0.1.0"},
    }
    if proxy is not None:
        client_options["proxy"] = proxy  # http(s):// 或 socks5://; socks 需 httpx[socks]

    report: dict[str, Any] = {
        "baseUrl": base_url,
        "proxy": proxy,
        "generated": [],
        "scaled": [],
        "overflow": [],
        "failures": [],
        "uploadFailures": [],
    }
    successful: list[dict[str, str]] = []

    with httpx.Client(**client_options) as client:
        pig_json = download(client, f"{base_url}/pig.json")
        pigs = validate_pigs(json.loads(pig_json.decode("utf-8-sig")))
        template_uri = prepare_template(here / "template.html", args.font, work_dir).resolve().as_uri()

        with sync_playwright() as playwright:
            browser = playwright.chromium.launch(headless=True)
            try:
                page = browser.new_page(viewport={"width": 800, "height": 800})
                for pig in pigs:
                    pig_id = pig["id"]
                    try:
                        raw_avatar = fetch_avatar(client, base_url, pig_id, raw_dir)
                        normalized_avatar = normalized_dir / f"{pig_id}.png"
                        normalize_avatar(raw_avatar, normalized_avatar)

                        card_path = cards_dir / f"{pig_id}.png"
                        result = render_card(page, template_uri, pig, normalized_avatar, card_path)

                        with Image.open(card_path) as card:
                            if card.size != (800, 800):
                                raise RuntimeError(f"卡片尺寸异常: {card.size}")
                            card.verify()

                        successful.append({"id": pig_id, "name": pig["name"]})
                        entry = {"id": pig_id, "sha256": sha256(card_path), **result}
                        report["generated"].append(entry)
                        if result["descriptionFontSize"] != FONT_STEPS[0][0]:
                            report["scaled"].append(entry)
                        if result["overflow"]:
                            report["overflow"].append(entry)
                    except Exception as exc:  # noqa: BLE001 - 单只失败不应中断整批
                        report["failures"].append({"id": pig_id, "error": f"{type(exc).__name__}: {exc}"})
                        print(f"[失败] {pig_id}: {exc}", file=sys.stderr)
            finally:
                browser.close()

    (output_dir / "index.json").write_text(
        json.dumps({"pigs": successful}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )

    if args.upload and successful:
        prefix = args.cos_prefix.strip("/")
        print(f"上传 COS: region={args.cos_region}, {prefix}/<id>.png, 共 {len(successful)} 张")
        # 凭证缺失/SDK 缺失等整体性失败收敛成一条 uploadFailures, 既保留本地产物又让退出码非 0。
        try:
            report["uploadFailures"] = upload_cards_to_cos(
                cards_dir,
                successful,
                credentials_path=args.cos_credentials,
                region=args.cos_region,
                prefix=args.cos_prefix,
            )
        except RuntimeError as exc:
            report["uploadFailures"] = [{"id": "*", "error": str(exc)}]
            print(f"[上传中止] {exc}", file=sys.stderr)
    elif not args.upload:
        print("已跳过 COS 上传 (--no-upload)")

    (output_dir / "generation-report.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )

    print(
        f"完成: 成功 {len(successful)}, 失败 {len(report['failures'])}, "
        f"仍溢出 {len(report['overflow'])}, 上传失败 {len(report['uploadFailures'])}"
    )
    print(f"产物目录: {output_dir}")
    return 1 if report["failures"] or report["overflow"] or report["uploadFailures"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
