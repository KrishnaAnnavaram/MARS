package com.example.migrationdemo.init;

import com.example.migrationdemo.entity.Employee;
import com.example.migrationdemo.repository.EmployeeRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class DataInitializer implements CommandLineRunner {

    private final EmployeeRepository employeeRepository;

    public DataInitializer(EmployeeRepository employeeRepository) {
        this.employeeRepository = employeeRepository;
    }

    @Override
    public void run(String... args) throws Exception {
        // Only initialize if table is empty
        if (employeeRepository.count() == 0) {
            initializeSampleData();
        }
    }

    private void initializeSampleData() {
        Employee emp1 = new Employee(
                "EMP001",
                "John",
                "Smith",
                "john.smith@example.com",
                "Technology",
                new BigDecimal("95000.00"));

        Employee emp2 = new Employee(
                "EMP002",
                "Sarah",
                "Johnson",
                "sarah.johnson@example.com",
                "Finance",
                new BigDecimal("85000.00"));

        Employee emp3 = new Employee(
                "EMP003",
                "Michael",
                "Brown",
                "michael.brown@example.com",
                "Technology",
                new BigDecimal("88000.00"));

        Employee emp4 = new Employee(
                "EMP004",
                "Emily",
                "Davis",
                "emily.davis@example.com",
                "Operations",
                new BigDecimal("72000.00"));

        Employee emp5 = new Employee(
                "EMP005",
                "James",
                "Wilson",
                "james.wilson@example.com",
                "HR",
                new BigDecimal("68000.00"));

        employeeRepository.save(emp1);
        employeeRepository.save(emp2);
        employeeRepository.save(emp3);
        employeeRepository.save(emp4);
        employeeRepository.save(emp5);
    }

}
