package com.bootshift.core.identity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * One persistent file identity.
 *
 * <p>Invariant (R4): {@code fileId != currentPath} and {@code fileId != contentHash}. The record
 * holds all three independently so a rename, an edit, or both together never force a new identity.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class FileRecord {

    private String fileId;
    private String module;
    private String baselinePath;
    private String currentPath;
    private String baselineSha256;
    private String currentSha256;
    private FileRole role = FileRole.UNKNOWN;
    private FileStatus status = FileStatus.ACTIVE;
    private long sizeBytes;
    private String language;
    private boolean generated;

    private RenameSource renameSource = RenameSource.NONE;
    private double renameConfidence;
    private String previousPath;

    private String splitFrom;
    private String mergedInto;
    private String createdByChange;
    private String deletedByChange;

    private final List<String> changeIds = new ArrayList<>();
    private final Set<String> symbolIds = new LinkedHashSet<>();
    private final List<Version> versions = new ArrayList<>();

    /** One point in a file lineage: the content hash after a specific change. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Version(String changeId, String path, String sha256, String at, String reason) {
    }

    public FileRecord() {
    }

    public FileRecord(String fileId, String module, String path, String sha256, FileRole role, long sizeBytes) {
        this.fileId = fileId;
        this.module = module;
        this.baselinePath = path;
        this.currentPath = path;
        this.baselineSha256 = sha256;
        this.currentSha256 = sha256;
        this.role = role;
        this.sizeBytes = sizeBytes;
    }

    public String getFileId() {
        return fileId;
    }

    public void setFileId(String fileId) {
        this.fileId = fileId;
    }

    public String getModule() {
        return module;
    }

    public void setModule(String module) {
        this.module = module;
    }

    public String getBaselinePath() {
        return baselinePath;
    }

    public void setBaselinePath(String baselinePath) {
        this.baselinePath = baselinePath;
    }

    public String getCurrentPath() {
        return currentPath;
    }

    public void setCurrentPath(String currentPath) {
        this.currentPath = currentPath;
    }

    public String getBaselineSha256() {
        return baselineSha256;
    }

    public void setBaselineSha256(String baselineSha256) {
        this.baselineSha256 = baselineSha256;
    }

    public String getCurrentSha256() {
        return currentSha256;
    }

    public void setCurrentSha256(String currentSha256) {
        this.currentSha256 = currentSha256;
    }

    public FileRole getRole() {
        return role;
    }

    public void setRole(FileRole role) {
        this.role = role;
    }

    public FileStatus getStatus() {
        return status;
    }

    public void setStatus(FileStatus status) {
        this.status = status;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public boolean isGenerated() {
        return generated;
    }

    public void setGenerated(boolean generated) {
        this.generated = generated;
    }

    public RenameSource getRenameSource() {
        return renameSource;
    }

    public void setRenameSource(RenameSource renameSource) {
        this.renameSource = renameSource;
    }

    public double getRenameConfidence() {
        return renameConfidence;
    }

    public void setRenameConfidence(double renameConfidence) {
        this.renameConfidence = renameConfidence;
    }

    public String getPreviousPath() {
        return previousPath;
    }

    public void setPreviousPath(String previousPath) {
        this.previousPath = previousPath;
    }

    public String getSplitFrom() {
        return splitFrom;
    }

    public void setSplitFrom(String splitFrom) {
        this.splitFrom = splitFrom;
    }

    public String getMergedInto() {
        return mergedInto;
    }

    public void setMergedInto(String mergedInto) {
        this.mergedInto = mergedInto;
    }

    public String getCreatedByChange() {
        return createdByChange;
    }

    public void setCreatedByChange(String createdByChange) {
        this.createdByChange = createdByChange;
    }

    public String getDeletedByChange() {
        return deletedByChange;
    }

    public void setDeletedByChange(String deletedByChange) {
        this.deletedByChange = deletedByChange;
    }

    public List<String> getChangeIds() {
        return changeIds;
    }

    public Set<String> getSymbolIds() {
        return symbolIds;
    }

    public List<Version> getVersions() {
        return versions;
    }
}
