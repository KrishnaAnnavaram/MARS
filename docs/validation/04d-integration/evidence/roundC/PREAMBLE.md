ROUND C RULES (full pipeline on the INTEGRATED harness)
- Repository root for this run is E:/mv/R: a standalone scratch git repository holding the Spring Boot
  3.5.0 demo application (src/spring-boot-migration-demo, exported from sample-java-project@6978c4a)
  plus the integrated MARS harness (.claude/ and .github/). Every relative path in your agent
  instructions (".claude/...", ".github/...", "docs/agent_output/...", "src/...") means E:/mv/R/<path>.
  Start every Bash command with:  source /e/mv/env17.sh && cd /e/mv/R && ...
  When you use Read/Write/Edit, use absolute paths under E:/mv/R/.
- Your agent definition is the file named in your task (E:/mv/R/.claude/agents/<NN>_*.agent.md). Read it
  in full first and follow it as your system instructions — that is exactly what Claude Code loads
  when this agent is invoked by name. Read each SKILL.md it tells you to read.
- NEVER read from or write to "E:/Virtusa Projects/MARS", E:/mv/A or E:/mv/C. They are off-limits.
- Toolchain (in /e/mv/env17.sh): JAVA_HOME = portable JDK 17.0.20.1; MIGRATION_JDK_17 and
  MIGRATION_JDK_21 (21.0.11) point at both JDKs; MIGRATION_MVN = Maven 3.9.9, also on PATH (the app has
  no Maven wrapper). Never change machine-wide settings. Network is allowed for Maven/OpenRewrite/npm.
- Docker is running for this round. Only disposable containers (e.g. Testcontainers); record any you
  start (image, ports, command, health, shutdown) and remove them before you finish.
- Neo4j is NOT configured: any graph step is "NOT EXECUTED — OPTIONAL DEPENDENCY NOT CONFIGURED".
- Do not commit, push, or create PRs.
- Trace: append concise progress lines to E:/mv/evidence/roundC/trace-<NN>.log as you go, prefixed with
  your agent number, e.g. "[02] Root cause started", "$ node scripts/x.js -> result". Only what really ran.
- Final message: commands run, skills used (and which SKILL.md you read), routing decisions and the
  rule/evidence behind each, outputs written, failures, environment deviations.
