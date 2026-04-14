#!/bin/bash
set -e

CACHE_MODE=${1:-PUSH}

echo "========================================="
echo "Server 3: QR Service 배포"
echo "캐시 모드: $CACHE_MODE"
echo "========================================="

if [ ! -f .env ]; then
    echo "ERROR: .env 파일이 없습니다."
    exit 1
fi

# 캐시 모드 업데이트
sed -i "s/CACHE_MODE=.*/CACHE_MODE=$CACHE_MODE/" .env
sed -i "s/CACHE_WARMING_ENABLED=.*/CACHE_WARMING_ENABLED=$([ "$CACHE_MODE" = "PUSH" ] && echo "true" || echo "false")/" .env

# Docker 이미지 확인
if ! docker image inspect keeping-qr-service:latest > /dev/null 2>&1; then
    echo "ERROR: keeping-qr-service:latest 이미지가 없습니다."
    exit 1
fi

docker compose down 2>/dev/null || true
docker compose up -d

echo "QR Service 시작 완료 (모드: $CACHE_MODE)"
echo "헬스체크: curl http://localhost:8082/actuator/health"
