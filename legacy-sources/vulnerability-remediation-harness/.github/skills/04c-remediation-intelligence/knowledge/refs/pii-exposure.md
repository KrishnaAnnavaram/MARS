# Exposure of private personal information (CWE-359) — reference remediations

## HF-PII-001 — response DTO projection

Never serialize the persistence entity directly. Map it to an outbound DTO that carries only the
fields the caller is authorized to see, and build that projection behind an authorization check.

```java
public record EmployeePublicView(String id, String name, String department) {}

// controller returns the view, never the entity
EmployeePublicView view = new EmployeePublicView(e.getId(), e.getName(), e.getDepartment());
// salary, SSN, address, etc. have no path into the response
```

Default to excluding sensitive fields; opt them in only for callers authorized for that projection.

## References
- CWE-359: https://cwe.mitre.org/data/definitions/359.html
- OWASP Sensitive Data Exposure guidance
