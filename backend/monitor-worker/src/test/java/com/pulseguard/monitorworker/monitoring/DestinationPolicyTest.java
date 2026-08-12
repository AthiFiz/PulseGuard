package com.pulseguard.monitorworker.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.pulseguard.monitorworker.config.MonitoringProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The SSRF policy.
 *
 * <p>Every URL here uses an IP literal or a name the JDK resolves without a
 * network lookup, so these tests never touch DNS or the internet.
 */
class DestinationPolicyTest {

    private final DestinationPolicy strictPolicy = policy(false);
    private final DestinationPolicy permissivePolicy = policy(true);

    // -------------------------------------------------------- blocked by default

    @ParameterizedTest
    @ValueSource(strings = {
        "http://127.0.0.1:8080/health",     // loopback
        "http://localhost:8080/health",     // the obvious name for it
        "http://[::1]:8080/health",         // IPv6 loopback
        "http://0.0.0.0:8080/health",       // wildcard
        "http://10.0.0.5/health",           // private class A
        "http://172.16.4.7/health",         // private class B
        "http://192.168.1.10/health",       // private class C
        "http://169.254.10.10/health",      // link-local
        "http://[fd00::1]/health",          // IPv6 unique-local
        "http://100.64.0.1/health"          // carrier-grade NAT
    })
    void localAndPrivateDestinationsAreBlockedByDefault(String url) {
        DestinationDecision decision = strictPolicy.evaluate(url);

        assertThat(decision.isAllowed()).isFalse();
        assertThat(decision.verdict()).isEqualTo(DestinationDecision.Verdict.BLOCKED);
        assertThat(decision.reason()).contains("monitoring security policy");
    }

    /** Blocking the string "localhost" alone would be trivially bypassed. */
    @Test
    void loopbackIsBlockedByAddressNotByName() {
        assertThat(strictPolicy.evaluate("http://127.0.0.1/health").isAllowed()).isFalse();
        assertThat(strictPolicy.evaluate("http://127.0.0.2/health").isAllowed()).isFalse();
        assertThat(strictPolicy.evaluate("http://[::1]/health").isAllowed()).isFalse();
    }

    // ------------------------------------------------------------ always blocked

    /**
     * The metadata endpoint hands out instance credentials, so it stays blocked
     * even when private addresses are explicitly allowed for development.
     */
    @ParameterizedTest
    @ValueSource(strings = {"http://169.254.169.254/latest/meta-data/", "http://100.100.100.200/"})
    void cloudMetadataIsBlockedEvenWhenPrivateAddressesAreAllowed(String url) {
        assertThat(strictPolicy.evaluate(url).isAllowed()).isFalse();

        DestinationDecision decision = permissivePolicy.evaluate(url);
        assertThat(decision.isAllowed()).isFalse();
        assertThat(decision.verdict()).isEqualTo(DestinationDecision.Verdict.BLOCKED);
    }

    @Test
    void multicastIsBlockedEvenWhenPrivateAddressesAreAllowed() {
        assertThat(permissivePolicy.evaluate("http://224.0.0.1/health").isAllowed()).isFalse();
    }

    // ------------------------------------------------- development override

    @ParameterizedTest
    @ValueSource(strings = {
        "http://127.0.0.1:8080/actuator/health",
        "http://localhost:8080/actuator/health",
        "http://192.168.1.10/health"
    })
    void privateDestinationsAreAllowedWhenExplicitlyEnabled(String url) {
        assertThat(permissivePolicy.evaluate(url).isAllowed()).isTrue();
    }

    // ------------------------------------------------------------ URL shape

    @ParameterizedTest
    @ValueSource(strings = {"ftp://example.com/x", "file:///etc/passwd", "gopher://example.com/"})
    void nonWebSchemesAreRejected(String url) {
        DestinationDecision decision = strictPolicy.evaluate(url);

        assertThat(decision.isAllowed()).isFalse();
        assertThat(decision.reason()).contains("http and https");
    }

    @Test
    void aUrlWithoutAHostIsRejected() {
        assertThat(strictPolicy.evaluate("http:///health").isAllowed()).isFalse();
    }

    @Test
    void anUnparseableUrlIsRejected() {
        assertThat(strictPolicy.evaluate("http://exa mple.com/health").isAllowed()).isFalse();
    }

    // ------------------------------------------------------------------ DNS

    /**
     * A name that cannot resolve is reported separately from a blocked one, so
     * the check can be stored as DNS_ERROR rather than BLOCKED_ADDRESS.
     */
    @Test
    void anUnresolvableHostIsReportedAsUnresolvable() {
        DestinationDecision decision =
                strictPolicy.evaluate("http://this-host-should-not-resolve.invalid/health");

        assertThat(decision.verdict()).isEqualTo(DestinationDecision.Verdict.UNRESOLVABLE);
        assertThat(decision.reason()).contains("DNS");
    }

    // -------------------------------------------------------------- allowed

    @ParameterizedTest
    @ValueSource(strings = {
        "http://93.184.216.34/health",      // a public IPv4 literal
        "https://8.8.8.8/health",
        "https://1.1.1.1:8443/health"
    })
    void publicDestinationsAreAllowed(String url) {
        assertThat(strictPolicy.evaluate(url).isAllowed()).isTrue();
    }

    private static DestinationPolicy policy(boolean allowPrivateAddresses) {
        return new DestinationPolicy(
                new MonitoringProperties(Duration.ofSeconds(5), 50, allowPrivateAddresses));
    }
}
