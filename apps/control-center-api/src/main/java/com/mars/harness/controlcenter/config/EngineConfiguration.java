package com.mars.harness.controlcenter.config;

import com.mars.harness.cli.HarnessFactory;
import com.mars.harness.kernel.engine.EngineConfig;
import com.mars.harness.kernel.engine.HarnessEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Wires the engine exactly as the CLI does, through {@link HarnessFactory}: the same adapters,
 * capability packs, policy and runs root. The Control Center adds no engine behaviour of its own.
 */
@Configuration(proxyBeanMethods = false)
public class EngineConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(EngineConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public EngineConfig engineConfig(ControlCenterProperties properties) {
        Path root = HarnessFactory.locateHarnessRoot(properties.harnessRoot());
        Path runs = properties.runsRoot() != null ? properties.runsRoot() : root.resolve("runs");
        return HarnessFactory.config(root, runs, null, properties.today(), properties.mavenOffline(), properties.network());
    }

    @Bean
    public HarnessEngine harnessEngine(EngineConfig config) {
        return HarnessFactory.engine(config);
    }

    @Bean
    public ControlCenterPaths controlCenterPaths(EngineConfig config, ControlCenterProperties properties) {
        List<Path> roots = new ArrayList<>();
        for (Path configured : properties.repositoryRoots()) {
            Path resolved = configured.isAbsolute() ? configured : config.harnessRoot().resolve(configured);
            roots.add(resolved.toAbsolutePath().normalize());
        }
        if (roots.isEmpty()) {
            LOG.warn("No mars.control-center.repository-roots configured: runs can be inspected but not started from the "
                    + "Control Center");
        }
        return new ControlCenterPaths(config.harnessRoot().toAbsolutePath().normalize(),
                config.runsRoot().toAbsolutePath().normalize(), List.copyOf(roots));
    }
}
