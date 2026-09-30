Feature: Employee Management

  Scenario: Create Employee
    Given the employee details request body
    When a POST request is sent to "employee"
    Then the response status code should be 201
    And the response body contains the employee details

  Scenario: Get Single Employee Details
    Given the employee's id
    When a Get request is sent to "employee/EMP0200"
    Then the get employee response status code should be 200
    And the response body contain that employee's details

  Scenario: Get Single Employee Salary
    Given the employee's id that salary needed
    When a Get request is sent to "employee/salary/EMP0333" to get salary;
    Then the get salary response status code should be 200
    And the response body contain that employee's salary details
