package com.pulseguard.notification.recipient;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Who to notify about a project's incidents.
 *
 * <p>Deliberately plain JDBC rather than JPA. The Notification Service does not
 * own users, projects or memberships — it needs two columns from a join it will
 * never write to. Mapping the whole project-management domain here would create
 * a second definition of those entities in a second application, and the first
 * schema change would have to be made in two places.
 *
 * <p>Everyone in the project is notified: PROJECT_ADMIN and VIEWER alike. There
 * are no notification preferences yet, and a system administrator who is not a
 * member is not a recipient — they can see every project, which is not the same
 * as wanting every project's email.
 */
@Repository
@RequiredArgsConstructor
public class ProjectRecipientRepository {

    /**
     * Visible for testing: the {@code enabled} predicate is the reason a
     * deactivated account stops receiving mail, and it is worth asserting on
     * without needing a database.
     */
    static final String ENABLED_MEMBERS_SQL =
            """
            select u.email as email, u.display_name as displayName
            from project_members pm
            join users u on u.id = pm.user_id
            where pm.project_id = :projectId
              and u.enabled = true
            order by u.email
            """;

    private final JdbcClient jdbcClient;

    public List<Recipient> findEnabledMembers(Long projectId) {
        return jdbcClient
                .sql(ENABLED_MEMBERS_SQL)
                .param("projectId", projectId)
                .query(Recipient.class)
                .list();
    }
}
