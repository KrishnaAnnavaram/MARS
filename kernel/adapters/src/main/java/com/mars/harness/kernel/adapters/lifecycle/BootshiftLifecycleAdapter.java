package com.mars.harness.kernel.adapters.lifecycle;

import com.bootshift.adapters.http.HttpFetcher;
import com.bootshift.stages.stage05.LifecycleSource;
import com.mars.harness.kernel.ports.lifecycle.LifecyclePort;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * {@link LifecyclePort} backed by Bootshift's {@link LifecycleSource}, unchanged.
 *
 * <p>The curated Spring Boot table stays Bootshift's single source. This adapter asks it about
 * candidate lines and keeps what it knows. A line it has no evidence for comes back
 * {@code UNKNOWN} and is reported as such, never guessed. With the network disabled (the
 * default), community sources are not consulted, so no ADVISORY fact can appear without the
 * operator enabling it.
 */
public final class BootshiftLifecycleAdapter implements LifecyclePort {

    private final LifecycleSource source;

    public BootshiftLifecycleAdapter(Path cacheRoot, boolean networkEnabled) {
        this.source = new LifecycleSource(new HttpFetcher(cacheRoot, networkEnabled));
    }

    @Override
    public Optional<LineFacts> line(String framework, String line, LocalDate today) {
        if (!"spring-boot".equals(framework.toLowerCase(Locale.ROOT))) {
            return Optional.empty();
        }
        Map<String, LifecycleSource.Line> resolved = source.resolve(List.of(line), Map.of(), today);
        LifecycleSource.Line facts = resolved.get(line);
        return facts == null ? Optional.empty() : Optional.of(convert(framework, facts, today));
    }

    @Override
    public List<LineFacts> lines(String framework, LocalDate today) {
        if (!"spring-boot".equals(framework.toLowerCase(Locale.ROOT))) {
            return List.of();
        }
        List<String> candidates = new ArrayList<>();
        for (int major = 1; major <= 6; major++) {
            for (int minor = 0; minor <= 9; minor++) {
                candidates.add(major + "." + minor);
            }
        }
        Map<String, LifecycleSource.Line> resolved = source.resolve(candidates, Map.of(), today);
        List<LineFacts> known = new ArrayList<>();
        for (String line : candidates) {
            LifecycleSource.Line facts = resolved.get(line);
            if (facts != null && facts.quality() != LifecycleSource.Quality.UNKNOWN) {
                known.add(convert(framework, facts, today));
            }
        }
        return known;
    }

    private static LineFacts convert(String framework, LifecycleSource.Line facts, LocalDate today) {
        return new LineFacts(framework, facts.line(), facts.latestPatch(), facts.generalAvailability(),
                facts.openSourceSupportEnds(), facts.supportedJavaMajors(), facts.quality().name(), facts.source(),
                LifecycleSource.AS_OF.toString(), LifecycleSource.tableIsStale(today));
    }
}
