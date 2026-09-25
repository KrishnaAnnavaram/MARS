package com.mars.harness.kernel.core.identity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.mars.harness.kernel.core.KernelJson;
import com.mars.harness.kernel.core.ids.HarnessIds;
import com.mars.harness.kernel.core.identity.observe.CodeObservation;
import com.mars.harness.kernel.core.identity.observe.ObservedStatement;
import com.mars.harness.kernel.core.identity.observe.ObservedSymbol;
import com.mars.harness.kernel.core.identity.observe.ObservedUnit;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Persistent sub-file identity: MODULE_ID, PROGRAM_UNIT_ID, SYMBOL_ID and STATEMENT_ID
 * (ADR-U002).
 *
 * <p>FILE_ID is not held here. It stays in Bootshift's {@code FileRegistry}, which this registry
 * references by ID only, so the file-identity rules remain Bootshift's and cannot drift. Losing
 * this registry loses sub-file lineage exactly as losing Bootshift's registry loses file lineage.
 * That is why it is persisted after every mutation batch and never only at the end of a run.
 */
public final class IdentityRegistry {

    public static final String SCHEMA_VERSION = "1.0";

    public String schemaVersion = SCHEMA_VERSION;
    public String repositoryId;
    public String runId;
    public Map<String, ModuleRecord> modules = new LinkedHashMap<>();
    public Map<String, ProgramUnitRecord> programUnits = new LinkedHashMap<>();
    public Map<String, SymbolRecord> symbols = new LinkedHashMap<>();
    public Map<String, StatementRecord> statements = new LinkedHashMap<>();

    public IdentityRegistry() {
    }

    public static IdentityRegistry create(String runId) {
        IdentityRegistry registry = new IdentityRegistry();
        registry.runId = runId;
        registry.repositoryId = HarnessIds.allocate(HarnessIds.Kind.REPOSITORY);
        return registry;
    }

    // ------------------------------------------------------------------ modules

    public ModuleRecord registerModule(String name, String path, String buildFile, String buildSystem) {
        Optional<ModuleRecord> existing = moduleByPath(path);
        if (existing.isPresent()) {
            return existing.get();
        }
        ModuleRecord module = new ModuleRecord();
        module.moduleId = HarnessIds.allocate(HarnessIds.Kind.MODULE);
        module.name = name;
        module.path = path;
        module.buildFile = buildFile;
        module.buildSystem = buildSystem;
        module.currentKey = path;
        module.versions.add(new IdentityVersion(null, name, null, Location.of(buildFile, 0, 0),
                now(), "INVENTORY_BASELINE"));
        modules.put(module.moduleId, module);
        return module;
    }

    public Optional<ModuleRecord> moduleByPath(String path) {
        return modules.values().stream().filter(m -> m.path.equals(path)).findFirst();
    }

    /** The module owning a repository-relative path: the longest module directory prefix. */
    public Optional<ModuleRecord> moduleForPath(String relativePath) {
        String normalized = relativePath.replace('\\', '/');
        return modules.values().stream()
                .filter(m -> ".".equals(m.path) || normalized.equals(m.path) || normalized.startsWith(m.path + "/"))
                .max(Comparator.comparingInt(m -> ".".equals(m.path) ? 0 : m.path.length()));
    }

    // ------------------------------------------------------------------ baseline allocation

    /**
     * First-scan allocation for one file. Every observed unit, symbol and statement receives a
     * fresh identity, and the method records BASELINE evidence.
     */
    public void allocateBaseline(CodeObservation observation) {
        IdentityReattacher.allocateAll(this, observation, null, ReattachmentMethod.BASELINE,
                "Allocated at inventory baseline");
    }

    ProgramUnitRecord newUnit(CodeObservation observation, ObservedUnit unit, String changeId) {
        ProgramUnitRecord record = new ProgramUnitRecord();
        record.programUnitId = HarnessIds.allocate(HarnessIds.Kind.PROGRAM_UNIT);
        record.moduleId = observation.moduleId();
        record.fileId = observation.fileId();
        record.kind = unit.kind();
        record.baselineFqn = unit.fqn();
        record.baselineLocation = Location.of(observation.path(), unit.lineStart(), unit.lineEnd());
        record.createdByChange = changeId;
        record.recordChange(changeId);
        programUnits.put(record.programUnitId, record);
        return record;
    }

