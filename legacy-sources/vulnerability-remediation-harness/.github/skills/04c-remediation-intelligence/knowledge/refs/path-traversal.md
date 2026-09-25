# Path traversal (CWE-22) — reference remediations

Local, citable notes the fallback skill draws `HF-PATH-*` patterns from. Framework-generic; the skill
adapts them to the defect site.

## HF-PATH-001 — resolve under a fixed base and verify containment

Bind every write/read to one fixed base directory, resolve, normalize, then confirm the result is
still inside the base before touching the filesystem.

```java
Path base = Paths.get(reportsDir).toAbsolutePath().normalize();
Path target = base.resolve(userSuppliedName).normalize();
if (!target.startsWith(base)) {
    throw new IllegalArgumentException("Resolved path escapes the reports directory");
}
// safe to use `target`
```

Key point: the containment check happens **after** `normalize()`, so `../` sequences and mixed
separators are already collapsed. Blacklisting `..` on the raw string is not equivalent.

## HF-PATH-002 — basename only, plus a server-generated name

When the caller has no legitimate need to choose a directory, discard everything but the file name and
generate the stored name yourself.

```java
String safeName = Paths.get(userSuppliedName).getFileName().toString();
Path target = base.resolve(System.currentTimeMillis() + "_" + safeName).normalize();
if (!target.startsWith(base)) throw new IllegalArgumentException("bad name");
```

## References
- CWE-22: https://cwe.mitre.org/data/definitions/22.html
- OWASP File Path Injection / Path Traversal Prevention Cheat Sheet
