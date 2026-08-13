package com.pulseguard.notification.recipient;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The recipient query, asserted as SQL.
 *
 * <p>This is a deliberate compromise. Running the query needs MySQL, which the
 * normal build does not have, so what is checked here is that the statement
 * still says the things the rules depend on. It proves the predicate is
 * present, not that the database honours it — that part is covered by manual
 * verification against a real schema.
 */
class ProjectRecipientRepositoryTest {

    private final String sql = ProjectRecipientRepository.ENABLED_MEMBERS_SQL;

    /** A deactivated account must stop receiving mail. */
    @Test
    void theQueryExcludesDisabledUsers() {
        assertThat(sql).contains("u.enabled = true");
    }

    /** Membership is what earns a notification, not a system-wide role. */
    @Test
    void theQuerySelectsProjectMembersOnly() {
        assertThat(sql).contains("from project_members pm");
        assertThat(sql).contains("join users u on u.id = pm.user_id");
        assertThat(sql).contains("pm.project_id = :projectId");
    }

    /** Every member of the project, whatever their role. */
    @Test
    void theQueryDoesNotFilterByProjectRole() {
        assertThat(sql).doesNotContain("pm.role");
        assertThat(sql).doesNotContain("PROJECT_ADMIN");
        assertThat(sql).doesNotContain("VIEWER");
    }

    /** Only what an email needs; no password hashes, no tokens. */
    @Test
    void theQueryReadsOnlyTheColumnsAnEmailNeeds() {
        assertThat(sql).contains("u.email").contains("u.display_name");
        assertThat(sql).doesNotContain("password");
    }
}
