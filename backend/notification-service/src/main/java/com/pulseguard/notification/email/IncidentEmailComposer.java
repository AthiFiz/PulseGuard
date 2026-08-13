package com.pulseguard.notification.email;

import com.pulseguard.notification.config.NotificationProperties;
import com.pulseguard.notification.enums.IncidentEventType;
import com.pulseguard.notification.event.IncidentLifecycleEvent;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Writes the email for an incident event.
 *
 * <p>Plain text on purpose. It renders everywhere, it cannot carry a tracking
 * pixel or a broken stylesheet, and it is trivial to assert on in a test —
 * which matters for something whose failure mode is "sent the wrong thing to
 * everyone".
 *
 * <p>Composition happens once, when the event is consumed, and the result is
 * stored on the delivery. A retry three hours later sends exactly the message
 * that was written at the time.
 */
@Component
@RequiredArgsConstructor
public class IncidentEmailComposer {

    /** Comfortably inside the {@code VARCHAR(500)} column. */
    private static final int MAX_SUBJECT_LENGTH = 200;

    private final NotificationProperties notificationProperties;

    public EmailMessage compose(IncidentLifecycleEvent event) {
        return event.eventType() == IncidentEventType.INCIDENT_OPENED
                ? new EmailMessage(openedSubject(event), openedBody(event))
                : new EmailMessage(resolvedSubject(event), resolvedBody(event));
    }

    // ---------------------------------------------------------------- opened

    private String openedSubject(IncidentLifecycleEvent event) {
        return safeSubject("[PulseGuard] Incident opened: " + event.monitorName());
    }

    private String openedBody(IncidentLifecycleEvent event) {
        StringBuilder body = new StringBuilder();
        body.append("PulseGuard detected an outage.\n\n");
        body.append("Monitor: ").append(event.monitorName()).append('\n');
        body.append("Incident ID: ").append(event.incidentId()).append('\n');
        body.append("Status: OPEN\n");
        body.append("Opened at: ").append(event.incidentOpenedAt()).append('\n');

        appendCheckDetails(body, "Failing check:", event);
        appendLink(body, event);

        return body.toString();
    }

    // -------------------------------------------------------------- resolved

    private String resolvedSubject(IncidentLifecycleEvent event) {
        return safeSubject("[PulseGuard] Incident resolved: " + event.monitorName());
    }

    private String resolvedBody(IncidentLifecycleEvent event) {
        StringBuilder body = new StringBuilder();
        body.append("PulseGuard detected a recovery.\n\n");
        body.append("Monitor: ").append(event.monitorName()).append('\n');
        body.append("Incident ID: ").append(event.incidentId()).append('\n');
        body.append("Status: RESOLVED\n");
        body.append("Opened at: ").append(event.incidentOpenedAt()).append('\n');
        body.append("Resolved at: ").append(event.incidentResolvedAt()).append('\n');

        String duration = formatDuration(event.incidentOpenedAt(), event.incidentResolvedAt());
        if (duration != null) {
            body.append("Duration: ").append(duration).append('\n');
        }

        appendCheckDetails(body, "Recovery check:", event);
        appendLink(body, event);

        return body.toString();
    }

    // ----------------------------------------------------------------- parts

    /**
     * Only what actually applies. A successful check has no error to report,
     * and a DNS failure never received a status code — printing "Error: null"
     * would be noise pretending to be information.
     */
    private static void appendCheckDetails(
            StringBuilder body, String heading, IncidentLifecycleEvent event) {

        if (event.httpStatusCode() == null
                && event.responseTimeMs() == null
                && event.errorType() == null
                && event.errorMessage() == null) {
            return;
        }

        body.append('\n').append(heading).append('\n');
        if (event.httpStatusCode() != null) {
            body.append("HTTP Status: ").append(event.httpStatusCode()).append('\n');
        }
        if (event.responseTimeMs() != null) {
            body.append("Response Time: ").append(event.responseTimeMs()).append(" ms\n");
        }
        if (event.errorType() != null) {
            body.append("Error: ").append(event.errorType()).append('\n');
        }
        if (event.errorMessage() != null && !event.errorMessage().isBlank()) {
            body.append("Details: ").append(event.errorMessage()).append('\n');
        }
    }

    private void appendLink(StringBuilder body, IncidentLifecycleEvent event) {
        body.append("\nView incident:\n");
        body.append(notificationProperties.frontendBaseUrl())
                .append("/incidents/")
                .append(event.incidentId())
                .append('\n');
    }

    /**
     * A subject is a mail header, and headers end at a newline. The monitor
     * name comes from whatever a user typed, so a name containing CR or LF
     * could otherwise inject additional headers into the message.
     */
    private static String safeSubject(String subject) {
        String singleLine = subject.replaceAll("[\\r\\n]+", " ").replaceAll("\\s{2,}", " ").trim();
        return singleLine.length() <= MAX_SUBJECT_LENGTH
                ? singleLine
                : singleLine.substring(0, MAX_SUBJECT_LENGTH - 1) + "…";
    }

    /** Presentation only — no duration is stored anywhere. */
    private static String formatDuration(Instant from, Instant to) {
        if (from == null || to == null) {
            return null;
        }
        Duration duration = Duration.between(from, to);
        if (duration.isNegative()) {
            return null;
        }

        long hours = duration.toHours();
        long minutes = duration.toMinutesPart();
        long seconds = duration.toSecondsPart();

        if (hours > 0) {
            return "%dh %dm".formatted(hours, minutes);
        }
        if (minutes > 0) {
            return "%dm %ds".formatted(minutes, seconds);
        }
        return "%ds".formatted(seconds);
    }
}
