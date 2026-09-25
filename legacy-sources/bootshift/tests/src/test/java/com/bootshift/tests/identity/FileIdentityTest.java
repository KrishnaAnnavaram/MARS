package com.bootshift.tests.identity;

import com.bootshift.core.identity.FileRecord;
import com.bootshift.core.identity.FileRegistry;
import com.bootshift.core.identity.FileRole;
import com.bootshift.core.identity.FileStatus;
import com.bootshift.core.identity.RenameSource;
import com.bootshift.core.util.Hashing;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * File identity tests (spec section 11).
 *
 * <p>The invariants under test are the ones that make lineage possible: identity is allocated rather
 * than derived, it survives an edit, a rename, and both at once, and deletion never erases it.
 */
class FileIdentityTest {

    private FileRegistry.ObservedFile observed(String path, String content, FileRole role) {
        return new FileRegistry.ObservedFile(path.split("/")[0], path, Hashing.sha256(content),
                role, content.length(), content);
    }

    @Test
    @DisplayName("FILE_ID is neither the path nor the content hash")
    void identityIsNeitherPathNorHash() {
        FileRegistry registry = new FileRegistry();
        String content = "package a; class A {}";
        FileRegistry.Attachment attachment = registry.allocate(
                observed("mod/src/main/java/A.java", content, FileRole.JAVA_MAIN));

        assertThat(attachment.fileId()).startsWith("FILE-");
        assertThat(attachment.fileId()).isNotEqualTo("mod/src/main/java/A.java");
        assertThat(attachment.fileId()).isNotEqualTo(Hashing.sha256(content));
        assertThat(attachment.newlyAllocated()).isTrue();
    }

    @Test
    @DisplayName("identity survives a content edit at the same path")
    void identitySurvivesEdit() {
        FileRegistry registry = new FileRegistry();
        String before = "package a; class A { void x() {} }";
        String fileId = registry.allocate(observed("mod/A.java", before, FileRole.JAVA_MAIN)).fileId();

        String after = "package a; class A { void x() {} void y() {} }";
        registry.beginScan(List.of("mod/A.java"));
        FileRegistry.Attachment reattached = registry.reattach(
                observed("mod/A.java", after, FileRole.JAVA_MAIN), null);

        assertThat(reattached.fileId()).isEqualTo(fileId);
        assertThat(reattached.rule()).isEqualTo("EXACT_PATH");
        assertThat(registry.byId(fileId).orElseThrow().getCurrentSha256())
                .isEqualTo(Hashing.sha256(after));
        assertThat(registry.byId(fileId).orElseThrow().getBaselineSha256())
                .isEqualTo(Hashing.sha256(before));
    }

    @Test
    @DisplayName("a provider-reported rename keeps the identity and records the source")
    void providerRenameKeepsIdentity() {
        FileRegistry registry = new FileRegistry();
        String content = "package a; class SecurityConfig {}";
        String fileId = registry.allocate(
                observed("mod/SecurityConfig.java", content, FileRole.JAVA_MAIN)).fileId();

        registry.beginScan(List.of("mod/ApplicationSecurityConfiguration.java"));
        FileRegistry.Attachment reattached = registry.reattach(
                observed("mod/ApplicationSecurityConfiguration.java", content, FileRole.JAVA_MAIN),
                new FileRegistry.RenameHint("mod/SecurityConfig.java",
                        "mod/ApplicationSecurityConfiguration.java", RenameSource.PROVIDER, 1.0));

        assertThat(reattached.fileId()).isEqualTo(fileId);
        assertThat(reattached.rule()).isEqualTo("PROVIDER_RENAME");
        assertThat(reattached.source()).isEqualTo(RenameSource.PROVIDER);
        FileRecord record = registry.byId(fileId).orElseThrow();
        assertThat(record.getBaselinePath()).isEqualTo("mod/SecurityConfig.java");
        assertThat(record.getCurrentPath()).isEqualTo("mod/ApplicationSecurityConfiguration.java");
        assertThat(record.getPreviousPath()).isEqualTo("mod/SecurityConfig.java");
    }

