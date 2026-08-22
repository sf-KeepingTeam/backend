#!/usr/bin/env bash
# loadgen EC2에서 관측 스택을 띄운다.
#   ./up.sh
# .env 의 MONOLITH_HOST / QR_SERVICE_HOST 를 prometheus.yml.template 에 치환한 뒤 기동.
set -euo pipefail
cd "$(dirname "$0")"

[ -f .env ] || { echo "ERROR: .env 가 없습니다. .env.example 을 복사해서 채우세요."; exit 1; }
set -a; . ./.env; set +a

: "${MONOLITH_HOST:?MONOLITH_HOST 미설정}"
: "${QR_SERVICE_HOST:?QR_SERVICE_HOST 미설정}"

envsubst '$MONOLITH_HOST $QR_SERVICE_HOST' < prometheus.yml.template > prometheus.generated.yml
echo "--- 생성된 타겟 ---"
grep -A1 "targets:" prometheus.generated.yml

docker compose up -d

echo
echo "기동 완료. 30초 뒤 타겟 상태를 확인하세요:"
echo "  curl -s localhost:9090/api/v1/targets | grep -o '\"health\":\"[a-z]*\"'"
echo "  Grafana: http://<loadgen 퍼블릭IP>:3000  (admin / \$GRAFANA_ADMIN_PASSWORD)"
