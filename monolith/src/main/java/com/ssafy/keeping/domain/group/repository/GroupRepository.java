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

    @Query("""
    select new com.ssafy.keeping.domain.group.dto.GroupMaskingResponseDto(
        g.groupId,
        g.groupName,
        g.groupDescription,
        case
        when length(c.name) = 1
            then '*'
        when length(c.name) = 2
            then concat(substring(c.name, 1, 1), '*')
        else concat(substring(c.name, 1, 1), repeat('*', length(c.name) - 2), substring(c.name, length(c.name), 1))
        end
    )
    from Group g
    join GroupMember gm on gm.group = g
    join Customer c on gm.user = c
    where g.groupName = :name
    and gm.leader = true
    """)
    List<GroupMaskingResponseDto> findGroupsByName(@Param("name") String name);
}
