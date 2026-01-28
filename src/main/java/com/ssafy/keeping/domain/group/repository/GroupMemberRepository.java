package com.ssafy.keeping.domain.group.repository;

import com.ssafy.keeping.domain.group.dto.GroupMemberResponseDto;
import com.ssafy.keeping.domain.group.model.GroupMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GroupMemberRepository extends JpaRepository<GroupMember, Long> {

    @Query("""
        select count(gm) > 0
        from GroupMember gm
        where gm.group.groupId = :groupId
          and gm.customerId  = :userId
    """)
    boolean existsMember(@Param("groupId") Long groupId, @Param("userId") Long userId);

    @Query("""
        select count(gm) > 0
        from GroupMember gm
        where gm.group.groupId = :groupId
          and gm.customerId  = :userId
          and gm.leader = true
    """)
    boolean existsLeader(@Param("groupId") Long groupId, @Param("userId") Long userId);

    /**
     * Pattern 3: customerNameSnapshot 사용
     * MSA 전환 시 Customer 도메인 조회 없이 멤버 목록 반환
     */
    @Query("""
        select new com.ssafy.keeping.domain.group.dto.GroupMemberResponseDto(
            :groupId, gm.customerId, gm.customerNameSnapshot, gm.leader, gm.groupMemberId
        )
        from GroupMember gm
        where gm.group.groupId = :groupId
    """)
    List<GroupMemberResponseDto> findAllGroupMembers(@Param("groupId") Long groupId);

    @Query("""
        select gm
        from GroupMember gm
        where gm.group.groupId = :groupId
          and gm.customerId  = :userId
    """)
    Optional<GroupMember> findGroupMember(@Param("groupId") Long groupId, @Param("userId") Long userId);

    @Query("""
        select gm.customerId
        from GroupMember gm
        where gm.group.groupId = :groupId
    """)
    List<Long> findMemberIdsByGroupId(@Param("groupId") Long groupId);

    @Query("""
        select gm.customerId
        from GroupMember gm
        where gm.group.groupId = :groupId
        and gm.leader = true
    """)
    Optional<Long> findLeaderId(@Param("groupId") Long groupId);

    @Modifying
    @Query("""
        delete from GroupMember gm
        where gm.group.groupId = :groupId
    """)
    void deleteByGroupId(@Param("groupId") Long groupId);

    @Query("""
       select gm.group.groupId
       from GroupMember gm
       where gm.customerId = :customerId
    """)
    List<Long> findGroupIdsByCustomerId(@Param("customerId") Long customerId);

    @Query("""
        select gm.group.groupId
        from GroupMember gm
        where gm.customerId = :customerId
    """)
    List<Long> findMemberGroupsByCustomerId(@Param("customerId") Long customerId);

    List<GroupMember> findAllByGroup_GroupId(Long groupId);
}
