package com.pulseguard.monitorworker.enums;

/**
 * The event types PulseGuard publishes.
 *
 * <p>Only incident lifecycle transitions are published — deliberately not
 * individual checks. A monitor on a 30-second interval produces thousands of
 * checks a day and almost none of them are news; an outage beginning or ending
 * always is.
 */
public enum OutboxEventType {

    /** A monitor reached its failure threshold and an incident was opened. */
    INCIDENT_OPENED,

    /** A successful check ended the outage and resolved the incident. */
    INCIDENT_RESOLVED
}
