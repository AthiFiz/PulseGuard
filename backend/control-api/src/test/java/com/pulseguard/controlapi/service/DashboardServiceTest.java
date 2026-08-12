package com.pulseguard.controlapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pulseguard.controlapi.domain.Monitor;
import com.pulseguard.controlapi.domain.MonitorCheck;
import com.pulseguard.controlapi.domain.Project;
import com.pulseguard.controlapi.domain.User;
import com.pulseguard.controlapi.dto.monitoring.ProjectDashboardResponse;
import com.pulseguard.controlapi.enums.MonitorCheckErrorType;
import com.pulseguard.controlapi.enums.MonitorCheckOutcome;
import com.pulseguard.controlapi.enums.MonitorStatus;
import com.pulseguard.controlapi.exception.ApiErrorCode;
import com.pulseguard.controlapi.exception.ApiException;
import com.pulseguard.controlapi.repository.MonitorCheckRepository;
import com.pulseguard.controlapi.repository.MonitorRepository;
import com.pulseguard.controlapi.repository.projection.MonitorStatusCountProjection;
import com.pulseguard.controlapi.repository.projection.ProjectCheckStatisticsProjection;
import com.pulseguard.controlapi.service.impl.DashboardServiceImpl;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Limit;
import org.springframework.test.util.ReflectionTestUtils;

