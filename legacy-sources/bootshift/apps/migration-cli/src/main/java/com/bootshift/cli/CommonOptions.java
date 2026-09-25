package com.bootshift.cli;

import com.bootshift.stages.RunFactory;
import com.bootshift.stages.StageContext;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Options shared by every command.
 *
 * <p>Defaults keep local development frictionless while honouring the spec: the workspace root is
 * external to the harness repository, AI is off, the environment provider is managed, and the policy
 * is production.
 */
public final class CommonOptions {

    @CommandLine.Option(names = {"-r", "--repo"}, description =
            "Path to the application under analysis. Default: ${DEFAULT-VALUE}")
    Path repository = Path.of("src");

    @CommandLine.Option(names = "--workspace-root", description =
            "External workspace root holding original/, migration/, runtime-old/, runtime-new/ and "
                    + "internal-checkpoint-git/. Default: ${DEFAULT-VALUE}")
    Path workspaceRoot = defaultWorkspaceRoot();

    @CommandLine.Option(names = "--output", description =
            "Artifact plane root. Default: ${DEFAULT-VALUE}")
    Path outputRoot = Path.of("output");

    @CommandLine.Option(names = "--harness-root", description =
            "Harness installation root holding schemas/, policies/ and migration-rules/. "
                    + "Default: ${DEFAULT-VALUE}")
    Path harnessRoot = Path.of(".");

    @CommandLine.Option(names = "--policy", description =
            "Policy profile: production or development. Default: ${DEFAULT-VALUE}")
    String policy = "production";

    @CommandLine.Option(names = "--policy-file", description = "Explicit policy JSON file")
    Path policyFile;

    @CommandLine.Option(names = "--ai", description =
            "Enable the optional local OSS AI provider. The pipeline is fully functional without it.")
    boolean ai;

    @CommandLine.Option(names = "--environment", description =
            "Environment provider mode: managed or delegated. Default: ${DEFAULT-VALUE}")
    String environment = "managed";

    @CommandLine.Option(names = "--offline", description =
            "Disable all network egress. Cached documents and metadata are still used.")
    boolean offline;

    @CommandLine.Option(names = "--run-id", description =
            "Continue a specific run instead of the current one")
    String runId;

    @CommandLine.Option(names = "--env-attribute", description =
            "Delegated environment attribute in key=value form. Repeatable.")
    Map<String, String> environmentAttributes = new LinkedHashMap<>();

    public StageContext context() {
        return RunFactory.create(new RunFactory.Options(repository, workspaceRoot, outputRoot,
                harnessRoot.toAbsolutePath().normalize(), policy, policyFile, ai, environment,
                !offline, runId, environmentAttributes));
    }

    public Path outputRoot() {
        return outputRoot.toAbsolutePath().normalize();
    }

    /**
     * Default workspace root lives outside the harness repository so customer worktrees are never
     * committed by accident (spec section 42).
     */
    private static Path defaultWorkspaceRoot() {
        String configured = System.getenv("BOOTSHIFT_WORKSPACE_ROOT");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        return Path.of(System.getProperty("java.io.tmpdir"), "bootshift-workspaces");
    }
}
