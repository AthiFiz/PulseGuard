package com.pulseguard.notification.recipient;

/**
 * Someone who should be told about an incident.
 *
 * @param email where to send it
 * @param displayName used to address them; never used for routing
 */
public record Recipient(String email, String displayName) {
}
