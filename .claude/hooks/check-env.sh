#!/usr/bin/env bash
# PostToolUse hook — .env / application-*.yml 저장 시 필수 변수 누락 경고.
# exit 0: 경고만 (블로킹 안 함). Claude Code stdin으로 tool-call JSON 수신.

set -u

input="$(cat)"

# 파일 경로 추출 (jq 없이 grep/sed)
file=$(printf '%s' "$input" \
  | tr -d '\n' \
  | grep -oE '"file_path"[[:space:]]*:[[:space:]]*"[^"]*"' \
  | head -1 \
  | sed -E 's/.*"file_path"[[:space:]]*:[[:space:]]*"//; s/"$//' \
  | sed 's/\\\\/\\/g; s/\\"/"/g')

[ -z "${file:-}" ] && exit 0

# 대상 파일만
case "$file" in
  *.env|*.env.*|*/application*.yml|*/application*.yaml)
    ;;
  *)
    exit 0
    ;;
esac

# 존재하지 않으면 종료
[ -f "$file" ] || exit 0

required_keys=(INTERNAL_AUTH_TOKEN JWT_SECRET)
# prod yml은 DATASOURCE도 체크
case "$file" in
  *application-prod.yml|*application-prod.yaml) required_keys+=(SPRING_DATASOURCE_URL) ;;
esac

missing=()
for key in "${required_keys[@]}"; do
  if ! grep -qE "^${key}([[:space:]]|=|:)" "$file"; then
    missing+=("$key")
  fi
done

if [ ${#missing[@]} -gt 0 ]; then
  echo "[check-env] $(basename "$file") 에 선언되지 않은 키: ${missing[*]}"
  echo "           (프로젝트 CLAUDE.md '환경 변수' 섹션 참조)"
fi

exit 0
