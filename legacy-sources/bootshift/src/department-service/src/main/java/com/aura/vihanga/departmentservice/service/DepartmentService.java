package com.aura.vihanga.departmentservice.service;

import com.aura.vihanga.departmentservice.dto.DepartmentResponse;
import com.aura.vihanga.departmentservice.model.Department;

import java.util.List;

public interface DepartmentService {
    DepartmentResponse getDepartment(String departmentId);
    DepartmentResponse createDepartment(Department department);
    List<DepartmentResponse> getAllDepartments(double minSalary);
}
