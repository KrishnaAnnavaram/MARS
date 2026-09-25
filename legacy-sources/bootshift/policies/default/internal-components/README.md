# Internal component profiles

Drop one `<groupId>_<artifactId>.json` file here for every organization-owned dependency whose
compatibility has been established. Without a profile, a coordinate that does not resolve in the
configured public artifact repository is classified `INTERNAL_COMPONENT` with compatibility
`UNKNOWN`, and `unknown_internal_component_action` decides whether that blocks the run (R28).

Example `com.acme_acme-spring-starter.json`:

```json
{
  "group_id": "com.acme",
  "artifact_id": "acme-spring-starter",
  "component_version": "4.2.1",
  "compatibility": "SUPPORTED",
  "java_range": "17-21",
  "spring_boot_range": "3.2.0-3.5.999",
  "spring_framework_range": "6.1.0-6.2.999",
  "spring_security_range": "6.2.0-6.4.999",
  "transitive_managed_dependencies": ["com.acme:acme-core:4.2.1"],
  "migration_notes": "4.2.x drops the deprecated AcmeAutoConfiguration entry point.",
  "evidence_references": [
    "https://internal.acme/confluence/acme-starter-compatibility",
    "sha256:<hash of the compatibility statement snapshot>"
  ],
  "owner_contact": "platform-team@acme.example",
  "confidence": "HIGH",
  "status": "VERIFIED"
}
```

`compatibility` must be one of `SUPPORTED`, `UNSUPPORTED` or `UNKNOWN`. Anything other than
`SUPPORTED` constrains target resolution.
