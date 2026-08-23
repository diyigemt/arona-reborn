#!/bin/bash
# 第六轮: 图库运营端点 (P3). 前置: shadow:true, baseUrl 指向 fake_llm, chatbot config stickerAutoApprove:false 且 stickerPublicBaseUrl 指向 18082
# (python3 -m http.server 18082 --directory sandbox/data/com.diyigemt.arona.chatbot/sticker 冒充 nginx),
# 测试群里有一个 role.admin 成员 (chatbot_web.py 自动选), fake_llm.py (18080) 与 fake_image.py (18081) 已启动, JVM 已用新 core jar + 新 chatbot jar 启动.
G=7D22AB5A3347CAE8509F62CDE7B31F96; L=/mnt/d/code/java/arona-reborn/arona/arona-core/sandbox/logs/log.log
IMG=http://127.0.0.1:18081
f() { python3 fake_webhook.py --gid $G --uid FAKEUSER0007 --username 群友七 "$@" >/dev/null; }
mode() { echo "$1" > llm_mode.txt; }
smode() { echo "$1" > llm_sticker.txt; }
web() { python3 chatbot_web.py $G "$@"; echo "  (exit $?)"; }
stickers() { python3 chatbot_state.py $G | sed -n '/^chatSticker/,/^chatNoop/p' | grep -v "^chatNoop\|^chatSticker"; }
B=$(wc -l < "$L")
mode reply; smode meme
python3 chatbot_state.py $G --clear --set-config '{"enabled":true,"probabilityMode":"FIXED","fixedProbability":0.0,"cooldownSec":0,"stickerCapture":true,"stickerReplyProbability":1.0}' | head -1
echo "=== A. 发表情图 (autoApprove=false): 入库 pending + 数据目录里出现文件"
f --content "" --image --image-url $IMG/meme.png; sleep 6; stickers | cut -c1-160
ID=$(python3 chatbot_state.py $G | sed -n '/^chatSticker/,/^chatNoop/p' | grep -o '[0-9a-f]\{64\}' | head -1); echo "id=$ID"
echo "=== B. list (管理员): 1 行 pending 带 url, GET url -> 200 (静态映射可读)"
web list --probe
echo "=== C. 负向: 非管理员 -> 权限不足(601); 不存在的群 -> 群不存在(601); 非法 status -> HTTP 400; 空改动 -> 400; 不存在的 id -> 601"
web list --user 1000002 | head -1
python3 chatbot_web.py NOPE_GROUP list --user 1141217 | head -1
web update $ID --status analyzing | head -1
web update $ID | head -1
web update 0000000000000000000000000000000000000000000000000000000000000000 --status ready | head -1
echo "=== D. 审核通过 + 改 tags/summary -> list 显示 ready 与新 tags"
web update $ID --status ready --tags "无语,猫,翻白眼" --summary "人工改过的描述" | head -1
web list | sed -n 2p
echo "=== E. sticker 模式 @ (概率 1.0): 模型关键词 '无语 猫' 命中人工 tags -> 图文一条发送, useCount=1"
mode sticker; f --content "来个表情" --at; sleep 8; mode reply
web list | sed -n 2p | grep -o "use=[0-9]*"
echo "=== F. 隐藏后选图不再命中 -> 纯文本出站 (useCount 仍 1)"
web update $ID --status hidden | head -1
mode sticker; f --content "再来个表情" --at; sleep 8; mode reply
web list | sed -n 2p | grep -o "ready\|hidden\|use=[0-9]*" | tr '\n' ' '; echo
echo "=== G. 删除: Mongo 行没了, list 空, 旧 URL GET -> 404 (文件已删)"
python3 - <<EOF
import subprocess, json, re, sys
sys.path.insert(0, '.')
import chatbot_web as w
tok = w.mint_token('1141217')
st, resp = w.call(tok, 'GET', '/chatbot/sticker/list', {'gid': '$G'})
url = (resp.get('data') or [{}])[0].get('url')
st, resp = w.call(tok, 'DELETE', '/chatbot/sticker', body={'gid': '$G', 'id': '$ID'})
print('delete ->', resp.get('code'), resp.get('message'))
st, resp = w.call(tok, 'GET', '/chatbot/sticker/list', {'gid': '$G'})
print('list after delete ->', len(resp.get('data') or []), 'rows')
print('GET old url ->', w.probe(url) if url else 'no url')
EOF
echo "chatSticker 行数 (期望 0): $(stickers | wc -l)"
tail -n +$((B+1)) "$L" | iconv -f gbk -t utf-8 -c > round6.log
echo "=== 日志"; grep -E "表情入库|取表情|删表情文件|chatbot" round6.log | cut -c1-200 | head -10
echo "--- WARN/ERROR"; grep -E "WARN|ERROR" round6.log | grep -v "plana\|rethis\|CORS" | cut -c1-300 | head -10
echo "--- 出站行 (期望 E 带 [图片], F 不带):"; python3 chatbot_state.py $G | grep " bot |" | cut -c1-160
