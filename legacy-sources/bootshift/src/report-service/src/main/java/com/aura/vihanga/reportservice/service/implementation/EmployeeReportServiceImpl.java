package com.aura.vihanga.reportservice.service.implementation;

import com.aura.vihanga.reportservice.config.DepartmentConfigUrl;
import com.aura.vihanga.reportservice.dto.DepartmentResponse;
import com.aura.vihanga.reportservice.dto.EmployeeResponse;
import com.aura.vihanga.reportservice.dto.EmployeeSalaryResponse;
import com.aura.vihanga.reportservice.entity.Employee;
import com.aura.vihanga.reportservice.repository.EmployeeReportRepository;
import com.aura.vihanga.reportservice.service.EmployeeReportService;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
public class EmployeeReportServiceImpl implements EmployeeReportService {

    @Autowired
    private EmployeeReportRepository employeeReportRepository;
    @Autowired
    private WebClient.Builder builder;
    @Autowired
    private DepartmentConfigUrl departmentConfigUrl;

    public List<EmployeeSalaryResponse> getEmployees() {
        log.info("Report Service");
        List<Employee> employeesList = employeeReportRepository.findAll();

//        List<String> employeeDepartmentId = employees.stream().map(employee -> employee.getDepartment()).toList();

        DepartmentResponse[] departmentResponses = builder.build().get()
                .uri(departmentConfigUrl.getDepartmentUrl(), 10000)
                .retrieve()
                .bodyToMono(DepartmentResponse[].class)
                .block();
        List<DepartmentResponse> departmentResponseList = Arrays.asList(departmentResponses);

        List<EmployeeSalaryResponse> employeeSalaryResponseList = new ArrayList<>();

        for (Employee employee : employeesList) {
            for (DepartmentResponse department : departmentResponseList) {
                if (employee.getDepartment().equals(department.getDepartmentId())) {
                    EmployeeSalaryResponse employeeSalaryResponse = EmployeeSalaryResponse.builder()
                            .employeeId(employee.getEmployeeId())
                            .name(employee.getName())
                            .departmentName(department.getDepartmentName())
                            .salary(department.getSalary())
                            .build();

                    log.info("employee Salary {}",employeeSalaryResponse);

                    employeeSalaryResponseList.add(employeeSalaryResponse);
                }
            }
        }

        return employeeSalaryResponseList;

    }

        public byte[] generateExcelFile(List<EmployeeSalaryResponse> employees) throws IOException {
            try (Workbook workbook = new XSSFWorkbook()) {
                Sheet sheet = workbook.createSheet("Employees");

                Row headerRow = sheet.createRow(0);
                headerRow.createCell(0).setCellValue("Employee Id");
                headerRow.createCell(1).setCellValue("Employee Name");
                headerRow.createCell(2).setCellValue("Department");
                headerRow.createCell(3).setCellValue("Salary");

                for (int i = 0; i < employees.size(); i++) {
                    EmployeeSalaryResponse employee = employees.get(i);
                    Row dataRow = sheet.createRow(i + 1);
                    dataRow.createCell(0).setCellValue(employee.getEmployeeId());
                    dataRow.createCell(1).setCellValue(employee.getName());
                    dataRow.createCell(2).setCellValue(employee.getDepartmentName());
                    dataRow.createCell(3).setCellValue(employee.getSalary());
                }

                ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                workbook.write(outputStream);
                return outputStream.toByteArray();
            }
        }
}
