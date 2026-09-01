#!/usr/bin/env bash
# loadgen EC2에서 관측 스택을 띄운다.
#   ./up.sh
# .env 의 MONOLITH_HOST / QR_SERVICE_HOST 를 prometheus.yml.template 에 치환한 뒤 기동.
set -euo pipefail
cd "$(dirname "$0")"

[ -f .env ] || { echo "ERROR: .env 가 없습니다. .env.example 을 복사해서 채우세요."; exit 1; }
set -a; . ./.env; set +a

: "${ALLINONE_HOST:?ALLINONE_HOST 미설정}"
: "${MONOLITH_HOST:?MONOLITH_HOST 미설정}"
: "${QR_SERVICE_HOST:?QR_SERVICE_HOST 미설정}"
# Kafka 는 선택 — 전용 EC2(keeping-kafka)의 Private IP 를 .env 에 넣는다.
# 미설정이면 127.0.0.1 로 두어 target down 으로만 뜨게 한다.
#   (빈 문자열로 두면 ':9308' 이 되어 Prometheus 설정 파싱이 통째로 실패한다)
KAFKA_HOST="${KAFKA_HOST:-127.0.0.1}"

export KAFKA_HOST
envsubst '$ALLINONE_HOST $MONOLITH_HOST $QR_SERVICE_HOST $KAFKA_HOST' < prometheus.yml.template > prometheus.generated.yml
echo "--- 생성된 타겟 ---"
grep -A1 "targets:" prometheus.generated.yml

docker compose up -d

echo
echo "기동 완료. 30초 뒤 타겟 상태를 확인하세요:"
echo "  curl -s localhost:9090/api/v1/targets | grep -o '\"health\":\"[a-z]*\"'"
echo "  Grafana: http://<loadgen 퍼블릭IP>:3000  (admin / \$GRAFANA_ADMIN_PASSWORD)"
