package com.mars.harness.kernel.engine.run;

import com.mars.harness.kernel.core.run.RunStateMachine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Durable run state ({@code state/run-state.json}), published atomically after every transition.
 *
 * <p>The cursor ({@link #machine}) says where the run is. The flags record what has happened and
 * each one names the artifact that proves it. On resume, the engine re-verifies those artifacts
 * before trusting the flags (Bootshift ADR-002).
 */
public final class RunRecord {

    public String runId;
    public String repository;
    public String repositoryId;
    public String createdAt;
    public String policyVersion;
    public String harnessVersion;
    public String today;
    public RunStateMachine machine = new RunStateMachine();

    public String strategy;
    public String executionDecisionId;
    public String postSecurityDecisionId;
    public boolean migrationDeclined;
    public boolean migrationExecuted;
    public boolean migrationPlanned;
    public boolean securityExecuted;
    public boolean postSecurityReassessed;
    /** Platform-blocked remediation plans were re-planned after the migration executed (at most once). */
    public boolean replannedAfterMigration;
    public boolean stopRequested;
    public String combinedAssessmentHash;
    public String migrationAssessmentId;
    public String migrationPlanId;
    public String migrationPlanHash;
    public String verdict;

    public List<String> findingInputs = new ArrayList<>();
    public Map<String, String> researchInputs = new LinkedHashMap<>();
    public String probesFile;
    public boolean skipBuild;
    public boolean acceptPending;
    public List<String> notes = new ArrayList<>();
    /** Proposal ID to its current lifecycle status. The proposal file is immutable; its status lives here. */
    public Map<String, String> proposalStatus = new LinkedHashMap<>();
    /** Proposal ID to the CHANGE_IDs it produced. Duplicate-application protection on resume. */
    public Map<String, List<String>> proposalChanges = new LinkedHashMap<>();
    /** Plan ID to its VRH verification outcome (Cleared/Blocked, …) once verified. */
    public Map<String, String> verification = new LinkedHashMap<>();
    public int batchCounter;
}
