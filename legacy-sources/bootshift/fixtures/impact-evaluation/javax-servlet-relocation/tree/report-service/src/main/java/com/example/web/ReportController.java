package com.example.web;

import javax.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ReportController {

    @GetMapping("/report")
    public String report(HttpServletRequest request) {
        return request.getRemoteAddr();
    }
}
