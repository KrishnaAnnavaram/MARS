package com.example.migrationdemo.integration;

import com.example.migrationdemo.entity.Employee;
import com.example.migrationdemo.repository.EmployeeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MIGRATION-DEMO:
 * Integration test using Testcontainers for PostgreSQL.
 * This test demonstrates database compatibility and will be important
 * for validating database and Hibernate queries during Spring Boot 4 migration.
 *
 * Note: The class is skipped when no Docker environment is available
 * (disabledWithoutDocker), and @AutoConfigureTestDatabase(replace = NONE) keeps
 * @DataJpaTest from swapping the container in for an embedded database.
 */
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class EmployeeRepositoryIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("test_db")
            .withUsername("test_user")
            .withPassword("test_pass");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private EmployeeRepository employeeRepository;

    @BeforeEach
    void setUp() {
        employeeRepository.deleteAll();
    }

    @Test
    void testSaveEmployee() {
        Employee employee = new Employee(
                "EMP001",
                "John",
                "Doe",
                "john@example.com",
                "Technology",
                new BigDecimal("85000.00"));

        Employee savedEmployee = employeeRepository.save(employee);

        assertNotNull(savedEmployee.getId());
        assertEquals("EMP001", savedEmployee.getEmployeeNumber());
        assertEquals("john@example.com", savedEmployee.getEmail());
    }

    @Test
    void testFindByEmployeeNumber() {
        Employee employee = new Employee(
                "EMP002",
                "Jane",
                "Smith",
                "jane@example.com",
                "Finance",
                new BigDecimal("90000.00"));
        employeeRepository.save(employee);

        Optional<Employee> found = employeeRepository.findByEmployeeNumber("EMP002");

        assertTrue(found.isPresent());
        assertEquals("Jane", found.get().getFirstName());
    }

    @Test
    void testFindByDepartmentIgnoreCase() {
        Employee emp1 = new Employee("EMP001", "John", "Doe", "john@example.com", "Technology",
                new BigDecimal("85000.00"));
        Employee emp2 = new Employee("EMP002", "Jane", "Smith", "jane@example.com", "Technology",
                new BigDecimal("90000.00"));
        Employee emp3 = new Employee("EMP003", "Mike", "Brown", "mike@example.com", "Finance",
                new BigDecimal("80000.00"));

        employeeRepository.save(emp1);
        employeeRepository.save(emp2);
        employeeRepository.save(emp3);

        List<Employee> techEmployees = employeeRepository.findByDepartmentIgnoreCase("TECHNOLOGY");

        assertEquals(2, techEmployees.size());
    }

    @Test
    void testFindActiveEmployeesByDepartment() {
        Employee emp1 = new Employee("EMP001", "John", "Doe", "john@example.com", "Technology",
                new BigDecimal("85000.00"));
        emp1.setActive(true);

        Employee emp2 = new Employee("EMP002", "Jane", "Smith", "jane@example.com", "Technology",
                new BigDecimal("90000.00"));
        emp2.setActive(false);

        Employee emp3 = new Employee("EMP003", "Mike", "Brown", "mike@example.com", "Finance",
                new BigDecimal("80000.00"));
        emp3.setActive(true);

        employeeRepository.save(emp1);
        employeeRepository.save(emp2);
        employeeRepository.save(emp3);

        /**
         * MIGRATION-DEMO: JPQL query test
         * This test validates that the JPQL query works correctly
         */
        List<Employee> activeInTech = employeeRepository.findActiveEmployeesByDepartment("Technology");

        assertEquals(1, activeInTech.size());
        assertEquals("John", activeInTech.get(0).getFirstName());
    }

    @Test
    void testFindHighEarners() {
        Employee emp1 = new Employee("EMP001", "John", "Doe", "john@example.com", "Technology",
                new BigDecimal("95000.00"));
        emp1.setActive(true);

        Employee emp2 = new Employee("EMP002", "Jane", "Smith", "jane@example.com", "Finance",
                new BigDecimal("75000.00"));
        emp2.setActive(true);

        Employee emp3 = new Employee("EMP003", "Mike", "Brown", "mike@example.com", "Technology",
                new BigDecimal("88000.00"));
        emp3.setActive(true);

        employeeRepository.save(emp1);
        employeeRepository.save(emp2);
        employeeRepository.save(emp3);

        /**
         * MIGRATION-DEMO: Native PostgreSQL query test
         * This test validates that the native SQL query works correctly
         */
        List<Employee> highEarners = employeeRepository.findHighEarners(new BigDecimal("90000.00"));

        assertEquals(1, highEarners.size());
        assertEquals("John", highEarners.get(0).getFirstName());
    }

}
