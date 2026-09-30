package com.aura.vihanga.employeeservice.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class DepartmentUrlConfiguration {

    @Autowired
    Environment environment;

    public String getDepartmentByIdUrl() {
        return environment.getProperty("employee.department.details.url");
    }
}
