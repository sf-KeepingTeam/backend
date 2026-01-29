package com.ssafy.keeping.domain.group.repository;

import com.ssafy.keeping.domain.group.dto.GroupMaskingResponseDto;
import com.ssafy.keeping.domain.group.model.Group;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface GroupRepository extends JpaRepository<Group, Long> {

    @Query(
    """
    select g.groupCode
    from Group g
    where g.groupId=:groupId
    """
    )
    String findGroupCodeById(@Param("groupId") Long groupId);

    /**
     * Step 6: 크로스-도메인 JOIN 제거
     * 변경 전: join Customer c on gm.user = c → c.name 사용
     * 변경 후: gm.customerNameSnapshot 사용 (Pattern 3)
     */
    @Query("""
    select new com.ssafy.keeping.domain.group.dto.GroupMaskingResponseDto(
        g.groupId,
        g.groupName,
        g.groupDescription,
        case
        when length(gm.customerNameSnapshot) = 1
            then '*'
        when length(gm.customerNameSnapshot) = 2
            then concat(substring(gm.customerNameSnapshot, 1, 1), '*')
        when gm.customerNameSnapshot is null
            then '***'
        else concat(substring(gm.customerNameSnapshot, 1, 1), repeat('*', length(gm.customerNameSnapshot) - 2), substring(gm.customerNameSnapshot, length(gm.customerNameSnapshot), 1))
        end
    )
    from Group g
    join GroupMember gm on gm.group = g
    where g.groupName = :name
    and gm.leader = true
    """)
    List<GroupMaskingResponseDto> findGroupsByName(@Param("name") String name);
}
