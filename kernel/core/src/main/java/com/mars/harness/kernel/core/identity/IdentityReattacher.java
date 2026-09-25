package com.mars.harness.kernel.core.identity;

import com.mars.harness.kernel.core.identity.observe.CodeObservation;
import com.mars.harness.kernel.core.identity.observe.ObservedStatement;
import com.mars.harness.kernel.core.identity.observe.ObservedSymbol;
import com.mars.harness.kernel.core.identity.observe.ObservedUnit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.ToDoubleBiFunction;

/**
 * Evidence-based reattachment of program-unit, symbol and statement identity after an authorized
 * mutation batch (spec §7.4, §20).
 *
 * <p>Order of evidence, tried strictly in this order at every level:
 *
 * <ol>
 *   <li>transformation-provider explicit node mapping</li>
 *   <li>AST-diff mapping: sequence alignment of sibling statements, plus in-place update of the
 *       aligned slot</li>
 *   <li>exact normalised fingerprint under the same parent</li>
 *   <li>structural similarity under the same or reattached parent</li>
 *   <li>contextual matching across parents (moved method or statement)</li>
 *   <li>otherwise a new identity</li>
 * </ol>
 *
 * <p>Every non-exact decision records method, confidence, evidence and old and new locations. A
 * candidate that does not beat the runner-up by the policy margin is <b>not</b> attached. The
 * reattacher allocates a new identity and records the competing candidates. Uncertain matches
 * between the low and high thresholds are attached with {@link Certainty#LOW} and flagged. They
 * are never presented as exact.
 */
public final class IdentityReattacher {

    /**
     * One changed file in the batch.
     *
     * @param lineageFileIds FILE_IDs whose previous identities may flow into this file: the file
     *                       itself (which survives renames via Bootshift's registry), a split
     *                       parent, and merge sources
     * @param observation    the new observation, or null when the file was deleted
     */
    public record FileChange(String fileId, List<String> lineageFileIds, CodeObservation observation,
                             boolean deleted, List<String> mergedSourceFileIds, String splitFromFileId) {

        public FileChange {
            lineageFileIds = lineageFileIds == null ? List.of(fileId) : List.copyOf(lineageFileIds);
            mergedSourceFileIds = mergedSourceFileIds == null ? List.of() : List.copyOf(mergedSourceFileIds);
        }

        public static FileChange modified(String fileId, CodeObservation observation) {
            return new FileChange(fileId, List.of(fileId), observation, false, List.of(), null);
        }

        public static FileChange deletedFile(String fileId) {
            return new FileChange(fileId, List.of(fileId), null, true, List.of(), null);
        }
    }

    /**
     * @param changeId      default CHANGE_ID for the batch
     * @param changeByFile  the CHANGE_ID the ledger assigned to each file's mutation; identity
     *                      versions cite the change that actually touched their file
     */
    public record Request(String changeId, List<FileChange> files, ProviderHints hints, Map<String, String> changeByFile) {
        public Request {
            files = List.copyOf(files);
            hints = hints == null ? ProviderHints.none() : hints;
            changeByFile = changeByFile == null ? Map.of() : Map.copyOf(changeByFile);
        }

        public Request(String changeId, List<FileChange> files, ProviderHints hints) {
            this(changeId, files, hints, Map.of());
        }
    }

    private static final String UNIT = "PROGRAM_UNIT";
    private static final String SYMBOL = "SYMBOL";
    private static final String STATEMENT = "STATEMENT";

    private final IdentityRegistry registry;
    private final ReattachmentPolicy policy;
    private final String changeId;
    private final ProviderHints hints;
    private final ReattachmentReport report = new ReattachmentReport();
    private Map<String, String> changeByFile = Map.of();

    /** Old identities available for reattachment in this batch, by level. */
    private final Map<String, ProgramUnitRecord> oldUnits = new LinkedHashMap<>();
    private final Map<String, SymbolRecord> oldSymbols = new LinkedHashMap<>();
    private final Map<String, StatementRecord> oldStatements = new LinkedHashMap<>();

    private final Set<String> matchedOld = new HashSet<>();
    /** Pre-batch member signatures and statement texts: split/merge lineage compares against these, not the updated records. */
    private final Map<String, List<String>> oldMembers = new HashMap<>();
    private final Map<String, String> oldTexts = new HashMap<>();
    /** New observation (file-scoped key) to identity. */
    private final Map<String, String> unitIds = new HashMap<>();
    private final Map<String, String> symbolIds = new HashMap<>();
    private final Map<String, String> statementIds = new HashMap<>();

    /** Where each new observation came from. */
    private final Map<String, CodeObservation> observationOfKey = new HashMap<>();
    private final List<CodeObservation> observations = new ArrayList<>();
    private final Set<String> mergeSourceFiles = new HashSet<>();

    private IdentityReattacher(IdentityRegistry registry, ReattachmentPolicy policy, String changeId,
                               ProviderHints hints) {
        this.registry = registry;
        this.policy = policy;
        this.changeId = changeId;
        this.hints = hints;
        this.report.changeId = changeId;
    }

    // ================================================================== entry points

    public static ReattachmentReport reattach(IdentityRegistry registry, Request request,
                                              ReattachmentPolicy policy) {
        IdentityReattacher r = new IdentityReattacher(registry, policy, request.changeId(), request.hints());
        r.changeByFile = request.changeByFile();
        r.run(request);
        return r.report;
    }

    /** Allocates every observed entity as new: the baseline scan, or a brand-new file. */
    static void allocateAll(IdentityRegistry registry, CodeObservation observation, String changeId,
                            ReattachmentMethod method, String evidence) {
        IdentityReattacher r = new IdentityReattacher(registry, ReattachmentPolicy.defaults(), changeId,
                ProviderHints.none());
        r.register(observation);
        for (ObservedUnit unit : observation.units()) {
            r.allocateUnit(observation, unit, method, evidence, List.of());
        }
        for (ObservedSymbol symbol : observation.symbols()) {
            r.allocateSymbol(observation, symbol, method, evidence, List.of());
        }
        for (ObservedStatement statement : observation.statements()) {
            r.allocateStatement(observation, statement, method, evidence, List.of());
        }
        r.linkParents();
    }

    // ================================================================== pass

