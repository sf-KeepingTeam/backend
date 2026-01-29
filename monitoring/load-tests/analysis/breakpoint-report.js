/**
 * K6 Breakpoint Test Result Analyzer
 *
 * k6 JSON 출력 파일을 분석하여 한계점 및 병목 지점을 식별합니다.
 *
 * 사용법:
 *   k6 run --out json=results/auth-breakpoint.json scenarios/breakpoint/auth-breakpoint.js
 *   node analysis/breakpoint-report.js results/auth-breakpoint.json
 */

const fs = require('fs');
const path = require('path');
const readline = require('readline');

// 분석 결과 저장
const analysis = {
  testName: '',
  startTime: null,
  endTime: null,
  totalRequests: 0,
  failedRequests: 0,
  metrics: {
    http_req_duration: {
      values: [],
      p50: 0,
      p95: 0,
      p99: 0,
      max: 0,
      min: Infinity,
      avg: 0,
    },
    http_reqs: {
      count: 0,
      rate: 0,
    },
    vus: {
      max: 0,
      timeline: [],
    },
  },
  breakpointDetected: false,
  breakpointTime: null,
  breakpointReason: null,
  breakpointRPS: 0,
  recommendations: [],
};

// 시간별 메트릭 집계
const timeSeriesData = new Map();

/**
 * NDJSON 파일을 라인별로 읽어 분석
 */
async function analyzeFile(filePath) {
  const fileStream = fs.createReadStream(filePath);
  const rl = readline.createInterface({
    input: fileStream,
    crlfDelay: Infinity,
  });

  let lineCount = 0;

  for await (const line of rl) {
    lineCount++;
    try {
      const data = JSON.parse(line);
      processDataPoint(data);
    } catch (e) {
      // JSON 파싱 실패한 라인은 무시
    }
  }

  console.log(`Processed ${lineCount} lines`);
}

/**
 * 개별 데이터 포인트 처리
 */
function processDataPoint(data) {
  const { type, metric, data: metricData } = data;

  if (!metricData || !metricData.time) return;

  const timestamp = new Date(metricData.time);
  const timeKey = Math.floor(timestamp.getTime() / 1000); // 초 단위로 집계

  // 시간별 데이터 초기화
  if (!timeSeriesData.has(timeKey)) {
    timeSeriesData.set(timeKey, {
      timestamp,
      requestCount: 0,
      failedCount: 0,
      durations: [],
      vus: 0,
    });
  }

  const timeData = timeSeriesData.get(timeKey);

  switch (metric) {
    case 'http_req_duration':
      if (type === 'Point' && typeof metricData.value === 'number') {
        analysis.metrics.http_req_duration.values.push(metricData.value);
        timeData.durations.push(metricData.value);
        analysis.totalRequests++;
      }
      break;

    case 'http_req_failed':
      if (type === 'Point' && metricData.value === 1) {
        analysis.failedRequests++;
        timeData.failedCount++;
      }
      if (type === 'Point') {
        timeData.requestCount++;
      }
      break;

    case 'vus':
      if (type === 'Point' && typeof metricData.value === 'number') {
        timeData.vus = Math.max(timeData.vus, metricData.value);
        analysis.metrics.vus.max = Math.max(analysis.metrics.vus.max, metricData.value);
      }
      break;
  }

  // 시간 범위 업데이트
  if (!analysis.startTime || timestamp < analysis.startTime) {
    analysis.startTime = timestamp;
  }
  if (!analysis.endTime || timestamp > analysis.endTime) {
    analysis.endTime = timestamp;
  }
}

/**
 * 백분위수 계산
 */
function percentile(arr, p) {
  if (arr.length === 0) return 0;
  const sorted = [...arr].sort((a, b) => a - b);
  const index = Math.ceil((p / 100) * sorted.length) - 1;
  return sorted[Math.max(0, index)];
}

/**
 * 통계 계산
 */
