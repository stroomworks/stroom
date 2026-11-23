# Shapeshifter AI: code assumption audit

*Stage 0a of the [delivery plan](learning-flow-delivery-plan.md) · branch `ai_translations_server` · 17 November 2026*

> **Note, 18 November 2026.** The findings record the code as it was on 17 November (delivery plan assumption AS2) and are not updated. The UI classes cited as evidence (`LearnTab`, `ScoreTab`, `LearnerList`, `FlowChart`, `FlowStepEditor`) were deleted when the UI rework was merged (`7e06bb589c`). For the current screens, see [ui-design.md](smart-pipelines/ui-design.md).

## Summary

The audit read the Shapeshifter AI code for assumptions like the one that made the target schema hard to change: a value that looks configurable, or a change that looks contained, but isn't.
It was read-only: nothing was run against a model or a database, and anything inferred rather than read is marked as such in the appendices.

It produced 66 findings (about 60 distinct, since some reviewers found the same thing from different directions):

| Severity | Count |
|---|---|
| Blocks a planned change as written | 8 |
| Changes an estimate | 41 |
| Minor | 17 |

The main conclusions:

1. **Several planned changes are described wrongly in the documents.**
   The fixed recipe proposed for option B drops two steps the demo depends on.
   Option C doesn't behave as described.
   Parser-drift fix 1 is not a contained change.
   The missing-year fix relies on a function that ruling A147 bans.
   The single-target-schema list is incomplete.
   None of these is fatal, but each needs a decision or a bigger estimate before work starts.
2. **Promotion and learning see only the first 64 KiB of a stream.**
   This undermines the "judged on the whole stream" design, and stops large XML or JSON documents getting a record boundary at all.
3. **Several things are keyed on names where they should be keyed on structure.**
   Examples are `"DSParser"`, `"XSLTFilter"`, `"<Events"` and "a record is a child of the root".
   New parser types, record boundaries and schemas therefore need changes in more places than expected, and some of those places fail silently rather than with an error.
4. **Several nodes running at once has unguarded races.**
   A lost cache event is never caught up while a feed is busy.
   A stale node can start an unrequested, paid session, or add a second live translation.
   The plan has no work item for this yet.
5. **Model cost accounting is optimistic.**
   Providers that don't report usage, and failed calls, count as zero tokens, so the planned spend breaker would not trip.
6. **Two things are lower risk than feared.**
   No Shapeshifter code is on `master`, so migrations only affect dev and demo databases.
   The `claimTasksOnWorker` removal's safety argument holds in the code.

## Findings that block a planned change

| ID | Finding | Blocks | Way forward |
|---|---|---|---|
| S8, W8-1 | The missing-year fix tells the model to use `stroom:format-date`, but ruling A147 (the planned static stylesheet check) refuses every extension function, and the test harness already does. The regression test can't be a fixture test. | Track 1 task 3; Track 2 step 3 | Decide which functions are allowed (decision N1). Write the test in `stroom-app`. |
| W1-3 | The ADMIT step is defined as event-logging's `EventDetail/Unknown`, and its kind is stored per corpus row. For another schema it asks for markup that fails conformance. | Track 1 task 7 | Switch ADMIT off for other schemas, or redefine it per schema. |
| W2-6 | The unknown-models proposal assumes the learning recipe stays editable data; option B removes it. | Option B; unknown-models proposal | Reconcile in stage 0b (decision N3). |
| W4-1 | No input record count exists to judge coverage while serving, and Data Splitter records can span several lines. | Track 1 task 2 | Measure coverage with a filter in the real pipeline run, as refactor i proposes. |
| W4-4 | The worker's full pass ignores serving's result, so changing only `Stage.stream` gives a paid relearn loop. | Track 1 task 2 | Change `fullPass` and `settle` too. |
| W5-4 | A single XML or JSON document of 64 KiB or more is learned from an unparseable prefix, so the split step passes with no boundary *(inferred)*. | Track 1 task 4 on real-size data | Decide how large documents are sampled (decision N2). |
| W5-6 | The text-parser path assumes the Data Splitter throughout. | Any new parser type | Treat a new parser type as a large job. |

## Themes

### Planned changes the documents describe wrongly

| Document | What it says | What the code does | Findings |
|---|---|---|---|
| [simplification-options.md](smart-pipelines/simplification-options.md) option B | Hard-code the `DIRECT` recipe, and a large part of `Conversation` goes | `DIRECT` has no SPLIT or TARGET step, so XML/JSON lose their record boundary, records are not transformed one at a time, and the target and preservation checks never run. The demo runs `ESCALATING`. Only about 250 of `Conversation`'s 1,288 lines interpret flows. The chain's allowed elements live only in the flow (A95 must be reversed), and the Supervisor's path chart is drawn over it. About 35 scenario tests are bound to the example flows. | W2-1 to W2-4, W2-7 |
| simplification-options.md option C | A too-small first result waits until a later session has learned from more data | A draft defers every stream and refuses every session, so the feed waits for a person. Relearns rebind with no record minimum, so C covers first learning only. | W3-1, W3-2 |
| simplification-options.md option E | Prompts in code make support easier | Turns store only a question summary, and earlier questions are re-rendered from current text. Without a recorded prompt version, nobody can tell what the model saw. | W2-5 |
| [parser-drift-not-detected.md](learning-flow-parser-drift-not-detected.md) fix 1 | A contained change to `Stage.stream`, counting one line per record | No input count, capped diagnostics, characters versus records, the full pass, and an unbounded relearn loop after `Kept`. M to L. | W4-1 to W4-5, W4-7 |
| [missing-year-hard-coded.md](learning-flow-missing-year-hard-coded.md) | Use `stroom:format-date`; the time check depends on the drift fix | Banned by A147. The prompt itself demands "a trailing Z". The worker has no stream metadata, so `format-date` uses the current year. The serving record checks can already defer, so the time check doesn't need task 2. | S8, W8-1 to W8-3, W8-6 |
| [single-target-schema.md](learning-flow-single-target-schema.md) | Lists the event-logging literals | Misses a third of the wording (Java literals no template override reaches), ADMIT, `Data` counted as untyped, "a record is a child of the root" in five classes, the demo pipeline's own schema setting, and test fixtures with only the event-logging XSD | S1, W1-1 to W1-7 |
| [remove-claim-tasks-on-worker-requirement.md](smart-pipelines/remove-claim-tasks-on-worker-requirement.md) | Safe to remove | Every point of its argument holds. No Shapeshifter test runs with the master queue, though, and its incorrect reason is repeated in a code comment and two design documents. | W7-6 |
| [supervisor-visibility.md](learning-flow-supervisor-visibility.md) item 5 | "Another node is learning" is wrong when the attempt is parked | It is also wrong while waiting for the model (the commonest case, on a single node too) and while a session is in progress | W7-5 |

### Only the first 64 KiB is judged

`Input.PREFIX_BYTES` is 64 KiB.
Promotion's stream-level checks (compile, coverage, yield, floor, "no worse than the incumbent") run on that prefix, not on the whole held stream as design 04 and ruling A14 say (S2).
The sample size setting is also capped by it, and by a fixed 6,000-character cut in the prompt (S4).
For one large XML or JSON document the prefix doesn't parse, so no record boundary is learned (W5-4).
"Too few records" for a provisional binding also means too few in the prefix (W3).

### Keyed on names, not structure

- Parser roles are decided by name: `"DSParser".equals` picks the rules, and every served record is judged as `XSLTFilter` output (S7, W5-6).
- The default element list offers `XMLParser`, which has no runner, and not `XMLFragmentParser`, which has one. The prompt describes the JSON parser's output wrongly (S6, W5-7).
- A parser run through the real pipeline element silently loses the input coverage check and the corpus record text (W5-3).
- Output records are assumed to be children of the root in five classes (S1, W1-5).
- Record boundaries are an implicit element-or-array choice with no `switch`, so a third kind falls back silently to the root's children (W5-1).
- Serving splits by depth, which helps the mixed-records fix (W5-2), but the depth is fixed when learned, so structural drift is served wrongly *(inferred)* (W5-8).

### Several nodes at once

Cache invalidation events are fire-and-forget, and `expireAfterAccess` never expires a busy learner's entry (W7-1).
A node that hasn't heard of a promotion can open an unrequested, paid session, or append a second live binding; nothing in the table prevents it (W7-2).
`LearnerBindingsDao.replace` is last-writer-wins, despite its comment (W7-3).
First-learning deferrals written after a wake wait for the 5-minute sweep (W7-4), and `bind` spans several transactions (W7-7).
No test has two caches.

### Model cost and weaker models

- Usage-less providers and failed calls count as zero tokens, so budgets and the planned spend breaker undercount (S10, W6-3).
- A token budget of 0 means "unlimited" in the client and "none" on the server (S11).
- The whole transcript is resent on every call, and the model document's context-window setting is never read (W6-1).
- The 60-second default run budget fits a fast cloud model only (W6-2).
- Truncated replies aren't detected (W6-4), and the reply grammars refuse trailing punctuation and lead-in prose (S12, W6-5).
- The live test harness builds its own model client with different settings, so calibrating with it would not measure production (W6-6).

### Smaller correctness bugs found on the way

| ID | Bug |
|---|---|
| S3 | The learning worker reads every stream as UTF-8; serving honours the feed's encoding |
| S5 | `heldOutFraction` is not applied to XML or JSON, though the editor says it is |
| S13 | The corpus stream type isn't in the configured stream types *(a feed-only processor filter might pick corpus streams up: unverified)* |
| W1-2 | `asDocument` wraps by the substring `"<Events"`, so a prefixed root is wrapped twice |
| W3-4 | A stale comment says provisional bindings record nothing in the regression set |
| W4-6 | The output score attribute is written before the stream is served |

## Effort, by walkthrough

| Walkthrough | Effort | Notes |
|---|---|---|
| W1: another target schema | M; L with D2 option B or a generic ADMIT | About 12 production files, 2 client screens and a new XSD fixture |
| W2: prompts in code (E) | S, plus S for a prompt-version record | |
| W2: no flow language (B) | L | Server saving about 1,500 lines, not "a large part of `Conversation`" |
| W3: no provisional bindings (C) | M | About 20 files, mostly deletions; the behaviour and migration are the work |
| W4: coverage while serving (task 2) | M to L | |
| W5: children-of boundary (task 4) | M, plus S for large-document sampling | Serving already splits by depth |
| W5: a new parser type | L | |
| W6: another OpenAI-compatible model | S | Works today |
| W6: a weaker or self-hosted model | M | |
| W6: a non-OpenAI protocol | M | Shared with Ask Stroom; needs a dependency decision |
| W7: remove the `claimTasksOnWorker` requirement (task 5) | S | Plus a test-cluster trial |
| W7: the waiting reason (task 6, item 5) | S | |
| W7: sound multi-node learning | M | Not yet in the plan |
| W8: prompt fix (task 3 fix 1) | S, once A147 is reconciled | |
| W8: task 3 fixes 1 to 3 | M | |
| W8: a time-zone setting (fix 4) | M | Coupled to D1 and the Settings tab |

## New decisions for stage 0b

