package com.aura.vihanga.employeeservice.controller;

import com.aura.vihanga.employeeservice.dto.EmployeeResponse;
import com.aura.vihanga.employeeservice.dto.EmployeeSalaryResponse;
import com.aura.vihanga.employeeservice.exception.EmployeeNotFoundException;
import com.aura.vihanga.employeeservice.model.Employee;
import com.aura.vihanga.employeeservice.service.EmployeeService;
import com.aura.vihanga.employeeservice.utill.StandardResponse;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("api/v1")
@Slf4j
public class EmployeeController {

    @Autowired
    private EmployeeService employeeService;

    @PostMapping("employee")
    public ResponseEntity<StandardResponse> createEmployee(@RequestBody Employee employee) {
        log.trace("EmployeeController - createEmployee - employee {}", employee);
        EmployeeResponse employeeResponse = employeeService.createEmployee(employee);

        return new ResponseEntity<StandardResponse>(
                new StandardResponse(201, "Employee Created Successfully", employeeResponse), HttpStatus.CREATED
        );
    }

    @PostMapping("employee/excelUpload")
    public ResponseEntity<StandardResponse> uploadEmployee(@RequestParam("file") MultipartFile multipartFile) throws IOException {
        employeeService.uploadEmployee(multipartFile);

        return new ResponseEntity<StandardResponse>(
                new StandardResponse(201, "File Upload Successfully", null), HttpStatus.OK
        );
    }

    @GetMapping("employee/search")
    public ResponseEntity<StandardResponse> searchEmployees(@RequestParam("name") String name,
                                                            @RequestParam(value = "department", required = false) String department) {
        log.trace("EmployeeController - searchEmployees - name {} department {}", name, department);
        List<EmployeeResponse> employeeResponses = employeeService.searchEmployees(name, department);

        return new ResponseEntity<StandardResponse>(
                new StandardResponse(200, "Employee Search Completed Successfully", employeeResponses), HttpStatus.OK
        );
    }

    @GetMapping("employee/{id}")
    public ResponseEntity<StandardResponse> getEmployee(@PathVariable("id") String employeeId) throws EmployeeNotFoundException {
        log.trace("EmployeeController - getEmployee - employeeId {}", employeeId);
        EmployeeResponse employeeResponse = employeeService.getEmployee(employeeId);

        return new ResponseEntity<StandardResponse>(
                new StandardResponse(200, "Employee Fetch Successfully", employeeResponse), HttpStatus.OK
        );
    }

    @GetMapping("employee/salary/{id}")
    @CircuitBreaker(name = "employee", fallbackMethod = "fallBackMethodEmployee")
    @TimeLimiter(name = "employee")
    @Retry(name="employee")
    public CompletableFuture<ResponseEntity<StandardResponse>> getEmployeeSalary(@PathVariable("id") String employeeId) throws EmployeeNotFoundException {
        log.trace("EmployeeController - getEmployeeSalary - employeeId {}", employeeId);
        EmployeeSalaryResponse employeeSalaryResponse = employeeService.getEmployeeSalary(employeeId);

        return CompletableFuture.supplyAsync(() -> new ResponseEntity<StandardResponse>(
                new StandardResponse(200, "Employee Salary Fetch Successfully", employeeSalaryResponse), HttpStatus.OK
        ));
    }

    public CompletableFuture<ResponseEntity<StandardResponse>> fallBackMethodEmployee(@PathVariable("id") String employeeId, RuntimeException runtimeException) {
        log.warn("EmployeeController - fallBackMethodEmployee");
        return CompletableFuture.supplyAsync(() -> new ResponseEntity<StandardResponse>(
                new StandardResponse(503, "Department Service Unavailable", null), HttpStatus.SERVICE_UNAVAILABLE
        ));
    }
}
