package com.acme.inventory.report;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private final ReportExporter exporter;

    public ReportController(ReportExporter exporter) {
        this.exporter = exporter;
    }

    @PostMapping("/export")
    public String export(@RequestParam String fileName) throws IOException {
        return exporter.export(fileName);
    }
}
