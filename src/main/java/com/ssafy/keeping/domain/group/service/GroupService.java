package com.ssafy.keeping.domain.group.service;

import com.ssafy.keeping.domain.group.constant.GroupStatus;
import com.ssafy.keeping.domain.group.constant.RequestStatus;
import com.ssafy.keeping.domain.group.dto.*;
import com.ssafy.keeping.domain.group.event.GroupDisbandPayload;
import com.ssafy.keeping.domain.group.model.Group;
import com.ssafy.keeping.domain.group.model.GroupAddRequest;
import com.ssafy.keeping.domain.group.model.GroupMember;
import com.ssafy.keeping.domain.group.repository.GroupAddRequestRepository;
import com.ssafy.keeping.domain.group.repository.GroupMemberRepository;
import com.ssafy.keeping.domain.group.repository.GroupRepository;
import com.ssafy.keeping.domain.notification.entity.NotificationType;
import com.ssafy.keeping.domain.notification.service.NotificationService;
import com.ssafy.keeping.domain.user.customer.model.Customer;
import com.ssafy.keeping.domain.user.customer.repository.CustomerRepository;
import com.ssafy.keeping.domain.wallet.dto.WalletResponseDto;
import com.ssafy.keeping.domain.wallet.service.WalletService;
import com.ssafy.keeping.global.exception.CustomException;
import com.ssafy.keeping.global.exception.constants.ErrorCode;
import com.ssafy.keeping.global.outbox.service.OutboxPublisher;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

import static com.ssafy.keeping.global.util.TxUtils.afterCommit;

@Service
@RequiredArgsConstructor
@Slf4j
public class GroupService {
    private static final int MAX_RETRY = 5;

    private final WalletService walletService;
    private final GroupRepository groupRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final GroupAddRequestRepository groupAddRequestRepository;
    private final NotificationService notificationService;
    private final OutboxPublisher outboxPublisher;

    // User 도메인 - 이름 조회용 (향후 Pattern 3 데이터 복제로 전환 예정)
    // MSA 전환 시: GroupMember에 customerName 스냅샷 필드 추가하여 CustomerRepository 의존성 제거
    private final CustomerRepository customerRepository;

    @Transactional
    public GroupResponseDto createGroup(Long groupLeaderId, GroupRequestDto requestDto) {
        Customer customer = validCustomer(groupLeaderId);

        String groupName = requestDto.getGroupName();
        String groupDescription = (requestDto.getGroupDescription() == null || requestDto.getGroupDescription().isBlank())
                ? String.format("%s의 모임입니다.", groupName)
                : requestDto.getGroupDescription();

        Group saved = null;
        for (int i = 0; i < MAX_RETRY; i++) {
            String inviteCode = makeGroupCode();
            try {
                saved = groupRepository.save(
                        Group.builder()
                                .groupName(groupName)
                                .groupDescription(groupDescription)
                                .groupCode(inviteCode)
                                .build()
                );
                break;
            } catch (DataIntegrityViolationException e) {
                if (i == MAX_RETRY - 1) throw e; // 유니크 충돌 반복 시 최종 에러
            }
        }

        groupMemberRepository.save(
                GroupMember.builder()
                        .group(saved)
                        .customerId(customer.getCustomerId())
                        .leader(true)
                        .build()
        );

        // 해당 모임의 지갑 생성 로직 추가
        WalletResponseDto responseDto = walletService.createGroupWallet(saved.getGroupId());

        return new GroupResponseDto(
                saved.getGroupId(), saved.getGroupName(),
                saved.getGroupDescription(), saved.getGroupCode(),
                responseDto.walletId().longValue()
        );
    }

    public GroupResponseDto getGroup(Long groupId, Long userId) {
        Group group = validGroup(groupId);

        boolean isGroupMember = groupMemberRepository
                                .existsMember(groupId, userId);
        if (!isGroupMember)
            throw new CustomException(ErrorCode.ONLY_GROUP_MEMBER);

        WalletResponseDto groupWallet = walletService.getGroupWallet(group.getGroupId());

        return new GroupResponseDto(
                group.getGroupId(), group.getGroupName(),
                group.getGroupDescription(), group.getGroupCode(),
                groupWallet.walletId()
        );
    }

    @Transactional
    public GroupResponseDto editGroup(Long groupId, Long customerId, GroupEditRequestDto requestDto) {
        Group group = validGroup(groupId);

        boolean isGroupLeader = groupMemberRepository
                .existsLeader(groupId, customerId);
        if (!isGroupLeader)
            throw new CustomException(ErrorCode.ONLY_GROUP_LEADER);

        // 방어 로직
        group.editGroup(
                Optional.ofNullable(requestDto.getGroupName()).orElse(group.getGroupName()),
                Optional.ofNullable(requestDto.getGroupDescription()).orElse(group.getGroupDescription())
        );

        return new GroupResponseDto(
                group.getGroupId(), group.getGroupName(),
                group.getGroupDescription(), group.getGroupCode(),
                null
        );
    }

