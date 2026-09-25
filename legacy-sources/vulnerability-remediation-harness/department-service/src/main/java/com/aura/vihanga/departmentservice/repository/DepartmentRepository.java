package com.aura.vihanga.departmentservice.repository;

import com.aura.vihanga.departmentservice.model.Department;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DepartmentRepository extends MongoRepository<Department, String> {
    List<Department> findBySalaryGreaterThan(double minSalary);
}
