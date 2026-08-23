#!/usr/bin/env python3
"""本机假图床 (P2 看图/表情库联调), 纯标准库生成 PNG, 不依赖 PIL.

  /meme.png   200x200 纯色方图       -> 过尺寸启发式, 进视觉模型打标
  /meme2.png  180x180 另一种颜色      -> 第二张不同 hash 的表情
  /long.png   300x1800 长图           -> 长宽比 6 > 3, 不入库 (不调模型)
  /wide.png   2048x400 大图           -> 最长边 > 1024, 不入库
  /huge.bin   3MB 随机字节 (非图片)   -> Content-Length 超 stickerMaxBytes 直接放弃; 看图路径 (4MB 上限) 下载后魔数不是图片也丢弃
  /text.html  非图片                  -> 魔数不是图片, 丢弃
用法: fake_image.py [port=18081]; 配合 fake_webhook.py --image --image-url http://127.0.0.1:18081/meme.png
"""
import os, struct, sys, zlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def png(width, height, rgb):
  raw = b"".join(b"\x00" + bytes(rgb) * width for _ in range(height))
  def chunk(tag, data):
    return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
  return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
          + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


FILES = {
  "/meme.png": ("image/png", png(200, 200, (255, 128, 0))),
  "/meme2.png": ("image/png", png(180, 180, (0, 128, 255))),
  "/long.png": ("image/png", png(300, 1800, (200, 200, 200))),
  "/wide.png": ("image/png", png(2048, 400, (100, 200, 100))),
  "/huge.bin": ("application/octet-stream", os.urandom(3 * 1024 * 1024)),
  "/text.html": ("text/html", b"<html><body>not an image</body></html>"),
}


class H(BaseHTTPRequestHandler):
  def log_message(self, *a):
    pass

  def do_GET(self):
    hit = FILES.get(self.path.split("?")[0])
    if not hit:
      self.send_response(404); self.end_headers(); return
    ctype, data = hit
    self.send_response(200); self.send_header("Content-Type", ctype); self.send_header("Content-Length", str(len(data)))
    self.end_headers(); self.wfile.write(data)


if __name__ == "__main__":
  port = int(sys.argv[1]) if len(sys.argv) > 1 else 18081
  print(f"fake image on 127.0.0.1:{port}: " + " ".join(FILES), flush=True)
  ThreadingHTTPServer(("127.0.0.1", port), H).serve_forever()
