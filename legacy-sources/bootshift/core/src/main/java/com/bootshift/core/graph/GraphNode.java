package com.bootshift.core.graph;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A node in the application graph.
 *
 * <p>{@code attribution} is load-bearing: a relationship built from an unresolved type must never be
 * presented as a high-confidence fact, and Agent 09 caps such findings at POSSIBLY_AFFECTED.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class GraphNode {

    public enum Attribution {
        /** The Java type system resolved this symbol against the project or its dependencies. */
        RESOLVED,
        /** The symbol was seen syntactically but could not be resolved to a declaration. */
        UNRESOLVED,
        /** More than one candidate declaration matched. */
        AMBIGUOUS
    }

    private String id;
    private NodeType type;
    private String name;
    private String fqn;
    private String module;
    private String packageName;
    private String fileId;
    private String symbolId;
    private String signature;
    private Integer lineStart;
    private Integer lineEnd;
    private Attribution attribution = Attribution.RESOLVED;
    private final List<String> annotations = new ArrayList<>();
    private final Map<String, Object> properties = new LinkedHashMap<>();

    public GraphNode() {
    }

    public GraphNode(String id, NodeType type, String name) {
        this.id = id;
        this.type = type;
        this.name = name;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public NodeType getType() {
        return type;
    }

    public void setType(NodeType type) {
        this.type = type;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getFqn() {
        return fqn;
    }

    public GraphNode setFqn(String fqn) {
        this.fqn = fqn;
        return this;
    }

    public String getModule() {
        return module;
    }

    public GraphNode setModule(String module) {
        this.module = module;
        return this;
    }

    public String getPackageName() {
        return packageName;
    }

    public GraphNode setPackageName(String packageName) {
        this.packageName = packageName;
        return this;
    }

    public String getFileId() {
        return fileId;
    }

    public GraphNode setFileId(String fileId) {
        this.fileId = fileId;
        return this;
    }

    public String getSymbolId() {
        return symbolId;
    }

    public GraphNode setSymbolId(String symbolId) {
        this.symbolId = symbolId;
        return this;
    }

    public String getSignature() {
        return signature;
    }

    public GraphNode setSignature(String signature) {
        this.signature = signature;
        return this;
    }

    public Integer getLineStart() {
        return lineStart;
    }

    public GraphNode setLineStart(Integer lineStart) {
        this.lineStart = lineStart;
        return this;
    }

    public Integer getLineEnd() {
        return lineEnd;
    }

    public GraphNode setLineEnd(Integer lineEnd) {
        this.lineEnd = lineEnd;
        return this;
    }

    public Attribution getAttribution() {
        return attribution;
    }

    public GraphNode setAttribution(Attribution attribution) {
        this.attribution = attribution;
        return this;
    }

    public List<String> getAnnotations() {
        return annotations;
    }

    public GraphNode annotation(String value) {
        annotations.add(value);
        return this;
    }

    public Map<String, Object> getProperties() {
        return properties;
    }

    public GraphNode property(String key, Object value) {
        properties.put(key, value);
        return this;
    }

    /** Stable identity fingerprint used by Graph Diff to detect semantic change, not reordering. */
    /**
     * Run-scoped fingerprint. Includes the node id, so it changes when identity changes - which is
     * the point: it detects id churn within a run.
     */
    public String fingerprint() {
        return type + "|" + id + "|" + (fqn == null ? name : fqn) + "|"
                + (signature == null ? "" : signature) + "|" + attribution;
    }

    /**
     * Identity-independent fingerprint. Two runs over a byte-identical repository allocate different
     * FILE_IDs and therefore different node ids, so a hash built from {@link #fingerprint()} cannot
     * be compared across runs even when nothing about the application changed. This one can.
     */
    public String contentFingerprint() {
        return type + "|" + (fqn == null ? name : fqn) + "|"
                + (signature == null ? "" : signature) + "|" + attribution;
    }
}
