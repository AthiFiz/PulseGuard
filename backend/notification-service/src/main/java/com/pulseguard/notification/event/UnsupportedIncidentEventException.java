package com.pulseguard.notification.event;

/**
 * The payload could not be read, or describes something this service does not
 * understand.
 *
 * <p>Thrown rather than swallowed. Silently skipping an event PulseGuard itself
 * published would hide a genuine contract break — and guessing at its meaning
 * could send somebody a wrong email.
 *
 * <p>Because there is no dead-letter topic yet, an event that can never be read
 * will be retried indefinitely and block its partition. That is a known Task 10
 * limitation, documented rather than papered over.
 */
public class UnsupportedIncidentEventException extends RuntimeException {

    public UnsupportedIncidentEventException(String message) {
        super(message);
    }
}
