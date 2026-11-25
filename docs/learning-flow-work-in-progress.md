# Learning Flow: work in progress

*Written 19 November 2026, rewritten the same day after the container rebuild · branch `ai_translations_server` · for whoever picks this up next, human or Claude*

> **Renamed on 23 November 2026 (stage R).** Everything below that was written before then uses the old
> code names, kept as a record. To read it against the code: `ShapeshifterAi…` and `Shapeshifter…` types
> are `LearningFlow…` (`ShapeshifterAiDoc` is `LearningFlowDoc`, `ShapeshifterAiParser` is
> `LearningFlowParser`); `stroom.shapeshifter.ai.x` is `stroom.learningflow.x`, `stroom.shapeshifter.shared` and
> `.client` are `stroom.learningflow.shared` and `.client`; modules `stroom-shapeshifter:stroom-shapeshifter-ai…`
> are `stroom-learning-flow:stroom-learning-flow…`; tables `shapeshifter_…` are `learning_flow_…`; REST paths
> `/shapeshifterAi…` are `/learningFlow…`; story titles `App/Editors/ShapeshifterAi…` are
> `App/Editors/LearningFlow…`; `docs/shapeshifter-ai-…` is `docs/learning-flow-…` and `docs/ai-translations/` is
> `docs/smart-pipelines/`; the pipeline element *AI Translator* is now *Smart Pipeline*.

## Where to start reading

1. [learning-flow-delivery-plan.md](learning-flow-delivery-plan.md): the plan being followed. Its "Ready to start now" section, at the end, lists the order. Its **The names** section is new, and so is **Stage R: the rename**.
2. [learning-flow-checks-model-design.md](learning-flow-checks-model-design.md): the design for Track 1 task 10 (one model of checks), now being built. Its "Step 1 as built" and "Step 2a as built" sections record where the code differs from the design.
3. [learning-flow-test-protocol.md](learning-flow-test-protocol.md): how to check the 19 November work, part by part, with the commands and what each proves.
4. This file: what is done, what is uncommitted, and what comes next.
5. [learning-flow-security-review.md](learning-flow-security-review.md): the Fable security review of 23 November against the OWASP Top 10, the OWASP Top 10 for LLM Applications and the OWASP Top 10 for Agentic Applications, with a suggested order for fixing. Nothing in it has been fixed yet.

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
| Track 1 task 6: supervisor visibility items 3 and 5 | **Uncommitted.** Recorded in [learning-flow-supervisor-visibility.md](learning-flow-supervisor-visibility.md). The truncated-sample note is moved to task 9 |
| Task 10 design, decisions D1–D6 | **Uncommitted.** All six settled as recommended |
| Task 10 step 1: shared model and server | **Uncommitted.** Built and tested |
| Task 10 step 2a: client *Quality checks* section | **Uncommitted.** Built; **stories now run and pass** (see below) |
| Task 10 step 2b: the step cards | **Uncommitted.** New, 19 November. Built and tested |
| Task 10 step 2c: the decision pane | **Uncommitted.** New, 19 November. Built and tested |
| The rename: plan updated, stage R written | **Uncommitted.** New, 19 November. **No code renamed** |
| Task 10 step 3, all of it | Committed by the user (`1629dc613d`). Task 10 is complete |
| Track 2 step 2: the Smart Pipelines list | **Uncommitted.** New, 20 November. Built and tested; see below |
| Track 1 task 3: the missing year | **Uncommitted.** New, 20 November. Fixes 1, 3 and 4, the AS6 work, the literals check and the rollout note; see below |
| Track 1 task 2: parser drift | **Uncommitted.** New, 20 November. **Complete**: declared skips (A148), completeness, fix 1, and the skip count shown; see below |
| Track 2 step 3: the spend breaker and rate limit | **Uncommitted.** New, 20 November. **Complete** server-side (A24); its UI is step 4's; see below |
| Track 1 task 5: `claimTasksOnWorker` | **Uncommitted.** New, 20 November. **Complete**; it fixes the three red `stroom-app` tests; see below |
| Track 1 task 9: the small fixes | **Uncommitted.** New, 20 November. **Complete** (eleven items); see below |
| Track 1 task 4: mixed record elements | **Uncommitted.** New, 20 November. **Complete**, with N2 option E's refusal; see below |
| Track 1 task 8: sound multi-node learning | **Uncommitted.** New, 20 November. **Complete** but for W7-7; see below |
| Track 2 step 1b: delete the QA assessments | **Uncommitted.** New, 20 November. **Complete**: client, shared types, server, the table and the stories; see below |
| Track 2 step 1c: flows in JSON | **Uncommitted.** New, 20 November. **Part done**: the three setups are JSON resources with a reader, an endpoint and tests, and the client fetches them. The shared cleanup, the DSL removal and the `ESCALATING` default are **not** done; see below |
| Track 2 step 1 (and 1a) | **Uncommitted.** New, 20 November. **Complete**: review by default and the permission; one promotion path (A); no provisional bindings (C) with the *On trial* chip and *Accept now* gone (1a); the unused parts (F); the Pipeline Factory (G); Improve on the worker (A46); N9's recording and resume. N9's rebuild service is moved to step 4, where its reader is. See below |

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

## Track 2 step 2, the Smart Pipelines list (20 November)

Decision N1 option C, built: a learned stylesheet may use only what is on an allow-list, checked when a
candidate is judged and again when it is promoted.

| Part | Where |
|---|---|
| The list, behind an interface so step 6 can swap it for the Egress Policy screen | `AiXsltAllowList`, `HardCodedXsltAllowList` |
| The static check | `LearnedStylesheetCheck`, `StylesheetScan` |
| As a check of the one model | `Check.PERMITTED`, `PermittedScorer`, outcome `permitted-short` |
| Again at promotion | `Stage.bind`, `FragmentCodes.stylesheets`, `StopReports.refusedByTheList` |
| The harness applying the same list | `XsltStep`, `StroomStandIns`, `StreamFacts` |

Ruling **A147 is amended** in `design/09-rulings.md`, from "any candidate calling an extension function is
refused" to the allow-list form; `ISSUES.md` item 21 records it as built.

### The decisions in it

None of these were settled by the plan, so they are the ones to argue with:

- **`PERMITTED` is a gate a Learning Flow cannot turn down**, like compile. A safety check, not a quality
  one: on an automatic flow nobody reads the stylesheet before it runs.
- **It is not counted as a *quality* gate** in the readiness banner — a translation can be safe and
  worthless, and a document with every quality check off must not look half-protected. The route map's
  total does count it: *Standard · 8 gates*, 6 of them quality gates.
- **The check refuses what it cannot read.** An unparseable stylesheet, an undeclared prefix, an unclosed
  brace: each is a refusal. An allow-list that passed what it could not understand would not be one.
- **The harness refuses rather than guessing.** A run that does not know the stream fails a partial date
  rather than taking the year from the clock — AS6 from the other side.
- **The name.** `Check.PERMITTED`, `permitted-short`, shown as "Uses only what it may". Cheap to change
  now; a migration once the demo pack ships with it in stored documents.

### What is left of it

- **No run-time enforcement here** — it is the separate XSLT egress project's (23 November); until its code
  arrives this is N1's accepted position (AS21), not a gap introduced
  here. A route the static check misses runs unchecked on a node.
- **25 of the 32 allowed `stroom:` functions have no harness stand-in** (`StroomStandIns.missing`), so a
  stylesheet using one passes the check and fails in the scenario suite. Nothing uses them; a test pins the
  list so it shrinks on purpose rather than being found by a scenario that mysteriously fails.
- **The `stroom-app` test holding the stand-ins to the real functions is not written, and cannot be yet.**
  Until task 3 makes the worker set `MetaHolder` and `MetaDataHolder` per attempt, the real `format-date`
  takes a missing year from the clock, so there is nothing stable to compare against. That test belongs to
  task 3, which is where the plan puts it.
- ~~**`xsl:message` is refused**, and the declared-skips design may have to reverse that.~~ **Settled 20
  November 2026**: the design chose a marker in the output, so the entry stands. `HardCodedXsltAllowList`
  now says why, and its refusal message points the model at the marker.

## Track 1 task 3, the missing year (20 November)

The fault the dev instance showed on 11 November: syslog writes `Jun 17 09:06:02` with no year, the
translation guessed one, and every event was dated three years out while passing every other check.

| Part | Where |
|---|---|
| The prompt explains partial dates | `Templates.TRANSFORMATION_RULES`, `VERSION` 7 |
| The times check | `Check.TIMES`, `TimesParameters`, `TimesScorer` — advice, gate under *Strict* |
| The literals check | `Check.LITERALS`, `LiteralsScorer` — advice, gate under *Strict* |
| The stream's time when judging (AS6) | `StreamHeld`, `PipelineStreamHeld`, `DeferredWorker` |
| The zone (N6) | `ResolvedZone`, the `timeZone` property, `PipelineZones`, `ZonedMetaData` |
| Proof it works | `TestJudgingTakesTheYearFromTheStream` in `stroom-app` |

### The one that proves the rest

`TestJudgingTakesTheYearFromTheStream` runs a stylesheet calling `stroom:format-date` through the **real**
pipeline. I checked it bites by removing the held stream: it then wrote **2026**-06-17 where the stream
says **2023** — the dev-instance fault, reproduced. Its third test holds `StroomStandIns` to the real
function, which is the comparison Track 2 step 2 could not make until the metadata was held.

### Two bugs found on the way, both mine

- **The streamed path dropped the received time.** `Supervision.streamed(...)` rebuilds the `Input` from
  its parts to attach the opener, which lost the field I had added two steps earlier — so every stream
  over 64 KiB would have had no year reference while small ones kept it. Exactly the fault this task
  exists to prevent.
- **The literals check missed the feed name**, because a stream's feed and type are its own fields rather
  than headers and I was iterating the attribute map. It goes through `StreamFacts.attribute` now.

### What is left of it

- **The `timeZone` property is not checked when the pipeline is saved**, which N6 asks for. There is no
  hook: `PipelineStoreImpl.writeDocument` has no element-level validation and no element validates in its
  setter. A bad zone saves and is reported at run time instead. **The plan sets out three ways forward
  and this needs a decision** — see *A decision N6 needs* in the delivery plan.
