package com.bootshift.core.graph;

/**
 * Application graph node categories (spec section 13).
 *
 * <p>Deliberately fine-grained: a CONTROLLER is not merely a CLASS, because impact analysis and
 * validation scoping ask questions in these terms.
 */
public enum NodeType {
    MODULE,
    FILE,
    PACKAGE,
    CLASS,
    INTERFACE,
    ENUM,
    RECORD,
    METHOD,
    CONSTRUCTOR,
    FIELD,
    LIBRARY,
    DEPENDENCY,
    SPRING_BEAN,
    CONFIGURATION_CLASS,
    CONTROLLER,
    ENDPOINT,
    SERVICE,
    REPOSITORY,
    ENTITY,
    MONGODB_DOCUMENT,
    CONFIG_PROPERTY,
    PROFILE,
    TEST,
    DATABASE_TABLE,
    MESSAGE_DESTINATION,
    EXTERNAL_SYSTEM,
    CONFIG_SERVER,
    DISCOVERY_SERVER;

    /**
     * True for every node kind that originates from a Java type declaration, including the Spring
     * stereotypes. Graph verification counts these against the number of parsed types, so omitting a
     * stereotype here would produce a spurious coverage warning.
     */
    public boolean isTypeDeclaration() {
        return this == CLASS || this == INTERFACE || this == ENUM || this == RECORD
                || this == CONTROLLER || this == SERVICE || this == REPOSITORY
                || this == ENTITY || this == MONGODB_DOCUMENT || this == CONFIGURATION_CLASS
                || this == SPRING_BEAN || this == TEST;
    }
}
