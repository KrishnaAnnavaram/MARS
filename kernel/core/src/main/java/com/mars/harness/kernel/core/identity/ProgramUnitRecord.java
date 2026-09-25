package com.mars.harness.kernel.core.identity;

import java.util.ArrayList;
import java.util.List;

/**
 * PROGRAM_UNIT_ID: a language-neutral executable or declarative unit, such as a Java class,
 * interface, record or enum, a COBOL program, a PL/SQL package, or a Python module. The fully
 * qualified name is an attribute: a rename backed by strong evidence keeps the identity.
 */
public final class ProgramUnitRecord extends TrackedIdentity {

    public String programUnitId;
    public String moduleId;
    public String kind;
    public String name;
    public String fqn;
    public String packageName;
    public String baselineFqn;
    public List<String> memberSignatures = new ArrayList<>();
    public List<String> annotations = new ArrayList<>();

    @Override
    public String id() {
        return programUnitId;
    }
}
