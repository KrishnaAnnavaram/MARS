package com.bootshift.core.graph;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A typed, directed relationship between two graph nodes.
 *
 * <p>Runtime-observed edges carry {@code evidenceRef} pointing at the observation that produced
 * them. A runtime edge never overwrites a static edge; both coexist so the final report can say
 * whether a relationship was inferred, observed, or both.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class GraphEdge {

    private String from;
    private String to;
    private EdgeType type;
    private String view;
    private String evidenceRef;
    private double confidence = 1.0;
    private final Map<String, Object> properties = new LinkedHashMap<>();

    public GraphEdge() {
    }

    public GraphEdge(String from, EdgeType type, String to, String view) {
        this.from = from;
        this.type = type;
        this.to = to;
        this.view = view;
    }

    public String getFrom() {
        return from;
    }

    public void setFrom(String from) {
        this.from = from;
    }

    public String getTo() {
        return to;
    }

    public void setTo(String to) {
        this.to = to;
    }

    public EdgeType getType() {
        return type;
    }

    public void setType(EdgeType type) {
        this.type = type;
    }

    public String getView() {
        return view;
    }

    public void setView(String view) {
        this.view = view;
    }

    public String getEvidenceRef() {
        return evidenceRef;
    }

    public GraphEdge setEvidenceRef(String evidenceRef) {
        this.evidenceRef = evidenceRef;
        return this;
    }

    public double getConfidence() {
        return confidence;
    }

    public GraphEdge setConfidence(double confidence) {
        this.confidence = confidence;
        return this;
    }

    public Map<String, Object> getProperties() {
        return properties;
    }

    public GraphEdge property(String key, Object value) {
        properties.put(key, value);
        return this;
    }

    public String key() {
        return from + "-[" + type + "]->" + to;
    }
}
