package com.acme.inventory.web;

import com.acme.inventory.repo.ProductRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductRepository repository;

    public ProductController(ProductRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/search")
    public List<Map<String, Object>> search(@RequestParam String name) {
        return repository.findByName(name);
    }

    @GetMapping("/{id}")
    public Map<String, Object> byId(@PathVariable long id) {
        return repository.findById(id);
    }
}