    private void run(Request request) {
        for (FileChange change : request.files()) {
            mergeSourceFiles.addAll(change.mergedSourceFileIds());
        }
        // Pool of old identities: everything active in any file the batch touched or draws from.
        Set<String> poolFiles = new HashSet<>();
        for (FileChange change : request.files()) {
            poolFiles.add(change.fileId());
            poolFiles.addAll(change.lineageFileIds());
            poolFiles.addAll(change.mergedSourceFileIds());
            if (change.splitFromFileId() != null) {
                poolFiles.add(change.splitFromFileId());
            }
            if (change.observation() != null) {
                register(change.observation());
            }
        }
        for (String fileId : poolFiles) {
            registry.activeUnitsInFile(fileId).forEach(u -> {
                oldUnits.put(u.programUnitId, u);
                oldMembers.put(u.programUnitId, List.copyOf(u.memberSignatures));
            });
            registry.activeSymbolsInFile(fileId).forEach(s -> oldSymbols.put(s.symbolId, s));
            registry.activeStatementsInFile(fileId).forEach(s -> {
                oldStatements.put(s.statementId, s);
                oldTexts.put(s.statementId, s.currentText == null ? "" : s.currentText);
            });
        }
        // A split parent is not itself changed by a CREATE of its sibling. Its identities stay put
        // unless the parent file is also in the batch, so only files actually re-observed or
        // deleted can lose identities below.
        Set<String> retiringFiles = new HashSet<>();
        for (FileChange change : request.files()) {
            retiringFiles.add(change.fileId());
            retiringFiles.addAll(change.mergedSourceFileIds());
        }

        // ---- program units
        for (FileChange change : request.files()) {
            if (change.observation() != null) {
                matchUnitsLocally(change);
            }
        }
        matchUnitsContextually();
        allocateRemainingUnits();

        // ---- symbols
        for (CodeObservation observation : observations) {
            for (ObservedUnit unit : observation.units()) {
                String unitId = unitIds.get(scoped(observation, unit.key()));
                ProgramUnitRecord old = unitId == null ? null : oldUnits.get(unitId);
                if (old != null) {
                    matchSymbolsLocally(observation, old, unit);
                }
            }
        }
        matchSymbolsContextually();
        allocateRemainingSymbols();

        // ---- statements
        for (CodeObservation observation : observations) {
            for (ObservedSymbol symbol : observation.symbols()) {
                String symbolId = symbolIds.get(scoped(observation, symbol.key()));
                SymbolRecord old = symbolId == null ? null : oldSymbols.get(symbolId);
                if (old != null) {
                    alignStatements(observation, old, symbol);
                }
            }
        }
        matchStatementsContextually();
        allocateRemainingStatements();
        linkParents();

        // ---- retire what was not reattached
        retireUnmatched(retiringFiles);
    }

    private void register(CodeObservation observation) {
        observations.add(observation);
        observation.units().forEach(u -> observationOfKey.put(scoped(observation, u.key()), observation));
        observation.symbols().forEach(s -> observationOfKey.put(scoped(observation, s.key()), observation));
        observation.statements().forEach(s -> observationOfKey.put(scoped(observation, s.key()), observation));
    }

    private String changeFor(String fileId) {
        return fileId == null ? changeId : changeByFile.getOrDefault(fileId, changeId);
    }

    private static String scoped(CodeObservation observation, String key) {
        return observation.fileId() + "::" + key;
    }

    // ================================================================== program units

    private void matchUnitsLocally(FileChange change) {
        CodeObservation obs = change.observation();
        List<ObservedUnit> pending = new ArrayList<>(obs.units());
        List<ProgramUnitRecord> candidates = oldUnits.values().stream()
                .filter(u -> change.lineageFileIds().contains(u.fileId) || change.fileId().equals(u.fileId))
                .toList();

        // 1. provider mapping
        for (ObservedUnit unit : List.copyOf(pending)) {
            for (Map.Entry<String, String> hint : hints.unitRenames().entrySet()) {
                if (!hint.getValue().equals(unit.fqn())) {
                    continue;
                }
                Optional<ProgramUnitRecord> old = unmatchedUnits(candidates).stream()
                        .filter(u -> hint.getKey().equals(u.fqn)).findFirst();
                if (old.isPresent()) {
                    attachUnit(obs, unit, old.get(), ReattachmentMethod.PROVIDER_MAPPING, 1.0, Certainty.EXACT,
                            "Provider asserted rename " + hint.getKey() + " -> " + unit.fqn(), List.of());
                    pending.remove(unit);
                }
            }
        }
        // 2. exact fully qualified name
        for (ObservedUnit unit : List.copyOf(pending)) {
            Optional<ProgramUnitRecord> old = unmatchedUnits(candidates).stream()
                    .filter(u -> unit.fqn().equals(u.fqn)).findFirst();
            if (old.isPresent()) {
                attachUnit(obs, unit, old.get(), ReattachmentMethod.EXACT_NAME, 1.0, Certainty.EXACT,
                        "Identical fully qualified name " + unit.fqn(), List.of());
                pending.remove(unit);
            }
        }
        // 3. file lineage: the only unmatched unit of its kind on both sides of a known FILE_ID
        for (ObservedUnit unit : List.copyOf(pending)) {
            List<ProgramUnitRecord> sameFileSameKind = unmatchedUnits(candidates).stream()
                    .filter(u -> change.fileId().equals(u.fileId) && Objects.equals(u.kind, unit.kind()))
                    .toList();
            long newOfKind = pending.stream().filter(p -> Objects.equals(p.kind(), unit.kind())).count();
            if (sameFileSameKind.size() == 1 && newOfKind == 1) {
                ProgramUnitRecord old = sameFileSameKind.get(0);
                double memberSimilarity = TokenSimilarity.dice(old.memberSignatures, unit.memberSignatures());
                attachUnit(obs, unit, old, ReattachmentMethod.FILE_LINEAGE, 0.95, Certainty.HIGH,
                        "Only " + unit.kind() + " in " + change.fileId() + " on both sides; FILE_ID lineage from "
                                + "the file registry; member similarity " + round(memberSimilarity),
                        List.of());
                pending.remove(unit);
            }
        }
        // 4. structural: member signature similarity, mutual best with margin
        List<ProgramUnitRecord> remaining = unmatchedUnits(candidates);
        mutualBest(remaining, pending,
                (old, unit) -> Objects.equals(old.kind, unit.kind())
                        ? TokenSimilarity.dice(old.memberSignatures, unit.memberSignatures()) : 0.0,
                policy.unitSimilarity(),
                (old, unit, score) -> attachUnit(obs, unit, old, ReattachmentMethod.STRUCTURAL_SIMILARITY,
                        score, capHigh(score >= policy.unitSimilarity() + 0.2 ? Certainty.HIGH : Certainty.LOW),
                        "Member-signature similarity " + round(score) + " (renamed or moved type)", List.of()),
                (unit, cands) -> ambiguous.put(scoped(obs, unit.key()), cands),
                u -> u.programUnitId);
    }

    private final Map<String, List<String>> ambiguous = new HashMap<>();

