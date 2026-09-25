package com.example.parser;

public final class ParserFactory {
    public Object create() throws Exception {
        return Class.forName("org.springframework.boot.json.YamlJsonParser")
                .getDeclaredConstructor().newInstance();
    }
}
