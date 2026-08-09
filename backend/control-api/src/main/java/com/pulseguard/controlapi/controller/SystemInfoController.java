package com.pulseguard.controlapi.controller;

import com.pulseguard.controlapi.dto.SystemInfoResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/system")
public class SystemInfoController {

    private static final String APPLICATION_NAME = "PulseGuard Control API";

    @GetMapping("/info")
    public SystemInfoResponse getSystemInfo() {
        return new SystemInfoResponse(APPLICATION_NAME, "UP");
    }
}
