#!/usr/bin/env python3
"""打沙箱 webui 后端的 chatbot 图库运营端点 (P3). 直接往 Redis 塞一个 web token 冒充登录用户, 不走 /login.

  chatbot_web.py GID list [--probe]                  列表 (--probe 顺带 GET 每个 url, 验证静态映射能读)
  chatbot_web.py GID update ID [--status S] [--tags a,b] [--summary ...]
  chatbot_web.py GID delete ID
  --user UID  冒充的用户 (默认: 该群第一个 role.admin 成员); 用非管理员 / 不存在的群做负向用例.
退出码: 业务 code != 200 或 HTTP 非 2xx 时为 1, 便于脚本断言.
"""
import argparse, json, os, re, secrets, socket, sys, urllib.error, urllib.request
import pymongo

CFG = os.environ.get("ARONA_SANDBOX_CONFIG", "/mnt/d/code/java/arona-reborn/arona/arona-core/sandbox/config.yaml")
BASE = os.environ.get("ARONA_SANDBOX_WEB", "http://127.0.0.1:12888/api/v1")


def cfg_block(name):
  t = open(CFG, encoding="utf-8").read()
  blk = re.search(r"^" + name + r":\n((?:[ \t]+.*\n?)*)", t, re.M).group(1)
  return lambda k: (re.search(r"^\s*" + k + r":\s*['\"]?([^'\"\n#]*)", blk, re.M) or [None, ""])[1].strip()


def mongo():
  v = cfg_block("mongodb")
  return pymongo.MongoClient(host=v("host"), port=int(v("port")), username=v("user") or None, password=v("password") or None,
                             authSource="admin", serverSelectionTimeoutMS=4000)[v("db")]


def redis_set_ex(key, value, ttl):
  parts = ["SET", key, value, "EX", str(ttl)]
  msg = ("*%d\r\n" % len(parts)) + "".join("$%d\r\n%s\r\n" % (len(p.encode()), p) for p in parts)
  s = socket.create_connection(("127.0.0.1", 6379), timeout=3)
  s.sendall(msg.encode())
  assert s.recv(64).startswith(b"+OK"), "redis SET failed"


def mint_token(user_id):
  token = secrets.token_hex(16)
  redis_set_ex("token." + token, user_id, 600)
  return token


def call(token, method, path, params=None, body=None):
  url = BASE + path + ("?" + "&".join("%s=%s" % kv for kv in params.items()) if params else "")
  data = json.dumps(body).encode() if body is not None else None
  req = urllib.request.Request(url, data=data, method=method, headers={"Authorization": "Bearer " + token, "Content-Type": "application/json"})
  try:
    with urllib.request.urlopen(req, timeout=10) as r:
      return r.status, json.loads(r.read().decode())
  except urllib.error.HTTPError as e:
    return e.code, json.loads(e.read().decode() or "{}")


def probe(url):
  """GET 一下 url (nginx / 沙箱里的 http.server 静态映射), 返回状态码与字节数."""
  try:
    with urllib.request.urlopen(urllib.request.Request(url, method="GET"), timeout=10) as r:
      return "%d %dB" % (r.status, len(r.read()))
  except urllib.error.HTTPError as e:
    return str(e.code)


def main():
  a = argparse.ArgumentParser()
  a.add_argument("gid"); a.add_argument("cmd", choices=["list", "update", "delete"]); a.add_argument("id", nargs="?")
  a.add_argument("--user"); a.add_argument("--status"); a.add_argument("--tags"); a.add_argument("--summary"); a.add_argument("--probe", action="store_true")
  o = a.parse_args()
  user = o.user
  if not user:
    doc = mongo()["Contact"].find_one({"_id": o.gid}, {"members": 1}) or {}
    user = next((m["_id"] for m in doc.get("members", []) if "role.admin" in m.get("roles", [])), None)
    if not user:
      sys.exit("群 %s 没有 role.admin 成员, 用 --user 指定" % o.gid)
  token = mint_token(user)
  if o.cmd == "list":
    status, resp = call(token, "GET", "/chatbot/sticker/list", {"gid": o.gid})
  elif o.cmd == "update":
    body = {"gid": o.gid, "id": o.id}
    if o.status is not None: body["status"] = o.status
    if o.tags is not None: body["tags"] = [t for t in o.tags.split(",")]
    if o.summary is not None: body["summary"] = o.summary
    status, resp = call(token, "POST", "/chatbot/sticker/update", body=body)
  else:
    status, resp = call(token, "DELETE", "/chatbot/sticker", body={"gid": o.gid, "id": o.id})
  print("HTTP %d code=%s message=%s" % (status, resp.get("code"), resp.get("message")))
  for s in (resp.get("data") or []) if o.cmd == "list" else []:
    print("  %s %-8s nsfw=%-4s use=%s tags=%s summary=%s url=%s" % (
      s["id"][:12], s["status"], s["nsfwRisk"], s["useCount"], s["tags"], (s["summary"] or "")[:40], "yes" if s["url"] else "None"))
    if o.probe and s["url"]:
      print("    GET url ->", probe(s["url"]))
  sys.exit(0 if status < 300 and resp.get("code") == 200 else 1)


if __name__ == "__main__":
  main()
