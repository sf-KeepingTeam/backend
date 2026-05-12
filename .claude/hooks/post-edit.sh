#!/usr/bin/env bash
# post-edit.sh
# Java 파일 편집 후 자동 컴파일 체크
# Hook이 stdin으로 JSON을 받으면 파일 경로를 추출해서 해당 모듈만 컴파일

set -euo pipefail

# stdin에서 tool input JSON 읽기
INPUT=$(cat)
FILE_PATH=$(echo "$INPUT" | python3 -c "
import sys, json
try:
    data = json.load(sys.stdin)
    # Edit 툴: file_path 또는 path 필드
    path = data.get('file_path') or data.get('path') or ''
    print(path)
except:
    print('')
" 2>/dev/null || true)

# 파일 경로가 없으면 스킵
if [ -z "$FILE_PATH" ]; then
  exit 0
fi

# .java 파일이 아니면 스킵
if [[ "$FILE_PATH" != *.java ]]; then
  exit 0
fi

# 프로젝트 루트 찾기 (이 스크립트 위치 기준)
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

# 어느 모듈인지 판별
if [[ "$FILE_PATH" == *"/monolith/"* ]]; then
  MODULE_DIR="$PROJECT_ROOT/monolith"
  MODULE_NAME="monolith"
elif [[ "$FILE_PATH" == *"/qr-service/"* ]]; then
  MODULE_DIR="$PROJECT_ROOT/qr-service"
  MODULE_NAME="qr-service"
else
  exit 0
fi

echo "🔨 [$MODULE_NAME] Java 파일 변경 감지 → 컴파일 체크 중..."

# Gradle 컴파일 체크 (테스트 제외, 데몬 사용으로 빠르게)
cd "$MODULE_DIR"
if ./gradlew compileJava --daemon -q 2>&1; then
  echo "✅ [$MODULE_NAME] 컴파일 성공"
else
  echo "❌ [$MODULE_NAME] 컴파일 실패 — 위 에러를 확인하세요"
  exit 1
fi