    SymbolRecord newSymbol(CodeObservation observation, ObservedSymbol symbol, String unitId, String changeId) {
        SymbolRecord record = new SymbolRecord();
        record.symbolId = HarnessIds.allocate(HarnessIds.Kind.SYMBOL);
        record.programUnitId = unitId;
        record.moduleId = observation.moduleId();
        record.fileId = observation.fileId();
        record.kind = symbol.kind();
        record.baselineSignature = symbol.signature();
        record.baselineLocation = Location.of(observation.path(), symbol.lineStart(), symbol.lineEnd());
        record.createdByChange = changeId;
        record.recordChange(changeId);
        symbols.put(record.symbolId, record);
        return record;
    }

    StatementRecord newStatement(CodeObservation observation, ObservedStatement statement, String symbolId,
                                 String changeId) {
        StatementRecord record = new StatementRecord();
        record.statementId = HarnessIds.allocate(HarnessIds.Kind.STATEMENT);
        record.parentSymbolId = symbolId;
        record.fileId = observation.fileId();
        record.nodeKind = statement.nodeKind();
        record.baselineFingerprint = statement.fingerprint();
        record.baselineLocation = location(observation.path(), statement);
        record.createdByChange = changeId;
        record.recordChange(changeId);
        statements.put(record.statementId, record);
        return record;
    }

    static Location location(String path, ObservedStatement statement) {
        return new Location(path, statement.lineStart(), statement.colStart(), statement.lineEnd(),
                statement.colEnd());
    }

    // ------------------------------------------------------------------ queries

    public Optional<ProgramUnitRecord> programUnit(String id) {
        return Optional.ofNullable(programUnits.get(id));
    }

    public Optional<SymbolRecord> symbol(String id) {
        return Optional.ofNullable(symbols.get(id));
    }

    public Optional<StatementRecord> statement(String id) {
        return Optional.ofNullable(statements.get(id));
    }

    public Optional<ModuleRecord> module(String id) {
        return Optional.ofNullable(modules.get(id));
    }

    public List<ProgramUnitRecord> activeUnitsInFile(String fileId) {
        return programUnits.values().stream().filter(TrackedIdentity::active)
                .filter(u -> fileId.equals(u.fileId)).toList();
    }

    public List<SymbolRecord> activeSymbolsInFile(String fileId) {
        return symbols.values().stream().filter(TrackedIdentity::active)
                .filter(s -> fileId.equals(s.fileId)).toList();
    }

    public List<SymbolRecord> activeSymbolsInUnit(String programUnitId) {
        return symbols.values().stream().filter(TrackedIdentity::active)
                .filter(s -> programUnitId.equals(s.programUnitId)).toList();
    }

    public List<StatementRecord> activeStatementsInSymbol(String symbolId) {
        return statements.values().stream().filter(TrackedIdentity::active)
                .filter(s -> symbolId.equals(s.parentSymbolId)).toList();
    }

    public List<StatementRecord> activeStatementsInFile(String fileId) {
        return statements.values().stream().filter(TrackedIdentity::active)
                .filter(s -> fileId.equals(s.fileId)).toList();
    }

    /** Innermost active symbol (method, constructor, initializer or field) covering a line. */
    public Optional<SymbolRecord> symbolAt(String fileId, int line) {
        return activeSymbolsInFile(fileId).stream()
                .filter(s -> !"ENDPOINT".equals(s.kind))
                .filter(s -> s.currentLocation != null && s.currentLocation.containsLine(line))
                .min(Comparator.comparingInt(s -> s.currentLocation.lineEnd() - s.currentLocation.lineStart()));
    }

