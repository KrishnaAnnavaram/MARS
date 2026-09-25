package com.aura.vihanga.shedulerservice.controller;

import com.aura.vihanga.shedulerservice.entity.Employee;
import com.aura.vihanga.shedulerservice.service.EmployeeSchedulerService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("api/v1")
public class EmployeeSchedulerController {
    @Autowired
    private EmployeeSchedulerService schedulerService;

    @GetMapping("employee")
    public List<Employee> getAllEmployees() {
        return schedulerService.getAllEmployees();
    }
}