    private void matchUnitsContextually() {
        List<ObservedUnit> pendingNew = new ArrayList<>();
        Map<ObservedUnit, CodeObservation> owner = new HashMap<>();
        for (CodeObservation obs : observations) {
            for (ObservedUnit unit : obs.units()) {
                if (!unitIds.containsKey(scoped(obs, unit.key()))) {
                    pendingNew.add(unit);
                    owner.put(unit, obs);
                }
            }
        }
        List<ProgramUnitRecord> pendingOld = unmatchedUnits(oldUnits.values());
        mutualBest(pendingOld, pendingNew,
                (old, unit) -> Objects.equals(old.kind, unit.kind()) && !old.memberSignatures.isEmpty()
                        ? TokenSimilarity.dice(old.memberSignatures, unit.memberSignatures()) : 0.0,
                0.9,
                (old, unit, score) -> attachUnit(owner.get(unit), unit, old, ReattachmentMethod.CONTEXTUAL, score,
                        Certainty.HIGH, "Type moved across files; member-signature similarity " + round(score),
                        List.of()),
                (unit, cands) -> ambiguous.merge(scoped(owner.get(unit), unit.key()), cands, IdentityReattacher::concat),
                u -> u.programUnitId);
    }

    private void allocateRemainingUnits() {
        for (CodeObservation obs : observations) {
            for (ObservedUnit unit : obs.units()) {
                String key = scoped(obs, unit.key());
                if (!unitIds.containsKey(key)) {
                    List<String> cands = ambiguous.getOrDefault(key, List.of());
                    ProgramUnitRecord created = allocateUnit(obs, unit, ReattachmentMethod.ALLOCATED_NEW,
                            cands.isEmpty() ? "No acceptable lineage evidence"
                                    : "Ambiguous: candidates did not clear the margin; new identity allocated",
                            cands);
                    // split lineage: a new unit whose members largely came from a surviving old unit
                    oldUnits.values().stream()
                            .filter(o -> !oldMembers.getOrDefault(o.programUnitId, List.of()).isEmpty())
                            .filter(o -> containmentOfMembers(unit.memberSignatures(), oldMembers.get(o.programUnitId)) >= 0.8)
                            .max(Comparator.comparingDouble(o -> containmentOfMembers(unit.memberSignatures(),
                                    oldMembers.get(o.programUnitId))))
                            .ifPresent(o -> created.splitFrom = o.programUnitId);
                }
            }
        }
    }

    private List<ProgramUnitRecord> unmatchedUnits(java.util.Collection<ProgramUnitRecord> candidates) {
        return candidates.stream().filter(u -> !matchedOld.contains(u.programUnitId)).toList();
    }

    private void attachUnit(CodeObservation obs, ObservedUnit unit, ProgramUnitRecord old,
                            ReattachmentMethod method, double confidence, Certainty certainty, String evidence,
                            List<String> candidates) {
        matchedOld.add(old.programUnitId);
        unitIds.put(scoped(obs, unit.key()), old.programUnitId);
        Location oldLoc = old.currentLocation;
        Location newLoc = Location.of(obs.path(), unit.lineStart(), unit.lineEnd());
        boolean renamed = !Objects.equals(old.fqn, unit.fqn());
        boolean moved = !Objects.equals(old.fileId, obs.fileId()) || oldLoc == null
                || !Objects.equals(oldLoc.path(), newLoc.path());
        boolean membersChanged = !old.memberSignatures.equals(unit.memberSignatures());
        String changeId = changeFor(obs.fileId());
        applyUnit(old, obs, unit);
        if (renamed || moved || membersChanged || method != ReattachmentMethod.EXACT_NAME) {
            old.recordChange(changeId);
            old.versions.add(new IdentityVersion(changeId, unit.fqn(), null, newLoc, IdentityRegistry.now(),
                    method.name() + (renamed ? " rename" : "") + (moved ? " move" : "")));
            ReattachmentEvidence ev = new ReattachmentEvidence(changeId, method, confidence, certainty, evidence,
                    oldLoc, newLoc, candidates, IdentityRegistry.now());
            old.reattachment.add(ev);
            report.add(new ReattachmentReport.Entry(UNIT, old.programUnitId, ReattachmentReport.REATTACHED, method,
                    confidence, certainty, evidence, oldLoc, newLoc, candidates));
        } else {
            report.add(new ReattachmentReport.Entry(UNIT, old.programUnitId, ReattachmentReport.UNCHANGED, method,
                    confidence, certainty, evidence, oldLoc, newLoc, List.of()));
        }
    }

    private ProgramUnitRecord allocateUnit(CodeObservation obs, ObservedUnit unit, ReattachmentMethod method,
                                           String evidence, List<String> candidates) {
        String changeId = changeFor(obs.fileId());
        ProgramUnitRecord record = registry.newUnit(obs, unit, changeId);
        applyUnit(record, obs, unit);
        Location loc = record.currentLocation;
        record.versions.add(new IdentityVersion(changeId, unit.fqn(), null, loc, IdentityRegistry.now(),
                method.name()));
        Certainty certainty = method == ReattachmentMethod.BASELINE ? Certainty.EXACT : Certainty.NONE;
        record.reattachment.add(new ReattachmentEvidence(changeId, method, method == ReattachmentMethod.BASELINE ? 1.0 : 0.0,
                certainty, evidence, null, loc, candidates, IdentityRegistry.now()));
        unitIds.put(scoped(obs, unit.key()), record.programUnitId);
        if (method != ReattachmentMethod.BASELINE) {
            report.add(new ReattachmentReport.Entry(UNIT, record.programUnitId, ReattachmentReport.ALLOCATED, method,
                    0.0, certainty, evidence, null, loc, candidates));
        }
        return record;
    }

    private static void applyUnit(ProgramUnitRecord record, CodeObservation obs, ObservedUnit unit) {
        record.fileId = obs.fileId();
        record.moduleId = obs.moduleId();
        record.kind = unit.kind();
        record.name = unit.name();
        record.fqn = unit.fqn();
        record.packageName = unit.packageName();
        record.currentLocation = Location.of(obs.path(), unit.lineStart(), unit.lineEnd());
        record.currentKey = unit.key();
        record.memberSignatures = new ArrayList<>(unit.memberSignatures());
        record.annotations = new ArrayList<>(unit.annotations());
    }

    // ================================================================== symbols

