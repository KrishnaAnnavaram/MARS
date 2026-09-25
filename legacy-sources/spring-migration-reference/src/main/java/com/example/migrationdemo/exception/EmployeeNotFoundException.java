package com.example.migrationdemo.exception;

public class EmployeeNotFoundException extends RuntimeException {

    public EmployeeNotFoundException(Long id) {
        super("Employee with id " + id + " was not found");
    }

    public EmployeeNotFoundException(String message) {
        super(message);
    }

}
