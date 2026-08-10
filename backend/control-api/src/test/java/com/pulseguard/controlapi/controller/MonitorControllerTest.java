package com.pulseguard.controlapi.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pulseguard.controlapi.dto.monitor.MonitorResponse;
import com.pulseguard.controlapi.enums.MonitorHttpMethod;
import com.pulseguard.controlapi.enums.MonitorStatus;
import com.pulseguard.controlapi.exception.ApiException;
import com.pulseguard.controlapi.service.MonitorService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Request mapping, status codes, and request validation for the monitor
 * endpoints. Security filters are disabled here so these stay focused;
 * {@code MonitorSecurityRulesTest} covers the authentication requirement.
 */
@WebMvcTest({MonitorController.class, ProjectMonitorController.class})
@AutoConfigureMockMvc(addFilters = false)
class MonitorControllerTest {

    private static final String VALID_BODY = """
            {
              "name": "Payment API",
              "description": "Payment health endpoint",
              "url": "https://api.example.com/health",
              "httpMethod": "GET",
              "expectedStatusCode": 200,
              "intervalSeconds": 60,
              "timeoutSeconds": 5,
              "failureThreshold": 3
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MonitorService monitorService;

    @Test
    void createReturnsCreatedWithTheMonitor() throws Exception {
        Mockito.when(monitorService.createMonitor(eq(10L), any())).thenReturn(sampleMonitor());

        mockMvc.perform(post("/api/v1/projects/10/monitors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(25))
                .andExpect(jsonPath("$.projectId").value(10))
                .andExpect(jsonPath("$.currentStatus").value("UNKNOWN"))
                .andExpect(jsonPath("$.lastCheckedAt").doesNotExist())
                .andExpect(jsonPath("$.nextCheckAt").exists());
    }

    @Test
    void listReturnsOk() throws Exception {
        Mockito.when(monitorService.listMonitors(10L)).thenReturn(List.of(sampleMonitor()));

        mockMvc.perform(get("/api/v1/projects/10/monitors"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Payment API"));
    }

    @Test
    void anEmptyProjectListsAsAnEmptyArrayNotANotFound() throws Exception {
        Mockito.when(monitorService.listMonitors(10L)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/projects/10/monitors"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void getReturnsOk() throws Exception {
        Mockito.when(monitorService.getMonitor(25L)).thenReturn(sampleMonitor());

        mockMvc.perform(get("/api/v1/monitors/25"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(25));
    }

    @Test
    void getAnUnknownMonitorReturnsNotFound() throws Exception {
        Mockito.when(monitorService.getMonitor(999L)).thenThrow(ApiException.monitorNotFound());

        mockMvc.perform(get("/api/v1/monitors/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MONITOR_NOT_FOUND"));
    }

    @Test
    void updateReturnsOk() throws Exception {
        Mockito.when(monitorService.updateMonitor(eq(25L), any())).thenReturn(sampleMonitor());

        mockMvc.perform(put("/api/v1/monitors/25")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isOk());
    }

    @Test
    void pauseReturnsOk() throws Exception {
        Mockito.when(monitorService.pauseMonitor(25L)).thenReturn(sampleMonitor());

        mockMvc.perform(post("/api/v1/monitors/25/pause")).andExpect(status().isOk());
    }

    @Test
    void resumeReturnsOk() throws Exception {
        Mockito.when(monitorService.resumeMonitor(25L)).thenReturn(sampleMonitor());

        mockMvc.perform(post("/api/v1/monitors/25/resume")).andExpect(status().isOk());
    }

    @Test
    void deleteReturnsNoContent() throws Exception {
        mockMvc.perform(delete("/api/v1/monitors/25")).andExpect(status().isNoContent());
        Mockito.verify(monitorService).deleteMonitor(25L);
    }

    /** Only GET is supported, so the enum has one constant and anything else fails to parse. */
    @Test
    void anUnsupportedHttpMethodIsRejectedAsBadRequestNotServerError() throws Exception {
        mockMvc.perform(post("/api/v1/projects/10/monitors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY.replace("\"GET\"", "\"POST\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void anUnparseableHttpMethodIsRejectedAsBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/projects/10/monitors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY.replace("\"GET\"", "\"NOT_A_METHOD\"")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aBlankNameIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/projects/10/monitors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY.replace("\"Payment API\"", "\"\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void anOutOfRangeExpectedStatusIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/projects/10/monitors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY.replace("\"expectedStatusCode\": 200", "\"expectedStatusCode\": 99")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void anIntervalBelowTheMinimumIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/projects/10/monitors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY.replace("\"intervalSeconds\": 60", "\"intervalSeconds\": 5")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aZeroTimeoutIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/projects/10/monitors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY.replace("\"timeoutSeconds\": 5", "\"timeoutSeconds\": 0")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aZeroFailureThresholdIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/projects/10/monitors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY.replace("\"failureThreshold\": 3", "\"failureThreshold\": 0")))
                .andExpect(status().isBadRequest());
    }

    /** The service raises this one, since it compares two fields. */
    @Test
    void aTimeoutLongerThanTheIntervalSurfacesAsAMonitorValidationError() throws Exception {
        Mockito.when(monitorService.createMonitor(eq(10L), any()))
                .thenThrow(ApiException.monitorValidation("Timeout must be shorter than the interval"));

        mockMvc.perform(post("/api/v1/projects/10/monitors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MONITOR_VALIDATION_ERROR"));
    }

    private static MonitorResponse sampleMonitor() {
        return new MonitorResponse(
                25L, 10L, "Payment API", "Payment health endpoint",
                "https://api.example.com/health", MonitorHttpMethod.GET,
                200, 60, 5, 3, 0, MonitorStatus.UNKNOWN,
                null, Instant.parse("2026-08-10T12:00:00Z"),
                Instant.parse("2026-08-10T12:00:00Z"), Instant.parse("2026-08-10T12:00:00Z"));
    }
}
