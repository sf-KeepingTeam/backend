#!/usr/bin/env bash
# 측정 도구 — 이 프로젝트의 훅 차단 로그(blocks.jsonl)를 집계
# 사용법: 프로젝트 루트에서  bash tools/harness-stats.sh
LOG=".claude/harness-log/blocks.jsonl"

if [ ! -f "$LOG" ]; then
  echo "로그 없음: $LOG (훅이 아직 아무것도 기록하지 않았습니다)"
  exit 0
fi

TOTAL=$(wc -l < "$LOG" | tr -d ' ')
echo "=== 하네스 통계 ($(basename "$PWD")) ==="
echo "총 기록: $TOTAL 건"
echo ""
echo "--- 훅별 집계 ---"
grep -oE '"hook":"[^"]*"' "$LOG" | sort | uniq -c | sed 's/"hook":"//;s/"//' | sort -rn
echo ""
echo "--- 액션별 집계 (BLOCKED = 시스템이 잡은 건수) ---"
grep -oE '"action":"[^"]*"' "$LOG" | sort | uniq -c | sed 's/"action":"//;s/"//' | sort -rn
echo ""
echo "--- 월별 차단 건수 ---"
grep '"action":"BLOCKED"' "$LOG" | grep -oE '"ts":"[0-9]{4}-[0-9]{2}' | sed 's/"ts":"//' | sort | uniq -c
echo ""
echo "--- 최근 차단 5건 ---"
grep '"action":"BLOCKED"' "$LOG" | tail -5
