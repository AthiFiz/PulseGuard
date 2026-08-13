package com.pulseguard.notification.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SystemInfoController.class)
class SystemInfoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void systemInfoIdentifiesTheApplication() throws Exception {
        mockMvc.perform(get("/api/v1/system/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.application").value("PulseGuard Notification Service"))
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
