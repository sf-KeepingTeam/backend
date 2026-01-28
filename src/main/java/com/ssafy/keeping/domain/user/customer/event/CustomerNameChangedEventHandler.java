package com.ssafy.keeping.domain.user.customer.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.keeping.domain.group.repository.GroupMemberRepository;
import com.ssafy.keeping.global.outbox.model.OutboxEvent;
import com.ssafy.keeping.global.outbox.service.OutboxEventHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * CustomerNameChanged 이벤트 핸들러
 * Pattern 3 (데이터 복제) - 고객 이름 변경 시 스냅샷 동기화
 *
 * 처리 대상:
 * - GroupMember.customerNameSnapshot
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CustomerNameChangedEventHandler implements OutboxEventHandler {

    private final GroupMemberRepository groupMemberRepository;
    private final ObjectMapper objectMapper;

    @Override
    public String getEventType() {
        return "CustomerNameChanged";
    }

    @Override
    @Transactional
    public void handle(OutboxEvent event) throws Exception {
        CustomerNameChangedPayload payload = objectMapper.readValue(
                event.getPayload(), CustomerNameChangedPayload.class);

        log.info("[이벤트] CustomerNameChanged 처리 시작 - customerId: {}, {} -> {}",
                payload.getCustomerId(), payload.getOldName(), payload.getNewName());

        // GroupMember의 customerNameSnapshot 업데이트
        int updatedCount = groupMemberRepository.updateCustomerNameSnapshot(
                payload.getCustomerId(), payload.getNewName());

        log.info("[이벤트] CustomerNameChanged 처리 완료 - customerId: {}, 업데이트된 GroupMember 수: {}",
                payload.getCustomerId(), updatedCount);
    }
}
