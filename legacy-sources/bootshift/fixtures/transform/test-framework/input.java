package com.example;

import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class CalculatorTest {

    @Before
    public void setUp() {
    }

    @Test
    public void adds() {
        assertEquals(2, 1 + 1);
    }
}
