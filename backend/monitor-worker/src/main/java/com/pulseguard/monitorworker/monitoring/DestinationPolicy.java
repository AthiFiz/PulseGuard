package com.pulseguard.monitorworker.monitoring;

import com.pulseguard.monitorworker.config.MonitoringProperties;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Decides whether a monitor's URL may actually be requested.
 *
 * <p>PulseGuard sends HTTP requests to addresses supplied by its users, which
 * makes it a potential server-side request forgery tool: anyone who can create
 * a monitor could otherwise point the worker at internal services it can reach
 * and the user cannot.
 *
 * <p>The check runs immediately before each request rather than when the
 * monitor was saved, because the answer can change — a host name resolves
 * differently over time, and the policy itself is configurable.
 *
 * <p>Judgements are made on the <em>resolved addresses</em>, never on the host
 * string. Blocking the literal text "localhost" would be trivially defeated by
 * {@code 127.0.0.1}, {@code [::1]}, {@code 2130706433}, or any name pointed at
 * a loopback address.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DestinationPolicy {

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    /**
     * Addresses that are never permitted, regardless of configuration.
     *
     * <p>The cloud metadata endpoint hands out instance credentials to anything
     * that can reach it, so it stays blocked even when private addresses are
     * enabled for local development.
     */
    private static final Set<String> ALWAYS_BLOCKED = Set.of(
            "169.254.169.254",  // AWS, Azure, DigitalOcean, OpenStack
            "fd00:ec2::254",    // AWS IPv6 metadata
            "100.100.100.200"); // Alibaba Cloud

    private final MonitoringProperties monitoringProperties;

    /**
     * @return whether the URL may be requested, and if not, a message safe to
     *     store against a failed check
     */
    public DestinationDecision evaluate(String url) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException ex) {
            return DestinationDecision.blocked("Monitor URL is not a valid URI");
        }

        String scheme = uri.getScheme();
        if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT))) {
            return DestinationDecision.blocked("Only http and https destinations are permitted");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return DestinationDecision.blocked("Monitor URL has no host");
        }

        InetAddress[] addresses;
        try {
            // Every address the name resolves to is examined. A host that
            // returns one public and one private address must not be allowed
            // through on the strength of the public one.
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException ex) {
            return DestinationDecision.unresolvable("DNS resolution failed");
        }

        for (InetAddress address : addresses) {
            String rejection = rejectionReasonFor(address);
            if (rejection != null) {
                log.warn("Destination blocked: host={}, reason={}", host, rejection);
                return DestinationDecision.blocked(rejection);
            }
        }

        return DestinationDecision.allowed();
    }

    /** @return why this address is refused, or null if it is acceptable */
    private String rejectionReasonFor(InetAddress address) {
        if (ALWAYS_BLOCKED.contains(address.getHostAddress())) {
            return "Destination blocked by monitoring security policy: cloud metadata endpoint";
        }

        // Multicast is never a sensible monitoring target and is blocked even
        // in development.
        if (address.isMulticastAddress()) {
            return "Destination blocked by monitoring security policy: multicast address";
        }

        if (monitoringProperties.allowPrivateAddresses()) {
            return null;
        }

//        The worker's own machine — its actuator endpoints, sibling containers
        if (address.isLoopbackAddress()) {
            return "Destination blocked by monitoring security policy: loopback address";
        }

//        0.0.0.0, ::	Wildcard; on many stacks routes to localhost
        if (address.isAnyLocalAddress()) {
            return "Destination blocked by monitoring security policy: wildcard address";
        }

//        The whole metadata neighbourhood
        if (address.isLinkLocalAddress()) {
            return "Destination blocked by monitoring security policy: link-local address";
        }

//        Your entire private network — databases, admin panels
        if (address.isSiteLocalAddress()) {
            // Covers IPv4 10/8, 172.16/12 and 192.168/16.
            return "Destination blocked by monitoring security policy: private address";
        }

//        ISP-internal shared space, not publicly routable
        if (isCarrierGradeNat(address)) {
            return "Destination blocked by monitoring security policy: shared address space";
        }

//        The IPv6 equivalent of a private range
        if (isUniqueLocalIpv6(address)) {
            // isSiteLocalAddress() only recognises the deprecated fec0::/10
            // range, so fc00::/7 has to be detected here.
            return "Destination blocked by monitoring security policy: unique-local address";
        }

        return null;
    }

    /** 100.64.0.0/10, reserved for carrier-grade NAT and not publicly routable. */
    private static boolean isCarrierGradeNat(InetAddress address) {
        if (!(address instanceof Inet4Address)) {
            return false;
        }
        byte[] octets = address.getAddress();
        return (octets[0] & 0xFF) == 100 && (octets[1] & 0xC0) == 64;
    }

    /** fc00::/7 — the IPv6 equivalent of a private range. */
    private static boolean isUniqueLocalIpv6(InetAddress address) {
        if (!(address instanceof Inet6Address)) {
            return false;
        }
        return (address.getAddress()[0] & 0xFE) == 0xFC;
    }
}