- **The zone and its source are not on the Learning Status tab** (AS18): a client change, which belongs
  with Track 2 step 4's other additions.
- `MetaDataHolder` in `stroom-pipeline` gained a `getMetaDataProvider()` so that the zone could be added
  by wrapping rather than replacing. A two-line accessor, but outside the module.

## Track 1 task 2, declared skips and completeness (20 November)

Task 2's gating item was the design it waited on, and the design is now a decision: **a translation says a
record is deliberately not an event by writing a marker in its output, and there is no cap on how many it
may declare.** [The design](learning-flow-declared-skips-design.md), ruling **A148**.

```xml
<Skipped xmlns="shapeshifter:skips" reason="header line"/>
```

| Part | Where |
|---|---|
| The marker, counted apart from the events, with its reasons | `DeclaredSkips`, `Records`, `OutputRecords` |
| Ignored by the Schema-valid check | `SchemaConformanceScorer`, via `DeclaredSkips.stripping` |
| Dropped before the writer, by never being handed on | `RecordGate`, `ServedStream.skipped` |
| Completeness itself, a gate in every preset | `Check.COMPLETENESS`, `CompletenessScorer`, `completeness-short` |
| The count said even when the check passes | `Verdict.notices` → `Judged.of` → the error stream |
| On the served stream | `ShapeshifterAiSkipped` (`Produced.SKIPPED_ATTRIBUTE`) |
| Taught to the model | `Templates`, `VERSION` 7 → **8** |

### Why a marker and not `xsl:message`

In Stroom, `xsl:message` whose text is not wrapped in a severity element is logged as an **`ERROR`**
(`XsltFilter.onXsltMessage`), and an `ERROR` drops records from the output — the very fault completeness
exists to catch. The safe form is a wrapper a model will get wrong sooner or later, and getting it wrong
loses data. A marker is data: written wrongly it fails to count, and nothing is lost.

### The hole this accepts, deliberately

A declared skip is a hole in completeness that the model controls, and the user declined a skip-rate cap
in favour of showing the count. `TestCompletenessScorer.aSkipForEveryRecordStillPasses` holds that: a
translation may declare every record skipped and completeness still passes.

**So the unfinished part matters more than it looks.** The count is *not* on the Learning Status tab, nor
beside the daily volume on the Quality Assurance tab (which would also need a column on
`shapeshifter_output`). Both are client work for Track 2 step 4. Until they land, what a person reads is
the error-stream line and the stream's attributes — thinner than the decision assumed. **If any part of
task 2 is worth finishing next, it is this one.**

### A bug it turned up, not mine this time

`RecordJoin` — which puts the per-record outputs back together as one document — passed on only the
**first** record's namespace declarations. An element a later record declared its own namespace for was
written with no binding at all and read back in whatever namespace surrounded it. The skip marker is the
first thing to use a namespace that varies record to record, which is why it had never shown; it counted
as an event, failed the Schema-valid gate and would have reached the writer. Fixed, with `TestRecordJoin`
holding both halves of it.

### Two story fixtures had to change

- `NO_GATES_DOC` now names `COMPLETENESS` as `OFF`. A check a document does not name takes *Standard*'s
  default, so a "no gates" document silently acquired one. The story means what it says again.
- `SetupRouteMap` expects **9 gates, 3 advice checks**. The gate is completeness; the *three* advice
  checks were already right before today and the story had not been re-run since task 3 made `TIMES` and
  `LITERALS` built. Worth knowing that these stories are the only thing that catches that kind of drift,
  and they take about two minutes.

### Fix 1: what the parser made of the input (20 November)

The other half of task 2, and the half the whole task is named after. A serving run could only say what
came out; now it says what went in.

| Part | Where |
|---|---|
| The records the parser cut, counted on its own fork | `RecordTap.records()`, `InputRead`, `ServedStream.input` |
| Its errors, counted **before** they are de-duplicated and capped at twenty (W4-2) | `PipelineFragmentRunner.read` |
| Whether the stream held content — whitespace is not content | `ContentCountInputStream` |
| The stand-in agreeing with the real runner (W4-7) | `TestFragmentRunnersAgree.bothRunnersCountWhatTheParserMadeOfTheInputTheSameWay` |
| The judgement | `Stage.inputShortfall`, and the same in the worker's full pass and `settle` (W4-4) |
| This run's score on the output row and the stream (W4-6) | `Stage.producedBy(doc, binding, served)`, `Produced.scoring`, `Supervision` |
| The relearn loop bounded (W4-5) | `Stage.MOST_SESSIONS_PER_STREAM` = 3 |

**The measure is records, not characters** — your decision of 20 November, audit finding W4-3. Learning
measures coverage as a share of characters (A36), which cannot be counted over a streamed input without
estimating it; the records the parser cut can be counted exactly. The two come out close: on the scenario
with six unmatched alarm lines, characters give 0.695 and records 0.700.

**What defers, and what does not.** A stream the parser read *nothing* of defers whatever the checks say —
that is not a quality threshold, it is the translation not working here, and the compile gate is fixed for
the same reason. Everything short of that goes through the Learning Flow's own checks, and only as gates:
records lost through `COMPLETENESS`, a parser reporting records it could not read through `COVERAGE`. A
*Lenient* document that has turned coverage down to advice goes on serving and its rolling score falls,
which is what Lenient asks for.

> **A thing to look at when the demo evaluation is re-run.** `DemoDocument` has input coverage **off**.
> Had the zero-records rule been left to that check, the dev instance's own fault would still not have
> been caught on the document it ran. It is not left to it — but a demo document that cannot see a feed it
> reads only half of is worth a second look.

Two scenarios changed meaning rather than being patched, both correctly: scenario 28's drifted stream is
now put back for the learner instead of being served as nought events, and scenario 27's feed with
unmatched alarm lines goes on serving because that document has coverage as advice. Held by
`TestScenario64TheStreamTheTranslationStoppedReading` (six tests).

### The skip count, shown (20 November)

The last of task 2, and not left for Track 2 step 4 because it is the whole of the mitigation for there
being no skip-rate cap.

| Where | What it says |
|---|---|
| The Learning Status tab's row for a feed | `Scores 98% · 96 of 1,204 skipped` |
| The Quality Assurance tab, beside the volume | *"… 18 of those records were declared skipped by the translation."* |
| The Error stream, as a stream is served | `312 event(s), 4 record(s) declared skipped: header line` |
| The stream's own attributes | `ShapeshifterAiSkipped` |

Kept over the **same rolling memory as the score** (`shapeshifter_learner_state.rolling_skips`) so that a
translation that skipped badly once stops looking as though it still were — the skips of records the
memory drops go with them, in proportion — and per stream on `shapeshifter_output.skip_count`. A
translation that skipped nothing says nothing, so the figure means something when it appears.

> **Two columns were added to the unreleased migration** and jOOQ regenerated, as N5 did for the QA
> assessments. The 54 `stroom-learning-flow-impl-db` tests run against a real database and pass, so the
> migration and both columns are proved end to end, not just compiled.

**`gradle/jooq-codegen.gradle` had a bug**: `-PjooqDbHost` moved only the root connection, while the
migration and the generator still went to `localhost:3307`. Regenerating from this container was
impossible until it was fixed. It now uses the given host throughout.

> **Regenerate jOOQ here with:**
> `./gradlew :stroom-learning-flow:stroom-learning-flow-impl-db:generateJooq -PjooqDbHost=stroom-all-dbs:3306`

### What is left of task 2

**Nothing.** Task 2 is complete.

## Track 2 step 3, the spend breaker and rate limit (20 November)

A44's counter was written by every model call and read by nothing. An attempt's budgets bound **one**
session; a feed that kept opening them was bounded by nothing at all, which is why
`simplification-options.md` §5 lists this as a thing that must exist before anything runs unattended.

| Part | Where |
|---|---|
| The policy over the counter | `SpendBreaker`, checked in `Stage.recording` before an attempt is opened |
| The cluster's total, which the counter could not give | `Spend.spentAll` |
| The cluster's ceilings | `shapeshifter.spendTokensPerHour` (10,000,000), `spendCallsPerHour` (1,000) |
| A Learning Flow's own | `ShapeshifterAiDoc.spendTokensPerHour`, `spendCallsPerHour`; null or zero leaves it to the cluster's |

**Four ceilings, not two**: a per-document ceiling cannot bound a cluster, and a cluster-wide ceiling
alone would let one runaway feed starve every other. Tokens *and* questions, because a model may be priced
or throttled by either.

**It defers rather than errors.** No new session opens; the streams wait, saying which ceiling and that it
clears when the hour rolls; a session already running finishes, being inside its own budgets. Nothing is
lost, and learning resumes by itself. That is what A24's "error mode" becomes, now A57 has the name.

> **The number to argue with:** 10,000,000 tokens an hour cluster-wide, which is about eighteen whole
> learnings at the dev instance's 550,000 tokens per feed. A backstop against a runaway, not a budget —
> and the first thing to change for a given model's prices. Zero turns a ceiling off, and the config says
> what that leaves.

**Left for Track 2 step 4**: the breaker's state on the readiness banner and Monitoring > Smart Pipelines,
and fields for a Learning Flow's own ceilings on the Setup tab. The cluster's ceilings are configuration
and need no screen, so the safety item §5 asks for is met; a document's are set through the document until
step 4.

Ruling **A24 is marked built** in `design/09-rulings.md`, and `design/10-status-and-future.md` moves it out
of *designed and deferred*.

## Track 1 task 5, `claimTasksOnWorker` (20 November)

Option H, as the task document sets it out. The check, `SupervisedPipelines` and
`SupervisedPipelineCheck` are deleted, and `ProcessorFilterServiceImpl` goes back to the shape it had
before `579e4969b8`.

**This is what the three red `stroom-app` tests were.** `TestDemoContentPackImports` has failed all
session — *"Pipeline … holds a supervised stage … Set stroom.processor.claimTasksOnWorker to true"* — and
it passes now. The whole of `--tests 'stroom.shapeshifter.*'` is green: 67 tests, nothing failing.

