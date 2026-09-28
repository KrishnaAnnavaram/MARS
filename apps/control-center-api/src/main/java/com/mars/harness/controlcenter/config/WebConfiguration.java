package com.mars.harness.controlcenter.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Files;

/**
 * Serves the built Control Center UI: from {@code mars.control-center.ui-dir} when set (for example
 * {@code ui/mars-control-center/dist}), otherwise from {@code classpath:/static/} (the {@code ui}
 * Maven profile packages it there). Client-side routes are forwarded to {@code index.html}.
 */
@Configuration(proxyBeanMethods = false)
public class WebConfiguration implements WebMvcConfigurer {

    private static final Logger LOG = LoggerFactory.getLogger(WebConfiguration.class);

    private final ControlCenterProperties properties;

    public WebConfiguration(ControlCenterProperties properties) {
        this.properties = properties;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        if (properties.uiDir() != null) {
            if (Files.isRegularFile(properties.uiDir().resolve("index.html"))) {
                String location = properties.uiDir().toAbsolutePath().normalize().toUri().toString();
                registry.addResourceHandler("/**").addResourceLocations(location, "classpath:/static/");
                LOG.info("Serving the Control Center UI from {}", properties.uiDir());
            } else {
                LOG.warn("mars.control-center.ui-dir {} has no index.html; build the UI first (npm run build)",
                        properties.uiDir());
            }
        }
    }

    /** Routes the single-page application owns; the browser loads index.html and the router takes over. */
    @Controller
    static class SpaRoutes {
        @GetMapping({"/", "/login", "/settings", "/runs", "/runs/{runId}", "/runs/{runId}/{view}",
                "/runs/{runId}/{view}/{item}"})
        String index() {
            return "forward:/index.html";
        }
    }
}
