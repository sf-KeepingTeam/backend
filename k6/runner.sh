#!/usr/bin/env bash
# =============================================================================
# 부하측정 러너 v2 — loadgen EC2에서 실행
# =============================================================================
# 배경부하(main 대상)와 결제 플로우(payment 대상)를 동시에 돌리고,
# 끝나자마자 그 구간의 Prometheus 지표를 덤프한다.
#
# 사용법:
#   ./runner.sh <라벨> <config1|config2> <배경VU> <결제VU> [지속시간] [메모]
#
#   ./runner.sh A0 config1   0 30 4m "argon2=strong"
#   ./runner.sh A1 config1 500 30 4m "argon2=strong"
#
# 산출물: ~/results/<라벨>/
#   meta.txt      ★ 러닝의 모든 조건 (시각·커밋·통제변인·부하정의·메모)
#   bg.log/json   배경부하 k6 원본 + 요약
#   qr.log/json   결제 k6 원본 + 요약
#   metrics.txt   결제 구간 서버 지표 (요약)
#   metrics.csv   ★ 5초 간격 시계열 원본 (Prometheus 소멸 대비)
#   volume.txt    ★ 실제 투입량 (RPS·요청수·이터레이션)
#
# ── v2 에서 고친 것 (2026-08-22) ─────────────────────────────────────────────
#  결함 1  결제 스크립트가 ramping-vus 였다. "30 VU 4m" 이라 적었지만 실제로는
#          0→15→30→30유지→0 (270초), 평균 19.4 VU, 30 VU 구간은 80초뿐.
#          k6 p95 는 전 구간 합산이라 한산한 램프업이 섞여 지연이 좋게 나왔다.
#          → QR_MODE=constant 로 고정한다.
#  결함 2  배경부하(DUR)가 결제(DUR+30s 이상)보다 먼저 끝났다. 러닝 후반
#          약 50초는 배경부하 없이 결제만 돌았다. → 배경을 90초 더 길게 돌린다.
#  결함 3  지표 창이 배경부하 대기 20초까지 포함했다. → 결제 구간만 사용한다.
#  결함 4  실제 투입량(RPS·요청수)을 기록하지 않았다. → volume.txt 추가.
#  결함 5  Prometheus 원본이 보존 기간 뒤 사라진다. → metrics.csv 로 덤프.
# =============================================================================
set -uo pipefail

LABEL=${1:?라벨이 필요합니다 (예: A1)}
SETUP=${2:?config1 또는 config2}
BG_VUS=${3:-0}
QR_VUS=${4:-30}
DUR=${5:-4m}
NOTE=${6:-}

# ── RUN_MODE ────────────────────────────────────────────────────────────────
#  v2      (기본)   결제 constant + 배경 DUR+90s + 창=결제구간   ← 결함 1·2·3 수정본
#  legacy           1차(A0/A1/A2-0/A2-1)와 부하 적용 방식을 100% 동일하게 재현
#
#  ★ legacy 가 왜 필요한가
#    A(구성1)를 1차 방식으로 이미 쟀다. Q2 는 A1 ↔ B1 비교이므로
#    **두 구성을 같은 방식으로 재야** 성립한다. B 를 v2 로 재면 비교가 깨진다.
#    그래서 B0/B1 은 legacy 로 돌린다. 기록(meta/csv/volume)은 v2 그대로 남는다.
#    결함 1·2·3 은 두 구성에 동일하게 적용되므로 **비교는 유효**하고,
#    절대값은 §3-9 에 적힌 대로 계속 유보한다.
RUN_MODE=${RUN_MODE:-v2}

# ── PIN_MODE ────────────────────────────────────────────────────────────────
#  pin   (기본) approve 에 평문 PIN 을 실어 보낸다 (v2 동작)
#  token        approve 전에 monolith 에서 PIN 토큰을 발급받아 첨부한다 (v3 개선 경로)
#
#  ★ qr-service 의 payment.pin.token-enabled=true 로 측정할 때는 반드시 PIN_MODE=token.
#    안 그러면 ApproveRequest.pinToken 이 비어 토큰 경로를 타지 않는다 = 측정이 무의미해진다.
PIN_MODE=${PIN_MODE:-pin}
case "$PIN_MODE" in pin|token) ;; *) echo "PIN_MODE는 pin 또는 token"; exit 1 ;; esac

case "$RUN_MODE" in
  v2)     QR_MODE=constant ;;
  legacy) QR_MODE=ramp     ;;
  *) echo "RUN_MODE는 v2 또는 legacy"; exit 1 ;;
esac

