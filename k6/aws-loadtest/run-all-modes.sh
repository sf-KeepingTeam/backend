#!/bin/bash
set -e

# ========================================
# 환경 변수 확인
# ========================================
if [ -z "$NGINX_PUBLIC_IP" ]; then
    echo "ERROR: NGINX_PUBLIC_IP 환경 변수를 설정하세요."
    echo "예: export NGINX_PUBLIC_IP='3.xxx.xxx.xxx'"
    exit 1
fi

if [ -z "$QR_SERVER_SSH" ]; then
    echo "ERROR: QR_SERVER_SSH 환경 변수를 설정하세요."
    echo "예: export QR_SERVER_SSH='ec2-user@10.0.1.3'"
    exit 1
fi

BASE_URL="http://$NGINX_PUBLIC_IP"
mkdir -p results

echo "========================================="
echo "3가지 캐시 모드 비교 테스트"
echo "Nginx: $NGINX_PUBLIC_IP"
echo "QR Server: $QR_SERVER_SSH"
echo "========================================="

for MODE in NONE PULL PUSH; do
    echo ""
    echo "[$MODE] 캐시 모드 변경 중..."

    # Server 3에서 캐시 모드 변경 및 재시작
    ssh $QR_SERVER_SSH "cd /app && ./deploy.sh $MODE"

    # 서비스 안정화 대기
    echo "[$MODE] 서비스 안정화 대기 (30초)..."
    sleep 30

    # 헬스체크
    echo "[$MODE] 헬스체크..."
    curl -sf "$BASE_URL/health" || { echo "헬스체크 실패"; exit 1; }

    # k6 테스트 실행
    echo "[$MODE] 부하 테스트 시작..."
    k6 run -e BASE_URL="$BASE_URL" \
           --out json="results/qr-payment-$MODE.json" \
           qr-payment.js

    echo "[$MODE] 완료!"
    sleep 10
done

echo ""
echo "========================================="
echo "모든 테스트 완료!"
echo "결과 파일: results/"
echo "========================================="
ls -la results/
