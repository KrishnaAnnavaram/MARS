package com.acme.inventory.report;

import com.acme.inventory.repo.ProductRepository;
import org.springframework.stereotype.Component;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Component
public class ReportExporter {

    private static final String REPORTS_DIR = "reports/";

    private final ProductRepository repository;

    public ReportExporter(ProductRepository repository) {
        this.repository = repository;
    }

    public String export(String fileName) throws IOException {
        List<Map<String, Object>> rows = repository.firstPage(100);
        StringBuilder csv = new StringBuilder("id,name,price\n");
        for (Map<String, Object> row : rows) {
            csv.append(row.get("ID")).append(',').append(row.get("NAME")).append(',').append(row.get("PRICE")).append('\n');
        }
        try (FileOutputStream out = new FileOutputStream(REPORTS_DIR + fileName)) {
            out.write(csv.toString().getBytes(StandardCharsets.UTF_8));
        }
        return fileName;
    }
}