case "$SETUP" in
  config1) MONO=172.31.39.211:8080 ; QR=172.31.39.211:8081 ;;
  config2) MONO=172.31.32.251:8080 ; QR=172.31.42.168:8081 ;;
  *) echo "SETUP은 config1 또는 config2"; exit 1 ;;
esac

PROM=localhost:9090
HERE="$(cd "$(dirname "$0")" && pwd)"
K6DIR="$HERE/performance-comparison"
OUT=~/results/$LABEL
mkdir -p "$OUT"

# 배경부하는 결제보다 90초 길게 돌려서 결제 구간 전체를 덮는다 (결함 2)
dur_sec() { case "$1" in *m) echo $(( ${1%m} * 60 )) ;; *s) echo "${1%s}" ;; *) echo "$1" ;; esac; }
DUR_S=$(dur_sec "$DUR")
if [ "$RUN_MODE" = "legacy" ]; then
  BG_DUR_S=$DUR_S            # 1차와 동일 (결제보다 먼저 끝난다 — 결함 2 재현)
else
  BG_DUR_S=$(( DUR_S + 90 )) # 결제 구간 전체를 덮는다
fi

pq() {  # pq <쿼리> <시각>
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

# ── 대상 생존 확인 ──────────────────────────────────────────────────────────
#  ★ 결함 8 (2026-08-22 발견)
#     B0/B1 러닝에서 qr-service 가 죽은 채로 4분간 부하를 계속 걸었다.
#     통제변인 조회에 qr-service 가 안 나왔는데 러너가 그냥 진행했다.
#     결과는 전부 0-null 이었고 러닝 2회를 통째로 버렸다.
#     → 부하 전·후로 두 서비스가 살아있는지 확인한다.
alive() {  # alive <setup>  → "monolith qr-service" 중 up 인 것들
  curl -s --get "$PROM/api/v1/query" \
    --data-urlencode "query=up{setup=\"$1\"}" \
  | python3 -c "
import sys,json
try: r=json.load(sys.stdin)['data']['result']
except Exception: sys.exit()
print(' '.join(sorted(x['metric'].get('service','?') for x in r if x['value'][1]=='1')))
"
}

require_alive() {  # require_alive <시점라벨>
  local got; got=$(alive "$SETUP")
  for svc in monolith qr-service; do
    case " $got " in *" $svc "*) ;; *)
      echo
      echo "✗✗✗ [$1] $svc 가 Prometheus 에서 up 이 아니다 (감지: ${got:-없음})"
      echo "    대상이 죽었거나 스크랩이 안 된다. 러닝을 중단한다."
      echo "    확인: curl -s $PROM/api/v1/targets | grep -o 'health\":\"[a-z]*'"
      echo "         해당 서버에서  docker ps  /  free -h  /  journalctl --list-boots"
      { echo; echo "★ 이 러닝은 무효다 — [$1] 시점에 $svc 가 down (감지: ${got:-없음})"; } >> "$OUT/meta.txt"
      return 1
    ;; esac
  done
  echo "  ✓ [$1] 생존 확인: $got"
  return 0
}

echo "════════════════════════════════════════════════════════"
echo " 러닝 $LABEL  |  $SETUP  |  배경 ${BG_VUS}VU  결제 ${QR_VUS}VU  ${DUR}"
echo " 모드: $RUN_MODE  (결제 executor = $QR_MODE)  ·  PIN: $PIN_MODE"
[ -n "$NOTE" ] && echo " 메모: $NOTE"
echo "════════════════════════════════════════════════════════"

