package com.pulseguard.controlapi.enums;

/**
 * The lifecycle of one outage.
 *
 * <p>Written entirely by the Monitor Worker: the Control API reads incidents
 * but never opens or closes one, because only an observed check can say whether
 * a service is down.
 */
public enum IncidentStatus {

    /** The outage is still ongoing: no successful check has arrived since. */
    OPEN,

    /** A successful check ended the outage. */
    RESOLVED
}
