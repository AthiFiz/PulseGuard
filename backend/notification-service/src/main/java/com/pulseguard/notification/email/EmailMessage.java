package com.pulseguard.notification.email;

/**
 * A composed message, before it has a recipient.
 *
 * <p>The same subject and body go to everyone on a project, so composition
 * happens once per event rather than once per recipient.
 */
public record EmailMessage(String subject, String body) {
}
