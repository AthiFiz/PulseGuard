package com.pulseguard.monitorworker.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.pulseguard.monitorworker.config.MonitoringProperties;
import java.net.InetAddress;
import java.net.UnknownHostException;
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
    @ValueSource(strings = {
            "http://169.254.169.254/latest/meta-data/",
            "http://100.100.100.200/",
            // AWS's IPv6 metadata endpoint. Java expands this to
            // fd00:ec2:0:0:0:0:0:254, so a policy comparing the literal text
            // would silently fail to match it.
            "http://[fd00:ec2::254]/latest/meta-data/"})
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

    // ------------------------------------------------ IPv4-mapped IPv6

    /**
     * {@code ::ffff:127.0.0.1} is the IPv6 spelling of an IPv4 address. Java
     * normalises these to {@code Inet4Address}, so the ordinary loopback and
     * private checks see them for what they are — but that is a property of the
     * JDK rather than of this code, and it is worth pinning down.
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "http://[::ffff:127.0.0.1]/health",   // loopback, IPv6 spelling
        "http://[::ffff:10.0.0.1]/health",    // private class A
        "http://[::ffff:192.168.1.1]/health"  // private class C
    })
    void ipv4MappedIpv6FormsCannotBypassThePolicy(String url) {
        DestinationDecision decision = strictPolicy.evaluate(url);

        assertThat(decision.isAllowed()).isFalse();
        assertThat(decision.verdict()).isEqualTo(DestinationDecision.Verdict.BLOCKED);
    }

    // ------------------------------------------- unusual address spellings

    /**
     * Integer and short-form IPv4 both resolve to real addresses in Java, so
     * the policy sees the address rather than the spelling.
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "http://2130706433/health",  // 127.0.0.1 as a single integer
        "http://127.1/health"        // short form, also 127.0.0.1
    })
    void unusualSpellingsOfLoopbackAreStillBlocked(String url) {
        assertThat(strictPolicy.evaluate(url).isAllowed()).isFalse();
    }

    /**
     * Documents a JDK behaviour rather than asserting a policy: Java does
     * <em>not</em> read a leading zero as octal, so {@code 0177.0.0.1} is the
     * public address 177.0.0.1 and not loopback. Tools that do parse octal
     * would reach a different host from the one PulseGuard checks — worth
     * knowing, but not a bypass of this policy.
     */
    @Test
    void aLeadingZeroIsNotTreatedAsOctal() throws UnknownHostException {
        assertThat(InetAddress.getByName("0177.0.0.1").getHostAddress()).isEqualTo("177.0.0.1");
        assertThat(strictPolicy.evaluate("http://0177.0.0.1/health").isAllowed()).isTrue();
    }

    // --------------------------------------------------- multi-address DNS

    /**
     * The rule that a partly-private name is refused outright. A host with one
     * public and one private address must not be allowed through on the
     * strength of the public one — the request could land on either.
     */
    @Test
    void aHostResolvingToBothPublicAndPrivateAddressesIsBlocked() {
        DestinationPolicy policy = policy(false, resolvesTo("8.8.8.8", "127.0.0.1"));

        DestinationDecision decision = policy.evaluate("http://mixed.example/health");

        assertThat(decision.isAllowed()).isFalse();
        assertThat(decision.verdict()).isEqualTo(DestinationDecision.Verdict.BLOCKED);
    }

    /** Order must not matter: the unsafe address may come first or last. */
    @Test
    void theUnsafeAddressIsFoundWhicheverOrderItArrivesIn() {
        assertThat(policy(false, resolvesTo("10.0.0.1", "8.8.8.8"))
                        .evaluate("http://mixed.example/health").isAllowed())
                .isFalse();
        assertThat(policy(false, resolvesTo("8.8.8.8", "1.1.1.1", "192.168.0.9"))
                        .evaluate("http://mixed.example/health").isAllowed())
                .isFalse();
    }

    /** Even with the development override, a metadata address in the set blocks it. */
    @Test
    void aMetadataAddressAmongOthersBlocksTheWholeDestination() {
        DestinationPolicy policy = policy(true, resolvesTo("8.8.8.8", "169.254.169.254"));

        assertThat(policy.evaluate("http://sneaky.example/health").isAllowed()).isFalse();
    }

    @Test
    void aHostResolvingOnlyToPublicAddressesIsAllowed() {
        DestinationPolicy policy = policy(false, resolvesTo("8.8.8.8", "1.1.1.1"));

        assertThat(policy.evaluate("http://public.example/health").isAllowed()).isTrue();
    }

    private static DestinationPolicy policy(boolean allowPrivateAddresses) {
        return policy(allowPrivateAddresses, InetAddress::getAllByName);
    }

    private static DestinationPolicy policy(boolean allowPrivateAddresses, HostResolver resolver) {
        return new DestinationPolicy(
                new MonitoringProperties(Duration.ofSeconds(5), 50, allowPrivateAddresses), resolver);
    }

    /** A resolver that answers with whatever the test says the name maps to. */
    private static HostResolver resolvesTo(String... addresses) {
        return host -> {
            InetAddress[] resolved = new InetAddress[addresses.length];
            for (int i = 0; i < addresses.length; i++) {
                resolved[i] = InetAddress.getByName(addresses[i]);
            }
            return resolved;
        };
    }
}
