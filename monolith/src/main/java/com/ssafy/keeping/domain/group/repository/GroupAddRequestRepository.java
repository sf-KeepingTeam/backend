package com.ssafy.keeping.domain.group.repository;

import com.ssafy.keeping.domain.group.constant.RequestStatus;
import com.ssafy.keeping.domain.group.dto.AddRequestResponseDto;
import com.ssafy.keeping.domain.group.model.GroupAddRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface GroupAddRequestRepository extends JpaRepository<GroupAddRequest, Long> {

    @Query("""
    select count(gr) > 0
    from GroupAddRequest gr
    where gr.group.groupId = :groupId
      and gr.user.customerId  = :userId
      and gr.requestStatus = :status
    """)
    boolean existsRequest(@Param("groupId") Long groupId, @Param("userId") Long userId,
                          @Param("status") RequestStatus status);

    @Query("""
    select new com.ssafy.keeping.domain.group.dto.AddRequestResponseDto(
        gr.groupAddRequestId, gr.user.name, gr.requestStatus
    )
    from GroupAddRequest gr
    where gr.group.groupId = :groupId
      and gr.requestStatus = :status
    """)
    List<AddRequestResponseDto> findAllAddRequestInPending(@Param("groupId") Long groupId,
                                                           @Param("status") RequestStatus status);
}
