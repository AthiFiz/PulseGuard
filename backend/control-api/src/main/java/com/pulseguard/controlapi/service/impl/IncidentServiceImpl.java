package com.pulseguard.controlapi.service.impl;

import com.pulseguard.controlapi.domain.Incident;
import com.pulseguard.controlapi.dto.incident.IncidentResponse;
import com.pulseguard.controlapi.dto.monitoring.PageResponse;
import com.pulseguard.controlapi.enums.IncidentStatus;
import com.pulseguard.controlapi.exception.ApiErrorCode;
import com.pulseguard.controlapi.exception.ApiException;
import com.pulseguard.controlapi.repository.IncidentRepository;
import com.pulseguard.controlapi.service.IncidentService;
import com.pulseguard.controlapi.service.ProjectAccessService;
import com.pulseguard.controlapi.service.TimeWindow;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class IncidentServiceImpl implements IncidentService {

    private final IncidentRepository incidentRepository;
    private final ProjectAccessService projectAccessService;

    /**
     * A project's incident history, newest first.
     *
     * <p>Paginated because incidents accumulate for the life of a project, and
     * a monitor that flaps produces one per episode.
     */
    @Override
    @Transactional(readOnly = true)
    public PageResponse<IncidentResponse> getProjectIncidents(
            Long projectId, IncidentStatus status, TimeWindow window, int page, int size) {

        // Access first: an inaccessible project must look missing, not empty.
        projectAccessService.requireReadableProject(projectId);

        QueryPagination.validate(page, size);

        // Fixed sort. Newest-first is what a history view wants, and it is the
        // order the (monitor_id, opened_at) index can serve.
        PageRequest pageRequest = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "openedAt"));

        Page<Incident> incidents = incidentRepository.findProjectIncidents(
                projectId, status, window.from(), window.to(), pageRequest);

        return PageResponse.from(incidents, IncidentResponse::from);
    }

    @Override
    @Transactional(readOnly = true)
    public IncidentResponse getIncident(Long incidentId) {
        return IncidentResponse.from(requireReadableIncident(incidentId));
    }

    /**
     * An incident carries no membership of its own, so access is resolved
     * through its monitor's project.
     *
     * <p>A caller who may not see that project gets an incident-shaped 404
     * rather than a project-shaped one — otherwise the error code alone would
     * tell them the incident exists.
     */
    private Incident requireReadableIncident(Long incidentId) {
        Incident incident = incidentRepository
                .findDetailById(incidentId)
                .orElseThrow(ApiException::incidentNotFound);

        try {
            projectAccessService.requireReadableProject(incident.getMonitor().getProject().getId());
        } catch (ApiException ex) {
            if (ex.getErrorCode() == ApiErrorCode.PROJECT_NOT_FOUND) {
                throw ApiException.incidentNotFound();
            }
            throw ex;
        }
        return incident;
    }
}
