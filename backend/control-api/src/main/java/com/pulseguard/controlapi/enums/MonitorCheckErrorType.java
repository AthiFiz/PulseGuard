package com.pulseguard.controlapi.enums;

/** Classification of why a monitor check failed. */
public enum MonitorCheckErrorType {
    TIMEOUT,
    CONNECTION_ERROR,
    UNEXPECTED_STATUS,
    DNS_ERROR,
    /** The Monitor Worker refused the destination under its SSRF policy. */
    BLOCKED_ADDRESS,
    UNKNOWN
}
