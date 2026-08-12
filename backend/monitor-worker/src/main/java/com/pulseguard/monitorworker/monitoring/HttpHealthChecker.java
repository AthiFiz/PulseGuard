package com.pulseguard.monitorworker.monitoring;

import com.pulseguard.monitorworker.enums.MonitorCheckErrorType;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Executes one HTTP check and classifies the outcome.
 *
 * <p>Only the status code and the elapsed time matter, so the response body is
 * never read. That keeps memory flat regardless of what the monitored endpoint
 * returns, and means a hostile endpoint cannot exhaust the worker by streaming
 * data at it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HttpHealthChecker {

    private static final String USER_AGENT = "PulseGuard-Monitor/1.0";

    /**
     * One client per distinct timeout.
     *
     * <p>The connect timeout belongs to the client rather than the request, and
     * each monitor configures its own, so a single shared client cannot serve
     * them all. Timeouts are constrained to 1–30 seconds by the Control API, so
     * this map is bounded at 30 entries and each client is reused thereafter.
     */
    private final Map<Integer, RestClient> clientsByTimeoutSeconds = new ConcurrentHashMap<>();

    private final DestinationPolicy destinationPolicy;
    private final Clock clock;

    public HealthCheckResult check(MonitorSnapshot monitor) {
        // The instant the attempt begins. Used for the stored check and for the
        // monitor's lastCheckedAt, so both agree.
        Instant checkedAt = Instant.now(clock);

        DestinationDecision decision = destinationPolicy.evaluate(monitor.url());
        if (!decision.isAllowed()) {
            // No request is made, so there is no status and no duration.
            MonitorCheckErrorType errorType =
                    decision.verdict() == DestinationDecision.Verdict.UNRESOLVABLE
                            ? MonitorCheckErrorType.DNS_ERROR
                            : MonitorCheckErrorType.BLOCKED_ADDRESS;
            return HealthCheckResult.failure(checkedAt, null, null, errorType, decision.reason());
        }

        long startedNanos = System.nanoTime();
        try {
            int status = clientFor(monitor.timeoutSeconds())
                    .get()
                    .uri(monitor.url())
                    .header("User-Agent", USER_AGENT)
                    // exchange() hands back the raw response and closes it
                    // afterwards, so the body is discarded unread. retrieve()
                    // would instead throw on 4xx/5xx, which are perfectly
                    // valid answers to a health check.
                    .exchange((request, response) -> response.getStatusCode().value());

            int elapsedMs = elapsedMillis(startedNanos);

            if (status == monitor.expectedStatusCode()) {
                return HealthCheckResult.success(checkedAt, status, elapsedMs);
            }

            return HealthCheckResult.failure(
                    checkedAt,
                    status,
                    elapsedMs,
                    MonitorCheckErrorType.UNEXPECTED_STATUS,
                    "Expected HTTP %d but received %d".formatted(monitor.expectedStatusCode(), status));

        } catch (Exception ex) {
            int elapsedMs = elapsedMillis(startedNanos);
            ClassifiedFailure failure = classify(ex);
            log.debug("Monitor check failed: monitorId={}, type={}", monitor.id(), failure.errorType());
            return HealthCheckResult.failure(
                    checkedAt, null, elapsedMs, failure.errorType(), failure.message());
        }
    }

    /**
     * Maps an exception onto a stored error type by walking the cause chain —
     * the useful exception is almost always wrapped by the HTTP client.
     */
    private static ClassifiedFailure classify(Throwable ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpConnectTimeoutException
                    || cause instanceof HttpTimeoutException
                    || cause instanceof SocketTimeoutException) {
                return new ClassifiedFailure(MonitorCheckErrorType.TIMEOUT, "Request timed out");
            }
            // Checked before ConnectException, which it extends.
            if (cause instanceof UnknownHostException) {
                return new ClassifiedFailure(MonitorCheckErrorType.DNS_ERROR, "DNS resolution failed");
            }
            if (cause instanceof ConnectException || cause instanceof NoRouteToHostException) {
                return new ClassifiedFailure(
                        MonitorCheckErrorType.CONNECTION_ERROR, "Connection failed");
            }
            if (cause instanceof IOException) {
                return new ClassifiedFailure(
                        MonitorCheckErrorType.CONNECTION_ERROR, "Network error during request");
            }
        }
        // Deliberately generic: the exception's own message could contain
        // fragments of the URL or response, so it is not stored.
        return new ClassifiedFailure(MonitorCheckErrorType.UNKNOWN, "Check failed for an unknown reason");
    }

    private static int elapsedMillis(long startedNanos) {
        // nanoTime is monotonic, so this cannot be skewed by a clock adjustment
        // mid-request the way subtracting two wall-clock readings could.
        return (int) Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
    }

    private RestClient clientFor(int timeoutSeconds) {
        return clientsByTimeoutSeconds.computeIfAbsent(timeoutSeconds, seconds -> {
            Duration timeout = Duration.ofSeconds(seconds);

            java.net.http.HttpClient httpClient = java.net.http.HttpClient.newBuilder()
                    // Redirects are not followed: a public URL that redirects
                    // into private address space would bypass the destination
                    // policy entirely, since only the first hop is checked.
                    .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
                    .connectTimeout(timeout)
                    .build();

            JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
            requestFactory.setReadTimeout(timeout);

            return RestClient.builder().requestFactory(requestFactory).build();
        });
    }

    private record ClassifiedFailure(MonitorCheckErrorType errorType, String message) {
    }
}