    @Test
    @DisplayName("an unreported rename is recovered by exact content hash")
    void contentHashRecoversRename() {
        FileRegistry registry = new FileRegistry();
        String content = "package a; class Moved { int value; }";
        String fileId = registry.allocate(observed("mod/old/Moved.java", content, FileRole.JAVA_MAIN))
                .fileId();

        registry.beginScan(List.of("mod/new/Moved.java"));
        FileRegistry.Attachment reattached = registry.reattach(
                observed("mod/new/Moved.java", content, FileRole.JAVA_MAIN), null);

        assertThat(reattached.fileId()).isEqualTo(fileId);
        assertThat(reattached.rule()).isEqualTo("EXACT_CONTENT_HASH");
    }

    @Test
    @DisplayName("a rename plus an edit is recovered by similarity, with the confidence recorded")
    void similarityRecoversRenameWithEdit() {
        FileRegistry registry = new FileRegistry(0.5);
        String before = """
                package com.example.service;
                import org.springframework.stereotype.Service;
                @Service
                public class EmployeeService {
                    public String find(String id) { return repository.findById(id); }
                    public void save(Object employee) { repository.save(employee); }
                    public void delete(String id) { repository.deleteById(id); }
                }
                """;
        String fileId = registry.allocate(
                observed("mod/EmployeeService.java", before, FileRole.JAVA_MAIN)).fileId();

        String after = before.replace("EmployeeService", "EmployeeApplicationService")
                + "// added during migration\n";

        registry.beginScan(List.of("mod/EmployeeApplicationService.java"));
        FileRegistry.Attachment reattached = registry.reattach(
                observed("mod/EmployeeApplicationService.java", after, FileRole.JAVA_MAIN), null);

        assertThat(reattached.fileId()).isEqualTo(fileId);
        assertThat(reattached.rule()).isEqualTo("SIMILARITY");
        assertThat(reattached.confidence()).isGreaterThanOrEqualTo(0.5).isLessThan(1.0);
        assertThat(registry.byId(fileId).orElseThrow().getRenameSource())
                .isEqualTo(RenameSource.SIMILARITY);
    }

    @Test
    @DisplayName("an unrelated file gets a new identity rather than stealing one")
    void unrelatedFileGetsNewIdentity() {
        FileRegistry registry = new FileRegistry();
        String original = registry.allocate(
                observed("mod/A.java", "package a; class A {}", FileRole.JAVA_MAIN)).fileId();

        registry.beginScan(List.of("mod/A.java", "mod/Z.java"));
        registry.reattach(observed("mod/A.java", "package a; class A {}", FileRole.JAVA_MAIN), null);
        FileRegistry.Attachment fresh = registry.reattach(
                observed("mod/Z.java", "package z; interface Zebra { void stripes(); }",
                        FileRole.JAVA_MAIN), null);

        assertThat(fresh.fileId()).isNotEqualTo(original);
        assertThat(fresh.newlyAllocated()).isTrue();
        assertThat(fresh.rule()).isEqualTo("ALLOCATE_NEW");
    }

    @Test
    @DisplayName("a split keeps the original identity on the survivor and marks the siblings")
    void splitRecordsLineage() {
        FileRegistry registry = new FileRegistry();
        String original = registry.allocate(
                observed("mod/Big.java", "class Big { void a() {} void b() {} }", FileRole.JAVA_MAIN))
                .fileId();

        FileRegistry.Attachment sibling = registry.recordSplit(original,
                observed("mod/BigPartTwo.java", "class BigPartTwo { void b() {} }", FileRole.JAVA_MAIN),
                "CHANGE-000042");

        assertThat(sibling.fileId()).isNotEqualTo(original);
        FileRecord siblingRecord = registry.byId(sibling.fileId()).orElseThrow();
        assertThat(siblingRecord.getSplitFrom()).isEqualTo(original);
        assertThat(siblingRecord.getCreatedByChange()).isEqualTo("CHANGE-000042");
        assertThat(registry.byId(original).orElseThrow().getStatus()).isEqualTo(FileStatus.ACTIVE);
    }

