package com.ssafy.keeping.qr.loadtest;

import com.ssafy.keeping.qr.domain.intent.constant.PaymentStatus;
import com.ssafy.keeping.qr.domain.intent.model.PaymentIntent;
import com.ssafy.keeping.qr.domain.intent.repository.PaymentIntentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 인덱스 성능 벤치마크용 테스트 컨트롤러
 * loadtest.backdoor.enabled=true 일 때만 활성화
 *
 * 테스트 대상:
 * - 쓰기: PaymentIntent INSERT (인덱스가 쓰기 성능에 미치는 영향)
 * - 읽기: findRecoveryTargets 쿼리 (idx_recovery 인덱스 사용)
 */
@Slf4j
@RestController
@RequestMapping("/loadtest/benchmark")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "loadtest.backdoor.enabled", havingValue = "true")
public class IndexBenchmarkController {

    private final PaymentIntentRepository paymentIntentRepository;

    /**
     * 헬스체크
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "message", "Index benchmark backdoor is enabled"
        ));
    }

    /**
     * 쓰기 테스트: PaymentIntent 생성
     * POST /loadtest/benchmark/write
     *
     * 테스트 목적: 인덱스가 INSERT 성능에 미치는 영향 측정
     * - idx_recovery (status, expires_at, created_at)
     * - idx_status_expires (status, expires_at)
     * - idx_store_status (store_id, status)
     * - idx_wallet_status (wallet_id, status)
     */
    @PostMapping("/write")
    public ResponseEntity<Map<String, Object>> writeTest(@RequestBody WriteRequest request) {
        LocalDateTime now = LocalDateTime.now();

        PaymentIntent intent = PaymentIntent.builder()
                .publicId(UUID.randomUUID())
                .qrTokenId("bench-" + UUID.randomUUID())
                .customerId(request.customerId())
                .walletId(request.walletId())
                .storeId(request.storeId())
                .amount(request.amount())
                .status(PaymentStatus.valueOf(request.status()))
                .createdAt(now)
                .updatedAt(now)
                .expiresAt(now.plusMinutes(5))
                .idempotencyKey(UUID.randomUUID().toString())
                .version(0L)
                .build();

        PaymentIntent saved = paymentIntentRepository.save(intent);

        return ResponseEntity.ok(Map.of(
                "success", true,
                "intentId", saved.getIntentId(),
                "publicId", saved.getPublicId().toString(),
                "status", saved.getStatus().name()
        ));
    }

    /**
     * 읽기 테스트: findRecoveryTargets 쿼리 호출
     * GET /loadtest/benchmark/read
     *
     * 테스트 목적: idx_recovery 인덱스가 SELECT 성능에 미치는 영향 측정
     * 쿼리: WHERE (status = 'UNCERTAIN' OR (status = 'PENDING' AND expires_at < now))
     *            AND created_at > since ORDER BY created_at ASC
     */
    @GetMapping("/read")
    public ResponseEntity<Map<String, Object>> readTest(
            @RequestParam(defaultValue = "7") int days
    ) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime since = now.minusDays(days);

        List<PaymentIntent> targets = paymentIntentRepository.findRecoveryTargets(
                PaymentStatus.UNCERTAIN,
                PaymentStatus.PENDING,
                now,
                since
        );

        return ResponseEntity.ok(Map.of(
                "success", true,
                "count", targets.size(),
                "queryParams", Map.of(
                        "uncertainStatus", "UNCERTAIN",
                        "pendingStatus", "PENDING",
                        "now", now.toString(),
                        "since", since.toString()
                )
        ));
    }

    /**
     * 벌크 쓰기 테스트: 여러 PaymentIntent 한번에 생성
     * POST /loadtest/benchmark/write-bulk
     */
    @PostMapping("/write-bulk")
    public ResponseEntity<Map<String, Object>> writeBulkTest(@RequestBody BulkWriteRequest request) {
        LocalDateTime now = LocalDateTime.now();
        int created = 0;

        for (int i = 0; i < request.count(); i++) {
            PaymentIntent intent = PaymentIntent.builder()
                    .publicId(UUID.randomUUID())
                    .qrTokenId("bulk-" + UUID.randomUUID())
                    .customerId(request.customerId())
                    .walletId(request.walletId())
                    .storeId(request.storeId())
                    .amount(request.amount())
                    .status(PaymentStatus.valueOf(request.status()))
                    .createdAt(now.minusMinutes(i))  // 시간 분산
                    .updatedAt(now)
                    .expiresAt(now.plusMinutes(5))
                    .idempotencyKey(UUID.randomUUID().toString())
                    .version(0L)
                    .build();

            paymentIntentRepository.save(intent);
            created++;
        }

        return ResponseEntity.ok(Map.of(
                "success", true,
                "createdCount", created
        ));
    }

    /**
     * 테스트 데이터 정리
     * DELETE /loadtest/benchmark/cleanup
     */
    @DeleteMapping("/cleanup")
    public ResponseEntity<Map<String, Object>> cleanup(
            @RequestParam(defaultValue = "bench-") String prefix
    ) {
        // qrTokenId가 prefix로 시작하는 것만 삭제
        List<PaymentIntent> toDelete = paymentIntentRepository.findAll().stream()
                .filter(pi -> pi.getQrTokenId() != null && pi.getQrTokenId().startsWith(prefix))
                .toList();

        paymentIntentRepository.deleteAll(toDelete);

        return ResponseEntity.ok(Map.of(
                "success", true,
                "deletedCount", toDelete.size()
        ));
    }

    public record WriteRequest(
            Long customerId,
            Long walletId,
            Long storeId,
            Long amount,
            String status  // PENDING, UNCERTAIN 등
    ) {}

    public record BulkWriteRequest(
            Long customerId,
            Long walletId,
            Long storeId,
            Long amount,
            String status,
            int count
    ) {}
}
