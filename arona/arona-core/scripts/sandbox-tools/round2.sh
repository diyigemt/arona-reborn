#!/bin/bash
G=7D22AB5A3347CAE8509F62CDE7B31F96; L=/mnt/d/code/java/arona-reborn/arona/arona-core/sandbox/logs/log.log
f() { python3 fake_webhook.py --gid $G --uid FAKEUSER0005 --username 群友五 "$@" >/dev/null; }
mode() { echo "$1" > llm_mode.txt; }
show() { python3 chatbot_state.py $G | grep -E "redis noop|^   " | tail -n ${1:-6}; }
cfg() { python3 chatbot_state.py $G --set-config "$1" | head -1; }
B=$(wc -l < "$L")
echo "=== A. 成功路径 (shadow 发送 + 出站行)"; mode reply; f --content "你好呀" --at; sleep 3; show 4
echo "=== B. 冷却: 概率路径两条 2s 内 -> 第二条 COOLDOWN; Must 不受冷却"; f --content "冷却测试一"; sleep 2; f --content "冷却测试二"; sleep 2; f --content "必答不受冷却" --at; sleep 3; show 5
echo "=== F. PITY base0 step1 cooldown0: 期望 MISS, 回复, MISS"; cfg '{"enabled":true,"mustPrefixes":["阿罗娜"],"probabilityMode":"PITY","pityBase":0.0,"pityStep":1.0,"cooldownSec":0,"muteKeywords":["闭嘴"],"muteDurationSec":5}'
for i in 1 2 3; do f --content "抽卡第 $i 条"; sleep 3; done; show 5
cfg '{"enabled":true,"mustPrefixes":["阿罗娜"],"probabilityMode":"FIXED","fixedProbability":1.0,"cooldownSec":0,"muteKeywords":["闭嘴"],"muteDurationSec":5}'
echo "=== G. 闭嘴 (5s) 确认语经 shadow 发出, 期间 @ -> MUTED"; f --content "阿罗娜 闭嘴"; sleep 2; f --content "还在吗" --at; sleep 2; show 3; sleep 5
echo "=== D. 模型异常分支"; for m in empty invalid silent error; do mode $m; f --content "模式 $m" --at; sleep 3; done; show 6
echo "--- slow (12s > 8s 超时)"; mode slow; f --content "模式 slow" --at; sleep 11; show 2
echo "--- hang (40s > 30s 预算) 期望 BUDGET_EXCEEDED 而非 MODEL_ERROR"; mode hang; f --content "模式 hang" --at; sleep 33; show 2
echo "=== E. 审核分支"; mode block; f --content "审核拦截" --at; sleep 3; mode slowaudit; f --content "审核超时" --at; sleep 6; mode noaudit; f --content "没人审" --at; sleep 3; show 5
echo "=== C. 限流: 零延迟模型下 1.2s 间隔连发 14 条 @"; mode reply; for i in $(seq 1 14); do f --content "限流 $i" --at; sleep 1.2; done; sleep 2; show 2
echo "=== 日志摘要"; tail -n +$((B+1)) "$L" | iconv -f gbk -t utf-8 -c > round2.log
echo "shadow PostGroupMessage 次数: $(grep -c 'skip endpoint=PostGroupMessage' round2.log)"
grep -E "sending message" round2.log | sed -E 's/.*"content":"([^"]{0,40}).*/  发: \1/' | sort | uniq -c | sort -rn | head -12
echo "--- WARN/ERROR"; grep -E "WARN|ERROR" round2.log | grep -v "plana\|rethis" | cut -c1-260 | head -10
echo "--- fake llm: empty 模式请求数 (期望 2, 第二次无 response_format)"; grep -c "mode=empty" fake_llm_requests.log; grep -A1 "mode=empty" fake_llm_requests.log | grep -c response_format
