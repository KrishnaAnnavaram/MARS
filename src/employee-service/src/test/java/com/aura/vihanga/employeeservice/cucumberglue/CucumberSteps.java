package com.aura.vihanga.employeeservice.cucumberglue;

import com.aura.vihanga.employeeservice.model.Employee;
import com.aura.vihanga.employeeservice.utill.EmployeeType;
import com.aura.vihanga.employeeservice.utill.GenderType;
import com.aura.vihanga.employeeservice.utill.StandardResponse;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.util.Optional;

import static org.junit.Assert.assertEquals;

public class CucumberSteps extends CucumberConfig {
    private Employee employee;
    private String employeeId;
    private String URI;
    private String responseMessage;

    private String getMainUrl() {
        System.out.println(mainUrl+8080+"/api/v1/");
        return mainUrl+"8500/api/v1/";
    }

    private ResponseEntity<StandardResponse> getEmployee() {
        HttpEntity<Employee> entity = new HttpEntity<Employee>(headers);

        HttpComponentsClientHttpRequestFactory requestFactory = new HttpComponentsClientHttpRequestFactory();
        RestTemplate restTemplate = new RestTemplate(requestFactory);

        return restTemplate.exchange(URI, HttpMethod.GET, entity, StandardResponse.class);
    }

    private ResponseEntity<StandardResponse> createEmployee() {
        HttpEntity<Employee> entity = new HttpEntity<Employee>(employee, headers);

        HttpComponentsClientHttpRequestFactory requestFactory = new HttpComponentsClientHttpRequestFactory();
        RestTemplate restTemplate = new RestTemplate(requestFactory);

        return restTemplate.exchange(URI, HttpMethod.POST, entity, StandardResponse.class);
    }

    private ResponseEntity<StandardResponse> getEmployeeSalary() {
        HttpEntity<Employee> entity = new HttpEntity<Employee>(headers);

        HttpComponentsClientHttpRequestFactory requestFactory = new HttpComponentsClientHttpRequestFactory();
        RestTemplate restTemplate = new RestTemplate(requestFactory);

        return restTemplate.exchange(URI, HttpMethod.GET, entity, StandardResponse.class);
    }

    @Given("the employee details request body")
    public void the_employee_details_request_body() {
        employee = Employee.builder()
                .employeeId("EMP006")
                .name("Naradha Nirmal")
                .department("DEP01")
                .phoneNo("0765478956")
                .address("No 70, Colombo")
                .employeeType(EmployeeType.CONTRACT_BASIS)
                .gender(GenderType.MALE)
                .build();
    }
    @When("a POST request is sent to {string}")
    public void a_post_request_is_sent_to(String urlPath) {
        URI = getMainUrl() + urlPath;
    }
    @Then("the response status code should be {int}")
    public void the_response_status_code_should_be(Integer expectedStatusCode) {
        ResponseEntity<StandardResponse> postEmployee = createEmployee();
        assertEquals(Optional.of(expectedStatusCode), Optional.of(postEmployee.getStatusCodeValue()));
    }
    @Then("the response body contains the employee details")
    public void the_response_body_contains_the_employee_details() {
        responseMessage = "Employee Created Successfully";
        ResponseEntity<StandardResponse> postEmployee = createEmployee();
        assertEquals(responseMessage, postEmployee.getBody().getMessage());
    }

    @Given("the employee's id")
    public void the_employee_s_id() {
        employeeId = "EMP05";
    }

    @When("a Get request is sent to {string}")
    public void a_get_request_is_sent_to(String urlPath) {
        URI = getMainUrl()+urlPath;
    }

    @Then("the get employee response status code should be {int}")
    public void the_get_employee_response_status_code_should_be(Integer expectedStatusCode) {
        ResponseEntity<StandardResponse> getResponse = getEmployee();
        assertEquals(Optional.ofNullable(expectedStatusCode), Optional.ofNullable(getResponse.getStatusCodeValue()));
    }

    @Then("the response body contain that employee's details")
    public void the_response_body_contain_that_employee_s_details() {
        responseMessage = "Employee Fetch Successfully";
        ResponseEntity<StandardResponse> getResponse = getEmployee();
        assertEquals(responseMessage, getResponse.getBody().getMessage());
    }

    @Given("the employee's id that salary needed")
    public void the_employee_s_id_that_salary_needed() {
        employeeId = "EMP05";
    }

    @When("a Get request is sent to {string} to get salary;")
    public void a_get_request_is_sent_to_to_get_salary(String urlPath) {
        URI = getMainUrl() + urlPath;
    }

    @Then("the get salary response status code should be {int}")
    public void the_get_salary_response_status_code_should_be(Integer expectedStatusCode) {
        ResponseEntity<StandardResponse> getSalaryResponse = getEmployeeSalary();
        assertEquals(Optional.ofNullable(expectedStatusCode), Optional.ofNullable(getSalaryResponse.getStatusCodeValue()));
    }

    @Then("the response body contain that employee's salary details")
    public void the_response_body_contain_that_employee_s_salary_details() {
        responseMessage = "Employee Salary Fetch Successfully";
        ResponseEntity<StandardResponse> getSalaryResponse = getEmployeeSalary();
        assertEquals(responseMessage, getSalaryResponse.getBody().getMessage());
    }
}
