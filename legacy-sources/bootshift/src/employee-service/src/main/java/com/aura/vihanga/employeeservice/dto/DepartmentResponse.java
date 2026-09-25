package com.aura.vihanga.employeeservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DepartmentResponse {
    private String departmentId;
    private String departmentName;
    private String salary;
}
