package com.bootshift.core.ledger;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * An immutable record of one change <em>attempt</em> (spec section 26).
 *
 * <p>Rejected, failed and reverted attempts are recorded exactly like applied ones (R14): the
 * ledger is a history of what the harness tried, not a list of what survived.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class ChangeEvent {

    public enum Operation {
        CREATE, MODIFY, DELETE, RENAME, SPLIT, MERGE
    }

    public enum Status {
        PROPOSED, APPLIED, VALIDATED, REJECTED, FAILED_VALIDATION, REVERTED
    }

    /** Which tool actually produced the patch. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Provider(String type, String name, String version) {
    }

    /** Provenance for an AI-authored proposal (spec section 29). Null for deterministic changes. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AiProvenance(String modelIdentity, String modelVersion, String runtime,
                               String promptHash, String contextHash, String responseHash,
                               List<String> verificationResults, String outcome) {
    }

    @JsonProperty("change_id")
    private String changeId;
    @JsonProperty("sequence")
    private long sequence;
    @JsonProperty("run_id")
    private String runId;
    @JsonProperty("edge_id")
    private String edgeId;
    @JsonProperty("file_id")
    private String fileId;
    @JsonProperty("operation")
    private Operation operation;
    @JsonProperty("path_before")
    private String pathBefore;
    @JsonProperty("path_after")
    private String pathAfter;
    @JsonProperty("before_sha256")
    private String beforeSha256;
    @JsonProperty("after_sha256")
    private String afterSha256;
    @JsonProperty("agent")
    private String agent;
    @JsonProperty("provider")
    private Provider provider;
    @JsonProperty("recipe_id")
    private String recipeId;
    @JsonProperty("knowledge_refs")
    private List<String> knowledgeRefs = new ArrayList<>();
    @JsonProperty("impact_refs")
    private List<String> impactRefs = new ArrayList<>();
    @JsonProperty("symbols_changed")
    private List<String> symbolsChanged = new ArrayList<>();
    @JsonProperty("patch_ref")
    private String patchRef;
    @JsonProperty("status")
    private Status status = Status.PROPOSED;
    @JsonProperty("rejection_reason")
    private String rejectionReason;
    @JsonProperty("split_from")
    private String splitFrom;
    @JsonProperty("merged_into")
    private String mergedInto;
    @JsonProperty("ai")
    private AiProvenance ai;
    @JsonProperty("recorded_at")
    private String recordedAt;

    public String getChangeId() {
        return changeId;
    }

    public ChangeEvent setChangeId(String changeId) {
        this.changeId = changeId;
        return this;
    }

    public long getSequence() {
        return sequence;
    }

    public ChangeEvent setSequence(long sequence) {
        this.sequence = sequence;
        return this;
    }

    public String getRunId() {
        return runId;
    }

    public ChangeEvent setRunId(String runId) {
        this.runId = runId;
        return this;
    }

    public String getEdgeId() {
        return edgeId;
    }

    public ChangeEvent setEdgeId(String edgeId) {
        this.edgeId = edgeId;
        return this;
    }

    public String getFileId() {
        return fileId;
    }

    public ChangeEvent setFileId(String fileId) {
        this.fileId = fileId;
        return this;
    }

    public Operation getOperation() {
        return operation;
    }

    public ChangeEvent setOperation(Operation operation) {
        this.operation = operation;
        return this;
    }

    public String getPathBefore() {
        return pathBefore;
    }

    public ChangeEvent setPathBefore(String pathBefore) {
        this.pathBefore = pathBefore;
        return this;
    }

    public String getPathAfter() {
        return pathAfter;
    }

    public ChangeEvent setPathAfter(String pathAfter) {
        this.pathAfter = pathAfter;
        return this;
    }

    public String getBeforeSha256() {
        return beforeSha256;
    }

    public ChangeEvent setBeforeSha256(String beforeSha256) {
        this.beforeSha256 = beforeSha256;
        return this;
    }

    public String getAfterSha256() {
        return afterSha256;
    }

    public ChangeEvent setAfterSha256(String afterSha256) {
        this.afterSha256 = afterSha256;
        return this;
    }

    public String getAgent() {
        return agent;
    }

    public ChangeEvent setAgent(String agent) {
        this.agent = agent;
        return this;
    }

    public Provider getProvider() {
        return provider;
    }

    public ChangeEvent setProvider(Provider provider) {
        this.provider = provider;
        return this;
    }

    public String getRecipeId() {
        return recipeId;
    }

    public ChangeEvent setRecipeId(String recipeId) {
        this.recipeId = recipeId;
        return this;
    }

    public List<String> getKnowledgeRefs() {
        return knowledgeRefs;
    }

    public ChangeEvent setKnowledgeRefs(List<String> knowledgeRefs) {
        this.knowledgeRefs = knowledgeRefs;
        return this;
    }

    public List<String> getImpactRefs() {
        return impactRefs;
    }

    public ChangeEvent setImpactRefs(List<String> impactRefs) {
        this.impactRefs = impactRefs;
        return this;
    }

    public List<String> getSymbolsChanged() {
        return symbolsChanged;
    }

    public ChangeEvent setSymbolsChanged(List<String> symbolsChanged) {
        this.symbolsChanged = symbolsChanged;
        return this;
    }

    public String getPatchRef() {
        return patchRef;
    }

    public ChangeEvent setPatchRef(String patchRef) {
        this.patchRef = patchRef;
        return this;
    }

    public Status getStatus() {
        return status;
    }

    public ChangeEvent setStatus(Status status) {
        this.status = status;
        return this;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public ChangeEvent setRejectionReason(String rejectionReason) {
        this.rejectionReason = rejectionReason;
        return this;
    }

    public String getSplitFrom() {
        return splitFrom;
    }

    public ChangeEvent setSplitFrom(String splitFrom) {
        this.splitFrom = splitFrom;
        return this;
    }

    public String getMergedInto() {
        return mergedInto;
    }

    public ChangeEvent setMergedInto(String mergedInto) {
        this.mergedInto = mergedInto;
        return this;
    }

    public AiProvenance getAi() {
        return ai;
    }

    public ChangeEvent setAi(AiProvenance ai) {
        this.ai = ai;
        return this;
    }

    public String getRecordedAt() {
        return recordedAt;
    }

    public ChangeEvent setRecordedAt(String recordedAt) {
        this.recordedAt = recordedAt;
        return this;
    }
}
