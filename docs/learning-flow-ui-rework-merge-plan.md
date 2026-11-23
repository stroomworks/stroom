# Shapeshifter AI: merging `ai_translations_ui_rework` into `ai_translations_server`

*Written 18 November 2026 · compares `origin/ai_translations_ui_rework` at `c40284265b` with `ai_translations_server` at `dce5a12755`*

## Status, after the merge

**The merge is done**: `7e06bb589c` merged `c40284265b` into `ai_translations_server` with a merge commit, and `3372cefb4e` followed with build fixes, including the `stroom.shapeshifter.ShapeshifterAi` inherit in `DashboardApp.gwt.xml` that this document found was missing.

**The UI branch's design is the final UI**, apart from minor bug fixes (decided 18 November 2026).
It is written up in [ui-design.md](smart-pipelines/ui-design.md).
That answers this document's first two questions for the team, and settles most of [What the merge changes in the plan and the documents](#what-the-merge-changes-in-the-plan-and-the-documents):

| Item below | Outcome |
|---|---|
| 1. Full rework, not simplified | The full rework's direction is kept. SIM-2 is settled (editable flows stay), and with it SIM-4 and SIM-5. The [delivery plan](learning-flow-delivery-plan.md) is updated: AS12 rewritten, Track 2 reduced to server work. [simplified-ui-rework.html](smart-pipelines/simplified-ui-rework.html) is marked superseded. |
| 2. Screens and roles neither design described | Written into [ui-design.md](smart-pipelines/ui-design.md): Smart Pipelines, the Learning Status and Quality Assurance tabs, assessments, the QA person and Data Provider roles. The assessments are in the delivery plan's stage 0b group 4. |
| 3. Track 1 work already done | Marked in the delivery plan (task 6 and task 9's S6). |
| 4. Audit line references | Unchanged, as recommended; the audit now has a note pointing at the merge. |
| 5. Documents naming deleted classes | [supervisor-visibility](learning-flow-supervisor-visibility.md) re-scoped against the new screens; the delivery plan, the bug write-ups and both UI proposals updated or marked superseded. |
| 6. The module's design notes | **Still to do**: `design/07-ui.md` and `05-learning-flow.md` describe the old screens. |
| 7. Decisions the merge makes | N5 recorded as decided in effect (option A); SIM-4 and D1 updated in the delivery plan. |

Still open from the checks and steps below: resetting the Shapeshifter tables on dev and demo databases, confirming the jOOQ regeneration makes no diff, the checks this document didn't run, and a CHANGELOG entry.
The rest of this document is kept as written before the merge.

## Summary

The merge itself is easy.
The two branches change no file in common, git finds no conflicts, and the merged code compiles.
The tests most affected by the merge pass: 408 in `stroom-learning-flow`, 35 in `stroom-ai-impl` and 32 Shapeshifter tests in `stroom-core-shared`.

The hard part comes after the merge.
The UI branch builds the *full* UI rework, which keeps learning flows editable, on the server as it is today.
The documents on this branch, including the [delivery plan](learning-flow-delivery-plan.md), assume the *simplified* rework, which removes editable flows.
The UI branch also adds screens and roles that neither design document describes.
None of this stops git from merging, but the plan and the documents are out of date as soon as it does.

**Recommendation:**

1. Commit the documentation work on this branch first, so the merge starts from a clean working tree.
2. Merge with an ordinary merge commit (`git merge --no-ff`), not a rebase or a squash.
3. Reset the Shapeshifter tables on dev and demo databases, because the branch edits the unreleased migration in place.
4. Run the checks listed below.
5. Then reconcile the plan and the documents with what the UI branch built, starting with decision SIM-2 (editable flows or a fixed recipe), which the UI branch has in effect reopened.

The merge has to be done by a person: this repository's `AGENTS.md` doesn't allow an agent to run `git merge`.

## What each branch holds

Both branches start from `2e03c9ee32` ("Merge branch 'master' … into initial").

| | `ai_translations_server` (this branch) | `ai_translations_ui_rework` |
|---|---|---|
| Commits since the common base | 4 (11–16 November) | 2 (13 and 17 November), both titled only "ai translations ui rework" |
| Size | 43 files, +8,815 / −10 | 261 files, +22,829 / −7,976 |
| What | Test data (OTEL and other feeds), the AI model timeout fix in `stroom-ai-impl`, and the design documents in `docs/smart-pipelines/` | The UI rework, plus the server changes it needs |
| Uncommitted | The Stage 0a/0b documents, the audit, the raised tasks, and two deleted query-filter documents | n/a |

The UI branch's changes, by area:

| Area | Files | Lines (+/−) | What |
|---|---|---|---|
| Client (`stroom-core-client`, workbench stories, CSS) | 157 | +17,778 / −7,626 | New Feeds tab, Setup tab with route map and step cards, a pipeline's AI Translation tab, a Smart Pipeline's Quality Assurance tab, readiness banner, assessment dialog. Deletes the Learn tab, Score tab, learner list, session list, flow chart and flow-step editor. |
| Server and shared (`stroom-learning-flow`, `stroom-core-shared`) | 104 | +5,051 / −350 | Stop reports (why a run stopped, in parts), quality assessments and daily volumes (new `QualityResource`), the waiting reason by what holds the claim, plain words for flow steps (`FlowWords`), learnable elements as a list the Setup tab offers. |
| Database | 1 migration, 20 jOOQ files | +629 / −59 | Edits `V08_00_00_001` in place: adds `shapeshifter_attempt.report`, `shapeshifter_output.record_count` and a new `shapeshifter_assessment` table. Regenerated jOOQ code. |
| Tests | 42 | +3,405 / −156 | New tests for stop reports, the quality service, assessments, volumes, flow words; scenario tests adjusted. |

The UI branch also renames the document type's display name from "Shapeshifter AI" to "Learning Flow".

## What was checked

All of this was done without touching the repository's working tree, index or branches.
`git merge-tree --write-tree` built the merged tree as an object, and `git archive` copied it to a scratch directory for building.

| Check | Result |
|---|---|
| Files changed on both sides | None |
| Textual conflicts (`git merge-tree`) | None |
| Compile, main and test code: `stroom-learning-flow`, `stroom-learning-flow-impl-db`, `stroom-core-client`, `stroom-ai-impl`, `stroom-gwt-workbench` | Passes |
| Tests: `stroom-learning-flow` | 408 run, 0 failed, 2 skipped |
| Tests: `stroom-ai-impl` (this branch's timeout fix) | 35 run, 0 failed, 1 skipped |
| Tests: `stroom.shapeshifter.*` in `stroom-core-shared` | 32 run, 0 failed |
| `gwtCompile` of the dashboard app (`stroom-dashboard-gwt`), checked after the merge | **Failed**: the UI branch's `PipelineEditor` imports `PipelineAiTabController`, `LearnedTranslationController` and `QualityTabController`, and `DashboardApp.gwt.xml` inherits the pipeline module but not `stroom.shapeshifter.ShapeshifterAi`. Fixed by adding that inherit; the compile then succeeds. The main app's `App.gwt.xml` already inherited it. |
| Not checked | `gwtCompile` of the main app, Checkstyle, the database tests in `stroom-learning-flow-impl-db`, `stroom-app` tests, workbench stories with the leak check, Docker images |

## How to merge

### Method: an ordinary merge commit

| Method | For | Against |
|---|---|---|
| **Merge commit, UI branch into this one (recommended)** | Both branches are already on `origin`, so no shared history is rewritten. One commit records exactly when the UI arrived, and can be reverted as a whole. | The UI branch's two commit messages say nothing; the merge commit's message has to carry the description. |
| Rebase the UI branch onto this one | A straight line of history. | Rewrites commits that are already pushed, so anyone with the UI branch checked out has to reset. Gains nothing, since there are no conflicts to resolve commit by commit. |
| Squash merge | One tidy commit. | Loses the link to the UI branch, so git can't tell later that it has been merged. Any further work on the UI branch would then conflict with its own squashed copy. |
| Merge this branch into the UI branch instead | Keeps the UI branch as the place UI work continues. | The server branch is where the delivery plan lives, and the request was to bring the UI here. Either direction gives the same tree. |
| Cherry-pick parts (for example, the server changes only) | Lets the plan's ordering rule be followed literally. | The client and server halves depend on each other (`QualityResource`, stop reports, `SupervisorResource` changes); taking half means rebuilding the other half. |

### Before merging

1. **Fetch**, and check the UI branch's tip is still `c40284265b`.
   If it has moved, the checks in this document need repeating.
2. **Commit the documentation on this branch.**
   The working tree has untracked documents (the delivery plan, the audit, the raised tasks, this file) and two deleted query-filter documents.
   Git would merge around them, since the UI branch doesn't touch `docs/`, but committing first keeps the merge commit to the UI change alone, and makes it easy to revert.
   Decide separately whether the two query-filter deletions belong on this branch.
3. **Agree whether the merge waits for the design question below.**
   It doesn't need to, because nothing in git depends on it.
   But if SIM-2 is going to be decided in favour of a fixed recipe soon, it is worth knowing before merging that the Setup tab's flow editing will then be removed again.

### The merge

```sh
git fetch origin
git merge --no-ff origin/ai_translations_ui_rework
```

Write a merge commit message that says what arrived, since the branch's own messages don't. For example:

```text
Merge the AI Translations UI rework

Feeds tab, Setup tab (route map and step cards), a pipeline's AI Translation
tab and a Smart Pipeline's Quality Assurance tab replace the Learn and Score
tabs and the learner list. Server: stop reports, quality assessments and
daily volumes, the waiting reason by claim holder. Edits the unreleased
V08_00_00_001 migration: attempt.report, output.record_count and a new
shapeshifter_assessment table.
```

### After merging

1. **Reset the Shapeshifter tables on every dev and demo database.**
   The branch edits `V08_00_00_001__learning_flow.sql` in place, so Flyway's checksum no longer matches on any database that has already applied it, and Stroom will refuse to start.
   `flyway repair` isn't enough: it would accept the new checksum without adding the new columns and table.
   Drop the `shapeshifter_*` tables and the `shapeshifter_ai_schema_history` table, then start Stroom so the migration runs again.
   This loses learned state and the Supervisor history on those databases.
   Learned parsers and XSLTs are documents, so they survive, but nothing will point at them.
2. **Regenerate the jOOQ code once, and check it doesn't change.**
   The branch's regenerated classes use `generatedByDefaultAsIdentity()` and `TableLike`, where every other jOOQ module in the repository still has `identity(true)` and `Select`.
   The jOOQ version didn't change (`3.21.9`), so this is most likely just what the current generator produces.
   Running `./gradlew :stroom-learning-flow:stroom-learning-flow-impl-db:generateJooq` and seeing no diff confirms it.
3. **Run the checks this document didn't**: `./gradlew gwtCompile checkstyleMain checkstyleTest`, the `stroom-learning-flow-impl-db` tests (they need the database), the workbench stories with the leak check, and then the full build `./gradlew clean build shadowJar buildDistribution` and `./buildDockerImages.sh` before any pull request.
4. **Add a CHANGELOG entry** with `./log_change.sh`, if the UI rework has a GitHub issue.
   Neither branch has one yet.
5. **Reconcile the plan and the documents**, as below.

## What the merge changes in the plan and the documents

These aren't git conflicts.
They are places where the documents on this branch will describe something other than the code once the UI arrives.

### 1. The UI branch builds the full rework, not the simplified one

There are two UI designs in `docs/smart-pipelines/`:

- [Shapeshifter AI UI Rework.html](smart-pipelines/Shapeshifter%20AI%20UI%20Rework.html), the *full* rework, with an Approach tab: starting-point cards, step cards, rules and a route map, so flows stay editable.
- [simplified-ui-rework.html](smart-pipelines/simplified-ui-rework.html), the *simplified* rework, which assumes the server simplifications are adopted.
  It says: "There's one fixed way of learning, so the flow editor goes … There's no Approach tab and no step editor."

The UI branch is closer to the full rework.
Its Setup tab has a route map and step cards (`RouteMap`, `ApproachSection`, `StepCard`), it adds plain words for flow steps and their rules (`FlowWords`), and it renames the document type "Learning Flow".

The delivery plan assumes the simplified rework:

| Plan item | What it assumes | After the merge |
|---|---|---|
| Assumption AS12 | "The UI rework is built on the simplified server, not on the Track 1 fixes." | The UI is built on today's server, with editable flows. |
| SIM-2 and N3 (fixed recipe) | Removing the flow language saves up to about 3,200 client lines that touch the flow editor. | The client has just been rebuilt around editing flows. Removing them now deletes new code rather than old, and the saving is different. |
| Track 2 step 1 | "Hide … the Learn tab's flow editing." | The Learn tab is gone; flow editing has moved to the Setup tab. |
| Track 2 step 5 | "Delete the Learn tab, flow editor and related dialogs, in step with the server removing the shared flow types." | Already deleted, but replaced by the Setup tab's editor, which uses the same shared flow types. |

**Suggestion:** treat SIM-2 as reopened.
The question is no longer "can we stop building a flow editor" but "do we keep the flow editor we've built".
The arguments for a fixed recipe (every feed runs a tested strategy; nothing unattended runs an edited one) still stand.
The cost argument has changed.
Settle it before Track 2 starts, and update AS12, SIM-2 and Track 2 to match.

### 2. Screens and roles that neither design describes

The UI branch adds things that neither UI document mentions:

- A **Smart Pipeline**: the name the UI uses for a pipeline holding the AI element.
- A pipeline's **AI Translation** tab, showing where its feed stands and what can be done about it.
- A Smart Pipeline's **Quality Assurance** tab: figures, daily volume, what records became, samples, and **assessments** ("looks right" or an issue).
- Two roles: a **QA person**, who records assessments, and a **Data Provider**, who fixes issues at the pipeline.

These are new product decisions.
They should be written into the design documents, and the delivery plan should say where they sit.
The assessments relate to UM-2/UM-3 (what a reviewer sees beside the gates) and to reference corpora (UM-8: approved output becoming references), so they belong in stage 0b's promotion and gates groups.

### 3. Track 1 work the UI branch has already done

| Plan item | Done on the UI branch | What is left |
|---|---|---|
| Task 6, item 3: make the session's Decision say why | `StopReports` and `StopReport`: an error line, details and suggestions for every way a run can stop, stored in `shapeshifter_attempt.report`. | Check it covers the cases in [supervisor-visibility](learning-flow-supervisor-visibility.md) item 3: every candidate failing the same way, the model's refused reply, and a truncated sample. |
| Task 6, item 5: correct the waiting reason | `Stage.why(...)` words the reason by what the claim holder is doing; "Another node is learning…" is gone. | Check all three states (waiting for the model, in progress, parked), as task 6 now requires. |
| Task 9, S6: the default element list | `FlowStep.LEARNABLE_ELEMENTS` offers `XMLFragmentParser`, not `XMLParser`, and `TestStageFactory` keeps it matched to the runners. | Nothing, once confirmed. |

After the merge, mark these in Track 1 as done or partly done, so nobody builds them twice.

### 4. Audit line references move

The audit cites `file:line` throughout, and the UI branch changes the files it cites most:

| File | Audit references | Lines changed on the UI branch |
|---|---|---|
| `Stage.java` | 67 | +87 / −17 |
| `Conversation.java` | 29 | +78 / −54 |
| `Supervision.java` | 13 | +14 / −14 |
| `StageFactory.java` | 7 | +28 / −6 |
| `AttemptsDao.java` | 6 | +23 / −2 |
| `OutputsDao.java` | 5 | +14 / 0 |
| `FlowStep.java` | 5 | +15 / −9 |
| `StepDetails.java` | 3 | +38 / −4 |

Don't rewrite the audit: it records the code as it was on 17 November (assumption AS2).
Each Track 1 task should re-find its findings in the merged code before starting, as AS2 already says.

### 5. Documents that name deleted classes

The UI branch deletes `LearnTab`, `ScoreTab`, `LearnerList`, `SessionList`, `FlowChart`, `FlowStepEditor`, `ProcessHeader` and `TurnTranscript`.
These documents refer to them:

| Document | Refers to |
|---|---|
| [learning-flow-supervisor-visibility.md](learning-flow-supervisor-visibility.md) | `SessionList`, `FlowChart`, `LearnerList`, `ProcessHeader`, `TurnTranscript` |
| [learning-flow-code-assumption-audit.md](learning-flow-code-assumption-audit.md) | `LearnTab`, `ScoreTab`, `LearnerList`, `FlowChart`, `FlowStepEditor` (as evidence; leave as is, per AS2) |
| [learning-flow-delivery-plan.md](learning-flow-delivery-plan.md) | The Learn tab (Track 2 steps 1 and 5) |
| Both UI design documents | All of them, as the screens being replaced |

The supervisor-visibility proposals should be rewritten against the Feeds tab and the AI Translation tab.

### 6. The design documents in the module are behind the code

The UI branch's classes cite "design 07" and "design 05" (`stroom-learning-flow/stroom-learning-flow/design/07-ui.md` and `05-learning-flow.md`), but the branch doesn't change either document.
Update them in the same pass as `docs/`, or the code's references point at a description of the old screens.

### 7. Decisions the merge makes, or bears on

| Decision | Effect |
|---|---|
| N5 (edit the unreleased migration in place, or add migrations) | The UI branch edits `V08_00_00_001` in place, which is option A. Record it as decided, unless someone objects. |
| Simplification option F (remove the record-spans column) | The UI branch adds `record_count` beside `record_spans`, because the spans are capped and can't be counted. Removing the spans column is still possible; the count no longer depends on it. |
| SIM-4 (Supervisor actions in v1) | The UI branch moves much of the Supervisor's work to the Feeds and AI Translation tabs. Check which actions the new screens offer before settling SIM-4. |
| D1 (where the target schema lives) | The Setup tab is now where document settings are edited, so a target-schema field would go there. |

## Questions for the team

1. ~~Is the UI branch's design (editable flows on the Setup tab, Smart Pipelines, a Quality Assurance tab) the one to keep?~~
   **Answered, 18 November 2026: yes**, it is the final UI. The simplified rework is marked superseded and SIM-2 is settled in favour of editable flows.
2. ~~Is "Learning Flow" the agreed name for the document type, and "Smart Pipeline" for a pipeline holding the AI element?~~
   **Answered with 1**: the screens' words are the agreed ones, and [ui-design.md](smart-pipelines/ui-design.md#the-words-the-screens-use) lists them for the documents to use.
3. Who are the QA person and the Data Provider in the permission model, and does recording an assessment need its own permission?
4. Will work continue on the UI branch after the merge?
   If so, keep merging it with merge commits (never squash), so git keeps track of what has already come across.
