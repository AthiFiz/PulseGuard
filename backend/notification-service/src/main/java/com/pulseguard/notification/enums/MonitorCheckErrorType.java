package com.pulseguard.notification.enums;

/** Why a check failed, as carried in an incident event. */
public enum MonitorCheckErrorType {
    TIMEOUT,
    CONNECTION_ERROR,
    DNS_ERROR,
    UNEXPECTED_STATUS,
    BLOCKED_ADDRESS,
    UNKNOWN
}
