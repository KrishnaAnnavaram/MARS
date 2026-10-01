# Missing authorization (CWE-862) — reference remediations

## HF-AUTHZ-001 — centralized, deny-by-default authorization

Add one authorization rule at the boundary of the protected actions rather than scattering ad-hoc
checks per controller. Evaluate it server-side against the caller's role and the target resource's
ownership, and deny by default so a newly added endpoint is covered automatically.

```java
// method-level security example — deny unless the rule passes
@PreAuthorize("hasRole('HR') or #ownerId == authentication.name")
public EmployeeView getEmployee(String ownerId) { ... }
```

The point is that the check is impossible to forget on a new sibling endpoint, because the default is
denial and the rule is centralized.

## References
- CWE-862: https://cwe.mitre.org/data/definitions/862.html
- OWASP Authorization Cheat Sheet
