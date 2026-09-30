package com.aura.vihanga.employeeservice.service;

import com.aura.vihanga.employeeservice.dto.EmployeeResponse;
import com.aura.vihanga.employeeservice.dto.EmployeeSalaryResponse;
import com.aura.vihanga.employeeservice.exception.EmployeeNotFoundException;
import com.aura.vihanga.employeeservice.model.Employee;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

public interface EmployeeService {
    EmployeeResponse createEmployee(Employee employee);

    EmployeeResponse getEmployee(String employeeId) throws EmployeeNotFoundException;

    EmployeeSalaryResponse getEmployeeSalary(String employeeId) throws EmployeeNotFoundException;

    void uploadEmployee(MultipartFile multipartFile) throws IOException;

    List<EmployeeResponse> searchEmployees(String name, String department);
}