    @Test
    @DisplayName("a merge leaves the source identity queryable")
    void mergeKeepsSourceQueryable() {
        FileRegistry registry = new FileRegistry();
        String target = registry.allocate(
                observed("mod/Target.java", "class Target {}", FileRole.JAVA_MAIN)).fileId();
        String source = registry.allocate(
                observed("mod/Source.java", "class Source {}", FileRole.JAVA_MAIN)).fileId();

        registry.recordMerge(source, target, "CHANGE-000050");

        FileRecord sourceRecord = registry.byId(source).orElseThrow();
        assertThat(sourceRecord.getStatus()).isEqualTo(FileStatus.MERGED_AWAY);
        assertThat(sourceRecord.getMergedInto()).isEqualTo(target);
        assertThat(registry.byPath("mod/Source.java")).isEmpty();
        assertThat(registry.byId(source)).isPresent();
    }

    @Test
    @DisplayName("deletion never erases identity")
    void deletionPreservesIdentity() {
        FileRegistry registry = new FileRegistry();
        String fileId = registry.allocate(
                observed("mod/Gone.java", "class Gone {}", FileRole.JAVA_MAIN)).fileId();

        registry.recordDelete(fileId, "CHANGE-000060");

        FileRecord record = registry.byId(fileId).orElseThrow();
        assertThat(record.getStatus()).isEqualTo(FileStatus.DELETED);
        assertThat(record.getDeletedByChange()).isEqualTo("CHANGE-000060");
        assertThat(record.getBaselinePath()).isEqualTo("mod/Gone.java");
        assertThat(registry.active()).noneMatch(r -> r.getFileId().equals(fileId));
    }

    @Test
    @DisplayName("lineage records every version transition in order")
    void lineageIsComplete() {
        FileRegistry registry = new FileRegistry();
        String fileId = registry.allocate(
                observed("mod/Config.java", "class Config { int a; }", FileRole.JAVA_MAIN)).fileId();

        registry.appendVersion(fileId, "CHANGE-000087", "mod/Config.java",
                Hashing.sha256("class Config { int b; }"), "deterministic security migration");
        registry.appendVersion(fileId, "CHANGE-000091", "mod/ApplicationConfig.java",
                Hashing.sha256("class ApplicationConfig { int b; }"), "compile repair");

        FileRecord record = registry.byId(fileId).orElseThrow();
        assertThat(record.getVersions()).hasSize(3);
        assertThat(record.getVersions().get(0).changeId()).isNull();
        assertThat(record.getVersions().get(1).changeId()).isEqualTo("CHANGE-000087");
        assertThat(record.getVersions().get(2).changeId()).isEqualTo("CHANGE-000091");
        assertThat(record.getCurrentPath()).isEqualTo("mod/ApplicationConfig.java");
        assertThat(record.getBaselinePath()).isEqualTo("mod/Config.java");
        assertThat(record.getChangeIds()).containsExactly("CHANGE-000087", "CHANGE-000091");
    }

    @Test
    @DisplayName("the registry seal changes when a baseline binding changes")
    void sealDetectsBaselineChange() {
        FileRegistry first = new FileRegistry();
        first.allocate(observed("mod/A.java", "class A {}", FileRole.JAVA_MAIN));
        String sealOne = first.seal();

        FileRegistry second = new FileRegistry();
        second.allocate(observed("mod/A.java", "class A { int changed; }", FileRole.JAVA_MAIN));
        String sealTwo = second.seal();

        assertThat(sealOne).isNotEqualTo(sealTwo);
        assertThat(sealOne).hasSize(64);
    }

    @Test
    @DisplayName("a registry survives a serialization round trip")
    void registryRoundTrips() {
        FileRegistry registry = new FileRegistry();
        String fileId = registry.allocate(
                observed("mod/A.java", "class A {}", FileRole.JAVA_MAIN)).fileId();
        registry.appendVersion(fileId, "CHANGE-000001", "mod/A.java",
                Hashing.sha256("class A { int x; }"), "edit");
        String seal = registry.seal();

        FileRegistry restored = FileRegistry.fromNode(registry.toNode());

        assertThat(restored.size()).isEqualTo(registry.size());
        assertThat(restored.sealHash()).isEqualTo(seal);
        assertThat(restored.byId(fileId).orElseThrow().getVersions()).hasSize(2);
    }
}