function calculateStatistics() {
  const durations = analysis.metrics.http_req_duration.values;

  if (durations.length > 0) {
    analysis.metrics.http_req_duration.p50 = percentile(durations, 50);
    analysis.metrics.http_req_duration.p95 = percentile(durations, 95);
    analysis.metrics.http_req_duration.p99 = percentile(durations, 99);
    analysis.metrics.http_req_duration.max = Math.max(...durations);
    analysis.metrics.http_req_duration.min = Math.min(...durations);
    analysis.metrics.http_req_duration.avg =
      durations.reduce((a, b) => a + b, 0) / durations.length;
  }

  // 테스트 시간 계산
  if (analysis.startTime && analysis.endTime) {
    const durationSec = (analysis.endTime - analysis.startTime) / 1000;
    analysis.metrics.http_reqs.count = analysis.totalRequests;
    analysis.metrics.http_reqs.rate = analysis.totalRequests / durationSec;
  }
}

/**
 * 한계점 탐지
 */
function detectBreakpoint() {
  const sortedTimes = [...timeSeriesData.keys()].sort((a, b) => a - b);

  let windowSize = 30; // 30초 윈도우
  let breakpointFound = false;

  for (let i = windowSize; i < sortedTimes.length; i++) {
    // 윈도우 내 데이터 집계
    const windowDurations = [];
    let windowRequests = 0;
    let windowFailed = 0;

    for (let j = i - windowSize; j <= i; j++) {
      const timeData = timeSeriesData.get(sortedTimes[j]);
      if (timeData) {
        windowDurations.push(...timeData.durations);
        windowRequests += timeData.requestCount;
        windowFailed += timeData.failedCount;
      }
    }

    if (windowDurations.length === 0) continue;

    const p95 = percentile(windowDurations, 95);
    const p99 = percentile(windowDurations, 99);
    const failRate = windowFailed / Math.max(windowRequests, 1);

    // 한계점 조건 확인
    if (p95 > 1000 || p99 > 2000 || failRate > 0.05) {
      analysis.breakpointDetected = true;
      analysis.breakpointTime = new Date(sortedTimes[i] * 1000);

      const reasons = [];
      if (p95 > 1000) reasons.push(`p95=${p95.toFixed(0)}ms (>1000ms)`);
      if (p99 > 2000) reasons.push(`p99=${p99.toFixed(0)}ms (>2000ms)`);
      if (failRate > 0.05) reasons.push(`fail_rate=${(failRate * 100).toFixed(1)}% (>5%)`);

      analysis.breakpointReason = reasons.join(', ');

      // 한계점 시점의 RPS 추정
      const timeData = timeSeriesData.get(sortedTimes[i]);
      analysis.breakpointRPS = windowRequests / windowSize;

      breakpointFound = true;
      break;
    }
  }

  if (!breakpointFound) {
    // 한계점에 도달하지 않음 (시스템이 충분히 처리함)
    analysis.breakpointRPS = analysis.metrics.http_reqs.rate;
  }
}

/**
 * 권장사항 생성
 */
