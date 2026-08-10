package com.pulseguard.controlapi.repository;

import com.pulseguard.controlapi.domain.Project;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ProjectRepository extends JpaRepository<Project, Long> {

    /**
     * Projects the given user belongs to.
     *
     * <p>Joins through membership and fetches the creator in one query, so
     * rendering the list does not trigger a lookup per row.
     */
    @Query("""
            select distinct p from Project p
            join fetch p.createdBy
            join ProjectMember m on m.project = p
            where m.user.id = :userId
            """)
    List<Project> findAllForMember(Long userId);

    /** Every project, for system administrators. */
    @Query("select p from Project p join fetch p.createdBy")
    List<Project> findAllWithCreator();
}
