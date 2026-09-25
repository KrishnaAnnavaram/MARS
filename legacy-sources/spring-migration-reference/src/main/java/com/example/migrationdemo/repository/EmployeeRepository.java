package com.example.migrationdemo.repository;

import com.example.migrationdemo.entity.Employee;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Repository
public interface EmployeeRepository extends JpaRepository<Employee, Long> {

    Optional<Employee> findByEmployeeNumber(String employeeNumber);

    List<Employee> findByDepartmentIgnoreCase(String department);

    /**
     * MIGRATION-DEMO: JPQL query that will be validated for Hibernate compatibility
     * during Spring Boot 4 migration.
     */
    @Query("""
            select e
            from Employee e
            where lower(e.department) = lower(:department)
            and e.active = true
            """)
    List<Employee> findActiveEmployeesByDepartment(
            @Param("department") String department);

    /**
     * MIGRATION-DEMO: Native PostgreSQL query that will be validated for
     * database compatibility during Spring Boot 4 migration.
     */
    @Query(value = """
            select *
            from employees
            where salary > :salary
            and active = true
            order by salary desc
            """, nativeQuery = true)
    List<Employee> findHighEarners(
            @Param("salary") BigDecimal salary);

}
