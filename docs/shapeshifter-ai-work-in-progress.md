# Learning Flow: work in progress

*Written 19 November 2026, rewritten the same day after the container rebuild · branch `ai_translations_server` · for whoever picks this up next, human or Claude*

## Where to start reading

1. [shapeshifter-ai-delivery-plan.md](shapeshifter-ai-delivery-plan.md): the plan being followed. Its "Ready to start now" section, at the end, lists the order. Its **The names** section is new, and so is **Stage R: the rename**.
2. [shapeshifter-ai-checks-model-design.md](shapeshifter-ai-checks-model-design.md): the design for Track 1 task 10 (one model of checks), now being built. Its "Step 1 as built" and "Step 2a as built" sections record where the code differs from the design.
3. [shapeshifter-ai-test-protocol.md](shapeshifter-ai-test-protocol.md): how to check the 19 November work, part by part, with the commands and what each proves.
4. This file: what is done, what is uncommitted, and what comes next.

## The names

**Decided 19 November 2026** (by the user, this session):

| Was | Is | What it means |
|---|---|---|
| Shapeshifter AI | **Learning Flow** | The document type |
| AI Translations | **Smart Pipelines** | The feature, and a pipeline holding the AI element |

"Learning flow" also keeps its older meaning — the ordered list of questions asked during a run, the `LearningFlow` class — which the user chose deliberately over renaming it. The document type and the question order inside it share a word.

The UI rework had already renamed the display name and the monitoring screen, so the screens say the new names. **The code does not**, and is not meant to yet: around 640 files say `shapeshifter` (count it rather than trusting the number, which drifts). `ai_translations` isn't in the code at all — its uses are in `docs/`, as branch names and references to the egress branch's directory. Stage R does that in one go, after the outstanding branches merge and before the first release. **Until then, write new code under the old names** rather than renaming as you go, so that the rename stays one mechanical change.

## Ways of working agreed with the user

- **"Follow the plan"** means: carry on with the next item in the delivery plan's order without asking.
- **Never** commit, merge, stage or otherwise change git state (`AGENTS.md`). The user commits.
- **Changelog entries** use issue number `0`: `./log_change.sh Bug 0 "…"`. The team doesn't use GitHub issue numbers.
- **Decisions** are the user's. Ask with options and a recommendation; record the answer in the design or plan document.
- **Reviews**: the user has asked for Fable reviews of plans (an `Agent` with `model: "fable"`).
- The **Stroom MCP server** still doesn't connect: `getaddrinfo EAI_AGAIN ${STROOM_MCP_URL}`, the variable unexpanded. Nothing has been checked on the dev instance in either session.

## Done

| Item | State |
|---|---|
| Plan review and corrections, with two Fable reviews | Committed (`14deb144f5`) |
| Track 1 task 1: preservation check matches record markup | Committed (`69ba9fc24b`). Not yet done: re-learning stream 2152 on `OTEL-TRACES` on the dev instance (needs the MCP server) |
| Track 1 task 6: supervisor visibility items 3 and 5 | **Uncommitted.** Recorded in [shapeshifter-ai-supervisor-visibility.md](shapeshifter-ai-supervisor-visibility.md). The truncated-sample note is moved to task 9 |
| Task 10 design, decisions D1–D6 | **Uncommitted.** All six settled as recommended |
| Task 10 step 1: shared model and server | **Uncommitted.** Built and tested |
| Task 10 step 2a: client *Quality checks* section | **Uncommitted.** Built; **stories now run and pass** (see below) |
| Task 10 step 2b: the step cards | **Uncommitted.** New, 19 November. Built and tested |
| Task 10 step 2c: the decision pane | **Uncommitted.** New, 19 November. Built and tested |
| The rename: plan updated, stage R written | **Uncommitted.** New, 19 November. **No code renamed** |
| Task 10 step 3, all of it | **Uncommitted.** New, 20 November. Built and tested. Task 10 is now complete |

## The container rebuild worked

Chromium and Playwright are in place and working: `workbenchTest` downloads nothing, and the browser self-test passes **165 of 165** checks. The blocked step is unblocked.

