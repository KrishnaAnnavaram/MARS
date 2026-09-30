package com.aura.vihanga.employeeservice.service.implementation;

import com.aura.vihanga.employeeservice.config.DepartmentUrlConfiguration;
import com.aura.vihanga.employeeservice.dto.DepartmentResponse;
import com.aura.vihanga.employeeservice.dto.EmployeeResponse;
import com.aura.vihanga.employeeservice.dto.EmployeeSalaryResponse;
import com.aura.vihanga.employeeservice.exception.EmployeeNotFoundException;
import com.aura.vihanga.employeeservice.model.Employee;
import com.aura.vihanga.employeeservice.repository.EmployeeRepository;
import com.aura.vihanga.employeeservice.repository.EmployeeSearchRepository;
import com.aura.vihanga.employeeservice.service.EmployeeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@Slf4j
public class EmployeeServiceImpl implements EmployeeService {

    @Autowired
    private EmployeeRepository employeeRepository;
    @Autowired
    private EmployeeSearchRepository employeeSearchRepository;
    @Autowired
    @Lazy
    private WebClient.Builder builder;

    @Autowired
    private DepartmentUrlConfiguration departmentUrlConfiguration;

    @Override
    public EmployeeResponse createEmployee(Employee employee) {
        log.trace("EmployeeServiceImpl - createEmployee - employee {}", employee);
        Employee savedEmployee = employeeRepository.save(employee);

        EmployeeResponse employeeResponse = EmployeeResponse.builder()
                .employeeId(savedEmployee.getEmployeeId())
                .name(savedEmployee.getName())
                .department(savedEmployee.getDepartment())
                .phoneNo(savedEmployee.getPhoneNo())
                .address(savedEmployee.getAddress())
                .gender(savedEmployee.getGender())
                .employeeType(savedEmployee.getEmployeeType())
                .build();

        return employeeResponse;
    }

    @Override
    public EmployeeResponse getEmployee(String employeeId) throws EmployeeNotFoundException {
        log.trace("EmployeeServiceImpl - getEmployee - employeeId {}", employeeId);
        Optional<Employee> fetchEmployee = employeeRepository.findById(employeeId);
        EmployeeResponse employeeResponse;

        if(fetchEmployee.isPresent()) {

            employeeResponse = EmployeeResponse.builder()
                    .employeeId(fetchEmployee.get().getEmployeeId())
                    .name(fetchEmployee.get().getName())
                    .department(fetchEmployee.get().getDepartment())
                    .phoneNo(fetchEmployee.get().getPhoneNo())
                    .address(fetchEmployee.get().getAddress())
                    .gender(fetchEmployee.get().getGender())
                    .employeeType(fetchEmployee.get().getEmployeeType())
                    .build();



        } else {
            log.error("EmployeeServiceImpl - getEmployee - Employee Not Found");
            throw new EmployeeNotFoundException("Employee Not Found");
        }

        return employeeResponse;

    }

    @Override
    public EmployeeSalaryResponse getEmployeeSalary(String employeeId) throws EmployeeNotFoundException {
        log.trace("EmployeeServiceImpl - getEmployeeSalary - employeeId {}", employeeId);
        EmployeeResponse employeeResponse = getEmployee(employeeId);
        String departmentId = employeeResponse.getDepartment();

        DepartmentResponse departmentResponse = builder.build().get()
                .uri(departmentUrlConfiguration.getDepartmentByIdUrl(), departmentId)
                .retrieve()
                .bodyToMono(DepartmentResponse.class)
                .block();

        EmployeeSalaryResponse employeeSalaryResponse = EmployeeSalaryResponse.builder()
                .employeeId(employeeResponse.getEmployeeId())
                .name(employeeResponse.getName())
                .departmentName(departmentResponse.getDepartmentName())
                .salary(departmentResponse.getSalary())
                .build();

        return employeeSalaryResponse;
    }

    @Override
    public List<EmployeeResponse> searchEmployees(String name, String department) {
        log.trace("EmployeeServiceImpl - searchEmployees - name {} department {}", name, department);
        List<Employee> employees = employeeSearchRepository.searchEmployees(name, department);

        return employees.stream().map(employee -> EmployeeResponse.builder()
                .employeeId(employee.getEmployeeId())
                .name(employee.getName())
                .department(employee.getDepartment())
                .phoneNo(employee.getPhoneNo())
                .address(employee.getAddress())
                .gender(employee.getGender())
                .employeeType(employee.getEmployeeType())
                .build()).collect(Collectors.toList());
    }

    @Override
    public void uploadEmployee(MultipartFile multipartFile) throws IOException {
//        log.trace("EmployeeServiceImpl - uploadEmployee - multipartFile {}", multipartFile);
        if (ExcelUploadImpl.isValidExcelFile(multipartFile)) {
            List<Employee> employees = ExcelUploadImpl.getEmployeeDataFromExcel(multipartFile);
            employeeRepository.saveAll(employees);
        }
    }
}
