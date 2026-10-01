# 04D Any-Version Validation

Evidence for extending Agent 04D (`04d-version-migration`) from a single 3 → 4 pack to a Spring Boot
any-version-to-any-version ladder, with open-source OpenRewrite recipes and endpoint preservation.
Start with [ANY_VERSION_REPORT.md](./ANY_VERSION_REPORT.md).

| Folder | Run |
|---|---|
| [`evidence/v1/`](./evidence/v1/) | MARS `employee-service`, 2.7.12 + Spring Cloud → 3.5.16 (Jakarta boundary) |
| [`evidence/v2/`](./evidence/v2/) | golden demo, 3.5.0 → 4.1.1 (first run; found defects A, B, F, H, L) |
| [`evidence/v2b/`](./evidence/v2b/) | golden demo, targeted check of fixes A and B (found defects Q–U) |
| [`evidence/v2c/`](./evidence/v2c/) | golden demo, clean 3.5.0 → 4.1.1 re-run with every fix |

Each run folder holds:
- `reports/`: the migration report and diff;
- `migration-runs/`: the automatic `MIGRATION_SUMMARY.md` and `migration-summary.json`;
- `session/`: the baseline, plan, narrative, probes, round records, generated edge recipes
  (`*.rewrite.yml`), preview patches and runtime probe records.

Sandbox workspaces and build logs are not included. V2's `probes.json` predates env-var credentials,
so its demo password is redacted.

Every run happened in scratch locations: `E:/mv/C` (the `validate/04d-integration` worktree),
`E:/mv/golden` and `E:/mv/evidence`. The main checkout was not changed.
