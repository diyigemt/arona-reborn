#!/usr/bin/env python3
"""本机假 OpenAI 兼容 /chat/completions, 用于逼出 chatbot 的各种模型异常分支.

模式由文件 tools/llm_mode.txt 控制 (改文件即生效, 不用重启):
  reply   -> {"reply": "假模型的回复喵", "silent": false}
  empty   -> content 为空串 (DeepSeek json_object 偶发行为) -> 期望 chatbot 重试一次后记 JSON_EMPTY
  invalid -> content 是一段非 JSON 文本 -> JSON_INVALID
  silent  -> {"silent": true} -> MODEL_SILENT
  error   -> HTTP 500 -> MODEL_ERROR
  slow    -> 睡 12s 再回 (超过 llmTimeoutMillis 8s) -> MODEL_ERROR (超时)
  hang    -> 睡 40s (超过 totalBudgetMillis 30s) -> BUDGET_EXCEEDED
  block   -> 回复里带 BLOCKME 标记 (配合 audit-stub 插件验证 AUDIT_BLOCK)
  noaudit -> 回复里带 NOAUDIT (audit-stub 不置位 -> AUDIT_UNAVAILABLE)
  slowaudit -> 回复里带 SLOWME (audit-stub 睡 5s -> 审核超时 AUDIT_UNAVAILABLE)
  bigusage -> 正常回复但 usage.prompt_tokens=9999 (逼出记忆压缩的 token 触发条件)
摘要请求 (无 response_format 且 user 内容含 "新摘要") 一律返回纯文本 "假摘要: ...", 与模式无关.
把 chatbot config.yml 的 baseUrl 指到 http://127.0.0.1:18080 后重启 arona 即可.
"""
import json, os, sys, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MODE_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "llm_mode.txt")
LOG = os.path.join(os.path.dirname(os.path.abspath(__file__)), "fake_llm_requests.log")


def mode():
  try:
    return open(MODE_FILE, encoding="utf-8").read().strip() or "reply"
  except FileNotFoundError:
    return "reply"


class H(BaseHTTPRequestHandler):
  def log_message(self, *a):  # 安静
    pass

  def do_POST(self):
    body = self.rfile.read(int(self.headers.get("Content-Length", 0))).decode("utf-8", "replace")
    m = mode()
    with open(LOG, "a", encoding="utf-8") as f:
      f.write(f"{time.strftime('%H:%M:%S')} mode={m} path={self.path}\n{body}\n\n")
    if m == "error":
      return self.reply(500, {"error": {"message": "fake upstream failure", "type": "server_error"}})
    if m == "slow":
      time.sleep(12)
    if m == "hang":
      time.sleep(40)
    if "response_format" not in body and "新摘要" in body:
      return self.completion("假摘要: 群友们一直在刷测试消息, 机器人偶尔回一句喵.", 1)
    content = {
      "reply": json.dumps({"reply": "假模型的回复喵", "silent": False}, ensure_ascii=False),
      "empty": "",
      "invalid": "我偏不用 JSON 回答你",
      "silent": json.dumps({"silent": True}),
      "block": json.dumps({"reply": "BLOCKME 这句话应该被审核拦下", "silent": False}, ensure_ascii=False),
      "noaudit": json.dumps({"reply": "NOAUDIT 没人审这句", "silent": False}, ensure_ascii=False),
      "slowaudit": json.dumps({"reply": "SLOWME 审核会超时", "silent": False}, ensure_ascii=False),
    }.get(m, json.dumps({"reply": f"模式 {m} 的回复喵", "silent": False}, ensure_ascii=False))
    self.completion(content, 9999 if m == "bigusage" else 1)

  def completion(self, content, prompt_tokens):
    self.reply(200, {"id": "fake", "object": "chat.completion", "model": "fake",
                     "choices": [{"index": 0, "message": {"role": "assistant", "content": content}, "finish_reason": "stop"}],
                     "usage": {"prompt_tokens": prompt_tokens, "completion_tokens": 1, "total_tokens": prompt_tokens + 1}})

  def reply(self, code, obj):
    data = json.dumps(obj, ensure_ascii=False).encode("utf-8")
    self.send_response(code); self.send_header("Content-Type", "application/json"); self.send_header("Content-Length", str(len(data)))
    self.end_headers(); self.wfile.write(data)


if __name__ == "__main__":
  port = int(sys.argv[1]) if len(sys.argv) > 1 else 18080
  print(f"fake llm on 127.0.0.1:{port}, mode file {MODE_FILE}", flush=True)
  ThreadingHTTPServer(("127.0.0.1", port), H).serve_forever()
