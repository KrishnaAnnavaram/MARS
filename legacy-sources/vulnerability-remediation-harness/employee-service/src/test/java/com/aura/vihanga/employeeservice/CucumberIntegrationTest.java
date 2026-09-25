package com.aura.vihanga.employeeservice;


import io.cucumber.junit.Cucumber;
import io.cucumber.junit.CucumberOptions;
import org.junit.runner.RunWith;

    @RunWith(Cucumber.class)
    @CucumberOptions(features = "classpath:/features", glue = "com.aura.vihanga.employeeservice.cucumberglue", plugin = {"pretty",
            "junit:target/cucumber-results.xml",
            "json:target/cucumber.json",
            "html:target/cucumber-html-report.html"
            },
            monochrome = true)
    public class CucumberIntegrationTest {
    }
