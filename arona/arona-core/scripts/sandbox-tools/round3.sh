#!/bin/bash
G=7D22AB5A3347CAE8509F62CDE7B31F96; L=/mnt/d/code/java/arona-reborn/arona/arona-core/sandbox/logs/log.log
f() { python3 fake_webhook.py --gid $G --uid FAKEUSER0006 --username 群友六 "$@" >/dev/null; }
mode() { echo "$1" > llm_mode.txt; }
show() { python3 chatbot_state.py $G | grep -E "redis noop|^   " | tail -n ${1:-6}; }
B=$(wc -l < "$L")
echo "=== 0. 假模型基线 (期望回复 '假模型的回复喵')"; mode reply; f --content "基线" --at; sleep 3; show 2
echo "=== D. 模型异常: empty / invalid / silent / error"; for m in empty invalid silent error; do mode $m; f --content "模式 $m" --at; sleep 3; done; show 8
echo "--- slow (12s > 8s 超时)"; mode slow; f --content "模式 slow" --at; sleep 11; show 2
echo "--- hang (40s > 30s 预算) 期望 BUDGET_EXCEEDED 而非 MODEL_ERROR"; mode hang; f --content "模式 hang" --at; sleep 33; show 2
echo "=== E. 审核: block / slowaudit / noaudit"; mode block; f --content "审核拦截" --at; sleep 3; mode slowaudit; f --content "审核超时" --at; sleep 6; mode noaudit; f --content "没人审" --at; sleep 3; show 6
echo "=== C. 限流: 1.2s 间隔连发 14 条 @ (每分钟 10)"; mode reply; for i in $(seq 1 14); do f --content "限流 $i" --at; sleep 1.2; done; sleep 2; show 2
tail -n +$((B+1)) "$L" | iconv -f gbk -t utf-8 -c > round3.log
echo "=== 日志摘要"; echo "shadow PostGroupMessage 次数: $(grep -c 'skip endpoint=PostGroupMessage' round3.log)  (期望 = 基线1 + 限流放行数)"
echo "提示语 '说太快了' 出现: $(grep -c '说太快了' round3.log)"; echo "audit-stub 被调用: $(grep -c 'audit-stub' round3.log)"
echo "--- chatbot WARN/ERROR"; grep -E "WARN|ERROR" round3.log | grep -v "plana\|rethis" | cut -c1-300 | head -8
echo "--- fake llm: empty 模式请求 $(grep -c 'mode=empty' fake_llm_requests.log) 次, 其中带 response_format 的 $(grep -A1 'mode=empty' fake_llm_requests.log | grep -c response_format) 次 (期望 2 / 1)"
echo "--- fake llm 请求总数: $(grep -c '^[0-9][0-9]:' fake_llm_requests.log)"
