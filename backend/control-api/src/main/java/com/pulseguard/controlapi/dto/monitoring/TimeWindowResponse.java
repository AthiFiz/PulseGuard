package com.pulseguard.controlapi.dto.monitoring;

import java.time.Instant;

/**
 * The range a set of figures was calculated over.
 *
 * <p>Echoed back so a client is never guessing which window it is looking at —
 * particularly for the dashboard, where the server picks a default.
 */
public record TimeWindowResponse(Instant from, Instant to) {
}
