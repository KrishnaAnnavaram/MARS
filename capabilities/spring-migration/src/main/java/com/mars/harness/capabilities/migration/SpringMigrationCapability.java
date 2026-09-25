package com.mars.harness.capabilities.migration;

import com.mars.harness.capabilities.migration.advisor.MigrationAdvisor;
import com.mars.harness.capabilities.migration.engine.ReferencePackEngine;
import com.mars.harness.kernel.core.migration.MigrationAssessment;
import com.mars.harness.kernel.ports.capability.CapabilityContext;
import com.mars.harness.kernel.ports.migration.MigrationCapability;
import com.mars.harness.kernel.ports.mutation.ProposalSink;

import java.util.List;

/**
 * The Spring migration capability pack behind the common contract (spec §13).
 *
 * <p>{@code assess} is the read-only advisor. {@code plan}, {@code execute} and {@code validate}
 * are the reference-pack engine, which implements the {@code 04d-version-migration} workflow
 * deterministically. Bootshift's own 20-stage migration pipeline stays available unchanged through
 * its own CLI; it is not rewritten here (ADR-U003).
 */
public final class SpringMigrationCapability implements MigrationCapability {

    private final MigrationAdvisor advisor = new MigrationAdvisor();
    private final ReferencePackEngine engine = new ReferencePackEngine();

    @Override
    public String id() {
        return "spring-migration";
    }

    @Override
    public MigrationAssessment assess(CapabilityContext context, List<Objective> objectives) {
        return advisor.assess(context, objectives);
    }

    @Override
    public MigrationPlan plan(CapabilityContext context, MigrationAssessment assessment) {
        return engine.plan(context, assessment);
    }

    @Override
    public MigrationExecution execute(CapabilityContext context, MigrationPlan plan, ProposalSink sink, MigrationExecution previous) {
        return engine.execute(context, plan, sink, previous);
    }

    @Override
    public CapabilityValidation validate(CapabilityContext context, MigrationPlan plan, MigrationExecution execution) {
        return engine.validate(context, plan, execution);
    }
}
