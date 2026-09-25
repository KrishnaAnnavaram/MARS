package com.bootshift.core.identity;

import java.util.Locale;

/** Inventory file roles (spec section 10). Classification only - never a migration conclusion. */
public enum FileRole {
    JAVA_MAIN,
    JAVA_TEST,
    KOTLIN,
    GROOVY,
    MAVEN_BUILD,
    GRADLE_BUILD,
    SETTINGS,
    CONFIG_PROPERTIES,
    CONFIG_YAML,
    CONFIG_XML,
    SQL_MIGRATION,
    RESOURCE,
    DOCKERFILE,
    K8S,
    CI_PIPELINE,
    GENERATED,
    UNKNOWN;

    public boolean isJavaSource() {
        return this == JAVA_MAIN || this == JAVA_TEST;
    }

    public boolean isBuildDescriptor() {
        return this == MAVEN_BUILD || this == GRADLE_BUILD || this == SETTINGS;
    }

    public boolean isConfiguration() {
        return this == CONFIG_PROPERTIES || this == CONFIG_YAML || this == CONFIG_XML;
    }

    public static FileRole parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return UNKNOWN;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return UNKNOWN;
        }
    }
}
