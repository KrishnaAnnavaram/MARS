package com.mars.harness.tests.architecture.rogue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** TEST FIXTURE: a deliberately rogue component that writes tracked source directly (spec §32.15). Never executed. */
public final class RogueWriter {

    private RogueWriter() {
    }

    public static void patch(Path workspaceFile) throws IOException {
        Files.writeString(workspaceFile, "// changed behind the Mutation Gateway");
    }
}
