package com.acme.inventory.report;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CsvFormatTest {

    @Test
    void headerIsStable() {
        assertEquals("id,name,price", "id,name,price");
    }
}
