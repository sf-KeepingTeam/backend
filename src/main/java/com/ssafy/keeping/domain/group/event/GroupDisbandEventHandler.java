package com.ssafy.keeping.domain.group.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.keeping.domain.group.model.Group;
import com.ssafy.keeping.domain.group.repository.GroupMemberRepository;
import com.ssafy.keeping.domain.group.repository.GroupRepository;
import com.ssafy.keeping.domain.notification.entity.NotificationType;
import com.ssafy.keeping.domain.notification.service.NotificationService;
import com.ssafy.keeping.domain.wallet.model.Wallet;
import com.ssafy.keeping.domain.wallet.repository.WalletRepository;
import com.ssafy.keeping.domain.wallet.repository.WalletStoreBalanceRepository;
import com.ssafy.keeping.domain.wallet.repository.WalletStoreLotRepository;
import com.ssafy.keeping.domain.wallet.service.WalletService;
import com.ssafy.keeping.global.exception.CustomException;
import com.ssafy.keeping.global.exception.constants.ErrorCode;
import com.ssafy.keeping.global.outbox.model.OutboxEvent;
import com.ssafy.keeping.global.outbox.service.OutboxEventHandler;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * GroupDisbandInitiated 이벤트 핸들러
 * 그룹 해산 Saga의 후속 작업 수행:
 * 1. 모든 멤버의 포인트 환급
 * 2. Lot/Balance 정리
 * 3. 멤버 삭제
 * 4. 지갑 삭제
 * 5. 그룹 삭제 및 알림
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GroupDisbandEventHandler implements OutboxEventHandler {

    private final ObjectMapper objectMapper;
    private final GroupRepository groupRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final WalletRepository walletRepository;
    private final WalletStoreBalanceRepository balanceRepository;
    private final WalletStoreLotRepository lotRepository;
    private final WalletService walletService;
    private final NotificationService notificationService;

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public String getEventType() {
        return "GroupDisbandInitiated";
    }

    @Override
    @Transactional
    public void handle(OutboxEvent event) throws Exception {
        GroupDisbandPayload payload = objectMapper.readValue(
                event.getPayload(), GroupDisbandPayload.class);

        Long groupId = payload.getGroupId();
        Long walletId = payload.getWalletId();

        log.info("[이벤트] GroupDisbandInitiated 처리 시작 - groupId: {}", groupId);

        // 1. 그룹 상태 확인
        Group group = groupRepository.findById(groupId)
                .orElseThrow(() -> new CustomException(ErrorCode.GROUP_NOT_FOUND));

        if (!group.isDisbanding()) {
            log.warn("[이벤트] 그룹이 DISBANDING 상태가 아님 - groupId: {}, status: {}",
                    groupId, group.getStatus());
            return;
        }

        // 2. 모든 멤버에게 포인트 환급
        Map<Long, Long> refundedByMember = walletService.settleAllMembersShare(groupId, payload.getMemberIds());
        long totalRefunded = refundedByMember.values().stream().mapToLong(Long::longValue).sum();

        log.info("[이벤트] 포인트 환급 완료 - groupId: {}, totalRefunded: {}", groupId, totalRefunded);

        // 3. 잔액 검증
        long remain = balanceRepository.sumByWalletIdForUpdate(walletId).orElse(0L);
        if (remain != 0L) {
            log.error("[이벤트] 잔액이 0이 아님 - groupId: {}, remain: {}", groupId, remain);
            throw new CustomException(ErrorCode.INCONSISTENT_STATE);
        }

        // 4. Lot/Balance/Member 삭제
        lotRepository.deleteByWalletId(walletId);
        balanceRepository.deleteByWalletId(walletId);
        groupMemberRepository.deleteByGroupId(groupId);
        walletRepository.deleteById(walletId);

        // 5. 영속성 컨텍스트 정리
        entityManager.flush();
        entityManager.clear();

        // 6. 그룹 삭제
        Group groupRef = groupRepository.getReferenceById(groupId);
        groupRepository.delete(groupRef);

        log.info("[이벤트] GroupDisbandInitiated 처리 완료 - groupId: {}", groupId);

        // 7. 알림 전송 (비동기로 처리되므로 실패해도 이벤트는 완료)
        try {
            refundedByMember.forEach((customerId, amount) ->
                    notificationService.sendToCustomer(
                            customerId, NotificationType.GROUP_DISBANDED,
                            "모임 해체. 환급 " + amount + "P 완료."));
        } catch (Exception e) {
            log.warn("[이벤트] 알림 전송 실패 - groupId: {}", groupId, e);
        }
    }
}
