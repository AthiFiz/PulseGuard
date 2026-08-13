package com.pulseguard.monitorworker.enums;

/**
 * What an outbox event is about.
 *
 * <p>One value today. It exists so a future event family — a monitor
 * configuration change, say — can share the outbox table and its publisher
 * without another migration, and so a consumer can tell the families apart
 * without parsing the payload.
 */
public enum OutboxAggregateType {

    INCIDENT
}
