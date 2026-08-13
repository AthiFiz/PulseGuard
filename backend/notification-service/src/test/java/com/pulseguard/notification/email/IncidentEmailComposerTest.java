package com.pulseguard.notification.email;

import static org.assertj.core.api.Assertions.assertThat;

import com.pulseguard.notification.config.NotificationProperties;
import com.pulseguard.notification.enums.IncidentEventType;
import com.pulseguard.notification.enums.MonitorCheckErrorType;
import com.pulseguard.notification.enums.MonitorStatus;
import com.pulseguard.notification.event.IncidentLifecycleEvent;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** What the email actually says. */
class IncidentEmailComposerTest {

    private static final Instant OPENED_AT = Instant.parse("2026-08-13T06:30:00Z");
    private static final Instant RESOLVED_AT = Instant.parse("2026-08-13T06:42:00Z");

    private final IncidentEmailComposer composer = new IncidentEmailComposer(new NotificationProperties(
            "pulseguard@example.com",
            "http://localhost:5173",
            Duration.ofSeconds(10),
            50,
            5,
            Duration.ofSeconds(30)));

    // ---------------------------------------------------------------- opened

    @Test
    void theOpenedSubjectNamesTheMonitor() {
        assertThat(composer.compose(openedEvent()).subject())
                .isEqualTo("[PulseGuard] Incident opened: Payment API");
    }

    @Test
    void theOpenedBodyExplainsWhatHappened() {
        String body = composer.compose(openedEvent()).body();

        assertThat(body).contains("PulseGuard detected an outage.");
        assertThat(body).contains("Monitor: Payment API");
        assertThat(body).contains("Incident ID: 41");
        assertThat(body).contains("Status: OPEN");
        assertThat(body).contains("Opened at: 2026-08-13T06:30:00Z");
    }

    /** The reason is the point: "it is down" is less useful than "it returned 503". */
    @Test
    void theOpenedBodyIncludesTheFailingCheck() {
        String body = composer.compose(openedEvent()).body();

        assertThat(body).contains("Failing check:");
        assertThat(body).contains("HTTP Status: 503");
        assertThat(body).contains("Response Time: 210 ms");
        assertThat(body).contains("Error: UNEXPECTED_STATUS");
        assertThat(body).contains("Details: Expected HTTP 200 but received 503");
    }

    @Test
    void theOpenedBodyLinksToTheIncident() {
        assertThat(composer.compose(openedEvent()).body())
                .contains("http://localhost:5173/incidents/41");
    }

    /** A DNS failure never received a status, and saying "null" would be noise. */
    @Test
    void absentCheckDetailsAreOmittedRatherThanPrintedAsNull() {
        IncidentLifecycleEvent event = new IncidentLifecycleEvent(
                1, "e-1", IncidentEventType.INCIDENT_OPENED, OPENED_AT,
                41L, 10L, 25L, "Payment API", OPENED_AT, null,
                1501L, MonitorStatus.DOWN,
                null, null, MonitorCheckErrorType.DNS_ERROR, null);

        String body = composer.compose(event).body();

        assertThat(body).contains("Error: DNS_ERROR");
        assertThat(body).doesNotContain("HTTP Status:");
        assertThat(body).doesNotContain("Response Time:");
        assertThat(body).doesNotContain("Details:");
        assertThat(body).doesNotContain("null");
    }

    // -------------------------------------------------------------- resolved

    @Test
    void theResolvedSubjectSaysResolved() {
        assertThat(composer.compose(resolvedEvent()).subject())
                .isEqualTo("[PulseGuard] Incident resolved: Payment API");
    }

    @Test
    void theResolvedBodyCarriesBothTimesAndTheDuration() {
        String body = composer.compose(resolvedEvent()).body();

        assertThat(body).contains("PulseGuard detected a recovery.");
        assertThat(body).contains("Status: RESOLVED");
        assertThat(body).contains("Opened at: 2026-08-13T06:30:00Z");
        assertThat(body).contains("Resolved at: 2026-08-13T06:42:00Z");
        // Twelve minutes, computed for display and stored nowhere.
        assertThat(body).contains("Duration: 12m 0s");
    }