/** The project dashboard, with time pinned so the default window is exact. */
@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {

    private static final Long PROJECT_ID = 10L;
    private static final Instant NOW = Instant.parse("2026-08-12T08:30:00Z");

    @Mock
    private MonitorRepository monitorRepository;

    @Mock
    private MonitorCheckRepository monitorCheckRepository;

    @Mock
    private ProjectAccessService projectAccessService;

    private DashboardServiceImpl dashboardService;

    @BeforeEach
    void setUp() {
        dashboardService = new DashboardServiceImpl(
                monitorRepository,
                monitorCheckRepository,
                projectAccessService,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    // ---------------------------------------------------------- time window

    /**
     * The dashboard answers "how are things now", so with no range it looks back
     * 24 hours — unlike monitor statistics, where no range means all history.
     */
    @Test
    void theDefaultWindowIsTheLastTwentyFourHours() {
        givenEmptyProject();

        ProjectDashboardResponse dashboard = dashboardService.getProjectDashboard(PROJECT_ID, null, null);

        assertThat(dashboard.window().to()).isEqualTo(NOW);
        assertThat(dashboard.window().from()).isEqualTo(NOW.minus(Duration.ofHours(24)));
        assertThat(dashboard.generatedAt()).isEqualTo(NOW);
    }

    @Test
    void aCustomWindowIsUsedAsGiven() {
        givenEmptyProject();
        Instant from = Instant.parse("2026-08-01T00:00:00Z");
        Instant to = Instant.parse("2026-08-05T00:00:00Z");

        ProjectDashboardResponse dashboard = dashboardService.getProjectDashboard(PROJECT_ID, from, to);

        assertThat(dashboard.window().from()).isEqualTo(from);
        assertThat(dashboard.window().to()).isEqualTo(to);
    }

    @Test
    void onlyAFromMeansUpToNow() {
        givenEmptyProject();
        Instant from = Instant.parse("2026-08-01T00:00:00Z");

        ProjectDashboardResponse dashboard = dashboardService.getProjectDashboard(PROJECT_ID, from, null);

        assertThat(dashboard.window().from()).isEqualTo(from);
        assertThat(dashboard.window().to()).isEqualTo(NOW);
    }

    @Test
    void onlyAToMeansTheTwentyFourHoursBeforeIt() {
        givenEmptyProject();
        Instant to = Instant.parse("2026-08-05T00:00:00Z");

        ProjectDashboardResponse dashboard = dashboardService.getProjectDashboard(PROJECT_ID, null, to);

        assertThat(dashboard.window().to()).isEqualTo(to);
        assertThat(dashboard.window().from()).isEqualTo(to.minus(Duration.ofHours(24)));
    }

    @Test
    void aBackwardsRangeIsRejected() {
        when(projectAccessService.requireReadableProject(PROJECT_ID)).thenReturn(project());

        assertThatThrownBy(() -> dashboardService.getProjectDashboard(
                        PROJECT_ID,
                        Instant.parse("2026-08-05T00:00:00Z"),
                        Instant.parse("2026-08-01T00:00:00Z")))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.MONITORING_QUERY_INVALID);
    }

    // -------------------------------------------------------- status counts

    @Test
    void statusCountsAreMappedAndTotalled() {
        givenAccessibleProject();
        givenStatusCounts(
                statusCount(MonitorStatus.UP, 7),
                statusCount(MonitorStatus.DOWN, 1),
                statusCount(MonitorStatus.UNKNOWN, 1),
                statusCount(MonitorStatus.PAUSED, 1));
        givenCheckAggregate(0L, 0L, null);
        givenRecentFailures(List.of());

        ProjectDashboardResponse dashboard = dashboardService.getProjectDashboard(PROJECT_ID, null, null);

        assertThat(dashboard.monitors().up()).isEqualTo(7);
        assertThat(dashboard.monitors().down()).isEqualTo(1);
        assertThat(dashboard.monitors().unknown()).isEqualTo(1);
        assertThat(dashboard.monitors().paused()).isEqualTo(1);
        assertThat(dashboard.monitors().total()).isEqualTo(10);
    }

    /** Statuses with no monitors come back as no row at all, not as a zero. */
    @Test
    void absentStatusesCountAsZero() {
        givenAccessibleProject();
        givenStatusCounts(statusCount(MonitorStatus.UP, 3));
        givenCheckAggregate(0L, 0L, null);
        givenRecentFailures(List.of());

        ProjectDashboardResponse dashboard = dashboardService.getProjectDashboard(PROJECT_ID, null, null);

        assertThat(dashboard.monitors().up()).isEqualTo(3);
        assertThat(dashboard.monitors().down()).isZero();
        assertThat(dashboard.monitors().unknown()).isZero();
        assertThat(dashboard.monitors().paused()).isZero();
        assertThat(dashboard.monitors().total()).isEqualTo(3);
    }

    // ------------------------------------------------------------- statistics

    @Test
    void checkStatisticsAreAggregatedAcrossTheProject() {
        givenAccessibleProject();
        givenStatusCounts(statusCount(MonitorStatus.UP, 10));
        givenCheckAggregate(1430L, 1400L, 132.5);
        givenRecentFailures(List.of());

        ProjectDashboardResponse dashboard = dashboardService.getProjectDashboard(PROJECT_ID, null, null);

        assertThat(dashboard.checks().total()).isEqualTo(1430);
        assertThat(dashboard.checks().successful()).isEqualTo(1400);
        assertThat(dashboard.checks().failed()).isEqualTo(30);
        assertThat(dashboard.checks().uptimePercentage()).isEqualByComparingTo("97.90");
        assertThat(dashboard.checks().averageResponseTimeMs()).isEqualByComparingTo("132.50");
    }

    /**
     * The important one. Project uptime must come from the aggregate check
     * counts, never from averaging each monitor's own percentage.
     *
     * <p>Monitor A: 1000 successes out of 1000. Monitor B: 0 out of 1.
     * By check count that is 1000/1001 = 99.90%. Averaging the two percentages
     * would give (100 + 0) / 2 = 50%, which describes nothing real — one failed
     * check would appear to halve the project's availability.
     */
    @Test
    void projectUptimeUsesCheckCountsNotTheAverageOfMonitorPercentages() {
        givenAccessibleProject();
        givenStatusCounts(statusCount(MonitorStatus.UP, 1), statusCount(MonitorStatus.DOWN, 1));
        givenCheckAggregate(1001L, 1000L, 100.0);
        givenRecentFailures(List.of());

        ProjectDashboardResponse dashboard = dashboardService.getProjectDashboard(PROJECT_ID, null, null);

        assertThat(dashboard.checks().uptimePercentage()).isEqualByComparingTo("99.90");
        assertThat(dashboard.checks().uptimePercentage()).isNotEqualByComparingTo("50.00");
    }

    @Test
    void aProjectWithNoMonitorsReturnsZeroesAndNulls() {
        givenEmptyProject();

        ProjectDashboardResponse dashboard = dashboardService.getProjectDashboard(PROJECT_ID, null, null);

        assertThat(dashboard.monitors().total()).isZero();
        assertThat(dashboard.checks().total()).isZero();
        assertThat(dashboard.checks().uptimePercentage()).isNull();
        assertThat(dashboard.checks().averageResponseTimeMs()).isNull();
        assertThat(dashboard.recentFailures()).isEmpty();
    }

    /** Monitors exist but the worker has not checked them yet. */
    @Test
    void monitorsWithNoChecksLeaveUptimeUnknownRatherThanZero() {
        givenAccessibleProject();
        givenStatusCounts(statusCount(MonitorStatus.UNKNOWN, 2), statusCount(MonitorStatus.PAUSED, 1));
        givenCheckAggregate(0L, 0L, null);
        givenRecentFailures(List.of());

        ProjectDashboardResponse dashboard = dashboardService.getProjectDashboard(PROJECT_ID, null, null);

        assertThat(dashboard.monitors().total()).isEqualTo(3);
        assertThat(dashboard.checks().total()).isZero();
        // UNKNOWN monitors are not failures.
        assertThat(dashboard.checks().failed()).isZero();
        assertThat(dashboard.checks().uptimePercentage()).isNull();
    }

    // -------------------------------------------------------- recent failures

    @Test
    void recentFailuresAreMappedWithTheirMonitor() {
        givenAccessibleProject();
        givenStatusCounts(statusCount(MonitorStatus.DOWN, 1));
        givenCheckAggregate(10L, 5L, 100.0);
        givenRecentFailures(List.of(failedCheck()));

        ProjectDashboardResponse dashboard = dashboardService.getProjectDashboard(PROJECT_ID, null, null);

        assertThat(dashboard.recentFailures()).hasSize(1);
        assertThat(dashboard.recentFailures().getFirst().monitorId()).isEqualTo(25L);
        assertThat(dashboard.recentFailures().getFirst().monitorName()).isEqualTo("Payment API");
        assertThat(dashboard.recentFailures().getFirst().httpStatusCode()).isEqualTo(500);
        assertThat(dashboard.recentFailures().getFirst().errorType())
                .isEqualTo(MonitorCheckErrorType.UNEXPECTED_STATUS);
    }

    @Test
    void atMostTenRecentFailuresAreRequested() {
        givenEmptyProject();

        dashboardService.getProjectDashboard(PROJECT_ID, null, null);

        ArgumentCaptor<Limit> limit = ArgumentCaptor.forClass(Limit.class);
        verify(monitorCheckRepository)
                .findRecentFailures(any(), any(), any(), any(), limit.capture());
        assertThat(limit.getValue().max()).isEqualTo(10);
    }

    @Test
    void recentFailuresAreScopedToTheWindow() {
        givenEmptyProject();

        dashboardService.getProjectDashboard(PROJECT_ID, null, null);

        verify(monitorCheckRepository)
                .findRecentFailures(
                        eq(PROJECT_ID),
                        eq(NOW.minus(Duration.ofHours(24))),
                        eq(NOW),
                        eq(MonitorCheckOutcome.FAILURE),
                        any(Limit.class));
    }

    // ------------------------------------------------------- authorization

    @Test
    void aNonMemberCannotSeeTheDashboard() {
        when(projectAccessService.requireReadableProject(PROJECT_ID))
                .thenThrow(ApiException.projectNotFound());

        assertThatThrownBy(() -> dashboardService.getProjectDashboard(PROJECT_ID, null, null))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.PROJECT_NOT_FOUND);

        verify(monitorRepository, never()).countByStatusForProject(any());
        verify(monitorCheckRepository, never()).aggregateForProject(any(), any(), any(), any());
    }

    /** Three queries, no matter how many monitors the project holds. */
    @Test
    void theDashboardCostsThreeQueriesRegardlessOfMonitorCount() {
        givenAccessibleProject();
        givenStatusCounts(statusCount(MonitorStatus.UP, 500));
        givenCheckAggregate(500_000L, 499_000L, 120.0);
        givenRecentFailures(List.of());

        dashboardService.getProjectDashboard(PROJECT_ID, null, null);

        verify(monitorRepository).countByStatusForProject(PROJECT_ID);
        verify(monitorCheckRepository).aggregateForProject(any(), any(), any(), any());
        verify(monitorCheckRepository).findRecentFailures(any(), any(), any(), any(), any());
        verify(monitorRepository, never()).findAllByProjectIdOrderByCreatedAtAsc(any());
    }

    // ----------------------------------------------------------------- setup

    private void givenAccessibleProject() {
        when(projectAccessService.requireReadableProject(PROJECT_ID)).thenReturn(project());
    }

    private void givenEmptyProject() {
        givenAccessibleProject();
        givenStatusCounts();
        givenCheckAggregate(null, null, null);
        givenRecentFailures(List.of());
    }

    private void givenStatusCounts(MonitorStatusCountProjection... rows) {
        when(monitorRepository.countByStatusForProject(PROJECT_ID)).thenReturn(List.of(rows));
    }

    private void givenCheckAggregate(Long total, Long successful, Double average) {
        when(monitorCheckRepository.aggregateForProject(any(), any(), any(), any()))
                .thenReturn(new ProjectCheckStatisticsProjection() {
                    @Override
                    public Long getTotalChecks() {
                        return total;
                    }

                    @Override
                    public Long getSuccessfulChecks() {
                        return successful;
                    }

                    @Override
                    public Double getAverageResponseTimeMs() {
                        return average;
                    }
                });
    }

    private void givenRecentFailures(List<MonitorCheck> failures) {
        when(monitorCheckRepository.findRecentFailures(any(), any(), any(), any(), any()))
                .thenReturn(failures);
    }

    private static MonitorStatusCountProjection statusCount(MonitorStatus status, long count) {
        return new MonitorStatusCountProjection() {
            @Override
            public MonitorStatus getStatus() {
                return status;
            }

            @Override
            public long getCount() {
                return count;
            }
        };
    }

    private static Project project() {
        Project project = new Project("Production APIs", new User("owner@example.com", "{bcrypt}h", "Owner"));
        ReflectionTestUtils.setField(project, "id", PROJECT_ID);
        return project;
    }

    private static MonitorCheck failedCheck() {
        Monitor monitor = new Monitor(project(), "Payment API", "https://api.example.com/health", 200, 60, 5, 3);
        ReflectionTestUtils.setField(monitor, "id", 25L);

        MonitorCheck check = new MonitorCheck(monitor, NOW, MonitorCheckOutcome.FAILURE);
        check.setHttpStatusCode(500);
        check.setErrorType(MonitorCheckErrorType.UNEXPECTED_STATUS);
        check.setErrorMessage("Expected HTTP 200 but received 500");
        return check;
    }
}
