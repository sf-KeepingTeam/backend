#!/bin/bash

if [ -z "$QR_SERVER_IP" ]; then
    echo "ERROR: export QR_SERVER_IP='10.0.1.3'"
    exit 1
fi

echo "Circuit Breaker 상태 모니터링 (Ctrl+C로 종료)"
echo "QR Server: $QR_SERVER_IP"
echo ""

while true; do
    echo "=== $(date) ==="
    curl -s "http://$QR_SERVER_IP:8082/actuator/health" | jq '.components.circuitBreakers' 2>/dev/null || echo "연결 실패"
    sleep 5
done