Running the editor's stories found **one real failure**, now fixed. It was the story, not the product: `NoGates` expected the readiness banner to count `"6 quality gates"` after applying the Standard checks, but `FeedsTabController:72` hides the banner once nothing stops the document learning, so the count is never shown. A first fix asserting the text was *absent* was also wrong — `ReadinessBanner` keeps its words when the tab hides it — so the assertion is now `.not().toBeVisible()`.

> **A trap:** piping Gradle through `tail` hides its exit code, so a failed run reads as "exit code 0". Read the `PASS`/`FAIL` line, or use `; echo "EXIT=${PIPESTATUS[0]}"`.

## Task 10 step 2b (the step cards), in short

A step no longer names checks of its own; a check applies to every step it can measure.

- **Gone:** the *Choose its own checks* tick box and the per-check tick boxes (`StepCard` and its `.ui.xml`), `ApproachFlow.withChecks`, the `"own checks"` heading tag, `ApproachSectionController`'s `setChecks` wiring, `PlainWords.check(Check)` and `PlainWords.usualChecks(QuestionKind)` (nothing used them afterwards), and the `step-card__judged` and `step-card__note` rules.
- **`ApproachFlow.judges(step)` is now the public `ApproachFlow.checksFor(step)`**, always the step kind's checks. The rule-condition menu is built from it, so conditions come from the checks that apply to the step kind.
- **The warning.** `FlowProblem` gained a `Severity` (`PROBLEM` or `WARNING`). A rule that fires on a check the Learning Flow has switched off never fires, so the step's card says so in amber (`step-card__warning`), and `StepCard.setWarning` shows it. `LearningFlow.check(List<CheckSetting>)` is a new overload that adds these. `Check.ofShortfall(StepOutcome)` reads a rule's outcome back to its check.
  **What the severity is actually for** (corrected after the Fable review): it separates a card's warnings from its problems, and keeps warnings out of `stepProblems`, which drives the route map. It is *not* what keeps the document saveable — `ShapeshifterAiStoreImpl:60` refuses a save on `getFlow().problems()`, which asks the no-argument `check()`, which raises no warnings at all. The store never sees this warning whatever its severity. The filter in `problems()` is defensive only, and the javadoc now says so.
- `SetupTabController` tells the approach the checks on all three paths that change them: reading a document, editing the list, choosing a preset.
- **`FlowStep.checks` was still there after 2b**, read and written, per AS13: the client stops using a feature before the server removes it. **Step 3a removed it the next day.**
- **Tests:** `TestLearningFlow` (new, 6 tests), `TestApproachFlow.aStepsChecksAreItsKindsWhateverTheStepSays`, `TestLearnFlow.aStepsWarningsAreKeptApartFromItsProblems`. Stories: `ApproachRuleOnCheckThatIsOff` and `ApproachRuleOnCheckThatIsOn`, with the fixture `RULE_ON_OFF_CHECK_DOC`.

**Worth a decision:** `FlowProblem.Severity` is a second `Severity` in the codebase (`stroom.util.shared.Severity` exists). They don't clash, being nested, but the user may want it named otherwise.

## Task 10 step 2c (the decision pane), in short

`StageDecisionPane.setVerdicts` no longer presents a total as the thing that decided a step:
`Step 1 — 0.95 (passed)` became `Step 1 — 95%, every gate passed`, and each check now says its value, the bar it had to clear, and whether it was **a gate** or **advice** — `Reads all the input · 90% (met 80%, advice)`. Values are percentages with the checks' plain names, as the rest of the client shows them. The figure itself was already `verdict.score()`, the average of the checks that ran (D3); only its presentation moved.

**Both of those were done the next day, in step 3c:** `StageVerdict.getWeightedTotal()` is now `getScore()`, and `StageScore` is keyed by `Check`, so wholeness, preservation, fidelity and the regression corpus now appear in this pane alongside the rest.

## Task 10 step 3, complete (20 November)

Six stages, each left green and each verified before the next began.

**3a. `FlowStep.checks` is gone.** The field, its `checks a,b,…` keyword in the flow DSL, the `SPLIT_CHECKS`
narrowing in `Conversation`, and the per-step narrowing at `Conversation:646` and `:965`. A step no longer
says what judges it, on the server as well as in the client.

*The review's "strip `checks` on read" worry turns out to need no code:* `JsonUtil` sets
`FAIL_ON_UNKNOWN_PROPERTIES` to false, so a stored document's `"checks"` is simply ignored once the field is
gone. That **is** stripping on read.

