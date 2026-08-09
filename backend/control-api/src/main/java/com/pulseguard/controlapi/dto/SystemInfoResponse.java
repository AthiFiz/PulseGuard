package com.pulseguard.controlapi.dto;

/**
 * Minimal response describing the running application.
 *
 * <p>This carries no business data. It exists so the frontend can verify
 * connectivity with the Control API and so the REST conventions used by later
 * stages are established early.
 */
public record SystemInfoResponse(String application, String status) {
}
