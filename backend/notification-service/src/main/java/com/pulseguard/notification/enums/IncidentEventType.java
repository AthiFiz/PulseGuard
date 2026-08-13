package com.pulseguard.notification.enums;

/**
 * The incident lifecycle events this service understands.
 *
 * <p>Part of the published contract, so the names match the Monitor Worker's
 * {@code OutboxEventType} exactly. An event type outside this set is rejected
 * rather than guessed at — sending an email about an event nobody understands
 * would be worse than not sending one.
 */
public enum IncidentEventType {

    INCIDENT_OPENED,

    INCIDENT_RESOLVED
}
