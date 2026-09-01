#!/bin/bash
set -e

echo "========================================="
echo "Server 1: Nginx Gateway 배포"
echo "========================================="

# 환경 변수 로드
if [ ! -f .env ]; then
    echo "ERROR: .env 파일이 없습니다. .env.example을 복사하여 설정하세요."
    exit 1
fi
source .env

# nginx.conf 생성 (환경 변수 치환)
envsubst '${MONOLITH_IP} ${QR_SERVER_IP}' < nginx.conf.template > nginx.conf

echo "nginx.conf 생성 완료:"
echo "  - MONOLITH_IP: $MONOLITH_IP"
echo "  - QR_SERVER_IP: $QR_SERVER_IP"

# Docker Compose 실행
docker compose down 2>/dev/null || true
docker compose up -d

echo "Nginx Gateway 시작 완료"
echo "헬스체크: curl http://localhost/health"
