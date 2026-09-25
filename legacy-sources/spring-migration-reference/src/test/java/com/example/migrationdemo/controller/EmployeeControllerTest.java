package com.example.migrationdemo.controller;

import com.example.migrationdemo.config.SecurityConfig;
import com.example.migrationdemo.dto.EmployeeCreateRequest;
import com.example.migrationdemo.dto.EmployeeResponse;
import com.example.migrationdemo.exception.EmployeeNotFoundException;
import com.example.migrationdemo.service.EmployeeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(EmployeeController.class)
@Import(SecurityConfig.class)
class EmployeeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private EmployeeService employeeService;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @WithMockUser
    void testGetAllEmployees_Success() throws Exception {
        mockMvc.perform(get("/api/v1/employees"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON));
    }

    @Test
    void testGetAllEmployees_Unauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/employees"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void testGetAllEmployees_WithBasicAuth() throws Exception {
        mockMvc.perform(get("/api/v1/employees")
                .with(httpBasic("demo", "demo123")))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser
    void testGetEmployeeById_Success() throws Exception {
        EmployeeResponse response = new EmployeeResponse(
                1L,
                "EMP001",
                "John",
                "Doe",
                "john@example.com",
                "Technology",
                new BigDecimal("85000.00"),
                true,
                LocalDateTime.now(),
                LocalDateTime.now());

        when(employeeService.getEmployeeById(1L)).thenReturn(response);

        mockMvc.perform(get("/api/v1/employees/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.employeeNumber").value("EMP001"));
    }

    @Test
    @WithMockUser
    void testGetEmployeeById_NotFound() throws Exception {
        when(employeeService.getEmployeeById(999L))
                .thenThrow(new EmployeeNotFoundException(999L));

        mockMvc.perform(get("/api/v1/employees/999"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser
    void testCreateEmployee_Success() throws Exception {
        EmployeeCreateRequest request = new EmployeeCreateRequest(
                "EMP001",
                "John",
                "Doe",
                "john@example.com",
                "Technology",
                new BigDecimal("85000.00"));

        EmployeeResponse response = new EmployeeResponse(
                1L,
                "EMP001",
                "John",
                "Doe",
                "john@example.com",
                "Technology",
                new BigDecimal("85000.00"),
                true,
                LocalDateTime.now(),
                LocalDateTime.now());

        when(employeeService.createEmployee(any())).thenReturn(response);

        mockMvc.perform(post("/api/v1/employees")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1));
    }

    @Test
    @WithMockUser
    void testCreateEmployee_InvalidRequest() throws Exception {
        EmployeeCreateRequest request = new EmployeeCreateRequest(
                "", // Empty employee number
                "John",
                "Doe",
                "invalid-email", // Invalid email
                "Technology",
                new BigDecimal("85000.00"));

        mockMvc.perform(post("/api/v1/employees")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

}
