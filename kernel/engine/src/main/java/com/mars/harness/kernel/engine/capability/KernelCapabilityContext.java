package com.mars.harness.kernel.engine.capability;

import com.bootshift.core.identity.FileRecord;
import com.mars.harness.kernel.core.KernelJson;
import com.mars.harness.kernel.core.findings.Finding;
import com.mars.harness.kernel.core.graph.CanonicalGraph;
import com.mars.harness.kernel.core.identity.IdentityRegistry;
import com.mars.harness.kernel.core.policy.UnifiedPolicy;
import com.mars.harness.kernel.engine.EngineConfig;
import com.mars.harness.kernel.engine.exec.WorkspaceSandbox;
import com.mars.harness.kernel.engine.run.RunSession;
import com.mars.harness.kernel.ports.analysis.CodeModelPort;
import com.mars.harness.kernel.ports.build.BuildModelView;
import com.mars.harness.kernel.ports.build.BuildPort;
import com.mars.harness.kernel.ports.capability.CapabilityContext;
import com.mars.harness.kernel.ports.evidence.ArtifactStore;
import com.mars.harness.kernel.ports.evidence.EvidenceStore;
import com.mars.harness.kernel.ports.execution.ExecutionSandbox;
import com.mars.harness.kernel.ports.identity.IdentityView;
import com.mars.harness.kernel.ports.lifecycle.LifecyclePort;
import com.mars.harness.kernel.ports.runtime.RuntimePort;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * The kernel's read-only view for capability packs.
 *
 * <p>Identity is handed out as a copy taken when the context is created. The engine builds a new
 * context after every mutation batch, so capabilities always see current identity and can never
 * alter it.
 */
public final class KernelCapabilityContext implements CapabilityContext {

    private final RunSession session;
    private final EngineConfig config;
    private final IdentityView identityView;
    private final WorkspaceSandbox sandbox;

    public KernelCapabilityContext(RunSession session, EngineConfig config) {
        this.session = session;
        this.config = config;
        this.identityView = new SnapshotIdentityView(session);
        this.sandbox = new WorkspaceSandbox(session.layout);
    }

    @Override
    public String runId() {
        return session.layout.runId();
    }

    @Override
    public UnifiedPolicy policy() {
        return session.policy;
    }

    @Override
    public Path harnessRoot() {
        return config.harnessRoot();
    }

    @Override
    public Optional<String> readWorkspaceFile(String relativePath) {
        return read(session.layout.workspace(), relativePath);
    }

    @Override
    public Optional<String> readSnapshotFile(String relativePath) {
        return read(session.layout.sourceSnapshot(), relativePath);
    }

    private static Optional<String> read(Path root, String relativePath) {
        Path file = root.resolve(relativePath).normalize();
        if (!file.startsWith(root.normalize()) || !Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    @Override
    public IdentityView identity() {
        return identityView;
    }

    @Override
    public CanonicalGraph graph() {
        return session.graph;
    }

    @Override
    public BuildModelView buildModel() {
        return session.buildModel;
    }

    @Override
    public List<Finding> findings() {
        return List.copyOf(session.findings);
    }

    @Override
    public List<Path> findingInputs() {
        return session.record.findingInputs.stream().map(Path::of).toList();
    }

    @Override
    public EvidenceStore evidence() {
        return session.evidence;
    }

    @Override
    public ArtifactStore artifacts() {
        return session.artifacts;
    }

    @Override
    public CodeModelPort codeModel() {
        return config.codeModel();
    }

    @Override
    public BuildPort build() {
        return config.build();
    }

    @Override
    public RuntimePort runtime() {
        return config.runtime();
    }

    @Override
    public LifecyclePort lifecycle() {
        return config.lifecycle();
    }

    @Override
    public ExecutionSandbox sandbox() {
        return sandbox;
    }

    @Override
    public String baselineSeal() {
        return session.baselineSeal();
    }

    @Override
    public LocalDate today() {
        return LocalDate.parse(session.record.today);
    }

    /** Identity view over a defensive copy of the kernel registries. */
    static final class SnapshotIdentityView implements IdentityView {

        private final List<FileInfo> files;
        private final IdentityRegistry registry;

        SnapshotIdentityView(RunSession session) {
            this.files = session.fileRegistry == null ? List.of() : session.fileRegistry.all().stream()
                    .map(SnapshotIdentityView::info).toList();
            this.registry = session.identity == null ? IdentityRegistry.create(session.layout.runId())
                    : KernelJson.convert(KernelJson.tree(session.identity), IdentityRegistry.class);
        }

        private static FileInfo info(FileRecord r) {
            return new FileInfo(r.getFileId(), r.getCurrentPath(), r.getModule(), r.getRole().name(), r.getCurrentSha256(),
                    r.getStatus().name(), r.getLanguage());
        }

        @Override
        public Optional<FileInfo> fileByPath(String path) {
            String normalized = path.replace('\\', '/');
            return files.stream().filter(f -> "ACTIVE".equals(f.status()) && f.path().equals(normalized)).findFirst();
        }

        @Override
        public Optional<FileInfo> fileById(String fileId) {
            return files.stream().filter(f -> f.fileId().equals(fileId)).findFirst();
        }

        @Override
        public List<FileInfo> activeFiles() {
            return files.stream().filter(f -> "ACTIVE".equals(f.status())).toList();
        }

        @Override
        public IdentityRegistry registry() {
            return registry;
        }
    }
}
