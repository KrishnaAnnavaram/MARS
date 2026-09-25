package com.aura.vihanga.configuarationserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.config.server.EnableConfigServer;

@SpringBootApplication
@EnableConfigServer
public class ConfiguarationServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConfiguarationServerApplication.class, args);
    }
}
