package com.aura.vihanga.departmentservice.service.implementation;

import com.aura.vihanga.departmentservice.dto.DepartmentResponse;
import com.aura.vihanga.departmentservice.model.Department;
import com.aura.vihanga.departmentservice.repository.DepartmentRepository;
import com.aura.vihanga.departmentservice.service.DepartmentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@Slf4j
public class DepartmentServiceImpl implements DepartmentService {

    @Autowired
    private DepartmentRepository departmentRepository;

    @Override
    public DepartmentResponse createDepartment(Department department) {
        Department savedDepartment = departmentRepository.save(department);

        DepartmentResponse departmentResponse = DepartmentResponse.builder().
                departmentId(savedDepartment.getDepartmentId()).
                departmentName(savedDepartment.getDepartmentName()).
                salary(savedDepartment.getSalary()).build();

        return departmentResponse;
    }

    @Override
    public List<DepartmentResponse> getAllDepartments(double minSalary) {
        List<Department> departments = (List<Department>) departmentRepository.findBySalaryGreaterThan(minSalary);
        return departments.stream().map(department -> DepartmentResponse.builder().
                        departmentId(department.getDepartmentId()).
                        departmentName(department.getDepartmentName()).
                        salary(department.getSalary()).build()).collect(Collectors.toList());
    }

    @Override
    public DepartmentResponse getDepartment(String departmentId) {
        Optional<Department> department = departmentRepository.findById(departmentId);

        if(department.isPresent()) {
            return DepartmentResponse.builder().
                    departmentId(department.get().getDepartmentId()).
                    departmentName(department.get().getDepartmentName()).
                    salary(department.get().getSalary()).build();
        } else {
            return null;
        }
    }


}
