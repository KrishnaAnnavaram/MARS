package com.aura.vihanga.reportservice.repository;

import com.aura.vihanga.reportservice.entity.Employee;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface EmployeeReportRepository extends MongoRepository<Employee, String> {
  List<Employee> findByDepartment(String department);
}
