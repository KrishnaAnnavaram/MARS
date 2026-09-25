package com.example.migrationdemo.mapper;

import com.example.migrationdemo.dto.EmployeeCreateRequest;
import com.example.migrationdemo.dto.EmployeeResponse;
import com.example.migrationdemo.entity.Employee;
import org.springframework.stereotype.Component;

@Component
public class EmployeeMapper {

    public Employee toEntity(EmployeeCreateRequest request) {
        return new Employee(
                request.getEmployeeNumber(),
                request.getFirstName(),
                request.getLastName(),
                request.getEmail(),
                request.getDepartment(),
                request.getSalary());
    }

    public EmployeeResponse toResponse(Employee employee) {
        return new EmployeeResponse(
                employee.getId(),
                employee.getEmployeeNumber(),
                employee.getFirstName(),
                employee.getLastName(),
                employee.getEmail(),
                employee.getDepartment(),
                employee.getSalary(),
                employee.getActive(),
                employee.getCreatedAt(),
                employee.getUpdatedAt());
    }

    public void updateEntityFromRequest(EmployeeResponse response, Employee employee) {
        if (response.getFirstName() != null) {
            employee.setFirstName(response.getFirstName());
        }
        if (response.getLastName() != null) {
            employee.setLastName(response.getLastName());
        }
        if (response.getEmail() != null) {
            employee.setEmail(response.getEmail());
        }
        if (response.getDepartment() != null) {
            employee.setDepartment(response.getDepartment());
        }
        if (response.getSalary() != null) {
            employee.setSalary(response.getSalary());
        }
        if (response.getActive() != null) {
            employee.setActive(response.getActive());
        }
    }

}
