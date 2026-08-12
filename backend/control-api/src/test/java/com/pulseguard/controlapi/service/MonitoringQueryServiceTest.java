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
import com.pulseguard.controlapi.dto.monitoring.MonitorCheckResponse;
import com.pulseguard.controlapi.dto.monitoring.MonitorStatisticsResponse;
import com.pulseguard.controlapi.dto.monitoring.PageResponse;
import com.pulseguard.controlapi.enums.MonitorCheckErrorType;
import com.pulseguard.controlapi.enums.MonitorCheckOutcome;
import com.pulseguard.controlapi.enums.MonitorStatus;
import com.pulseguard.controlapi.exception.ApiErrorCode;
import com.pulseguard.controlapi.exception.ApiException;
import com.pulseguard.controlapi.repository.MonitorCheckRepository;
import com.pulseguard.controlapi.repository.projection.MonitorStatisticsProjection;
import com.pulseguard.controlapi.service.impl.MonitoringQueryServiceImpl;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

/** Check history and monitor statistics. No database. */
@ExtendWith(MockitoExtension.class)
class MonitoringQueryServiceTest {

    private static final Long MONITOR_ID = 25L;
    private static final Instant FROM = Instant.parse("2026-08-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-08-12T00:00:00Z");

    @Mock
    private MonitorCheckRepository monitorCheckRepository;

    @Mock
    private MonitorAccessService monitorAccessService;

    @InjectMocks
    private MonitoringQueryServiceImpl monitoringQueryService;

    // -------------------------------------------------------------- history

    @Test
    void historyIsRequestedNewestFirst() {
        givenReadableMonitor();
        givenHistoryPage(List.of());

        monitoringQueryService.getCheckHistory(MONITOR_ID, TimeWindow.of(null, null), null, 0, 50);

        Pageable pageable = capturedPageable();
        assertThat(pageable.getSort().getOrderFor("checkedAt")).isNotNull();
        assertThat(pageable.getSort().getOrderFor("checkedAt").getDirection())
                .isEqualTo(Sort.Direction.DESC);
    }

    @Test
    void historyPassesThePageAndSizeThrough() {
        givenReadableMonitor();
        givenHistoryPage(List.of());

        monitoringQueryService.getCheckHistory(MONITOR_ID, TimeWindow.of(null, null), null, 2, 25);

        assertThat(capturedPageable().getPageNumber()).isEqualTo(2);
        assertThat(capturedPageable().getPageSize()).isEqualTo(25);
    }

    @Test
    void historyPassesTheDateRangeThrough() {
        givenReadableMonitor();
        givenHistoryPage(List.of());

        monitoringQueryService.getCheckHistory(MONITOR_ID, TimeWindow.of(FROM, TO), null, 0, 50);

        verify(monitorCheckRepository)
                .findHistory(eq(MONITOR_ID), eq(FROM), eq(TO), eq(null), any(Pageable.class));
    }

    @Test
    void historyPassesTheOutcomeFilterThrough() {
        givenReadableMonitor();
        givenHistoryPage(List.of());

        monitoringQueryService.getCheckHistory(
                MONITOR_ID, TimeWindow.of(null, null), MonitorCheckOutcome.FAILURE, 0, 50);

        verify(monitorCheckRepository)
                .findHistory(
                        eq(MONITOR_ID), eq(null), eq(null),
                        eq(MonitorCheckOutcome.FAILURE), any(Pageable.class));
    }

    @Test
    void historyMapsPaginationMetadata() {
        givenReadableMonitor();
        when(monitorCheckRepository.findHistory(any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(successCheck()), PageRequest.of(0, 50), 201));

        PageResponse<MonitorCheckResponse> response =
                monitoringQueryService.getCheckHistory(MONITOR_ID, TimeWindow.of(null, null), null, 0, 50);

        assertThat(response.page()).isZero();
        assertThat(response.size()).isEqualTo(50);
        assertThat(response.totalElements()).isEqualTo(201);
        assertThat(response.totalPages()).isEqualTo(5);
        assertThat(response.first()).isTrue();
        assertThat(response.last()).isFalse();
        assertThat(response.content()).hasSize(1);
    }

    /** A monitor with no checks is empty, not missing. */
    @Test
    void anEmptyHistoryIsAnEmptyPageNotAnError() {
        givenReadableMonitor();
        givenHistoryPage(List.of());

        PageResponse<MonitorCheckResponse> response =
                monitoringQueryService.getCheckHistory(MONITOR_ID, TimeWindow.of(null, null), null, 0, 50);

        assertThat(response.content()).isEmpty();
        assertThat(response.totalElements()).isZero();
    }

    @Test
    void historyRequiresReadAccessBeforeAnythingElse() {
        when(monitorAccessService.requireReadableMonitor(MONITOR_ID))
                .thenThrow(ApiException.monitorNotFound());

        assertThatThrownBy(() -> monitoringQueryService.getCheckHistory(
                        MONITOR_ID, TimeWindow.of(null, null), null, 0, 50))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.MONITOR_NOT_FOUND);

