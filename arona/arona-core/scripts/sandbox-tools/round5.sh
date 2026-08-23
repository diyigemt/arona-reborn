#!/bin/bash
# 第五轮: 看图 + 表情库 (P2). 前置: shadow:true, baseUrl 指向 fake_llm, chatbot config 填了 COS 凭据且 stickerAutoApprove:true,
# fake_llm.py (18080) 与 fake_image.py (18081) 已启动, JVM 已用新 jar 启动.
G=7D22AB5A3347CAE8509F62CDE7B31F96; L=/mnt/d/code/java/arona-reborn/arona/arona-core/sandbox/logs/log.log
IMG=http://127.0.0.1:18081
f() { python3 fake_webhook.py --gid $G --uid FAKEUSER0007 --username 群友七 "$@" >/dev/null; }
mode() { echo "$1" > llm_mode.txt; }
smode() { echo "$1" > llm_sticker.txt; }
stickers() { python3 chatbot_state.py $G | sed -n '/^chatSticker/,/^chatNoop/p' | grep -v "^chatNoop"; }
B=$(wc -l < "$L"); R=$(wc -l < fake_llm_requests.log)
mode reply; smode meme
python3 chatbot_state.py $G --clear --set-config '{"enabled":true,"probabilityMode":"FIXED","fixedProbability":0.0,"cooldownSec":0,"stickerCapture":true,"stickerReplyProbability":1.0}' | head -1
echo "=== A. 普通消息带表情图 (FIXED 0 不回): 观察 -> 抓取 -> 打标 meme -> COS -> ready (autoApprove), 且行上出现图片描述"
f --content "" --image --image-url $IMG/meme.png; sleep 6; stickers; python3 chatbot_state.py $G | grep "图片:" | cut -c1-200
echo "=== B. 同一张图再发 (webhook 不同 msg_id): 去重, 不再调模型 (期望 chatSticker 仍 1 行)"
f --content "再发一次" --image --image-url $IMG/meme.png; sleep 3; stickers | wc -l
echo "=== C. 长图 / 大图 / 非图片 / 超大: 都不入库"
f --content "" --image --image-url $IMG/long.png; f --content "" --image --image-url $IMG/wide.png; f --content "" --image --image-url $IMG/text.html; f --content "" --image --image-url $IMG/huge.bin; sleep 5; echo "chatSticker 行数 (期望 1): $(stickers | wc -l)"
echo "=== D. 模型判不是表情 / nsfw high -> rejected, 不传 COS (cosKey=None)"
smode nomeme; f --content "" --image --image-url $IMG/meme2.png; sleep 5; stickers | tail -1 | cut -c1-200; smode meme
echo "=== E. @ + 图: 看图路径, 期望回复 '看到图了喵 (1 张)' 且请求 images=1 无 response_format"
f --content "这是什么" --at --image --image-url $IMG/meme.png; sleep 6
echo "=== F. sticker 模式 @: 概率 1.0 -> system prompt 带配图约定 -> 模型给关键词 '无语 猫' -> 选中 ready 表情 -> COS 取回 -> (shadow) 上传 -> 图文一条发送, useCount=1"
mode sticker; f --content "来个表情" --at; sleep 8; stickers | head -1 | cut -c1-200; mode reply
tail -n +$((B+1)) "$L" | iconv -f gbk -t utf-8 -c > round5.log
echo "=== 日志"; grep -E "表情入库|表情|看图|uploadImage|shadow\] skip" round5.log | cut -c1-220 | head -12
echo "--- WARN/ERROR"; grep -E "WARN|ERROR" round5.log | grep -v "plana\|rethis\|CORS" | cut -c1-300 | head -10
tail -n +$((R+1)) fake_llm_requests.log > round5_llm.log
echo "--- fake llm: 打标请求 $(grep -c '表情包分类器' round5_llm.log) 次 (期望 2: A 与 D), 看图对话 $(grep -c 'images=1' round5_llm.log | head -1) 次含打标, 配图约定进 system $(grep -c '可以配一张表情包' round5_llm.log) 次 (期望 1: F)"
echo "--- 出站行 (期望 E 文本 + F 带 [图片]):"; python3 chatbot_state.py $G | grep " bot |" | cut -c1-160
