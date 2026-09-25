package com.aura.vihanga.employeeservice.cucumberglue;

import com.aura.vihanga.employeeservice.EmployeeServiceApplication;
import org.junit.Before;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootContextLoader;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.http.HttpHeaders;

import javax.ws.rs.core.Application;

@ContextConfiguration(classes = Application.class, loader = SpringBootContextLoader.class)
@SpringBootTest(classes = EmployeeServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class CucumberConfig {

    @Autowired
    TestRestTemplate testRestTemplate;

    @LocalServerPort
    public int port;

    public String mainUrl = "http://localhost:";

    HttpHeaders headers = new HttpHeaders();

    @Before
    public void setUp() throws Exception {
        headers.set("Content-Type", "application/json");
    }
}
