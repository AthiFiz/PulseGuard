package com.pulseguard.monitorworker.controller;

import com.pulseguard.monitorworker.dto.SystemInfoResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/system")
public class SystemInfoController {

    private static final String APPLICATION_NAME = "PulseGuard Monitor Worker";

    @GetMapping("/info")
    public SystemInfoResponse getSystemInfo() {
        return new SystemInfoResponse(APPLICATION_NAME, "UP");
    }
}
