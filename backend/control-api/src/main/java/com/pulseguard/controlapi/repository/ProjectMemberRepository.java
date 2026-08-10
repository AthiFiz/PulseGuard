package com.pulseguard.controlapi.repository;

import com.pulseguard.controlapi.domain.ProjectMember;
import com.pulseguard.controlapi.enums.ProjectRole;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ProjectMemberRepository extends JpaRepository<ProjectMember, Long> {

    Optional<ProjectMember> findByProjectIdAndUserId(Long projectId, Long userId);

    boolean existsByProjectIdAndUserId(Long projectId, Long userId);

    /** Fetches the user eagerly; the members list always renders user details. */
    @Query("select m from ProjectMember m join fetch m.user where m.project.id = :projectId")
    List<ProjectMember> findAllByProjectIdWithUser(Long projectId);

    /** Used for the last-admin rule. */
    long countByProjectIdAndRole(Long projectId, ProjectRole role);

    /**
     * Scoped by project so a membership id belonging to another project cannot
     * be manipulated through the wrong path.
     */
    Optional<ProjectMember> findByIdAndProjectId(Long id, Long projectId);

    /**
     * Removes a project's memberships before the project itself is deleted.
     *
     * <p>The database would cascade these away on its own, but Hibernate does
     * not know that: any membership already loaded in the persistence context
     * would still be flushed, pointing at a parent that no longer exists.
     * Deleting them through the repository keeps the in-memory state honest.
     */
    void deleteAllByProjectId(Long projectId);
}