function generateRecommendations() {
  const { p95, p99 } = analysis.metrics.http_req_duration;
  const failRate = analysis.failedRequests / Math.max(analysis.totalRequests, 1);

  // 응답 시간 기반 권장사항
  if (p95 > 500) {
    analysis.recommendations.push({
      category: 'RESPONSE_TIME',
      severity: p95 > 1000 ? 'HIGH' : 'MEDIUM',
      message: `p95 응답 시간이 ${p95.toFixed(0)}ms입니다. 500ms 이하로 개선이 필요합니다.`,
      suggestions: [
        '쿼리 최적화 (N+1 문제, 인덱스 확인)',
        '캐싱 전략 도입 (Redis, Local Cache)',
        '비동기 처리 검토 (@Async, 메시지 큐)',
      ],
    });
  }

  if (p99 > 1000) {
    analysis.recommendations.push({
      category: 'TAIL_LATENCY',
      severity: 'MEDIUM',
      message: `p99 응답 시간이 ${p99.toFixed(0)}ms입니다. 꼬리 지연 시간이 높습니다.`,
      suggestions: [
        'Connection pool 크기 확인 (HikariCP, Tomcat)',
        'GC 튜닝 (G1GC 파라미터 조정)',
        'Slow query 분석 및 최적화',
      ],
    });
  }

  // 실패율 기반 권장사항
  if (failRate > 0.01) {
    analysis.recommendations.push({
      category: 'ERROR_RATE',
      severity: failRate > 0.05 ? 'HIGH' : 'MEDIUM',
      message: `실패율이 ${(failRate * 100).toFixed(2)}%입니다.`,
      suggestions: [
        'Connection timeout 설정 확인',
        'Circuit breaker 패턴 도입',
        'Rate limiting 설정 검토',
      ],
    });
  }

  // VU 기반 권장사항
  if (analysis.metrics.vus.max > 400) {
    analysis.recommendations.push({
      category: 'CONCURRENCY',
      severity: 'MEDIUM',
      message: `동시 VU가 ${analysis.metrics.vus.max}까지 증가했습니다.`,
      suggestions: [
        'Tomcat thread pool 크기 증가',
        'HikariCP connection pool 크기 조정',
        'Horizontal scaling 검토 (인스턴스 추가)',
      ],
    });
  }

  // 한계점 관련 권장사항
  if (analysis.breakpointDetected) {
    analysis.recommendations.push({
      category: 'BREAKPOINT',
      severity: 'HIGH',
      message: `한계점 감지: ${analysis.breakpointRPS.toFixed(1)} RPS에서 성능 저하 발생`,
      suggestions: [
        '병목 지점 분석 (Tomcat/DB/Network)',
        'MSA 분리 검토 (해당 도메인 독립 서비스화)',
        '수평 확장 계획 수립',
      ],
    });
  }
}

/**
 * 리포트 출력
 */
function printReport() {
  console.log('\n');
  console.log('═'.repeat(70));
  console.log('  K6 BREAKPOINT TEST ANALYSIS REPORT');
  console.log('═'.repeat(70));

  // 테스트 개요
  console.log('\n[ Test Overview ]');
  console.log(`  Start Time     : ${analysis.startTime?.toISOString() || 'N/A'}`);
  console.log(`  End Time       : ${analysis.endTime?.toISOString() || 'N/A'}`);
  console.log(`  Total Requests : ${analysis.totalRequests.toLocaleString()}`);
  console.log(`  Failed Requests: ${analysis.failedRequests.toLocaleString()}`);
  console.log(`  Fail Rate      : ${((analysis.failedRequests / Math.max(analysis.totalRequests, 1)) * 100).toFixed(2)}%`);
  console.log(`  Max VUs        : ${analysis.metrics.vus.max}`);

  // 응답 시간 통계
  console.log('\n[ Response Time Statistics (ms) ]');
  const d = analysis.metrics.http_req_duration;
  console.log(`  Min    : ${d.min === Infinity ? 'N/A' : d.min.toFixed(2)}`);
  console.log(`  Avg    : ${d.avg.toFixed(2)}`);
  console.log(`  p50    : ${d.p50.toFixed(2)}`);
  console.log(`  p95    : ${d.p95.toFixed(2)}`);
  console.log(`  p99    : ${d.p99.toFixed(2)}`);
  console.log(`  Max    : ${d.max.toFixed(2)}`);

  // 처리량
  console.log('\n[ Throughput ]');
  console.log(`  Total Requests : ${analysis.metrics.http_reqs.count.toLocaleString()}`);
  console.log(`  Avg RPS        : ${analysis.metrics.http_reqs.rate.toFixed(2)}`);

  // 한계점 분석
  console.log('\n[ Breakpoint Analysis ]');
  if (analysis.breakpointDetected) {
    console.log(`  ⚠️  BREAKPOINT DETECTED!`);
    console.log(`  Time   : ${analysis.breakpointTime?.toISOString() || 'N/A'}`);
    console.log(`  RPS    : ~${analysis.breakpointRPS.toFixed(1)} requests/sec`);
    console.log(`  Reason : ${analysis.breakpointReason}`);
  } else {
    console.log(`  ✓ No breakpoint detected within test duration`);
    console.log(`  System handled up to ${analysis.breakpointRPS.toFixed(1)} RPS`);
  }

  // 권장사항
  if (analysis.recommendations.length > 0) {
    console.log('\n[ Recommendations ]');
    analysis.recommendations.forEach((rec, idx) => {
      const icon = rec.severity === 'HIGH' ? '🔴' : rec.severity === 'MEDIUM' ? '🟡' : '🟢';
      console.log(`\n  ${idx + 1}. [${rec.category}] ${icon} ${rec.severity}`);
      console.log(`     ${rec.message}`);
      console.log('     Suggestions:');
      rec.suggestions.forEach((s) => console.log(`       - ${s}`));
    });
  }

  // 병목 지점 판단 가이드
  console.log('\n[ Bottleneck Identification Guide ]');
  console.log('  Check these metrics in Grafana/Prometheus:');
  console.log('');
  console.log('  1. If `tomcat_threads_busy` hits max first:');
  console.log('     → Consider async processing (@Async, WebFlux)');
  console.log('');
  console.log('  2. If `hikaricp_connections_active` hits max first:');
  console.log('     → Increase connection pool or optimize queries');
  console.log('');
  console.log('  3. If only response time increases (no saturation):');
  console.log('     → Focus on logic optimization and caching');

  console.log('\n' + '═'.repeat(70));
  console.log('\n');
}

