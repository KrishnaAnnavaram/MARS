package com.example.migrationdemo.actuator;

import com.example.migrationdemo.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * MIGRATION-DEMO:
 * Actuator endpoints are contributed by auto-configuration, not by a controller,
 * so they are not registered inside a @WebMvcTest slice. These checks load the
 * full application context to verify that /actuator/health and /actuator/info
 * remain publicly accessible under SecurityConfig.
 */
@AutoConfigureMockMvc
class ActuatorEndpointsTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void testActuatorHealth_Public() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void testActuatorInfo_Public() throws Exception {
        mockMvc.perform(get("/actuator/info"))
                .andExpect(status().isOk());
    }

}
