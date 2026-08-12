package com.pulseguard.monitorworker.enums;

/**
 * Must stay value-compatible with the Control API's enum of the same name —
 * both applications read and write the same VARCHAR column.
 */
public enum MonitorStatus {
    UNKNOWN,
    UP,
    DOWN,
    PAUSED
}