    private void matchSymbolsLocally(CodeObservation obs, ProgramUnitRecord oldUnit, ObservedUnit newUnit) {
        List<SymbolRecord> olds = oldSymbols.values().stream()
                .filter(s -> oldUnit.programUnitId.equals(s.programUnitId))
                .filter(s -> !"ENDPOINT".equals(s.kind))
                .toList();
        List<ObservedSymbol> pending = new ArrayList<>(obs.symbols().stream()
                .filter(s -> newUnit.key().equals(s.unitKey()))
                .filter(s -> !"ENDPOINT".equals(s.kind()))
                .toList());

        // 1. provider mapping
        for (ObservedSymbol symbol : List.copyOf(pending)) {
            for (SymbolRecord old : unmatchedSymbols(olds)) {
                String mapped = hints.symbolRenames().get(oldUnit.fqn + "#" + old.signature);
                if (mapped != null && mapped.equals(symbol.signature())) {
                    attachSymbol(obs, symbol, old, ReattachmentMethod.PROVIDER_MAPPING, 1.0, Certainty.EXACT,
                            "Provider asserted " + old.signature + " -> " + symbol.signature(), List.of());
                    pending.remove(symbol);
                    break;
                }
            }
        }
        // 2. exact signature and kind
        for (ObservedSymbol symbol : List.copyOf(pending)) {
            Optional<SymbolRecord> old = unmatchedSymbols(olds).stream()
                    .filter(s -> Objects.equals(s.kind, symbol.kind()) && Objects.equals(s.signature, symbol.signature()))
                    .findFirst();
            if (old.isPresent()) {
                attachSymbol(obs, symbol, old.get(), ReattachmentMethod.EXACT_NAME, 1.0, Certainty.EXACT,
                        "Identical signature " + symbol.signature(), List.of());
                pending.remove(symbol);
            }
        }
        // 3. same name and kind, changed signature; unique on both sides
        for (ObservedSymbol symbol : List.copyOf(pending)) {
            List<SymbolRecord> sameName = unmatchedSymbols(olds).stream()
                    .filter(s -> Objects.equals(s.kind, symbol.kind()) && Objects.equals(s.name, symbol.name()))
                    .toList();
            long newSameName = pending.stream()
                    .filter(p -> Objects.equals(p.kind(), symbol.kind()) && Objects.equals(p.name(), symbol.name()))
                    .count();
            if (sameName.size() == 1 && newSameName == 1) {
                attachSymbol(obs, symbol, sameName.get(0), ReattachmentMethod.SIGNATURE_CHANGED, 0.9, Certainty.HIGH,
                        "Same name and kind, signature changed " + sameName.get(0).signature + " -> "
                                + symbol.signature(), List.of());
                pending.remove(symbol);
            }
        }
        // 4. body similarity (renamed method), mutual best with margin
        mutualBest(unmatchedSymbols(olds), pending,
                (old, sym) -> Objects.equals(old.kind, sym.kind())
                        ? TokenSimilarity.dice(old.statementFingerprints, sym.statementFingerprints()) : 0.0,
                policy.symbolLow(),
                (old, sym, score) -> attachSymbol(obs, sym, old, ReattachmentMethod.STRUCTURAL_SIMILARITY, score,
                        capHigh(policy.symbolCertainty(score)),
                        "Body similarity " + round(score) + " under the same program unit (rename "
                                + old.name + " -> " + sym.name() + ")", List.of()),
                (sym, cands) -> ambiguous.put(scoped(obs, sym.key()), cands),
                s -> s.symbolId);
    }

    private void matchSymbolsContextually() {
        List<ObservedSymbol> pendingNew = new ArrayList<>();
        Map<ObservedSymbol, CodeObservation> owner = new HashMap<>();
        for (CodeObservation obs : observations) {
            for (ObservedSymbol symbol : obs.symbols()) {
                if (!"ENDPOINT".equals(symbol.kind()) && !symbolIds.containsKey(scoped(obs, symbol.key()))) {
                    pendingNew.add(symbol);
                    owner.put(symbol, obs);
                }
            }
        }
        List<SymbolRecord> pendingOld = unmatchedSymbols(oldSymbols.values()).stream()
                .filter(s -> !"ENDPOINT".equals(s.kind)).toList();
        mutualBest(pendingOld, pendingNew,
                (old, sym) -> {
                    if (!Objects.equals(old.kind, sym.kind())) {
                        return 0.0;
                    }
                    double body = TokenSimilarity.dice(old.statementFingerprints, sym.statementFingerprints());
                    boolean sameSignature = Objects.equals(old.signature, sym.signature());
                    return sameSignature ? Math.max(body, old.statementFingerprints.isEmpty() ? 0.0 : body) : body;
                },
                policy.symbolHigh(),
                (old, sym, score) -> attachSymbol(owner.get(sym), sym, old, ReattachmentMethod.CONTEXTUAL, score,
                        capHigh(policy.symbolCertainty(score)),
                        "Moved across program units; body similarity " + round(score), List.of()),
                (sym, cands) -> ambiguous.merge(scoped(owner.get(sym), sym.key()), cands, IdentityReattacher::concat),
                s -> s.symbolId);
    }

    private void allocateRemainingSymbols() {
        // methods, constructors, fields, initializers first; endpoints hang off their handler
        for (CodeObservation obs : observations) {
            for (ObservedSymbol symbol : obs.symbols()) {
                if ("ENDPOINT".equals(symbol.kind())) {
                    continue;
                }
                String key = scoped(obs, symbol.key());
                if (!symbolIds.containsKey(key)) {
                    List<String> cands = ambiguous.getOrDefault(key, List.of());
                    allocateSymbol(obs, symbol, ReattachmentMethod.ALLOCATED_NEW,
                            cands.isEmpty() ? "No acceptable lineage evidence"
                                    : "Ambiguous: candidates did not clear the margin; new identity allocated",
                            cands);
                }
            }
        }
        for (CodeObservation obs : observations) {
            for (ObservedSymbol endpoint : obs.symbols()) {
                if (!"ENDPOINT".equals(endpoint.kind())) {
                    continue;
                }
                String parentId = endpoint.parentSymbolKey() == null ? null
                        : symbolIds.get(scoped(obs, endpoint.parentSymbolKey()));
                List<SymbolRecord> underParent = unmatchedSymbols(oldSymbols.values()).stream()
                        .filter(s -> "ENDPOINT".equals(s.kind) && parentId != null && parentId.equals(s.parentSymbolId))
                        .toList();
                Optional<SymbolRecord> exact = underParent.stream()
                        .filter(s -> Objects.equals(s.signature, endpoint.signature())).findFirst();
                if (exact.isPresent()) {
                    attachSymbol(obs, endpoint, exact.get(), ReattachmentMethod.EXACT_NAME, 1.0, Certainty.EXACT,
                            "Same route under the same handler", List.of());
                } else if (underParent.size() == 1) {
                    attachSymbol(obs, endpoint, underParent.get(0), ReattachmentMethod.SIGNATURE_CHANGED, 0.85,
                            Certainty.HIGH, "Route changed " + underParent.get(0).signature + " -> "
                                    + endpoint.signature() + " on the same handler lineage", List.of());
                } else {
                    allocateSymbol(obs, endpoint, ReattachmentMethod.ALLOCATED_NEW, "New route", List.of());
                }
            }
        }
    }

    private List<SymbolRecord> unmatchedSymbols(java.util.Collection<SymbolRecord> candidates) {
        return candidates.stream().filter(s -> !matchedOld.contains(s.symbolId)).toList();
    }

