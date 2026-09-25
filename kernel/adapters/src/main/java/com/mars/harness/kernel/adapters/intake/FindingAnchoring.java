package com.mars.harness.kernel.adapters.intake;

import com.mars.harness.kernel.core.findings.FindingFingerprint;
import com.mars.harness.kernel.core.identity.IdentityRegistry;
import com.mars.harness.kernel.core.identity.Location;
import com.mars.harness.kernel.core.identity.StatementRecord;
import com.mars.harness.kernel.core.identity.SymbolRecord;
import com.mars.harness.kernel.ports.identity.IdentityView;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves the loose references external finding sources carry (paths with {@code ...} elision,
 * {@code Type.member} symbols, {@code File.java:18} locations) to allocated identities.
 *
 * <p>The anchor is the most precise identity the evidence supports, and its quality is recorded:
 * STATEMENT, SYMBOL, FILE or UNANCHORED. An unresolvable reference is reported as unanchored and
 * is never forced onto a nearby symbol.
 */
public final class FindingAnchoring {

    private static final Pattern FILE_LINE = Pattern.compile("([\\w$-]+\\.(?:java|kt|xml|yml|yaml|properties)):(\\d+)");

    public record Anchor(String fileId, String programUnitId, String symbolId, String statementId, Location location,
                         String quality, List<String> resolvedFileIds, List<String> resolvedSymbolIds,
                         List<String> unresolved) {
    }

    private FindingAnchoring() {
    }

    /** Files matching a reference; {@code ...} (or {@code …}) matches any number of directories. */
    public static List<IdentityView.FileInfo> resolveFiles(IdentityView identity, String reference) {
        String ref = reference.trim().replace('\\', '/').replace("…", "...");
        StringBuilder regex = new StringBuilder("(?:^|.*/)");
        String[] parts = ref.split("/");
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.equals("...")) {
                regex.append("(?:[^/]+/)*");
                continue;
            }
            regex.append(Pattern.quote(part));
            if (i < parts.length - 1) {
                regex.append("/");
            }
        }
        regex.append("$");
        Pattern pattern = Pattern.compile(regex.toString());
        return identity.activeFiles().stream().filter(f -> pattern.matcher(f.path()).matches()).toList();
    }

    public static Anchor anchor(IdentityView identity, List<String> fileRefs, List<String> symbolRefs,
                                List<String> locationTexts) {
        IdentityRegistry registry = identity.registry();
        List<String> unresolved = new ArrayList<>();
        List<String> fileIds = new ArrayList<>();
        for (String ref : fileRefs) {
            List<IdentityView.FileInfo> matches = resolveFiles(identity, ref);
            if (matches.isEmpty()) {
                unresolved.add("file " + ref);
            }
            matches.forEach(f -> {
                if (!fileIds.contains(f.fileId())) {
                    fileIds.add(f.fileId());
                }
            });
        }
        List<String> symbolIds = new ArrayList<>();
        for (String ref : symbolRefs) {
            List<SymbolRecord> matches = registry.symbolsByReference(ref);
            if (matches.isEmpty()) {
                unresolved.add("symbol " + ref);
            }
            matches.forEach(s -> {
                if (!symbolIds.contains(s.symbolId)) {
                    symbolIds.add(s.symbolId);
                }
                if (s.fileId != null && !fileIds.contains(s.fileId)) {
                    fileIds.add(s.fileId);
                }
            });
        }
        // the most precise anchor: a File.java:line reference that lands on a statement in a resolved file
        for (String text : locationTexts) {
            if (text == null) {
                continue;
            }
            Matcher m = FILE_LINE.matcher(text);
            while (m.find()) {
                String fileName = m.group(1);
                int line = Integer.parseInt(m.group(2));
                for (IdentityView.FileInfo f : identity.activeFiles()) {
                    if (!f.path().endsWith("/" + fileName) && !f.path().equals(fileName)) {
                        continue;
                    }
                    if (!fileIds.isEmpty() && !fileIds.contains(f.fileId())) {
                        continue;
                    }
                    Optional<StatementRecord> stmt = registry.statementAt(f.fileId(), line);
                    if (stmt.isPresent()) {
                        SymbolRecord sym = registry.symbol(stmt.get().parentSymbolId).orElse(null);
                        return new Anchor(f.fileId(), sym == null ? null : sym.programUnitId,
                                sym == null ? null : sym.symbolId, stmt.get().statementId, stmt.get().currentLocation,
                                "STATEMENT", fileIds.isEmpty() ? List.of(f.fileId()) : fileIds, symbolIds, unresolved);
                    }
                }
            }
        }
        if (!symbolIds.isEmpty()) {
            SymbolRecord sym = registry.symbol(symbolIds.get(0)).orElseThrow();
            return new Anchor(sym.fileId, sym.programUnitId, sym.symbolId, null, sym.currentLocation, "SYMBOL",
                    fileIds, symbolIds, unresolved);
        }
        if (!fileIds.isEmpty()) {
            IdentityView.FileInfo f = identity.fileById(fileIds.get(0)).orElseThrow();
            return new Anchor(f.fileId(), null, null, null, Location.of(f.path(), 0, 0), "FILE", fileIds, symbolIds,
                    unresolved);
        }
        return new Anchor(null, null, null, null, null, "UNANCHORED", fileIds, symbolIds, unresolved);
    }

    public static String fingerprint(String rule, Anchor anchor, String sourceId) {
        return FindingFingerprint.of(rule, anchor.statementId(), anchor.symbolId(), anchor.fileId(), sourceId);
    }
}
