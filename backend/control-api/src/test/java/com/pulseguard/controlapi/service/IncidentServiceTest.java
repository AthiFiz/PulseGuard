package com.pulseguard.controlapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pulseguard.controlapi.domain.Incident;
import com.pulseguard.controlapi.domain.Monitor;
import com.pulseguard.controlapi.domain.Project;
import com.pulseguard.controlapi.domain.User;
import com.pulseguard.controlapi.dto.incident.IncidentResponse;
import com.pulseguard.controlapi.dto.monitoring.PageResponse;
import com.pulseguard.controlapi.enums.IncidentStatus;
import com.pulseguard.controlapi.exception.ApiErrorCode;
import com.pulseguard.controlapi.exception.ApiException;
import com.pulseguard.controlapi.repository.IncidentRepository;
import com.pulseguard.controlapi.service.impl.IncidentServiceImpl;
import java.lang.reflect.Constructor;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

/** Incident reads: paging, filtering, mapping and resource hiding. */
@ExtendWith(MockitoExtension.class)
class IncidentServiceTest {

    private static final Long PROJECT_ID = 10L;
    private static final Long MONITOR_ID = 25L;
    private static final Long INCIDENT_ID = 41L;
    private static final Instant OPENED_AT = Instant.parse("2026-08-12T10:10:00Z");
    private static final Instant RESOLVED_AT = Instant.parse("2026-08-12T10:18:00Z");

    @Mock
    private IncidentRepository incidentRepository;

    @Mock
    private ProjectAccessService projectAccessService;

    @InjectMocks
    private IncidentServiceImpl incidentService;

    // ------------------------------------------------------------- listing

    @Test
    void aProjectsIncidentsAreMappedWithTheirMonitor() {
        givenReadableProject();
        givenIncidentPage(resolvedIncident());

        PageResponse<IncidentResponse> page =
                incidentService.getProjectIncidents(PROJECT_ID, null, TimeWindow.of(null, null), 0, 20);

        assertThat(page.content()).hasSize(1);
        IncidentResponse incident = page.content().getFirst();
        assertThat(incident.id()).isEqualTo(INCIDENT_ID);
        assertThat(incident.projectId()).isEqualTo(PROJECT_ID);
        assertThat(incident.monitorId()).isEqualTo(MONITOR_ID);
        assertThat(incident.monitorName()).isEqualTo("Payment API");
        assertThat(incident.status()).isEqualTo(IncidentStatus.RESOLVED);
        assertThat(incident.openedAt()).isEqualTo(OPENED_AT);
        assertThat(incident.resolvedAt()).isEqualTo(RESOLVED_AT);
        assertThat(incident.openingCheckId()).isEqualTo(1501L);
        assertThat(incident.resolutionCheckId()).isEqualTo(1517L);
    }

    /** Nothing invents a resolution for an outage that has not ended. */
    @Test
    void anOpenIncidentReportsNoResolution() {
        givenReadableProject();
        givenIncidentPage(openIncident());

        PageResponse<IncidentResponse> page =
                incidentService.getProjectIncidents(PROJECT_ID, null, TimeWindow.of(null, null), 0, 20);

        IncidentResponse incident = page.content().getFirst();
        assertThat(incident.status()).isEqualTo(IncidentStatus.OPEN);
        assertThat(incident.resolvedAt()).isNull();
        assertThat(incident.resolutionCheckId()).isNull();
    }

    /** A healthy project legitimately has no incidents at all. */
    @Test
    void aProjectWithNoIncidentsReturnsAnEmptyPage() {
        givenReadableProject();
        givenIncidentPage();

        PageResponse<IncidentResponse> page =
                incidentService.getProjectIncidents(PROJECT_ID, null, TimeWindow.of(null, null), 0, 20);

        assertThat(page.content()).isEmpty();
        assertThat(page.totalElements()).isZero();
    }

    // ------------------------------------------------------------- paging

