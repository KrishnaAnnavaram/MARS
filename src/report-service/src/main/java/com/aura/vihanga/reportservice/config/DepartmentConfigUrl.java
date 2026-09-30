package com.aura.vihanga.reportservice.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class DepartmentConfigUrl {

    @Autowired
    private Environment environment;

    public String getDepartmentUrl() {
        return environment.getProperty("get.department.min.salary.url");
    }
}
