package com.bootshift.ports.state;

import com.bootshift.core.state.RunState;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Persistence for run lifecycle metadata.
 *
 * <p>The core domain must not depend on a specific database or cloud vendor. A deterministic
 * filesystem implementation is sufficient for local development; a PostgreSQL-backed implementation
 * satisfies distributed execution without changing any caller.
 */
public interface RunStateStore {

    record RunRecord(String runId, RunState state, String startedAt, String updatedAt,
                     Map<String, String> attributes) {
    }

    void createRun(String runId, Map<String, String> attributes);

    void updateState(String runId, RunState state, String reason);

    Optional<RunRecord> load(String runId);

    List<RunRecord> list();

    void putAttribute(String runId, String key, String value);
}
