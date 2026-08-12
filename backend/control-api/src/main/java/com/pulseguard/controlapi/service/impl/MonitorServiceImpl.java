package com.pulseguard.controlapi.service.impl;

import com.pulseguard.controlapi.domain.Monitor;
import com.pulseguard.controlapi.domain.Project;
import com.pulseguard.controlapi.dto.monitor.CreateMonitorRequest;
import com.pulseguard.controlapi.dto.monitor.MonitorResponse;
import com.pulseguard.controlapi.dto.monitor.UpdateMonitorRequest;
import com.pulseguard.controlapi.enums.MonitorStatus;
import com.pulseguard.controlapi.exception.ApiException;
import com.pulseguard.controlapi.repository.MonitorRepository;
import com.pulseguard.controlapi.service.MonitorAccessService;
import com.pulseguard.controlapi.service.MonitorService;
import com.pulseguard.controlapi.service.ProjectAccessService;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class MonitorServiceImpl implements MonitorService {

    /** PulseGuard only ever issues web requests, so no other scheme is useful. */
    private static final Set<String> ALLOWED_URL_SCHEMES = Set.of("http", "https");

    private final MonitorRepository monitorRepository;
    private final ProjectAccessService projectAccessService;
    private final MonitorAccessService monitorAccessService;
    private final Clock clock;

    /**
     * Creates a monitor in the given project. Requires PROJECT_ADMIN or a
     * system administrator.
     *
     * <p>{@code nextCheckAt} is set to now so the monitor is already due the
     * moment the Monitor Worker exists. Nothing consumes it yet.
     */
    @Override
    @Transactional
    public MonitorResponse createMonitor(Long projectId, CreateMonitorRequest request) {
        Project project = projectAccessService.requireManageableProject(projectId);

        validateUrl(request.url());
        validateTimeoutAgainstInterval(request.timeoutSeconds(), request.intervalSeconds());

        Monitor monitor = new Monitor(
                project,
                request.name(),
                request.url(),
                request.expectedStatusCode(),
                request.intervalSeconds(),
                request.timeoutSeconds(),
                request.failureThreshold());

        monitor.setDescription(emptyToNull(request.description()));
        monitor.setHttpMethod(request.httpMethod());
        // Operational state is owned here, never taken from the request.
        monitor.setCurrentStatus(MonitorStatus.UNKNOWN);
        monitor.setConsecutiveFailures(0);
        monitor.setLastCheckedAt(null);
        monitor.setNextCheckAt(Instant.now(clock));

        Monitor saved = monitorRepository.save(monitor);
        log.info("Monitor created: monitorId={}, projectId={}", saved.getId(), projectId);

        return MonitorResponse.from(saved);
    }

    /** Any project member may list monitors. */
    @Override
    @Transactional(readOnly = true)
    public List<MonitorResponse> listMonitors(Long projectId) {
        projectAccessService.requireReadableProject(projectId);

        return monitorRepository.findAllByProjectIdOrderByCreatedAtAsc(projectId).stream()
                .map(MonitorResponse::from)
                .toList();
    }

    /** Any project member may read a monitor. */
    @Override
    @Transactional(readOnly = true)
    public MonitorResponse getMonitor(Long monitorId) {
        return MonitorResponse.from(monitorAccessService.requireReadableMonitor(monitorId));
    }

    /**
     * Replaces the monitor's configuration. Requires PROJECT_ADMIN or a system
     * administrator.
     *
     * <p>Only configuration is touched. Status, failure count, and check
     * timestamps are left exactly as they are — reconfiguring a monitor is not
     * evidence about its health.
     */
    @Override
    @Transactional
    public MonitorResponse updateMonitor(Long monitorId, UpdateMonitorRequest request) {
        Monitor monitor = monitorAccessService.requireManageableMonitor(monitorId);

        validateUrl(request.url());
        validateTimeoutAgainstInterval(request.timeoutSeconds(), request.intervalSeconds());

        monitor.setName(request.name());
        monitor.setDescription(emptyToNull(request.description()));
        monitor.setUrl(request.url());
        monitor.setHttpMethod(request.httpMethod());
        monitor.setExpectedStatusCode(request.expectedStatusCode());
        monitor.setIntervalSeconds(request.intervalSeconds());
        monitor.setTimeoutSeconds(request.timeoutSeconds());
        monitor.setFailureThreshold(request.failureThreshold());

        log.info("Monitor updated: monitorId={}", monitorId);
        return MonitorResponse.from(monitor);
    }

    /**
     * Stops a monitor being scheduled. Requires PROJECT_ADMIN or a system
     * administrator.
     *
     * <p>Clearing {@code nextCheckAt} is what actually takes it out of the
     * future worker's queue; the status is the human-visible half. The failure
     * count is reset so resuming later starts from a clean slate, but
     * {@code lastCheckedAt} is preserved because it records something that
     * really happened.
     *
     * <p>Idempotent: pausing an already-paused monitor simply returns it.
     */
    @Override
    @Transactional
    public MonitorResponse pauseMonitor(Long monitorId) {
        Monitor monitor = monitorAccessService.requireManageableMonitor(monitorId);

        if (monitor.getCurrentStatus() == MonitorStatus.PAUSED) {
            return MonitorResponse.from(monitor);
        }

        monitor.setCurrentStatus(MonitorStatus.PAUSED);
        monitor.setNextCheckAt(null);
        monitor.setConsecutiveFailures(0);

        log.info("Monitor paused: monitorId={}", monitorId);
        return MonitorResponse.from(monitor);
    }

    /**
     * Puts a paused monitor back into the schedule. Requires PROJECT_ADMIN or a
     * system administrator.
     *
     * <p>The status becomes UNKNOWN rather than UP: no check has run, so
     * claiming health would be a lie.
     *
     * <p>Only PAUSED monitors transition. Resuming one that is already UP or
     * DOWN leaves it untouched, so an accidental call cannot discard a real
     * observed state.
     */
    @Override
    @Transactional
    public MonitorResponse resumeMonitor(Long monitorId) {
        Monitor monitor = monitorAccessService.requireManageableMonitor(monitorId);

        if (monitor.getCurrentStatus() != MonitorStatus.PAUSED) {
            return MonitorResponse.from(monitor);
        }

        monitor.setCurrentStatus(MonitorStatus.UNKNOWN);
        monitor.setConsecutiveFailures(0);
        monitor.setNextCheckAt(Instant.now(clock));

        log.info("Monitor resumed: monitorId={}", monitorId);
        return MonitorResponse.from(monitor);
    }

    /**
     * Deletes a monitor. Requires PROJECT_ADMIN or a system administrator.
     *
     * <p>Its {@code monitor_checks} rows go with it through the database
     * cascade. Nothing loads those checks here, so unlike project deletion there
     * is no stale entity in the persistence context to worry about.
     */
    @Override
    @Transactional
    public void deleteMonitor(Long monitorId) {
        Monitor monitor = monitorAccessService.requireManageableMonitor(monitorId);
        monitorRepository.delete(monitor);
        log.info("Monitor deleted: monitorId={}", monitorId);
    }

    /**
     * Accepts only http and https.
     *
     * <p>Parsed as a URI rather than matched with a prefix check, so oddities
     * like {@code HtTpS://} or a missing host are judged on structure. No
     * network request is made — whether the URL responds is the worker's
     * problem, not a validation concern.
     */
    private static void validateUrl(String url) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException ex) {
            throw ApiException.monitorValidation("URL is not a valid URI");
        }

        String scheme = uri.getScheme();
        if (scheme == null || !ALLOWED_URL_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT))) {
            throw ApiException.monitorValidation("URL must use the http or https scheme");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw ApiException.monitorValidation("URL must include a host");
        }
    }

    /**
     * A timeout at or beyond the interval would let one slow check still be
     * running when the next is due, so the two are ordered here rather than in
     * an annotation, which cannot compare two fields.
     */
    private static void validateTimeoutAgainstInterval(int timeoutSeconds, int intervalSeconds) {
        if (timeoutSeconds >= intervalSeconds) {
            throw ApiException.monitorValidation(
                    "Timeout (%ds) must be shorter than the check interval (%ds)"
                            .formatted(timeoutSeconds, intervalSeconds));
        }
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
