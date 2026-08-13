package com.pulseguard.notification.dto;

/** Mirrors the other two applications so all three can be checked the same way. */
public record SystemInfoResponse(String application, String status) {
}