    @Test
    void incidentsAreSortedNewestFirst() {
        givenReadableProject();
        givenIncidentPage();

        incidentService.getProjectIncidents(PROJECT_ID, null, TimeWindow.of(null, null), 0, 20);

        Sort sort = capturedPageable().getSort();
        assertThat(sort.getOrderFor("openedAt")).isNotNull();
        assertThat(sort.getOrderFor("openedAt").getDirection()).isEqualTo(Sort.Direction.DESC);
    }

    @Test
    void theRequestedPageAndSizeAreUsed() {
        givenReadableProject();
        givenIncidentPage();

        incidentService.getProjectIncidents(PROJECT_ID, null, TimeWindow.of(null, null), 2, 50);

        assertThat(capturedPageable().getPageNumber()).isEqualTo(2);
        assertThat(capturedPageable().getPageSize()).isEqualTo(50);
    }

    /** Clamping would leave a client convinced it had received everything. */
    @Test
    void anOversizedPageIsRejectedRatherThanClamped() {
        givenReadableProject();

        assertThatThrownBy(() -> incidentService.getProjectIncidents(
                        PROJECT_ID, null, TimeWindow.of(null, null), 0, 101))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.MONITORING_QUERY_INVALID);

        verify(incidentRepository, never()).findProjectIncidents(any(), any(), any(), any(), any());
    }

    @Test
    void aNegativePageIsRejected() {
        givenReadableProject();

        assertThatThrownBy(() -> incidentService.getProjectIncidents(
                        PROJECT_ID, null, TimeWindow.of(null, null), -1, 20))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.MONITORING_QUERY_INVALID);
    }

    // ------------------------------------------------------------ filtering

    @Test
    void theStatusFilterIsPassedToTheQuery() {
        givenReadableProject();
        givenIncidentPage();

        incidentService.getProjectIncidents(
                PROJECT_ID, IncidentStatus.OPEN, TimeWindow.of(null, null), 0, 20);

        verify(incidentRepository)
                .findProjectIncidents(eq(PROJECT_ID), eq(IncidentStatus.OPEN), eq(null), eq(null), any());
    }

    @Test
    void noStatusFilterMeansBothOpenAndResolved() {
        givenReadableProject();
        givenIncidentPage();

        incidentService.getProjectIncidents(PROJECT_ID, null, TimeWindow.of(null, null), 0, 20);

        verify(incidentRepository)
                .findProjectIncidents(eq(PROJECT_ID), eq(null), eq(null), eq(null), any());
    }

    @Test
    void theDateRangeIsPassedToTheQuery() {
        givenReadableProject();
        givenIncidentPage();
        Instant from = Instant.parse("2026-08-01T00:00:00Z");
        Instant to = Instant.parse("2026-08-13T00:00:00Z");

        incidentService.getProjectIncidents(PROJECT_ID, null, TimeWindow.of(from, to), 0, 20);

        verify(incidentRepository)
                .findProjectIncidents(eq(PROJECT_ID), eq(null), eq(from), eq(to), any());
    }

    /** Reuses the existing time-window rule rather than a second one. */
    @Test
    void aBackwardsRangeIsRejected() {
        assertThatThrownBy(() -> TimeWindow.of(
                        Instant.parse("2026-08-13T00:00:00Z"), Instant.parse("2026-08-01T00:00:00Z")))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.MONITORING_QUERY_INVALID);
    }

    // -------------------------------------------------------------- detail

    @Test
    void anIncidentCanBeReadOnItsOwn() {
        when(incidentRepository.findDetailById(INCIDENT_ID)).thenReturn(Optional.of(resolvedIncident()));
        when(projectAccessService.requireReadableProject(PROJECT_ID)).thenReturn(project());

        IncidentResponse incident = incidentService.getIncident(INCIDENT_ID);

        assertThat(incident.id()).isEqualTo(INCIDENT_ID);
        assertThat(incident.monitorName()).isEqualTo("Payment API");
        assertThat(incident.status()).isEqualTo(IncidentStatus.RESOLVED);
    }

    @Test
    void anUnknownIncidentIsNotFound() {
        when(incidentRepository.findDetailById(INCIDENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> incidentService.getIncident(INCIDENT_ID))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.INCIDENT_NOT_FOUND);
    }

    // ------------------------------------------------------- authorization

    @Test
    void aNonMemberCannotListAProjectsIncidents() {
        when(projectAccessService.requireReadableProject(PROJECT_ID))
                .thenThrow(ApiException.projectNotFound());

        assertThatThrownBy(() -> incidentService.getProjectIncidents(
                        PROJECT_ID, null, TimeWindow.of(null, null), 0, 20))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.PROJECT_NOT_FOUND);

        verify(incidentRepository, never()).findProjectIncidents(any(), any(), any(), any(), any());
    }

    /**
     * A non-member gets an incident-shaped 404, not a project-shaped one.
     * Otherwise the error code alone would confirm the incident exists.
     */
    @Test
    void aNonMemberCannotTellAnExistingIncidentFromAMissingOne() {
        when(incidentRepository.findDetailById(INCIDENT_ID)).thenReturn(Optional.of(resolvedIncident()));
        when(projectAccessService.requireReadableProject(PROJECT_ID))
                .thenThrow(ApiException.projectNotFound());

        assertThatThrownBy(() -> incidentService.getIncident(INCIDENT_ID))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.INCIDENT_NOT_FOUND);
    }

    // ---------------------------------------------------------------- setup

    private void givenReadableProject() {
        when(projectAccessService.requireReadableProject(PROJECT_ID)).thenReturn(project());
    }

    private void givenIncidentPage(Incident... incidents) {
        when(incidentRepository.findProjectIncidents(any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(incidents), PageRequest.of(0, 20), incidents.length));
    }

    private Pageable capturedPageable() {
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(incidentRepository).findProjectIncidents(any(), any(), any(), any(), captor.capture());
        return captor.getValue();
    }

    private static Project project() {
        Project project = new Project("Production APIs", new User("owner@example.com", "{bcrypt}h", "Owner"));
        ReflectionTestUtils.setField(project, "id", PROJECT_ID);
        return project;
    }

    private static Monitor monitor() {
        Monitor monitor =
                new Monitor(project(), "Payment API", "https://api.example.com/health", 200, 60, 5, 3);
        ReflectionTestUtils.setField(monitor, "id", MONITOR_ID);
        return monitor;
    }

    private static Incident openIncident() {
        return incident(IncidentStatus.OPEN, null, 1501L, null);
    }

    private static Incident resolvedIncident() {
        return incident(IncidentStatus.RESOLVED, RESOLVED_AT, 1501L, 1517L);
    }

    /**
     * The entity is written only by the worker, so it has no public constructor
     * and no setters. Tests populate it reflectively rather than widening it.
     */
    private static Incident incident(
            IncidentStatus status, Instant resolvedAt, Long openingCheckId, Long resolutionCheckId) {

        Incident incident = newIncident();
        ReflectionTestUtils.setField(incident, "id", INCIDENT_ID);
        ReflectionTestUtils.setField(incident, "monitor", monitor());
        ReflectionTestUtils.setField(incident, "status", status);
        ReflectionTestUtils.setField(incident, "openedAt", OPENED_AT);
        ReflectionTestUtils.setField(incident, "resolvedAt", resolvedAt);
        ReflectionTestUtils.setField(incident, "openingCheckId", openingCheckId);
        ReflectionTestUtils.setField(incident, "resolutionCheckId", resolutionCheckId);
        return incident;
    }

    private static Incident newIncident() {
        try {
            Constructor<Incident> constructor = Incident.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Could not instantiate Incident for tests", ex);
        }
    }
}
