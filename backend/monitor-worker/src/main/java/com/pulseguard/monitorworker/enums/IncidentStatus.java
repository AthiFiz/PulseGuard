package com.pulseguard.monitorworker.enums;

/**
 * The lifecycle of one outage.
 *
 * <p>Only two states exist at this stage. Acknowledgement, investigation and
 * suppression are workflow concepts that need a user acting on an incident, and
 * nothing in PulseGuard offers that yet.
 *
 * <p>Stored as a string, so adding a state later is an application change
 * rather than a schema migration.
 */
public enum IncidentStatus {

    /** The outage is still ongoing: no successful check has arrived since. */
    OPEN,

    /** A successful check ended the outage. */
    RESOLVED
}
