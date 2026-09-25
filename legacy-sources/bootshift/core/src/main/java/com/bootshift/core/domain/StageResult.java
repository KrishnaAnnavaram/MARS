package com.bootshift.core.domain;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Uniform return value from every pipeline stage. */
public record StageResult(String stageId,
                          ExitCode exitCode,
                          String summary,
                          List<String> messages,
                          Map<String, Path> artifacts,
                          String primaryArtifactHash) {

    public static StageResult ok(String stageId, String summary, Map<String, Path> artifacts, String hash) {
        return new StageResult(stageId, ExitCode.SUCCESS, summary, List.of(), artifacts, hash);
    }

    public static StageResult failure(String stageId, ExitCode code, String summary, List<String> messages) {
        return new StageResult(stageId, code, summary, new ArrayList<>(messages), Map.of(), null);
    }

    public boolean succeeded() {
        return exitCode == ExitCode.SUCCESS;
    }
}