/**
 * JSON 리포트 저장
 */
function saveJsonReport(outputPath) {
  const report = {
    summary: {
      totalRequests: analysis.totalRequests,
      failedRequests: analysis.failedRequests,
      failRate: analysis.failedRequests / Math.max(analysis.totalRequests, 1),
      maxVUs: analysis.metrics.vus.max,
      avgRPS: analysis.metrics.http_reqs.rate,
    },
    responseTime: {
      min: analysis.metrics.http_req_duration.min === Infinity ? null : analysis.metrics.http_req_duration.min,
      avg: analysis.metrics.http_req_duration.avg,
      p50: analysis.metrics.http_req_duration.p50,
      p95: analysis.metrics.http_req_duration.p95,
      p99: analysis.metrics.http_req_duration.p99,
      max: analysis.metrics.http_req_duration.max,
    },
    breakpoint: {
      detected: analysis.breakpointDetected,
      time: analysis.breakpointTime?.toISOString() || null,
      rps: analysis.breakpointRPS,
      reason: analysis.breakpointReason,
    },
    recommendations: analysis.recommendations,
    testDuration: {
      start: analysis.startTime?.toISOString() || null,
      end: analysis.endTime?.toISOString() || null,
    },
  };

  fs.writeFileSync(outputPath, JSON.stringify(report, null, 2));
  console.log(`JSON report saved to: ${outputPath}`);
}

/**
 * 메인 함수
 */
async function main() {
  const args = process.argv.slice(2);

  if (args.length < 1) {
    console.log('Usage: node breakpoint-report.js <k6-output.json> [--json <output.json>]');
    console.log('');
    console.log('Example:');
    console.log('  k6 run --out json=results/test.json scenarios/breakpoint/auth-breakpoint.js');
    console.log('  node analysis/breakpoint-report.js results/test.json');
    console.log('  node analysis/breakpoint-report.js results/test.json --json report.json');
    process.exit(1);
  }

  const inputFile = args[0];
  const jsonOutputIndex = args.indexOf('--json');
  const jsonOutput = jsonOutputIndex !== -1 ? args[jsonOutputIndex + 1] : null;

  if (!fs.existsSync(inputFile)) {
    console.error(`Error: File not found: ${inputFile}`);
    process.exit(1);
  }

  console.log(`Analyzing: ${inputFile}`);
  console.log('This may take a while for large files...');

  await analyzeFile(inputFile);
  calculateStatistics();
  detectBreakpoint();
  generateRecommendations();
  printReport();

  if (jsonOutput) {
    saveJsonReport(jsonOutput);
  }
}

main().catch(console.error);
