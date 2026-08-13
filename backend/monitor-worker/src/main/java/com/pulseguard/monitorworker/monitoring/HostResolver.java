package com.pulseguard.monitorworker.monitoring;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Turns a host name into every address it stands for.
 *
 * <p>A seam over {@link InetAddress#getAllByName(String)}, which is static and
 * therefore untestable. The rule that matters most here — a name resolving to
 * one public and one private address must be refused entirely — cannot be
 * demonstrated with IP literals, and depending on real DNS would make the test
 * depend on somebody else's zone file.
 *
 * <p>Production uses the real resolver; only tests substitute anything else.
 */
@FunctionalInterface
public interface HostResolver {

    InetAddress[] resolve(String host) throws UnknownHostException;
}