    private void attachSymbol(CodeObservation obs, ObservedSymbol symbol, SymbolRecord old,
                              ReattachmentMethod method, double confidence, Certainty certainty, String evidence,
                              List<String> candidates) {
        matchedOld.add(old.symbolId);
        symbolIds.put(scoped(obs, symbol.key()), old.symbolId);
        Location oldLoc = old.currentLocation;
        Location newLoc = Location.of(obs.path(), symbol.lineStart(), symbol.lineEnd());
        boolean changed = !Objects.equals(old.signature, symbol.signature())
                || !old.statementFingerprints.equals(symbol.statementFingerprints())
                || !Objects.equals(old.fileId, obs.fileId())
                || !Objects.equals(unitIdFor(obs, symbol), old.programUnitId)
                || method != ReattachmentMethod.EXACT_NAME;
        String changeId = changeFor(obs.fileId());
        applySymbol(old, obs, symbol);
        if (changed) {
            old.recordChange(changeId);
            old.versions.add(new IdentityVersion(changeId, symbol.signature(), bodyHash(symbol), newLoc,
                    IdentityRegistry.now(), method.name()));
            old.reattachment.add(new ReattachmentEvidence(changeId, method, confidence, certainty, evidence, oldLoc,
                    newLoc, candidates, IdentityRegistry.now()));
            report.add(new ReattachmentReport.Entry(SYMBOL, old.symbolId, ReattachmentReport.REATTACHED, method,
                    confidence, certainty, evidence, oldLoc, newLoc, candidates));
        } else {
            report.add(new ReattachmentReport.Entry(SYMBOL, old.symbolId, ReattachmentReport.UNCHANGED, method,
                    confidence, certainty, evidence, oldLoc, newLoc, List.of()));
        }
    }

    private SymbolRecord allocateSymbol(CodeObservation obs, ObservedSymbol symbol, ReattachmentMethod method,
                                        String evidence, List<String> candidates) {
        String changeId = changeFor(obs.fileId());
        SymbolRecord record = registry.newSymbol(obs, symbol, unitIdFor(obs, symbol), changeId);
        applySymbol(record, obs, symbol);
        Location loc = record.currentLocation;
        record.versions.add(new IdentityVersion(changeId, symbol.signature(), bodyHash(symbol), loc,
                IdentityRegistry.now(), method.name()));
        Certainty certainty = method == ReattachmentMethod.BASELINE ? Certainty.EXACT : Certainty.NONE;
        record.reattachment.add(new ReattachmentEvidence(changeId, method, method == ReattachmentMethod.BASELINE ? 1.0 : 0.0,
                certainty, evidence, null, loc, candidates, IdentityRegistry.now()));
        symbolIds.put(scoped(obs, symbol.key()), record.symbolId);
        if (method != ReattachmentMethod.BASELINE) {
            report.add(new ReattachmentReport.Entry(SYMBOL, record.symbolId, ReattachmentReport.ALLOCATED, method, 0.0,
                    certainty, evidence, null, loc, candidates));
        }
        return record;
    }

    private void applySymbol(SymbolRecord record, CodeObservation obs, ObservedSymbol symbol) {
        record.fileId = obs.fileId();
        record.moduleId = obs.moduleId();
        record.programUnitId = unitIdFor(obs, symbol);
        record.parentSymbolId = symbol.parentSymbolKey() == null ? null
                : symbolIds.get(scoped(obs, symbol.parentSymbolKey()));
        record.kind = symbol.kind();
        record.name = symbol.name();
        record.signature = symbol.signature();
        ObservedUnit unit = obs.units().stream().filter(u -> u.key().equals(symbol.unitKey())).findFirst().orElse(null);
        record.fqn = unit == null ? symbol.name() : unit.fqn() + "." + symbol.name();
        record.currentLocation = Location.of(obs.path(), symbol.lineStart(), symbol.lineEnd());
        record.currentKey = symbol.key();
        record.annotations = new ArrayList<>(symbol.annotations());
        record.statementFingerprints = new ArrayList<>(symbol.statementFingerprints());
    }

    private String unitIdFor(CodeObservation obs, ObservedSymbol symbol) {
        return symbol.unitKey() == null ? null : unitIds.get(scoped(obs, symbol.unitKey()));
    }

    private static String bodyHash(ObservedSymbol symbol) {
        return symbol.statementFingerprints().isEmpty() ? null
                : com.bootshift.core.util.Hashing.sha256(String.join(",", symbol.statementFingerprints())).substring(0, 16);
    }

    // ================================================================== statements