| Change | Where |
|---|---|
| The check and its hook, gone | `SupervisedPipelines`, `SupervisedPipelineCheck`, `requireWorkerClaimingForSupervised`, the two multibinders |
| A filter over an AI pipeline, both ways round | `TestASupervisedPipelineRunsInEitherMode` (replaces `TestASupervisedPipelineNeedsWorkerClaiming`), including a pipeline that only *inherits* the element |
| A whole AI feed end to end with the master queue | `TestScenarioAFromNothingToServed` and `TestScenarioAStepsFourToSix`, parameterised over the flag |
| A factory making an AI pipeline either way | `TestPipelineFactoryMakesPipelines`, new test — the case option H is really about, since a Content Template fires for a feed name the sender chooses |
| The flag removed from six other scenarios | They never needed it for their own sake |

### Two things that did not go as the task document expected

- **No natural refusal of a *filter* could be arranged** for `aPipelineWhoseFilterIsRefusedIsDeletedAgain`.
  `PipelineMaker` runs as the factory's run-as user, and nothing in `ProcessorFilterServiceImpl.create`
  validates the settings a factory supplies. Rather than assert against a stub, the test was rewritten
  around the failure that *is* natural: a factory property whose **value** names a variable the key does
  not hold. Property *names* are checked before the pipeline is made; a value is only substituted in
  `writePipeline`, which is inside the try — so the pipeline is made and then deleted again, which is the
  claim. It is now `aPipelineThatCannotBeFinishedIsDeletedAgain`.
- **The cluster trial is an inspection** (your decision). §7.2 of the task document now answers its four
  questions by reading the code, and **§7.3 says what that cannot show**. The short of it: a deferral and
  a wake both carry `null` for the node, and the master's fill resets `lastTaskId = 0` on every call and
  selects on status and filter — so a woken task is exactly what the next fill finds, whoever woke it.
  What is unproven is timing and contention on a real cluster, and the large-wake case is the one where
  reading the code is least satisfying.

## Track 1 task 9, the small fixes (20 November)

Eleven items from the audit, each independent. The three worth reading about:

- **S4, the sample size limit.** It was cut twice — to the Learning Flow's limit, then again by a fixed
  **6,000 characters** inside the prompt. With the default of 8,192 the model never saw the last quarter
  of its own sample *while the parser's split was judged on all of it*, and raising the limit to give a
  strong model more context did nothing above 6,000. It is now one setting, honoured by the prompt, and
  counted in **bytes** as its label has always claimed.
- **S5, the held-out share.** A markup or JSON document within the limit was shown whole, so A14 —
  "judged on records the model never saw" — did not hold for those feeds, though the editor said it did.
- **S3, the worker's encoding.** The worker decoded every stream as UTF-8 whatever the feed said, while
  serving honoured the feed's encoding. A windows-1252 or UTF-16 feed was learned from wrongly decoded
  text and then served correctly decoded: the parser was taught against characters the stream does not
  contain.

The rest: a token budget of 0 meaning unlimited on the server as it does in the editor (**S11**);
`asDocument` reading the root element rather than looking for the characters `<Events` (**W1-2**); the
chain question no longer telling the model `JSONParser` produces `records:2` when it produces the XSL
JSON vocabulary (**W5-7**); the learnable element list pinned against the installed runners (**S6**); and
the comments that gave A17's unbuilt redaction as the reason stream text is not stored, which A146
settled (samples are never redacted).

> **One judgement worth knowing about (S13).** The corpus stream type is now a *stored* type, so it can
> be picked and therefore **excluded** from a processor filter written over a feed alone. It is
> deliberately **not** in `ALL_HARD_CODED_STREAM_TYPE_NAMES`, because that set is also what a sender may
> declare on receipt — and nobody outside should be able to post a stream claiming to be a translation's
> corpus, since the corpus is what the next translation is held to.

**Not done**: capping the sample at a share of the model's *Max context window tokens*. The plan calls it
optional, and it is a guess about tokenisation that the byte limit makes unnecessary for the fault S4 was
about.

## Track 1 task 4, mixed record elements (20 November)

`GATEWAY-AUDIT` could not be learned **at all**. Every record is a child of `<batch>`, but they come
under nine different names, and the split step could only say *every element named X* — which then had to
hold 90% of the document. `transfer` holds 41% and `authentication` 20%, so every answer that named a
real record was rejected and the step ran out of candidates. The model diagnosed it correctly on its
fifth try and had no way to say so.

A boundary can now be **the children of an element**: `batch/*`, or `*` for the root's own children.
Stored as a real field (`RecordBoundary.children`), not `batch/*` stuffed into the element field, and
`equals` tells it from the element of the same name.

**The other half of why it failed**: the split is judged on a *sample*, the first `</batch>` is at byte
11,318 and the default sample is 8,192 — so `batch` "occurs once" there and the old check read a
container that repeats in the stream as a container of records. `recordChildren` allows that; what it
refuses is a container with a *single* child, and it says to look one level deeper or one level up.

**Every consumer of the boundary handles it explicitly** — in code there is exactly one,
`OutputRecords.recordsBy`.

> **A correction.** I first reported that N5's new column was not needed because the boundary lives in
> the binding's JSON. **It does not** — it is stored in columns, and I found that while reading
> `LearnerBindingsDao` for task 8. `boundary_children` is now in the unreleased migration on both
> `shapeshifter_binding` and `shapeshifter_output`, with jOOQ regenerated. Without it a node would have
> lost a children boundary silently: written as all-nulls, read back as no boundary, the fragment
> splitting in the wrong place.

### Also: the sample-size refusal (N2 option E)

A stream that is one XML or JSON document larger than the prefix is refused, with a report that says *a
part of a document is not a document*, that a larger limit is not the answer, and that the feed should be
sent as records or split before the stage. Before, the split passed with **no boundary at all** and
everything after it was judged on nothing.

> **The near-miss worth knowing about.** My first version refused whenever the sample failed to parse —
> which caught a CSV stream of a million lines, because a chain whose first element is a transform reads
> anything down that branch. That would have refused the feeds this whole feature is for. It now also
> requires the sample to *look* like markup, and
> `aStreamOfRecordsLongerThanThePrefixIsLearnedFromItsPrefixAsBefore` holds it.

Held by `TestMixedRecordElements` (8, over the real fixtures and all three drift files),
`TestScenario65MixedRecordElements` (3, end to end), `TestOneDocumentLargerThanThePrefix` (2).

**Not done**: re-running `GATEWAY-AUDIT` (stream 2117) on the dev instance, which needs the MCP server.

## Track 1 task 8, sound multi-node learning (20 November)

The four W7 findings the audit raised about several nodes learning and serving one learner, plus the
harness it asked for. **Track 1 is now complete except task 7.**

| Finding | Fixed by |
|---|---|
| **W7-1** a lost cache event is never backstopped under traffic | `expireAfterWrite` beside `expireAfterAccess` on both caches |
| **W7-2** a stale node can append a second live binding | `append` hands back the live one; `shapeshifter_binding_live` is unique over the learner while not a draft |
| **W7-3** `replace` is last-writer-wins despite its comment | `Binding.version`, checked in the `WHERE`; a refused write returns false and clears the loser's cache |
| **W7-4** first-learning deferrals are not swept awake | `stampsWaiting` includes rows with no stamp |
| The harness | `TestTwoNodesOneLearner` — two caches over one store, each with a bus that reaches nobody else |

**W7-1 in one sentence**: `expireAfterAccess` never expires a *busy* learner, because every stream
touches it — so a node that missed the clearing event went on serving an old binding for as long as the
feed kept arriving, which is exactly when it matters.

**W7-2's guard is in the database**, not only in Java: two nodes racing should lose the race somewhere
that both of them can see.

> **A trade-off taken deliberately.** Including the unstamped rows means the sweep re-wakes a learner
> whose first-learning rows are still waiting, every pass, until those streams are served — a deferral
> goes when its stream is served, not when it is woken. Waking an already-`CREATED` task is an update of
> no rows. The precise alternative (compare the row's wait against the binding's promotion time) is right
> in production but cannot be tested here: the in-memory store uses wall-clock time while the stage runs
> on a fixed clock. Three scenario tests now record the second wake so it is documented, not a surprise.

**Not covered: W7-7** — `bind` spans several transactions, so a crash between the append and the close
makes the next worker treat the learner as relearning. Not in the task's list; the audit calls it minor.

## Track 2 step 1, most of it (20 November)

Five of step 1's seven parts are built. What is left is moving Improve from the web request to the
worker, and N9 option D extended (record each run's templates and texts, rebuild a turn's prompt for
someone with *View Data*, resume from the recorded templates).

### Review by default, and the permission (SIM-1 option B)

- `ShapeshifterAiDoc.DEFAULT_PROMOTION_MODE` is `REVIEW`. A new Learning Flow waits for someone to
  approve what it learns; A9's automatic default is gone.
- A new application permission, `APPROVE_SMART_PIPELINES_AUTOMATICALLY` (*Approve Smart Pipelines
  Automatically*). `ShapeshifterAiStoreImpl.writeDocument` refuses the move **to** automatic without it,
  so the check holds on import as well as on save. Switching back to waiting for approval needs only
  EDIT, and someone without the permission can still edit the rest of a Learning Flow that is already
  automatic.
- Every scenario and integration test that is about the learning machinery rather than about approval
  now says `.promotionMode(AUTOMATIC)` explicitly — `Scenarios.document()`, `DemoDocument.configure`,
  and six `stroom-app` tests.

### One promotion path (option A)

Whatever the mode, a result that clears the gates is written as a draft. `Stage.approvesItself(doc)`
decides whether the system approves it at once or a person does; both go through one new private
method, `promoteDraft`, which `approve` also calls.

**A58 is kept, which cost more than re-pointing the binding would have.** The obvious implementation —
point the incumbent binding at the draft's fragment, as `approve` already did — leaves the learner with
two fragments and breaks "one fragment per learner". So approval now **copies the draft into the
fragment that serves** and deletes the draft's documents: `FragmentWriter.copyInto`, and a new
`ContentCreator.delete` so the explorer node goes with the document. The serving pipeline keeps its own
`DocRef`, which is better than what review mode did before: every output ever recorded against the
binding still names the pipeline that serves it.

### No provisional bindings (option C / SIM-3 option E), and step 1a

