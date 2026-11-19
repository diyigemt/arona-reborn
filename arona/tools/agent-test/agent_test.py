#!/usr/bin/env python3
"""arona 真群测试客户端: 给 AI agent 用, 通过 test 插件的 /test/agent 端点注入群消息并取回 bot 的出站回复.

只用标准库. 凭据存在 ~/.config/arona-agent-test/session.json (权限 600), 环境变量 ARONA_API / ARONA_TOKEN 优先.

  agent_test.py login --api https://<host>/api/v1   # 打印验证码, 由超管在 QQ 里对 bot 发 "/登录 <验证码>"
  agent_test.py targets                              # 列出已登记的测试群/成员别名
  agent_test.py inject -g test -m me -M capture "/帮助"
  agent_test.py inject -g test -m me -M send --idle 6000 "阿罗娜 今天吃什么"

inject 输出 JSON (data 字段): finishedBy / dispatchError / messages[{offsetMs, ok, receiptId, replyToInjection, text, elements, error}].
inject 不是幂等的, 本脚本不会自动重试; 失败时退出码非 0, 错误写到 stderr.
"""
import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

SESSION_DIR = os.path.join(os.path.expanduser("~"), ".config", "arona-agent-test")
SESSION_FILE = os.path.join(SESSION_DIR, "session.json")


def check_api(api):
    """token 是超管凭据: 只发往 https, 本机沙箱 (localhost/127.0.0.1) 例外."""
    u = urllib.parse.urlsplit(api)
    if u.scheme == "https" or (u.scheme == "http" and u.hostname in ("localhost", "127.0.0.1", "::1")):
        return api.rstrip("/")
    die(f"API 地址必须是 https (本机除外): {api}")


def load_session():
    """环境变量必须成对给出, 否则用 session 文件里成对保存的 (api, token), 避免把 token 发往别的地址."""
    env_api, env_token = os.environ.get("ARONA_API"), os.environ.get("ARONA_TOKEN")
    if env_api or env_token:
        if not (env_api and env_token):
            die("ARONA_API 与 ARONA_TOKEN 要么都设置, 要么都不设置")
        return check_api(env_api), env_token
    try:
        with open(SESSION_FILE, encoding="utf-8") as f:
            data = json.load(f)
    except (OSError, ValueError):
        data = {}
    api, token = data.get("api") or "", data.get("token") or ""
    return (check_api(api) if api else ""), token


def save_session(api, token):
    os.makedirs(SESSION_DIR, mode=0o700, exist_ok=True)
    fd = os.open(SESSION_FILE, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as f:
        json.dump({"api": api, "token": token}, f)


def die(msg, code=1):
    print(msg, file=sys.stderr)
    sys.exit(code)


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    # 默认处理器跟随重定向时会带上 Authorization 头.
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise urllib.error.HTTPError(req.full_url, code, f"拒绝重定向到 {newurl}", headers, fp)


_opener = urllib.request.build_opener(_NoRedirect)


def call(method, url, token=None, body=None, timeout=30):
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Accept", "application/json")
    if data is not None:
        req.add_header("Content-Type", "application/json; charset=utf-8")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with _opener.open(req, timeout=timeout) as resp:
            payload = resp.read().decode("utf-8")
    except urllib.error.HTTPError as e:
        hint = {401: "token 无效或已过期 (1 小时无访问即失效), 请重新 login", 403: "当前账号不是超管"}.get(e.code, "")
        die(f"HTTP {e.code} {e.reason} {hint}".strip())
    except urllib.error.URLError as e:
        die(f"请求失败: {e.reason}")
    try:
        parsed = json.loads(payload)
    except ValueError:
        die(f"响应不是 JSON: {payload[:200]}")
    if parsed.get("code") != 200:
        die(f"业务失败 code={parsed.get('code')}: {parsed.get('message')}")
    return parsed.get("data")


def cmd_login(args):
    if not args.api:
        die("缺少 --api (形如 https://<host>/api/v1)")
    api = check_api(args.api)
    code = call("GET", f"{api}/user/login")
    print(f"请超管在 QQ 里对 bot 发送: /登录 {code}  (10 分钟内有效)", file=sys.stderr)
    deadline = time.monotonic() + 600
    while time.monotonic() < deadline:
        time.sleep(3)
        r = call("GET", f"{api}/user/login?" + urllib.parse.urlencode({"token": code}))
        if r.get("status") == 2 and r.get("token"):
            save_session(api, r["token"])
            print(f"登录成功, 凭据已存到 {SESSION_FILE}", file=sys.stderr)
            return
        if r.get("status") == 0:
            die("验证码已失效, 请重新 login")
    die("等待确认超时")


def require_session():
    api, token = load_session()
    if not api or not token:
        die("未登录: 先运行 login, 或设置 ARONA_API 与 ARONA_TOKEN")
    return api, token


def cmd_targets(_args):
    api, token = require_session()
    print(json.dumps(call("GET", f"{api}/test/agent/targets", token), ensure_ascii=False, indent=2))


def cmd_inject(args):
    api, token = require_session()
    body = {
        "group": args.group,
        "member": args.member,
        "mode": args.mode,
        "content": args.content,
        "at": not args.no_at,
        "imageUrls": args.image or [],
    }
    if args.username:
        body["username"] = args.username
    if args.quote is not None:
        body["quoted"] = {"content": args.quote}
    if args.idle is not None:
        body["idleMs"] = args.idle
    if args.timeout is not None:
        body["timeoutMs"] = args.timeout
    # 服务端最长等 maxTimeoutMs (默认 120s), 客户端多留余量.
    wait = (args.timeout or 120_000) / 1000 + 30
    print(json.dumps(call("POST", f"{api}/test/agent/inject", token, body, timeout=wait), ensure_ascii=False, indent=2))


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)

    lp = sub.add_parser("login", help="走 webui 的 QQ 验证码登录, 保存超管 token")
    lp.add_argument("--api", required=True, help="API 根地址, 含 /api/v1")
    lp.set_defaults(func=cmd_login)

    tp = sub.add_parser("targets", help="列出已登记的测试群与成员别名")
    tp.set_defaults(func=cmd_targets)

    ip = sub.add_parser("inject", help="注入一条群消息并收集回复")
    ip.add_argument("-g", "--group", required=True, help="群别名")
    ip.add_argument("-m", "--member", required=True, help="成员别名")
    ip.add_argument("-M", "--mode", required=True, choices=["send", "capture"],
                    help="send: 真发到测试群; capture: 只截获不出网")
    ip.add_argument("--no-at", action="store_true", help="不 @ bot (默认 @)")
    ip.add_argument("--username", help="发送者展示名")
    ip.add_argument("--quote", help="模拟引用消息的原文")
    ip.add_argument("--image", action="append", help="https 图片附件, 可重复")
    ip.add_argument("--idle", type=int, help="最后一条回复后静默多少毫秒算结束")
    ip.add_argument("--timeout", type=int, help="总等待上限毫秒")
    ip.add_argument("content", nargs="?", default="", help="消息正文")
    ip.set_defaults(func=cmd_inject)

    args = p.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