    /**
     * AST-diff style alignment of one method body, top-down: sibling groups are aligned only after
     * their parent statements were matched.
     */
    private void alignStatements(CodeObservation obs, SymbolRecord oldSymbol, ObservedSymbol newSymbol) {
        List<StatementRecord> olds = oldStatements.values().stream()
                .filter(s -> oldSymbol.symbolId.equals(s.parentSymbolId)).toList();
        List<ObservedStatement> news = obs.statements().stream()
                .filter(s -> newSymbol.key().equals(s.symbolKey())).toList();

        // 1. provider explicit mapping: the provider says which statement it rewrote and to what
        for (StatementRecord old : olds) {
            String rewritten = hints.statementRewrites().get(old.statementId);
            if (rewritten == null) {
                continue;
            }
            String normalizedHint = normalizeHint(rewritten);
            List<ObservedStatement> targets = news.stream()
                    .filter(n -> !statementIds.containsKey(scoped(obs, n.key())))
                    .filter(n -> n.normalizedText().equals(normalizedHint))
                    .toList();
            if (targets.size() == 1) {
                attachStatement(obs, targets.get(0), old, ReattachmentMethod.PROVIDER_MAPPING, 1.0, Certainty.EXACT,
                        "Provider asserted rewrite of " + old.statementId, List.of());
            } else {
                report.ignoredHints.add("statement rewrite hint for " + old.statementId + " matched "
                        + targets.size() + " statements; not trusted");
            }
        }

        // 2. top-down AST-diff over sibling groups
        java.util.ArrayDeque<String[]> queue = new java.util.ArrayDeque<>();
        queue.add(new String[]{null, null});
        Set<String> visited = new HashSet<>();
        while (!queue.isEmpty()) {
            String[] parents = queue.poll();
            String oldParent = parents[0];
            String newParentKey = parents[1];
            if (!visited.add(oldParent + "|" + newParentKey)) {
                continue;
            }
            Set<String> slots = new java.util.TreeSet<>();
            olds.stream().filter(s -> Objects.equals(s.parentStatementId, oldParent)).forEach(s -> slots.add(s.slot));
            news.stream().filter(s -> Objects.equals(s.parentStatementKey(), newParentKey)).forEach(s -> slots.add(s.slot()));
            for (String slot : slots) {
                List<StatementRecord> oldGroup = olds.stream()
                        .filter(s -> Objects.equals(s.parentStatementId, oldParent) && Objects.equals(s.slot, slot))
                        .sorted(Comparator.comparingInt(s -> s.index)).toList();
                List<ObservedStatement> newGroup = news.stream()
                        .filter(s -> Objects.equals(s.parentStatementKey(), newParentKey) && Objects.equals(s.slot(), slot))
                        .sorted(Comparator.comparingInt(ObservedStatement::index)).toList();
                alignGroup(obs, oldGroup, newGroup);
            }
            // descend into matched pairs
            for (ObservedStatement n : news) {
                if (!Objects.equals(n.parentStatementKey(), newParentKey)) {
                    continue;
                }
                String id = statementIds.get(scoped(obs, n.key()));
                if (id != null && oldStatements.containsKey(id)) {
                    queue.add(new String[]{id, n.key()});
                }
            }
        }

        // 3. exact fingerprint under the same parent symbol, out of alignment order (a move)
        Map<String, List<StatementRecord>> oldByFp = new LinkedHashMap<>();
        unmatchedStatements(olds).forEach(s -> oldByFp.computeIfAbsent(s.currentFingerprint, k -> new ArrayList<>()).add(s));
        Map<String, List<ObservedStatement>> newByFp = new LinkedHashMap<>();
        news.stream().filter(n -> !statementIds.containsKey(scoped(obs, n.key())))
                .forEach(n -> newByFp.computeIfAbsent(n.fingerprint(), k -> new ArrayList<>()).add(n));
        for (Map.Entry<String, List<ObservedStatement>> entry : newByFp.entrySet()) {
            List<StatementRecord> sameOld = oldByFp.getOrDefault(entry.getKey(), List.of());
            List<ObservedStatement> sameNew = entry.getValue();
            if (sameOld.isEmpty()) {
                continue;
            }
            if (sameOld.size() == 1 && sameNew.size() == 1) {
                attachStatement(obs, sameNew.get(0), sameOld.get(0), ReattachmentMethod.EXACT_FINGERPRINT, 1.0,
                        Certainty.EXACT, "Identical normalised statement under the same symbol, moved", List.of());
            } else if (sameOld.size() == sameNew.size()) {
                List<StatementRecord> o = sameOld.stream().sorted(Comparator.comparingInt(s -> s.index)).toList();
                List<ObservedStatement> nn = sameNew.stream().sorted(Comparator.comparingInt(ObservedStatement::index)).toList();
                for (int i = 0; i < o.size(); i++) {
                    attachStatement(obs, nn.get(i), o.get(i), ReattachmentMethod.EXACT_FINGERPRINT, 0.9, Certainty.HIGH,
                            "Ordinal pairing among " + o.size() + " identical statements under the same symbol",
                            List.of());
                }
            } else {
                for (ObservedStatement n : sameNew) {
                    ambiguous.merge(scoped(obs, n.key()),
                            sameOld.stream().map(s -> s.statementId + "@1.00").toList(), IdentityReattacher::concat);
                }
            }
        }

        // 4. structural similarity under the same parent symbol, same node kind
        List<ObservedStatement> pendingNew = news.stream()
                .filter(n -> !statementIds.containsKey(scoped(obs, n.key()))).toList();
        mutualBest(unmatchedStatements(olds), pendingNew,
                (old, n) -> Objects.equals(old.nodeKind, n.nodeKind())
                        ? TokenSimilarity.sequence(old.currentText, n.normalizedText()) : 0.0,
                policy.statementLow(),
                (old, n, score) -> attachStatement(obs, n, old, ReattachmentMethod.STRUCTURAL_SIMILARITY, score,
                        capHigh(policy.statementCertainty(score)),
                        "Token similarity " + round(score) + " to the same-kind statement under the same symbol",
                        List.of()),
                (n, cands) -> ambiguous.merge(scoped(obs, n.key()), cands, IdentityReattacher::concat),
                s -> s.statementId);
    }

    private void alignGroup(CodeObservation obs, List<StatementRecord> oldGroup, List<ObservedStatement> newGroup) {
        List<StatementRecord> olds = unmatchedStatements(oldGroup);
        List<ObservedStatement> news = newGroup.stream()
                .filter(n -> !statementIds.containsKey(scoped(obs, n.key()))).toList();
        List<String> oldFps = olds.stream().map(s -> s.currentFingerprint).toList();
        List<String> newFps = news.stream().map(ObservedStatement::fingerprint).toList();
        List<int[]> anchors = TokenSimilarity.align(oldFps, newFps);
        for (int[] pair : anchors) {
            attachStatement(obs, news.get(pair[1]), olds.get(pair[0]), ReattachmentMethod.AST_DIFF, 1.0,
                    Certainty.EXACT, "Aligned identical statement in the same sibling sequence", List.of());
        }
        // in-place updates: gaps between consecutive anchors
        int prevOld = -1;
        int prevNew = -1;
        List<int[]> bounds = new ArrayList<>(anchors);
        bounds.add(new int[]{olds.size(), news.size()});
        for (int[] bound : bounds) {
            List<StatementRecord> oldGap = olds.subList(prevOld + 1, bound[0]);
            List<ObservedStatement> newGap = news.subList(prevNew + 1, bound[1]);
            if (!oldGap.isEmpty() && !newGap.isEmpty()) {
                mutualBest(oldGap, newGap,
                        (old, n) -> Objects.equals(old.nodeKind, n.nodeKind())
                                ? TokenSimilarity.sequence(old.currentText, n.normalizedText()) : 0.0,
                        policy.statementLow(),
                        (old, n, score) -> attachStatement(obs, n, old, ReattachmentMethod.AST_DIFF_UPDATE, score,
                                capHigh(policy.statementCertainty(score)),
                                "Same slot in the aligned sibling sequence, contents updated; token similarity "
                                        + round(score), List.of()),
                        (n, cands) -> ambiguous.merge(scoped(obs, n.key()), cands, IdentityReattacher::concat),
                        s -> s.statementId);
            }
            prevOld = bound[0];
            prevNew = bound[1];
        }
    }

    private void matchStatementsContextually() {
        List<ObservedStatement> pendingNew = new ArrayList<>();
        Map<ObservedStatement, CodeObservation> owner = new HashMap<>();
        for (CodeObservation obs : observations) {
            for (ObservedStatement s : obs.statements()) {
                if (!statementIds.containsKey(scoped(obs, s.key()))) {
                    pendingNew.add(s);
                    owner.put(s, obs);
                }
            }
        }
        Map<String, List<StatementRecord>> oldByFp = new LinkedHashMap<>();
        unmatchedStatements(oldStatements.values())
                .forEach(s -> oldByFp.computeIfAbsent(s.currentFingerprint, k -> new ArrayList<>()).add(s));
        Map<String, List<ObservedStatement>> newByFp = new LinkedHashMap<>();
        pendingNew.forEach(n -> newByFp.computeIfAbsent(n.fingerprint(), k -> new ArrayList<>()).add(n));
        for (Map.Entry<String, List<ObservedStatement>> entry : newByFp.entrySet()) {
            List<StatementRecord> sameOld = oldByFp.getOrDefault(entry.getKey(), List.of());
            if (sameOld.size() == 1 && entry.getValue().size() == 1) {
                ObservedStatement n = entry.getValue().get(0);
                attachStatement(owner.get(n), n, sameOld.get(0), ReattachmentMethod.CONTEXTUAL, 0.85, Certainty.HIGH,
                        "Identical normalised statement moved to a different parent symbol", List.of());
            } else if (!sameOld.isEmpty()) {
                for (ObservedStatement n : entry.getValue()) {
                    ambiguous.merge(scoped(owner.get(n), n.key()),
                            sameOld.stream().map(s -> s.statementId + "@0.85").toList(), IdentityReattacher::concat);
                }
            }
        }
    }

