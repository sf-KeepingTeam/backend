#!/bin/bash
set -e

if [ -z "$NGINX_PUBLIC_IP" ] || [ -z "$MONOLITH_SERVER_SSH" ]; then
    echo "ERROR: 환경 변수를 설정하세요."
    echo "export NGINX_PUBLIC_IP='3.xxx.xxx.xxx'"
    echo "export MONOLITH_SERVER_SSH='ec2-user@10.0.1.2'"
    exit 1
fi

BASE_URL="http://$NGINX_PUBLIC_IP"

echo "========================================="
echo "Circuit Breaker Chaos 테스트"
echo "========================================="

# 1. 부하 테스트 시작 (백그라운드)
echo "[1/5] 부하 테스트 시작 (3분)..."
k6 run --duration 3m -e BASE_URL="$BASE_URL" qr-payment.js &
K6_PID=$!

sleep 30

# 2. 모놀리스 중단
echo "[2/5] 모놀리스 중단 (Chaos 주입)..."
ssh $MONOLITH_SERVER_SSH "docker stop loadtest-monolith"
echo "모놀리스 중단됨 - Circuit Breaker 동작 확인"

# 3. 60초간 장애 상태 유지
echo "[3/5] 60초간 장애 상태 유지..."
sleep 60

# 4. 모놀리스 재시작
echo "[4/5] 모놀리스 재시작..."
ssh $MONOLITH_SERVER_SSH "docker start loadtest-monolith"
sleep 30

# 5. 테스트 완료 대기
echo "[5/5] 테스트 완료 대기..."
wait $K6_PID

echo "========================================="
echo "Chaos 테스트 완료"
echo "========================================="
echo "확인 사항:"
echo "  1. 모놀리스 중단 시 에러율 확인"
echo "  2. Circuit Breaker OPEN 로그 확인"
echo "  3. 복구 후 정상화 시간 측정"