The provisional state is gone from the shared model, the server, the database and the client: no
`Binding.provisional`, no `AttemptStatus.PROVISIONAL`, no `Decision.Provisional`, no
`Produced.PROVISIONAL_ATTRIBUTE`, no `provisional` column on `shapeshifter_binding` or
`shapeshifter_output` (edited out of the unreleased migration, N5, and jOOQ regenerated), no
`Stage.accept`, no `accept` endpoint, no *On trial* chip, no *Accept now*, no *Trust* figure.

A first result learned from too few records for a held-out judgement (A14) is now a draft **even on an
automatic Learning Flow**: `bind` asks `judged.records() >= doc.getMinRecordsToLearn()` as well as the
mode. The record minimum is a first learning's bar only, so the relearning paths don't ask it (N4).

Scenario 14 is rewritten around the new behaviour; scenario 28 (a provisional binding is not retracted
by the floor) is retired, with a note where scenarios 25 and 26 are.

### The unfinished and unused parts (option F)

- `regressionRetentionDays` removed from `ShapeshifterAiDoc`, with `ScoreValues.toDays`/`fromDays`.
  ISSUES item 24 closed.
- The `record_spans` column, `Outputs.span`, `Judged.spans` and `OutputsDao`'s span reading and writing
  removed. Written for every learned stream, read by nothing but its own test; scenario 38 is retired,
  with a note. What it was for — putting a fault back to the model with the record that made it — is the
  regression corpus's job, and that keeps the record's own text (A56).
- `ERROR_LOAD` and `EVENT_CLASSIFICATION` were already gone with task 10.
- `shapeshifter_spend` and the Admit step are kept, as the option says.

### The Pipeline Factory (option G)

Removed: `stroom-pipeline-factory` (both modules), `PipelineFactoryDoc` and its shared types, the client
editor and its plugin, the workbench stories, the two CSS files, the icon, the document-type registry
entry, the config class and its provider, the Guice wiring in `CoreModule` and `DbConnectionsModule`,
the GWT module inherits, the checkstyle exclusions, and the `stroom-app` tests. `expected.yaml`, the
swagger spec and `SvgImage` regenerated.

Nothing in `stroom-learning-flow` depended on it. The comments that cited it now cite Content Templates,
which is what makes each feed's pipeline from here on (SIM-7 option B). It was never released, so no
installation loses any configuration, and its migration went with it rather than being undone.

**Worth a reviewer's eye:** anyone who made a `PipelineFactoryDoc` on a dev instance will find the
document type gone when they next start that instance.

### N9 option D extended: parts 1 and 2 built

Parts 1 and 2 are built: a run records the words it was asked with, and a parked run resumes in them.
Part 3, the rebuild service behind *View Data*, goes with Track 2 step 4's client, since until that
lands it would be a service with no reader — which is exactly what simplification option F has just
finished deleting. Part 4 is step 4's.

**What has to be recorded** (four columns on `shapeshifter_attempt`, which is in the unreleased
migration, so N5 lets them be added in place, with jOOQ regenerated):

- the resolved template set as JSON — every `Template` with the text the run used, the Learning Flow's
  own where it overrode and the built-in otherwise, so the prompt can be rebuilt after an upgrade
  without the old templates in the code;
- `Templates.VERSION` as the run had it;
- the Learning Flow's *Instructions to the AI*;
- the build of Stroom that ran it, so a rebuild after an upgrade can say the checks' wording may have
  changed.

**What must not be recorded there: the guidance.** The design speaks of the pipeline's *Notes to the AI*
alongside the instructions, but guidance is not a property of the run — `Stage` reads it afresh before
every question (`guidedBy(() -> guidance.standing(learner))`), precisely so a hint given while an
attempt was parked reaches the turn that resumes it. It already has its own table with timestamps, so a
rebuild should read what was standing at the turn's time rather than a copy frozen when the run opened.
Copying it into the attempt would be wrong as well as redundant.

**The four changes, in order:**

1. **Built.** `AskedWith` in `stroom.shapeshifter.ai.learning`, carried on `Attempts.Attempt` and
   written by `Stage.recording(...)` when an attempt opens, into four new columns.
2. **Built.** `Advisors.of(doc, askedWith)`, so `QuestionText` is built from what the run was asked
   with rather than from the document as it stands; `DeferredWorker` passes the attempt's words.
   `TestAParkedRunResumesInItsOwnWords` is the one that proves it: the Learning Flow is edited while
   the attempt waits, and the worker still hands the resume the run's own words.
3. The rebuild service, behind the *View Data* check — deferred to step 4, see above.
4. The client's *what was asked*, which is Track 2 step 4's.

`AskedWith.UNKNOWN` is what every attempt opened before this reads back as, and both the resume and
the (future) rebuild say so rather than offering today's words as though they were the ones used.

### Improve moved to the worker (A46)

The last thing in A46 that was not as the ruling asked. `Stage.improve` now validates, records the
guidance and opens an attempt **parked at its first question** — the same `RecordedAdvisor.awaiting()`
mechanism a processing task uses — and the Deferred Learning job puts the questions.

Two things made it possible, and both were named in the comment that used to explain why it had not
been done:

- **The attempt remembers it is an improvement.** A new `improving` on `Attempts.Attempt` and an
  `improving_binding_uuid` column. `Stage.resume` reads it and routes to `improveUnderClaim`, because
  an improvement's learner is served exactly like a relearning's — the bindings cannot tell them apart,
  so the attempt has to say.
- **It finds its sample in the regression set.** `Stage.improvementInput(attempt)` puts the binding's
  accepted records back together; `DeferredWorker` asks for that instead of a stream, and the attempt
  records no `inputId`, because there was never a stream.

What a person sees changes, as it had to: the button now says the improvement was asked for, and what
it comes to appears on the Learning Status tab. The failure message changed with it — a failure now
means the ask failed and nothing was spent, where before it meant the run might still be going.

**One behaviour moved, deliberately.** A learner marked to relearn has its mark spent when the
improvement *promotes*, not when it is asked for. That is where it was spent before, and it is the
right place: a failed improvement must leave the mark standing, since what it was marked for has not
been dealt with. Nothing can relearn behind it meanwhile, because the open attempt holds the learner's
claim (A45).

## Track 2 step 1b: the QA assessments are gone (20 November)

Stage 0b group 4, and the plan's reason stands: no requirement for them could be found. Removed in the
AS13 order, client first:

- **Client:** the *Assessments* section of the Quality Assurance tab with its Record an assessment
  button and Mark resolved; `AssessmentEditor`, its `.ui.xml` and its controller; the *Flagged by QA*
  banner on Learning Status with its "See them on the Quality Assurance tab" link, and the
  `setOnOpenQuality` wiring that reached from `PipelineEditor` through `PipelineAiTabController` to
  `FeedDetail`; `QualityFigures.isOpenIssue`; `assessment-editor.css` and the assessment rules in
  `quality-tab.css`.
- **Shared:** `QualityAssessment`, `AssessmentVerdict`, `AssessmentRequest`, and the three
  `QualityResource` endpoints.
- **Server:** `QualityService.assessments`, `assess` and `resolve`; the `Assessments` seam,
  `InMemoryAssessments` and `AssessmentsDao`, with their Guice bindings.
- **Database:** `shapeshifter_assessment`, edited out of the unreleased migration (N5), jOOQ
  regenerated.
- **Stories:** the four assessment stories in `PipelineQualityStories`, and `QaIssueFlagged` /
  `QaNothingFlagged` in `PipelineAiTranslationStories`, with their fixtures.

`TestQualityService` keeps its volume, cardinality, samples and permission tests; the four assessment
tests go, and `someoneWhoMayNotViewThePipelineReadsNothing` now proves the refusal through `samples`
rather than through `assessments`.

The Quality Assurance tab itself stays: the volume, what the records became, the samples and each
learning run's score are all untouched.

## Track 2 step 1c: flows in JSON — the first slice (20 November)

### A conflict with the plan, and the decision taken

The plan says the default setups become "JSON resources (for example `escalating.json`,
`target-first.json`, `direct.json` **beside `FlowExample`**), read with the same Jackson mapper as a
stored document". That cannot be done where `FlowExample` lives: `ShapeshifterAi.gwt.xml` declares
`<source path="shared"/>`, so `stroom.shapeshifter.shared` is compiled to JavaScript, and GWT has
neither classpath resources nor Jackson databind. The client calls `FlowExample.steps()` in production
code — `ApproachFlow.example(...)` to name which setup a document matches, and
`ApproachSectionController.startFrom(...)` to load one.

**The user chose: the presets live as JSON on the server and the client fetches them**, over building
them with builders in shared code. A caveat on the reasoning I gave at the time: I cited
`BuiltInTemplates` as precedent, and it is one only for the *endpoint* — nothing on the client consumes
it yet, so the client-side fetch-and-cache pattern is new, not copied.

### What is built

- `direct.json`, `target-first.json` and `escalating.json` under
  `stroom-learning-flow/src/main/resources/stroom/shapeshifter/ai/learning/flow/`, generated from the
  current `FlowExample` steps so they are faithful rather than retyped.
- `FlowExamples` reads them once with `JsonUtil`, and `all()` offers them with their display names.
- `ShapeshifterAiResource.flowExamples()` and its implementation, with the new `BuiltInFlow` DTO.
- `TestFlowExamples`, seven tests: every setup reads and is a flow the stage can hold (A33), and each of
  the three is pinned to its shape — Direct's fallback to the exhaustion step, Escalating's
  yield-short-goes-back-to-split and its `first`/`again` ids, Target first's parser-back-on-preservation.

Nothing is removed yet, so `FlowExample.steps()` still works from the DSL and nothing is half-migrated.

### The client fetch, built

`FlowExamplesCache` (`stroom.shapeshifter.client`) fetches the setups once and keeps them: they cannot
change while the server runs, so there is no timer and no refresh. Two screens opening at once make one
request; a failure leaves them unfetched so the next ask tries again.

What names a setup takes the fetched list as an argument rather than reading a constant:
`ApproachFlow.example(examples, steps)`, `ApproachFlow.steps(examples, example)`,
`Readiness.of(doc, examples)` and `SetupSummary.of(doc, examples)`. Everything stays synchronous at the
point of use; only the fetch is asynchronous, and each screen that shows a setup's name fetches before
it draws — `ShapeshifterAiEditor.onRead` for the readiness check, `ApproachSectionController.read` for
the Setup tab, which draws again when they land. *Start from* asks the cache and then confirms.

