package com.example.fixture;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;

@WebMvcTest(App.class)
class AppTest {

    @MockBean
    private Other other;

    @Test
    void contextLoads() {
    }
}