# ── 1. 조건 기록 ───────────────────────────────────────────────────────────
{
  echo "═══════ 러닝 $LABEL ═══════"
  echo "실행 시각  : $(date '+%Y-%m-%d %H:%M:%S %Z')  (UTC $(date -u '+%H:%M:%S'))"
  echo "메모       : ${NOTE:-(없음)}"
  echo
  echo "── 대상 ──"
  echo "구성       : $SETUP"
  echo "main       : $MONO"
  echo "payment    : $QR"
  echo
  echo "── 투입 부하 (설정값) ──"
  echo "러너 모드  : $RUN_MODE"
  echo "PIN 모드   : $PIN_MODE  $([ "$PIN_MODE" = token ] && echo '(monolith 에서 토큰 발급 후 approve 에 첨부)' || echo '(approve 에 평문 PIN)')"
  echo "배경 VU    : $BG_VUS  (constant-vus, ${BG_DUR_S}초)"
  if [ "$QR_MODE" = "constant" ]; then
    echo "결제 VU    : $QR_VUS  (constant-vus, ${DUR_S}초 고정)"
  else
    R=$(( DUR_S / 3 )); [ $R -lt 30 ] && R=30
    echo "결제 VU    : $QR_VUS  (ramping-vus)  ⚠️ 실제 계단:"
    echo "               0→$(( QR_VUS / 2 )) (${R}초) · $(( QR_VUS / 2 ))→$QR_VUS (${R}초) · $QR_VUS 유지 (${R}초) · →0 (30초)"
    echo "               = 총 $(( R * 3 + 30 ))초, 평균 약 $(( QR_VUS * 65 / 100 )) VU"
    echo "               ⚠️ k6 p95 는 전 구간 합산이라 램프업이 섞인다 (§3-9 결함 1)"
    echo "               ⚠️ 배경부하가 결제보다 먼저 끝난다 (§3-9 결함 2)"
    echo "               → 1차(A0/A1/A2-0/A2-1)와 비교하기 위해 의도적으로 재현한 것"
  fi
  echo
  echo "  배경 1이터레이션 = GET 4건 + sleep 1.4초"
  echo "    /wallets/individual/balance · /wallets/both/balance"
  echo "    /stores/{id} · /stores/{id}/menus"
  echo "  결제 1이터레이션 = POST 4건 + sleep 1.6초"
  echo "    /api/qr · /api/qr/{t}/scan · /cpqr/{s}/initiate · /payments/{i}/approve"
  echo "  ※ 닫힌(closed) 모델이다. VU 수는 RPS 가 아니다."
  echo "     서버가 느려지면 투입 RPS 도 같이 떨어진다. 실측은 volume.txt 참조."
  echo
  echo "── 실행 환경 ──"
  echo "k6         : $(k6 version 2>/dev/null | head -1)"
  echo "커밋       : $(cd "$HERE" && git rev-parse --short HEAD 2>/dev/null) $(cd "$HERE" && git rev-parse --abbrev-ref HEAD 2>/dev/null)"
  echo "loadgen    : $(hostname) $(uname -r)"
  echo
  echo "── 통제변인 (러닝 시작 전 실측) ──"
} > "$OUT/meta.txt"

RSTART=$(date +%s)
echo "▸ 통제변인 확인"
for m in hikaricp_connections_max tomcat_threads_config_max_threads; do
  echo "  $m" | tee -a "$OUT/meta.txt"
  pq "$m{setup=\"$SETUP\"}" | tee -a "$OUT/meta.txt"
done

# ── 1-1. 사전 생존 확인 (결함 8) ───────────────────────────────────────────
require_alive "사전" || { echo "→ 러닝 $LABEL 중단. 대상을 살린 뒤 다시 실행하라."; exit 2; }

# ── 2. 부하 실행 ───────────────────────────────────────────────────────────
cd "$K6DIR"
BGPID=""
if [ "$BG_VUS" -gt 0 ]; then
  k6 run -e MONO_BASE_URL="http://$MONO" -e BG_VUS="$BG_VUS" -e BG_DURATION="${BG_DUR_S}s" \
     --summary-export="$OUT/bg.json" 01-background-load.js > "$OUT/bg.log" 2>&1 &
  BGPID=$!
  echo "▸ 배경부하 기동 (pid $BGPID). 자리잡을 때까지 20초 대기"
  sleep 20
else
  echo "▸ 배경부하 없음 (기준선 러닝)"
fi

QSTART=$(date +%s)
echo "▸ 결제 시작 $(date +%H:%M:%S)"
k6 run -e QR_BASE_URL="http://$QR" -e QR_VUS="$QR_VUS" -e QR_DURATION="$DUR" -e QR_MODE="$QR_MODE" -e PIN_MODE="$PIN_MODE" -e MONO_BASE_URL="http://$MONO" \
   --summary-export="$OUT/qr.json" 02-qr-payment-flow.js > "$OUT/qr.log" 2>&1
QEND=$(date +%s)
echo "▸ 결제 종료 $(date +%H:%M:%S)  (결제 구간 $(( QEND - QSTART ))초)"

[ -n "$BGPID" ] && { echo "▸ 배경부하 종료 대기"; wait "$BGPID" 2>/dev/null; }
WEND=$(date +%s)
sleep 10   # 마지막 스크랩

# ── 사후 생존 확인 (결함 8) ────────────────────────────────────────────────
POST_OK=1
require_alive "사후" || POST_OK=0

# legacy 는 1차와 동일한 창(러너 시작~종료+30초)을 써야 숫자가 대응된다
if [ "$RUN_MODE" = "legacy" ]; then
  WSTART=$RSTART; WQEND=$WEND; WIN=$(( WEND - RSTART + 30 ))