    private void allocateRemainingStatements() {
        for (CodeObservation obs : observations) {
            for (ObservedStatement statement : obs.statements()) {
                String key = scoped(obs, statement.key());
                if (!statementIds.containsKey(key)) {
                    List<String> cands = ambiguous.getOrDefault(key, List.of());
                    StatementRecord created = allocateStatement(obs, statement, ReattachmentMethod.ALLOCATED_NEW,
                            cands.isEmpty() ? "No acceptable lineage evidence"
                                    : "Ambiguous or below threshold: candidates recorded, new identity allocated",
                            cands);
                    // split lineage: most of this new statement's tokens came from one old statement
                    oldStatements.values().stream()
                            .filter(o -> Objects.equals(o.parentSymbolId, created.parentSymbolId))
                            .filter(o -> TokenSimilarity.tokens(statement.normalizedText()).size() >= 3)
                            .filter(o -> TokenSimilarity.containment(statement.normalizedText(), oldTexts.get(o.statementId)) >= 0.8)
                            .max(Comparator.comparingDouble(o -> TokenSimilarity.containment(statement.normalizedText(),
                                    oldTexts.get(o.statementId))))
                            .ifPresent(o -> created.splitFrom = o.statementId);
                }
            }
        }
    }

    private List<StatementRecord> unmatchedStatements(java.util.Collection<StatementRecord> candidates) {
        return candidates.stream().filter(s -> !matchedOld.contains(s.statementId)).toList();
    }

    private void attachStatement(CodeObservation obs, ObservedStatement statement, StatementRecord old,
                                 ReattachmentMethod method, double confidence, Certainty certainty, String evidence,
                                 List<String> candidates) {
        matchedOld.add(old.statementId);
        statementIds.put(scoped(obs, statement.key()), old.statementId);
        Location oldLoc = old.currentLocation;
        Location newLoc = IdentityRegistry.location(obs.path(), statement);
        boolean contentChanged = !Objects.equals(old.currentFingerprint, statement.fingerprint());
        String newSymbolId = symbolIds.get(scoped(obs, statement.symbolKey()));
        boolean reparented = !Objects.equals(old.parentSymbolId, newSymbolId);
        boolean moved = reparented || method == ReattachmentMethod.EXACT_FINGERPRINT
                || method == ReattachmentMethod.CONTEXTUAL || !Objects.equals(old.fileId, obs.fileId());
        String changeId = changeFor(obs.fileId());
        applyStatement(old, obs, statement, newSymbolId);
        if (contentChanged || moved || method == ReattachmentMethod.PROVIDER_MAPPING) {
            old.recordChange(changeId);
            old.versions.add(new IdentityVersion(changeId, statement.normalizedText(), statement.fingerprint(),
                    newLoc, IdentityRegistry.now(), method.name() + (contentChanged ? " edit" : "") + (moved ? " move" : "")));
            old.reattachment.add(new ReattachmentEvidence(changeId, method, confidence, certainty, evidence, oldLoc,
                    newLoc, candidates, IdentityRegistry.now()));
            report.add(new ReattachmentReport.Entry(STATEMENT, old.statementId, ReattachmentReport.REATTACHED, method,
                    confidence, certainty, evidence, oldLoc, newLoc, candidates));
        } else {
            report.add(new ReattachmentReport.Entry(STATEMENT, old.statementId, ReattachmentReport.UNCHANGED, method,
                    confidence, certainty, evidence, oldLoc, newLoc, List.of()));
        }
    }

    private StatementRecord allocateStatement(CodeObservation obs, ObservedStatement statement,
                                              ReattachmentMethod method, String evidence, List<String> candidates) {
        String symbolId = symbolIds.get(scoped(obs, statement.symbolKey()));
        String changeId = changeFor(obs.fileId());
        StatementRecord record = registry.newStatement(obs, statement, symbolId, changeId);
        applyStatement(record, obs, statement, symbolId);
        Location loc = record.currentLocation;
        record.versions.add(new IdentityVersion(changeId, statement.normalizedText(), statement.fingerprint(), loc,
                IdentityRegistry.now(), method.name()));
        Certainty certainty = method == ReattachmentMethod.BASELINE ? Certainty.EXACT : Certainty.NONE;
        record.reattachment.add(new ReattachmentEvidence(changeId, method, method == ReattachmentMethod.BASELINE ? 1.0 : 0.0,
                certainty, evidence, null, loc, candidates, IdentityRegistry.now()));
        statementIds.put(scoped(obs, statement.key()), record.statementId);
        if (method != ReattachmentMethod.BASELINE) {
            report.add(new ReattachmentReport.Entry(STATEMENT, record.statementId, ReattachmentReport.ALLOCATED, method,
                    0.0, certainty, evidence, null, loc, candidates));
        }
        return record;
    }

    private static void applyStatement(StatementRecord record, CodeObservation obs, ObservedStatement statement,
                                       String symbolId) {
        record.fileId = obs.fileId();
        record.parentSymbolId = symbolId;
        record.slot = statement.slot();
        record.index = statement.index();
        record.nodeKind = statement.nodeKind();
        record.currentFingerprint = statement.fingerprint();
        record.currentText = statement.normalizedText();
        record.currentLocation = IdentityRegistry.location(obs.path(), statement);
        record.currentKey = statement.key();
    }

    /** Resolves parent-statement links once every statement in the batch has an identity. */
    private void linkParents() {
        for (CodeObservation obs : observations) {
            for (ObservedStatement statement : obs.statements()) {
                String id = statementIds.get(scoped(obs, statement.key()));
                StatementRecord record = id == null ? null : registry.statements.get(id);
                if (record != null) {
                    record.parentStatementId = statement.parentStatementKey() == null ? null
                            : statementIds.get(scoped(obs, statement.parentStatementKey()));
                }
            }
        }
    }

    // ================================================================== retirement