**3b. `ERRORS` and `CLASSIFICATION` are gone** (decision D5), with `ScorerType.ERROR_LOAD`,
`EVENT_CLASSIFICATION`, `ErrorLoadParameters`, `EventClassificationParameters`, and their cases in
`ScorerParameters`, `CheckPreset` and `Scorecard.MEANING`. Every check that is left is built, so the
"isn't built yet" refusal has no case to answer; it stays in `CheckSettings.problems` for a check added
before its scorer, and `TestCheckSettings` still covers it.

**3c. `TurnScore` and `StageScore` are keyed by `Check`, not `ScorerType`**, and
`StageVerdict.weightedTotal` is now `score`. This is what completes step 2c: wholeness, preservation,
fidelity and the regression corpus have no scorer, so they could not appear in the decision pane before, and
now they can. `PlainWords.scoreName` gained their wording.

> **One thing deliberately left:** the database column is still `shapeshifter_turn.scorer`, and now holds a
> `Check`'s name. Renaming the column needs a jOOQ regeneration against a live database, so it belongs with
> the other table changes (stage R, or the first migration that touches this table), not here. There is a
> comment saying so at `AttemptsDao.score(Record)`.

**3d (part). The promotion floor is gone.** `getPromotionFloor()` was never read in production — only set,
in eighteen test files and fixtures — so removing it was mechanical. `TestScenario01` no longer asserts a
score clears a floor; a candidate is promoted when every gate passes (D2).

### 3d. The old model of checks is gone

`ScorerSetting`, `ScorerType`, `CheckSettings.fromScorers`, `CheckSetting.of(ScorerSetting)`,
`ShapeshifterAiDoc.scorers` and its builder, and the deprecated `Scorecard(List<ScorerSetting>, …)`
constructor. 178 call sites across 47 files.

**How the conversion was kept honest.** `fromScorers` did three things, and a converted list had to do all
three or a test's meaning would change while its result did not: a gate became `GATE`, a listed non-gate
became `ADVICE`, a scorer-backed check that was *not* listed became `OFF` — and `.checks(...)` fills
anything unnamed with *Standard*'s defaults, which is not the same — and the checks with no scorer became
`GATE`. So every converted list names all ten checks.

The conversion was scripted rather than hand-typed, and **proved before it was trusted**: `DemoDocument` was
converted first, the demo pack regenerated, and the pack's documents compared against the previous pack.
The derived `checks` were byte-identical; only `scorers` had emptied. That is what licensed the other 46
files.

`Check` absorbed what `ScorerType` carried: `isScored()` (a scorer measures it) and `isBuilt()` (its code
exists) in place of the old `getScorer()` indirection, plus `getName()` — "Input coverage", "Extraction
quality" — so that **every message and list reads exactly as before**. That last part was not free: the
first pass had messages saying "quality scored 0.27" where they used to say "Extraction quality scored
0.27", which showed up as fifteen failing scenario tests. They were all wording, and all went when `Check`
got its name back.

### 3e. The client's `Scorer*` classes are `Check*`

`ScorerList` → `CheckList`, `ScorerListController` → `CheckListController`, `ScorerSet` → `CheckSet`,
`ScorerSettingEditor` → `CheckEditor`, `ScorerSettingEditorController` → `CheckEditorController`, with
their `.ui.xml` templates, the stylesheet `scorer-setting-editor.css` → `check-editor.css` and its BEM
classes, the DOM identity `scorerThreshold` → `checkThreshold`, and the `scorerList` fields and accessors.

A sweep for `ScorerSetting`, `ScorerType`, `promotionFloor`, `getScorers`, `getWeightedTotal`, `ScorerList`,
`ScorerSet` and `scorerList` across every `.java` and `.xml` now returns nothing.

> **One thing left, deliberately:** the database column `shapeshifter_turn.scorer` still has its old name
> and now holds a `Check`'s name. Renaming it needs a jOOQ regeneration against a live database, so it goes
> with the other table changes rather than here. `AttemptsDao.score(Record)` says so in a comment.
>
> `Scenarios.scorers()` also stays: it returns the `Scorer` SPI implementations, which are the code that
> measures a check, not the old settings.