else
  WSTART=$QSTART; WQEND=$QEND;  WIN=$(( QEND - QSTART ))
fi
W="[${WIN}s]"

{
  echo
  echo "── 실제 측정 창 ──"
  echo "창 기준    : $([ "$RUN_MODE" = legacy ] && echo '러너 시작~종료+30s (1차와 동일)' || echo '결제 구간만')"
  echo "결제 시작  : $(date -d @$QSTART '+%Y-%m-%d %H:%M:%S')"
  echo "결제 종료  : $(date -d @$QEND '+%Y-%m-%d %H:%M:%S')"
  echo "창         : ${WIN}s"
  echo "배경부하   : 결제 시작 20초 전 기동, 결제 종료 후까지 유지"
} >> "$OUT/meta.txt"

# ── 3. 지표 덤프 ───────────────────────────────────────────────────────────
{
echo "════════ $LABEL · $SETUP · 배경 ${BG_VUS}VU / 결제 ${QR_VUS}VU (constant) ════════"
echo "결제 구간: $(date -d @$QSTART '+%H:%M:%S') ~ $(date -d @$QEND '+%H:%M:%S')  (${WIN}s)"
[ -n "$NOTE" ] && echo "메모: $NOTE"
echo
echo "──────── 포화 지표 (monolith) ────────"
S="{setup=\"$SETUP\",service=\"monolith\"}"
echo "[DB 커넥션 대기 최대/평균]  >0 이면 DB 경합"; pq "max_over_time(hikaricp_connections_pending$S$W)" "$WQEND"; pq "avg_over_time(hikaricp_connections_pending$S$W)" "$WQEND"
echo "[DB 커넥션 사용 최대/평균]"; pq "max_over_time(hikaricp_connections_active$S$W)" "$WQEND"; pq "avg_over_time(hikaricp_connections_active$S$W)" "$WQEND"
echo "[톰캣 busy 최대/평균]"; pq "max_over_time(tomcat_threads_busy_threads$S$W)" "$WQEND"; pq "avg_over_time(tomcat_threads_busy_threads$S$W)" "$WQEND"
echo "[프로세스 CPU 최대/평균]"; pq "max_over_time(process_cpu_usage$S$W)" "$WQEND"; pq "avg_over_time(process_cpu_usage$S$W)" "$WQEND"
echo "[호스트 CPU 최대/평균]";   pq "max_over_time(system_cpu_usage$S$W)" "$WQEND";  pq "avg_over_time(system_cpu_usage$S$W)" "$WQEND"
echo "[힙 최대 bytes]";          pq "max_over_time(jvm_memory_used_bytes${S%\}},area=\"heap\"}$W)" "$WQEND"
echo "[GC pause 합 초]";         pq "sum(increase(jvm_gc_pause_seconds_sum$S$W))" "$WQEND"
echo "[서버측 처리 건수/초]";    pq "sum(rate(http_server_requests_seconds_count$S$W))" "$WQEND"

echo
echo "──────── 포화 지표 (qr-service) ────────"
Q="{setup=\"$SETUP\",service=\"qr-service\"}"
echo "[DB 커넥션 대기 최대/평균]"; pq "max_over_time(hikaricp_connections_pending$Q$W)" "$WQEND"; pq "avg_over_time(hikaricp_connections_pending$Q$W)" "$WQEND"
echo "[DB 커넥션 사용 최대]";      pq "max_over_time(hikaricp_connections_active$Q$W)" "$WQEND"
echo "[톰캣 busy 최대/평균]";      pq "max_over_time(tomcat_threads_busy_threads$Q$W)" "$WQEND"; pq "avg_over_time(tomcat_threads_busy_threads$Q$W)" "$WQEND"
echo "[프로세스 CPU 최대/평균]";   pq "max_over_time(process_cpu_usage$Q$W)" "$WQEND"; pq "avg_over_time(process_cpu_usage$Q$W)" "$WQEND"
echo "[서버측 처리 건수/초]";      pq "sum(rate(http_server_requests_seconds_count$Q$W))" "$WQEND"
echo "[복구 대기 UNCERTAIN]";      pq "max_over_time(payment_uncertain_count$Q$W)" "$WQEND"

echo
echo "──────── ★ Approve 구간 분해 ────────"
echo "[monolith 가 「일한」 시간 · 구간 평균 초]"
pq "sum by (uri) (rate(http_server_requests_seconds_sum{setup=\"$SETUP\",service=\"monolith\",uri=~\".*internal.*\"}$W)) / sum by (uri) (rate(http_server_requests_seconds_count{setup=\"$SETUP\",service=\"monolith\",uri=~\".*internal.*\"}$W))" "$WQEND"
echo "[monolith 내부 API 처리 건수/초]"
pq "sum by (uri) (rate(http_server_requests_seconds_count{setup=\"$SETUP\",service=\"monolith\",uri=~\".*internal.*\"}$W))" "$WQEND"
echo "[qr 이 「기다린」 시간 · 구간 평균 초 · 상위만]"
pq "topk(12, sum by (uri) (rate(http_client_requests_seconds_sum{setup=\"$SETUP\",service=\"qr-service\"}$W)) / sum by (uri) (rate(http_client_requests_seconds_count{setup=\"$SETUP\",service=\"qr-service\"}$W)))" "$WQEND"
echo "[qr 엔드포인트별 처리 시간 · 구간 평균 초]"
pq "sum by (uri) (rate(http_server_requests_seconds_sum{setup=\"$SETUP\",service=\"qr-service\"}$W)) / sum by (uri) (rate(http_server_requests_seconds_count{setup=\"$SETUP\",service=\"qr-service\"}$W))" "$WQEND"
echo "[monolith 프론트 API 처리 시간 · 구간 평균 초]"
pq "sum by (uri) (rate(http_server_requests_seconds_sum{setup=\"$SETUP\",service=\"monolith\",uri!~\".*internal.*|.*actuator.*\"}$W)) / sum by (uri) (rate(http_server_requests_seconds_count{setup=\"$SETUP\",service=\"monolith\",uri!~\".*internal.*|.*actuator.*\"}$W))" "$WQEND"
echo "[monolith 프론트 API 처리 건수/초]"
pq "sum by (uri) (rate(http_server_requests_seconds_count{setup=\"$SETUP\",service=\"monolith\",uri!~\".*internal.*|.*actuator.*\"}$W))" "$WQEND"
} > "$OUT/metrics.txt" 2>&1

