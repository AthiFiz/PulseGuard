package com.pulseguard.controlapi.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pulseguard.controlapi.dto.monitoring.MonitorCheckResponse;
import com.pulseguard.controlapi.dto.monitoring.MonitorStatisticsResponse;
import com.pulseguard.controlapi.dto.monitoring.MonitorStatusCountsResponse;
import com.pulseguard.controlapi.dto.monitoring.PageResponse;
import com.pulseguard.controlapi.dto.monitoring.ProjectCheckStatisticsResponse;
import com.pulseguard.controlapi.dto.monitoring.ProjectDashboardResponse;
import com.pulseguard.controlapi.dto.monitoring.RecentFailureResponse;
import com.pulseguard.controlapi.dto.monitoring.TimeWindowResponse;
import com.pulseguard.controlapi.enums.MonitorCheckErrorType;
import com.pulseguard.controlapi.enums.MonitorCheckOutcome;
import com.pulseguard.controlapi.enums.MonitorStatus;
import com.pulseguard.controlapi.exception.ApiException;
import com.pulseguard.controlapi.service.DashboardService;
import com.pulseguard.controlapi.service.MonitoringQueryService;
import com.pulseguard.controlapi.service.TimeWindow;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Request mapping, parameter binding and status codes for the Task 06 read
 * endpoints. Security filters are off here; {@code SecurityRulesTest} covers the
 * authentication requirement.
 */
@WebMvcTest({MonitorHistoryController.class, ProjectDashboardController.class})
@AutoConfigureMockMvc(addFilters = false)
class MonitoringControllerTest {

