package com.example.migrationdemo.service;

import com.example.migrationdemo.dto.EmployeeCreateRequest;
import com.example.migrationdemo.dto.EmployeeResponse;
import com.example.migrationdemo.entity.Employee;
import com.example.migrationdemo.exception.DuplicateEmployeeException;
import com.example.migrationdemo.exception.EmployeeNotFoundException;
import com.example.migrationdemo.mapper.EmployeeMapper;
import com.example.migrationdemo.repository.EmployeeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EmployeeServiceTest {

    @Mock
    private EmployeeRepository employeeRepository;

    private EmployeeMapper employeeMapper;
    private EmployeeService employeeService;

    @BeforeEach
    void setUp() {
        employeeMapper = new EmployeeMapper();
        employeeService = new EmployeeService(employeeRepository, employeeMapper);
    }

    @Test
    void testGetEmployeeById_Success() {
        Long employeeId = 1L;
        Employee employee = new Employee(
                "EMP001",
                "John",
                "Doe",
                "john@example.com",
                "Technology",
                new BigDecimal("85000.00"));
        employee.setId(employeeId);

        when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employee));

        EmployeeResponse response = employeeService.getEmployeeById(employeeId);

        assertNotNull(response);
        assertEquals("EMP001", response.getEmployeeNumber());
        verify(employeeRepository, times(1)).findById(employeeId);
    }

    @Test
    void testGetEmployeeById_NotFound() {
        Long employeeId = 999L;
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.empty());

        assertThrows(EmployeeNotFoundException.class, () -> {
            employeeService.getEmployeeById(employeeId);
        });
    }

    @Test
    void testCreateEmployee_DuplicateEmployeeNumber() {
        EmployeeCreateRequest request = new EmployeeCreateRequest(
                "EMP001",
                "John",
                "Doe",
                "john@example.com",
                "Technology",
                new BigDecimal("85000.00"));

        when(employeeRepository.findByEmployeeNumber("EMP001"))
                .thenReturn(Optional.of(new Employee()));

        assertThrows(DuplicateEmployeeException.class, () -> {
            employeeService.createEmployee(request);
        });
    }

}
