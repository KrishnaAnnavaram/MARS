package com.aura.vihanga.employeeservice.repository;

import com.aura.vihanga.employeeservice.model.Employee;
import com.aura.vihanga.employeeservice.utill.EmployeeType;
import com.aura.vihanga.employeeservice.utill.GenderType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@RunWith(SpringRunner.class)
@DataMongoTest
class EmployeeRepositoryTest {

    @Autowired
    private EmployeeRepository employeeRepository;

    private Employee employee;

    @BeforeEach
    void setUp() {
        employee = Employee.builder()
                .employeeId("EMP01")
                .name("Nadun")
                .department("DEP02")
                .phoneNo("0746546546")
                .phoneNo("AAAAA")
                .gender(GenderType.MALE)
                .employeeType(EmployeeType.PERMANENT)
                .build();
    }

    @Test
    @DisplayName("Unit Test For Save Employee Method")
    public void employeeSaveMethodTest() {
        Employee savedEmployee = employeeRepository.save(employee);
        assertNotNull(savedEmployee);
        assertEquals(employee.getEmployeeId(), savedEmployee.getEmployeeId());
    }

    @Test
    @DisplayName("Unit Test For Get Employee Method")
    public void getEmployeeById() {
        String employeeId = "EMP01";

        Optional<Employee> getEmployee = employeeRepository.findById(employeeId);
        assertEquals(employeeId, getEmployee.get().getEmployeeId());
    }
}