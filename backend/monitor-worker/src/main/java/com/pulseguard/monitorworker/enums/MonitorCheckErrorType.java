package com.pulseguard.monitorworker.enums;

/**
 * Why a check failed.
 *
 * <p>{@code BLOCKED_ADDRESS} is recorded when the destination security policy
 * refuses the target before any request is made. The same constant exists in
 * the Control API so both applications can read the stored value.
 */
public enum MonitorCheckErrorType {
    TIMEOUT,
    CONNECTION_ERROR,
    UNEXPECTED_STATUS,
    DNS_ERROR,
    BLOCKED_ADDRESS,
    UNKNOWN
}
