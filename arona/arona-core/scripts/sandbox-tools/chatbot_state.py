#!/usr/bin/env python3
"""查看沙箱里 chatbot 的 Mongo (chatContext/chatNoop/群配置) 与 Redis noop 计数. 用法: inspect.py GID [--set-config JSON] [--clear]"""
import argparse, json, os, re, socket, sys
import pymongo
CFG = os.environ.get("ARONA_SANDBOX_CONFIG", "/mnt/d/code/java/arona-reborn/arona/arona-core/sandbox/config.yaml")
KEY = "config.com·diyigemt·arona·chatbot.ChatbotConfig"

def mongo():
  t = open(CFG, encoding="utf-8").read()
  blk = re.search(r"^mongodb:\n((?:[ \t]+.*\n?)*)", t, re.M).group(1)
  v = lambda k: (re.search(r"^\s*" + k + r":\s*['\"]?([^'\"\n#]*)", blk, re.M) or [None, ""])[1].strip()
  return pymongo.MongoClient(host=v("host"), port=int(v("port")), username=v("user") or None, password=v("password") or None,
                             authSource="admin", serverSelectionTimeoutMS=4000)[v("db")]

def redis_hgetall(key):
  s = socket.create_connection(("127.0.0.1", 6379), timeout=3)
  s.sendall(("*2\r\n$7\r\nHGETALL\r\n$%d\r\n%s\r\n" % (len(key.encode()), key)).encode())
  parts = s.recv(65536).decode().split("\r\n")
  vals = [p for p in parts[1:] if p and not p.startswith("$")]
  return dict(zip(vals[::2], vals[1::2]))

a = argparse.ArgumentParser(); a.add_argument("gid"); a.add_argument("--set-config"); a.add_argument("--clear", action="store_true")
o = a.parse_args(); db = mongo()
if o.set_config:
  r = db["Contact"].update_one({"_id": o.gid}, {"$set": {KEY: json.loads(o.set_config)}}); print("set config matched/modified:", r.matched_count, r.modified_count)
if o.clear:
  print("cleared chatContext/chatNoop/chatMemory:", db["chatContext"].delete_many({"groupId": o.gid}).deleted_count, db["chatNoop"].delete_many({"groupId": o.gid}).deleted_count, db["chatMemory"].delete_many({"_id": o.gid}).deleted_count)
doc = db["Contact"].find_one({"_id": o.gid}, {KEY: 1}) or {}
print("group config:", json.dumps(doc.get("config", {}).get("com·diyigemt·arona·chatbot", {}).get("ChatbotConfig"), ensure_ascii=False))
m = db["chatMemory"].find_one({"_id": o.gid})
print("chatMemory:", "none" if not m else f"coveredUntil={m['coveredUntil'].strftime('%H:%M:%S.%f')[:-3]} updatedAt={m['updatedAt'].strftime('%H:%M:%S')} summary={m.get('summary')!r}")
print("chatContext:")
for d in db["chatContext"].find({"groupId": o.gid}).sort("ts", 1): print("  ", d["ts"].strftime("%H:%M:%S"), "bot" if d.get("fromBot") else d.get("senderName") or d.get("senderId"), "|", d["_id"], "|", d.get("content"))
print("chatNoop:")
for d in db["chatNoop"].find({"groupId": o.gid}).sort("ts", 1): print("  ", d["ts"].strftime("%H:%M:%S"), d["reason"], d.get("messageId"), "|", (d.get("detail") or "")[:160])
print("redis noop:", redis_hgetall("chatbot.noop." + o.gid))
