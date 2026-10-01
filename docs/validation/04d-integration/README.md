# 04D Integration Validation

Evidence that Agent 04D (`04d-version-migration`) works end to end in the MARS pipeline. Start with
[FINAL_VALIDATION_REPORT.md](./FINAL_VALIDATION_REPORT.md).

| File | Contents |
|---|---|
| [FINAL_VALIDATION_REPORT.md](./FINAL_VALIDATION_REPORT.md) | Both verdicts, before/after comparison, all 27 questions, what changed, open items |
| [BASELINE_PIPELINE_REPORT.md](./BASELINE_PIPELINE_REPORT.md) | Round A: the unmodified pipeline, where 04D is never reached |
| [ROUND_B_REPORT.md](./ROUND_B_REPORT.md) | Round B: 04D on its own (B1 pack-selection bug and its fix; B2 Boot 3.5.0 → 4.1.1) |
| [INTEGRATED_PIPELINE_REPORT.md](./INTEGRATED_PIPELINE_REPORT.md) | Round C: 01 → 04 → 04D → 05 → 06 → 07, with the live trace |
| [`migration-runs/`](./migration-runs/) | One `MIGRATION_SUMMARY.md` + `migration-summary.json` per 04D run |
| [`evidence/`](./evidence/) | Agent traces, 04D session records (sandbox workspaces excluded), every Round C agent output, container log |

Every run happened in scratch locations: `E:/mv/A` (a detached worktree), `E:/mv/R` (a scratch
repository) and `E:/mv/evidence`. The main checkout's branch and tracked files were not changed.
