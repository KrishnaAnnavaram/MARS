package com.aura.vihanga.employeeservice.repository;

import com.aura.vihanga.employeeservice.model.Employee;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface EmployeeRepository extends MongoRepository<Employee, String> {
}