    /** Innermost active statement covering a line. */
    public Optional<StatementRecord> statementAt(String fileId, int line) {
        return activeStatementsInFile(fileId).stream()
                .filter(s -> s.currentLocation != null && s.currentLocation.containsLine(line))
                .min(Comparator.comparingInt((StatementRecord s) ->
                                s.currentLocation.lineEnd() - s.currentLocation.lineStart())
                        .thenComparing(s -> -depthOf(s)));
    }

    public Optional<ProgramUnitRecord> unitAt(String fileId, int line) {
        return activeUnitsInFile(fileId).stream()
                .filter(u -> u.currentLocation != null && u.currentLocation.containsLine(line))
                .min(Comparator.comparingInt(u -> u.currentLocation.lineEnd() - u.currentLocation.lineStart()));
    }

    public Optional<ProgramUnitRecord> unitByFqn(String fqn) {
        return programUnits.values().stream().filter(TrackedIdentity::active)
                .filter(u -> fqn.equals(u.fqn)).findFirst();
    }

    /**
     * Resolves a {@code Type.member} or {@code Type#member} reference, as found in an issue register
     * or a SARIF logical location, to active symbols. The simple type name is accepted, because
     * registers rarely carry FQNs.
     */
    public List<SymbolRecord> symbolsByReference(String reference) {
        String normalized = reference.trim().replace('#', '.').replaceAll("\\(.*\\)$", "");
        int dot = normalized.lastIndexOf('.');
        if (dot <= 0) {
            return List.of();
        }
        String type = normalized.substring(0, dot);
        String member = normalized.substring(dot + 1);
        return symbols.values().stream().filter(TrackedIdentity::active)
                .filter(s -> member.equals(s.name))
                .filter(s -> programUnit(s.programUnitId)
                        .map(u -> type.equals(u.fqn) || type.equals(u.name) || u.fqn.endsWith("." + type))
                        .orElse(false))
                .toList();
    }

    private int depthOf(StatementRecord statement) {
        int depth = 0;
        String parent = statement.parentStatementId;
        while (parent != null && depth < 64) {
            depth++;
            StatementRecord p = statements.get(parent);
            parent = p == null ? null : p.parentStatementId;
        }
        return depth;
    }

    /** Any tracked identity by ID, regardless of level or status. */
    public Optional<TrackedIdentity> any(String id) {
        return Stream.<Map<String, ? extends TrackedIdentity>>of(modules, programUnits, symbols, statements)
                .map(m -> (TrackedIdentity) m.get(id))
                .filter(java.util.Objects::nonNull)
                .findFirst();
    }

    // ------------------------------------------------------------------ coverage & sealing

    public record Coverage(int modules, int programUnits, int symbols, int statements,
                           int activeStatements, int uncertainAttachments, int deleted) {
    }

    public Coverage coverage() {
        int uncertain = (int) Stream.of(programUnits.values(), symbols.values(), statements.values())
                .flatMap(java.util.Collection::stream)
                .mapToLong(r -> ((TrackedIdentity) r).reattachment.stream().filter(ReattachmentEvidence::uncertain).count())
                .sum();
        int deleted = (int) Stream.of(programUnits.values(), symbols.values(), statements.values())
                .flatMap(java.util.Collection::stream)
                .filter(r -> !((TrackedIdentity) r).active())
                .count();
        return new Coverage(modules.size(), programUnits.size(), symbols.size(), statements.size(),
                (int) statements.values().stream().filter(TrackedIdentity::active).count(), uncertain, deleted);
    }

    /** Hash over identity bindings (IDs plus current attributes); changes whenever lineage changes. */
    @JsonIgnore
    public String contentHash() {
        return KernelJson.hash(this);
    }

    public void save(Path file) {
        com.bootshift.core.util.Json.writeAtomic(file, KernelJson.tree(this));
    }

    public static IdentityRegistry load(Path file) {
        return KernelJson.read(file, IdentityRegistry.class);
    }

    static String now() {
        return Instant.now().toString();
    }

    /** Ordered siblings of an active statement group, used by alignment. */
    List<StatementRecord> orderedGroup(List<StatementRecord> members) {
        List<StatementRecord> sorted = new ArrayList<>(members);
        sorted.sort(Comparator.comparingInt(s -> s.index));
        return sorted;
    }
}
