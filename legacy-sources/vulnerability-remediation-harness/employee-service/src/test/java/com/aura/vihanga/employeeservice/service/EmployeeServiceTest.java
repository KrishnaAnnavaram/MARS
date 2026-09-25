package com.aura.vihanga.employeeservice.service;

import com.aura.vihanga.employeeservice.dto.DepartmentResponse;
import com.aura.vihanga.employeeservice.dto.EmployeeResponse;
import com.aura.vihanga.employeeservice.exception.EmployeeNotFoundException;
import com.aura.vihanga.employeeservice.model.Employee;
import com.aura.vihanga.employeeservice.repository.EmployeeRepository;
import com.aura.vihanga.employeeservice.utill.EmployeeType;
import com.aura.vihanga.employeeservice.utill.GenderType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class EmployeeServiceTest {
    @Autowired
    private EmployeeService employeeService;

    @MockBean
    private EmployeeRepository employeeRepository;

    DepartmentResponse departmentResponse;

    @BeforeEach
    void setUp() {

        Employee employee = Employee.builder()
                .employeeId("EMP01")
                .name("Nadun")
                .department("DEP02")
                .phoneNo("0746546546")
                .phoneNo("AAAAA")
                .gender(GenderType.MALE)
                .employeeType(EmployeeType.PERMANENT)
                .build();

        departmentResponse = DepartmentResponse.builder()
                .departmentId("DEP02")
                .departmentName("IT Department")
                .salary("50000.00")
                .build();

        Mockito.when(employeeRepository.findById("EMP01")).thenReturn(Optional.ofNullable(employee));
        Mockito.when(employeeRepository.save(employee)).thenReturn(employee);

    }

    @Test
    @DisplayName("Create Employee Method Validate in Service Layer")
    void createEmployee() {
        Employee employee = Employee.builder()
                .employeeId("EMP01")
                .name("Nadun")
                .department("DEP02")
                .phoneNo("0746546546")
                .phoneNo("AAAAA")
                .gender(GenderType.MALE)
                .employeeType(EmployeeType.PERMANENT)
                .build();

        EmployeeResponse savedEmployee = employeeService.createEmployee(employee);
        assertEquals(employee.getEmployeeId(), savedEmployee.getEmployeeId());
    }

    @Test
    @DisplayName("Get Employee Method Validate in Service Layer")
    void getEmployee() throws EmployeeNotFoundException {
        String employeeId = "EMP01";

        EmployeeResponse employeeDetails = employeeService.getEmployee(employeeId);

        assertEquals(employeeId, employeeDetails.getEmployeeId());
    }
}