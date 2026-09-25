package com.bootshift.stages;

import com.bootshift.adapters.approval.FilesystemDecisionStore;
import com.bootshift.adapters.evidence.FilesystemEvidenceStore;
import com.bootshift.adapters.http.HttpFetcher;
import com.bootshift.adapters.scm.GitScmAdapter;
import com.bootshift.adapters.state.FilesystemRunStateStore;
import com.bootshift.adapters.telemetry.StructuredTelemetryAdapter;
import com.bootshift.core.domain.RunContext;
import com.bootshift.core.policy.HarnessPolicy;
import com.bootshift.core.state.StateMachine;
import com.bootshift.core.util.SchemaValidator;
import com.bootshift.ports.ai.AIProvider;
import com.bootshift.ports.environment.EnvironmentProvider;
import com.bootshift.ports.approval.DecisionStore;
import com.bootshift.ports.evidence.EvidenceObjectStore;
import com.bootshift.ports.scm.ScmPort;
import com.bootshift.ports.state.RunStateStore;
import com.bootshift.ports.telemetry.TelemetryPort;

import java.nio.file.Path;

/**
 * Everything a stage is allowed to reach.
 *
 * <p>Deliberately explicit rather than a service locator: a stage that needs a capability declares it
 * by using this context, and anything absent here is something the stage is not permitted to do.
 */
public final class StageContext {

    private final RunContext run;
    private final StateMachine stateMachine;
    private final ScmPort scm;
    private final RunStateStore runStateStore;
    private final EvidenceObjectStore evidenceStore;
    private final TelemetryPort telemetry;
    private final SchemaValidator schemaValidator;
    private final HttpFetcher httpFetcher;
    private final AIProvider ai;
    private final EnvironmentProvider environment;
    private final Path harnessRoot;
    private final DecisionStore decisions;

    public StageContext(RunContext run,
                        StateMachine stateMachine,
                        ScmPort scm,
                        RunStateStore runStateStore,
                        EvidenceObjectStore evidenceStore,
                        TelemetryPort telemetry,
                        SchemaValidator schemaValidator,
                        HttpFetcher httpFetcher,
                        AIProvider ai,
                        EnvironmentProvider environment,
                        Path harnessRoot) {
        this(run, stateMachine, scm, runStateStore, evidenceStore, telemetry, schemaValidator,
                httpFetcher, ai, environment, harnessRoot,
                new FilesystemDecisionStore(run.runWorkspace(), externalDecisionsDirectory()));
    }

    public StageContext(RunContext run,
                        StateMachine stateMachine,
                        ScmPort scm,
                        RunStateStore runStateStore,
                        EvidenceObjectStore evidenceStore,
                        TelemetryPort telemetry,
                        SchemaValidator schemaValidator,
                        HttpFetcher httpFetcher,
                        AIProvider ai,
                        EnvironmentProvider environment,
                        Path harnessRoot,
                        DecisionStore decisions) {
        this.decisions = decisions;
        this.run = run;
        this.stateMachine = stateMachine;
        this.scm = scm;
        this.runStateStore = runStateStore;
        this.evidenceStore = evidenceStore;
        this.telemetry = telemetry;
        this.schemaValidator = schemaValidator;
        this.httpFetcher = httpFetcher;
        this.ai = ai;
        this.environment = environment;
        this.harnessRoot = harnessRoot;
    }

    /**
     * The human decision store.
     *
     * <p>Reachable from any stage, deliberately. Validation needs to know whether a legitimate
     * intentional-change decision exists, and routing that question through the approval stage - which
     * runs after validation and derives its gates from validation's output - made the two mutually
     * dependent.
     */
    public DecisionStore decisions() {
        return decisions;
    }

    /**
     * Where externally supplied decisions are read from.
     *
     * <p>Outside the run workspace on purpose: a decision that only exists inside the artifacts of
     * the run it approves cannot have been supplied independently of it.
     */
    public static Path externalDecisionsDirectory() {
        String configured = System.getenv("BOOTSHIFT_DECISIONS_DIR");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        return Path.of(System.getProperty("user.home"), ".bootshift", "decisions");
    }

    public RunContext run() {
        return run;
    }

    public HarnessPolicy policy() {
        return run.policy();
    }

    public StateMachine stateMachine() {
        return stateMachine;
    }

    public ScmPort scm() {
        return scm;
    }

    public RunStateStore runStateStore() {
        return runStateStore;
    }

    public EvidenceObjectStore evidenceStore() {
        return evidenceStore;
    }

    public TelemetryPort telemetry() {
        return telemetry;
    }

    public SchemaValidator schemas() {
        return schemaValidator;
    }

    public HttpFetcher http() {
        return httpFetcher;
    }

    public AIProvider ai() {
        return ai;
    }

    public EnvironmentProvider environment() {
        return environment;
    }

    /** Root of the harness installation, used to locate schemas, policies and migration rules. */
    public Path harnessRoot() {
        return harnessRoot;
    }

    public Path migrationRules() {
        return harnessRoot.resolve("migration-rules");
    }

    public Path policies() {
        return harnessRoot.resolve("policies");
    }

    /** Builds the default local-development context for a run. */
    public static StageContext localDefault(RunContext run, Path harnessRoot, AIProvider ai,
                                            EnvironmentProvider environment, boolean networkEnabled) {
        return new StageContext(
                run,
                new StateMachine(),
                new GitScmAdapter(),
                new FilesystemRunStateStore(run.stateStore()),
                new FilesystemEvidenceStore(run.evidenceStore()),
                new StructuredTelemetryAdapter(run.runWorkspace().resolve("telemetry/telemetry.jsonl"),
                        run.runId()),
                new SchemaValidator(harnessRoot.resolve("schemas")),
                new HttpFetcher(run.runWorkspace().resolve("http-cache"), networkEnabled),
                ai,
                environment,
                harnessRoot);
    }
}
