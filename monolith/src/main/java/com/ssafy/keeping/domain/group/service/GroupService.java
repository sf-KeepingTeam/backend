package com.ssafy.keeping.domain.group.service;

import com.ssafy.keeping.domain.notification.entity.NotificationType;
import com.ssafy.keeping.domain.notification.service.NotificationService;
import com.ssafy.keeping.domain.user.customer.model.Customer;
import com.ssafy.keeping.domain.user.customer.repository.CustomerRepository;
import com.ssafy.keeping.domain.wallet.dto.WalletResponseDto;
import com.ssafy.keeping.domain.wallet.model.Wallet;
import com.ssafy.keeping.domain.wallet.repository.WalletRepository;
import com.ssafy.keeping.domain.wallet.repository.WalletStoreBalanceRepository;
import com.ssafy.keeping.domain.wallet.repository.WalletStoreLotRepository;
import com.ssafy.keeping.domain.wallet.service.WalletService;
import com.ssafy.keeping.domain.group.constant.RequestStatus;
import com.ssafy.keeping.domain.group.dto.*;
import com.ssafy.keeping.domain.group.model.Group;
import com.ssafy.keeping.domain.group.model.GroupAddRequest;
import com.ssafy.keeping.domain.group.model.GroupMember;
import com.ssafy.keeping.domain.group.repository.GroupAddRequestRepository;
import com.ssafy.keeping.domain.group.repository.GroupMemberRepository;
import com.ssafy.keeping.domain.group.repository.GroupRepository;
import com.ssafy.keeping.global.exception.CustomException;
import com.ssafy.keeping.global.exception.constants.ErrorCode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

import static com.ssafy.keeping.global.util.TxUtils.afterCommit;

@Service
@RequiredArgsConstructor
public class GroupService {
    private static final int MAX_RETRY = 5;
    @PersistenceContext
    private EntityManager entityManager;

    private final WalletService walletService;
    private final CustomerRepository customerRepository;
    private final GroupRepository groupRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final GroupAddRequestRepository groupAddRequestRepository;
    private final NotificationService notificationService;

    private final WalletRepository walletRepository;
    private final WalletStoreBalanceRepository balanceRepository;
    private final WalletStoreLotRepository lotRepository;

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
                        .user(customer)
                        .leader(true)
                        .build()
        );

        // 해당 모임의 지갑 생성 로직 추가
        WalletResponseDto responseDto = walletService.createGroupWallet(saved);

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

        WalletResponseDto groupWallet = walletService.getGroupWallet(group);

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

        Customer customer = validCustomer(customerId);

        boolean alreadyRequest = groupAddRequestRepository
                .existsRequest(groupId, customerId, RequestStatus.PENDING);

        if (alreadyRequest) throw new CustomException(ErrorCode.ALREADY_GROUP_REQUEST);

        groupAddRequestRepository.save(
                GroupAddRequest.builder()
                        .user(customer)
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



        Customer requester = groupAddRequest.getUser();

        if (changeStatus == RequestStatus.ACCEPT) {
            Long requesterId = groupAddRequest.getUser().getCustomerId();
            if (!groupMemberRepository.existsMember(groupId, requesterId)) {
                groupMemberRepository.save(GroupMember.builder()
                        .group(group)
                        .leader(false)
                        .user(requester)
                        .build());
            }
        }

        boolean accepted = (changeStatus == RequestStatus.ACCEPT);
        final Long requesterId = groupAddRequest.getUser().getCustomerId();

        afterCommit(() -> notificationService.sendToCustomer(
                requesterId,
                accepted ? NotificationType.GROUP_JOIN_ACCEPTED : NotificationType.GROUP_JOIN_REJECTED,
                accepted ? "가입이 승인되었습니다." : "가입이 거절되었습니다."));

        return new AddRequestResponseDto(
            groupAddRequest.getGroupAddRequestId(), groupAddRequest.getUser().getName(),
                groupAddRequest.getRequestStatus()
        );
    }

    @Transactional
    public GroupResponseDto createGroupMember(Long groupId, Long userId, GroupEntranceRequestDto requestDto) {
        Group group = validGroup(groupId);

        boolean isGroupMember = groupMemberRepository
                .existsMember(groupId, userId);

        WalletResponseDto groupWallet = walletService.getGroupWallet(group);

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

        Customer user = validCustomer(userId);

        List<Long> memberIdsToNotify = groupMemberRepository.findMemberIdsByGroupId(groupId);

        groupMemberRepository.save(
                GroupMember.builder()
                        .group(group)
                        .leader(false)
                        .user(user)
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

        final Long oldLeaderId = originGroupLeader.getUser().getCustomerId();
        final Long newLeaderId = newGroupLeader.getUser().getCustomerId();

        afterCommit(() -> {
            notificationService.sendToCustomer(
                    oldLeaderId, NotificationType.GROUP_LEADER_CHANGED, "리더 권한이 해제되었습니다.");
            notificationService.sendToCustomer(
                    newLeaderId, NotificationType.GROUP_LEADER_CHANGED, "새 리더로 지정되었습니다.");
        });

        return new GroupLeaderChangeResponseDto(
                groupId, newGroupLeader.getGroupMemberId(), newGroupLeader.getUser().getName()
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
        String targetName = target.getUser().getName();
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

    @Transactional
    public GroupDisbandResponseDto disbandGroup(Long groupId, Long leaderId) {
        validGroup(groupId);
        GroupMember me = validGroupMember(groupId, leaderId);
        if (!me.isLeader()) throw new CustomException(ErrorCode.ONLY_GROUP_LEADER);

        Wallet gw = validGroupWallet(groupId);
        Long walletId = gw.getWalletId();
        List<Long> memberIds = groupMemberRepository.findMemberIdsByGroupId(groupId);

        Map<Long, Long> refundedByMember = walletService.settleAllMembersShare(groupId, memberIds); // 변경
        long totalRefunded = refundedByMember.values().stream().mapToLong(Long::longValue).sum();

        long remain = balanceRepository.sumByWalletIdForUpdate(gw.getWalletId()).orElse(0L);
        if (remain != 0L) throw new CustomException(ErrorCode.INCONSISTENT_STATE);
        if (lotRepository.existsActiveLotByWalletId(gw.getWalletId()))
            throw new CustomException(ErrorCode.INCONSISTENT_STATE);

        lotRepository.deleteByWalletId(gw.getWalletId());
        balanceRepository.deleteByWalletId(gw.getWalletId());
        groupMemberRepository.deleteByGroupId(groupId);
        walletRepository.deleteById(walletId);

        entityManager.flush();
        entityManager.clear(); // 1차 캐시 비우기

        Group group = groupRepository.getReferenceById(groupId);
        groupRepository.delete(group);

        afterCommit(() -> refundedByMember.forEach((cid, amt) ->
                notificationService.sendToCustomer(
                        cid, NotificationType.GROUP_DISBANDED,
                        "모임 해체. 환급 " + amt + "P 완료.")));

        return new GroupDisbandResponseDto(
                groupId, memberIds.size(), totalRefunded, refundedByMember, LocalDateTime.now());
    }


    /**
     * ===============validate method=============
     *  Group, GroupMember, Customer, GroupWallet 검증
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

    public Customer validCustomer(Long customerId) {
        return customerRepository.findById(customerId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
    }

    public Wallet validGroupWallet(Long groupId) {
        return walletRepository.findByGroupId(groupId)
                .orElseThrow(() -> new CustomException(ErrorCode.WALLET_NOT_FOUND));
    }
}