Before the fetch lands the list is empty and a document reads as having **its own approach**, never a
wrong name. `TestApproachFlow` pins that.

`ApproachSectionController`'s `flow` field no longer starts as `LearningFlow.of(FlowExample.DIRECT)`.
Its own steps were never read — the separate `steps` field holds those, and only
`flow.getBuiltInVersion()` is taken from it — so a placeholder claiming to be a default setup was both
a needless dependency and misleading. It is an empty flow now, and **no production code in
`stroom-core-client` reads `FlowExample.steps()` or `LearningFlow.of(FlowExample)` any more.** The enum
itself stays, for the menu and the display names.

### One hidden dependency the client still has, for the shared cleanup to settle

`LearningFlow`'s own constructor falls back to `FlowExample.DIRECT.steps()` when it is given null steps,
and `LearningFlow` is shared code compiled into GWT — so a flow deserialised without steps still gets
Direct's, in the browser as on the server. That is not a client bug to fix in isolation: the fallback is
what makes a step-less document usable today, and it can only become "empty" once the **store** fills a
new document's steps on create. It is the first thing the shared cleanup has to deal with, and it needs
a decision — a document that genuinely has no steps should probably say so rather than silently become
Direct.

**A correction to what I said when the decision was taken.** I claimed `BuiltInTemplates` was precedent
for the endpoint only and that nothing on the client consumed it. That was wrong:
`ApproachSectionController.fetchBuiltIns()` has been fetching the built-in texts all along, and the
flow examples now follow exactly that shape. The precedent was real and the client half was less
novel than I made it sound.

### What is left of 1c
1. ~~**The shared cleanup.**~~ **Done, 20 November.** `FlowExample.steps()` and
   `LearningFlow.of(FlowExample)` are gone; the enum is identity and display name alone.
   `LearningFlow` no longer falls back to Direct for null steps, `ShapeshifterAiDoc.DEFAULT_FLOW` is an
   empty flow, and `ShapeshifterAiStoreImpl.createDocument` gives a new document the steps of
   `DEFAULT_EXAMPLE` — which has to happen there, because `writeDocument` refuses a flow the stage
   cannot hold (A33) and no steps is one of those. See below.
2. ~~**The DSL removal.**~~ **Done, 20 November.** See below.
3. **The `ESCALATING` default** (N3), which the plan gates on a `TestLiveScenarios` run — that needs a
   live model, so it cannot be verified here. This is all that is left of 1c.
4. ~~**The design notes**, which show flows in the DSL.~~ **Done, 20 November.**

### The shared cleanup, and a defect it exposed (20 November)

The decision was no fallback, empty steps, store fills on create — simplest because the fallback was
what blocked deleting `FlowExample.steps()` at all, and because an empty flow already had a meaning:
`LearningFlow.check()` opens with "The flow has no steps", which the Setup tab, the readiness banner
and the store's A33 refusal all say.

**A real defect fell out of it, in `AdvancedSettings`.** Its baseline for "what has been changed from
the defaults" is `ShapeshifterAiDoc.builder().uuid("new").build()` — a document built in the client.
With the empty default that baseline has no steps, so no fallback, so `hasFallback(doc) !=
hasFallback(NEW)` became true for every real Learning Flow: the Setup tab would have shown *Approving
new translations* as changed, on every document, for ever. It is now asked of the document directly —
"has the fallback been turned off", which is what the check means — and a flow with no steps is not
that, it is a flow with no steps, which the approach already reports.

Worth noting how it was caught: not by reasoning, but because `TestAdvancedSettings` went red in eight
places at once. A baseline built from a default is exactly the kind of thing that quietly inverts when
the default changes.

**The churn, which was the expected cost.** `Scenarios.document()` and `TestConversation.policy()` now
give their documents Direct's steps, as the store would; about 35 server test files moved from
`LearningFlow.of(FlowExample.X)` to `FlowExamples.flow(...)`; the client tests use
`FlowExampleFixtures`; and `stroom-core-shared`'s tests build the flow they mean, with `FlowFixtures`
for the two that need a real setup's *shape* because they walk its steps.

`FlowFixtures` and `FlowExampleFixtures` are a second and third copy of the setups, in tests only, and
both say so in their javadoc with a pointer to `TestFlowExamples` as the authority. A shared or client
test cannot reach the server's resources, and moving those tests to the server module would be a larger
change than the duplication is worth.

### The DSL removal (20 November)

Gone: `FlowStep.parse` with its grammar and its error messages, `FlowStep.format`, `Transition.format`,
the DSL's word constants on `Transition` (`on`, `goto`, `abandon`, `spent`) and the three enums'
`parse(String)` methods, which nothing outside the parser used. `toString` on both classes is now a
plain field dump, so a failing assertion still reads.

**What replaces it in code: `FlowStep.builder(QuestionKind)`.** A fluent builder, in shared code, with
`id`, `role`, `when`, `candidates`, `kinds`, `elements`, and four ways to say where a step goes —
`goTo(outcome, step)`, `abandonOn(outcome)`, `whenSpentGoTo(step)` and `abandonWhenSpent()`. The last
two read better than the DSL did: `on spent abandon` never said *what* was spent.

**All 126 call sites were converted mechanically**, by a throwaway translator that reimplemented the
grammar and emitted builder calls, so the move could not introduce a typo in a flow. Long chains were
then wrapped by a second script, and the result read through. Only one call site was dynamic
(`TestConversation` looped over two transitions as strings) and it was done by hand; the two grammar
tests in `TestShapeshifterAiDoc` became tests of the builder and of the limits the step holds however
it is made, which is what survives of them.

**The built-in setup JSON was tidied** at the same time: Jackson had written `"when": "ALWAYS"`,
`"elements": []` and `"transitions": []` on every step, which the constructor supplies anyway. The three
files are now short enough to read as the definition they are. This is not the model tidying the
requirement also asks for — the reserved `goTo` names — which stays with Track 1 task 10 so the step
model changes once.

**One production use of the written form had to move.** `Conversation` keyed a visited edge by
`step.effectiveId() + " " + transition.format()`, for the "each transition at most once" rule; it now
keys on the transition's two fields.

**A pre-existing broken story turned up while verifying.** `App/Editors/PipelineAiTranslation/
Conversation` asserted a score beside a question and found none: the workbench fixture still named the
turn score's check `"scorer": "INPUT_COVERAGE"`, which the one-model-of-checks work had renamed to
`"check": "COVERAGE"`. Nothing to do with the DSL; fixed, with a changelog entry, because it was a real
break in the tree.

## Track 1 task 7: the target schema setting (20 November)

The first slice: the setting exists, resolves, and drives the conformance check. The prompts do not read
it yet, which is the next slice.

**What is built.** `ShapeshifterAiDoc.targetSchema` is a `DocRef` to one `XMLSchema` document — a
version, not a group, because `EVENTS` holds seven versions of event-logging (D1). `TargetSchemas`
resolves it into a `TargetSchema`: namespace, system id, the `version` the XSD declares and the global
element it declares, all read **from the XSD itself** rather than from the document's name, so a schema
installed under another name still says what it is. `XmlSchemaTargetSchemas` reads Stroom's `XMLSchema`
documents; `TargetSchemas.none()` is what a test with no schemas uses.

**The conformance check judges against that one version**, by namespace and system id — a system id is
unique in Stroom, so the two name a single document. Where a document names no target it falls back to
the schema group its own check settings carry, which is exactly what it did before the setting existed.

**The `Scorer` SPI carries the resolved target.** The scorers are shared across documents — `StageFactory`
builds them once per node — so the target cannot live on a scorer instance; it comes down through
`Scorecard`, which `Stage` now builds against `targetSchemas.of(doc)`. Ten scorers and about thirty call
sites changed. It is the right seam rather than the smallest one: `ExtractionQualityScorer`, `YieldScorer`
and `Records` all have event-logging written into them and will need the same target as task 7 goes on.

### The default is deliberately not set yet, and the scenarios are why

D1 says a new document starts on the latest event-logging installed. Wiring that into
`ShapeshifterAiStoreImpl.createDocument` turned ten `stroom-app` scenarios red at once — questions asked
that no script expected, backlogs that never drained.

The cause is worth writing down: a node's test content installs **all seven** versions of event-logging,
so the latest is 4.1.0, while everything the module writes still says 3.0.0 — `Templates` tells the model
to write `xsi:schemaLocation="event-logging:3 file://event-logging-v3.0.0.xsd"` with `Version="3.0.0"`,
and every scenario fixture does the same. A document pinned to 4.1.0 therefore fails conformance on its
own prompt's output, every candidate, for ever.

That is not a test artefact. It is what would have shipped: any node with a newer event-logging installed
would have had every new Smart Pipeline fail the moment it was created. So the default waits for the
slice that builds the prompt from the target, and the two land together. The setting itself, and the
conformance check reading it, are in and tested.

### The prompt and the target checks follow the target (20 November)

The second slice. `Template.TRANSFORMATION_RULES` now carries `${targetNamespace}`,
`${targetSchemaLocation}`, `${targetVersion}` and `${targetRoot}` instead of naming event-logging 3.0.0
in so many words, and the chain question's one-line description of `XSLTFilter` names the target's
namespace. `QuestionText` fills them from the document's target, falling back to event-logging 3.0.0
where it names none, so a document written before the setting existed is asked word for word as it was.
`Templates.VERSION` is 10 and the demo pack is regenerated.

A passage of rules is a template of its own, substituted into the question as a value — and a question
renders in one pass, so a slot inside a substituted value would have been left as written. `QuestionText`
therefore renders the passage first. That is the kind of thing that would have shipped looking right in
the code and wrong in the prompt.

`TargetChecks.asDocument` wraps a proposed event in the target's own root, namespace, schema location and
version, which `Conversation` takes from the scorecard. Without it a non-event-logging document's targets
would all be refused by its own conformance check.

