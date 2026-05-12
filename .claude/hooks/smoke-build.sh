#!/usr/bin/env bash
# PostToolUse hook — Java 파일 저장 시 해당 서비스의 compileJava만 빠르게 돌려 컴파일 오류 조기 감지.
# 테스트는 실행하지 않음. Gradle daemon 캐시가 있으면 1~5초.
# exit 0 유지 — 빌드 실패해도 블로킹 안 함 (출력만).

set -u

input="$(cat)"

file=$(printf '%s' "$input" \
  | tr -d '\n' \
  | grep -oE '"file_path"[[:space:]]*:[[:space:]]*"[^"]*"' \
  | head -1 \
  | sed -E 's/.*"file_path"[[:space:]]*:[[:space:]]*"//; s/"$//' \
  | sed 's/\\\\/\\/g; s/\\"/"/g')

[ -z "${file:-}" ] && exit 0

# Java 파일만
case "$file" in
  *.java) ;;
  *) exit 0 ;;
esac

# 서비스 판별 (Windows 경로 / POSIX 양쪽 대응)
lower=$(printf '%s' "$file" | tr '[:upper:]' '[:lower:]' | tr '\\' '/')
if [[ "$lower" == *"/monolith/"* ]]; then
  proj_dir="/c/keeping/backend/monolith"
  label="monolith"
elif [[ "$lower" == *"/qr-service/"* ]]; then
  proj_dir="/c/keeping/backend/qr-service"
  label="qr-service"
else
  exit 0
fi

[ -x "$proj_dir/gradlew" ] || exit 0

echo "[smoke-build] $label compileJava ..."
# 조용히 실행하고 실패 시 마지막 몇 줄만
out=$(cd "$proj_dir" && ./gradlew -q --no-daemon=false compileJava 2>&1)
rc=$?
if [ "$rc" -ne 0 ]; then
  echo "[smoke-build] FAILED (rc=$rc)"
  printf '%s\n' "$out" | tail -15
else
  echo "[smoke-build] OK"
fi

exit 0
