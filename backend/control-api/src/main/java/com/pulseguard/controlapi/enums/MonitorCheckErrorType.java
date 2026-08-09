package com.pulseguard.controlapi.enums;

/** Classification of why a monitor check failed. */
public enum MonitorCheckErrorType {
    TIMEOUT,
    CONNECTION_ERROR,
    UNEXPECTED_STATUS,
    DNS_ERROR,
    UNKNOWN
}
