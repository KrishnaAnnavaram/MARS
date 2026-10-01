ROUND A ISOLATION RULES (validation run of the CURRENT, UNMODIFIED harness)
- Repository root for this run is E:/mv/A (an isolated, detached git worktree of the MARS repo).
  Every relative path in your agent instructions (".claude/...", "docs/agent_output/...", "src/...")
  means E:/mv/A/<path>. Start every Bash command with:  source /e/mv/env17.sh && cd /e/mv/A && ...
  When you use Read/Write/Edit, use absolute paths under E:/mv/A/.
- NEVER read from or write to "E:/Virtusa Projects/MARS" (the user's main checkout). It is off-limits.
- Toolchain (already in /e/mv/env17.sh): portable JDK 17.0.20.1 as JAVA_HOME, Maven 3.9.9 on PATH.
  Do not change any machine-wide setting. Network access for Maven/npm dependencies is allowed.
- Neo4j is NOT configured (no .env). Any graph-backed step is "NOT EXECUTED — OPTIONAL DEPENDENCY NOT
  CONFIGURED"; continue without it, exactly as the harness README says the pipeline does.
- Do not commit, push, or create PRs.
- Evidence: append every command you run (and a one-line result) to E:/mv/evidence/roundA/trace-<agentnumber>.log
  as you go, e.g. "[02] $ node scripts/list-issues.js -> 4 issues".
- In your final message include: commands run, skills used (and which you read SKILL.md for), any routing
  decision you made and why, outputs written, failures, and environment deviations.
