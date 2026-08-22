#!/usr/bin/env bash
# =============================================================================
# 부하측정 러너 — loadgen EC2에서 실행
# =============================================================================
# 배경부하(main 대상)와 결제 플로우(payment 대상)를 동시에 돌리고,
# 끝나자마자 그 구간의 Prometheus 지표를 덤프한다.
#
# 수동으로 하면 러닝이 끝난 뒤 시간대를 역추적해야 해서 창을 놓친다.
# (실제로 R-03에서 그 일이 있었다)
#
# 사용법:
#   ./runner.sh <라벨> <config1|config2> <배경VU> <결제VU> [지속시간]
#
#   ./runner.sh A0 config1   0 30 4m     # 무부하 결제 기준선
#   ./runner.sh A1 config1 500 30 4m     # 배경부하 하 결제
#   ./runner.sh B0 config2   0 30 4m
#   ./runner.sh B1 config2 500 30 4m
#
# 산출물: ~/results/<라벨>/
#   bg.log bg.json   배경부하 k6 원본
#   qr.log qr.json   결제 k6 원본
#   metrics.txt      러닝 구간 서버 지표
#   meta.txt         시각·통제변인·구성
# =============================================================================
set -uo pipefail

LABEL=${1:?라벨이 필요합니다 (예: A1)}
SETUP=${2:?config1 또는 config2}
BG_VUS=${3:-0}
QR_VUS=${4:-30}
DUR=${5:-4m}

case "$SETUP" in
  config1) MONO=172.31.39.211:8080 ; QR=172.31.39.211:8081 ;;
  config2) MONO=172.31.32.251:8080 ; QR=172.31.42.168:8081 ;;
  *) echo "SETUP은 config1 또는 config2"; exit 1 ;;
esac

PROM=localhost:9090
K6DIR="$(cd "$(dirname "$0")/performance-comparison" && pwd)"
OUT=~/results/$LABEL
mkdir -p "$OUT"

