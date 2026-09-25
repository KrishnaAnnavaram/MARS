package com.example;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class CalculatorTest {

    @BeforeEach
    public void setUp() {
    }

    @Test
    public void adds() {
        assertEquals(2, 1 + 1);
    }
}
