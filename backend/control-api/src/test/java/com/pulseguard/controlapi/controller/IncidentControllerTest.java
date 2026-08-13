package com.pulseguard.controlapi.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pulseguard.controlapi.dto.incident.IncidentResponse;
import com.pulseguard.controlapi.dto.monitoring.PageResponse;
import com.pulseguard.controlapi.enums.IncidentStatus;
import com.pulseguard.controlapi.exception.ApiException;
import com.pulseguard.controlapi.service.IncidentService;
import com.pulseguard.controlapi.service.TimeWindow;
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
 * Request mapping, parameter binding and status codes for the incident
 * endpoints. Security filters are off here; {@code SecurityRulesTest} covers
 * the authentication requirement.
 */
@WebMvcTest({ProjectIncidentController.class, IncidentController.class})
@AutoConfigureMockMvc(addFilters = false)
class IncidentControllerTest {

    private static final Instant OPENED_AT = Instant.parse("2026-08-12T10:10:00Z");
    private static final Instant RESOLVED_AT = Instant.parse("2026-08-12T10:18:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private IncidentService incidentService;

    // ----------------------------------------------------------------- list

    @Test
    void listReturnsOkWithPaginationMetadata() throws Exception {
        Mockito.when(incidentService.getProjectIncidents(eq(10L), any(), any(), anyInt(), anyInt()))
                .thenReturn(new PageResponse<>(List.of(resolved()), 0, 20, 41, 3, true, false));

        mockMvc.perform(get("/api/v1/projects/10/incidents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(41))
                .andExpect(jsonPath("$.content[0].monitorName").value("Payment API"))
                .andExpect(jsonPath("$.content[0].status").value("RESOLVED"))
                .andExpect(jsonPath("$.totalElements").value(41))
                .andExpect(jsonPath("$.totalPages").value(3))
                // The Spring Page internals must not leak into the contract.
                .andExpect(jsonPath("$.pageable").doesNotExist());
    }

    @Test
    void listDefaultsToTheFirstPageOfTwenty() throws Exception {
        Mockito.when(incidentService.getProjectIncidents(any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(emptyPage());

        mockMvc.perform(get("/api/v1/projects/10/incidents")).andExpect(status().isOk());

        Mockito.verify(incidentService).getProjectIncidents(eq(10L), eq(null), any(), eq(0), eq(20));
    }

    @Test
    void listBindsPageSizeStatusAndDateRange() throws Exception {
        Mockito.when(incidentService.getProjectIncidents(any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(emptyPage());

        mockMvc.perform(get("/api/v1/projects/10/incidents")
                        .param("page", "2")
                        .param("size", "50")
                        .param("status", "OPEN")
                        .param("from", "2026-08-01T00:00:00Z")
                        .param("to", "2026-08-13T00:00:00Z"))
                .andExpect(status().isOk());

        ArgumentCaptor<TimeWindow> window = ArgumentCaptor.forClass(TimeWindow.class);
        Mockito.verify(incidentService)
                .getProjectIncidents(eq(10L), eq(IncidentStatus.OPEN), window.capture(), eq(2), eq(50));
        assertThat(window.getValue().from())
                .isEqualTo(Instant.parse("2026-08-01T00:00:00Z"));
        assertThat(window.getValue().to())
                .isEqualTo(Instant.parse("2026-08-13T00:00:00Z"));
    }

    /** An unknown status is a bad request, not a 500. */
    @Test
    void anUnknownStatusIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/projects/10/incidents").param("status", "ACKNOWLEDGED"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anInaccessibleProjectIsNotFound() throws Exception {
        Mockito.when(incidentService.getProjectIncidents(any(), any(), any(), anyInt(), anyInt()))
                .thenThrow(ApiException.projectNotFound());

        mockMvc.perform(get("/api/v1/projects/99/incidents"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROJECT_NOT_FOUND"));
    }

    @Test
    void anInvalidPageSizeIsABadRequest() throws Exception {
        Mockito.when(incidentService.getProjectIncidents(any(), any(), any(), anyInt(), anyInt()))
                .thenThrow(ApiException.monitoringQueryInvalid("'size' must not exceed 100"));

        mockMvc.perform(get("/api/v1/projects/10/incidents").param("size", "500"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MONITORING_QUERY_INVALID"));
    }

    // --------------------------------------------------------------- detail

    @Test
    void detailReturnsTheIncident() throws Exception {
        Mockito.when(incidentService.getIncident(41L)).thenReturn(resolved());

        mockMvc.perform(get("/api/v1/incidents/41"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(41))
                .andExpect(jsonPath("$.projectId").value(10))
                .andExpect(jsonPath("$.monitorId").value(25))
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.openedAt").value("2026-08-12T10:10:00Z"))
                .andExpect(jsonPath("$.resolvedAt").value("2026-08-12T10:18:00Z"))
                .andExpect(jsonPath("$.openingCheckId").value(1501))
                .andExpect(jsonPath("$.resolutionCheckId").value(1517));
    }

    /** An open incident has no end, and the response says so plainly. */
    @Test
    void anOpenIncidentSerialisesItsNulls() throws Exception {
        Mockito.when(incidentService.getIncident(41L)).thenReturn(open());

        mockMvc.perform(get("/api/v1/incidents/41"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.resolvedAt").value(nullValue()))
                .andExpect(jsonPath("$.resolutionCheckId").value(nullValue()));
    }

    @Test
    void anUnknownIncidentIsNotFound() throws Exception {
        Mockito.when(incidentService.getIncident(999L)).thenThrow(ApiException.incidentNotFound());

        mockMvc.perform(get("/api/v1/incidents/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INCIDENT_NOT_FOUND"));
    }

    /** Incidents are system-generated; there is nothing to post to. */
    @Test
    void thereIsNoWayToCreateAnIncident() throws Exception {
        mockMvc.perform(post("/api/v1/projects/10/incidents"))
                .andExpect(status().isMethodNotAllowed());
    }

    // ---------------------------------------------------------------- setup

    private static PageResponse<IncidentResponse> emptyPage() {
        return new PageResponse<>(List.of(), 0, 20, 0, 0, true, true);
    }

    private static IncidentResponse resolved() {
        return new IncidentResponse(
                41L, 10L, 25L, "Payment API", IncidentStatus.RESOLVED,
                OPENED_AT, RESOLVED_AT, 1501L, 1517L);
    }

    private static IncidentResponse open() {
        return new IncidentResponse(
                41L, 10L, 25L, "Payment API", IncidentStatus.OPEN, OPENED_AT, null, 1501L, null);
    }
}
