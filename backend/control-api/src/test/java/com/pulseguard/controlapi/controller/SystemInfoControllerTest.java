package com.pulseguard.controlapi.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Covers request mapping and the response body only.
 *
 * <p>The security filters are disabled here so this stays a focused controller
 * test; that this endpoint is publicly reachable through the real filter chain
 * is asserted by {@code SecurityRulesTest}.
 */
@WebMvcTest(SystemInfoController.class)
@AutoConfigureMockMvc(addFilters = false)
class SystemInfoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void returnsApplicationNameAndStatus() throws Exception {
        mockMvc.perform(get("/api/v1/system/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.application").value("PulseGuard Control API"))
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
