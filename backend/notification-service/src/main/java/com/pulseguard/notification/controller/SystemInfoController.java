package com.pulseguard.notification.controller;

import com.pulseguard.notification.dto.SystemInfoResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The service's only HTTP surface, alongside the actuator health endpoint.
 *
 * <p>There is deliberately no API for notifications, deliveries or retries:
 * this application is not user-facing, and delivery state is inspected in the
 * database.
 */
@RestController
@RequestMapping("/api/v1/system")
public class SystemInfoController {

    private static final String APPLICATION_NAME = "PulseGuard Notification Service";

    @GetMapping("/info")
    public SystemInfoResponse getSystemInfo() {
        return new SystemInfoResponse(APPLICATION_NAME, "UP");
    }
}