    @Test
    void theResolvedBodyIncludesTheRecoveringCheck() {
        String body = composer.compose(resolvedEvent()).body();

        assertThat(body).contains("Recovery check:");
        assertThat(body).contains("HTTP Status: 200");
        assertThat(body).contains("Response Time: 93 ms");
        // A success has nothing to explain.
        assertThat(body).doesNotContain("Error:");
    }

    @Test
    void aLongOutageIsShownInHours() {
        IncidentLifecycleEvent event = resolvedEventEnding(OPENED_AT.plus(Duration.ofMinutes(150)));

        assertThat(composer.compose(event).body()).contains("Duration: 2h 30m");
    }

    @Test
    void aBriefOutageIsShownInSeconds() {
        IncidentLifecycleEvent event = resolvedEventEnding(OPENED_AT.plusSeconds(45));

        assertThat(composer.compose(event).body()).contains("Duration: 45s");
    }

    // ----------------------------------------------------------- safety

    /**
     * A subject is a mail header, and headers end at a newline. The monitor
     * name comes from whatever a user typed, so a name containing CR or LF
     * could otherwise inject extra headers into the message.
     */
    @Test
    void aMonitorNameWithNewlinesCannotBreakTheSubjectHeader() {
        IncidentLifecycleEvent event = openedEventNamed(
                "Payment API\r\nBcc: attacker@example.com\nSubject: Free money");

        String subject = composer.compose(event).subject();

        assertThat(subject).doesNotContain("\r").doesNotContain("\n");
        assertThat(subject).startsWith("[PulseGuard] Incident opened: Payment API");
    }

    @Test
    void anAbsurdlyLongMonitorNameIsTruncatedToFitTheSubject() {
        String subject = composer.compose(openedEventNamed("x".repeat(1000))).subject();

        assertThat(subject.length()).isLessThanOrEqualTo(200);
        assertThat(subject).endsWith("…");
    }

    /**
     * The event contract deliberately excludes the monitor's URL, and the email
     * must not reintroduce it — a URL can carry tokens and internal hostnames.
     */
    @Test
    void theOnlyUrlInAnEmailIsTheIncidentLink() {
        assertOnlyLinkIsTheIncident(composer.compose(openedEvent()).body());
        assertOnlyLinkIsTheIncident(composer.compose(resolvedEvent()).body());
    }

    /**
     * Every URL in the message must point at PulseGuard's own frontend. The
     * event carries no monitor URL, and the composer must not find a way to
     * introduce one.
     */
    private static void assertOnlyLinkIsTheIncident(String body) {
        java.util.regex.Matcher urls =
                java.util.regex.Pattern.compile("\\bhttps?://\\S+").matcher(body);

        int found = 0;
        while (urls.find()) {
            found++;
            assertThat(urls.group()).startsWith("http://localhost:5173/incidents/");
        }
        assertThat(found).isEqualTo(1);
    }

    // ---------------------------------------------------------------- setup

    private static IncidentLifecycleEvent openedEvent() {
        return openedEventNamed("Payment API");
    }

    private static IncidentLifecycleEvent openedEventNamed(String monitorName) {
        return new IncidentLifecycleEvent(
                1, "e-1", IncidentEventType.INCIDENT_OPENED, OPENED_AT,
                41L, 10L, 25L, monitorName, OPENED_AT, null,
                1501L, MonitorStatus.DOWN,
                503, 210, MonitorCheckErrorType.UNEXPECTED_STATUS, "Expected HTTP 200 but received 503");
    }

    private static IncidentLifecycleEvent resolvedEvent() {
        return resolvedEventEnding(RESOLVED_AT);
    }

    private static IncidentLifecycleEvent resolvedEventEnding(Instant resolvedAt) {
        return new IncidentLifecycleEvent(
                1, "e-2", IncidentEventType.INCIDENT_RESOLVED, resolvedAt,
                41L, 10L, 25L, "Payment API", OPENED_AT, resolvedAt,
                1525L, MonitorStatus.UP,
                200, 93, null, null);
    }
}