# ── Prometheus 조회 헬퍼 ────────────────────────────────────────────────────
pq() {  # pq <쿼리> <시각>  → 값 목록 (라벨 = 값)
  curl -s --get "$PROM/api/v1/query" \
    --data-urlencode "query=$1" --data-urlencode "time=${2:-}" \
  | python3 -c "
import sys,json
try: r=json.load(sys.stdin)['data']['result']
except Exception: print('    (조회 실패)'); sys.exit()
if not r: print('    (데이터 없음)'); sys.exit()
def key(m): return m.get('uri') or m.get('service') or '?'
r.sort(key=lambda x:-float(x['value'][1]))
for x in r: print(f\"    {float(x['value'][1]):>12.4f}  {key(x['metric'])}\")
"
}

echo "════════════════════════════════════════════════════════"
echo " 러닝 $LABEL  |  $SETUP  |  배경 ${BG_VUS}VU  결제 ${QR_VUS}VU  ${DUR}"
echo "════════════════════════════════════════════════════════"

# ── 1. 통제변인 검증 (조용히 안 먹는 사고 방지) ────────────────────────────
{
  echo "라벨      : $LABEL"
  echo "구성      : $SETUP"
  echo "main      : $MONO"
  echo "payment   : $QR"
  echo "배경VU    : $BG_VUS"
  echo "결제VU    : $QR_VUS"
  echo "지속       : $DUR"
  echo
  echo "── 통제변인 (러닝 시작 전 실측) ──"
} > "$OUT/meta.txt"

echo "▸ 통제변인 확인"
for m in hikaricp_connections_max tomcat_threads_config_max_threads; do
  echo "  $m" | tee -a "$OUT/meta.txt"
  pq "$m{setup=\"$SETUP\"}" | tee -a "$OUT/meta.txt"
done

# ── 2. 부하 실행 ───────────────────────────────────────────────────────────
cd "$K6DIR"
START=$(date +%s)
echo "▸ 시작 $(date +%H:%M:%S)"

BGPID=""
if [ "$BG_VUS" -gt 0 ]; then
  k6 run -e MONO_BASE_URL="http://$MONO" -e BG_VUS="$BG_VUS" -e BG_DURATION="$DUR" \
     --summary-export="$OUT/bg.json" 01-background-load.js > "$OUT/bg.log" 2>&1 &
  BGPID=$!
  echo "  배경부하 기동 (pid $BGPID). 자리잡을 때까지 20초 대기"
  sleep 20
else
  echo "  배경부하 없음 (기준선 러닝)"
fi

echo "  결제 플로우 실행"
k6 run -e QR_BASE_URL="http://$QR" -e QR_VUS="$QR_VUS" -e QR_DURATION="$DUR" \
   --summary-export="$OUT/qr.json" 02-qr-payment-flow.js > "$OUT/qr.log" 2>&1

[ -n "$BGPID" ] && { echo "  배경부하 종료 대기"; wait "$BGPID" 2>/dev/null; }

END=$(date +%s)
WIN=$(( END - START + 30 ))
echo "▸ 종료 $(date +%H:%M:%S)  (구간 ${WIN}초)"
sleep 10   # 마지막 스크랩이 들어올 시간

# ── 3. 지표 덤프 (러닝 구간만) ─────────────────────────────────────────────
W="[${WIN}s]"
{
  echo
  echo "── 러닝 구간 ──"
  echo "시작: $(date -d @$START '+%Y-%m-%d %H:%M:%S')"
  echo "종료: $(date -d @$END '+%Y-%m-%d %H:%M:%S')"
  echo "창  : ${WIN}s"
} >> "$OUT/meta.txt"

{
echo "════════ $LABEL · $SETUP · 배경 ${BG_VUS}VU / 결제 ${QR_VUS}VU ════════"
echo "구간: $(date -d @$START '+%H:%M:%S') ~ $(date -d @$END '+%H:%M:%S')"
echo
echo "──────── 포화 지표 (monolith) ────────"
S="{setup=\"$SETUP\",service=\"monolith\"}"
echo "[DB 커넥션 대기 최대]  >0 이면 DB 경합";  pq "max_over_time(hikaricp_connections_pending$S$W)" "$END"
echo "[DB 커넥션 사용 최대]";                    pq "max_over_time(hikaricp_connections_active$S$W)" "$END"
echo "[DB 커넥션 사용 평균]";                    pq "avg_over_time(hikaricp_connections_active$S$W)" "$END"
echo "[톰캣 busy 최대]  config_max에 붙으면 스레드 경합"; pq "max_over_time(tomcat_threads_busy_threads$S$W)" "$END"
echo "[프로세스 CPU 최대/평균]"; pq "max_over_time(process_cpu_usage$S$W)" "$END"; pq "avg_over_time(process_cpu_usage$S$W)" "$END"
echo "[호스트 CPU 최대/평균]";   pq "max_over_time(system_cpu_usage$S$W)" "$END";  pq "avg_over_time(system_cpu_usage$S$W)" "$END"
echo "[힙 최대 bytes]";          pq "max_over_time(jvm_memory_used_bytes$S$W)" "$END"

echo
echo "──────── 포화 지표 (qr-service) ────────"
Q="{setup=\"$SETUP\",service=\"qr-service\"}"
echo "[DB 커넥션 대기 최대]"; pq "max_over_time(hikaricp_connections_pending$Q$W)" "$END"
echo "[톰캣 busy 최대]";      pq "max_over_time(tomcat_threads_busy_threads$Q$W)" "$END"
echo "[프로세스 CPU 평균]";   pq "avg_over_time(process_cpu_usage$Q$W)" "$END"

echo
echo "──────── ★ Approve 구간 분해 ────────"
echo "[monolith 가 「일한」 시간 · 구간 평균 초]"
pq "sum by (uri) (rate(http_server_requests_seconds_sum{setup=\"$SETUP\",service=\"monolith\",uri=~\".*internal.*\"}$W)) / sum by (uri) (rate(http_server_requests_seconds_count{setup=\"$SETUP\",service=\"monolith\",uri=~\".*internal.*\"}$W))" "$END"
echo "[qr 이 「기다린」 시간 · 구간 평균 초]"
pq "sum by (uri) (rate(http_client_requests_seconds_sum{setup=\"$SETUP\",service=\"qr-service\"}$W)) / sum by (uri) (rate(http_client_requests_seconds_count{setup=\"$SETUP\",service=\"qr-service\"}$W))" "$END"
echo "[qr 엔드포인트별 처리 시간 · 구간 평균 초]"
pq "sum by (uri) (rate(http_server_requests_seconds_sum{setup=\"$SETUP\",service=\"qr-service\"}$W)) / sum by (uri) (rate(http_server_requests_seconds_count{setup=\"$SETUP\",service=\"qr-service\"}$W))" "$END"
echo "[monolith 프론트 API 처리 시간 · 구간 평균 초]"
pq "sum by (uri) (rate(http_server_requests_seconds_sum{setup=\"$SETUP\",service=\"monolith\",uri!~\".*internal.*|.*actuator.*\"}$W)) / sum by (uri) (rate(http_server_requests_seconds_count{setup=\"$SETUP\",service=\"monolith\",uri!~\".*internal.*|.*actuator.*\"}$W))" "$END"
} > "$OUT/metrics.txt" 2>&1

# ── 4. 요약 출력 ───────────────────────────────────────────────────────────
echo
echo "──────── k6 결제 결과 ────────"
grep -E "단계|전체 플로우|성공률|실패 수" "$OUT/qr.log" | sed 's/^INFO\[[0-9]*\] //' || tail -25 "$OUT/qr.log"
if [ "$BG_VUS" -gt 0 ]; then
  echo "──────── k6 배경부하 결과 ────────"
  grep -E "p\(95\)|에러 수" "$OUT/bg.log" | sed 's/^INFO\[[0-9]*\] //' || true
fi
echo
echo "저장됨: $OUT/  (metrics.txt 에 서버 지표)"