        // An inaccessible monitor must not even be queried.
        verify(monitorCheckRepository, never()).findHistory(any(), any(), any(), any(), any());
    }

    // ----------------------------------------------------------- pagination

    @ParameterizedTest
    @CsvSource({"-1, 50", "0, 0", "0, 101", "0, -5"})
    void invalidPaginationIsRejected(int page, int size) {
        givenReadableMonitor();

        assertThatThrownBy(() -> monitoringQueryService.getCheckHistory(
                        MONITOR_ID, TimeWindow.of(null, null), null, page, size))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.MONITORING_QUERY_INVALID);
    }

    @Test
    void theMaximumPageSizeIsAccepted() {
        givenReadableMonitor();
        givenHistoryPage(List.of());

        monitoringQueryService.getCheckHistory(MONITOR_ID, TimeWindow.of(null, null), null, 0, 100);

        assertThat(capturedPageable().getPageSize()).isEqualTo(100);
    }

    // ------------------------------------------------------- time windows

    @Test
    void aRangeThatEndsBeforeItStartsIsRejected() {
        assertThatThrownBy(() -> TimeWindow.of(TO, FROM))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.MONITORING_QUERY_INVALID);
    }

    @Test
    void anOpenEndedRangeIsAllowed() {
        assertThat(TimeWindow.of(FROM, null).to()).isNull();
        assertThat(TimeWindow.of(null, TO).from()).isNull();
        assertThat(TimeWindow.of(null, null).from()).isNull();
    }

    @Test
    void identicalBoundsAreAllowed() {
        assertThat(TimeWindow.of(FROM, FROM).from()).isEqualTo(FROM);
    }

    // ----------------------------------------------------------- statistics

    @Test
    void statisticsReportCountsAndResponseTimes() {
        givenReadableMonitor();
        givenAggregate(1440L, 1435L, 121.4242, 82, 645, TO);

        MonitorStatisticsResponse stats =
                monitoringQueryService.getStatistics(MONITOR_ID, TimeWindow.of(FROM, TO));

        assertThat(stats.monitorId()).isEqualTo(MONITOR_ID);
        assertThat(stats.totalChecks()).isEqualTo(1440);
        assertThat(stats.successfulChecks()).isEqualTo(1435);
        assertThat(stats.failedChecks()).isEqualTo(5);
        assertThat(stats.averageResponseTimeMs()).isEqualByComparingTo("121.42");
        assertThat(stats.minimumResponseTimeMs()).isEqualTo(82);
        assertThat(stats.maximumResponseTimeMs()).isEqualTo(645);
        assertThat(stats.lastCheckedAt()).isEqualTo(TO);
        assertThat(stats.currentStatus()).isEqualTo(MonitorStatus.UP);
    }

    @Test
    void statisticsEchoTheRequestedRange() {
        givenReadableMonitor();
        givenAggregate(10L, 10L, 100.0, 90, 110, TO);

        MonitorStatisticsResponse stats =
                monitoringQueryService.getStatistics(MONITOR_ID, TimeWindow.of(FROM, TO));

        assertThat(stats.from()).isEqualTo(FROM);
        assertThat(stats.to()).isEqualTo(TO);
    }

    /** No range means all history — not a silently invented default window. */
    @Test
    void anUnboundedRangeIsPassedThroughAsNull() {
        givenReadableMonitor();
        givenAggregate(10L, 10L, 100.0, 90, 110, TO);

        MonitorStatisticsResponse stats =
                monitoringQueryService.getStatistics(MONITOR_ID, TimeWindow.of(null, null));

        assertThat(stats.from()).isNull();
        assertThat(stats.to()).isNull();
        verify(monitorCheckRepository)
                .aggregateForMonitor(MONITOR_ID, null, null, MonitorCheckOutcome.SUCCESS);
    }

    @ParameterizedTest
    @CsvSource({
        "10, 10, 100.00",
        "9,  10, 90.00",
        "1,  3,  33.33",
        "2,  3,  66.67",
        "0,  10, 0.00",
        "1435, 1440, 99.65"
    })
    void uptimeIsSuccessfulChecksOverTotalChecks(long successful, long total, String expected) {
        givenReadableMonitor();
        givenAggregate(total, successful, 100.0, 90, 110, TO);

        MonitorStatisticsResponse stats =
                monitoringQueryService.getStatistics(MONITOR_ID, TimeWindow.of(null, null));

        assertThat(stats.uptimePercentage()).isEqualByComparingTo(new BigDecimal(expected));
    }

    /**
     * Zero checks means unknown availability, not zero availability — a monitor
     * nobody has checked has not been down.
     */
    @Test
    void aMonitorWithNoChecksHasNullUptimeRatherThanZero() {
        givenReadableMonitor();
        givenAggregate(0L, 0L, null, null, null, null);

        MonitorStatisticsResponse stats =
                monitoringQueryService.getStatistics(MONITOR_ID, TimeWindow.of(null, null));

        assertThat(stats.totalChecks()).isZero();
        assertThat(stats.successfulChecks()).isZero();
        assertThat(stats.failedChecks()).isZero();
        assertThat(stats.uptimePercentage()).isNull();
        assertThat(stats.averageResponseTimeMs()).isNull();
        assertThat(stats.minimumResponseTimeMs()).isNull();
        assertThat(stats.maximumResponseTimeMs()).isNull();
        assertThat(stats.lastCheckedAt()).isNull();
    }

    /**
     * SQL aggregates return NULL, not 0, when nothing matched — unboxing those
     * straight into primitives would be a 500.
     */
    @Test
    void nullAggregatesFromTheDatabaseAreHandled() {
        givenReadableMonitor();
        givenAggregate(null, null, null, null, null, null);

        MonitorStatisticsResponse stats =
                monitoringQueryService.getStatistics(MONITOR_ID, TimeWindow.of(null, null));

        assertThat(stats.totalChecks()).isZero();
        assertThat(stats.uptimePercentage()).isNull();
    }

    /**
     * Checks exist but none measured a duration — a run of DNS failures, say.
     * Reporting 0ms would suggest an implausibly fast service.
     */
    @Test
    void checksWithoutResponseTimesLeaveTheTimingFiguresNull() {
        givenReadableMonitor();
        givenAggregate(5L, 0L, null, null, null, TO);

        MonitorStatisticsResponse stats =
                monitoringQueryService.getStatistics(MONITOR_ID, TimeWindow.of(null, null));

        assertThat(stats.totalChecks()).isEqualTo(5);
        assertThat(stats.uptimePercentage()).isEqualByComparingTo("0.00");
        assertThat(stats.averageResponseTimeMs()).isNull();
        assertThat(stats.minimumResponseTimeMs()).isNull();
        assertThat(stats.maximumResponseTimeMs()).isNull();
    }

    @ParameterizedTest
    @ValueSource(doubles = {121.4242, 121.425, 99.999})
    void averageResponseTimeIsRoundedToTwoDecimals(double raw) {
        givenReadableMonitor();
        givenAggregate(10L, 10L, raw, 1, 2, TO);

        MonitorStatisticsResponse stats =
                monitoringQueryService.getStatistics(MONITOR_ID, TimeWindow.of(null, null));

        assertThat(stats.averageResponseTimeMs().scale()).isEqualTo(2);
    }

    @Test
    void statisticsRequireReadAccess() {
        when(monitorAccessService.requireReadableMonitor(MONITOR_ID))
                .thenThrow(ApiException.monitorNotFound());

        assertThatThrownBy(() ->
                        monitoringQueryService.getStatistics(MONITOR_ID, TimeWindow.of(null, null)))
                .isInstanceOf(ApiException.class);

        verify(monitorCheckRepository, never()).aggregateForMonitor(any(), any(), any(), any());
    }

    // ----------------------------------------------------------------- setup

    private void givenReadableMonitor() {
        when(monitorAccessService.requireReadableMonitor(MONITOR_ID)).thenReturn(monitor());
    }

    private void givenHistoryPage(List<MonitorCheck> checks) {
        when(monitorCheckRepository.findHistory(any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(checks, PageRequest.of(0, 50), checks.size()));
    }

    private void givenAggregate(
            Long total, Long successful, Double average, Integer min, Integer max, Instant last) {
        when(monitorCheckRepository.aggregateForMonitor(any(), any(), any(), any()))
                .thenReturn(new MonitorStatisticsProjection() {
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

                    @Override
                    public Integer getMinimumResponseTimeMs() {
                        return min;
                    }

                    @Override
                    public Integer getMaximumResponseTimeMs() {
                        return max;
                    }

                    @Override
                    public Instant getLastCheckedAt() {
                        return last;
                    }
                });
    }

    private Pageable capturedPageable() {
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(monitorCheckRepository).findHistory(any(), any(), any(), any(), captor.capture());
        return captor.getValue();
    }

    private static Monitor monitor() {
        Project project = new Project("Production APIs", new User("owner@example.com", "{bcrypt}h", "Owner"));
        ReflectionTestUtils.setField(project, "id", 10L);

        Monitor monitor = new Monitor(project, "Payment API", "https://api.example.com/health", 200, 60, 5, 3);
        ReflectionTestUtils.setField(monitor, "id", MONITOR_ID);
        monitor.setCurrentStatus(MonitorStatus.UP);
        return monitor;
    }

    private static MonitorCheck successCheck() {
        MonitorCheck check = new MonitorCheck(monitor(), TO, MonitorCheckOutcome.SUCCESS);
        check.setHttpStatusCode(200);
        check.setResponseTimeMs(124);
        check.setErrorType((MonitorCheckErrorType) null);
        ReflectionTestUtils.setField(check, "id", 1001L);
        return check;
    }
}
