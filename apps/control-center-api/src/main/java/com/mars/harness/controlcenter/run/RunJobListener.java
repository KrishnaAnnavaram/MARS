package com.mars.harness.controlcenter.run;

/** Observes the engine operations the Control Center runs (for operational telemetry only). */
public interface RunJobListener {

    void started(RunCoordinator.Job job);

    void finished(RunCoordinator.Job job, boolean failed, String error);
}
