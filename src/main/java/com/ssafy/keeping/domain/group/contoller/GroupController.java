package com.ssafy.keeping.domain.group.contoller;

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
            @AuthenticationPrincipal Long customerId,
            @Valid @RequestBody GroupRequestDto requestDto
    ) {
        GroupResponseDto dto = groupService.createGroup(customerId, requestDto);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success( "모임이 생성되었습니다.", HttpStatus.CREATED.value(), dto));
    }

    @GetMapping()
    public ResponseEntity<ApiResponse<List<GroupMaskingResponseDto>>> getSearchGroup(
            @AuthenticationPrincipal Long customerId,
            @RequestParam String name
    ) {
        List<GroupMaskingResponseDto> dtos = groupService.getSearchGroup(customerId, name);
        String message = dtos.size() == 0 ?
                "해당 이름으로 조회되는 모임이 존재하지 않습니다." : "해당 모임이 조회되었습니다.";
        return ResponseEntity.ok(ApiResponse.success(message, HttpStatus.OK.value(), dtos));
    }

    @GetMapping("/{groupId}")
    public ResponseEntity<ApiResponse<GroupResponseDto>> getGroup(
            @AuthenticationPrincipal Long customerId,
            @PathVariable Long groupId
    ) {
        GroupResponseDto dto = groupService.getGroup(groupId, customerId);
        return ResponseEntity.ok(ApiResponse.success("해당 모임이 조회되었습니다.", HttpStatus.OK.value(), dto));
    }

    @PatchMapping("/{groupId}")
    public ResponseEntity<ApiResponse<GroupResponseDto>> editGroup(
            @AuthenticationPrincipal Long customerId,
            @PathVariable Long groupId,
            @Valid @RequestBody GroupEditRequestDto requestDto
    ) {
        GroupResponseDto dto = groupService.editGroup(groupId, customerId, requestDto);
        return ResponseEntity.ok(ApiResponse.success("해당 모임이 수정되었습니다.", HttpStatus.OK.value(), dto));
    }

    @GetMapping("/{groupId}/group-members")
    public ResponseEntity<ApiResponse<List<GroupMemberResponseDto>>> getGroupMembers(
            @AuthenticationPrincipal Long customerId,
            @PathVariable Long groupId
    ) {
        List<GroupMemberResponseDto> dtos = groupService.getGroupMembers(groupId, customerId);
        return ResponseEntity.ok(ApiResponse.success("해당 모임의 모임원들이 조회되었습니다.", HttpStatus.OK.value(), dtos));
    }

    @PostMapping("/{groupId}/add-requests")
    public ResponseEntity<ApiResponse<Void>> createGroupAddRequest(
            @AuthenticationPrincipal Long customerId,
            @PathVariable Long groupId
    ) {
        groupService.createGroupAddRequest(groupId, customerId);
        return ResponseEntity.ok(ApiResponse.success("해당 모임에 추가 신청을 완료했습니다.", HttpStatus.OK.value(), null));
    }

    @GetMapping("/{groupId}/add-requests")
    public ResponseEntity<ApiResponse<List<AddRequestResponseDto>>> getAllGroupAddRequest(
            @AuthenticationPrincipal Long customerId,
            @PathVariable Long groupId
    ) {
        List<AddRequestResponseDto> dtos = groupService.getAllGroupAddRequest(groupId, customerId);
        return ResponseEntity.ok(ApiResponse.success("모임 신청 내역을 조회했습니다.", HttpStatus.OK.value(), dtos));
    }

    @PatchMapping("/{groupId}/add-requests")
    public ResponseEntity<ApiResponse<AddRequestResponseDto>> updateAddRequestStatus(
            @AuthenticationPrincipal Long customerId,
            @PathVariable Long groupId,
            @Valid @RequestBody AddRequestDecisionDto request
    ) {
        AddRequestResponseDto dto = groupService.updateAddRequestStatus(groupId, customerId, request);
        String message = String.format("모임 추가 신청 %s 성공", dto.status() == RequestStatus.ACCEPT ? "승인" : "거절");
        return ResponseEntity.ok(ApiResponse.success(message, HttpStatus.OK.value(), dto));
    }

    @PostMapping("/{groupId}/entrance")
    public ResponseEntity<ApiResponse<GroupResponseDto>> createGroupMember(
            @AuthenticationPrincipal Long customerId,
            @PathVariable Long groupId,
            @Valid @RequestBody GroupEntranceRequestDto requestDto
    ) {
        GroupResponseDto dto = groupService.createGroupMember(groupId, customerId, requestDto);
        return ResponseEntity.ok(ApiResponse.success("해당 모임에 입장을 완료했습니다.", HttpStatus.OK.value(), dto));
    }

    @PatchMapping("/{groupId}/group-leader")
    public ResponseEntity<ApiResponse<GroupLeaderChangeResponseDto>> changeGroupLeader(
            @AuthenticationPrincipal Long customerId,
            @PathVariable Long groupId,
            @Valid @RequestBody GroupLeaderChangeRequestDto requestDto
    ) {
        GroupLeaderChangeResponseDto dto = groupService.changeGroupLeader(groupId, customerId, requestDto);
        return ResponseEntity.ok(ApiResponse.success("모임장 위임에 성공했습니다.", HttpStatus.OK.value(), dto));
    }

    // 모임원 내보내기
    @PostMapping("/{groupId}/group-member")
    public ResponseEntity<ApiResponse<Void>> expelMember(
            @AuthenticationPrincipal Long leaderId,
            @PathVariable Long groupId,
            @Valid @RequestBody GroupExpelRequestDto req
    ) {
        groupService.expelMember(groupId, leaderId, req.getTargetCustomerId());
        return ResponseEntity.ok(ApiResponse.success("모임원을 내보냈습니다.", 200, null));
    }

    @DeleteMapping("/{groupId}/group-member")
    public ResponseEntity<ApiResponse<GroupLeaveResponseDto>> leaveGroup(
            @PathVariable Long groupId,
            @AuthenticationPrincipal Long customerId
    ) {
        GroupLeaveResponseDto dto =groupService.leaveGroup(groupId, customerId);
        return ResponseEntity.ok(ApiResponse.success("모임을 탈퇴했습니다.", 200, dto));
    }

    /**
     * 모임 해체: 리더만
     * 정책: Saga 패턴으로 비동기 처리
     * - 즉시 응답 후 백그라운드에서 환급 처리
     * - 완료 시 각 멤버에게 알림 전송
     */
    @DeleteMapping("/{groupId}")
    public ResponseEntity<ApiResponse<GroupDisbandResponseDto>> disbandGroup(
            @PathVariable Long groupId,
            @AuthenticationPrincipal Long leaderId
    ) {
        GroupDisbandResponseDto dto = groupService.initiateDisbandGroupAsync(groupId, leaderId);
        return ResponseEntity.accepted()
                .body(ApiResponse.success("모임 해체가 시작되었습니다. 환급은 잠시 후 완료됩니다.", 202, dto));
    }
}