# ── 4. 시계열 CSV 덤프 (Prometheus 소멸 대비) ──────────────────────────────
python3 - "$PROM" "$SETUP" "$WSTART" "$WQEND" "$OUT/metrics.csv" <<'PY'
import sys, json, urllib.parse, urllib.request, csv
prom, setup, start, end, out = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4], sys.argv[5]
series = ["hikaricp_connections_pending","hikaricp_connections_active","hikaricp_connections_max",
          "tomcat_threads_busy_threads","tomcat_threads_config_max_threads",
          "process_cpu_usage","system_cpu_usage"]
rows = []
for s in series:
    q = f'{s}{{setup="{setup}"}}'
    url = f"http://{prom}/api/v1/query_range?" + urllib.parse.urlencode(
        {"query": q, "start": start, "end": end, "step": "5"})
    try:
        d = json.load(urllib.request.urlopen(url, timeout=20))["data"]["result"]
    except Exception as e:
        rows.append([s, "ERROR", str(e), ""]); continue
    for r in d:
        svc = r["metric"].get("service", "?")
        for ts, v in r["values"]:
            rows.append([s, svc, ts, v])
with open(out, "w", newline="") as f:
    w = csv.writer(f); w.writerow(["metric","service","timestamp","value"]); w.writerows(rows)
print(f"  metrics.csv: {len(rows)}행")
PY

# ── 5. 실제 투입량 ─────────────────────────────────────────────────────────
"$HERE/extract.sh" "$LABEL" > "$OUT/volume.txt" 2>&1 || true

# ── 6. 요약 출력 ───────────────────────────────────────────────────────────
echo
echo "──────── k6 결제 결과 ────────"
grep -E "단계|전체 플로우|성공률|실패 수" "$OUT/qr.log" | sed 's/^INFO\[[0-9]*\] //' || tail -25 "$OUT/qr.log"
if [ "$BG_VUS" -gt 0 ]; then
  echo "──────── k6 배경부하 결과 ────────"
  grep -E "p\(95\)|에러 수" "$OUT/bg.log" | sed 's/^INFO\[[0-9]*\] //' || true
fi
echo
echo "──────── ★ 실제 투입량 ────────"
grep -E "이터레이션|RPS|총 HTTP|최대 동시 VU|실패율" "$OUT/volume.txt" || true
echo
if [ "$POST_OK" -eq 0 ]; then
  echo
  echo "████████████████████████████████████████████████████████"
  echo "  ★ 이 러닝은 무효다 — 러닝 도중 대상이 죽었다"
  echo "     결과를 result.md 에 인용하지 말 것. meta.txt 에 기록됨"
  echo "████████████████████████████████████████████████████████"
fi
echo "저장됨: $OUT/   meta.txt · metrics.txt · metrics.csv · volume.txt · bg/qr.{log,json}"
