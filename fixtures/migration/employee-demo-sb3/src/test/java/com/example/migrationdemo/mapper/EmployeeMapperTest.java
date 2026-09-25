package com.example.migrationdemo.mapper;

import com.example.migrationdemo.dto.EmployeeCreateRequest;
import com.example.migrationdemo.dto.EmployeeResponse;
import com.example.migrationdemo.entity.Employee;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class EmployeeMapperTest {

    private EmployeeMapper employeeMapper;

    @BeforeEach
    void setUp() {
        employeeMapper = new EmployeeMapper();
    }

    @Test
    void testToEntity() {
        EmployeeCreateRequest request = new EmployeeCreateRequest(
                "EMP001",
                "John",
                "Doe",
                "john@example.com",
                "Technology",
                new BigDecimal("85000.00"));

        Employee employee = employeeMapper.toEntity(request);

        assertEquals("EMP001", employee.getEmployeeNumber());
        assertEquals("John", employee.getFirstName());
        assertEquals("Doe", employee.getLastName());
        assertEquals("john@example.com", employee.getEmail());
        assertEquals("Technology", employee.getDepartment());
        assertEquals(new BigDecimal("85000.00"), employee.getSalary());
    }

    @Test
    void testToResponse() {
        Employee employee = new Employee(
                "EMP002",
                "Jane",
                "Smith",
                "jane@example.com",
                "Finance",
                new BigDecimal("90000.00"));

        EmployeeResponse response = employeeMapper.toResponse(employee);

        assertEquals("EMP002", response.getEmployeeNumber());
        assertEquals("Jane", response.getFirstName());
        assertEquals("Smith", response.getLastName());
        assertEquals("jane@example.com", response.getEmail());
        assertEquals("Finance", response.getDepartment());
        assertEquals(new BigDecimal("90000.00"), response.getSalary());
    }

}