    private void retireUnmatched(Set<String> retiringFiles) {
        for (ProgramUnitRecord old : oldUnits.values()) {
            if (matchedOld.contains(old.programUnitId) || !retiringFiles.contains(old.fileId)) {
                continue;
            }
            // merge: this unit's members now live inside a unit that survived
            Optional<ProgramUnitRecord> absorbedBy = registry.programUnits.values().stream()
                    .filter(TrackedIdentity::active)
                    .filter(u -> !u.programUnitId.equals(old.programUnitId))
                    .filter(u -> matchedOld.contains(u.programUnitId)
                            || u.createdByChange != null && u.createdByChange.equals(changeFor(u.fileId)))
                    .filter(u -> !old.memberSignatures.isEmpty()
                            && containmentOfMembers(old.memberSignatures, u.memberSignatures) >= 0.8)
                    .findFirst();
            if (absorbedBy.isPresent() && mergeSourceFiles.contains(old.fileId)) {
                retire(old, UNIT, IdentityStatus.MERGED_AWAY, absorbedBy.get().programUnitId,
                        "Members absorbed by " + absorbedBy.get().programUnitId + " in a MERGE");
            } else {
                retire(old, UNIT, IdentityStatus.DELETED, null, "No surviving program unit matched");
            }
        }
        for (SymbolRecord old : oldSymbols.values()) {
            if (!matchedOld.contains(old.symbolId) && retiringFiles.contains(old.fileId)) {
                retire(old, SYMBOL, IdentityStatus.DELETED, null, "No surviving symbol matched");
            }
        }
        for (StatementRecord old : oldStatements.values()) {
            if (matchedOld.contains(old.statementId) || !retiringFiles.contains(old.fileId)) {
                continue;
            }
            // merge: the deleted statement's tokens now sit inside one surviving statement of the same lineage
            List<StatementRecord> absorbers = registry.statements.values().stream()
                    .filter(TrackedIdentity::active)
                    .filter(s -> Objects.equals(s.parentSymbolId, old.parentSymbolId))
                    .filter(s -> s.changeIds.contains(changeFor(s.fileId)))
                    .filter(s -> TokenSimilarity.tokens(old.currentText).size() >= 3)
                    .filter(s -> TokenSimilarity.containment(old.currentText, s.currentText) >= 0.8)
                    .toList();
            if (absorbers.size() == 1) {
                retire(old, STATEMENT, IdentityStatus.MERGED_AWAY, absorbers.get(0).statementId,
                        "Contents absorbed by " + absorbers.get(0).statementId);
            } else {
                retire(old, STATEMENT, IdentityStatus.DELETED, null, "No surviving statement matched");
            }
        }
    }

    private void retire(TrackedIdentity record, String level, IdentityStatus status, String mergedInto, String evidence) {
        String changeId = changeFor(record.fileId);
        record.status = status;
        record.deletedByChange = changeId;
        record.mergedInto = mergedInto;
        record.recordChange(changeId);
        record.versions.add(new IdentityVersion(changeId, null, null, record.currentLocation, IdentityRegistry.now(),
                status.name()));
        record.reattachment.add(new ReattachmentEvidence(changeId, ReattachmentMethod.ALLOCATED_NEW, 0.0, Certainty.NONE,
                evidence, record.currentLocation, null, List.of(), IdentityRegistry.now()));
        report.add(new ReattachmentReport.Entry(level, record.id(),
                status == IdentityStatus.MERGED_AWAY ? ReattachmentReport.MERGED_AWAY : ReattachmentReport.DELETED,
                ReattachmentMethod.ALLOCATED_NEW, 0.0, Certainty.NONE, evidence, record.currentLocation, null,
                mergedInto == null ? List.of() : List.of(mergedInto)));
    }

    // ================================================================== matching utilities

    @FunctionalInterface
    private interface Accept<O, N> {
        void accept(O old, N candidate, double score);
    }

    /**
     * One-to-one matching: a pair is accepted only when each side is the other's best candidate
     * and each best beats its runner-up by the ambiguity margin. Pairs that score above
     * {@code min} but fail the margin are reported as ambiguous and left unattached.
     */
    private <O, N> void mutualBest(List<O> olds, List<N> news, ToDoubleBiFunction<O, N> score, double min,
                                   Accept<O, N> accept, java.util.function.BiConsumer<N, List<String>> onAmbiguous,
                                   java.util.function.Function<O, String> idOf) {
        List<O> remainingOld = new ArrayList<>(olds);
        List<N> remainingNew = new ArrayList<>(news);
        boolean progress = true;
        while (progress && !remainingOld.isEmpty() && !remainingNew.isEmpty()) {
            progress = false;
            double[][] matrix = new double[remainingOld.size()][remainingNew.size()];
            for (int i = 0; i < remainingOld.size(); i++) {
                for (int j = 0; j < remainingNew.size(); j++) {
                    matrix[i][j] = score.applyAsDouble(remainingOld.get(i), remainingNew.get(j));
                }
            }
            List<int[]> accepted = new ArrayList<>();
            for (int j = 0; j < remainingNew.size(); j++) {
                int bestI = -1;
                double best = -1;
                double second = -1;
                for (int i = 0; i < remainingOld.size(); i++) {
                    if (matrix[i][j] > best) {
                        second = best;
                        best = matrix[i][j];
                        bestI = i;
                    } else if (matrix[i][j] > second) {
                        second = matrix[i][j];
                    }
                }
                if (bestI < 0 || best < min || best - Math.max(second, 0) < policy.ambiguityMargin() && second >= min) {
                    continue;
                }
                // the old side must agree
                double oldBest = -1;
                int oldBestJ = -1;
                double oldSecond = -1;
                for (int k = 0; k < remainingNew.size(); k++) {
                    if (matrix[bestI][k] > oldBest) {
                        oldSecond = oldBest;
                        oldBest = matrix[bestI][k];
                        oldBestJ = k;
                    } else if (matrix[bestI][k] > oldSecond) {
                        oldSecond = matrix[bestI][k];
                    }
                }
                if (oldBestJ != j || oldBest - Math.max(oldSecond, 0) < policy.ambiguityMargin() && oldSecond >= min) {
                    continue;
                }
                accepted.add(new int[]{bestI, j});
            }
            if (!accepted.isEmpty()) {
                progress = true;
                List<O> doneOld = new ArrayList<>();
                List<N> doneNew = new ArrayList<>();
                for (int[] pair : accepted) {
                    O o = remainingOld.get(pair[0]);
                    N n = remainingNew.get(pair[1]);
                    accept.accept(o, n, matrix[pair[0]][pair[1]]);
                    doneOld.add(o);
                    doneNew.add(n);
                }
                remainingOld.removeAll(doneOld);
                remainingNew.removeAll(doneNew);
            }
        }
        // whatever still has above-threshold candidates is ambiguous; say so rather than guess
        for (N n : remainingNew) {
            List<String> cands = new ArrayList<>();
            for (O o : remainingOld) {
                double s = score.applyAsDouble(o, n);
                if (s >= min) {
                    cands.add(idOf.apply(o) + "@" + round(s));
                }
            }
            if (!cands.isEmpty()) {
                onAmbiguous.accept(n, cands);
            }
        }
    }

    private static Certainty capHigh(Certainty certainty) {
        return certainty == Certainty.EXACT ? Certainty.HIGH : certainty;
    }

    private static double containmentOfMembers(List<String> part, List<String> whole) {
        if (part.isEmpty()) {
            return 0.0;
        }
        long contained = part.stream().filter(whole::contains).count();
        return (double) contained / part.size();
    }

    private static String normalizeHint(String text) {
        return text.replaceAll("\\s+", " ").trim();
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> all = new ArrayList<>(a);
        b.stream().filter(x -> !all.contains(x)).forEach(all::add);
        return all;
    }

    private static String round(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }
}
