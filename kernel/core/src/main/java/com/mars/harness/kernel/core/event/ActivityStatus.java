package com.mars.harness.kernel.core.event;

/**
 * Status of the activity an event describes.
 *
 * <p>{@code WAITING} is used only when the run has stopped for a human. {@code SKIPPED} records an
 * activity the harness deliberately did not perform (for example a baseline build under
 * {@code --skip-build}); it is never a pass.
 */
public enum ActivityStatus { STARTED, PROGRESS, COMPLETED, WAITING, FAILED, SKIPPED, INFO }
