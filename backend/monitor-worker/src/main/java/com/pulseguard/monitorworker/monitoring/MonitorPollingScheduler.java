package com.pulseguard.monitorworker.monitoring;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class MonitorPollingScheduler {

    private final MonitorPollingService monitorPollingService;

    @Scheduled(
            fixedDelayString = "${app.monitoring.poll-interval}",
            initialDelayString = "${app.monitoring.poll-interval}")
    public void pollDueMonitors() {
        try {
            monitorPollingService.pollOnce();
        } catch (Exception ex) {
            log.error("Polling cycle failed; will retry on the next tick", ex);
        }
    }
}
