Feature: Department Management

  Scenario: Create Department
    Given the department details request body
    When a POST request is sent to "department"
    Then the response status code should be 201
    And the response body contains the department details
