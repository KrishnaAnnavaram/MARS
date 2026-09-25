package com.mars.harness.kernel.core.identity;

/**
 * MODULE_ID: a logical Maven or Gradle module, microservice or application module.
 *
 * <p>Bootshift records the module only as a string attribute on each FileRecord. This record
 * gives the module an allocated identity, with name and path as attributes.
 */
public final class ModuleRecord extends TrackedIdentity {

    public String moduleId;
    public String name;
    /** Repository-relative directory; "." for a single-module repository. */
    public String path;
    public String buildFile;
    public String buildSystem;

    @Override
    public String id() {
        return moduleId;
    }
}
