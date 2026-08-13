package com.pulseguard.notification.enums;

/**
 * How a notification is delivered.
 *
 * <p>Email only. Slack, SMS and webhooks are deliberately absent rather than
 * stubbed: an unimplemented enum constant is a promise the code does not keep.
 */
public enum NotificationChannel {

    EMAIL
}
