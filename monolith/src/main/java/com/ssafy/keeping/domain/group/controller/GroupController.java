package com.ssafy.keeping.domain.group.controller;

import com.ssafy.keeping.domain.auth.security.principal.UserPrincipal;
import com.ssafy.keeping.domain.group.constant.RequestStatus;
import com.ssafy.keeping.domain.group.dto.*;
import com.ssafy.keeping.domain.group.service.GroupService;
import com.ssafy.keeping.global.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/groups")
@RequiredArgsConstructor
public class GroupController {
    private final GroupService groupService;

    @PostMapping()
    public ResponseEntity<ApiResponse<GroupResponseDto>> createGroup(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody GroupRequestDto requestDto
    ) {
        Long customerId = principal.id();
        GroupResponseDto dto = groupService.createGroup(customerId, requestDto);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success( "모임이 생성되었습니다.", HttpStatus.CREATED.value(), dto));
    }

    @GetMapping()
    public ResponseEntity<ApiResponse<List<GroupMaskingResponseDto>>> getSearchGroup(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam String name
    ) {
        Long customerId = principal.id();
        List<GroupMaskingResponseDto> dtos = groupService.getSearchGroup(customerId, name);
        String message = dtos.size() == 0 ?
                "해당 이름으로 조회되는 모임이 존재하지 않습니다." : "해당 모임이 조회되었습니다.";
        return ResponseEntity.ok(ApiResponse.success(message, HttpStatus.OK.value(), dtos));
    }

    @GetMapping("/{groupId}")
    public ResponseEntity<ApiResponse<GroupResponseDto>> getGroup(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long groupId
    ) {
        Long customerId = principal.id();
        GroupResponseDto dto = groupService.getGroup(groupId, customerId);
        return ResponseEntity.ok(ApiResponse.success("해당 모임이 조회되었습니다.", HttpStatus.OK.value(), dto));
    }

    @PatchMapping("/{groupId}")
    public ResponseEntity<ApiResponse<GroupResponseDto>> editGroup(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long groupId,
            @Valid @RequestBody GroupEditRequestDto requestDto
    ) {
        Long customerId = principal.id();
        GroupResponseDto dto = groupService.editGroup(groupId, customerId, requestDto);
        return ResponseEntity.ok(ApiResponse.success("해당 모임이 수정되었습니다.", HttpStatus.OK.value(), dto));
    }

    @GetMapping("/{groupId}/group-members")
    public ResponseEntity<ApiResponse<List<GroupMemberResponseDto>>> getGroupMembers(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long groupId
    ) {
        Long customerId = principal.id();
        List<GroupMemberResponseDto> dtos = groupService.getGroupMembers(groupId, customerId);
        return ResponseEntity.ok(ApiResponse.success("해당 모임의 모임원들이 조회되었습니다.", HttpStatus.OK.value(), dtos));
    }

    @PostMapping("/{groupId}/add-requests")
    public ResponseEntity<ApiResponse<Void>> createGroupAddRequest(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long groupId
    ) {
        Long customerId = principal.id();
        groupService.createGroupAddRequest(groupId, customerId);
        return ResponseEntity.ok(ApiResponse.success("해당 모임에 추가 신청을 완료했습니다.", HttpStatus.OK.value(), null));
    }

    @GetMapping("/{groupId}/add-requests")
    public ResponseEntity<ApiResponse<List<AddRequestResponseDto>>> getAllGroupAddRequest(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long groupId
    ) {
        Long customerId = principal.id();
        List<AddRequestResponseDto> dtos = groupService.getAllGroupAddRequest(groupId, customerId);
        return ResponseEntity.ok(ApiResponse.success("모임 신청 내역을 조회했습니다.", HttpStatus.OK.value(), dtos));
    }

    @PatchMapping("/{groupId}/add-requests")
    public ResponseEntity<ApiResponse<AddRequestResponseDto>> updateAddRequestStatus(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long groupId,
            @Valid @RequestBody AddRequestDecisionDto request
    ) {
        Long customerId = principal.id();
        AddRequestResponseDto dto = groupService.updateAddRequestStatus(groupId, customerId, request);
        String message = String.format("모임 추가 신청 %s 성공", dto.status() == RequestStatus.ACCEPT ? "승인" : "거절");
        return ResponseEntity.ok(ApiResponse.success(message, HttpStatus.OK.value(), dto));
    }

    @PostMapping("/{groupId}/entrance")
    public ResponseEntity<ApiResponse<GroupResponseDto>> createGroupMember(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long groupId,
            @Valid @RequestBody GroupEntranceRequestDto requestDto
    ) {
        Long customerId = principal.id();
        GroupResponseDto dto = groupService.createGroupMember(groupId, customerId, requestDto);
        return ResponseEntity.ok(ApiResponse.success("해당 모임에 입장을 완료했습니다.", HttpStatus.OK.value(), dto));
    }

    @PatchMapping("/{groupId}/group-leader")
    public ResponseEntity<ApiResponse<GroupLeaderChangeResponseDto>> changeGroupLeader(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long groupId,
            @Valid @RequestBody GroupLeaderChangeRequestDto requestDto
    ) {
        Long customerId = principal.id();
        GroupLeaderChangeResponseDto dto = groupService.changeGroupLeader(groupId, customerId, requestDto);
        return ResponseEntity.ok(ApiResponse.success("모임장 위임에 성공했습니다.", HttpStatus.OK.value(), dto));
    }

    // 모임원 내보내기
    @PostMapping("/{groupId}/group-member")
    public ResponseEntity<ApiResponse<Void>> expelMember(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long groupId,
            @Valid @RequestBody GroupExpelRequestDto req
    ) {
        Long leaderId = principal.id();
        groupService.expelMember(groupId, leaderId, req.getTargetCustomerId());
        return ResponseEntity.ok(ApiResponse.success("모임원을 내보냈습니다.", 200, null));
    }

    @DeleteMapping("/{groupId}/group-member")
    public ResponseEntity<ApiResponse<GroupLeaveResponseDto>> leaveGroup(
            @PathVariable Long groupId,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        Long customerId = principal.id();
        GroupLeaveResponseDto dto =groupService.leaveGroup(groupId, customerId);
        return ResponseEntity.ok(ApiResponse.success("모임을 탈퇴했습니다.", 200, dto));
    }

    /**
     * 모임 해체: 리더만
     * 정책: 자동 환급 → 멤버 정리 → 지갑/그룹 삭제
     */
    @DeleteMapping("/{groupId}")
    public ResponseEntity<ApiResponse<GroupDisbandResponseDto>> disbandGroup(
            @PathVariable Long groupId,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        Long leaderId = principal.id();
        GroupDisbandResponseDto dto = groupService.disbandGroup(groupId, leaderId);
        return ResponseEntity.ok(ApiResponse.success("모임을 해체했습니다.", 200, dto));
    }
}
