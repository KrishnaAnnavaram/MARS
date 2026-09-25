package com.aura.vihanga.employeeservice.repository;

import com.aura.vihanga.employeeservice.model.Employee;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.BasicQuery;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@Slf4j
public class EmployeeSearchRepository {

    @Autowired
    private MongoTemplate mongoTemplate;

    public List<Employee> searchEmployees(String name, String department) {
        StringBuilder filter = new StringBuilder("{ ");
        filter.append("'name': { $regex: '").append(name).append("' }");

        if (department != null && !department.isEmpty()) {
            filter.append(", 'department': '").append(department).append("'");
        }

        filter.append(" }");

        log.info("EmployeeSearchRepository - searchEmployees - filter {}", filter);

        return mongoTemplate.find(new BasicQuery(filter.toString()), Employee.class);
    }
}
