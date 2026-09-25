package com.acme.inventory.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

@Repository
public class ProductRepository {

    private final JdbcTemplate jdbcTemplate;

    public ProductRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<Map<String, Object>> findByName(String name) {
        String sql = "SELECT id, name, price FROM product WHERE name = '" + name + "'";
        return jdbcTemplate.queryForList(sql);
    }

    public Map<String, Object> findById(long id) {
        return jdbcTemplate.queryForMap("SELECT id, name, price FROM product WHERE id = ?", id);
    }

    public List<Map<String, Object>> firstPage(int limit) {
        return jdbcTemplate.queryForList("SELECT id, name, price FROM product ORDER BY id LIMIT ?", limit);
    }
}
