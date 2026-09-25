package com.aura.vihanga.departmentservice.controller;

import com.aura.vihanga.departmentservice.dto.DepartmentResponse;
import com.aura.vihanga.departmentservice.model.Department;
import com.aura.vihanga.departmentservice.service.DepartmentService;
import com.aura.vihanga.departmentservice.utill.StandardResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("api/v1")
@Slf4j
public class DepartmentController {

    @Autowired
    private DepartmentService departmentService;

    @PostMapping("department")
    public ResponseEntity<StandardResponse> createDepartment(@RequestBody Department department) {
        DepartmentResponse departmentResponse = departmentService.createDepartment(department);

        return new ResponseEntity<StandardResponse>(
                new StandardResponse(201, "Department Created Successfully", departmentResponse), HttpStatus.CREATED
        );


    }

    @GetMapping("department/{id}")
    public DepartmentResponse getDepartment(@PathVariable("id") String departmentId) {
        return departmentService.getDepartment(departmentId);
    }

    @GetMapping("department/salary/{minSalary}")
    public List<DepartmentResponse> getAllDepartments(@PathVariable("minSalary") double minSalary) {
        List<DepartmentResponse> departmentResponses = departmentService.getAllDepartments(minSalary);
        log.info("Department Controller {}", departmentResponses);
        return  departmentResponses;
    }
}