**What is left of task 7** after the third slice below: the Setup tab's picker; the event-logging-only
*scorer* options and the `Admission`/`Corpus` XPaths (step 5's third bullet, which touches the scorers);
and regenerating the demo content for the version a new document now targets. The parts that remove
`schemaGroup` still wait on the *Quality checks* client change (AS13), and time plausibility's time field
still waits on task 3.

### A schema that is not event-logging describes itself (20 November, D2 option G)

The third slice, and the one the requirement "every target schema usable" actually turns on.

`Template.TARGET_STRUCTURE` is new, and `TRANSFORMATION_RULES` carries `${targetStructure}` where the
structural paragraph used to be written out. Its built-in text is event-logging's, word for word;
`TargetStructure` generates the passage for any other schema from its XSD, walking out from the root a
level at a time with `ContentModels` — the same content-model reading the conformance check already uses
for its feedback — capped at 12 lines or 2,000 characters so a large schema cannot crowd out the sample
and the feedback. A Learning Flow that overrides the template wins over both.

`ContentModels.childrenOf` is new, and is what makes the walk possible: `describe` renders a content
model but does not say what the children are called.

**The split is held to reassembling exactly.** `TestTheTargetSchemaDrivesTheWords` keeps the whole
transformation-rules passage as it was written before the split and asserts that a document targeting
event-logging, or targeting nothing, still renders that text character for character. That is the
acceptance criterion ("same prompts, same scores" for existing documents) made into a test rather than
a promise, and it is what makes the split safe to do at all.

`Templates.VERSION` stays at 10: the target variables and this split are two halves of one unreleased
change, and the event-logging text they produce is identical.

**A second XSD fixture**, `sightings.xsd`, with the shapes that matter — a root holding a repeating
record, required children in order, an optional one, a choice and an enumeration — plus a check against
Stroom's own Data Splitter schema, a real one, for the cap.

**Two things the tail of the rules still says in event-logging's words**, for a schema that is not
event-logging: "Do not put fields into Data elements or use the Unknown branch", and "produce an Event
for each one". They are mild rather than wrong, and they belong with step 5's third bullet — the
event-logging-only scorer options and the `Admission`/`Corpus` XPaths — which is scorer work, not prompt
work.

### The default: latest installed, as D1 says (user's decision, 20 November)

With the prompt following the target, pinning a new document to the latest installed event-logging is
right for a live model: it is told to write 4.1.0 and does. What it is not right for is the module's own
corpus — every scenario fixture, and the demo content pack, writes `Version="3.0.0"`, and event-logging
4.1.0's `VersionSimpleType` allows the single value `"4.1.0"`, so none of it validates against 4.1.0.

Put to the user with four options; the answer was **latest installed, D1 as decided**.

So `ShapeshifterAiStoreImpl.createDocument` now settles the target when the document is made, and eight
`stroom-app` scenarios say which version their own canned answers write
(`TargetSchemaFixture.eventLogging300`). That is more honest than what they relied on before — being
judged against a whole group, so that any of seven versions could let an answer through. A scenario
about a live model would say nothing and take the default.

The shipped demo content still writes 3.0.0 while a document made on a standard install targets 4.1.0.
Nothing depends on the two agreeing — the demo document carries its own target once imported — but
regenerating the demo content for 4.1.0 is worth doing before release.

### The Setup tab picker and other schemas made usable (23 November)

The fourth slice.

**The *Target schema* picker** is on the Setup tab, under *AI model and instructions*, below *Model*: a
document picker over `XMLSchema` documents the user may use (`SetupTab`, `SetupTabController`). Stories:
`SetupTargetSchema` (shown and kept on save), `SetupTargetSchemaReadOnly`, `SetupNoTargetSchema`,
`SetupFallbackForEventLogging` and `SetupFallbackNotForOtherSchemas`. The fixture `DOC` now names a target
(`schema-1`, event-logging 4.1.0), and the editor's routes serve `GET /xmlSchema/v1/{uuid}`.

**Untyped `Data` and `Unknown` are event-logging's (W1-4).** `ExtractionQualityScorer` measures them only
where the target is event-logging or none (`TargetSchema.isEventLoggingOrNone`). For another schema only
the required fields are measured, and with none required the check doesn't run.

**The questions name the target's own record (W1-1).** New template variables, filled by `QuestionText`:
`${targetRecord}` in `TARGET`, `${aTargetRecord}` and `${untypedRule}` in `TRANSFORMATION_RULES`
(registered on `Template`). The record element comes from `TargetStructure.recordElement`: the root's
child where the root holds only one kind of element. Otherwise the question names a place ("element under
<Sightings>"). The business-rules line in the system text says "over the Sighting". Event-logging's text is
unchanged character for character (`TestTheTargetSchemaDrivesTheWords` still passes), so
`Templates.VERSION` stays at 10.

**No fallback for another schema (W1-3, the user's decision of 23 November: option "No fallback").**
`Conversation.admit` stops the run for a person (`FlowExit.PARK`) with `StopReports.noFallbackFor` instead
of asking for a marked Unknown. `Stage.exhaust` refuses *Finish with Unknowns* for such a target. The Setup
tab shows a note beside *If the transform can't be finished* once it has fetched the schema and found it
isn't event-logging.

**The rest of task 7, the same day.** All of it, so **Track 1 task 7 is complete**:

- **`schemaGroup` is gone.** `SchemaConformanceParameters` is deleted; the conformance check takes no
  parameters and judges against the target, or the `EVENTS` group where a document names none, which is
  what the setting always defaulted to. Its field is gone from the check dialog. No document migration
  (AS1): a dev document saved with `"type": "schemaConformance"` parameters won't read and must be reset,
  as N5 does for the database. About 20 test call sites moved to `null` parameters. The OpenAPI spec is
  regenerated (`:stroom-app:resolve`).
- **Time plausibility's time field** (`TimesParameters.timeField`, an XPath from a record). With none named
  it judges `EventTime/TimeCreated` for event-logging, or no target, and doesn't run for another schema.
  The check dialog has a panel for it and the window (*Days before*, *Days after*), which it never had:
  **saving the check from its dialog used to reset a custom window to the default**, as it wrote no
  parameters. Stories `TimesDialogOk` and `TimesDialogCancel`.
- **Finish with Unknowns hidden in the client** for a target that isn't event-logging:
  `SupervisorLearner.fallbackUnavailable`, set by `SupervisorResourceImpl` from `TargetSchemas`, read by
  `FeedSituation`.
- **`TargetChecks.refusal`** names the target's own record and root.
- **W1-5, by the user's decision (23 November: "State the limit"):** a record is still a child of the
  root. The Setup tab warns under *Target schema* when the root may hold more than one kind of element.
  The tab learns this, and whether the schema is event-logging, from a new endpoint,
  `GET /shapeshifterAi/v1/targetSchema/{uuid}` (`TargetSchemaSummary`, held to USE on the schema), which
  replaces the direct `XMLSchema` fetch. Story `SetupTargetSchemaRootHoldsSeveralKinds`.
- **W1-6:** the demo document keeps no target, so it works on any install; a test holds its pipelines'
  Schema filter to the `EVENTS` group it is judged against. The demo pack is regenerated.
- The conformance check's plain name is now "Valid against the target schema".

## Track 2 step 4, four of its five parts (23 November)

**Approval evidence and who approved.** Three columns on `shapeshifter_attempt`, in the unreleased
migration (N5), jOOQ regenerated: `evidence` (JSON `CheckResult`s: check, state, score, threshold;
numbers only, as a check's diagnostics quote the stream, A146), `reviewed_by` and `reviewed_time_ms`.
`Stage` records the evidence whenever a run writes a translation (`Stage.evidence(verdicts)`, a check
judging several steps shown at its least score); `approve` and `reject` take who decided, which
`SupervisorResourceImpl` passes as the audit identity. `Attempts.Review` carries it on `Recorded`;
`SupervisorAttempt` has `evidence`, `reviewedBy` and `reviewedTimeMs`. The Learning Status card shows
*How it was judged*, gates first (`Evidence`), and "Approved by jo, <time>". Stories
`AwaitingReviewEvidence` and `ApprovedBy`.

**The spend breaker's state.** The breaker is computed, not stored, so `StageFactory.breaker()` is the
one the stages use and the Supervisor asks it per document. `SupervisorLearner.spendPaused` carries the
breaker's own words; a learning learner held back shows **Spend limit** as its chip in both lists of
Smart Pipelines and a warning on its Learning Status tab. **A deviation from the plan, worth a look:**
it is shown on the rows and the Learning Status tab rather than on the Learning Flow's readiness banner,
which describes the document's settings, not what is happening now. Story `SpendLimit`. The
`shapeshifter_spend.breaker_open_since_ms` and `breaker_reason` columns are still unused.

**The time zone (AS18, N6).** The pipeline's Learning Status tab says which zone timestamps with no
offset are read in, whether it is the pipeline's `timeZone` property or UTC, and what is wrong with the
property, with the stream's `TimeZone` header said to come first. `SupervisorLearner.withTimeZone`, set
by `pipelineLearners` from `PipelineZones`; `TimeZoneSource` is now shared and `ResolvedZone` uses it.
**Not shown:** which zone a particular stream was read in, where its header set one, as nothing records
it; that would need the attempt to keep its stream's resolved zone. Story `TimeZone`.

**What was asked, and N9's rebuild service (the fifth part, 23 November): Track 2 step 4 is complete.**
`Stage.rebuild(doc, attemptId, input)` re-walks the run's first walk over the sample read back from its
stream with `PromptCapture`, an advisor that answers from the record (`RecordedAdvisor`) and keeps each
turn's prompt — the system text with the guidance the turn carried (`Guidance.asSaid`, now shared with
`ModelAdvisor`) and the question — rendered from the run's recorded words. It writes nothing and asks
nobody. `GET /shapeshifterAiSupervisor/v1/{id}/turns/{number}/prompt` (`RebuiltPrompt`) is held to the
attempt's VIEW and, for the prompt itself, to *View Data*; it refuses in words for: no *View Data*; an
improvement (no stream); a stream deleted or unreadable; a run asked before its words were kept; and a
turn from a later round, which only the first walk's re-walk can't reach. Notes say the earlier exchanges
are the turns above, that withdrawn guidance isn't shown, and that an upgrade since may change the checks'
wording. The conversation has *Show the prompt as it was sent* under each question, opening the
`TurnPrompt` dialog. Stories `PromptDialog` and `PromptDialogRefused`; tests `TestRebuildingWhatWasAsked`
and resource refusals in `TestSupervisorResourceImpl`.

**Limits worth a reviewer's eye:** a relearning's later rounds aren't rebuilt (they opened with feedback
from a full pass, which the rebuild doesn't repeat); the prompt shows the system text and the question,
not the earlier exchanges, which the conversation already shows; and the re-walk runs the steps again,
so asking costs a learning walk's CPU without the model.

## Track 2 step 5, under way (23 November)

**Scope, the user's decision of 23 November:** options iv and iii before stage R; option ii (17 storage
interfaces into about 5) and the rest of option i after the first release.

**iv, done.** The in-memory stores (`stroom.shapeshifter.ai.state`, 20 classes) moved from `src/main` to
`src/testFixtures`; nothing in production used them. `stroom-learning-flow-impl-db`'s tests now take this
module's test fixtures (`TestLearningPipelinesDao` checks the database against them). The three
import-order errors `testFixtures` had are fixed, so its checkstyle is clean.

**i, largely done before it started.** On a node, XSLT candidates run through the real pipeline
(`PipelineStepRunner`, `StageFactory.runners`); the parser runners drive Stroom's own Data Splitter, JSON
and XML factories and also report what each record consumed; the full pass and serving run the real
pipeline. The plan's "about 1,900 lines" predates that. What is left is parser candidates through the
pipeline as well, with the coverage filter, which is post-release.

**iii, serving and learning split (23 November).** `Stage` (3,115 lines) is now serving alone (993 lines):
`decide`, `stream`, `serve`, `reprocess`, `dryRun`, waking, and one-line delegates for everything that moved,
so its callers — the resource, the worker and about 60 tests — are unchanged. Extracted, each step behaviour-
preserving with every test green after it:

| Class | Lines | What it is | Pipeline scope? |
|---|---|---|---|
| `Learning` | 1,012 | Learning, relearning, improving, settling round by round, resuming (A28, A45), rebuilding (N9) | Yes, as the runners are |
| `Sessions` (with `Sessions.Recorder`) | 519 | Opening a run, carrying it under its claim, writing its turns and what it came to | No |
| `Promotion` | 356 | Drafts, approve, reject, retract, the one promotion path, snapshots | No |
| `SupervisorActions` | 242 | Grant, exhaust, amend, relearn, give up | No |
| `Judging` | 197 | Running a fragment and scoring it, re-running a learned chain, the record bar, input shortfall | Yes, through the fragment runner |
| `LearningPrefix` | 349 | What the model is shown of a stream (static) | No |
| `Judged`, `HeldPass` | 271 | The judgement records, out of `Stage`'s body | — |

Serving calls learning at two points (`learnAndBind`, `relearnUnderClaim`); learning does not call back.
`Stage` builds the others from its own constructor arguments, so `StageFactory` and the test fixtures are
unchanged. Two doc comments that had drifted onto the wrong methods are fixed.

**The REST class's logic is a service (23 November).** `SupervisorService` (873 lines) holds what
each Supervisor request does and who may make it; `SupervisorResourceImpl` is the 163-line `@AutoLogged`
resource that delegates to it (resource, service, DAO, as `AGENTS.md` asks). `TestSupervisorResourceImpl`
is now `TestSupervisorService`. Approve, reject, retract, grant, exhaust, amend, relearn and give up no
longer build a whole stage in a pipeline scope: `StageFactory.promotion()` and
`StageFactory.supervisorActions()` give the service the pipeline-free classes directly. Improve and the
prompt rebuild still run in a stage, since both run pipelines. `TestRestResources` passes (236).

**Option iii is done, with three loose ends** worth knowing, none of them behaviour:
- `Stage` keeps one-line delegates for the moved public methods, which only tests now call (about 60
  call sites, mostly `scenarios.stage(...).approve(...)`). Moving those tests onto `Promotion` and
  `SupervisorActions` would let the delegates go.
- `Promotion` and `SupervisorActions` are built by `StageFactory`, which Guice builds, rather than by
  Guice themselves: binding them directly needs a `Clock` and the lease bound, which nothing else wants.
- `Stage` still keeps every constructor argument as a field, some now only passed on; its `seed` was
  never read, before or after.

## Stage R, the rename (23 November)

Done in one go on a tree the user had just committed (`21cec95284`), by a script
(`scratchpad/rename/rename.py` in the session; the rules are below), then checked by building.

- **Decisions:** R1 as recommended (`stroom.learningflow`; modules `stroom-learning-flow:stroom-learning-flow`
  and `stroom-learning-flow:stroom-learning-flow-impl-db`); **R2, the user's choice against the
  recommendation: the element's display name is *Smart Pipeline***; R2b, the element type id changed; R3,
  documents moved and links re-pointed.
- **Rules, in order:** the demo pack (`smart-pipelines-demo-v1.0.zip`, folder `Smart_Pipelines_Demo`);
  *AI Translator* to *Smart Pipeline* in code; module and document file names (`shapeshifter-ai-` to
  `learning-flow-`, `ai-translations` to `smart-pipelines`); packages and resource paths; constants and tables
  (`SHAPESHIFTER_` to `LEARNING_FLOW_`, `shapeshifter_` to `learning_flow_`); types (`ShapeshifterAi`,
  `Shapeshifter` + capital, to `LearningFlow`); camel case (`shapeshifterAi` to `learningFlow`); then prose in
  code (*Shapeshifter AI* to *Learning Flow*). Markdown and the `.html` histories keep their old identifiers
  as a record; only paths and links in them changed. Not touched: `CHANGELOG.md`, `unreleased_changes/`,
  `docs-obs/`, the git branch names, and the third-party `zxcvbn.js`.
- **The wire:** the document type id and the pipeline element type id are both now `LearningFlow` (separate
  registries; the element's display name is *Smart Pipeline*); the element's property and default id
  `shapeshifterAi` are `learningFlow`; REST paths `/learningFlow/v1`, `/learningFlowSupervisor/v1`,
  `/learningFlowQuality/v1`; the config key `learningFlow`.
- **Also renamed, worth a reviewer's eye:** the declared-skip namespace `shapeshifter:skips` is
  `learningflow:skips` (it is in the built-in prompt, so `Templates.VERSION` is 11); the stream type
  *Shapeshifter Corpus* is *Learning Flow Corpus*; the explorer folder learned fragments go in is
  *Learning Flow*.
- **Generated:** jOOQ regenerated from the renamed migration (`V08_00_00_001__learning_flow.sql`) and found
  identical to the rewritten code but for comment wrapping and order; the demo pack regenerated; the
  OpenAPI spec and `expected.yaml` follow from the code. **Any dev or demo database must be rebuilt**, as N5
  says: the tables and the document and element type ids have all changed.

## The XSLT sandbox brought in (24 November)

gchq/stroom PR #6081 (issue #6079) is the separate XSLT security project's code. It is already merged on
gchq master, which is 476 commits ahead of this branch, so it was not merged here. AGENTS.md forbids an
agent running `git merge` in any case. Instead, four commits were applied as unstaged changes:

| Commit | What it is |
|---|---|
| `70ca3fb0fb` | #6000, pool invalidation. A prerequisite: the sandbox changes `AbstractPoolCache`. Its hunk on `TestSteppingServiceImportedXslt` was left out, because it only removes an `@Isolated` that this branch never had |
| `af5b07d5ba` | #6036, NUL in `XMLWriter`. A prerequisite: the sandbox changes `TransformerFactoryFactory` |
| `a042d23fb0` | #6079 *Add XSLT sandbox*: `SaxonSecurity`, `SaxonPolicy`, `XsltSecuritySettings`, `XsltConfig.secureMode` and `allowedUriPrefixes`, and the guard test |
| `34af1d51f9` | #6079 *Extend XSLT sandbox*: `AddressCheckingDnsResolver` for XSLT's HTTP functions; `send-email` recipients and a per-run cap |

Their three `unreleased_changes/` entries came with them. A later real merge of master should find the
same content.

**Changed on this branch to fit:**

- **The module's five Saxon sites** use `SaxonSecurity` with `SaxonPolicy.LOCKED`: `XsltStep`,
  `OutputRecords`, `RecordJoin`, `RecordSplit` and `JsonStep`. The guard test failed until they did.
- **`XsltStep.confine()` no longer sets `ALLOW_EXTERNAL_FUNCTIONS` to true.** That setting would have
  reopened `xsl:result-document` and `system-property()`. The `stroom:` stand-ins still work, because they
  are registered in Java.
- **`Conversation`** writes `new Question.Configuration(...)`, so that the guard's regular expression does
  not take the module's record for Saxon's `Configuration`.
- **`TestAiServiceImpl`:** `HttpClientProviderCache` gained `getAddressChecked`, so the test's lambda became
  an anonymous class.

**Passes:**

- compile of all main and test code, and `checkstyleMain checkstyleTest`;
- `stroom.util.xml.*` (127, including the guard);
- the module's 632;
- the `stroom-pipeline` cache, filter, writer and XSLT-function tests (394);
- `TestXPath`, `stroom-ai-impl` (35), `stroom-config-app` and `stroom-config-global-impl`;
- in `stroom-app`, `stroom.learningflow.*`, `stroom.pipeline.cache.*` and
  `TestSteppingServiceImportedXslt` (65).

**Not run:** the full build and the Docker images.

**What it means for Track 3:**

- **Re-reviewed the same day by Fable** (see the security review's *Re-review, 24 November 2026*, and
  the plan's Track 3 table of changes):
  - Finding 2 is fixed while `secureMode` is on.
  - Finding 1 is wider: three more bypasses. In the pool a bypass reaches every `stroom:` function,
    because the sandbox does not govern Java-registered functions.
  - On a node, learned stylesheets run in the real pool, not through the module's `LOCKED` sites.
  - Nothing in the module checks `secureMode`.
- **Two of the four agents were stopped by a safety classifier** when writing attack harnesses: the
  sandbox reviewer and the verifier. N1 and finding 2's fix are therefore reproduced by one reviewer's
  harness, not independently verified. The harnesses are in the session scratchpad
  (`rereview-part1/`).
- **Regression tests added (24 November):**
  - `TestLearnedStylesheetCheck`: the four bypasses, `@Disabled` until Track 3 task 1, shown to fail
    today; a control that passes.
  - `TestXsltStep`: `parse-xml` with a DOCTYPE, and the environment. These pass, and were shown to fail
    without the sandbox.
  - New `stroom-app` `TestPipelineStepRunner`: the real node path, with a control, and `parse-xml` and
    file reads refused. Also `stroom:host-address` running on the node, `@Disabled` until task 1, which
    verifies C2 on the real path.

  When task 1 is done, remove the `@Disabled` annotations: the tests are the definition of done.

## gchq master merged (24 November)

The user merged `origin/master` (`3d7f4ca611`, 476 commits) into this branch and committed it as
`404b1916fb`. It brought in the XSLT sandbox's own commits as well, on top of the copies applied earlier
(`db65b2525b`). The twelve conflicts were resolved by hand and checked file by file against both sides:

- **Master's version taken:** `ApacheHttpClientBuilder` and its test (master has the same timeout fix,
  #6017, with more tests), and `CommonHttpClient` and its test (master's holds the sandbox's secure-mode
  code we had applied, and adds the OkHttp/Apache format detection).
- **Combined:** `TestAiServiceImpl` (our four tests and master's two), `stroom-config-global-impl`'s
  `build.gradle`, and `ProcessorTaskService` with its implementation and mock (`wakeDeferredTasks` beside
  master's `fetch`, `returnToCreated` and `markFailed`).
- **One addition of neither side's:** `ProcessorTaskServiceImpl.markFailed()` also accepts `DEFERRED`, a
  status only this branch has, so that a deferred task can be marked failed. No test covers it yet.
- **The swagger files** were merged by hand, then regenerated with `:stroom-app:resolve`: identical.
- **`DashboardApp.gwt.xml`** is deleted, as master deleted it (the dashboard GWT app was removed). Our
  change to it, inheriting `stroom.learningflow.LearningFlow`, is already in `App.gwt.xml`.

**Passes after the merge:** `compileJava compileTestJava` everywhere, `checkstyleMain checkstyleTest`,
`stroom-ai-impl` (40), `stroom-processor-impl` (278), `stroom-config-global-impl` (95) and the
`stroom.pipeline.xsltfunctions` tests (330). **Not run since the merge:** `gwtCompile`, the Learning Flow
module's tests, `stroom-app`, the full build and the Docker images.

`stroom-app/src/main/resources/ui/dashboard/` is untracked compiled output of the removed dashboard app,
and can be deleted.

## What passes now

> **Re-run 23 November, after stage R (the rename):** checkstyle passes across the whole project
> (`checkstyleMain checkstyleTest`, and `checkstyleTestFixtures` for the module); the module's tests 632,
> `stroom-learning-flow-impl-db` 64 against MySQL, `stroom-core-shared`, `stroom-core-client`,
> `stroom-config-app` (with `expected.yaml` regenerated: the config block moved to its sorted place) and
> `stroom-config-global-impl` pass; `:stroom-app:test --tests 'stroom.learningflow.*'` 42 of 42 and
> `TestRestResources` 236 of 236; every story title under its new name passes with the leak check, 110
> stories, which also compiles the client through the renamed GWT module. jOOQ, the demo pack and the
> OpenAPI spec are regenerated. **Not run:** the full build and `./buildDockerImages.sh`, the gate before a
> pull request.

| Check | Result |
|---|---|
| `:stroom-core-shared:test`, `:stroom-core-client:test` | Pass |
| `checkstyleMain checkstyleTest` | Pass |
| `TestTemplateBindings`, `TestStylesheets`, `TestStroomWidgetsOnly` | Pass (11 tests) |
| Every Shapeshifter story title, with the leak check | 92 of 92 passed earlier in the session: `ShapeshifterAiEditor` 38, `ShapeshifterAiStopReports` 20, `PipelineAiTranslation` 18, `ShapeshifterAiSupervisor` 7, `PipelineQualityAssurance` 7, `StageDecisionPane` 2. The editor's 38 passed again after task 7's second slice; **not re-run after the third** |
| `:stroom-learning-flow:stroom-learning-flow:test` | Pass, 579 tests (step 1b took the four assessment tests with the feature) (Track 2 step 1 retired scenarios 28 and 38, and added the permission, `AskedWith`, resume-in-its-own-words and Improve-on-the-worker tests) |
| `:stroom-app:test --tests 'stroom.shapeshifter.*'` | Passed after task 7's second slice, with eight scenarios declaring the schema their canned answers write. **Not re-run after the third slice.** 42 tests. The `stroom.pipelinefactory.*` tests went with the Pipeline Factory (Track 2 step 1, option G) |
| The same batch with `stroom.config.*` in it | 9 failures, none of them code: the sandbox could not clone `stroom-visualisations-dev.git` for a content pack, and the test database went down behind the first failure. Worth knowing before anybody reads the report and starts bisecting |
| `checkstyleMain checkstyleTest` across the whole project | Pass |
| `:stroom-learning-flow:stroom-learning-flow-impl-db:test` | Pass, against a real MySQL |
| `:stroom-config:stroom-config-app:test`, `:stroom-config:stroom-config-global-impl:test` | Pass (`expected.yaml` regenerated three times: the spend ceilings, the corpus stream type, and the Pipeline Factory's removal) |
| `compileTestJava` across every module | Pass |
| The demo pack, regenerated after 3a, 3b and the promotion floor | `TestDemoContentPack` passes |
| Browser self-test | 165 of 165 |

**Story titles are easy to miss.** Step 1 removed the *Trust* figure and was verified against
`App/Editors/ShapeshifterAiEditor` only; `App/Editors/PipelineQualityAssurance` was still asserting
"Trusted" and went red when step 1b ran it. Run every title that touches a screen you have changed.

Not run: the full build (`clean build shadowJar buildDistribution`) and `./buildDockerImages.sh`, which are the gate before a pull request. Not re-run since Track 2 step 1: the workbench stories and the browser self-test — step 1 took the *On trial* chip, *Accept now* and the *Trust* figure out of the client, so the Shapeshifter story titles should be run again before a pull request.

## Next, in order

*Rewritten 23 November. Everything in the Done table above is committed (`67d54e0648`); the 23 November
work is uncommitted.*

1. **XSLT sandbox brought in and re-reviewed (24 November):** see *The XSLT sandbox brought in*, and
   Track 3's table of changes. Track 3 task 1 is larger and still first, and was started on 24 November
   after the master merge (see *gchq master merged*). Decision S-D8 is new.
2. **Security review (23 November):** [learning-flow-security-review.md](learning-flow-security-review.md) has 28
   findings: two Critical (1, 2) and four High (3, 4, 17, 18). The fixes are now **Track 3** of the plan:
   17 tasks in three groups, with tasks 1–7 before the first release. Tasks 1, 3 and 7 need nothing; tasks
   2, 4, 5, 6 and 10 need decisions S-D1 to S-D5, which are the user's, each with a recommendation in the
   plan.
3. **Stage R is done.** What the plan has left before the first release is the full build and Docker
   images, and the blocked items below; ii and the rest of i are post-release. Options iv is done; ii and the rest of i are
   post-release.
4. Track 2 step 6 is out of scope (23 November): XSLT egress is managed by a separate project, whose code
   arrives in due course, so the rename no longer waits for an egress merge.
5. **When Track 1 is done** (it now is): re-run the demo evaluation, which needs the dev instance.
6. **Blocked on a live model or the dev instance:** the `ESCALATING` default (Track 2 step 1c), re-learning
   stream 2152 on `OTEL-TRACES`, and the demo evaluation.

## Known gaps, recorded in the design or plan

- Import isn't validated for checks or flow (`AbstractDocumentStore.importDocument` skips `writeDocument`); Track 2 step 1 needs the same hook.
- Advice results aren't shown anywhere yet (Track 2 step 4, approval evidence).
- The new step-card warning is client-side only: a flow saved through the REST API with a rule on a check that is off is accepted in silence. Right for a warning, but worth knowing.
- `step-card.css` changed, so screenshot baselines for the step card will differ if `workbenchScreenshotDiff` is part of the routine.
- Many client comments still say "Feeds tab" (`FeedsTab`, `Readiness`, `SetupTabController` …); for the plan's design-notes item.
- **A step's warning shows only on the card of the step selected.** `stepProblems` is problems-only, so the route map and "the approach has a problem" don't mark a step whose only fault is a warning. A user who switches a check off sees nothing until they open that step. Deliberate for now, but worth a decision — it may make the warning too easy to miss.
- **The four changelog entries in `unreleased_changes/` still say "Shapeshifter AI"** and are uncommitted. Since the rename is decided, they can be reworded now so CHANGELOG.md never carries the old name.
- **`smart-pipelines-demo-v1.0.zip` is modified** because step 1 regenerated it (documents now carry `checks`). It is binary, so a reviewer can't see the change; it was regenerated again after Track 2 step 1 removed `regressionRetentionDays` from the document, and must be renamed in stage R. `TestDemoContentPack.theCommittedPackIsTheOneThisCodeWrites` is what catches a stale pack, so regenerate it after **any** change to the document model.
- **Branch state:** `ai_translations_ui_rework` was merged at `7e06bb589c`. `ai_translations_egress` is not to be merged by this project: XSLT egress moved to a separate project on 23 November.
- **The MCP server:** the fix is not in this document — see the memory note `stroom-mcp-server-setup` for where the bridge's config lives on the host and what each error means.

## Uncommitted files

Run `git status` for the full list. **24 November:** the XSLT sandbox, its prerequisites and the module's changes to fit it (see *The XSLT sandbox brought in*); the Track 3 additions to the plan; the security review. **Not part of the rename commit, and belonging with it:** `settings.gradle`, `config/checkstyle/checkstyle.xml`, and the demo pack renamed to `smart-pipelines-demo-v1.0.zip`. Earlier, in summary: the docs above, the delivery plan and the new test protocol; `smart-pipelines-demo-v1.0.zip`; shared `Check*` classes, `FlowProblem`, `LearningFlow` and `ShapeshifterAiDoc`; server scoring, `Stage`, `Conversation`, `TargetChecks`, `StopReports`, `QuestionText`, `StepDetails`, `RecordGate`, `RecordJoin`, `ShapeshifterAiStoreImpl`; client Shapeshifter components, `step-card.css`; workbench fixtures and stories; their tests; and four changelog entries in `unreleased_changes/` (task 1, task 6, task 10 step 1–2a, task 10 steps 2b–2c).
