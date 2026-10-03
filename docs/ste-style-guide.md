# MARS writing standard: ASD-STE100 Simplified Technical English

Write the MARS README and the harness documentation in **Simplified Technical English (STE)**, as
the ASD-STE100 specification defines it. This file gives the rules that we use and the project
vocabulary. Use it when you write or change a document.

STE has two parts: writing rules and a controlled dictionary. The official dictionary is not in
this repository. This file lists the project's **technical names** and **technical verbs**, which
STE lets each project add. It also lists the general words that we replace.

## 1. Writing rules

### Words

1. Use one word for one meaning. Use one meaning for one word. The vocabulary in Section 2 is
   fixed. Do not use a synonym for variety.
2. Use a word only as the part of speech that the vocabulary gives. For example, "check" is a verb
   and "test" is a noun or a verb. "Build" is a verb and a noun ("the build").
3. Do not use phrasal verbs ("set up", "carry out", "pick up", "work out", "come through").
   Use a single verb: "prepare", "do", "find", "calculate".
4. Do not use an "-ing" form as a noun or an adjective ("the running agent", "after applying").
   Exception: a technical name that has "-ing", such as `Compile Failed`, a file name or a status
   value.
5. Do not use contractions ("don't", "it's"). Do not use slang or idioms ("gotcha", "at a glance",
   "out of the box", "load-bearing").
6. Do not use "and/or". Write "A, B or both".
7. Do not use "should", "could" or "would" for instructions. Use "must" for a rule and the
   imperative for a step. Use "can" for a possibility.
8. Keep the articles "a", "an" and "the". Do not write telegraphic text in sentences.
9. Do not make a noun cluster of more than three words. Write "the connector of the oil pressure
   sensor", not "oil pressure sensor connector". A technical name, such as `fix_plan_ID.md` or
   "Fix Type", is one word.

### Sentences

1. A **procedural** sentence (an instruction) has a maximum of **20 words**.
2. A **descriptive** sentence has a maximum of **25 words**.
3. Write one instruction in one sentence. If two actions occur at the same time, you can put them
   in one sentence.
4. Use the **imperative** for an instruction: "Run the agent." Not "The agent should be run."
5. Use the **active voice**. Use the passive voice only when the agent of the action is not
   important in descriptive text.
6. Use only these tenses: simple present, simple past and simple future. Do not use "has been",
   "had been" or "will have".
7. Put a condition before the instruction: "If the build fails, read the log."
8. Do not use semicolons in sentences. Write two sentences.

### Paragraphs and notes

1. A paragraph has one topic and a maximum of **6 sentences**.
2. Start each paragraph with its topic sentence.
3. A **warning** or **caution** starts with a simple, clear command. Then give the reason.
4. A **note** gives information. It does not give an instruction.
5. A vertical list is a good way to show a sequence or a set of conditions. Each item of a numbered
   procedure is one step.

### Tables, headings and diagrams

1. A table cell can be a short phrase. If a cell has a sentence, the sentence obeys the rules.
2. A heading is a noun phrase ("The ship decision") or an imperative ("Run MARS"). Do not use an
   "-ing" form in a heading.
3. A diagram label is a short phrase. Use the same terms as the text.

### What STE does not change

Code, commands, file names, paths, field names, status values, enum values, CWE names, product names
and URLs stay exactly as they are. They are technical names. Put them in backticks.

## 2. Project vocabulary

### 2.1 Technical names (nouns)

Use these terms with only the meaning given. Do not use the words in the "Do not use" column for the
same meaning.

