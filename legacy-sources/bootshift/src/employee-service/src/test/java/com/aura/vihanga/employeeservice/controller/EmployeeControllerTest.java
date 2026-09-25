package com.aura.vihanga.employeeservice.controller;

import com.aura.vihanga.employeeservice.dto.EmployeeResponse;
import com.aura.vihanga.employeeservice.exception.EmployeeNotFoundException;
import com.aura.vihanga.employeeservice.model.Employee;
import com.aura.vihanga.employeeservice.service.EmployeeService;
import com.aura.vihanga.employeeservice.utill.EmployeeType;
import com.aura.vihanga.employeeservice.utill.GenderType;
import com.aura.vihanga.employeeservice.utill.StandardResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class EmployeeControllerTest {

    @Autowired
    private EmployeeController employeeController;

    @MockBean
    private EmployeeService employeeService;

    private Employee employee;

    @BeforeEach
    void setUp() throws EmployeeNotFoundException {
        String employeeId = "EMP01";

        employee = Employee.builder()
                .employeeId("EMP01")
                .name("Nadun")
                .department("DEP02")
                .phoneNo("0746546546")
                .phoneNo("AAAAA")
                .gender(GenderType.MALE)
                .employeeType(EmployeeType.PERMANENT)
                .build();

        EmployeeResponse employeeResponse = EmployeeResponse.builder()
                .employeeId("EMP01")
                .name("Nadun")
                .department("DEP02")
                .phoneNo("0746546546")
                .phoneNo("AAAAA")
                .gender(GenderType.MALE)
                .employeeType(EmployeeType.PERMANENT)
                .build();

        Mockito.when(employeeService.createEmployee(employee)).thenReturn(employeeResponse);
        Mockito.when(employeeService.getEmployee(employeeId)).thenReturn(employeeResponse);
    }

    @Test
    @DisplayName("Unit Testing For Create Employee Controller")
    void createEmployee() {
        ResponseEntity<StandardResponse> createdEmployee  = employeeController.createEmployee(employee);
        assertEquals(201,createdEmployee.getStatusCodeValue());
    }

    @Test
    @DisplayName("Unit Testing For Get Employee Controller")
    void getEmployee() throws EmployeeNotFoundException {
        String employeeId = "EMP01";
        ResponseEntity<StandardResponse> getEmployee = employeeController.getEmployee(employeeId);
        assertEquals(200, getEmployee.getBody().getCode());
    }
}