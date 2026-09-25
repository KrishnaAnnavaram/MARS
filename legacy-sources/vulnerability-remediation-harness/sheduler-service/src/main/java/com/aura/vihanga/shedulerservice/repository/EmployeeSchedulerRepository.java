package com.aura.vihanga.shedulerservice.repository;

import com.aura.vihanga.shedulerservice.entity.Employee;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface EmployeeSchedulerRepository extends MongoRepository<Employee, String> {
}