    private static final Instant NOW = Instant.parse("2026-08-12T08:30:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MonitoringQueryService monitoringQueryService;

    @MockitoBean
    private DashboardService dashboardService;

    // -------------------------------------------------------------- history

    @Test
    void historyReturnsOkWithPaginationMetadata() throws Exception {
        Mockito.when(monitoringQueryService.getCheckHistory(eq(25L), any(), any(), anyInt(), anyInt()))
                .thenReturn(new PageResponse<>(List.of(checkResponse()), 0, 50, 201, 5, true, false));

        mockMvc.perform(get("/api/v1/monitors/25/checks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(1001))
                .andExpect(jsonPath("$.content[0].outcome").value("SUCCESS"))
                .andExpect(jsonPath("$.totalElements").value(201))
                .andExpect(jsonPath("$.totalPages").value(5))
                .andExpect(jsonPath("$.first").value(true))
                .andExpect(jsonPath("$.last").value(false))
                // The Spring Page internals must not leak into the contract.
                .andExpect(jsonPath("$.pageable").doesNotExist())
                .andExpect(jsonPath("$.sort").doesNotExist());
    }

    @Test
    void historyDefaultsToTheFirstPageOfFifty() throws Exception {
        Mockito.when(monitoringQueryService.getCheckHistory(any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(emptyPage());

        mockMvc.perform(get("/api/v1/monitors/25/checks")).andExpect(status().isOk());

        Mockito.verify(monitoringQueryService)
                .getCheckHistory(eq(25L), any(), eq(null), eq(0), eq(50));
    }

    @Test
    void historyBindsPageSizeOutcomeAndDateRange() throws Exception {
        Mockito.when(monitoringQueryService.getCheckHistory(any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(emptyPage());

        mockMvc.perform(get("/api/v1/monitors/25/checks")
                        .param("page", "2")
                        .param("size", "20")
                        .param("outcome", "FAILURE")
                        .param("from", "2026-08-01T00:00:00Z")
                        .param("to", "2026-08-12T00:00:00Z"))
                .andExpect(status().isOk());

        ArgumentCaptor<TimeWindow> window = ArgumentCaptor.forClass(TimeWindow.class);
        Mockito.verify(monitoringQueryService)
                .getCheckHistory(
                        eq(25L), window.capture(), eq(MonitorCheckOutcome.FAILURE), eq(2), eq(20));

        org.assertj.core.api.Assertions.assertThat(window.getValue().from())
                .isEqualTo(Instant.parse("2026-08-01T00:00:00Z"));
        org.assertj.core.api.Assertions.assertThat(window.getValue().to())
                .isEqualTo(Instant.parse("2026-08-12T00:00:00Z"));
    }

    @Test
    void anUnknownOutcomeValueIsRejectedAsBadRequestNotServerError() throws Exception {
        mockMvc.perform(get("/api/v1/monitors/25/checks").param("outcome", "MAYBE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void anUnparseableTimestampIsRejectedAsBadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/monitors/25/checks").param("from", "yesterday"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aNonNumericPageIsRejectedAsBadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/monitors/25/checks").param("page", "first"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void invalidPaginationSurfacesAsAMonitoringQueryError() throws Exception {
        Mockito.when(monitoringQueryService.getCheckHistory(any(), any(), any(), anyInt(), anyInt()))
                .thenThrow(ApiException.monitoringQueryInvalid("'size' must not exceed 100"));

        mockMvc.perform(get("/api/v1/monitors/25/checks").param("size", "500"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MONITORING_QUERY_INVALID"));
    }

    @Test
    void historyForAnInaccessibleMonitorIsNotFound() throws Exception {
        Mockito.when(monitoringQueryService.getCheckHistory(any(), any(), any(), anyInt(), anyInt()))
                .thenThrow(ApiException.monitorNotFound());

        mockMvc.perform(get("/api/v1/monitors/999/checks"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MONITOR_NOT_FOUND"));
    }

    // ----------------------------------------------------------- statistics

    @Test
    void statisticsReturnsOk() throws Exception {
        Mockito.when(monitoringQueryService.getStatistics(eq(25L), any()))
                .thenReturn(new MonitorStatisticsResponse(
                        25L, null, null, 1440, 1435, 5,
                        new BigDecimal("99.65"), new BigDecimal("121.42"),
                        82, 645, NOW, MonitorStatus.UP));

        mockMvc.perform(get("/api/v1/monitors/25/statistics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalChecks").value(1440))
                .andExpect(jsonPath("$.failedChecks").value(5))
                .andExpect(jsonPath("$.uptimePercentage").value(99.65))
                .andExpect(jsonPath("$.averageResponseTimeMs").value(121.42))
                .andExpect(jsonPath("$.currentStatus").value("UP"));
    }

    /** Nulls must serialise as JSON null, not disappear or become zero. */
    @Test
    void statisticsForAMonitorWithNoChecksReturnsNulls() throws Exception {
        Mockito.when(monitoringQueryService.getStatistics(eq(25L), any()))
                .thenReturn(new MonitorStatisticsResponse(
                        25L, null, null, 0, 0, 0, null, null, null, null, null, MonitorStatus.UNKNOWN));

        mockMvc.perform(get("/api/v1/monitors/25/statistics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalChecks").value(0))
                .andExpect(jsonPath("$.uptimePercentage").doesNotExist())
                .andExpect(jsonPath("$.averageResponseTimeMs").doesNotExist());
    }

    // ------------------------------------------------------------ dashboard

    @Test
    void dashboardReturnsOk() throws Exception {
        Mockito.when(dashboardService.getProjectDashboard(eq(10L), any(), any()))
                .thenReturn(dashboardResponse());

        mockMvc.perform(get("/api/v1/projects/10/dashboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value(10))
                .andExpect(jsonPath("$.monitors.total").value(10))
                .andExpect(jsonPath("$.monitors.up").value(7))
                // Current state, alongside the status counts rather than inside
                // the windowed check figures.
                .andExpect(jsonPath("$.openIncidents").value(1))
                .andExpect(jsonPath("$.checks.uptimePercentage").value(97.90))
                .andExpect(jsonPath("$.recentFailures[0].monitorName").value("Payment API"))
                .andExpect(jsonPath("$.window.from").exists());
    }

    @Test
    void dashboardBindsACustomWindow() throws Exception {
        Mockito.when(dashboardService.getProjectDashboard(any(), any(), any()))
                .thenReturn(dashboardResponse());

        mockMvc.perform(get("/api/v1/projects/10/dashboard")
                        .param("from", "2026-08-01T00:00:00Z")
                        .param("to", "2026-08-05T00:00:00Z"))
                .andExpect(status().isOk());

        Mockito.verify(dashboardService)
                .getProjectDashboard(
                        10L,
                        Instant.parse("2026-08-01T00:00:00Z"),
                        Instant.parse("2026-08-05T00:00:00Z"));
    }

    @Test
    void aBackwardsDashboardWindowIsRejected() throws Exception {
        Mockito.when(dashboardService.getProjectDashboard(any(), any(), any()))
                .thenThrow(ApiException.monitoringQueryInvalid("'from' must not be after 'to'"));

        mockMvc.perform(get("/api/v1/projects/10/dashboard")
                        .param("from", "2026-08-05T00:00:00Z")
                        .param("to", "2026-08-01T00:00:00Z"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MONITORING_QUERY_INVALID"));
    }

    @Test
    void dashboardForAnInaccessibleProjectIsNotFound() throws Exception {
        Mockito.when(dashboardService.getProjectDashboard(any(), any(), any()))
                .thenThrow(ApiException.projectNotFound());

        mockMvc.perform(get("/api/v1/projects/999/dashboard"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROJECT_NOT_FOUND"));
    }

    // ----------------------------------------------------------------- setup

    private static PageResponse<MonitorCheckResponse> emptyPage() {
        return new PageResponse<>(List.of(), 0, 50, 0, 0, true, true);
    }

    private static MonitorCheckResponse checkResponse() {
        return new MonitorCheckResponse(
                1001L, NOW, MonitorCheckOutcome.SUCCESS, 200, 124, null, null);
    }

    private static ProjectDashboardResponse dashboardResponse() {
        return new ProjectDashboardResponse(
                10L,
                NOW,
                new TimeWindowResponse(NOW.minusSeconds(86400), NOW),
                new MonitorStatusCountsResponse(10, 7, 1, 1, 1),
                1,
                new ProjectCheckStatisticsResponse(
                        1430, 1400, 30, new BigDecimal("97.90"), new BigDecimal("132.50")),
                List.of(new RecentFailureResponse(
                        25L, "Payment API", NOW, 500,
                        MonitorCheckErrorType.UNEXPECTED_STATUS,
                        "Expected HTTP 200 but received 500")));
    }
}
