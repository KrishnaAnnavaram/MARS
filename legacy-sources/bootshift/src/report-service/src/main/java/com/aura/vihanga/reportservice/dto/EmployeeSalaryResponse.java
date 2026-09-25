package com.aura.vihanga.reportservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@NoArgsConstructor
@AllArgsConstructor
@Data
@Builder
public class EmployeeSalaryResponse {
    private String employeeId;
    private String name;
    private String departmentName;
    private String salary;
}
