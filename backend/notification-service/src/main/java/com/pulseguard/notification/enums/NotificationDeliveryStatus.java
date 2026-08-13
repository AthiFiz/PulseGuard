package com.pulseguard.notification.enums;

/**
 * Where a delivery has got to.
 *
 * <p>Three states, no more. Anything else would need a distinction the service
 * cannot currently observe.
 */
public enum NotificationDeliveryStatus {

    /** Waiting for its first attempt, or for a retry after a failure. */
    PENDING,

    /** The mail server accepted the message. */
    SENT,

    /** Every configured attempt was used up. Kept for inspection, never retried. */
    FAILED
}
