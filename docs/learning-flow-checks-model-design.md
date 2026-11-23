# Shapeshifter AI: one model of checks (design)

*Written 19 November 2026 · branch `ai_translations_server` · for Track 1 task 10, with the step-model part of Track 2 step 1c and the* Quality checks *part of Track 2 step 4*

## Summary

This is the design for [How checks work](learning-flow-delivery-plan.md#how-checks-work-decided-18-november-2026) (decisions UM-2 and UM-3).
It says what the code does today, what changes in the shared model, the server and the client, in what order, and what has still to be decided.

Today's code is further from the decided model than the plan's "What goes" list suggests:

- There is no single weighted total compared with a pass mark. The promoted score is the **product** of each step's weighted total, with 0 for a step whose gates failed, and the pass mark applies to that product. Serving uses a different number again.
- What the plan calls Advice doesn't exist yet: a check that isn't a gate already **fails a step** inside the conversation, so the step asks again or follows a rule.
- A new Learning Flow has **no checks at all** on the server. The defaults and the presets live only in the client.
- "Off" means the check is missing from the list. Only one bit says what a check is, a gate or not.
- Wholeness, preservation and fidelity aren't scored checks. Each is a pass or fail coded into `Conversation`, and they can't be reported as scores, because score records are keyed by `ScorerType`.

The six decisions the design needed beyond UM-2 and UM-3 were settled on 19 November 2026, each as recommended; see [Decisions](#decisions).

## Today

References are to `stroom-core-shared/.../shapeshifter/shared/` (shared), `stroom-learning-flow/stroom-learning-flow/.../ai/` (server) and `stroom-core-client/.../shapeshifter/client/component/` (client).

### Two names for one thing

- `ScorerType` names the scorers: compile, input coverage, yield, schema conformance, extraction quality and business rules. `ERROR_LOAD` and `EVENT_CLASSIFICATION` are named but have no scorer; a document that names them fails when its scorecard is built.
- `Check` names what a step is judged on. It maps to a `ScorerType` where there is one, and to null for wholeness, preservation and fidelity, "the checks that have nothing to weight or threshold" (`Check.java:208-222`).
- `StepOutcome` has one `X_SHORT` per `Check`. A step's rules (`on yield-short goto split`) name outcomes.

### Settings

- `ScorerSetting` (shared) holds `type`, `weight`, `threshold`, `gate` and `parameters`. It has no on/off state; a check that is off is absent from `ShapeshifterAiDoc.scorers`.
- `ShapeshifterAiDoc.scorers` defaults to an **empty list** (`ShapeshifterAiDoc.java:256`). The Setup tab's presets (`QualityPreset`, client only) and the readiness fix "Use the standard checks" are the only source of defaults.
- `promotionFloor` (default 0.9) is the *Pass mark* under *Approving new translations*. The client also calls each check's threshold its "Pass mark" (`ScorerList`), so one label means two things.
- `FlowStep.checks` narrows the checks that judge a step; empty means the step kind's own set.
- Settings aren't validated when a document is saved (`ShapeshifterAiStoreImpl.writeDocument` checks only the flow). A bad scorer fails at run time.

### Judging a step (`Conversation`)

- A step is judged by a scorecard narrowed to its checks:
  - SPLIT by coverage, yield and wholeness (`SPLIT_CHECKS`);
  - TARGET by the checks of meaning (schema, extraction quality, business rules);
  - CONFIGURE and ADMIT by every check.
- `Verdict.passed()` needs **every** threshold met, gate or not. A step that misses any of them falls short, with the outcome of the first failed gate, else the first missed threshold (`Scorecard.outcome`).
- Wholeness, preservation and fidelity are judged in `Conversation` itself, by `TargetChecks`, after the scorecard. Each gives its `X_SHORT` with no score. On text input a fidelity failure caused by values missing from the input is reported as `PRESERVATION_SHORT`.
- Wholeness's 90% is a constant (`TargetChecks.SPLIT_WHOLENESS`), also used by `recordElement`, which additionally refuses the root and container elements.

### Promotion (`Stage`)

- A candidate's score is the product over the chain's steps of each step's weighted total, 0 where a step's gates failed (`Stage.candidateScore`).
- It clears when that product is at least `promotionFloor` and every step's gates passed (`Judged.clearsFloor`). Thresholds that aren't gates are **not** needed here, though they were needed to get through the conversation.
- A relearn keeps the incumbent if the candidate's product is lower than the incumbent's (`Stage.java:1558`), or if the regression corpus finds a shortfall.
- "It scored N%" on the Learning Status tab is this product.

### Serving (`Stage`, `RecordGate`)

- The record bar is the document's settings whose type is in `RECORD_SCORERS` (schema, extraction quality, business rules). A record clears when no gate among them fails. In effect, then, it is those three types that are gates.
- A provisional binding is promoted while serving when the mean per-record weighted total is at least `promotionFloor` (`Stage.java:975`).
- The rolling score behind *Below par* is the running mean of per-record weighted totals over those three types, gates or not (`LearnerStates.scored`).

### The regression corpus (`Corpus`)

It runs on Improve and on relearns, never on first learning.
Each accepted record is re-run and judged with the **full scorecard**; a failed gate, a failing record, a translation that became an Unknown, or a record that now makes nothing is a shortfall, and the incumbent is kept.

### The client

- `QualityPreset` (Lenient, Standard, Strict), found by exact equality, weights included.
- `ScorerList`, `ScorerListController`, `ScorerSet`, `ScorerSettingEditor` and its controller: the check list and its dialog, with *Weight*, *Pass mark* (threshold), *Gate* and parameters.
- `SetupTab` and `SetupTabController`: the preset choice and the `promotionFloor` spinner. `SetupSummary`, `AdvancedSettings`, `QualityFigures` and `FeedSituation` show "Pass mark N%".
- `StepCard`, `ApproachSectionController`, `ApproachFlow` and `PlainWords`: a tick box per check on each step's card ("own checks"), and the client's own copy of each step kind's default checks (`ApproachFlow.judges`). That copy **differs from the server's**: ADMIT has no checks on the client and all of them on the server; TARGET omits classification; CONFIGURE is split by role on the client but not on the server.
- `Readiness`: "There are no quality checks, so every answer would pass", with the fix *Use the standard checks*.
- `StageDecisionPane`: each step's weighted total and each scorer's value.

## The design

### 1. One identity: `Check`

`Check` becomes the one name for a check, everywhere: settings, step outcomes, scores and wording.

- `ScorerType` is removed. Each scorer is registered against the `Check` it computes, and the scorer interface (`Scorer`) is keyed by `Check`.
- `ScorerParameters` subtypes are keyed by `Check`. Their JSON type names don't change.
- `TurnScore`, `StageScore` and `StageVerdict` carry a `Check`, so wholeness, preservation and fidelity can be reported like any other check.
- The new checks are added as `Check` values with their outcomes: `COMPLETENESS` (task 2), `LITERALS` and `TIME_PLAUSIBILITY` (task 3) and `REGRESSION` (the corpus).
- `COMPILE` becomes a `Check` too. Its outcome stays `COMPILE_FAILED`.
- `ERRORS` and `CLASSIFICATION` have no scorer, and are removed with their outcomes, wording and parameters (D5, decided).

### 2. Settings: `CheckSetting`

`ScorerSetting` is replaced by `CheckSetting`:

| Field | Type | Meaning |
|---|---|---|
| `check` | `Check` | Which check |
| `state` | `CheckState` | `OFF`, `ADVICE` or `GATE` |
| `threshold` | `Double` | The lowest passing value, where the check has one; null for a check that only passes or fails |
| `parameters` | `CheckParameters` | The check's own settings (yield's ratio, business rules' assertions, time plausibility's window and time field, …); null where it has none |

- `weight` goes. `gate` becomes `state`.
- **Every check has a row.** `ShapeshifterAiDoc.checks` holds one setting per `Check`; reading a document fills any missing check with its default, and duplicates are refused. Off is a state, not an absence.
- **The defaults move to the shared model**, as `CheckDefaults`, used by the server for a new document and by the client. They are UM-2's and UM-3's table:

  | Check | Default state | Default threshold |
  |---|---|---|
  | Compile | Gate (fixed) | – |
  | Schema-valid | Gate | 1.0 |
  | Completeness | Gate | – |
  | Preservation | Gate | – |
  | Regression corpus | Gate | – |
  | Input coverage | Gate | 0.9 (D4) |
  | Wholeness | Gate | 0.9 |
  | Fidelity | Gate | – |
  | No data-like literals | Advice | – |
  | Time plausibility | Advice | – (window: 1 year before, 1 day after) |
  | Yield | Off | 0.5 |
  | Extraction quality | Off | 0.7 |
  | Business rules | Off | 1.0 |

- **Compile is always a gate.** The server refuses to save a document whose compile setting isn't `GATE`; the client shows the row without a state choice.
- **Settings are validated on save and import**, beside the flow: one row per check, a threshold in [0, 1] where the check has one, parameters of the check's own type and valid by its scorer's own `validate`.
- `promotionFloor` goes, with its *Pass mark* field. `relearnThreshold` stays (*Below par*).
- The presets move to the shared model and are redefined over the three states. *Standard* is the defaults. *Lenient* and *Strict* are as in [D6](#d6-lenient-and-strict).

### 3. Judging a step

**Which checks apply.** A step is judged by every check that isn't Off and has something to measure on it. `FlowStep.checks` goes, and so do `SPLIT_CHECKS`, the TARGET step's narrowing to the checks of meaning, and the client's `ApproachFlow.judges`.

"Has something to measure" is the scorer's own answer (`Scorer.score` returning empty), which most scorers give already: schema, extraction quality and business rules measure only transform output; coverage only a parser with record ranges. The checks judged in `Conversation` today become scorers with the same rule written down:

| Check | Measures on |
|---|---|
| Wholeness | SPLIT (XML, JSON and text) |
| Preservation | A parser CONFIGURE step, once targets are agreed |
| Fidelity | The last element's CONFIGURE step, once targets are agreed |
| Completeness | A transform run over the held stream (task 2) |
| No data-like literals | A transform's stylesheet (task 3) |
| Time plausibility | A transform's events (task 3) |
| Regression corpus | Promotion of a relearn or an Improve (not a step) |

The one known difference from today: yield and coverage would also judge a CONFIGURE step on the parser, which today they do only if the step names no checks of its own. That is what CONFIGURE already does by default, so no change in practice.

`recordElement`'s other refusals (the root, a container) stay part of wholeness. Fidelity failures on text input that are caused by values missing from the input are still reported as `PRESERVATION_SHORT`, because the parser is what to fix.

**A step's outcome** (D1, decided):

1. A run failure is `COMPILE_FAILED` or `RUN_FAILED`, as today.
2. The first **gate** below its threshold gives its `X_SHORT`. The step asks again, or follows its rule for that outcome, as today.
3. Otherwise, the first **advice** check below its threshold gives its `X_SHORT` **only if the step has a rule for that outcome**. Without a rule, the candidate passes the step, and the advice goes with it into the feedback and beside the draft.
4. Otherwise it passes.

This keeps "a check set to Advice still routes" (a rule that names it fires) without letting advice refuse a candidate. Today any missed threshold fails the step, so an advice check that misses every time would spend the step's tries and stop the run, which is a gate in all but name.

**A rule on a check that is Off.** `LearningFlow.check()` gains a warning, "This rule uses Yield, which is off", for any rule whose outcome belongs to a check set to Off. It is a warning, not a problem: the flow is still saved and still runs. `FlowProblem` gains a severity, so the store refuses only problems, and the step card shows warnings.

### 4. Promotion

- **A candidate passes when every gate passes**, over every step's verdict on the final candidate, the held-stream checks and, for a relearn or Improve, the regression corpus. There is no total and no floor: `candidateScore`, `clearsFloor` and `promotionFloor` go.
- **The incumbent comparison goes** (D2, decided). Today a relearn is refused if its weighted product is lower than the incumbent's. With no total, the regression corpus, a gate by default, is the guard. Ruling A15 is amended to match.
- **The score people see** ("It scored 87%", the binding's score, `promotedScore`) is the average of the checks that ran on the final candidate, gates and advice alike. Each check counts once: where a check ran on several steps, its lowest value counts. A check that only passes or fails counts as 1.0 or 0.0 (D3, decided).
- **The regression corpus becomes the `REGRESSION` check.** It re-judges each accepted record with the checks that are gates and judge records, the same bar as serving (section 5), instead of the full scorecard. Its two rules of its own (a translation that became an Unknown, a record that now makes nothing) stay part of it.

### 5. Serving

- **The record bar is every check set to Gate that judges records one at a time**: schema, extraction quality and business rules when they are gates, and time plausibility if a user makes it one. `RECORD_SCORERS` goes; each check says whether it judges records (`Check.judgesRecords()`).
- **The rolling score** is the running mean, over served records, of the average of the record checks that ran on each record, gates and advice alike. It is compared with `relearnThreshold` as today.
- **A provisional binding** is promoted while serving when its records clear the gates (replacing `meanTotal >= promotionFloor`). Provisional bindings go in Track 2 step 1a; if step 1a lands first, this line goes with them instead.

### 6. Flows in JSON (the part shared with Track 2 step 1c)

Only the step-model change is done here, so the step model changes once:

- `FlowStep.checks` is removed from the class, its constructors, `withElements`, `equals`, `hashCode` and `@JsonPropertyOrder`.
- `FlowStep.format()` and `FlowStep.parse()` lose the `checks` keyword. The DSL itself goes later, in step 1c.
- The reserved `goTo` names stay as they are. Renaming them is step 1c's.

### 7. The client (Track 2 step 4's *Quality checks* part)

- `ScorerList` becomes a list of every check with a state (Off, Advice, Gate) per row; compile is shown as a gate with no choice. The *Weight* column and field go. The threshold column is renamed *Threshold*, so *Pass mark* no longer means two things.
- `ScorerSettingEditor` loses *Weight* and *Gate* and gains the state. Add and delete go, since every check has a row.
- `SetupTab` loses the *Pass mark* spinner. `SetupSummary`, `AdvancedSettings`, `QualityFigures` and `FeedSituation` stop showing the pass mark.
- `QualityPreset` moves to the shared model (section 2) and is matched over states, thresholds and parameters.
- `StepCard` loses its check tick boxes and "own checks"; `ApproachFlow.judges` and `withChecks` go. Rule conditions offer the outcomes of checks that apply to the step kind (from the shared model, so the client and server agree), and a rule on a check that is Off shows the warning.
- Under *Quality checks*, a check made a gate that judges records says "As a gate, this also holds back served records that fail it, and learns again".
- `Readiness`'s "no quality checks" goes, since a document always has compile and its defaults. It is replaced by a warning when every check other than compile is Off.
- `StageDecisionPane` shows each check's value and state instead of a weighted total.
- Workbench stories for each changed component (default, read-only, dialog OK and Cancel), run with the leak check.

## Order of work

The client stops using a field before the server removes it (AS13), and `ScorerSetting` is a shared class the GWT client compiles against.

1. **Shared and server, additive.** Add `CheckState`, `CheckSetting`, `CheckDefaults`, the new `Check` values and `Check.judgesRecords()`. Keep `ScorerSetting`, `weight`, `promotionFloor` and `FlowStep.checks` in place, read but deprecated. The server judges by the new model; a document read with only `scorers` is converted on read.
2. **Client.** Move every screen to `CheckSetting` and the shared presets; stop reading `weight`, `promotionFloor` and `FlowStep.checks`. Update the workbench stories.
3. **Remove.** Delete `ScorerSetting`, `ScorerType`, `weight`, `gate`, `promotionFloor`, `FlowStep.checks`, `SPLIT_CHECKS`, `RECORD_SCORERS`, `candidateScore`, `clearsFloor` and the read-time conversion. Dev and demo documents are reset or re-saved (AS1; N5 for the database).
4. **Then tasks 2 and 3** add `COMPLETENESS`, `LITERALS` and `TIME_PLAUSIBILITY` as scorers in this structure.

Steps 1 and 2 can be reviewed separately; step 3 is mostly deletion.

## Step 1 as built (19 November 2026)

Step 1 is in the working tree. Where it differs from the design above, the design follows the code:

- **What applies to a step stays partly in code.** SPLIT is still judged by coverage, yield and wholeness, and TARGET by the checks of meaning; these are properties of the step kind, not a person's per-step list. Only the per-step list (`FlowStep.checks`) goes, and it is still honoured until the client stops writing it (step 3).
- **Promotion also holds a candidate to the record bar.** A candidate clears when every gate passes on every step *and* every record of the stream clears the gates that judge records, which is the bar it will be served to. Without this, dropping the incumbent comparison (D2) let a relearn be promoted and then fall short on the same stream at once (`TestScenariosIncumbent`, scenario 8).
- **The score people see doesn't yet count wholeness, preservation and fidelity**, since they produce no score records. They join the average when `TurnScore` and `StageScore` are keyed by `Check` (step 3).
- **The regression corpus runs only as a gate.** As advice it has nowhere to be shown until approval evidence exists (Track 2 step 4), so it isn't run.
- **Completeness, literals and time plausibility** are added as `Check` values with their scorers in Track 1 tasks 2 and 3, not in step 1, so no check exists without a scorer.
- **Import isn't validated**, for the checks or for the flow: `AbstractDocumentStore.importDocument` doesn't call `writeDocument`. Track 2 step 1 needs an import hook for the *Approve AI Translations Automatically* permission, and the checks' validation should use the same hook.
- **Documents read with `scorers`** have their checks derived from them (a gate stays a gate, any other listed scorer becomes advice, the conversation's own checks are gates), which is how the client still writes until step 2. A document with no scorers has its stored checks, completed with the defaults.
- **The step cards hide compile and the regression corpus**, which aren't step checks, so the screens are unchanged until step 2.

## Step 2a as built (19 November 2026): the *Quality checks* section

- The check list shows a row for every check that is built (compile, coverage, yield, wholeness, schema, extraction quality, business rules, preservation, fidelity, the regression corpus), with its state, threshold and settings. *Add* and *Remove* are gone; *Edit* opens a dialog named after the check, with *State* (Off, Advice, Gate; fixed at Gate for compile), *Threshold* only where the check has one, and the check's own settings. The Error load and Event classification panels are gone with their rows (D5).
- The presets are the shared `CheckPreset`; the client's `QualityPreset` is deleted. Choosing one over the Learning Flow's own checks asks first.
- The *Pass mark* field is gone from *Approving new translations*, and from the route map, the Learning Status tab's draft line and the Quality Assurance tab's figures. The route map sums up the checks as gates and advice, e.g. "Standard · 7 gates".
- The readiness banner no longer says "no quality checks": a new Learning Flow has the Standard checks. It says "No quality check is a gate, so any answer that runs would pass" when nothing but compile is a gate, and offers the Standard set.
- The Setup tab saves `checks`, which clears any `scorers` the document had, so from here on the server reads the client's checks rather than deriving them.
- The class names (`ScorerList`, `ScorerSet`, `ScorerSettingEditor` and their controllers) are kept; renaming them to *Check…* goes with step 3's removals.

## Tests

The survey found these depend on what changes:

- **The pass mark:** `TestScenariosScoring` (floors of 0.95 and "Below the promotion floor"), `DemoDocument` (floor 0.85, used by about 12 scenario tests), and one reference each in about 15 other server and `stroom-app` tests. Each needs re-reading: a test that relied on the floor to refuse a candidate needs a gate instead.
- **Weights:** `new ScorerSetting(` appears in about 50 test files. Mechanical, once `CheckSetting` exists.
- **Step check lists:** `TestScenariosFlow` (three flows using `checks`), `TestShapeshifterAiDoc` (the round trip and `checks bogus`), `TestApproachFlow`.
- **Presets:** `TestQualityPreset`, `TestAdvancedSettings`, `TestSetupSummary`, `TestReadiness`, `TestPlainWords`.

New tests:

- One per row of the step outcome rule (section 3), including an advice shortfall with and without a rule.
- A candidate that fails only an advice check is promoted; one that fails a gate isn't, whatever its average.
- Serving holds back a record that fails a gate, not one that fails advice, and the rolling score counts both.
- Saving refuses compile not set to Gate, a duplicate row, a bad threshold and parameters of the wrong check; import is refused the same way.
- A rule on a check that is Off is warned about and saved.
- `CheckDefaults` matches the table above, and *Standard* equals the defaults.

## Effort

M to L, as the plan says (a relative size, not a duration; AS15): the server work in steps 1 and 3, the client work in step 2, and the test edits, of which the `ScorerSetting` call sites are the most numerous but the most mechanical.

## Decisions

All six were settled on 19 November 2026, each as recommended (marked **chosen**). The options are kept as the record.

### D1: what an advice shortfall does inside a step

UM-2 says advice "never decides promotion" and "still routes". Today every missed threshold fails a step.

- **A. It fails the step only where a rule names it (chosen).** Otherwise the candidate passes the step with the advice attached.
- B. It fails the step, as today. A candidate that always misses an advice check spends its tries and stops the run, so advice would decide learning in practice.
- C. It never fails the step, and rules on advice checks never fire. This contradicts "still routes".

### D2: the incumbent comparison

Today a relearn is refused if its weighted product is lower than the incumbent's (A15, "no lower than the incumbent").

- **A. Drop it; the regression corpus gate is the guard (chosen).** The corpus checks the records the incumbent was accepted on.
- B. Compare the average score. Advice checks would then help decide promotion, which UM-2 rules out.
- C. Compare gate by gate: refuse a relearn that is lower than the incumbent on any gate with a threshold. Stricter, more code, and a fix that improves one gate at the cost of another is refused.

### D3: the score people see

"The average of the checks that ran" needs two choices: how a check that ran on several steps counts, and how a pass/fail check counts.

- **A. Each check once, at its lowest value; pass/fail counts as 1.0 or 0.0 (chosen).** Simple, and a weak step is visible.
- B. Every judgement counts, so a check that ran on three steps counts three times.
- C. Leave pass/fail checks out of the average. A candidate that failed preservation could still show 100%.

### D4: input coverage's default threshold

It is a gate in the defaults, but its threshold is set per preset today (Strict 0.95; Lenient and Standard don't include it). Completeness covers what it misses.

- **A. 0.9 (chosen)**, low enough for unmatched trailers and headers.
- B. 0.95, as Strict has it.
- C. Advice, not a gate, since completeness is the stronger check.

### D5: the checks with no scorer

`ERROR_LOAD` and `EVENT_CLASSIFICATION` are named, have outcomes and wording, but no scorer; naming them breaks the scorecard.

- **A. Remove them (chosen)**, with their outcomes, wording and parameters. Simplification option F ("unfinished and unused parts") already covers them.
- B. Keep them, hidden, until they are built.

### D6: Lenient and Strict

**Chosen as proposed**, over the three states:

| Check | Lenient | Standard (defaults) | Strict |
|---|---|---|---|
| Schema-valid | Gate 1.0 | Gate 1.0 | Gate 1.0 |
| Completeness | Advice | Gate | Gate |
| Preservation | Gate | Gate | Gate |
| Regression corpus | Gate | Gate | Gate |
| Input coverage | Advice 0.8 | Gate 0.9 | Gate 0.95 |
| Wholeness | Gate 0.8 | Gate 0.9 | Gate 0.95 |
| Fidelity | Advice | Gate | Gate |
| No data-like literals | Off | Advice | Gate |
| Time plausibility | Off | Advice | Advice |
| Yield | Off | Off | Advice 0.8 |
| Extraction quality | Off | Off | Advice 0.9 |
| Business rules | Off | Off | Gate 1.0 if the site has written rules |