    public List<GroupMemberResponseDto> getGroupMembers(Long groupId, Long customerId) {
       validGroup(groupId);

        boolean isGroupMember = groupMemberRepository
                .existsMember(groupId, customerId);

        if (!isGroupMember)
            throw new CustomException(ErrorCode.ONLY_GROUP_MEMBER);

        return groupMemberRepository.findAllGroupMembers(groupId);
    }

    @Transactional
    public void createGroupAddRequest(Long groupId, Long customerId) {
        Group group = validGroup(groupId);

        boolean isGroupMember = groupMemberRepository
                .existsMember(groupId, customerId);
        if (isGroupMember)
            throw new CustomException(ErrorCode.ALREADY_GROUP_MEMBER);

        validCustomer(customerId);

        boolean alreadyRequest = groupAddRequestRepository
                .existsRequest(groupId, customerId, RequestStatus.PENDING);

        if (alreadyRequest) throw new CustomException(ErrorCode.ALREADY_GROUP_REQUEST);

        groupAddRequestRepository.save(
                GroupAddRequest.builder()
                        .customerId(customerId)
                        .group(group)
                        .build()
        );

        Long leaderId = groupMemberRepository.findLeaderId(groupId)
                .orElseThrow(() -> new CustomException(ErrorCode.GROUP_LEADER_NOT_FOUND));

        afterCommit(() -> notificationService.sendToCustomer(
                leaderId, NotificationType.GROUP_JOIN_REQUEST,
                "새 가입 요청이 도착했습니다."));

    }

    private String makeGroupCode() {
        return UUID.randomUUID()
                .toString()
                .replace("-", "")
                .substring(0, 12)
                .toUpperCase();
    }

    public List<AddRequestResponseDto> getAllGroupAddRequest(Long groupId, Long customerId) {
       validGroup(groupId);

        boolean isGroupLeader = groupMemberRepository
                .existsLeader(groupId, customerId);
        if (!isGroupLeader)
            throw new CustomException(ErrorCode.ONLY_GROUP_LEADER);

        return groupAddRequestRepository.findAllAddRequestInPending(groupId, RequestStatus.PENDING);
    }

