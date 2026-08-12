package com.pulseguard.monitorworker.monitoring;

/**
 * The outcome of checking a monitor's URL before any request is made.
 *
 * @param verdict whether the request may proceed
 * @param reason a short, safe explanation suitable for storing on a failed check
 */
public record DestinationDecision(Verdict verdict, String reason) {

    public enum Verdict {
        /** Safe to request. */
        ALLOWED,
        /** Refused by the destination policy — no request is made. */
        BLOCKED,
        /** The host name could not be resolved, so there is nothing to request. */
        UNRESOLVABLE
    }

    public static DestinationDecision allowed() {
        return new DestinationDecision(Verdict.ALLOWED, null);
    }

    public static DestinationDecision blocked(String reason) {
        return new DestinationDecision(Verdict.BLOCKED, reason);
    }

    public static DestinationDecision unresolvable(String reason) {
        return new DestinationDecision(Verdict.UNRESOLVABLE, reason);
    }

    public boolean isAllowed() {
        return verdict == Verdict.ALLOWED;
    }
}
