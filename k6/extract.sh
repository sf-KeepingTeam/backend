#!/usr/bin/env bash
# =============================================================================
#  extract.sh — 저장된 k6 요약(JSON)에서 「실제로 얼마나 때렸는지」를 뽑는다
# =============================================================================
#  ./extract.sh              ~/results 전체
#  ./extract.sh A1 A2-1      지정한 라벨만
# =============================================================================
set -uo pipefail
RES=~/results
LABELS=("$@")
[ ${#LABELS[@]} -eq 0 ] && LABELS=($(ls "$RES" 2>/dev/null))

python3 - "$RES" "${LABELS[@]}" <<'PY'
import json, os, sys
res, labels = sys.argv[1], sys.argv[2:]

def metrics(path):
    try:
        d = json.load(open(path))
    except Exception:
        return None
    return d.get('metrics', d)

def g(m, name, key):
    v = (m or {}).get(name)
    if not isinstance(v, dict): return None
    if key in v: return v[key]
    return (v.get('values') or {}).get(key)

def fmt(x, n=1):
    return "-" if x is None else f"{x:,.{n}f}"

for lab in labels:
    d = os.path.join(res, lab)
    if not os.path.isdir(d): continue
    print("=" * 74)
    print(f" {lab}")
    print("=" * 74)
    meta = os.path.join(d, 'meta.txt')
    if os.path.exists(meta):
        for line in open(meta):
            if line.startswith(('라벨','구성','러너 모드','PIN 모드','main','payment','배경 VU','결제 VU','지속','결제 시작','결제 종료','창','메모')):
                print("  " + line.rstrip())
    for tag, fn in (("배경부하 (main 대상)", "bg.json"), ("결제 플로우 (payment 대상)", "qr.json")):
        m = metrics(os.path.join(d, fn))
        if not m:
            print(f"\n  [{tag}]  (없음)")
            continue
        print(f"\n  [{tag}]")
        rows = [
            ("완료 이터레이션",  g(m,'iterations','count'),      0),
            ("이터레이션/초",    g(m,'iterations','rate'),        2),
            ("총 HTTP 요청",     g(m,'http_reqs','count'),        0),
            ("★ 실제 RPS",       g(m,'http_reqs','rate'),         2),
            ("요청 평균 ms",     g(m,'http_req_duration','avg'),  1),
            ("요청 p95 ms",      g(m,'http_req_duration','p(95)'),1),
            ("요청 최대 ms",     g(m,'http_req_duration','max'),  1),
            ("실패율",           g(m,'http_req_failed','rate'),   4),
            ("최대 동시 VU",     g(m,'vus_max','max') or g(m,'vus_max','value'), 0),
            ("데이터 수신 MB",   (lambda v: v/1e6 if v else None)(g(m,'data_received','count')), 1),
        ]
        for k, v, n in rows:
            print(f"      {k:<16} {fmt(v,n):>14}")
        for extra in ('qr_create_time','intent_time','pin_token_time','approve_time','total_flow_time',
                      'bg_wallet_duration','bg_store_duration','bg_menu_duration'):
            if extra in m:
                print(f"      {extra:<16} avg {fmt(g(m,extra,'avg')):>9}  "
                      f"p95 {fmt(g(m,extra,'p(95)')):>9}  max {fmt(g(m,extra,'max')):>9}")
        for c in ('payment_failures','bg_errors','payment_success'):
            if c in m:
                print(f"      {c:<16} {fmt(g(m,c,'count'),0):>14}")
    print()
PY