    @Transactional
    public AddRequestResponseDto updateAddRequestStatus(Long groupId, Long customerId, @Valid AddRequestDecisionDto request) {
        Group group = validGroup(groupId);

        Long groupAddRequestId = request.getGroupAddRequestId();

        GroupAddRequest groupAddRequest = groupAddRequestRepository.findById(groupAddRequestId)
                .orElseThrow(
                () -> new CustomException(ErrorCode.ADD_REQUEST_NOT_FOUND)
        );

        validCustomer(customerId);

        boolean isGroupLeader = groupMemberRepository
                .existsLeader(groupId, customerId);
        if (!isGroupLeader)
            throw new CustomException(ErrorCode.ONLY_GROUP_LEADER);


        if (groupAddRequest.getRequestStatus() != RequestStatus.PENDING)
            throw new CustomException(ErrorCode.ALREADY_PROCESS_REQUEST);

        if (!groupAddRequest.getGroup().getGroupId().equals(groupId)) {
            throw new CustomException(ErrorCode.BAD_REQUEST);
        }

        RequestStatus changeStatus = request.getIsAccept() == Boolean.TRUE
                ? RequestStatus.ACCEPT : RequestStatus.REJECT;
        groupAddRequest.changeStatus(changeStatus);



        Long requesterId = groupAddRequest.getCustomerId();

        if (changeStatus == RequestStatus.ACCEPT) {
            if (!groupMemberRepository.existsMember(groupId, requesterId)) {
                groupMemberRepository.save(GroupMember.builder()
                        .group(group)
                        .leader(false)
                        .customerId(requesterId)
                        .build());
            }
        }

        boolean accepted = (changeStatus == RequestStatus.ACCEPT);

        afterCommit(() -> notificationService.sendToCustomer(
                requesterId,
                accepted ? NotificationType.GROUP_JOIN_ACCEPTED : NotificationType.GROUP_JOIN_REJECTED,
                accepted ? "가입이 승인되었습니다." : "가입이 거절되었습니다."));

        Customer requester = customerRepository.findById(requesterId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        return new AddRequestResponseDto(
            groupAddRequest.getGroupAddRequestId(), requester.getName(),
                groupAddRequest.getRequestStatus()
        );
    }

    @Transactional
    public GroupResponseDto createGroupMember(Long groupId, Long userId, GroupEntranceRequestDto requestDto) {
        Group group = validGroup(groupId);

        boolean isGroupMember = groupMemberRepository
                .existsMember(groupId, userId);

        WalletResponseDto groupWallet = walletService.getGroupWallet(group.getGroupId());

        if (isGroupMember) {
            return new GroupResponseDto(
                    group.getGroupId(), group.getGroupName(),
                    group.getGroupDescription(), group.getGroupCode(),
                    groupWallet.walletId());
        }

        /* TODO: 복사해서 줄때 복사 날짜 시간을 접미사로 얹어서 주면,
            일정 시간이 지나면 안되게도 할 건지 논의 필요
        * */
        String groupCode = groupRepository.findGroupCodeById(groupId);
        if (!Objects.equals(groupCode, requestDto.getInviteCode()))
            throw new CustomException(ErrorCode.CODE_NOT_MATCH);

        validCustomer(userId);

        List<Long> memberIdsToNotify = groupMemberRepository.findMemberIdsByGroupId(groupId);

        groupMemberRepository.save(
                GroupMember.builder()
                        .group(group)
                        .leader(false)
                        .customerId(userId)
                        .build()
        );

        // 커밋 후 알림
        afterCommit(() -> {
            // 본인: 참여 완료
            notificationService.sendToCustomer(
                    userId, NotificationType.GROUP_JOINED, "모임 참여가 완료되었습니다.");

            // 기존 멤버 전원: 새 멤버 참여 알림 (본인 제외, 스냅샷 기반)
            memberIdsToNotify.stream()
                    .filter(id -> !id.equals(userId))
                    .distinct()
                    .forEach(id -> notificationService.sendToCustomer(
                            id, NotificationType.GROUP_JOINED, "새 멤버가 참여했습니다."));
        });


        return new GroupResponseDto(
                group.getGroupId(), group.getGroupName(),
                group.getGroupDescription(), group.getGroupCode(),
                groupWallet.walletId()
        );

    }

    public List<GroupMaskingResponseDto> getSearchGroup(Long customerId, String name) {
        // 고객만 모임을 검색할 수 있게 change => 경로로 막음 + valid 체크
        validCustomer(customerId);

        return groupRepository.findGroupsByName(name);
    }

    @Transactional
    public GroupLeaderChangeResponseDto changeGroupLeader(Long groupId, Long userId, @Valid GroupLeaderChangeRequestDto requestDto) {
        validGroup(groupId);
        validCustomer(userId);

        Long newLeaderUserId = requestDto.getNewGroupLeaderId();
        GroupMember originGroupLeader = validGroupMember(groupId, userId);
        GroupMember newGroupLeader = validGroupMember(groupId, newLeaderUserId);

        if (!originGroupLeader.isLeader())
            throw new CustomException(ErrorCode.ONLY_GROUP_LEADER);

        if (!originGroupLeader.changeLeader(false)
                || !newGroupLeader.changeLeader(true)) {
            throw new CustomException(ErrorCode.BAD_REQUEST);
        }

        final Long oldLeaderId = originGroupLeader.getCustomerId();
        final Long newLeaderId = newGroupLeader.getCustomerId();

        afterCommit(() -> {
            notificationService.sendToCustomer(
                    oldLeaderId, NotificationType.GROUP_LEADER_CHANGED, "리더 권한이 해제되었습니다.");
            notificationService.sendToCustomer(
                    newLeaderId, NotificationType.GROUP_LEADER_CHANGED, "새 리더로 지정되었습니다.");
        });

        Customer newLeaderCustomer = customerRepository.findById(newLeaderId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        return new GroupLeaderChangeResponseDto(
                groupId, newGroupLeader.getGroupMemberId(), newLeaderCustomer.getName()
        );
    }

    @Transactional
    public void expelMember(Long groupId, Long leaderId, Long targetCustomerId) {
        Group group = validGroup(groupId);
        if (!groupMemberRepository.existsLeader(groupId, leaderId)) throw new CustomException(ErrorCode.ONLY_GROUP_LEADER);
        if (leaderId.equals(targetCustomerId)) throw new CustomException(ErrorCode.BAD_REQUEST);

        GroupMember target = validGroupMember(groupId, targetCustomerId);
        if (target.isLeader()) throw new CustomException(ErrorCode.BAD_REQUEST);

        long remain = walletService.getMemberSharedBalance(groupId, targetCustomerId); // 변경
        if (remain > 0L) walletService.settleShareToIndividual(groupId, targetCustomerId); // 변경

        String groupName = group.getGroupName();
        Customer targetCustomer = customerRepository.findById(targetCustomerId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
        String targetName = targetCustomer.getName();
        List<Long> memberIds = groupMemberRepository.findMemberIdsByGroupId(groupId);

        groupMemberRepository.delete(target);

        afterCommit(() -> {
            notificationService.sendToCustomer(
                    targetCustomerId, NotificationType.MEMBER_EXPELLED,
                    String.format("%s 모임에서 내보내졌습니다.", groupName)
            );
            memberIds.forEach(id ->
                    notificationService.sendToCustomer(
                            id, NotificationType.MEMBER_EXPELLED,
                            String.format("%s 모임의 모임원 %s이 내보내졌습니다.", groupName, targetName)
                    )
            );
        });
    }

    @Transactional
    public GroupLeaveResponseDto leaveGroup(Long groupId, Long customerId) {
        Group group = validGroup(groupId);
        GroupMember me = validGroupMember(groupId, customerId);
        if (me.isLeader()) throw new CustomException(ErrorCode.ONLY_GROUP_LEADER);

        long refunded = walletService.settleShareToIndividual(groupId, customerId); // 변경
        groupMemberRepository.delete(me);

        long indivBalance = walletService.getTotalIndividualBalance(customerId);

        List<Long> others = groupMemberRepository.findMemberIdsByGroupId(groupId);
        afterCommit(() -> {
            notificationService.sendToCustomer(
                    customerId, NotificationType.GROUP_LEFT,
                    String.format("모임 탈퇴, %dP 환급되었습니다. 잔액 %dP", refunded, indivBalance));
            others.forEach(id -> notificationService.sendToCustomer(
                    id, NotificationType.GROUP_LEFT, "모임원이 탈퇴했습니다."));
        });

        return new GroupLeaveResponseDto(groupId, customerId, refunded, indivBalance, LocalDateTime.now());
    }

    /**
     * 그룹 해산 시작 (Saga 패턴 - 비동기)
     * Phase 1: 상태를 DISBANDING으로 변경하고 Outbox 이벤트 발행
     * 실제 해산 작업은 GroupDisbandEventHandler에서 수행
     *
     * 장점:
     * - 긴 트랜잭션 분리로 DB 락 시간 감소
     * - 실패 시 재시도 가능
     * - DB 분리 시에도 동작
     */
    @Transactional
    public GroupDisbandResponseDto initiateDisbandGroupAsync(Long groupId, Long leaderId) {
        Group group = validGroup(groupId);
        GroupMember me = validGroupMember(groupId, leaderId);
        if (!me.isLeader()) throw new CustomException(ErrorCode.ONLY_GROUP_LEADER);

        // 이미 해산 중이거나 해산된 그룹인지 확인
        if (!group.isActive()) {
            log.warn("[그룹해산] 이미 해산 중인 그룹 - groupId: {}, status: {}", groupId, group.getStatus());
            throw new CustomException(ErrorCode.BAD_REQUEST);
        }

        // WalletService를 통해 지갑 ID 조회 (MSA 대비 - WalletRepository 직접 접근 제거)
        Long walletId = walletService.getGroupWalletId(groupId);
        List<Long> memberIds = groupMemberRepository.findMemberIdsByGroupId(groupId);

        // 상태를 DISBANDING으로 변경
        group.startDisband();

        log.info("[그룹해산] Saga 시작 - groupId: {}, memberCount: {}", groupId, memberIds.size());

        // Outbox 이벤트 발행
        outboxPublisher.publish(
                "Group",
                groupId.toString(),
                "GroupDisbandInitiated",
                GroupDisbandPayload.builder()
                        .groupId(groupId)
                        .walletId(walletId)
                        .memberIds(memberIds)
                        .leaderId(leaderId)
                        .build()
        );

        // 비동기 처리이므로 환급 정보는 아직 없음
        return new GroupDisbandResponseDto(
                groupId, memberIds.size(), 0L, Map.of(), LocalDateTime.now());
    }


    /**
     * ===============validate method=============
     *  Group, GroupMember, Customer 검증
     *  GroupWallet 검증은 WalletService.getGroupWalletId() 사용
     */

    public Group validGroup(Long groupId) {
        return groupRepository.findById(groupId).orElseThrow(
                () -> new CustomException(ErrorCode.GROUP_NOT_FOUND));
    }

    public GroupMember validGroupMember(Long groupId, Long userId) {
        return groupMemberRepository.findGroupMember(groupId, userId).orElseThrow(
                () -> new CustomException(ErrorCode.GROUP_MEMBER_NOT_FOUND)
        );
    }

    /**
     * 고객 존재 확인 (이름 조회용)
     * 향후 Pattern 3 (데이터 복제)로 전환하여 CustomerRepository 의존성 제거 예정
     * MSA 전환 시: GroupMember/GroupAddRequest에 customerName 스냅샷 필드 추가
     */
    public Customer validCustomer(Long customerId) {
        return customerRepository.findById(customerId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
    }
}
