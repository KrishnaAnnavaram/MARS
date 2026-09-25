package com.aura.vihanga.reportservice.service;


import com.aura.vihanga.reportservice.dto.EmployeeSalaryResponse;
import com.aura.vihanga.reportservice.entity.Employee;

import java.io.IOException;
import java.util.List;

public interface EmployeeReportService {
    List<EmployeeSalaryResponse> getEmployees();

    public byte[] generateExcelFile(List<EmployeeSalaryResponse> employees) throws IOException;
}
