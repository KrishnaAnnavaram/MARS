# Server-Side Request Forgery (CWE-918) — reference remediations

## HF-SSRF-001 — allow-list base URL from configuration

Never build an outbound target from a raw caller-supplied URL. Load the base from config; let the
caller supply only an opaque id/path appended to that fixed base.

```java
// base URL comes from configuration, not the request
String base = departmentServiceConfig.getBaseUrl();     // e.g. http://department-service
URI target = URI.create(base).resolve("/departments/" + encode(departmentId));
// call `target`; the caller never controls host or scheme
```

Validate the resolved host after DNS resolution against an allow-list and refuse internal/link-local
ranges; do not follow redirects to non-allow-listed hosts.

## References
- CWE-918: https://cwe.mitre.org/data/definitions/918.html
- OWASP SSRF Prevention Cheat Sheet
