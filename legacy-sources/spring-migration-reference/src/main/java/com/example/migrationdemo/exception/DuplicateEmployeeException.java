package com.example.migrationdemo.exception;

public class DuplicateEmployeeException extends RuntimeException {

    public DuplicateEmployeeException(String field, String value) {
        super("Employee with " + field + " '" + value + "' already exists");
    }

    public DuplicateEmployeeException(String message) {
        super(message);
    }

}
