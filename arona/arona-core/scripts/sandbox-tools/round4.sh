#!/bin/bash
# 第四轮: 记忆压缩 (P1). 前置: shadow:true, baseUrl 指向 fake_llm (reply 模式), JVM 已用新 jar 启动.
G=7D22AB5A3347CAE8509F62CDE7B31F96; L=/mnt/d/code/java/arona-reborn/arona/arona-core/sandbox/logs/log.log
f() { python3 fake_webhook.py --gid $G --uid FAKEUSER0006 --username 群友六 "$@" >/dev/null; }
mode() { echo "$1" > llm_mode.txt; }
mem() { python3 chatbot_state.py $G | grep -E "^chatMemory"; }
B=$(wc -l < "$L"); R=$(wc -l < fake_llm_requests.log)
mode reply
python3 chatbot_state.py $G --clear --set-config '{"enabled":true,"probabilityMode":"FIXED","fixedProbability":0.0,"cooldownSec":0}' | head -1
echo "=== A. 65 条普通消息只观察不回 (FIXED 0)"; for i in $(seq 1 65); do f --content "刷屏第 $i 条, 今天聊 $((i % 5)) 号话题"; sleep 0.15; done; sleep 2
echo "落库行数: $(python3 chatbot_state.py $G | grep -c '^   ')  (期望 65)"; mem
echo "=== B. 一条 @ 触发回复 -> 行数达标 -> 压缩 (期望 chatMemory 出现, 日志 '压缩 ... 47 行')"; f --content "总结一下" --at; sleep 5; mem
echo "=== C. 再一条 @: prompt 应带摘要, 未覆盖行不足 60 不再压缩"; f --content "第二次" --at; sleep 4; mem
echo "=== D. bigusage: prompt_tokens=9999 -> token 触发压缩 (期望 '触发: prompt_tokens 9999')"; mode bigusage; f --content "第三次" --at; sleep 5; mem; mode reply
tail -n +$((B+1)) "$L" | iconv -f gbk -t utf-8 -c > round4.log
echo "=== 日志"; grep "chatbot 压缩" round4.log | cut -c1-200
echo "--- WARN/ERROR"; grep -E "WARN|ERROR" round4.log | grep -v "plana\|rethis\|CORS" | cut -c1-300 | head -8
tail -n +$((R+1)) fake_llm_requests.log > round4_llm.log
echo "--- fake llm: 对话请求 $(grep -c '"response_format"' round4_llm.log) 次 (期望 3), 摘要请求 $(grep -c '新摘要' round4_llm.log) 次 (期望 2), 对话 prompt 带摘要 $(grep -c '更早的聊天摘要' round4_llm.log) 次 (期望 2: C 与 D)"
echo "--- 第三次对话的 history 行数 (期望 <= 22):"; grep -A3 '"response_format"' round4_llm.log | tail -4 | grep -o '群友六: [^\\]*' | wc -l
