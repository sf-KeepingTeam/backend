#!/bin/bash
set -e

echo "========================================="
echo "Server 2: Monolith Server 배포"
echo "========================================="

if [ ! -f .env ]; then
    echo "ERROR: .env 파일이 없습니다."
    exit 1
fi

# Docker 이미지 확인
if ! docker image inspect keeping-monolith:latest > /dev/null 2>&1; then
    echo "ERROR: keeping-monolith:latest 이미지가 없습니다."
    echo "먼저 ./gradlew bootBuildImage 또는 docker build를 실행하세요."
    exit 1
fi

docker compose down 2>/dev/null || true
docker compose up -d

echo "Monolith Server 시작 완료"
echo "헬스체크: curl http://localhost:8080/actuator/health"