## What passes now

| Check | Result |
|---|---|
| `:stroom-core-shared:test`, `:stroom-core-client:test` | Pass |
| `checkstyleMain checkstyleTest` | Pass |
| `TestTemplateBindings`, `TestStylesheets`, `TestStroomWidgetsOnly` | Pass (11 tests) |
| Stories `App/Editors/ShapeshifterAiEditor`, with the leak check | **38 of 38 pass** |
| Stories `App/Main/StageDecisionPane`, with the leak check | 2 of 2 pass |
| `:stroom-shapeshifter:stroom-shapeshifter-ai:test` | Pass (added after the review found part A never re-ran it against the changed shared model) |
| `compileTestJava` across every module | Pass |
| The demo pack, regenerated after 3a, 3b and the promotion floor | `TestDemoContentPack` passes |
| Browser self-test | 165 of 165 |

Not run: the full build (`clean build shadowJar buildDistribution`) and `./buildDockerImages.sh`, which are the gate before a pull request. The 3 `stroom-app` `TestDemoContentPackImports` failures are unchanged and unrelated (the `claimTasksOnWorker` requirement, which task 5 removes).

## Next, in order

1. **Track 2 step 2, the hard-coded Smart Pipelines allow-list** (settle AS6 first), which also unblocks
   Track 1 task 3.
2. **Track 1 task 5**, `claimTasksOnWorker`: it fixes the three `stroom-app` failures, and needs a test
   cluster, so arrange one early.
3. **Tracks 1 tasks 2 and 3**, whose non-check parts can start now, and the declared-skips design that
   task 2's completeness check needs.
4. **Track 1 tasks 4, 8 and 9**, and task 7's schema field and prompt summary.
5. **Stage R, the rename**, at its window: after Track 2 step 6's egress merge, before the first release.
   Settle R1, R2, R2b and R3 when it starts.

## Known gaps, recorded in the design or plan

- Import isn't validated for checks or flow (`AbstractDocumentStore.importDocument` skips `writeDocument`); Track 2 step 1 needs the same hook.
- Advice results aren't shown anywhere yet (Track 2 step 4, approval evidence).
- The new step-card warning is client-side only: a flow saved through the REST API with a rule on a check that is off is accepted in silence. Right for a warning, but worth knowing.
- `step-card.css` changed, so screenshot baselines for the step card will differ if `workbenchScreenshotDiff` is part of the routine.
- Many client comments still say "Feeds tab" (`FeedsTab`, `Readiness`, `SetupTabController` …); for the plan's design-notes item.
- **A step's warning shows only on the card of the step selected.** `stepProblems` is problems-only, so the route map and "the approach has a problem" don't mark a step whose only fault is a warning. A user who switches a check off sees nothing until they open that step. Deliberate for now, but worth a decision — it may make the warning too easy to miss.
- **The four changelog entries in `unreleased_changes/` still say "Shapeshifter AI"** and are uncommitted. Since the rename is decided, they can be reworded now so CHANGELOG.md never carries the old name.
- **`shapeshifter-ai-demo-v1.0.zip` is modified** because step 1 regenerated it (documents now carry `checks`). It is binary, so a reviewer can't see the change; it must be regenerated again after step 3, and renamed in stage R.
- **Branch state:** `ai_translations_egress` is unmerged (Track 2 step 6); `ai_translations_ui_rework` was merged at `7e06bb589c`.
- **The MCP server:** the fix is not in this document — see the memory note `stroom-mcp-server-setup` for where the bridge's config lives on the host and what each error means.

## Uncommitted files

Run `git status` for the full list. In summary: the docs above, the delivery plan and the new test protocol; `shapeshifter-ai-demo-v1.0.zip`; shared `Check*` classes, `FlowProblem`, `LearningFlow` and `ShapeshifterAiDoc`; server scoring, `Stage`, `Conversation`, `TargetChecks`, `StopReports`, `QuestionText`, `StepDetails`, `RecordGate`, `ShapeshifterAiStoreImpl`; client Shapeshifter components, `step-card.css`; workbench fixtures and stories; their tests; and four changelog entries in `unreleased_changes/` (task 1, task 6, task 10 step 1–2a, task 10 steps 2b–2c).