| # | Question | Findings | Suggested answer |
|---|---|---|---|
| N1 | Which Stroom XSLT extension functions may a learned stylesheet use? | S8, W8-1 | An allow-list of pure date and string functions (including `format-date`), applied by the static check and the test harness alike. Everything else refused, as A147 intends. |
| N2 | Are promotion and learning judged on the whole stream or on the 64 KiB prefix, and how is a large single document sampled? | S2, S4, W5-4, W5-5 | Judge coverage on the whole held stream (the full pass already reads it). Sample large documents by cutting at the record boundary. One sample-size setting that the prompt honours. |
| N3 | Which fixed recipe, if option B goes ahead, and how does it fit the unknown-models step sizes? | W2-1, W2-6, W2-7 | Keep SPLIT and TARGET (close to `ESCALATING`). Record the `TestLiveScenarios` comparison before choosing. Treat step size as a code setting rather than editable flows. |
| N4 | Under option C, what happens to later streams behind a too-small draft, and are relearns in scope? | W3-1, W3-2 | Decide explicitly. Either a person must act (today's behaviour, and simpler), or a later session may replace a too-small draft. |
| N5 | Edit the single unreleased migration in place, or add migrations? | W3-5 | Edit in place until the first release, and reset dev and demo databases, since nothing is on `master`. Record the rule. |
| N6 | Where does a feed's time zone live: the document, the feed, or a receipt header? | S9, W8-4 | Decide alongside D1. |
| N7 | How is spend counted when the provider reports no usage, or a call fails? | S10, W6-3 | Estimate from characters, and charge failed calls, before the spend breaker is built. |
| N8 | Must several nodes learning at once be sound for the first release? | W7-1 to W7-4, W7-7 | Yes, if any multi-node installation will run it. Add a work item. |
| N9 | Under option E, record the prompt version (or rendered text) per turn? | W2-5 | Yes: one column. |

## Changes to the delivery plan

These are reflected in [learning-flow-delivery-plan.md](learning-flow-delivery-plan.md):

- Stage 0b gains decisions N1 to N9.
- Track 1 task 2 is re-scoped (M to L) and depends on N2.
- Task 3 depends on N1, needs a `stroom-app` test, and no longer has to wait for task 2.
- Task 4 depends on N2 for real-size data.
- Task 5 gains a master-queue scenario test.
- Task 6 item 5 covers three states.
- Task 7 grows.
- Two new tasks: sound multi-node learning (W7), and a batch of small fixes.
- Track 2's server removals take the corrected scope for options B, C and E.
- The spend breaker needs N7 first.
- Calibration needs one model client shared by the harness and the node (W6-6).

## How the audit was done

The audit followed stage 0a of the delivery plan: a sweep for hard-coded assumptions, and eight "what would it take to…?" walkthroughs (W1 to W8).
Four reviewers (Claude, read-only) each took a share, checked `stroom-learning-flow/stroom-learning-flow/ISSUES.md`, the design rulings and the server-side inventory's boundary findings first, and cited them where a finding was already known.
Each finding gives `file:line` evidence.
Where a finding was spot-checked against the code while combining the reports (S2, S8 and W8-2, W3-3, W7-1), it held.

The appendices are the reviewers' reports, lightly edited.
Line numbers were correct on 17 November 2026 and will drift; class and method names are given as well.

| Appendix | Covers |
|---|---|
| A | Sweep for hard-coded assumptions (S1 to S13) |
| B | W1 (target schema), W2 (prompts in code, no flow language), W6 (other models) |
| C | W3 (no provisional bindings), W4 (coverage while serving), W7 (several nodes) |
| D | W5 (parser types and record boundaries), W8 (timestamps and time zones) |


---

## Appendix A: Sweep for hard-coded assumptions

*Read-only sweep of `stroom-learning-flow/**/src/main`, `stroom-core-shared/.../stroom/shapeshifter/**`, with a glance at `stroom-core-client/.../stroom/shapeshifter/client`. Branch `ai_translations_server`, 17 November 2026.*

Paths below are relative to `stroom-learning-flow/stroom-learning-flow/src/main/java/stroom/shapeshifter/ai/` unless they start with a module name.
"Shared" means `stroom-core-shared/src/main/java/stroom/shapeshifter/shared/`; "client" means `stroom-core-client/src/main/java/stroom/shapeshifter/client/`.

The `event-logging:3` literals already listed in `docs/learning-flow-single-target-schema.md` are not repeated here.
S1 lists only what that document misses.

### Variables checked

| Variable | Where it is set | Verdict |
|---|---|---|
| Target schema | `SchemaConformanceParameters.schemaGroup` | Contradicted: known (single-target doc), and further gaps in S1 |
| Parser types / chain elements | `FlowStep.elements`, `DEFAULT_ELEMENTS` | Contradicted: S6, S7 |
| Record boundary | `RecordBoundary` (element / array / depth) | Mixed-record shape known (mixed-record-elements doc); output-side shape in S1 |
| Time zone | No setting exists | S9 (mostly known) |
| Character encoding | Feed encoding (Stroom), via `InputSource.getEncoding()` | Honoured only when serving: S3 |
| Stream size | Implicit | S2 |
| Sample size, held-out fraction | `sampleSizeLimit`, `heldOutFraction` | S4, S5 |
| Model / provider | `model` (OpenAI-compatible, A13) | S10, S12 |
| Token budget | `tokenBudget` | S11 |
| Stroom XSLT function library | Implicit | S8 |
| Promotion mode | `promotionMode` | Configurable in practice: read on every binding path (`stage/Stage.java:554, 560, 1555, 1558, 1612`) |
| Learning mode | `learningMode` | Configurable in practice (`Stage.java:450, 659, 798, 859, 914, 1044`) |
| Floor, min records, regression cap, max attempts, attempt budget, instructions, flow, scorers | Document | Configurable in practice (consumers in `Stage`, `Conversation`, `QuestionText`) |
| `relearnThreshold`, `maxDeferralAgeMs` | Document | Read only by the Supervisor (`doc/SupervisorResourceImpl.java:262, 268, 290`), as A93 and A79 intend |
| `regressionRetentionDays` | Document | Read by nothing: known, ISSUES 24 |
| `ERROR_LOAD`, `EVENT_CLASSIFICATION` parameters | Document | No scorer: known, ISSUES 20 |
| `ShapeshifterAiConfig` (5 properties) | `appConfig.shapeshifterAi` | All read: `DeferredLearning.java:92`, `StageFactory.java:215`, `AttemptRetention.java:89`, the two caches |
| Node count | Implicit | Nothing new beyond A102 / ISSUES 3 and 11; `Stage` is created per use (`StageFactory.create()`), so no per-node stale state found |
| Feed / stream type | Implicit | No `Raw Events`/`Events` literals in production code; one side-effect in S13 |

### Findings

#### S1. The single-target-schema inventory is incomplete: "a record is a child of the output root" and event wording elsewhere

**Evidence**

- A record of the output is assumed to be a child of the output's root element, in five places:
  - `scoring/OutputRecords.java:37-41, 106-110`
  - `scoring/Records.java:34, 93`, which counts at depth 2
  - `scoring/YieldScorer.java:33-36`
  - `scoring/SchemaConformanceScorer.java:129-130`: `SplitFilter` with `setSplitCount(1)` and the default depth
  - `fragment/RecordGate.java:30-33`: the serving bar, "A record is one child of the output's root"
- Event wording that the single-target doc does not list:
  - `learning/Templates.java:52, 58`: SYSTEM and CHAIN ("events")
  - `learning/Templates.java:90`: SPLIT_XML ("one event each")
  - `learning/Templates.java:114, 121`: TARGET ("Write the single `<Event>`")
  - `learning/QuestionText.java:94, 101`: the scorers' demands ("Events are scored…", "an XPath over the Event")
  - `learning/QuestionText.java:236, 250, 255`: "Produce one event per…"
  - `learning/TargetChecks.java:226`: `contains("<Events")` decides whether to wrap
  - `learning/TargetChecks.java:257-259`: the refusal text, "Reply with the Event element alone… Events wrapper"

**Assumption and what breaks**

A target schema whose record element is not a direct child of the root cannot be scored, gated or served correctly by any of the five classes. For example, a schema with a header element, or with `Root/Records/Record`.
Examples are a header-plus-body schema, or `records:2` itself used as a target.
A proposed target whose own root happens to be named `Events…` would not be wrapped.
D2 option B ("generate root element, record element…") would generate a record element that the code then ignores.

**Severity:** changes an estimate. Task 7's scope grows from prompts and checks to the scoring and serving record model.

**Affects:** Track 1 task 7; decisions D1 and D2; walkthrough W1.

**Already known?** Partly. The single-target doc lists `EVENTS_OPEN`, `TRANSFORMATION_RULES`, the admission template, `QuestionText.ELEMENTS`, `Admission`, `Corpus` and `ExtractionQualityScorer`. It does not list the items above.

**Test:** score and serve a stylesheet whose output is `<Root><Header/><Records><Record/>…</Records></Root>` against an XSD for it. Check that `Records.parsed`, `OutputRecords.records()`, `RecordGate` and `SchemaConformanceScorer` each count N records, not 2.

#### S2. Promotion is judged on the first 64 KiB, not on "the whole held stream"

**Evidence**

- `stage/Input.java:45`: `PREFIX_BYTES = 64 * 1024`.
- Both readers keep only that prefix as `data` for any larger stream:
  - `element/StreamInputs.java:101-106` (worker)
  - `element/Supervision.java:377-384` (task)
- The candidate and the incumbent are judged on `input.data()`:
  - `stage/Stage.java:527, 1450, 1505`: candidate
  - `stage/Stage.java:272, 772, 920, 1058`: incumbent
- Only `fullPass` (`Stage.java:2014`) and serving (`Stage.java:954`) read the whole stream through `input.open()`, and those run only the record bar (`RECORD_SCORERS`, `Stage.java:163`).
- `fragment/PipelineFragmentRunner.java:110, 180` also caps a judged run at 10,000 records.

**Assumption and what breaks**

The stream-level scorers judge only the first 64 KiB of any larger stream: compile, input coverage, yield, the floor, and "no lower than the incumbent".
Design 04 §4.5 ("run over the whole held stream") and 06 §6.5 (A14) say otherwise.
A candidate that drops lines past 64 KiB passes coverage.

**Severity:** changes an estimate. W4 (judging coverage while serving) has to add whole-stream coverage to the serve path, which has none. Option i ("judge with the same code that serves") inherits this gap.

**Affects:** Track 1 task 2 (W4); simplification option i.

**Already known?** Partly. Design 03 §3.2 documents the 64 KiB prefix for the task decision. Nothing records that the promotion judgement uses it, and ISSUES 10 notes only that a marked learner relearns on a prefix.

**Test:** a scenario with a stream larger than 64 KiB whose lines after 64 KiB have a second format. Assert that coverage falls below 1.0 and promotion is refused. It passes today.

#### S3. The worker reads every stream as UTF-8, whatever the feed's encoding

**Evidence**

- `element/StreamInputs.java:101-103` decodes the prefix with `StandardCharsets.UTF_8` unconditionally.
- Its opener hands raw bytes to `fragment/PipelineFragmentRunner.java:329-330`, which runs them as UTF-8.
- The task path honours the source encoding: `element/Supervision.java:371-397` reads a non-UTF-8 stream whole, through `Charset.forName(encoding)`.

**Assumption and what breaks**

Learning happens in the worker (A60), so a feed in UTF-16 or windows-1252 is learned from wrongly decoded text, and its promotion and full pass are judged on it. The same stream is then served correctly decoded.
UTF-16 data would be unreadable to the parser in the worker. Non-ASCII characters in single-byte encodings would differ between learning and serving.

**Severity:** minor for the plan; a correctness bug for any non-UTF-8 feed.

**Affects:** none directly; worth adding to W5 and W7.

**Already known?** No. Design 03 §3.2 describes only the task path.

**Test:** `StreamInputs.byId` on a stream whose feed encoding is UTF-16LE returns the same `data` as `Supervision.streamed` does for it.

#### S4. The sample size limit is overridden by two fixed caps, and is in characters, not bytes

**Evidence**

- `learning/QuestionText.java:69, 370-372` cuts every sample shown to the model at `INPUT_SHOWN = 6000` characters. The default `sampleSizeLimit` is 8,192 (shared `ShapeshifterAiDoc.java:105`).
- `Stage.java:2112-2150` compares the limit with `String.length()` (characters). The editor labels it "Sample size limit (bytes)" and calls it "the largest sample of the stream shown to the model" (client `component/LearnTab.ui.xml:88-90`).
- The editor allows up to `Integer.MAX_VALUE` (client `component/LearnTab.java:147-148`). Anything over 64 KiB is silently capped by `Input.PREFIX_BYTES` (S2).

**Assumption and what breaks**

With the defaults, the model never sees the last quarter of its own sample, but the parser's split is still judged on it.
Raising the limit to give a strong model more context has no effect above 6,000 characters.
The fixed 6,000 is also blind to the model's context window.

**Severity:** changes an estimate. The setting has to become one number that `QuestionText` honours, or be removed.

**Affects:** W6 (unknown or weaker models); option E (prompts in code), which should take the cap with it.

**Already known?** No.

**Test:** a document with `sampleSizeLimit` 20,000 and a 15,000-character text sample. The rendered CHAIN question contains the whole sample with no "(cut after…)" note.

#### S5. `heldOutFraction` is not applied to XML or JSON streams

**Evidence**

- `Stage.java:2116-2127`: markup and JSON documents within the limit are learned whole. Only text is cut by `heldOutFraction` (`Stage.java:2133`).
- The editor says "The share of a stream's records withheld from the model and used only to judge promotion" (client `component/ScoreTab.ui.xml:32`).

**Assumption and what breaks**

For XML and JSON feeds up to the sample limit, A14 ("judged on records the model never saw") does not hold. Promotion is judged on exactly what the model was shown.

**Severity:** minor.

**Affects:** simplification option C (provisional bindings) and the review-evidence work, which lean on held-out judgement.

**Already known?** Partly. Design 05 §5.6 documents it for text only. The rulings table (A14) and the UI help text do not mention it.

**Test:** `Stage.learningPrefix` on a 50-record JSON array under the limit with `heldOutFraction` 0.2 returns fewer than 50 records, or the help text says "text only".

#### S6. The default chain offers `XMLParser`, which has no runner, and never offers `XMLFragmentParser`, which has one

**Evidence**

- The default elements are `DSParser, JSONParser, XMLParser, XSLTFilter` (shared `FlowStep.java:51`). The editor's help text repeats the list (client `component/FlowStepEditor.ui.xml:42`).
- The runners are `DSParser, JSONParser, XMLFragmentParser, XSLTFilter` (`element/StageFactory.java:181-186`).
- `learning/Conversation.java:258-260` silently drops `XMLParser`.
- `learning/QuestionText.java:61-67` describes all five elements. It says JSONParser "parses JSON into records:2 XML" (line 63), but `extraction/JsonStep.java:46` and the SPLIT_JSON template (`Templates.java:101-103`) say the output is the `http://www.w3.org/2013/XSL/json` vocabulary.
- `Templates.java:242` TRANSFORMATION_RULES tells every stylesheet "reads records:2". `QuestionText.java:358-364` patches this for non-records input by testing whether the input text contains `records:2`.

**Assumption and what breaks**

A document "allows" `XMLParser`, but it is never offered. A feed of XML fragments, one per line, can only be learned by a flow that names `XMLFragmentParser` explicitly.
The model gets contradicting statements about the JSON parser's output.
The input vocabulary is assumed to be `records:2`, and is corrected by a substring heuristic.

**Severity:** changes an estimate.

**Affects:** Track 1 task 4 and W5 (a new parser type or record boundary); option B (fixed recipe), which must pick the element list.

**Already known?** No.

**Test:** with the default flow, the chain question's element list equals the set of installed runners. `QuestionText.ELEMENTS` for `JSONParser` matches `JsonStep`'s output namespace.

#### S7. Element roles are decided by element name, and a chain need not end in a transform

**Evidence**

- `learning/QuestionText.java:194`: `extraction = "DSParser".equals(elementType)` chooses EXTRACTION_RULES over TRANSFORMATION_RULES. Any other configurable parser would be given the XSLT rules.
- `Stage.java:158, 2035`: the `DSParser` boundary is fixed as `record` at depth 1.
- `Stage.java:161, 952, 2012`: every served record is judged as if `XSLTFilter` produced it, with `parser=false`.
- `Conversation.java:466-480`: the chain step accepts any sequence of allowed elements, including a parser alone. `OutputRecords.ofTransformed` (`scoring/OutputRecords.java:73-77`) skips the meaning scorers on parser output.

**Assumption and what breaks**

A new parser type needs edits in `QuestionText` and `Stage`, not only a new `StepRunner`.

*Speculative, not run:* a parser-only chain would pass learning, with meaning scorers not applied, and then fail every served record against the schema, looping through relearning.

**Severity:** changes an estimate.

**Affects:** Track 1 task 4 and W5; option B.

**Already known?** No.

**Test:** a chain reply of just `JSONParser` is refused, or judged with the meaning scorers. A `StepRunner` stub for a third configurable parser gets EXTRACTION_RULES.

#### S8. The Stroom XSLT function library: the task 3 prompt fix conflicts with A147

**Evidence**

- `docs/learning-flow-missing-year-hard-coded.md` (why-it-happens item 3; Track 1 task 3 fix 1) proposes telling the model to use `stroom:format-date`.
- A147 (`design/09-rulings.md` §9.5) and ISSUES 21 rule that every candidate that "calls any extension function" is refused. Track 2 item 3 builds that check.
- The Tier 1 stand-in already refuses all extension functions: `transformation/XsltStep.java:50-56, 160`, `ALLOW_EXTERNAL_FUNCTIONS false`.
- On a node, candidates run in the real `XSLTFilter` with the full library (ISSUES 21).

**Assumption and what breaks**

There is an implicit "which functions may a stylesheet use" setting with three different answers: the node allows all, the harness allows none, and the plan allows none (A147).
Task 3's fix would produce stylesheets that the planned static check refuses and that the scenario harness already fails.

**Severity:** blocks a planned change. Track 1 task 3 fix 1 and Track 2 item 3 cannot both ship as written. Needs a decision, such as an allow-list of pure `stroom:` date and string functions.

**Affects:** Track 1 task 3; Track 2 item 3; A147.

**Already known?** The node/harness divergence is known (ISSUES 21, A147). The conflict with task 3 is not recorded.

**Test:** a candidate using `stroom:format-date` is accepted by the static check and by the Tier 1 harness, and one using `stroom:http-call` is refused by both.

#### S9. Time zone and receipt headers: no setting, and the model is told to write UTC

**Evidence**

- `Templates.java:252`: "datetimes are ISO 8601 with milliseconds and a trailing Z".
- No time-zone field exists on the document, the parameters or the config. No production code uses `ZoneId` or `ZoneOffset`; the only UTC use is `Clock.systemUTC()` for epoch milliseconds.
- `Input.attributes` (the receipt headers) is carried but never read; its only use is a copy at `element/Supervision.java:382`. `learning/Sample.java:43, 58-66` shows only Feed and Type.
- TRANSFORMATION_RULES nonetheless requires EventSource/System Name and Environment (`Templates.java:245-246`), which feeds usually carry as receipt headers.

**Assumption and what breaks**

Local times are written as UTC. The model cannot read a feed-level `TimeZone`, `System` or `Environment` header even when the sender supplies one.

**Severity:** changes an estimate. W8 needs a new setting, and where it lives (document, feed or header) is an undecided question like D1.

**Affects:** Track 1 task 3; W8.

**Already known?** Mostly. The missing-year doc causes 1 and 2 cover `Sample.SHOWN` and the missing time-zone rule. New here: no time-zone variable exists at all, and `Input.attributes` is dead data.

**Test:** a stream with header `TimeZone: Europe/London` produces a TARGET question that names that zone.

#### S10. Token budget and spend assume the provider reports usage

**Evidence**

- `element/ModelAdvisor.java:98-101` counts 0 tokens when `tokenUsage()` or `totalTokenCount()` is null.
- `learning/Conversation.java:1272-1279`: the budget then never trips.
- `Stage.java:1686-1687`: spend records 0.

**Assumption and what breaks**

Against a provider or proxy that omits `usage`, which some OpenAI-compatible local servers do, the token budget is silently unlimited and the spend counter reads zero. The planned spend breaker would then never open.

**Severity:** changes an estimate. The spend breaker (Track 2 item 3) needs a fallback, such as estimating from characters or refusing models with no usage.

**Affects:** W6 (the unknown-models proposal); Track 2 item 3; simplification §5.

**Already known?** No.

**Test:** a `ChatModel` stub returning no `tokenUsage`, with a document `tokenBudget` of 1. The session parks, or the attempt says usage is unknown.

#### S11. "Zero means unlimited" for the token budget holds only in the client

**Evidence**

- Client `component/LearnTab.java:412-418` maps 0 to null. Client `component/GrantText.java:40` treats 0 as unlimited.
- The server (`Conversation.java:1272-1274`) treats a stored 0 as a budget of 0 tokens.

**Assumption and what breaks**

A document written with `tokenBudget: 0` by import, REST or MCP parks at its first answer.

**Severity:** minor.

**Affects:** Track 2 (the Settings tab rework, which will rewrite this field).

**Already known?** No.

**Test:** the serialiser or constructor normalises 0 to null, or `Budget.check` treats 0 as unlimited.

#### S12. The reply grammar assumes a terse, instruction-following model

**Evidence**

- `learning/ChainReply.java:37-50` refuses a reply that has any token other than element names.
- `Conversation.java:509-512` (XML split) and `581-588` (JSON split) require a bare name.
- `learning/ConfigurationReply.java` takes an unfenced reply starting with `<` as the document.

**Assumption and what breaks**

*Speculative, not run against such a model:* a model that adds prose or emits `<think>…</think>` would spend candidates on refusals. An unfenced think-block reply would be compiled as configuration.

**Severity:** minor.

**Affects:** W6 (the unknown-models proposal).

**Already known?** No.

**Test:** reply parsers given "Chain: DSParser -> XSLTFilter" and a `<think>` preamble, with the expected outcome stated.

#### S13. Corpus streams use a stream type that is not in `metaTypes`

**Evidence**

- `element/StoredRegressionSet.java:63, 154` writes `Shapeshifter Corpus` streams onto the source feed.
- That type is not in the default `metaTypes` (`stroom-config/.../expected.yaml:395-406`). `MetaTypeDaoImpl.load` creates it on demand, so the write succeeds.
- `MetaServiceImpl.getTypes()` (`stroom-meta/.../MetaServiceImpl.java:519-521`) returns only the configured list.

**Assumption and what breaks**

The type is missing from type pickers.

*Unverified:* a processor filter on the feed that does not constrain the type would also pick up corpus JSON as input.

**Severity:** minor.

**Affects:** option G (dropping the Pipeline Factory). Its feed-and-type keyed filters avoid this; hand-made filters may not.

**Already known?** A18 places the corpus on the feed. This side-effect is not recorded.

**Test:** a feed-only processor filter does not create a task for a corpus stream.

### Hard-coded but harmless

| Value | Evidence | Note |
|---|---|---|
| Spend window 1 h | `Stage.java:135` | Known, A24 |
| At most 10 settle rounds, 5 evidence records | `Stage.java:1946, 1950` | Behind the budgets (A64) |
| `REPRESENTATIVES = 3` | `TargetChecks.java:54`; `Conversation.java:726` | Duplicates `FlowStep.DEFAULT_KINDS`. The Configure step's "other kinds" ignores a TARGET step's `kinds` |
| Split wholeness 0.9 | `TargetChecks.java:191` | Guard threshold, not presented as a setting |
| 10,000-record capture cap | `PipelineStepRunner.java:75`; `PipelineFragmentRunner.java:110` | Harmless given S2's 64 KiB prefix |
| Feedback caps (20, 5, 3, 700 …) | `PerRecord.java:39`; `InputCoverageScorer.java:42`; `SchemaConformanceScorer.java:78, 84`; `ContentModels.java:54-56` | Message sizes only |
| CHAIN example names `DSParser -> XSLTFilter` | `Templates.java:69-70` | Shown even when the step's elements differ |
| Yield `BYTES` basis counts characters | `YieldScorer.java:72-74` | Only differs for multi-byte text |
| Xerces English message patterns | `SchemaConformanceScorer.java:80-83` | Hints are best effort |
| `Clock.systemUTC()` | `StageFactory.java:214`; `DeferredLearning.java:91`; `Conversation.java:115` | Epoch milliseconds only |
| OpenAI model document only | `ModelAdvisors.java:51` | A13 |
| Data Splitter record is `record` at depth 1 | `Stage.java:2035`; `DataSplitterRunner.java:52` | Fixed by the `records:2` format |
| Stream read is part 0 only | `StreamInputs.java:47-48` | Design 02 §7 |

### Summary

There are 13 findings. One blocks a planned change, eight change an estimate and four are minor.

- **Blocks a planned change (S8).** The Stroom function library has three different answers: the node, the harness and A147. The task 3 prompt fix depends on the library that A147 removes.
- **Change an estimate:**
  - The target-schema work is larger than the single-target doc says, because "record = child of the output root" is built into scoring and serving (S1).
  - Promotion is judged on the 64 KiB prefix rather than the whole stream (S2).
  - The sample size setting is overridden by fixed caps (S4).
  - Parser types are handled by name and the element list is inconsistent (S6, S7).
  - No time-zone variable exists (S9).
  - Token accounting fails silently without provider usage (S10).
- **Minor:**
  - The worker ignores the feed's encoding (S3). This is a real bug for non-UTF-8 feeds.
  - `heldOutFraction` does not apply to XML or JSON (S5).
  - A token budget of 0 means different things in the client and the server (S11).
  - Reply grammar strictness (S12) and the corpus stream type (S13) are minor, and partly speculative where labelled.


---

## Appendix B: Walkthroughs W1, W2 and W6

Branch `ai_translations_server`, read 17 November 2026. Read-only audit; every `file:line` below was read. Paths are abbreviated:

- `ai/` = `stroom-learning-flow/stroom-learning-flow/src/main/java/stroom/shapeshifter/ai/`
- `shared/` = `stroom-core-shared/src/main/java/stroom/shapeshifter/shared/`
- `client/` = `stroom-core-client/src/main/java/stroom/shapeshifter/client/component/`
- `tf/` = `stroom-learning-flow/stroom-learning-flow/src/testFixtures/java/stroom/shapeshifter/ai/`
- `test/` = `stroom-learning-flow/stroom-learning-flow/src/test/java/stroom/shapeshifter/ai/`

Background fact that affects all three: no Shapeshifter code is on `master` (`git log master -- stroom-learning-flow` is empty), and the DB has a single migration `V08_00_00_001__learning_flow.sql`. Stored-document and table "migrations" therefore only matter for dev instances and the demo content pack, not for released installations. This lowers the migration part of every estimate below.

---

### W1: a target schema other than event-logging

#### Files that would change

The list in `docs/learning-flow-single-target-schema.md` is correct as far as it goes (`Templates.TRANSFORMATION_RULES`, admission template, `QuestionText.ELEMENTS`, `TargetChecks.EVENTS_OPEN`, `Admission`, `Corpus`, `ExtractionQualityScorer`, `ExtractionQualityParameters`). It is missing the following:

| Where | What is event-logging-specific |
|---|---|
| `ai/learning/Templates.java:52,58,90,121` | The SYSTEM, CHAIN, SPLIT_XML and TARGET templates say "events", "turn into one event each" and "Write the single `<Event>`". The TARGET question asks for an `<Event>` by name. |
| `ai/learning/QuestionText.java:94,101` | The scorer demands are hard-coded Java, not templates: "Events are scored on…" and "each an XPath over the Event". |
| `ai/learning/QuestionText.java:236,250,253,255` | Boundary and one-record text ("Produce one event per record/item/<x>") is hard-coded Java, not a template. A template override (D2 option A) cannot reach it. |
| `ai/learning/TargetChecks.java:226` | `asDocument` decides whether to wrap by `stripped.contains("<Events")`, a substring test on the root name. It is not namespace-aware, and a prefixed `<evt:Events` gets wrapped twice. |
| `ai/learning/TargetChecks.java:257-259` | The refusal text tells the model "Reply with the Event element alone, without … an Events wrapper". |
| `ai/scoring/Admission.java:36` (`DATA_BUT_MARKER`) used by `ExtractionQualityScorer.java:51,88` | The typed-element ratio counts every element named `Data` as untyped, by local name only. Any other schema that has a legitimate `Data` element gets marked down. |
| `shared/LearnedUnknown.java:36`, `shared/QuestionKind.java:47`, `ai/stage/RegressionSet.java:79` (`Kind.UNKNOWN`), DB `shapeshifter_corpus.kind` | The whole exhaustion step (ADMIT, A51/A53/A62/A74) is defined as `EventDetail/Unknown` plus a `Data` marker. Its kind is persisted per corpus row. |
| `ai/scoring/OutputRecords.java:99-110`, `ai/scoring/Records.java:73-100`, `SchemaConformanceScorer.java:128-129` (`SplitFilter`, depth 2), `TargetChecks.refusal:255` | They assume one output record is one child of the root. Conformance validates each root child re-wrapped in the root. |
| `ai/stage/Stage.java:161` | `TRANSFORM = "XSLTFilter"` is the element type the record scorers judge a served record as. |
| `client/ScorerSettingEditor.java:65,69` and `ScorerSettingEditorController.java:129,147` | The schema group is a free-text box on the conformance scorer, and the `allowUnknownEventDetail` tick box is on the quality scorer. `client/SupervisorController.java:311` says "marked Unknown events". |
| `tf/pack/DemoContentPack.java:302,306` | The demo's AI pipeline has its own downstream `Schema filter` with `schemaGroup=EVENTS` and an `Events` stream appender. That is a third schema setting, outside Shapeshifter. |
| `tf/extraction/NodeFixture.java:70-77,141-144` | The test node loads only the event-logging 3.0.0 XSD, downloaded from the content pack. There is no second schema to test against. |
| `tf/resources/.../scenario/*.events.xml`, `*.xsl` (about 25 files) | Every scenario reference and scripted answer is event-logging. |
| Tests that assert event-logging text | `test/learning/TestQuestionText.java:179-182` and 10 other test files (`TestTargetChecks`, `TestScenariosTarget`, `TestScenariosScoring`, `TestYieldScorer`, `TestMeaningScorers`, `TestContentModels`, `TestPerRecordScoring`, `TestConversation`, `TestSteppingIsADryRun`, `TestImproveABindingThatIsServing`) |

#### Hard-coded values met on the way

- `event-logging:3`, `file://event-logging-v3.0.0.xsd` and `Version="3.0.0"`: `Templates.java:242-244` and `TargetChecks.java:58-61`.
- `"EVENTS"` as the default schema group: `shared/SchemaConformanceParameters.java:38`.
- `EventDetail`, `Unknown`, `Data` and the marker `ShapeshifterUnknown` as local names: `Admission.java:28-37`, `Corpus.java:46` (duplicated rather than reusing `Admission`), `LearnedUnknown.java:27,36`.
- `records:2` as the input namespace: `QuestionText.java:56`. This is a parser-side value and does not need to change.

#### Gotchas

1. D2 option A (a template override) cannot fully retarget the prompts. About a third of the event wording is in Java string literals in `QuestionText` and `TargetChecks`, not in templates. Even today, with overrides available, a non-event-logging target is only partly expressible.
2. The admission design has no counterpart outside event-logging. For another schema, the flow's `on spent goto admit` asks for an `EventDetail/Unknown` that then fails conformance. The ADMIT step has to be switched off, or redefined per schema. The proposal's step 5 mentions the XPaths but not the step or the recipe.
3. "One record is one root child" runs through four classes. A schema whose root needs a header child, or whose records sit deeper, breaks yield, conformance and the target checks together.
4. Tests can't exercise a second schema without a new XSD fixture. `NodeFixture` downloads only the event-logging pack. The scenario references (also the proposed calibration references) are all event-logging.
5. The downstream pipeline's own SchemaFilter (in the demo pack, and presumably in any Content Template or factory pipeline) is a separate setting that the "single setting" proposal doesn't cover.
6. `SchemaConformanceScorer.java:194` caches `ContentModels` by group name for the life of the scorer instance. A schema edited in place isn't re-read. This is minor.

#### Tests that exist or would catch a regression

- These exist and pin today's behaviour: `TestQuestionText.theTransformQuestionCarriesTheSchemasFailureModesAndTheDegeneracyTrap` (asserts `event-logging:3`), `TestTargetChecks`, `TestContentModels` and `TestMeaningScorers`.
- None of them uses a non-EVENTS group. `grep` finds no `SchemaConformanceParameters("…")` other than `"EVENTS"` or null.
- Needed:
  - a fixture with a small second XSD (for example, root `Records`, record `Rec`, no `Data`);
  - a test that a `<Data>` element in that schema isn't counted as untyped;
  - a test that ADMIT isn't offered when the target isn't event-logging;
  - the regression test the proposal already lists (the prompt says event-logging while the scorer validates against another group).

#### Effort

**M** for "identity from one setting, event-logging guidance only when EL, ADMIT and Unknown measures off otherwise" (D2 option A or C). It touches about 12 production files, 2 client screens and a new fixture.

**L** with D2 option B (structure generated from the XSD), or if admission must work for other schemas. Admission is persisted (`corpus.kind`), measured (quality), gated (`Corpus.shortfall`) and prompted (ADMISSION template), so making it generic is a design task, not a substitution.

---

### W2: prompts in code (E) and removing the flow language (B)

#### Files that would change

**E (prompts in code):**

- Server:
  - `Templates.java:283-353`: `of(flow)`, `overrides`, `problems`, `slots`. About 60 lines; the built-in text itself stays.
  - `QuestionText.of(LearningFlow)`: line 120.
  - `ShapeshifterAiResourceImpl.java:54-55` and `shared/ShapeshifterAiResource` (`GET /templates`).
  - `shared/BuiltInTemplates.java` and `shared/Template.java`. `Template` may stay as an engine enum.
  - `LearningFlow.templates` and `builtInVersion`.
- Client: `client/BuiltInText*`, `LearnQuestionText*`, the Learn tab's template panel, and the workbench stories `BuiltInTextDialogOk` and `BuiltInTextDialogCancel` (`ShapeshifterAiEditorStories.java:255,268`).
- Tests: `TestShapeshifterAiSerialiser`, `TestShapeshifterAiStoreImpl` and `TestQuestionText` (template overrides).
- Demo: `DemoContentPack.java:193` (`withBuiltInVersion`).

**B (no flow language):**

- Shared: `LearningFlow` (263), `FlowStep` (467), `FlowPath` (139), `FlowExit` (41), `FlowExample` (76), `FlowProblem` (75), `Transition` (133), `StepGuard` (54). That is about 1,250 lines, plus `Template` and `BuiltInTemplates` (114) from E. The "about 1,400" claim holds.
- `StepOutcome`, `QuestionKind` and `Check` must stay. They are stored in `shapeshifter_turn.outcome` and `question_kind`, used by `Scorecard`, `Attempts` and `AttemptsDao`, and appear in `SupervisorTurn`.
- Server:
  - `Conversation.java:256-465` and `1130-1170`: the interpreter (follow, enter, transitions, Visit), about 250 lines.
  - `Outcome.java` (`FlowExit`).
  - `ShapeshifterAiSerialiser.java:48-140`: the legacy `plan`, `dialogue`, `preset` and `allowedElements` reads.
  - `ShapeshifterAiDoc.java:67,102,145,223,244,292-305,453,512-535`.
- Client (about 3,400 lines in the files that touch it): `FlowChart`, `LearnFlow`, `LearnTab*`, `FlowStepEditor*`, `Transition*`, plus `SupervisorController.java:87,196,348-364` and `Supervisor.java:47,98`.
- Tests:
  - `FlowExample` is referenced in 35 test files: `TARGET_FIRST` 27 times, `DIRECT` 16 and `ESCALATING` 7.
  - `FlowStep.parse` appears in 8 test files.
  - The flow tests: `TestScenariosFlow`, `TestScenario63TheFlowNamesItsExits`, `TestFlowPath`, `TestLearnFlow`, and parts of `TestShapeshifterAiDoc`.

#### Hard-coded values met on the way

- `DEFAULT_FLOW = LearningFlow.of(FlowExample.DIRECT)`: `ShapeshifterAiDoc.java:102`.
- The demo uses `FlowExample.ESCALATING`: `tf/pack/DemoDocument.java:105`. The "Any feed" dev document also uses ESCALATING, per the unknown-models paper §1.
- `SPLIT_CHECKS = {COVERAGE, YIELD, WHOLENESS}`: `Conversation.java:88`. Each kind's default checks live in the engine (ISSUES 8).
- `candidates 2` and `kinds 3` are per-step limits in the examples: `FlowExample.java:51,52`. `FlowStep.DEFAULT_KINDS` is at `Conversation.java:626`.

#### Gotchas

1. **The proposed fixed recipe drops SPLIT, and much depends on SPLIT.** The recipe in simplification-options §B is chain, parser, transform, admit, and its recommendation is "`DIRECT`, the simplest".
   - Without a SPLIT step, `walk.split` is null (`Conversation.java:708-711, 759-761`).
   - So XML and JSON inputs get no record boundary (A35), and the transform is not shown or run one record at a time (§12 item 25).
   - The target questions, `TargetChecks.preservation` and `fidelity` (the A31 machinery) are never used.
   - The demo deliberately runs ESCALATING "for formats nobody has seen yet" (`DemoDocument.java:93-96`).
   - Hard-coding DIRECT is a behaviour change, not a simplification. Hard-coding ESCALATING keeps nearly all of `Conversation`.
2. **The `Conversation` saving is overstated.** Only about 250 of its 1,288 lines interpret flows. The rest is per-question logic (chain, split, target, configure, held-to, judge), which a fixed recipe still needs.
3. **The chain's allowed elements live only in the flow** (A95; `ShapeshifterAiDoc.java:292-298`, "Not a property of the document any more"). Removing the flow means reversing A95: a top-level field again, with the serialiser's `LEGACY_ELEMENTS` path (`ShapeshifterAiSerialiser.java:56,103-121`) turned round.
4. **The Supervisor draws a session's path over the document's flow** (A88, A116; `SupervisorController.java:348-349`, `FlowPath.visits` keyed on `stepId`). With no flow on the document, the client needs the recipe from somewhere: a REST call or a shared constant. Otherwise the chart goes, which is a UI scope decision. `shapeshifter_turn.step_id` stays.
5. **Replay of an open attempt** compares question identity, not step ids (`RecordedAdvisor.java:85-101`, `Question.identity` at `Question.java:73-82`).
   - If the recipe changes under a parked or awaiting attempt, it ends in `ReplayDiverged`.
   - Prompt-text changes (E) don't affect replay.
   - With nothing released, this is minor.
6. **E alone doesn't give "what exactly did the model see?"**
   - Turns store only the question's summary: `Stage.java:1209,1329` writes `question().summary()` (for example "Configuration: XSLTFilter XSLT").
   - `ModelAdvisor.java:91` re-renders every earlier question from the current templates on each call, so after an upgrade a resumed attempt's history is shown to the model in new words.
   - Neither the rendered prompt nor `Templates.VERSION` is recorded per attempt (no column in `shapeshifter_attempt` or `shapeshifter_turn`).
   - E needs a prompt-version column, or stored rendered text, to deliver the support benefit it claims.
7. **The unknown-models paper builds on the flow language** that B removes. Its §5C says "Flows are already data: a document owns its steps… Step size becomes one more property of the document". B and that proposal must be reconciled in stage 0b.
8. Rulings A32-A34, A37, A81, A83, A85, A88, A95 and A116 are superseded. ISSUES 7, 8, 13 and 16 (the Flow-tab and live-run items) go away.
9. Import and export: the doc mapper uses `JsonUtil` (`FAIL_ON_UNKNOWN_PROPERTIES=false`, `JsonUtil.java:193`), so old exports with `flow` still read. The demo pack's writer (`DemoContentPack.java:193`) has to change.

#### Tests that exist or would catch a regression

- The scripted scenarios (53 classes in `test/scenario`) are the regression net. They fix question order by flow, so each one must be moved onto the fixed recipe or deleted.
- About 27 use `TARGET_FIRST`, which the proposed recipe doesn't contain. Their target and preservation coverage would be lost unless TARGET stays in the recipe.
- `TestLiveScenarios` compares DIRECT, TARGET_FIRST and ESCALATING (lines 83-100). It is the only evidence for choosing the recipe, and it hasn't been recorded as run against the proposed one.

#### Effort

- **E: S.** About 60 server lines, a REST path, one client panel, two stories and three tests. Add **S** for a prompt-version or rendered-text column, if the support claim is wanted.
- **B: L.** The shared-type deletion is mechanical. The cost is in:
  - choosing a recipe without losing SPLIT/TARGET behaviour;
  - reinstating `allowedElements`;
  - the Supervisor chart;
  - rewriting or removing about 35 scenario tests;
  - reconciling with the unknown-models step-size proposal.

  The server saving is about 1,500 lines (shared types plus the interpreter), not "a large part" of `Conversation`.

---

### W6: a different model provider, or a weaker model

#### How the model is reached

1. `ModelAdvisors.of` (`ai/element/ModelAdvisors.java:47-58`) calls `AiService.getOpenAIModelDoc(doc.getModel())` and then `aiService.getChatModel(modelDoc)`.
2. `AiServiceImpl.getChatModel` (`stroom-ai/stroom-ai-impl/.../AiServiceImpl.java:411-449`) always builds a langchain4j `OpenAiChatModel`. It sets:
   - `modelName`;
   - `maxRetries` from the document's HTTP config (default 0, `HttpClientConfiguration.java:60`);
   - Stroom's `ApacheHttpClientBuilder`;
   - the `baseUrl` (SSRF-guarded);
   - the API key;
   - `reasoningEffort`, if set.

   A `__stub__` model id returns a `StubChatModel` (line 106, 413-416).
3. `ModelAdvisor.ask` (`ai/element/ModelAdvisor.java:86-122`) sends:
   - one `SystemMessage`: the system text plus supervisor guidance;
   - the whole transcript, each earlier question re-rendered as a `UserMessage` with its reply as an `AiMessage`;
   - the new question.

   It reads `aiMessage().text()` and adds `tokenUsage().totalTokenCount()`, or 0 where that is null.

There is no temperature, max output tokens, response format or JSON mode, seed, tools or streaming. Replies are parsed with strict text grammars: `ChainReply` (`ChainReply.java:38-52`), `ConfigurationReply` (`ConfigurationReply.java:40-58`), the split reply regex `[A-Za-z_][\w.-]*` (`Conversation.java` around line 510), and `none`/`root` keywords.

**The 60-second timeout problem is fixed.** Commit `5e255e593a` (16 November) made `ApacheHttpClientBuilder` ignore langchain4j's `connectTimeout()` and `readTimeout()` calls (`ApacheHttpClientBuilder.java:32-90`), and set `maxRetries` from the document. `TestAiServiceImpl` covers both. The test harness `LiveAdvisor` still uses its own values (below).

#### Files that would change

- **Other OpenAI-compatible endpoint:** none. It is configuration only (`OpenAIModelDoc` base URL, key, model id).
- **Non-OpenAI protocol** (native Anthropic, Bedrock, Vertex, Azure with deployment and api-version):
  - `AiServiceImpl.getChatModel` needs a provider switch.
  - `shared/../openai/shared/OpenAIModelDoc.java` needs a provider field; the type is literally named OpenAI.
  - Both are shared with Ask Stroom, so the change is outside Shapeshifter.
  - langchain4j has those providers, but adding them is a new external dependency (AGENTS.md says to avoid that).
  - Server-inventory §12 already notes the leak: `AiService` returns langchain4j types.
- **Weaker model:**
  - `ModelAdvisor` (transcript trimming, finish reason, think-tag stripping);
  - `ChainReply` and `ConfigurationReply` (tolerance);
  - `QuestionText.INPUT_SHOWN` (6,000 chars, line 70);
  - the document defaults `DEFAULT_ATTEMPT_BUDGET_MS` (60 s) and `DEFAULT_SAMPLE_SIZE_LIMIT` (8,192) in `ShapeshifterAiDoc.java:103,105`;
  - `OpenAIModelDoc.maxContextWindowTokens`, which exists but Shapeshifter never reads (only `AskStroomAIService.java:523` does).

#### Hard-coded values met on the way

- `INPUT_SHOWN = 6000` and `SHORTEST = 1000`: `QuestionText.java:70-72`.
- Attempt time budget of 60 s: `ShapeshifterAiDoc.java:103`.
- `maxAttempts` 5: `ShapeshifterAiDoc.java:97`.
- `LiveAdvisor` timeout 5 min and retries 2: `tf/scenario/LiveAdvisor.java:92-93`.
- `HttpClientConfiguration.DEFAULT_TIMEOUT` of 3 min: `HttpClientConfiguration.java:51`.

#### Gotchas

1. **The transcript grows without bound.** Every call resends every earlier question in full, each with up to 6,000 characters of input, the previous configuration, the targets and the feedback (`ModelAdvisor.java:88-94`).
   - With 5 candidates per step across 3 or 4 steps, a session passes 32k tokens.
   - Nothing reads `maxContextWindowTokens`.
   - Some self-hosted servers silently truncate an over-long prompt rather than erroring; Ollama's default `num_ctx` is known for this, but that is external behaviour, not verified here. Others return 400.
   - Any exception becomes attempt `ERROR`, with no retry and no trimming (`Stage.java:1214-1220`).
2. **The 60 s time budget suits a fast cloud model, not a slow one.**
   - `budget.check` runs before and after every call (`Conversation.java:1032,1049`).
   - A self-hosted model at about 40 s per reply (the paper's mock) gets one or two questions per run.
   - A reply that arrives after the budget is spent is recorded and then the run is abandoned, so tokens are paid for an answer that is never judged.
3. **Token accounting is optimistic.**
   - A provider that omits usage counts as 0 (`ModelAdvisor.java:98-101`), so `tokenBudget` and the planned spend breaker (`Spend.java:27-33`, "zero where it does not") never trip on tokens; only calls are counted.
   - A failed or timed-out call adds nothing (`ModelAdvisor.java:114-118`), though the code's own comment says timed-out calls are usually charged in full (`AiServiceImpl.java:424-425`).
   - The AUDITD-AUSEARCH loss quoted in the unknown-models paper (1.74 M tokens) shows the scale.
4. **A truncated reply isn't recognised.** No code reads `finishReason` (grep is empty). A long XSLT cut off by a server's default output limit shows up as "The reply was not a single XSLT document" or as a compile failure. The model is then asked again with the same limit, and the feedback doesn't say what went wrong.
5. **The grammars are strict for weaker models.**
   - `ChainReply` refuses "DSParser -> XSLTFilter." (the trailing full stop makes an unknown token) and any lead-in prose.
   - Speculative: models that put `<think>…</think>` reasoning in `content` (some vLLM and DeepSeek setups) fail the chain and split grammars, and fail `ConfigurationReply` if the reasoning holds a fenced block (two blocks are refused).
   - Each refusal costs a candidate.
6. **No sampling control in production.** `OpenAIModelDoc` has no temperature, so a weaker model runs at its server's default. Only `LiveAdvisor` can set one (`LiveAdvisor.java:94-96`). `reasoningEffort` is sent as given, and a model that doesn't accept it fails every call with a 400.
7. **Calibration doesn't measure the production path.** `LiveAdvisor` builds its own `OpenAiChatModel`:
   - a 5 min timeout and 2 retries, not the document's HTTP config;
   - no Stroom HTTP client, so no truststore or proxy settings;
   - no `reasoningEffort`;
   - no supervisor guidance in the system message;
   - it unboxes `totalTokenCount()` without a null check (line 116), so it throws on a provider that omits usage where the node wouldn't.

   The unknown-models paper's §5B "calibrate each model" via `TestLiveScenarios` would measure a different client.
8. **The scenario references are event-logging** (W1 gotcha 4). Calibrating for another schema needs new references.

#### Tests that exist or would catch a regression

- These exist:
  - `TestModelAdvisor` (2 tests: message assembly, audited failure);
  - `TestAiServiceImpl` (timeouts and retries from the document, the default config, embeddings);
  - `TestApacheHttpClientBuilder`;
  - `TestConfigurationReply` (6 cases);
  - `TestChainReply`.
- Missing:
  - usage null leading to a budget not counting;
  - a failed call charging nothing;
  - `finishReason=LENGTH`;
  - transcript size versus `maxContextWindowTokens`;
  - think-tag and trailing-punctuation replies;
  - `LiveAdvisor` and `ModelAdvisor` building the same request.

#### Effort

- **S** for another OpenAI-compatible endpoint with a capable model. It works today, and the timeout fix is in.
- **M** for a weaker or self-hosted model to work reliably:
  - transcript trimming against `maxContextWindowTokens`;
  - finish-reason feedback;
  - grammar tolerance;
  - charging failed calls;
  - budget defaults;
  - one advisor shared by `LiveAdvisor` and the node.
- **M** for a non-OpenAI protocol. It is mostly in `stroom-ai` and the model document, shared with Ask Stroom, and needs a dependency decision.

---

### Findings

| ID | Title | Evidence | Assumption, and what breaks | Severity | Plan item or decision | Already known? | Test that would catch it |
|---|---|---|---|---|---|---|---|
| W1-1 | Event wording is in Java literals that no template override can reach | `QuestionText.java:94,101,236,250,253,255`; `TargetChecks.java:257-259`; `Templates.java:52,58,90,121` | Assumes the target is "events". With another schema, the model is still told to write one `<Event>` and to aim rules at "the Event". D2 option A can't fix this even with overrides. | changes an estimate | Track 1 task 7; D2 | Partly. The single-target doc lists `TRANSFORMATION_RULES`, admission and `ELEMENTS` only. | `TestQuestionText`: render every question kind with a non-EL target and assert no `Event`/`Events` wording. |
| W1-2 | `asDocument` wraps by substring `"<Events"` | `TargetChecks.java:226` | Assumes the root is literally `Events` and unprefixed. Another root, or `<evt:Events>`, gets wrapped twice, and the target is judged as a whole document. | minor | Track 1 task 7 | Partly (the doc names `EVENTS_OPEN` only) | `TestTargetChecks`: `asDocument` of a prefixed root and of a non-EL root. |
| W1-3 | The admission (ADMIT) step is event-logging-only by design | `LearnedUnknown.java:36`; `Admission.java:30-37`; `Corpus.java:46,190-193`; `RegressionSet.java:79`; DB `shapeshifter_corpus.kind`; `Templates.java:139-152` | Assumes an `EventDetail/Unknown` branch exists. For another schema, the exhaustion step asks for markup that fails conformance, and `Kind.UNKNOWN` never occurs. The recipe needs a "no ADMIT" path. | blocks a planned change (non-EL target with today's flows) | Track 1 task 7; D2; simplification B (recipe includes Admit) | Partly. The doc mentions the XPaths, not the step or recipe. A51, A53, A62 and A74 define it. | A scenario with a non-EL target whose transform is spent: assert it parks rather than asking for Unknown. |
| W1-4 | Any element named `Data` counts as untyped | `Admission.java:36`; `ExtractionQualityScorer.java:51,88` | Local-name match only. Another schema with a real `Data` element is marked down on quality. | changes an estimate | Track 1 task 7 | No | `TestMeaningScorers` with a non-EL record containing `<Data>`. |
| W1-5 | One record is one root child, everywhere | `OutputRecords.java:99-110`; `Records.java:73-100`; `SchemaConformanceScorer.java:128-129`; `TargetChecks.java:255` | Assumes record-per-root-child output. A schema with a header child, or deeper records, breaks yield, conformance and the target checks together. | changes an estimate | Track 1 tasks 4 and 7 (W5 overlaps) | No (the A35 boundary covers the input side only) | `TestYieldScorer` and the conformance scorer over an output with a non-record header child. |
| W1-6 | The downstream pipeline has its own schema group | `tf/pack/DemoContentPack.java:302,306` | The "single setting" covers Shapeshifter only. The pipeline's SchemaFilter and Events stream type still say EVENTS. | minor | D1 | No | A demo-pack test asserting the pipeline's schema group equals the document's target. |
| W1-7 | No second schema in the test fixtures; references are all event-logging | `NodeFixture.java:70-77,141-144`; `tf/resources/.../scenario/*.events.xml` | Tests and calibration can't exercise a non-EL target without new fixtures. | changes an estimate | Track 1 task 7; unknown-models §5B | Yes, for references (unknown-models paper §5B) | n/a (this is the missing fixture) |
| W2-1 | The proposed fixed recipe drops SPLIT and TARGET | `simplification-options.md` §B; `Conversation.java:708-711,759-761`; `DemoDocument.java:93-105` | "Hard-code DIRECT" loses the XML/JSON record boundary (A35), one-record-at-a-time transforms (§12 item 25) and target/preservation/fidelity (A31). The demo runs ESCALATING. | changes an estimate (and a decision) | Simplification B, decision 2 | No | Run `TestScenario46Json` and `TestScenario49XmlFragments` under the chosen recipe; `TestLiveScenarios` comparison recorded. |
| W2-2 | The `Conversation` saving is overstated | `Conversation.java:256-465,1130-1170` versus 1,288 lines | Only about 250 lines are the flow interpreter. Per-question logic stays. | changes an estimate | Simplification B; §4 "Rough size of the saving" | No | n/a |
| W2-3 | The chain's allowed elements live only in the flow | `ShapeshifterAiDoc.java:292-298`; `ShapeshifterAiSerialiser.java:56,103-121` | Removing the flow removes allowed elements. A95 must be reversed, with a new field and migration. | changes an estimate | Simplification B | A95 (the design, not the dependency) | `TestShapeshifterAiDoc`: `getAllowedElements` survives without a flow. |
| W2-4 | The Supervisor's path chart needs the flow | `SupervisorController.java:87,196,348-364`; `FlowPath.java:47-60` | The chart draws turns over the document's flow by `stepId`. Without one, the server must publish the recipe, or the chart goes. | changes an estimate | Simplification B and D; UI rework | A88 and A116 (design) | `TestFlowPath` against the fixed recipe's step ids. |
| W2-5 | Prompts aren't stored, so E doesn't answer "what did the model see?" | `Stage.java:1209,1329` (summary only); `ModelAdvisor.java:91` (re-render); no version column in the migration (lines 105-163) | Assumes code-only prompts make support easy. Without a recorded prompt version or rendered text, a past turn can't be reproduced after an upgrade. A resumed attempt's history is re-rendered in new words. | changes an estimate | Simplification E, decision 5 | No | A test that a recorded turn carries the prompt version, and `ModelAdvisor` replays history with the recorded version. |
| W2-6 | The unknown-models proposal depends on editable flows | `ai-translation-approach-for-unknown-models.html` §5C ("Flows are already data… step size becomes one more property") versus simplification B | The two papers assume opposite things about flows. | blocks a planned change (until reconciled) | Simplification B; unknown-models decisions; stage 0b | No | n/a (decision) |
| W2-7 | About 35 scenario tests are bound to example flows | 27 × `TARGET_FIRST`, 16 × `DIRECT`, 7 × `ESCALATING` across 35 test files; `FlowStep.parse` in 8 | The regression net must be rewritten for the recipe, or its TARGET coverage is lost. | changes an estimate | Simplification B | Partly. Server-inventory §13 says "assess the tests". | n/a |
| W2-8 | A changed recipe makes open attempts unresumable | `RecordedAdvisor.java:85-101`; `Question.java:73-82` | Replay compares question identity, so a different question order raises `ReplayDiverged`. | minor (nothing released) | Simplification B rollout | A45 (by design) | `TestScenario31APersonAnswersATurn`, with a recipe change between carryings. |
| W6-1 | The transcript grows without limit, and the context window is ignored | `ModelAdvisor.java:88-94`; `QuestionText.java:70`; `OpenAIModelDoc.maxContextWindowTokens` read only by `AskStroomAIService.java:523` | Assumes a large context. A small or self-hosted model overflows within one session: either an error (attempt `ERROR`, `Stage.java:1214-1220`) or silent truncation (server-dependent, not verified). | changes an estimate | Unknown-models proposal (Fine step size for weak models) | No (server-inventory §13 "risk review of the model path" is generic) | A `TestModelAdvisor` case: the message size over a 20-turn transcript stays under `maxContextWindowTokens`. |
| W6-2 | The 60 s default attempt budget doesn't fit slow models | `ShapeshifterAiDoc.java:103`; `Conversation.java:1032,1049,1263-1270` | Assumes fast replies. A 40 s per reply model gets one or two questions per run, and a reply after the budget is paid for and discarded. | changes an estimate | Unknown-models §4 ("Speed") | ISSUES 17 (per-run semantics), not the default | A scenario with a slow `Script` advisor (clock advance) checking the reply is judged or the budget scales. |
| W6-3 | Usage-less providers and failed calls cost nothing | `ModelAdvisor.java:98-102,114-118`; `Spend.java:27-33`; `AiServiceImpl.java:424-425` | Assumes the provider returns usage and that failures are free. `tokenBudget` and the planned spend breaker undercount; the AUDITD-AUSEARCH loss is invisible. | changes an estimate | Simplification §5 (cost ceiling); unknown-models | Partly (`Spend` javadoc says "zero where it does not") | `TestModelAdvisor`: null usage, and a thrown call, each recorded as spend (calls at least). |
| W6-4 | A truncated reply isn't detected | No `finishReason` use in `stroom-learning-flow` or `stroom-ai-impl` | A reply cut by an output limit is reported as a malformed or uncompiled document, and the model is re-asked blind. | minor (changes an estimate for weak models) | Unknown-models proposal | No | `TestModelAdvisor` with `FinishReason.LENGTH`: assert feedback names truncation. |
| W6-5 | The grammars refuse common weak-model habits | `ChainReply.java:21,38-52`; `ConfigurationReply.java:43-58`; split regex in `Conversation.java` (around line 510) | Trailing punctuation, lead-in prose and (speculative) `<think>` blocks are refused, each costing a candidate. | minor | Unknown-models proposal | No | `TestChainReply` "DSParser -> XSLTFilter."; `TestConfigurationReply` with a think block. |
| W6-6 | Calibration measures a different client from production | `tf/scenario/LiveAdvisor.java:85-96,116` versus `ModelAdvisor` and `AiServiceImpl.getChatModel` | Its timeout, retries, HTTP client, `reasoningEffort`, guidance and null handling all differ. Calibration results would not predict node behaviour. | changes an estimate | Unknown-models §5B (calibration) | No | A test that `LiveAdvisor` and `ModelAdvisor` produce the same `ChatRequest` for the same document. |
| W6-7 | Only the OpenAI protocol is supported, and it is shared with Ask Stroom | `AiServiceImpl.java:411-449`; `OpenAIModelDoc` | A different provider protocol is a `stroom-ai` change with a doc-type change and a new dependency. | changes an estimate | Unknown-models proposal | Yes: A13 (by design); server-inventory §12 (langchain4j leak) | n/a |


---

## Appendix C: Walkthroughs W3, W4 and W7

Branch `ai_translations_server`, read-only. Paths are abbreviated:

- `S` = `stroom-learning-flow/stroom-learning-flow/src/main/java/stroom/shapeshifter/ai/`
- `DB` = `stroom-learning-flow/stroom-learning-flow-impl-db/src/main/java/stroom/shapeshifter/ai/impl/db/`
- `SH` = `stroom-core-shared/src/main/java/stroom/shapeshifter/shared/`
- `P` = `stroom-processor/stroom-processor-impl/src/main/java/stroom/processor/impl/`
- `MIG` = `stroom-learning-flow/stroom-learning-flow-impl-db/src/main/resources/stroom/shapeshifter/ai/impl/db/migration/V08_00_00_001__learning_flow.sql`

Everything below was read in the code unless it is marked *speculation*.

---

### W3: removing provisional bindings (simplification option C)

#### How provisional bindings work today

- **Created** in `S/stage/Stage.java:1611` (`bind`): `provisional = judged.records() < doc.getMinRecordsToLearn()`.
  This is set **independently of `draft`** (1612), so in review mode a draft can also be provisional.
  `TestScenario22ReviewMode.approvingAProvisionalDraftStartsItsRegressionSet` (line 373) relies on that.
  `judged` comes from `rerun(..., input.data(), ...)` (1449), and for a streamed input `input.data()` is only the 64 KiB prefix (`S/stage/Input.java:45`). So "too few records" means too few records in the prefix.
- **Promoted while serving** in `Stage.stream` (970–979): only when **one stream** brings `>= minRecordsToLearn` records and `meanTotal >= promotionFloor`. The count is not cumulative.
  A provisional binding never feeds the rolling score: the `else if` at 981 skips it.
  So a feed that sends 4 records a file stays provisional for ever, and has no rolling score (`TestScenariosRetractionRelearningAndRelease.scenario28…`, lines 106–138, asserts exactly this).
- **Promoted by a dry run** (stepping) report: `Stage.dryRun` 776–785.
- **Accept**: `Stage.accept` 1743–1771. REST `SH/SupervisorResource.java:118-123` (`/serving/{docUuid}/{bindingUuid}/accept`), `S/doc/SupervisorResourceImpl.java:382-399`.
  Client: `LearnerList.ui.xml:23`, `LearnerList.java:265,310`, `LearnerListController.java:302-311,394,420,441`.
- **Cleared** by `approve` (1706, 1717) and by the serve-time promotion. It is **not** cleared by a relearn rebind (`relearnUnderClaim` 1577 uses `incumbent.copy()`), so a relearned provisional binding stays provisional.
- **Recorded** in:
  - `Decision.Provisional` (`S/stage/Decision.java:76`)
  - `AttemptStatus.PROVISIONAL` (`SH/AttemptStatus.java:40`), written by `statusOf` (`Stage.java:1358`) and read in the `BOUND` lists (`DB/AttemptsDao.java:81-82`, `S/state/InMemoryAttempts.java:45-46`)
  - `Produced.provisional` and the output stream's meta attribute `ShapeshifterAiProvisional` (`S/stage/Produced.java:63,93`), written before serving by `Supervision.record` (`S/element/Supervision.java:544-556`)
  - `shapeshifter_binding.provisional` (MIG:69) and `shapeshifter_output.provisional` (MIG:177)
  - `DB/LearnerBindingsDao.java:102,149,172`, `DB/OutputsDao.java:112,130,210,235` and `DB/LearningPipelinesDao.java:162,269`
  - in memory: `S/state/InMemoryLearningPipelines.java:124`
  - DTOs: `SH/SupervisorLearner.java:50-161` and `SH/ShapeshifterAiStepDetails.java:59-168`
  - step text: `S/element/StepDetails.java:78,124-126`; `bindingOf` switches on it in `Supervision.java:359` and `Stage.settle` 1975
  - client: `StageDecisionPaneController.java:102`, the help text in `ScoreTab.ui.xml:38`, `SupervisorStories.java:74,79`
  - Javadoc: `SH/ShapeshifterAiDoc.java:188` and `SH/Binding.java:38`

#### Interactions

- **Deferral and wake.** `bind` wakes the pipeline for a provisional binding exactly as for a promotion (1639).
  Under option C the binding would be a draft, and `decide` (842–846) defers **every** later stream with "Awaiting review". `whyNotToCarryOn` (651) and `session` (1036) refuse to start any session behind a draft.
  So the option's text "or it waits until a later session has learned from more data" is not what the code would do: nothing learns until a person approves or rejects. Supporting it needs new code, for example letting a session replace a too-few-records draft. See W3-1.
- **Binding cache.** Every `replace` fires a cluster event (`S/cache/CachedLearnerBindings.java:113-118`). The serve-time promotion in `Stage.stream` is the only place a **serving task** writes a binding. Removing it takes the hot path out of `shapeshifter_binding` writes and removes one of the lost-update writers in W7-3.
- **Corpus and improve.** `accept`'s comment (1754–1761) says a provisional binding "deliberately puts nothing in the regression set". But `bind` adds the entries for provisional bindings too (1628–1632). The comment is stale (W3-4).
- **`minRecordsToLearn` has three jobs:**
  - the provisional cut-off (1611, 973)
  - the relearn-on-mark gate (921)
  - the rolling-score memory (`states.scored(..., doc.getMinRecordsToLearn())`, 986; `DB/LearnerStatesDao.java:68-72`, parameter `memory`)

  Removing provisional bindings leaves the setting in place, but its help text and Javadoc change.
- **Relearns are not covered.** A relearn rebinds in place in automatic mode with no record minimum (`relearnUnderClaim` 1577–1590). Option C's "never approved automatically on too few records" would therefore cover first learning only (W3-2).

#### Stored state and migration

- The `provisional` columns in `shapeshifter_binding` and `shapeshifter_output`, plus attempt rows with status `PROVISIONAL`.
- `DB/AttemptsDao.java:572` parses status with `AttemptStatus.valueOf`. Removing the enum constant without a data update makes every old `PROVISIONAL` attempt row unreadable (W3-3).
- `ISSUES.md` #18: the module has a single, unreleased migration (`V08_00_00_001`).
  Editing it in place is possible for an unreleased 8.0, but every dev or demo database that has already applied it fails Flyway's checksum (W3-5).
  The alternative is a new `V08_00_00_002` that maps provisional bindings to something (promoted, or draft plus `awaitReview` state) and then drops the columns. After that, run `generateJooq`.
- The output stream attribute `ShapeshifterAiProvisional` is on already-written streams. Removing it is a visible change to stream attributes. `TestScenario18LearnsACsvFeedInAPipeline:186` and `TestScenario51…InAPipeline:241` assert it.

#### Hard-coded values met

| Value | Where |
|---|---|
| `DEFAULT_MIN_RECORDS_TO_LEARN = 10` | `SH/ShapeshifterAiDoc.java:108` |
| Prefix of 64 KiB | `Input.java:45` |
| Rolling-score memory = `minRecordsToLearn` | `Stage.java:986` |

#### Tests

These would change or be deleted:

- `TestScenariosBindingBeforeLearning.scenario14TooFewRecordsBindsProvisionallyThenPromotesWhenEnoughArrive` (141)
- `TestScenariosRetractionRelearningAndRelease.scenario28…` (106)
- `TestRetractingABindingByHand.aProvisionalBindingCanBeAcceptedRatherThanWaitedOut` (197)
- `TestScenario22ReviewMode.approvingAProvisionalDraft…` (373)
- `TestSteppingIsADryRun`, `TestStepDetails`, `TestLearnerBindingsDao`, `TestLearnerStatesAndAttemptsDao`
- the two stroom-app attribute assertions above
- the Supervisor workbench stories

A new test is needed: on an automatic document, a first stream with fewer than `minRecordsToLearn` records yields `Drafted`, and the next stream defers with "Awaiting review".

#### Effort: **M**

The code removal is about 20 production files across server, shared, DB and client, mostly deletions. The extra work is behavioural:

- what a draft that is too small does to later streams (W3-1)
- whether relearns are in scope (W3-2)
- migrating existing rows and the enum (W3-3, W3-5)
- the Accept endpoint, which the REST contract and the UI phase 1 hiding both need to agree on

---

### W4: judging input coverage while serving (parser-drift fix 1)

#### Where serving happens

1. `Supervision.decideAndStream` (`S/element/Supervision.java:322-352`):
   - calls `stage.decide`
   - writes the output stream attributes via `record(produced, …)`, **before** the stream is served (339–341)
   - calls `stage.stream`
   - logs the diagnostics
   - logs "produced nothing" when `served.records() == 0` (347)

   It runs **per part** of a multi-part stream (`record` 544–556).
2. `Stage.stream` (`Stage.java:946-990`) builds the record bar from `RECORD_SCORERS` = schema conformance, extraction quality and business rules (163–164, 994–999), then calls `fragmentRunner.serve`.
3. `PipelineFragmentRunner.serve` (`S/fragment/PipelineFragmentRunner.java:278-366`) runs the real pipeline over the streamed `InputStream`:
   - `RecordGate` (`S/fragment/RecordGate.java`) counts and judges **output** records
   - `RecordTap` holds the parser's current record
   - a `LoggingErrorReceiver` collects per-element `Indicators` (356–359)
   - the diagnostics are then passed through `PerRecord.told` (364), which de-duplicates by severity, element and message and caps them at **20** (`S/extraction/PerRecord.java:39,113-123`)
4. `StandInFragmentRunner.serve` (`S/fragment/StandInFragmentRunner.java:191-231`) is the test double. It uses the in-module step runners, which **do** produce record ranges.

#### What can be observed where judging happens

- `ServedStream` (`S/fragment/ServedStream.java`) carries `records` (output), `meanTotal`, the de-duplicated `diagnostics` and `shortfall`. It carries no input count and no per-element error count.
- Inside `serve`, `logging.getIndicators(parserId).getCount(Severity.ERROR)` is available (`stroom-util-shared/.../Indicators.java:199`) before de-duplication. It is not passed out.
  Counting from `served.diagnostics()` in `Stage` would be wrong, because they are de-duplicated and capped at 20 (W4-2).
- The real pipeline reports **no record ranges**, so `InputCoverageScorer` cannot run at serve time: it needs `step.result().recordRanges()` (`S/scoring/InputCoverageScorer.java:51`).
  Learning measures coverage as a share of **characters** (A36). Serve-time coverage would be a count of records or errors. The same document threshold would mean different things in each place (W4-3).
- The drift doc assumes "one line per record for this parser". The binding knows only the **output** boundary: for the Data Splitter it is always `record` at depth 1 (`Stage.boundary`, 2031–2038).
  Multi-line Data Splitter records exist (`TestScenario48CsvMultiline`), so counting input lines is not a record count (W4-1).

#### Deferral, relearn mark, rolling score and output row

- **Defer and relearn.** The existing route, `session(...)` (1015–1060) followed by `throw DeferTaskException`, works for a check made **after** `serve` returns. The record-shortfall path already does this (955–967).
  The processor discards the task's output on a deferral (`P/DataProcessorTaskHandler.java:226-231`; `TestDeferredTask.aReprocessThatDefersLeavesTheEarlierOutputStanding`).
  - Any downstream element with side effects outside the output stream would already have received the records. *Speculation: indexers or other external writers are not rolled back.* This applies equally to today's mid-stream shortfall.
  - `Judged.shortOf(served, shortfall)` (2477) needs a `RecordShortfall` with an output index. An input-coverage failure has no output record, so it needs a new `Judged` form and a `failingRecords` value.
  - The unparsed lines are not kept anywhere (serving is streamed). The relearn's evidence would come from the worker's full pass, so that pass must see coverage too (next point).
- **The worker's full pass ignores the `ServedStream`.** `Stage.fullPass` (2009–2016) throws away `serve`'s return value and judges only output records. `settle` (1957–2005) treats `pass.clear()` as success.
  If fix 1 changes only `Stage.stream`, then after a relearn the worker sees a "clear" held stream and wakes it. The stream then fails coverage again in the task, defers, and opens another session: a loop that costs money (W4-4).
- **Relearn after drift.** `relearnUnderClaim` requires the candidate to clear every accepted corpus record, which are in the **old** format (1514–1527, A66). When a format has changed for good, the candidate must parse both formats or it is `Kept`.
  After `Kept`, the mark is reset (1491). The deferral row's stamp still equals the binding in force, so every retry, every `deferredTaskRetryDelay` (5 minutes, `P/ProcessorConfig.java:49`), runs `decideServed` → `session` → `markForRelearning` → a new attempt. This repeats without limit while the stream waits (W4-5). The loop exists today for record shortfalls; fix 1 makes it much more likely.
- **Rolling score.** `states.scored` is fed only `else if (served.records() > 0)` and not for provisional bindings (981–987). Its memory is `minRecordsToLearn`.
- **Output row.** `outputs.emitted(…, producedBy(doc, binding), …)` (988) records the binding's promotion score (1001–1006, `DB/OutputsDao.java:113`).
  The stream attribute `ShapeshifterAiScore` is written from the same `Produced`, before the stream is served (`Supervision.java:339-341`). So "record this run's score" changes the order of writes in `Supervision`, not only in `Stage` (W4-6).

#### Interaction with refactor i ("judge with the same code that serves")

- Refactor i proposes running candidates through the real pipeline with "a small extra filter that records input coverage". That filter is exactly what fix 1 needs inside `PipelineFragmentRunner.serve`.
- If fix 1 is built as a line count in `Stage` or an error count from `Indicators`, refactor i will replace it, and the plan's "the refactor keeps its tests" holds only for tests written against `Stage.stream` outcomes.
- **Recommendation:** build fix 1's measurement as that filter in the real-pipeline path, and expose it through `ServedStream`. `StandInFragmentRunner` must report the same value or `TestFragmentRunnersAgree` diverges.
- A unit test using `StandInFragmentRunner` (as the drift doc suggests) exercises the stand-in's coverage, not the real runner's. A stroom-app test is needed as well (W4-7).

#### Hard-coded values met

| Value | Where |
|---|---|
| `RECORD_SCORERS` set | `Stage.java:163` |
| `PerRecord.MOST = 20` | `PerRecord.java:39` |
| `PREFIX_BYTES` = 64 KiB | `Input.java:45` |
| `MOST_ROUNDS = 10` | `Stage.java:1946` |
| Data Splitter boundary `record`, depth 1 | `Stage.java:2036` |
| `deferredTaskRetryDelay` 5 minutes | `ProcessorConfig.java:49` |
| `ScorerSetting` default threshold 0.0 | `SH/ScorerSetting.java:41` |

#### Tests

- **Existing:** `TestScenario54TheTaskDecision`, `TestServedRunEvents`, `TestScenario57TheSessionRunsTheHeldStreamInFull` (and its `InAPipeline` version), `TestScenario16AServingRunWritesAsItGoes`, `TestTheJudgedRunIsTheServedRun`, `TestFragmentRunnersAgree`, and `TestScenarioBManyStreamsManyThreads`, which asserts that streams are not served twice.
- **Fixtures:** `netfilter.log` and `netfilter-drift-1/2/3`.
- **Missing:** a test that the worker's full pass refuses a held stream with zero coverage, and a stroom-app test that serves `netfilter-drift-1` through `PipelineFragmentRunner`.

#### Effort: **M to L**

The drift doc presents fix 1 as a contained `Stage.stream` change. The real scope is:

- a coverage measure in the real runner
- the same measure in the stand-in
- `ServedStream` and a new `Judged` form
- `fullPass` and `settle`
- the order of writes in `Supervision` for the score
- a decision on how the threshold maps onto the character-based learning metric
- guarding against the relearn loop (W4-5)

---

### W7: several nodes learning and serving at once

#### Claims

- **Attempt claim.** `DB/AttemptsDao.java:97-111` inserts under a unique key on the learner and its claim, and an integrity violation returns `Optional.empty()`.
  `claimed` (114–131) lets a worker take an `AWAITING_MODEL` attempt, or its own or a lapsed `IN_PROGRESS` one.
  `closed` (362–384) frees the claim by setting `CLAIMED` to null.
- **The learning job.** `DeferredLearning` runs every **1 minute** on every node (`S/element/ShapeshifterAiElementModule.java:64-70`) with a batch of 10.
  Every node reads the same oldest-first list (`DeferredWorker.advance`, `S/stage/DeferredWorker.java:80-98`), and the claim decides who wins. Losers get a `Sentinel`.
- **Single-node and multi-node test coverage.** `TestScenariosBindingBeforeLearning.aSecondNodeMeetingTheSameLearnerWaitsWithoutHoldingAThread` (218) simulates a second node through the claim only.
  `TestScenarioBManyStreamsManyThreads` uses one JVM and therefore **one cache**, in worker-claim mode.
  No test has two caches with delayed or lost events.

#### The waiting reason (supervisor visibility, item 5)

- `Stage.recording` (`Stage.java:1166-1172`) uses the same text whenever `opened` is empty. `opened` returns no information about who holds the claim.
- `OPEN` = `IN_PROGRESS`, `AWAITING_MODEL` and `PARKED` (`AttemptsDao.java:61-62`). The **commonest** holder is `AWAITING_MODEL`: every task-opened session parks at its first question (`RecordedAdvisor.awaiting()`, caught at 1200–1212) and waits up to a minute for the job.
- So the text is wrong on a single node too, and for three different states, not only `PARKED` (W7-5).
- The fix needs an `attempts.open(learner, now)` lookup after the insert fails (the method exists, 247–258), and a third wording for "queued for the learning job".
- `deferrals.deferred` rewrites the reason on each retry (`DB/DeferralsDao.java:72-79`), so the reason refreshes only every 5 minutes.
- The retry count already reaches the DTO (`SupervisorResourceImpl.java:191,298`, `row.attempts()`).
- The test at line 229 asserts the old text and will need changing.

#### Binding cache across nodes

- `CachedLearnerBindings` and `CachedLearnerStates` invalidate locally and then fire an `EntityEvent` (`CachedLearnerBindings.java:113-118`).
- `EntityEventBusImpl.fireGlobally` sends to other nodes asynchronously, and errors are only logged (`stroom-core/.../EntityEventBusImpl.java:146-171,188-217`). There is no retry or acknowledgement.
- The backstop expiry is `expireAfterAccess` 10 minutes (`S/ShapeshifterAiConfig.java:46-48`; `expected.yaml:1366-1369`). A learner whose streams keep arriving keeps refreshing the access time, so **a lost event leaves a stale entry for as long as traffic continues** (W7-1).

#### Races found by reading the code

1. **Promotion and then a stale node.**
   - `bind` appends the binding (1621), wakes (1639) and emits, and only then does `carrying` close the attempt and free the claim (1222–1223).
   - A woken task on a node whose cache still says "no binding" either gets the claim refused ("Another node…", deferred with `failedAgainst = null`) or, once the claim is freed, **opens a new attempt** (1164).
   - The worker that resumes it re-reads the bindings (`resume` 252, 270). If its cache is fresh, it runs `relearnUnderClaim` with "the learner was marked to learn again", which calls the model with nothing actually marked. If its cache is also stale, `learnAndBind` **appends a second binding** for the same learner (W7-2).
   - Nothing in `shapeshifter_binding` stops this: the learner key is not unique (MIG:79).
   - *The window is normally the event's latency; with a lost event it is unbounded.*
2. **A deferral written after the wake is not woken early.** `wakeSuperseded` reads only rows whose `failed_against IS NOT NULL` (`DeferralsDao.java:126-137`). A first-learning deferral that lands after a promotion's wake waits for the 5-minute retry sweep. This happens in both processor modes (W7-4).
3. **Lost update on `replace`.**
   - `LearnerBindingsDao.replace` (`DB/LearnerBindingsDao.java:82-118`) runs `SELECT … FOR UPDATE`, ignores the result, and then overwrites **every** column from the caller's copy. There is no version check.
   - Its comment says "the second seeing what the first wrote rather than both succeeding blind". That is not what the code does.
   - `Stage.stream`'s provisional promotion (974) builds from the binding handed to it by `decide`, which may be a stale cached copy. It can therefore overwrite a concurrent in-place rebind's pipeline, snapshot and boundary (W7-3). Option C removes this writer.
4. **`bind` is not one transaction.** The binding append, the regression set, the state reset, the wake, the output row and the attempt close each commit separately (1621–1643, 1222).
   A node that dies after the append and before `closed` leaves an `IN_PROGRESS` attempt. It lapses, and the next worker resumes it as a relearn, because a binding now exists (`resume` 270).
   It replays recorded turns, so this is cheap. *Severity minor; no test covers dying mid-bind (Scenario 58 covers dying mid-conversation).*
5. **The rolling score is safe.** It is updated in a transaction with `FOR UPDATE` (`DB/LearnerStatesDao.java:65-95`).

#### The `claimTasksOnWorker` requirement: checking the doc's safety argument

Each claim was checked in the code and holds.

| Claim | Where it was checked |
|---|---|
| A deferral writes `DEFERRED` with no node | `P/DataProcessorTaskHandler.java:226-231` |
| A wake returns tasks to `CREATED` with no node | `P/dao/ProcessorTaskDaoImpl.java:485-509` |
| The retry sweep runs in both modes | `P/ProcessorTaskReaper.java:110-113` |
| The master re-reads `CREATED` tasks from id 0 on every fill | `queueCreatedTasks` (`P/ProcessorTaskQueueManagerImpl.java:920-1030`) calling `findExistingCreatedTasks` (`ProcessorTaskDaoImpl.java:1635-1649`) |
| Filters are chosen only on enabled and not deleted | `P/PrioritisedFilters.java:76-81` |
| The empty-filter backoff lasts 10 seconds and a wake does not reset it | `FilterFetchBackoff.java:105-116`, `ProcessorConfig.java:120` |
| The check runs only on create | `ProcessorFilterServiceImpl.java:170,284`; `update` at 305 does not call it |
| Its callers | `ContentAutoCreationServiceImpl.java:694`, `PipelineMaker.java:133`, `TestPipelineFactoryMakesPipelines:179-191` |
| The module binding | `ShapeshifterAiModule.java:40` |
| `TestDeferredTask` runs in both modes | three parameterised tests; processor only, no Shapeshifter element |

Caveats not stated in the doc:

- All 8 Shapeshifter stroom-app scenario tests force claim mode on, so no Shapeshifter test runs with the master queue (W7-6).
- W7-2 and W7-4 do not depend on the mode, but the master queue adds latency to every wake (up to 10 seconds plus a fill cycle).
- The comment on the check (`ProcessorFilterServiceImpl.java:120-122`) and design docs 03 §3 (`03-processing.md:136-138`) and 08 repeat the incorrect reason.

#### Hard-coded values met

| Value | Where |
|---|---|
| Job every 1 minute, batch 10 | |
| Session lease 10 minutes | `ShapeshifterAiConfig.java:56` |
| Binding and state cache `expireAfterAccess` 10 minutes, max 1000 | |
| Deferred retry 5 minutes | |
| `skipEmptyFilterFetchDuration` 10 seconds | |
| `skipNonProducingFilters` 10 seconds to 1 minute | `ProcessorConfig.java:118-119` |

#### Effort

- **S** for task 5 (removing the requirement): deletions plus a test-cluster trial.
- **S** for task 6, item 5: one lookup and three wordings.
- **M** to make multi-node learning sound: a unique open-binding guard, or a check before append; a version-checked `replace`; waking first-learning deferrals; a two-cache test harness. The plan has none of this yet.

---

### Findings

| ID | Title | Evidence | Assumption → what breaks | Severity | Plan item / decision | Known? | Test that would catch it |
|---|---|---|---|---|---|---|---|
| W3-1 | A small draft blocks all learning | `Stage.java:842-846, 651, 1036` | Option C says a small first result "waits until a later session has learned from more data". In the code a draft defers every stream and refuses every session, so the feed waits only for a person. | changes an estimate | Simplification C; decision 3 | No | Automatic document, first stream of 4 records → `Drafted`; a second stream of 20 records → still `Deferred` "Awaiting review", and no attempt opened |
| W3-2 | Relearns ignore the record minimum | `Stage.java:1470-1590` (no `minRecordsToLearn` check; rebinds in place) | "Never auto-approved on too few records" covers first learning only. A relearn from a 1-record shortfall goes live automatically. | changes an estimate | C; A | No | Relearn triggered by a 2-record stream on an automatic document → expect a draft under C; today it gives `Rebound` |
| W3-3 | Removing `AttemptStatus.PROVISIONAL` breaks old rows | `DB/AttemptsDao.java:572` (`valueOf`), `:81-82` | Old attempt rows with status `PROVISIONAL` throw when read | changes an estimate | C | No | DAO test: insert a `PROVISIONAL` row, run the migration, read with `forDocument` |
| W3-4 | Stale comment about the provisional corpus | `Stage.java:1754-1761` vs `1628-1632` | The comment says provisional bindings record nothing in the regression set; `bind` records them | minor | C | No | `regressionSet.accepted(provisionalUuid)` is not empty after a provisional bind |
| W3-5 | Dropping the columns means editing the one unreleased migration or adding another | MIG:69,177; `ISSUES.md` #18 | Editing `V08_00_00_001` in place breaks Flyway's checksum on existing dev and demo databases | changes an estimate | C; inventory §12 ("release-bound artefacts") | Partly (§12) | Flyway validate against a database migrated from the current file |
| W3-6 | "Review mode never has provisional bindings" is wrong | `Stage.java:1611-1612`; `TestScenario22ReviewMode:373-386` | Drafts carry `provisional=true` until approved | minor | C (text) | No | Existing `approvingAProvisionalDraft…` |
| W3-7 | A low-volume provisional feed never promotes and has no rolling score | `Stage.java:970-987` | Promotion needs a single stream with ≥ min records; provisional bindings skip the rolling score | minor (supports C) | C | Implicitly, A101 | Existing `scenario28…` |
| W4-1 | No input-record count exists for a Data Splitter | `Stage.java:2031-2038`; `TestScenario48CsvMultiline` | The drift doc's "one line per record" is false for multi-line Data Splitter configurations; the binding knows only the output boundary | blocks a planned change (as designed) | Track 1 task 2 | `ISSUES.md` #2 (the gap itself, not this) | Multi-line CSV served with no drops must not be deferred |
| W4-2 | Diagnostics are de-duplicated and capped before `Stage` sees them | `PerRecord.java:39,113-123`; `PipelineFragmentRunner.java:354-364` | Counting parser errors from `served.diagnostics()` undercounts above 20 distinct messages and above repeats of one message | changes an estimate | Task 2 | No | A stream with 100 unmatched lines must report 100 (or a ratio), not 20 |
| W4-3 | Learning coverage counts characters; serve-time would count records | `InputCoverageScorer.java:51-56`; A36 | One "coverage threshold" would mean different things in learning and serving | changes an estimate | Task 2; D-unknown-models 2 | No | A candidate promoted at coverage 0.97 by characters is not deferred at serve time on the same stream |
| W4-4 | The worker's full pass ignores input coverage | `Stage.java:2009-2016, 1987-1990` | If only `Stage.stream` changes, settle declares the held stream clear, wakes it, and it defers again: a relearn loop | blocks a planned change (as scoped) | Task 2 | No | Scenario: learn `netfilter.log`, serve `drift-1`; assert the session does not end `Promoted` with a full pass that reads 0 of 30 |
| W4-5 | Unbounded relearn sessions after `Kept` | `Stage.java:1491, 1503-1527, 884-890, 1040-1060` | The corpus no-worse rule keeps a candidate that parses only the new format out; every 5-minute retry re-marks and opens a new attempt that calls the model | changes an estimate | Task 2; spend breaker (simplification §5) | No | Drift stream whose relearn is `Kept`: after N retries, assert ≤ 1 attempt (or that it parks) |
| W4-6 | The output score and stream attribute are fixed before serving | `Supervision.java:339-341`; `Stage.java:988,1001-1006` | "Record this run's score" needs the attribute write moved after `stage.stream` | minor | Task 2 ("either way") | Drift doc (row only) | Output row and `ShapeshifterAiScore` attribute equal the run's `meanTotal` |
| W4-7 | Stand-in and real runner would measure coverage differently | `StandInFragmentRunner.java:191-231` vs `PipelineFragmentRunner.java:278-366` | A unit test on the stand-in passes while production does not detect drift | changes an estimate | Task 2; refactor i | Refactor i names the general risk | Extend `TestFragmentRunnersAgree` to compare coverage on the drift fixtures |
| W7-1 | A lost cache event is never backstopped under traffic | `ShapeshifterAiConfig.java:46-48`; `EntityEventBusImpl.java:188-217` | `expireAfterAccess` never expires a busy learner; a node keeps serving an old binding, or "no binding" | changes an estimate | Tasks 5–6; multi-node | No | Two-cache harness: drop the event to node B and keep serving; B must see the new binding within a bound (`expireAfterWrite`) |
| W7-2 | A stale node can open a spurious session or append a second binding | `Stage.java:1621,1639,1222-1223,1164,252-286`; MIG:79 | Assumes every node sees the promotion before the claim is freed. Otherwise there is an unrequested model call, or two live bindings for one learner (the first by id wins and the second is orphaned) | changes an estimate | Task 5; multi-node | No (Scenario B uses one cache) | Two-cache harness: delay the event to B; wake; assert one session per learning event and ≤ 1 non-draft binding |
| W7-3 | `replace` is last-writer-wins, despite its comment | `DB/LearnerBindingsDao.java:82-118`; `Stage.java:974` | A serve-time provisional promotion from a cached copy can revert a concurrent rebind's pipeline and snapshot | changes an estimate (removed by C) | C; multi-node | No | DAO test: two `replace` calls from the same stale copy with different fields; expect a version conflict |
| W7-4 | First-learning deferrals are not swept awake | `DeferralsDao.java:126-137`; `Stage.java:1115-1127` | A deferral written after the promotion's wake waits for the 5-minute retry sweep | minor | Tasks 5–6 | A130 covers stamped rows only | Defer with `failedAgainst=null` after the promotion; `wakeSuperseded` should return 1 |
| W7-5 | The waiting reason is wrong in three states, not one | `Stage.java:1166-1172`; `AttemptsDao.java:61-62,247-258` | It is said for `AWAITING_MODEL` (the commonest, including on single-node installs) and `IN_PROGRESS` on the same node, as well as `PARKED` | changes an estimate (small) | Task 6, item 5 | Visibility doc item 5 (`PARKED` only) | Update `aSecondNodeMeeting…:229`; add cases for `AWAITING_MODEL` and `PARKED` |
| W7-6 | No Shapeshifter test runs with the master queue | 8 `stroom-app/src/test/java/stroom/shapeshifter/*` set claim mode on; `TestDeferredTask` is processor-only | The doc's argument is correct in the code but untested end to end | changes an estimate | Task 5; H | Doc says "one or two scenario tests should be run in both modes" | Parameterise `TestScenarioAFromNothingToServed` on `claimTasksOnWorker` |
| W7-7 | `bind` spans several transactions | `Stage.java:1603-1643, 1222` | A crash between append and close makes the next worker treat the learner as relearning | minor | Multi-node | No | Kill after `learnerBindings.append`; the resumed attempt should end `Promoted` without asking the model |


---

## Appendix D: Walkthroughs W5 and W8

Branch `ai_translations_server`, read 2026-11-17. Read-only: nothing was run; every claim below comes
from reading the code unless it is marked *(inferred)* or *(speculation)*.
Paths are shortened: `ai/` = `stroom-learning-flow/stroom-learning-flow/src/main/java/stroom/shapeshifter/ai/`,
`shared/` = `stroom-core-shared/src/main/java/stroom/shapeshifter/shared/`,
`db/` = `stroom-learning-flow/stroom-learning-flow-impl-db/src/main/`.

---

### W5. A new parser type, or a record boundary that isn't one element name

#### How the record boundary is represented and stored

| Layer | Representation | Evidence |
|---|---|---|
| Shared DTO | `RecordBoundary`: `element` XOR `array`, plus `depth`. The constructor throws unless exactly one is set. `equals` ignores depth. | `shared/RecordBoundary.java:36-60`, `:125-139` |
| Learning | `Boundary`: `configuration` XOR `element` XOR `array`, plus `depth`. `shared()` returns **null** for a Data Splitter configuration. | `ai/learning/Boundary.java:32-42`, `:71-78` |
| Default | A chain whose first element is literally `DSParser` and that settled no boundary gets `ofElement("record").atDepth(1)`. Any other text parser gets none. | `ai/stage/Stage.java:158`, `:2031-2038` |
| Binding (DB) | Three columns: `boundary_element`, `boundary_array`, `boundary_depth`. These are rebuilt by an if/else on which column is set. | `db/.../V08_00_00_001__learning_flow.sql:72-74`; `LearnerBindingsDao.java:162-178`, `:203-220` |
| Output rows (DB) | The same three columns on `shapeshifter_output`. | SQL `:179-181`; `OutputsDao.java:106-129`, `:251-255` |
| UI | Only `toString()` (`"element X"` / `"array Y"`), shown as a "Record" term. | `ai/element/StepDetails.java:82-84`; `stroom-core-client/.../StageDecisionPaneController.java:105-106` |

The corpus, regression set and snapshots do **not** store the boundary. Corpus entries are records that have already been split (`ai/stage/Corpus.java:98-101`), and `StoredRegressionSet` never refers to it.

#### What consumes the boundary

| Consumer | Uses the name or the depth? | Evidence |
|---|---|---|
| Split step (XML) | Name: the reply must match `[A-Za-z_][\w.-]*`, then `elementsNamed`, then `recordElement` (root check, single-container check, 90% wholeness) | `ai/learning/Conversation.java:505-525`; `TargetChecks.java:129-163`, `:191` |
| Split step (JSON) | Array key or `root` | `Conversation.java:568-612` |
| Fragment written for serving | **Depth only**: `SplitFilter` at `splitDepth` in front of the first non-parser element | `ai/fragment/FragmentWriter.java:200-216` (and `rewrite` `:122-135`) |
| Learn-time per-record rerun, corpus building | Depth only (`PerRecord`, `RecordSplit` drive the real `SplitFilter`) | `Stage.java:2376-2405`; `Corpus.java:98-101`; `extraction/RecordSplit.java:38-80` |
| Stand-in runner (Tier 1) | Depth (`orElse(1)`) | `ai/fragment/StandInFragmentRunner.java:132`, `:217` |
| Counting records brought / yield | **Name**: `Records.parsed(doc, boundary)` → `OutputRecords.recordsBy`. Where this finds nothing it **falls back silently** to the root's children. | `Stage.java:2157-2175`; `scoring/Records.java:53-65`; `scoring/YieldScorer.java:57-62` |
| `recordsBy` | Name, checked against `[\w.:-]+`. Anything else (e.g. `batch/*`) returns an empty list without failing, so the caller falls back. | `scoring/OutputRecords.java:128-153` |
| Configuration question text | One sentence for each variant | `ai/learning/QuestionText.java:224-257` |
| Per-record bar while serving | The boundary is passed in, but the bar judges each emitted record; it does not use the boundary | `Stage.java:952-953`, `:2011-2014` |

Key observation: **serving already splits by depth, not by name.**
A GATEWAY-AUDIT binding with `ofElement("authentication")` at depth 3 would hand the transform *every* child of `<batch>` (*inferred* from `SplitFilter` semantics plus `FragmentWriter.java:206-213`).
The name only drives the learn-time wholeness check and the record counts.
So the "children of element" boundary is mostly a learning and counting change, and the serving path needs almost nothing new.

#### Where the set of parser types is fixed

| Place | What is fixed | Evidence |
|---|---|---|
| Default allowed elements | `DSParser, JSONParser, XMLParser, XSLTFilter`. `XMLParser` has **no runner** and is silently filtered out of the chain question. | `shared/FlowStep.java:51`; `Conversation.java:258-260` |
| Runners | Four, hand-listed: DS, JSON and XMLFragment are *module re-implementations*; only XSLT is wrapped in the real element | `ai/element/StageFactory.java:175-186` |
| Input kinds | `TEXT`, `XML`, `JSON` only; each runner declares one | `ai/learning/InputKind.java:22-26`; `StepRunner.java:71-95` |
| Chain prompt | Example `"DSParser -> XSLTFilter"`; element descriptions are a fixed map, otherwise "a pipeline element". The map says `JSONParser` produces records:2, but it produces XSL/json. | `Templates.java:69`; `QuestionText.java:61-67` |
| Split prompts | `SPLIT` plus `SPLIT_RULES` are Data Splitter 3 only; a new kind of split question needs a new `Template` enum value (shared, and the flow editor lists them) | `Templates.java:71-84`, `:155-178`; `shared/Template.java:29-39` |
| Extraction vs transform rules | `"DSParser".equals(elementType)` picks `EXTRACTION_RULES`; any other parser is given the XSLT *transformation* rules | `QuestionText.java:194-201` |
| Text split step | Assumes a configured parser (`orElseThrow`); record text is read as `data/@value` (records:2) | `Conversation.java:528`, `:955-963` |
| Default boundary | `DSParser` only (see above) | `Stage.java:2035` |
| Input coverage gate | Applies only when the step reports `recordRanges`. `PipelineStepRunner` (the real element) reports none, so coverage is **silently skipped** for any parser that isn't re-implemented | `scoring/InputCoverageScorer.java:51`; `element/PipelineStepRunner.java:213` |
| Corpus spans | Only a parser that reports ranges keeps the record's own text, preamble and postamble | `Corpus.java:102-112` |
| Preservation | The root's children, read as `data/@value \| text()` (this is Track 1 task 1) | `TargetChecks.java:277-310`, `:368-373` |
| Configuration docs written | `TextConverter` (with a `converterType`) or `XSLT` only | `fragment/FragmentWriter.java:258-290` |
| Generic (already fine) | The fragment runner, `ReplayUnits` and `Supervision` ask the element registry for `ROLE_PARSER` | `fragment/PipelineFragmentRunner.java:507-513`; `element/Supervision.java:178-181` |

#### Files that would change

**Children-of boundary (fix 1 of `learning-flow-mixed-record-elements.md`):**
- `shared/RecordBoundary.java`: add a third field (e.g. `childrenOf`), rewrite the XOR check, `equals`/`hashCode`/`toString`, Jackson order. It must stay GWT-safe.
- `ai/learning/Boundary.java`: the same variant, and `shared()`.
- `ai/scoring/OutputRecords.java:128-166`: `recordsBy` and `depthOf` for the new variant.
- `ai/learning/TargetChecks.java`: a new `recordChildren` check, written so that a parent occurring **once** in the sample is allowed.
- `ai/learning/Conversation.java:505-525`: accept `x/*` and `*`.
- `ai/learning/Templates.java:85-97` (`SPLIT_XML`), and `VERSION` at `:45`.
- `ai/learning/QuestionText.java:255-256`: describe the boundary to the transform.
- `db/.../LearnerBindingsDao.java:162-220`, `OutputsDao.java:106-129`, `:251-255`.
- A migration: a new column on `shapeshifter_binding` and `shapeshifter_output`, plus jOOQ regeneration. Or edit `V08_00_00_001` in place, since it is unreleased; that is a decision.
- `ai/stage/Stage.java:2320-2347` (`wholeChildren`), so that the sample can be cut at the boundary depth (see W5-4).
- Tests (below).
- Probably *no* change to `FragmentWriter`, `RecordSplit`, `PerRecord` or the per-record bar, since they already work by depth.

**New parser type (e.g. CombinedParser, or a CSV/regex parser):**
- `ai/element/StageFactory.java:181-186`.
- A new `StepRunner` that reports `recordRanges`. Otherwise coverage is lost (W5-3), so in practice this means re-implementing the parser as `DataSplitterStep` does.
- `QuestionText.java:61-67`, `:194`.
- `Templates.java`: new split and extraction rules.
- `shared/Template.java`: new enum values.
- `shared/FlowStep.java:51`.
- `Conversation.java:526-552`: the text split assumes `data/@value`.
- `Stage.java:2031-2038`.
- `TargetChecks.java:368-373`.
- `FragmentWriter.java:258-290`, if it is not a TextConverter kind.
- A `TestStepRunnersAgree` case in `stroom-app`.

#### Hard-coded values met
`SPLIT_WHOLENESS = 0.9` (`TargetChecks.java:191`); `DEFAULT_SAMPLE_SIZE_LIMIT = 8_192` (`shared/ShapeshifterAiDoc.java:105`); `Input.PREFIX_BYTES = 64 KiB` (`ai/stage/Input.java:45`); `FlowStep.DEFAULT_KINDS = 3` (`shared/FlowStep.java:47`; GATEWAY-AUDIT has about 10 kinds); `"DSParser"` (`Stage.java:158`, `QuestionText.java:194`); `"record"` default element (`Stage.java:2036`); `"XMLParser"` spliced in front of filters (`PipelineStepRunner.java:71`, `PipelineFragmentRunner.java:99`); `SHAPING = {"SplitFilter"}` (`PipelineFragmentRunner.java:114`).

#### Gotchas
- No `switch` over the boundary variant exists. Every consumer branches with `isArray() ? … : getElement()`, so the compiler will not point at the places a third variant must be handled. Missing one gives a silent fallback to the root's children (`OutputRecords.java:132-136`, `Records.java:61-64`), not an error.
- The cheap encoding (`element = "batch/*"`) would compile and store, but `recordsBy` would silently refuse it (`OutputRecords.java:132`). It needs a real field.
- The XML split step passes **with no boundary** when the sample doesn't parse (`Conversation.java:500-503`). A stream of 64 KiB or more is given to the stage as a raw byte prefix (`Supervision.java:377-385`). That prefix isn't well-formed, so `learningPrefix` treats it as lines (`Stage.java:2115`), and the split is skipped (W5-4).
- `wholeChildren` always keeps the first root child whole, ignoring `sampleSizeLimit` (`Stage.java:2338-2341`). For a children-of boundary, one `<batch>` may be the whole stream.
- The depth is frozen in the fragment. A structural drift that moves records (gateway drift 3: depth 3 → 2) makes `SplitFilter` cut *fields* as records *(inferred)*, and zero or odd events are not deferred while serving (`Stage.java:981-988`).

#### Tests that exist / would catch a regression
Exist: `TestTargetChecks` (`recordElement`, `elementsNamed`), `TestScenario49XmlFragments`, `TestScenario46Json`, `TestScenario01LearnsACsvFeed`, `TestYieldScorer`, `TestFragmentWriter`, `TestStandInFragmentRunner`, `TestRecordSkeleton`, `TestLearnerBindingsDao`, `TestLearnerStatesAndAttemptsDao`; in `stroom-app`, `TestFragmentRunnersAgree`, `TestStepRunnersAgree`, `TestTheJudgedRunIsTheServedRun`.
**No test uses `gateway-audit*.xml`.**
Needed: the scenario and unit tests the mixed-records doc lists, plus a `Records.parsed` test that a children-of boundary is counted rather than falling back, a DAO round-trip for the new column, and a test with a single XML document of 64 KiB or more.

#### Effort
- **Children-of boundary: M.** About 12 production files plus a migration and jOOQ regeneration, but serving and corpus are already depth-based, so the risk is concentrated in counting and sampling. Add S if the 64 KiB and `wholeChildren` sampling fix (W5-4) is taken on as well, which it should be for real-size exports.
- **New parser type: L.** The prompts, split step, extraction rules, preservation and default boundary are all Data-Splitter-shaped. Wrapping the real element is cheap but loses input coverage and corpus spans, so a faithful runner means a re-implementation plus a Tier 2 agreement test.

---

### W8. A feed with no timestamps, or local times / no year

#### What the model sees about the stream
- Only `Feed` and `Type`: `Sample.of(text, feed, type)` at `Stage.java:1674`; `Sample.SHOWN` at `ai/learning/Sample.java:43`; rendered by `QuestionText.java:295-302`. Received time, create time and every other attribute are dropped.
- The data is reachable. `Input.attributes` holds the stream's full attribute map, both in a task (`Supervision.java:512-518`) and in the worker (`StreamInputs.java:66-80`, via `AttributeMapFactory`). `Input` has **no** `Meta` create time field (`ai/stage/Input.java:39-40`), so fix 2 either relies on a `ReceivedTime` attribute being present or adds a field to a record with about 66 `new Input(` call sites.
- `${headers}` already appears in every question template, so adding a header needs no `Template` enum change.

#### Time zone anywhere?
None. There is no time-zone field on `ShapeshifterAiDoc`, `FeedDoc`, `PipelineDoc` or `XsltDoc` (grep), and the shapeshifter production code has no `ZoneId`/`ZoneOffset` use (its only `UTC` is `Clock.systemUTC()`).
The only time zone in reach is *in the data*: GATEWAY-AUDIT puts `timeZone` on `<batch>`, and `SplitFilter` replicates the parent structure (`RecordSplit.java:38-39`).

#### How the prompts and scorers treat times
- `TRANSFORMATION_RULES` (`Templates.java:241-254`) says *"datetimes are ISO 8601 with milliseconds and a trailing Z"* (`:252`). This **instructs** the model to append `Z` to a local time, which is the NETFILTER observation (W8-2).
- No prompt mentions `stroom:` functions, a missing year or a time zone.
- No scorer reads times. `scoring/*` has no date or time logic, and schema conformance only checks the `xs:dateTime` lexical form.
- A document's business rule could use `current-dateTime()` as an interim gate, since business rules are in the record bar at `Stage.java:163` *(speculation: untested, and it is "now", not received time)*.

#### Is `stroom:format-date` usable?
| Path | Usable? | Base time it gets |
|---|---|---|
| Tier 1 (`XsltStep`, `StandInFragmentRunner`, all `src/test` scenarios) | **No.** `ALLOW_EXTERNAL_FUNCTIONS=false` and no Stroom library, so the stylesheet fails to compile | n/a (`ai/transformation/XsltStep.java:51-55`, `:156`) |
| Node, learning (`PipelineStepRunner` → real `XSLTFilter`) | Yes | **now**. Learning runs in `DeferredWorker` under a *fresh* pipeline scope (`element/DeferredLearning.java:85-92`) where nothing sets `MetaHolder` (only `Supervision` reads it), so `FormatDate.getBaseTime()` falls back to `Instant::now` (`stroom-pipeline/.../FormatDate.java:449-457`) |
| Node, serving (`PipelineFragmentRunner.serve` inside the task) | Yes | The stream's create time *(inferred: same pipeline scope as `Supervision`; `MetaHolder` is `@PipelineScoped`)* |
| Node, held-stream full pass / corpus reruns in a session | Yes | now (worker scope) |
| **Planned static check (A147, ISSUES item 21)** | **Would refuse it**: "refused … if it calls any extension function" | n/a |

#### What a time-plausibility gate needs, and where it runs
- **Learning.** `Scorer.score(params, Attempted)` has no stream context (`scoring/Attempted.java:30-34`). The gate needs a reference time threaded through `Attempted` (19 call sites) or the `Scorecard`/`Conversation` built per session (the `Stage` has the `Input`). The target step judges proposed events through `scorecard.meaning()` (`Conversation.java:625-627`). A gate there would catch an invented year before any XSLT is written.
- **Serving.** The record bar's lambda has `input` in scope (`Stage.java:951-953`, `:2009-2012`).
  - If the gate is a new `ScorerType`, it must be added to `RECORD_SCORERS` (`Stage.java:163`), to the shared enum and parameters (`shared/ScorerType.java:26-34`), and to the client editor (`ScorerSettingEditor.java:93-127`, `ScorerSet.java:54`). It would then also be off for every document that does not list it (ISSUES item 22).
  - A built-in rule avoids both.
- **Corpus.** `Corpus.shortfall` re-judges old entries with the scorecard (`Stage.java:537-539`, `:1524-1526`). A gate measured against *the current stream's* received time would fail entries accepted from older streams. Entries carry the accepted time, not the stream time (`Corpus.java:120-131`), so they need a reference time of their own, e.g. through `shapeshifter_corpus.meta_id`.

#### Files that would change
`Templates.java:241-254` (and `VERSION` at `:45`); `Sample.java`/`Stage.java:1674` (headers); `Input.java` (create time); `Attempted.java` or the `Scorecard`; a new scorer or built-in check in `scoring/`; `Stage.java:163`, `:951-953`, `:2009-2012`; `Corpus.java`; `XsltStep.java:156`, if Tier 1 is to accept `stroom:format-date`; `DeferredLearning.java`/`DeferredWorker.java` to set `MetaHolder` from the attempt's stream; and the A147 check when it is built. Fix 4 (a time zone setting) adds `shared/ShapeshifterAiDoc.java`, `doc/ShapeshifterAiSerialiser.java` and a client field.

#### Hard-coded values met
`"…trailing Z"` (`Templates.java:252`); `Sample.SHOWN` = Feed and Type (`Sample.java:43`); the `FormatDate` defaults of now and UTC (`FormatDate.java:435-457`); `Clock.systemUTC()` (`StageFactory.java:213`, `DeferredLearning.java:91`).

#### Gotchas
- The fix the docs recommend (use `stroom:format-date`) collides with ruling A147's "no extension functions".
- It cannot be exercised by Tier 1 scenario tests at all, although the plan says each Track 1 fix comes with a regression test "on the scenario fixtures".
- `TestStepRunnersAgree` will show the stand-in and the node disagreeing on any stylesheet that uses it.
- The worker computes years from *now*, not from the stream. That is fine for live feeds and wrong for back-loaded or reprocessed streams, and it is the path every model-driven learning takes.
- Once the gate lands, every existing binding that hard-codes a year (e.g. NETFILTER's 2023) will defer every stream and relearn, up to `MOST_ROUNDS`, then park. This needs a rollout note.
- The per-record bar already judges events while serving, so a time rule placed in the bar triggers deferral without waiting for parser-drift fix 1. The plan's ordering (task 3 depends on task 2) is stricter than the code requires.
- Document overrides of `TRANSFORMATION_RULES` will not receive the new wording (`Templates.java:285-287`).

#### Tests that exist / would catch it
Exist: `TestQuestionText` (asserts parts of `TRANSFORMATION_RULES`, `:180`), `TestPerRecordScoring`, `TestStepRunnersAgree` (stroom-app). **None uses `netfilter*.log`** and none checks times.
Needed: a Tier 2 (`stroom-app`) scenario learning `netfilter.log` with a scripted hard-coded-year reply, which should be rejected, and a `format-date` reply, which should pass; unit tests of the gate (inside the window, a year early, in the future, missing or unparseable); a test that a worker-run fragment gets the stream's create time; a prompt snapshot test.

#### Effort
- **Fix 1 (prompt): S** once A147 is reconciled.
- **Fixes 1+2+3 as planned: M.** The gate needs a reference time plumbed through scorer inputs, the record bar and corpus re-judging; Tier 2 tests are needed; and the worker's `MetaHolder` gap must be closed.
- **Fix 4 (time zone setting): M**, touching the shared doc, the serialiser and the UI. It is coupled to decision D1 and the Settings tab.

---

### Findings

| ID | Title | Evidence | Assumption → what breaks | Severity | Plan item / decision | Already known? | Test that would catch it |
|---|---|---|---|---|---|---|---|
| W5-1 | Boundary variants are an implicit if/else; a third variant falls back silently | `OutputRecords.java:128-153`; `Records.java:53-65`; `LearnerBindingsDao.java:175-178`; `OutputsDao.java:251-255` | Assumes element XOR array. A new variant that isn't handled counts the root's children (for GATEWAY-AUDIT, 2 `batch` instead of about 25 records) and wrong yields still pass | Changes an estimate | Track 1 task 4 | No | `Records.parsed` with a children-of boundary asserts the child count; DAO round-trip |
| W5-2 | Serving splits by depth, not name; the name is only used in learning and counting | `FragmentWriter.java:200-216`; `RecordSplit.java`; `Stage.java:2376-2405` vs `Conversation.java:510-525` | A name-based learn check and a depth-based serve can disagree. It also means the children-of fix needs little serving work | Changes an estimate (down) | Track 1 task 4 | Partly (the mixed-records doc notes `SplitFilter` splits by depth) | `TestTheJudgedRunIsTheServedRun` with a mixed-sibling XML |
| W5-3 | A parser run through the real element loses the input coverage gate and corpus spans | `PipelineStepRunner.java:213`; `InputCoverageScorer.java:51`; `Corpus.java:102-112`; `StageFactory.java:175-186` | Assumes parsers are module re-implementations that report `recordRanges`. A CombinedParser added cheaply has coverage silently unscored | Changes an estimate | W5 / any new parser; parser-drift fix 1 | Partly (`StageFactory` comment; not in ISSUES) | Scorecard over a `PipelineStepRunner` parser asserts that `INPUT_COVERAGE` is present |
| W5-4 | A single XML/JSON document of 64 KiB or more is learned from an unparseable prefix; the split passes with no boundary | `Supervision.java:377-385`; `Stage.java:2115-2119`; `Conversation.java:500-503`; `Stage.java:2031-2038` | Assumes the stream fits in the prefix. A large export (the GATEWAY-AUDIT style) gets no boundary, so no `SplitFilter`, so the whole stream goes into one transform *(inferred, not run)* | Blocks a planned change (task 4 on real-size data) | Track 1 task 4 | No (design 03 §3.2 describes the prefix, but not this effect) | Scenario with a 70 KiB `gateway-audit` variant asserts that a boundary is settled |
| W5-5 | The sample cutter keeps whole root children, ignoring the limit for the first | `Stage.java:2320-2347` (`:2340`) | Assumes records are root children. With `batch/*`, a sample is one whole batch, however large | Changes an estimate | Track 1 task 4 | Partly (mixed-records doc, "sample size makes the container check misfire") | `learningPrefix` test with a large single wrapper |
| W5-6 | The text-parser path is Data-Splitter-shaped throughout | `QuestionText.java:194`; `Conversation.java:528`, `:955-963`; `Stage.java:2035`; `Templates.java:71-84`, `:155-240`; `TargetChecks.java:370` | Assumes `DSParser` and records:2 `data/@value`. A new text parser gets XSLT rules, no default boundary (the whole stream goes into the transform), and the preservation vocabulary breaks | Blocks a planned change (new parser type) | W5; Track 1 task 1 | Task 1 doc covers preservation only | A scenario with a second text runner asserts extraction rules and a depth-1 boundary |
| W5-7 | `XMLParser` is a default element with no runner | `FlowStep.java:51`; `Conversation.java:258-260` | The model is never offered it, so the default list misleads | Minor | W5 | No | `Conversation` test asserting that the allowed list equals the runners |
| W5-8 | The depth frozen in the fragment does not survive structural drift | `RecordBoundary.java:84-99`; `FragmentWriter.java:206-213`; `Stage.java:981-988` | Assumes records stay at the learned depth. Gateway drift 3 would be served with fields as records *(inferred)* | Changes an estimate | Tasks 2 and 4 | Zero events are known (parser-drift doc); depth drift is not | Serve `gateway-audit-drift-3-flattened.xml` through a binding learned on `gateway-audit.xml` and assert a deferral |
| W8-1 | `stroom:format-date` conflicts with A147 and is impossible in Tier 1 | `ISSUES.md` item 21; `09-rulings.md` A147; `XsltStep.java:51-55`, `:156` | The planned check refuses every extension function, and Tier 1 refuses them already. Fix 1's recommended tool would be refused, and the regression test can't run in `src/test` | **Blocks a planned change** | Track 1 task 3; Track 2 step 3 (static check) | No (each is known separately; the collision is not) | Tier 2 scenario with a `format-date` stylesheet; static-check test with an allowlist |
| W8-2 | The prompt tells the model to write a trailing `Z` | `Templates.java:252` | Assumes every input time is UTC. A local time is relabelled as UTC (the NETFILTER case) | Changes an estimate (the fix must *replace* this text, not add to it) | Task 3 fix 1 | Partly (the missing-year doc blames the absence of rules, not this sentence) | `TestQuestionText` asserting that the rules forbid assuming UTC |
| W8-3 | The worker has no `MetaHolder`, so `format-date` uses now | `DeferredLearning.java:85-92`; `FormatDate.java:449-457`; only `Supervision.java:513` reads it | Assumes learning happens in the stream's task. In practice it happens in the worker, so a back-loaded stream is judged against the current year | Changes an estimate | Task 3 | Raised as "to confirm" in the missing-year doc; confirmed here | Tier 2: worker-run fragment over a stream with an old create time |
| W8-4 | No stream time or time zone reaches the model or the scorers | `Stage.java:1674`; `Sample.java:43`; `Input.java:39-40`; `Attempted.java:30-34`; no time-zone field on any doc | Assumes times need no context. A gate needs reference-time plumbing through scorer inputs; `Input` has no create time | Changes an estimate | Task 3 fixes 2 and 3; D1 (where a setting lives) | Yes (missing-year doc, causes 1 and 4) | Prompt-header test; gate unit tests |
| W8-5 | Corpus re-judging would fail old entries under a stream-relative gate | `Stage.java:537-539`, `:1524-1526`; `Corpus.java:120-131` | Assumes every check is time-independent. A year-old corpus record fails "one year before stream" | Changes an estimate | Task 3 fix 3 | No | Corpus regression test with an entry from an older stream |
| W8-6 | The time gate needs no parser-drift fix in order to trigger relearning | `Stage.java:951-966` (the record bar defers serve-time shortfalls) | The plan's ordering assumes task 3 waits on task 2. A rule in the record bar defers on its own | Changes an estimate (ordering) | Track 1 order (tasks 2 → 3) | No (the missing-year doc says it depends) | Serve with a bar rule rejecting the 2023 event, then assert a deferral |
| W8-7 | Rollout: existing bindings with hard-coded years will relearn-loop then park | `Stage.java:1985-2003` (`MOST_ROUNDS`, then parked) | Assumes a new gate meets only new bindings | Minor | Task 3 | No | Scenario: a promoted binding plus a new gate gives one session, not a loop |
| W8-8 | A new time `ScorerType` is off unless a document lists it | `Stage.java:163`; `shared/ScorerType.java`; ISSUES item 22 | Assumes the documents opt in. Existing documents never run the gate | Changes an estimate (prefer a built-in rule) | Task 3 fix 3; unknown-models decision 2 | Yes, generally (ISSUES 22) | Document without the scorer plus a hard-coded year: assert rejection |