| Term | Meaning | Do not use |
|---|---|---|
| **issue** | One row in the issue register: a reported defect or vulnerability | (for a problem in MARS itself, use **problem**) |
| **problem** | A known fault or limit in MARS itself | issue, gotcha, bug |
| **issue register** | `issue-register.xlsx` | register file, sheet, tracker |
| **agent** | A persona file (`*.agent.md`) that the AI runtime follows | persona (alone), bot |
| **skill** | A self-contained folder of scripts, schemas and data that an agent uses | toolbox, plugin |
| **script** | A deterministic Node.js program in a skill | tool, utility |
| **AI runtime** | GitHub Copilot Chat or Claude Code | model, LLM (except for the 01b batch generator) |
| **stage** | One step of the pipeline that has its own output folder | step (for a pipeline stage) |
| **phase** | A group of stages: A, B or C | |
| **step** | One action in a procedure | stage |
| **fact** | Data that a script measures | evidence (as a synonym for fact) |
| **judgement** | Reasoning that an agent writes in a schema-checked JSON file | reasoning, opinion |
| **briefing** | The `facts.md` or `evidence.md` file that a script writes for an agent | brief, context bundle |
| **report** | A rendered Markdown file in `docs/agent_output/` | document (for a stage output) |
| **fix plan** | `fix_plan_ID.md` | strategy document, proposal |
| **strategy file** | `ID.strategy.json` | |
| **patch** | The code change that Stage 2 makes | fix (for the code change), change set |
| **diff file** | `fix_ID.diff` | patch file |
| **fix report** | `fix_ID.md` | |
| **hand-off** | The fix report and the diff file together | handoff, handover |
| **worktree** | A temporary git checkout of `HEAD` where a script applies a patch | copy, clone, throwaway |
| **temporary** | Made for one run and then removed (a worktree, a directory) | throwaway |
| **person** | The human who approves a fix plan or asks for a PR | human (except in names such as `human.waiting` and "Human checkpoints") |
| **approval record** | A `DEC-*.json` file that `record-decision.js` writes | decision (reserved for `Cleared` or `Blocked`) |
| **evidence** | Only in names (`evidence.md`, `insufficient_evidence`, evidence-gap plan, Evidence guard) and in Mission Control, where it means the files in `docs/agent_output/**` | (use **fact** for data that a script measures) |
| **Round A, B, C** | The names of the three validation runs in `docs/validation/` | (not a 04d **round**) |
| **sandbox** | The private copy of the project that 04d uses, with its own git repository | worktree |
| **verdict** | The result of one check in Agent 05, or the result file of Agent 07 | |
| **decision** | `Cleared` or `Blocked` | outcome, ruling |
| **gate** | A check that a script decides from an exit code or a rule | |
| **hard gate** | A condition that blocks a patch at all scores | |
| **score** | The number from 0 to 100 that 07a calculates | |
| **threshold** | The minimum score for a severity | bar |
| **override** | The change of a `Cleared` decision to `Blocked` by Agent 07 | downgrade (except in a table) |
| **catalog** | `cwe-patterns.json` | |
| **knowledge base (KB)** | `remediation-kb.json` and its references | |
| **catalog gap**, **KB gap** | A CWE that is not in the catalog, or not in the catalog and not in the KB | |
| **Fix Type** | `CODE_FIX`, `DEPENDENCY_UPGRADE` or `VERSION_MIGRATION` | |
| **meaning layer** | The descriptions that 01b validates (`ctx*` properties in the graph) | context layer, semantic layer |
| **graph** | The Neo4j knowledge graph | |
| **node** | A node in the graph or in `artifacts.json` | |
| **round** | One recorded build in a 04d session | |
| **probe** | An HTTP request that 04d sends before and after a migration | |
| **edge** | One step on the 04d migration ladder | |
| **recipe** | An OpenRewrite transformation | |
| **ledger** | The telemetry event log in `.mars/ledger/` | |

### 2.2 Technical verbs

These verbs have a technical meaning in MARS. Use them only with that meaning.

| Verb | Meaning |
|---|---|
| **apply** | Put a patch into files (`git apply`) |
| **build** | Run Maven to compile, test or package code |
| **compile** | Run the Java compiler |
| **parse** | Read source or a file into a structure |
| **render** | Make a Markdown report from facts and judgement |
| **validate** | Compare a JSON file with its schema |
| **load** | Put data into the graph |
| **scan** | Read all source files to find structures or signatures |
| **migrate** | Move a project to a newer framework or Java version |
| **route** | Send a fix plan to the correct Stage 2 skill |
| **approve**, **reject** | Change the Status of a fix plan (a person only) |
| **clear**, **block** | Give the `Cleared` or `Blocked` decision |
| **publish** | Make a branch and a pull request |
| **commit**, **push** | The git operations |
| **query** | Send Cypher to the graph |

### 2.3 General words that we replace

| Do not use | Use |
|---|---|
| ensure | make sure |
| utilize | use |
| prior to | before |
| subsequent to | after |
| commence, initiate | start |
| terminate | stop |
| obtain | get |
| perform | do |
| sufficient | enough |
| additional | more |
| in order to | to |
| approximately | about |
| numerous | many |
| leverage | use |
| via | through |
| e.g., i.e. | for example, that is |
| etc. | (write the full list) |
| deliberately | intentionally, or delete |
| quietly, silently | without a message |
| genuinely, really | (delete) |
| should (instruction) | must, or the imperative |
| Never (instruction) | Do not |
| bump (a version) | raise, increase |
| drive (a skill) | use |
| re-validate | check again |
| reachable (Neo4j) | available |

## 3. Examples

| Not STE | STE |
|---|---|
| It is essential to ensure that a human has approved the plan prior to running Stage 2. | A person must approve the fix plan before you run Stage 2. |
| Running the gate twice hoping for a different result is not allowed. | Do not run a gate again on the same input to get a different result. |
| The renderer refuses missing required fields. | The renderer stops if a required field is missing. |
| Known issues and gotchas | Known problems |
