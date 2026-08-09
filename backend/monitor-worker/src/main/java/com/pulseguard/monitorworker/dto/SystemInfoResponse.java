package com.pulseguard.monitorworker.dto;

/**
 * Minimal response describing the running application.
 *
 * <p>Mirrors the Control API endpoint so both applications can be checked the
 * same way. The worker carries no monitoring logic yet.
 */
public record SystemInfoResponse(String application, String status) {
}
