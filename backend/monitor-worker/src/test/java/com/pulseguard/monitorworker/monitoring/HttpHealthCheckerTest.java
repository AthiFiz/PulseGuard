package com.pulseguard.monitorworker.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.pulseguard.monitorworker.config.MonitoringProperties;
import com.pulseguard.monitorworker.enums.MonitorCheckErrorType;
import com.pulseguard.monitorworker.enums.MonitorCheckOutcome;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Result mapping for real HTTP exchanges.
 *
 * <p>Requests go to a throwaway server bound to the loopback interface, so the
 * tests exercise the actual client — timeouts, connection failures and status
 * handling included — without touching the internet.
 */
class HttpHealthCheckerTest {

    private static final Instant NOW = Instant.parse("2026-08-11T12:00:00Z");

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void aMatchingStatusIsASuccess() throws IOException {
        int port = startServer(200, 0);

        HealthCheckResult result = checker(true).check(monitor(url(port), 200, 5));

        assertThat(result.outcome()).isEqualTo(MonitorCheckOutcome.SUCCESS);
        assertThat(result.httpStatusCode()).isEqualTo(200);
        assertThat(result.responseTimeMs()).isNotNull().isGreaterThanOrEqualTo(0);
        assertThat(result.errorType()).isNull();
        assertThat(result.errorMessage()).isNull();
        assertThat(result.checkedAt()).isEqualTo(NOW);
    }

    /** The configured status is authoritative — 2xx is not automatically healthy. */
    @Test
    void aDifferentStatusIsAFailureEvenWhenItIsSuccessful() throws IOException {
        int port = startServer(200, 0);

        HealthCheckResult result = checker(true).check(monitor(url(port), 201, 5));

        assertThat(result.outcome()).isEqualTo(MonitorCheckOutcome.FAILURE);
        assertThat(result.errorType()).isEqualTo(MonitorCheckErrorType.UNEXPECTED_STATUS);
        // The status that actually came back is recorded.
        assertThat(result.httpStatusCode()).isEqualTo(200);
        assertThat(result.errorMessage()).isEqualTo("Expected HTTP 201 but received 200");
    }

    @Test
    void aServerErrorIsRecordedWithItsStatus() throws IOException {
        int port = startServer(503, 0);

        HealthCheckResult result = checker(true).check(monitor(url(port), 200, 5));

        assertThat(result.outcome()).isEqualTo(MonitorCheckOutcome.FAILURE);
        assertThat(result.httpStatusCode()).isEqualTo(503);
        assertThat(result.errorType()).isEqualTo(MonitorCheckErrorType.UNEXPECTED_STATUS);
    }

    /** Redirects are not followed, so a 3xx is just a status like any other. */
    @Test
    void aRedirectIsReportedRatherThanFollowed() throws IOException {
        int port = startRedirectServer("http://169.254.169.254/latest/meta-data/");

        HealthCheckResult result = checker(true).check(monitor(url(port), 200, 5));

        assertThat(result.httpStatusCode()).isEqualTo(302);
        assertThat(result.errorType()).isEqualTo(MonitorCheckErrorType.UNEXPECTED_STATUS);
    }

    @Test
    void aRedirectCanItselfBeTheExpectedStatus() throws IOException {
        int port = startRedirectServer("https://example.com/moved");

        HealthCheckResult result = checker(true).check(monitor(url(port), 302, 5));

        assertThat(result.outcome()).isEqualTo(MonitorCheckOutcome.SUCCESS);
    }

    /** The timeout must actually cut the request off, not just be measured afterwards. */
    @Test
    void aSlowEndpointTimesOut() throws IOException {
        int port = startServer(200, 3000);

        HealthCheckResult result = checker(true).check(monitor(url(port), 200, 1));

        assertThat(result.outcome()).isEqualTo(MonitorCheckOutcome.FAILURE);
        assertThat(result.errorType()).isEqualTo(MonitorCheckErrorType.TIMEOUT);
        assertThat(result.httpStatusCode()).isNull();
        assertThat(result.errorMessage()).isEqualTo("Request timed out");
    }

    @Test
    void nothingListeningIsAConnectionError() throws IOException {
        int port = findFreePort();

        HealthCheckResult result = checker(true).check(monitor(url(port), 200, 2));

        assertThat(result.outcome()).isEqualTo(MonitorCheckOutcome.FAILURE);
        assertThat(result.errorType()).isEqualTo(MonitorCheckErrorType.CONNECTION_ERROR);
        assertThat(result.httpStatusCode()).isNull();
    }

    /**
     * The destination policy resolves the host itself, so a name that cannot
     * resolve is reported as DNS_ERROR before any socket is opened. `.invalid`
     * is reserved by RFC 2606 and never resolves.
     */
    @Test
    void anUnresolvableHostIsADnsError() {
        HealthCheckResult result = checker(true)
                .check(monitor("http://pulseguard-nonexistent.invalid/health", 200, 2));

        assertThat(result.outcome()).isEqualTo(MonitorCheckOutcome.FAILURE);
        assertThat(result.errorType()).isEqualTo(MonitorCheckErrorType.DNS_ERROR);
        assertThat(result.httpStatusCode()).isNull();
        assertThat(result.responseTimeMs()).isNull();
    }

    /** With the default policy, loopback is refused before a request is attempted. */
    @Test
    void aBlockedDestinationIsNeverRequested() throws IOException {
        int port = startServer(200, 0);

        HealthCheckResult result = checker(false).check(monitor(url(port), 200, 5));

        assertThat(result.outcome()).isEqualTo(MonitorCheckOutcome.FAILURE);
        assertThat(result.errorType()).isEqualTo(MonitorCheckErrorType.BLOCKED_ADDRESS);
        assertThat(result.httpStatusCode()).isNull();
        // No request was made, so there is no duration to report.
        assertThat(result.responseTimeMs()).isNull();
        assertThat(result.errorMessage()).contains("monitoring security policy");
    }

    @Test
    void metadataStaysBlockedEvenWithPrivateAddressesEnabled() {
        HealthCheckResult result =
                checker(true).check(monitor("http://169.254.169.254/latest/meta-data/", 200, 2));

        assertThat(result.errorType()).isEqualTo(MonitorCheckErrorType.BLOCKED_ADDRESS);
    }

    // ----------------------------------------------------------------- setup

    private static HttpHealthChecker checker(boolean allowPrivateAddresses) {
        DestinationPolicy policy = new DestinationPolicy(
                new MonitoringProperties(Duration.ofSeconds(5), 50, allowPrivateAddresses),
                java.net.InetAddress::getAllByName);
        return new HttpHealthChecker(policy, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static MonitorSnapshot monitor(String url, int expectedStatus, int timeoutSeconds) {
        return new MonitorSnapshot(1L, "Test monitor", url, expectedStatus, timeoutSeconds);
    }

    private static String url(int port) {
        return "http://127.0.0.1:" + port + "/health";
    }

    private int startServer(int status, long delayMillis) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", exchange -> {
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] body = "ok".getBytes();
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return server.getAddress().getPort();
    }

    private int startRedirectServer(String location) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", exchange -> {
            exchange.getResponseHeaders().add("Location", location);
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.start();
        return server.getAddress().getPort();
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
