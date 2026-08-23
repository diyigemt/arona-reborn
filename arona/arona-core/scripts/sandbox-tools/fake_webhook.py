#!/usr/bin/env python3
"""向本机 arona 伪造腾讯 webhook 群消息事件 (Ed25519 签名与平台一致), 用于沙箱联调.

签名密钥从 sandbox/config.yaml 的 bot.secret 派生 (secret 循环填满 32 字节作 seed), 与 WebhookBot.kt 同源; secret 不会被打印.
例:
  fake_webhook.py --gid G --uid U --content "阿罗娜 在吗"
  fake_webhook.py --gid G --uid U --content "在吗" --at            # GROUP_AT_MESSAGE_CREATE + mentions.is_you
  fake_webhook.py --gid G --uid U --content "旧消息" --age-sec 180  # 陈旧消息 (STALE)
  fake_webhook.py --gid G --uid U --content "并发" --repeat 3 --parallel
"""
import argparse, concurrent.futures, datetime, json, os, re, sys, time, uuid
import requests
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

DEFAULT_CONFIG = os.environ.get("ARONA_SANDBOX_CONFIG", "/mnt/d/code/java/arona-reborn/arona/arona-core/sandbox/config.yaml")


def load_secret(path):
  text = open(path, encoding="utf-8").read()
  bot = re.search(r"^bot:\n((?:[ \t]+.*\n?)*)", text, re.M).group(1)
  return re.search(r"^\s*secret:\s*['\"]?([^'\"\n#]*)", bot, re.M).group(1).strip()


def signer(secret):
  raw = secret.encode("utf-8")
  seed = bytes(raw[i % len(raw)] for i in range(32))
  key = Ed25519PrivateKey.from_private_bytes(seed)
  return lambda ts, body: key.sign((ts + body).encode("utf-8")).hex()


def payload(a, n):
  now = datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=8))) - datetime.timedelta(seconds=a.age_sec)
  msg_id = a.msg_id or f"fake-{uuid.uuid4().hex[:12]}"
  d = {
    "id": msg_id if a.repeat == 1 else f"{msg_id}-{n}" if not a.same_msg_id else msg_id,
    "author": {"member_openid": a.uid, "username": a.username},
    "content": a.content,
    "timestamp": now.isoformat(timespec="seconds"),
    "group_openid": a.gid,
  }
  if a.at:
    d["mentions"] = [{"is_you": True}]
  if a.image:
    d["attachments"] = [{"url": "https://example.invalid/fake.jpg"}]
  return {"id": f"evt-{uuid.uuid4().hex[:12]}", "op": 0, "s": 1,
          "t": "GROUP_AT_MESSAGE_CREATE" if a.at else "GROUP_MESSAGE_CREATE", "d": d}


def send(a, sign, n):
  body = json.dumps(payload(a, n), ensure_ascii=False)
  ts = str(int(time.time()))
  r = requests.post(a.url, data=body.encode("utf-8"), timeout=10, headers={
    "Content-Type": "application/json",
    "x-signature-ed25519": sign(ts, body),
    "x-signature-timestamp": ts,
  })
  return n, r.status_code, r.text[:120], json.loads(body)["d"]["id"]


def main():
  p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
  p.add_argument("--gid", required=True); p.add_argument("--uid", required=True)
  p.add_argument("--content", required=True); p.add_argument("--username", default="测试群友")
  p.add_argument("--at", action="store_true", help="模拟 @ 机器人")
  p.add_argument("--image", action="store_true", help="附带一张图片附件 (content 为空时即纯图片消息)")
  p.add_argument("--age-sec", type=int, default=0, help="消息创建时间距今秒数")
  p.add_argument("--msg-id", help="指定 d.id (默认随机)")
  p.add_argument("--same-msg-id", action="store_true", help="repeat 时复用同一 d.id (模拟 webhook 重投)")
  p.add_argument("--repeat", type=int, default=1); p.add_argument("--parallel", action="store_true")
  p.add_argument("--url", default="http://127.0.0.1:12888/api/v1/webhook"); p.add_argument("--config", default=DEFAULT_CONFIG)
  a = p.parse_args()
  sign = signer(load_secret(a.config))
  if a.parallel and a.repeat > 1:
    with concurrent.futures.ThreadPoolExecutor(a.repeat) as ex:
      results = list(ex.map(lambda n: send(a, sign, n), range(a.repeat)))
  else:
    results = [send(a, sign, n) for n in range(a.repeat)]
  for n, code, text, mid in results:
    print(f"#{n} msg_id={mid} -> HTTP {code} {text}")
  sys.exit(0 if all(c == 200 for _, c, _, _ in results) else 1)


if __name__ == "__main__":
  main()
