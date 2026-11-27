# Shapeshifter AI: test protocol for the 19 November 2026 work

*Written 19 November 2026 · branch `ai_translations_server` · for the user to check what was done*

This protocol covers four pieces of work, in the order they were done.
Each part says what to run, what you should see, and what it proves.
Everything is uncommitted and unstaged, as `AGENTS.md` requires.

| # | Part | What it covers |
|---|---|---|
| A | [The build and the tests](#a-the-build-and-the-tests) | Everything, mechanically |
| B | [The `NoGates` story](#b-the-nogates-story) | The story that was failing when the container was rebuilt |
| C | [The plan, renamed](#c-the-plan-renamed) | *Smart Pipelines* and *Learning Flow* in the delivery plan, and stage R |
| D | [Task 10 step 2b: the step cards](#d-task-10-step-2b-the-step-cards) | Per-step checks removed; the warning on a card |
| E | [Task 10 step 2c: the decision pane](#e-task-10-step-2c-the-decision-pane) | Each check's value and state, not a weighted total |
| G | [Task 10 step 3](#g-task-10-step-3-complete-20-november) | What went, and the two judgement calls in it |
| H | [Track 2 step 2](#h-track-2-step-2-the-smart-pipelines-list-20-november) | The Smart Pipelines list, and the four judgement calls in it |
| I | [Track 1 task 3](#i-track-1-task-3-the-missing-year-20-november) | The missing year, and the decision N6 still needs |
| F | [What this does not prove](#f-what-this-does-not-prove) | The gaps, so you know what is still untested |

Allow about 25 minutes for parts A to E, most of it waiting for part A.

## A. The build and the tests

Run these from the repository root, in order.
The first is slow (a GWT compile); the rest are seconds.

### A1. The unit tests

```sh
./gradlew :stroom-core-shared:test :stroom-core-client:test
```

**Expect:** `BUILD SUCCESSFUL`.

To see that the new tests really ran, rather than being skipped:

```sh
python3 -c "
import xml.etree.ElementTree as ET, glob
for pattern in ('stroom-core-shared', 'stroom-core-client'):
    for fn in sorted(glob.glob(pattern + '/build/test-results/test/*.xml')):
        if any(k in fn for k in ('LearningFlow', 'ApproachFlow', 'LearnFlow', 'PlainWords')):
            r = ET.parse(fn).getroot()
            print('%-55s tests=%s failures=%s errors=%s' % (
                fn.split('/')[-1], r.get('tests'), r.get('failures'), r.get('errors')))
"
```

**Expect** four classes, no failures and no errors:

| Class | Tests | What is new in it |
|---|---|---|
| `TestLearningFlow` | 6 | All six: the whole class is new |
| `TestApproachFlow` | 22 | `aStepsChecksAreItsKindsWhateverTheStepSays` |
| `TestLearnFlow` | 8 | `aStepsWarningsAreKeptApartFromItsProblems` |
| `TestPlainWords` | 9 | None; it was changed to stop using a deleted method |

### A2. Checkstyle

```sh
./gradlew checkstyleMain checkstyleTest
```

**Expect:** `BUILD SUCCESSFUL`.
(It prints many jOOQ deprecation warnings from unrelated modules; those are pre-existing.)

### A3. The UI guard tests

These are the ones that catch a UiBinder field with no Java field, a BEM class with no stylesheet rule, and a GWT widget used where a Stroom one should be.
They matter here because a `.ui.xml` and a stylesheet were both edited.

```sh
./gradlew :stroom-core-client:test \
  --tests '*TestTemplateBindings*' --tests '*TestStylesheets*' --tests '*TestStroomWidgetsOnly*'
```

**Expect:** `BUILD SUCCESSFUL`, 11 tests in total, none failing.

> If you run these with a bare `./gradlew test --tests ...` instead, every module without a matching test reports `FAILED`.
> That is the filter finding nothing, not a real failure. Name the module, as above.

### A4. The workbench stories

This is the long one: about 2 minutes, most of it the GWT draft compile.

```sh
./gradlew :stroom-gwt:stroom-gwt-workbench:workbenchTest \
  -PworkbenchTestFilter='App/Editors/ShapeshifterAiEditor' -PworkbenchLeakCheck
```

**Expect:**

```
PASS  App/Editors/ShapeshifterAiEditor (38 stories, …)
Tests:       38 passed, 38 total
…
165 of 165 checks passed
BUILD SUCCESSFUL
```

38, not 36: two stories were added in part D.
The `165 of 165` line is the browser self-test, which is what proves Chromium and Playwright are working rather than silently skipping.

Then the decision pane's own stories:

```sh
./gradlew :stroom-gwt:stroom-gwt-workbench:workbenchTest \
  -PworkbenchTestFilter='App/Main/StageDecisionPane' -PworkbenchLeakCheck
```

**Expect:** `PASS`, 2 stories.

### A5. The server module

`LearningFlow` and `FlowProblem` are in `stroom-core-shared`, and the server reads them (`ShapeshifterAiStoreImpl:60`, `Conversation:268`), so the module's own tests should run against the changed shared model:

```sh
./gradlew :stroom-learning-flow:stroom-learning-flow:test
```

**Expect:** `BUILD SUCCESSFUL`. This was added after the Fable review pointed out that part A compiled the server but never re-ran its tests.

> **A trap worth knowing.** Piping Gradle through `tail` or `head` hides its exit code, so a failed run looks like it passed. Use `; echo "EXIT=${PIPESTATUS[0]}"`, or read the `PASS`/`FAIL` line rather than the exit status. This caught me out once in this session.

## B. The `NoGates` story

This is the story that was failing when you rebuilt the container.
It is covered by A4, but here is what was wrong, so you can judge the fix.

The story applies the *Standard* checks to a Learning Flow that had none, then looked for the readiness banner counting `"6 quality gates"`.
Six is right for the Standard set, but the text is never shown: `FeedsTabController:72` hides the whole banner once nothing stops the document learning.
The count only appears while something *else* is still wrong, which is why the neighbouring `NotReady` story can assert it — that document has no model.

The first fix (asserting the text was absent from the DOM) was also wrong: `ReadinessBanner` keeps its words when the tab hides it, so the node is still there. The assertion is now about what is *seen*:

```java
play.waitFor(() -> play.expect(play.getByText("This document won't learn anything yet"))
        .not().toBeVisible());
```

**To check it yourself**, open `ShapeshifterAiEditorStories.java` and read the `NoGates` story (search for `.story("NoGates"`).
Nothing in the product changed for this: it was the story that was wrong.

## C. The plan, renamed

Open [learning-flow-delivery-plan.md](learning-flow-delivery-plan.md).

### C1. The names

Read the new **The names** section (it is the second section, after the summary).
Check you agree with:

- *Shapeshifter AI* → **Learning Flow** for the document type, and *AI Translations* → **Smart Pipelines** for the feature and for a pipeline holding the AI element.
- The note that "learning flow" deliberately keeps its second meaning (the ordered list of questions), which is what you chose. The consequence is written down: where a sentence could be read either way, it says "the Learning Flow document" or "the order of questions".
- The convention the document now follows: prose, screen names and permission names use the new names; file paths, branch names, class names, table names and the links to other design documents are left alone so they still match the code and still resolve.

### C2. The rename reached the prose

```sh
grep -n "AI pipeline" docs/learning-flow-delivery-plan.md
```

**Expect:** nothing. Every one became "Smart Pipeline".

```sh
grep -n "AI Translation" docs/learning-flow-delivery-plan.md
grep -n "Shapeshifter" docs/learning-flow-delivery-plan.md \
  | grep -v "learning-flow-\|stroom-learning-flow\|ShapeshifterAiDoc\|ShapeshifterAiSerialiser\|shapeshifter_\|stroom/shapeshifter"
```

**Expect a handful of hits, all deliberate** — not nothing. (My first draft of this protocol said "should all return nothing", which is wrong; the Fable review caught it.) What should come back is only:

- the ***Was*** column of the names table, which has to print the old names to say what they were;
- the *AI Translator* element in R2, and `ShapeshifterAiElements` in R2b — the element keeps its display name, and its type id is a decision;
- the Stage R work list, which names the files and symbols it is going to rename.

Anything else is a miss. The second grep's filter drops the mentions that are deliberately left alone until Stage R: document filenames, module paths, class names and table names.

Renamed along the way, which you may want to check reads correctly:
*Approve AI Translations Automatically* → *Approve Smart Pipelines Automatically*, the "hard-coded AI Translations list" (Track 2 step 2, and its heading and three anchors) → "Smart Pipelines list", and every "AI pipeline" → "Smart Pipeline".

### C3. Every link still resolves

```sh
python3 - <<'PY'
import re, os
s = open('docs/learning-flow-delivery-plan.md', encoding='utf-8').read()
slugs = set()
for line in s.splitlines():
    m = re.match(r'^#{1,6}\s+(.*)$', line)
    if m:
        t = re.sub(r'`([^`]*)`', r'\1', m.group(1))
        t = re.sub(r'\*\*?([^*]*)\*\*?', r'\1', t).lower()
        t = re.sub(r'[^\w\s-]', '', t)
        slugs.add(re.sub(r'\s+', '-', t.strip()))
bad = []
for m in re.finditer(r'\[([^\]]+)\]\(([^)]+)\)', s):
    target = m.group(2)
    if target.startswith('http'):
        continue
    path, _, frag = target.partition('#')
    if path:
        if not os.path.exists(os.path.join('docs', path)):
            bad.append('MISSING FILE: ' + target)
    elif frag not in slugs:
        bad.append('MISSING ANCHOR: #' + frag)
print('\n'.join(sorted(set(bad))) or 'all links resolve')
PY
```

**Expect:** `all links resolve`.

### C4. Stage R

Read the new **Stage R: the rename** section, near the end, and the row added to the stage table in the summary.

The part to judge is the **timing**, which is an argument, not a given:

> After Track 2 step 6, before the first release.

Not before step 6, because `ai_translations_egress` was cut under the old names and already conflicts in `XsltStep` and `OutputRecords`; renaming first turns a reconcilable merge into a rewrite.
Before release, because assumption AS1 only holds until then — today the 12 tables can be renamed by editing the unreleased `V08_00_00_001` in place (decision N5 option A) and the document type's id can change freely; afterwards the same rename needs a mapping migration, a document-type conversion per installation, and a deprecation period for the REST paths and the config key.

**Three decisions are left open deliberately** (R1–R3 in that section), because they are yours:
the exact package and module names (recommended: `stroom.learningflow`, `stroom-learning-flow`);
whether the pipeline element keeps the display name *AI Translator* (recommended: yes) — the only user-visible wording the rename would change that the UI rework did not settle;
whether the **element type id** changes, which is a different string from the document type's and is embedded in stored pipelines (R2b, added after the review);
and whether the document directories move.

Also added: a row in **Where the tracks meet** about the rename colliding with any open branch, and a line in **Ready to start now** saying to write new code under the old names until stage R runs, so the rename stays one mechanical change.

**No code was renamed.** That is stage R's job, and it is scheduled, not done.

## D. Task 10 step 2b: the step cards

A step of a learning flow no longer names checks of its own.

### D1. What went

```sh
grep -rn "withChecks\|usualChecks\|setOnChecks\|ownChecks" --include='*.java' \
  stroom-core-client/src/main stroom-core-shared/src/main
```

**Expect:** nothing.
Gone with them: the *Choose its own checks* tick box and the per-check tick boxes from `StepCard` and its `.ui.xml`, the `step-card__judged` and `step-card__note` rules from `step-card.css`, `ApproachFlow.withChecks`, the `"own checks"` tag on a step's heading, and `PlainWords.check(Check)` and `PlainWords.usualChecks(QuestionKind)`, which nothing used afterwards.

`ApproachFlow.judges(step)` — which returned the step's own checks where it named any — is now the public `ApproachFlow.checksFor(step)`, which always returns what the step's kind can measure.
That is what the rule-condition menu is built from, so the conditions a rule may fire on now come from the checks that apply to the step kind, as the plan asks.

> **`FlowStep.checks` itself is still there**, and still read and written. Removing the field is task 10 **step 3**, along with `ScorerSetting`, the weights, `promotionFloor` and the `Scorer*` → `Check*` rename. Step 2b only stops the client using it, which is the ordering rule AS13 requires: the client stops using a feature before the server removes it.

### D2. The warning, in the real UI

This is the part worth seeing rather than reading. Serve the workbench:

```sh
./gradlew :stroom-gwt:stroom-gwt-workbench:workbenchServe
```

Open <http://localhost:6008>, then find **App / Editors / ShapeshifterAiEditor** in the list and open the story **Approach Rule On Check That Is Off**.

**You should see**, on the Setup tab with step 2 chosen, under the step's heading:

> The rule 'if the records don't read all the input' on step 2 never fires: the coverage check is off

in the warning colour (`--warn`, amber), *not* the problem colour (`--invalid`, red).

Then check the two things that make it a warning rather than a problem:

- **The Save button is still enabled.** A warning does not stop the document being held. Switch to the neighbouring story **Approach Rule On Check That Is On** (the same flow, with coverage a gate) and the line is absent.
- The fixture behind it is `ShapeshifterAiFixtures.RULE_ON_OFF_CHECK_DOC`: the standard document with coverage forced to `OFF`, while its second step keeps its rule on `COVERAGE_SHORT`.

### D3. Why a warning and not a problem

`FlowProblem` gained a `Severity` of `PROBLEM` or `WARNING`, which is what lets a step's card paint a warning differently from a problem, and keeps warnings out of `stepProblems`, which drives the route map and "the approach has a problem".

**A correction, found by the Fable review.** I first told you the severity was what stopped a switched-off check making the document unsaveable. That was wrong, and the javadoc said so too until it was corrected. `ShapeshifterAiStoreImpl:60` does refuse a save using `getFlow().problems()`, but `problems()` asks the *no-argument* `check()`, which raises no warnings at all — only the new `check(List<CheckSetting>)` overload does, and only the Setup tab calls it. So the store never sees this warning whatever its severity. The severity filter in `problems()` is now documented for what it is: defensive, so that the store would still refuse only problems if a warning ever reached that path.

`TestLearningFlow.aWarningDoesNotStopTheFlowBeingHeld` was passing trivially for the same reason, and now asserts both halves explicitly.

The warnings need the document's checks, which `LearningFlow.check()` has never had, so there is a new overload `check(List<CheckSetting>)`. `SetupTabController` keeps the approach up to date on all three paths that can change the checks: reading a document, editing the check list, and choosing a preset.

**Worth a moment's thought:** `FlowProblem.Severity` is a second `Severity` in the codebase — `stroom.util.shared.Severity` already exists, and `TestShapeshifterAiDoc` imports it. They do not clash, because the new one is nested and referred to as `FlowProblem.Severity`, but say if you would rather it were named something else.

## E. Task 10 step 2c: the decision pane

`StageDecisionPane` no longer presents a weighted total as the thing that decided a step.

Serve the workbench as in D2 and open **App / Main / StageDecisionPane**, story **Default**.

| Was | Is |
|---|---|
| `Step 1 — 0.95 (passed)` | `Step 1 — 95%, every gate passed` |
| `COMPILE` · `1.0 (met 1.0, a gate)` | `Compiles` · `100% (met 100%, a gate)` |
| `INPUT_COVERAGE` · `0.9 (met 0.8)` | `Reads all the input` · `90% (met 80%, advice)` |

Three changes: the step's figure is no longer called a total and no longer decides anything — every gate passing does, and the wording says so; each check says whether it was a **gate** or only **advice**; and values are percentages with the checks' plain names, as the rest of the client already shows them.

The figure itself is unchanged — `StepDetails:181` already passes `verdict.score()`, the average of the checks that ran (decision D3). Only its presentation moved.

> **Still misnamed:** the shared field is `StageVerdict.getWeightedTotal()`, which has not been a weighted total since step 1. Renaming it is task 10 step 3. Likewise, `StageScore` is keyed by `ScorerType`, so wholeness, preservation and fidelity — which have no scorer — still do not appear in this pane. Step 3 keys it by `Check` and they join.

## G. Task 10 step 3, complete (20 November)

Six stages, each left green before the next began. All of it is covered mechanically by part A.

| Stage | What went | How to see it |
|---|---|---|
| 3a | `FlowStep.checks`, its `checks a,b,…` DSL keyword, `SPLIT_CHECKS`, and the server's per-step narrowing | `grep -rn "getChecks()" --include='*.java' . \| grep -v build \| grep -v "doc\.\|document\."` returns nothing |
| 3b | `ERRORS`, `CLASSIFICATION`, `ScorerType.ERROR_LOAD`, `EVENT_CLASSIFICATION` and their two parameter classes (D5) | `grep -rn "ERROR_LOAD\|EVENT_CLASSIFICATION" --include='*.java' . \| grep -v build` returns nothing |
| 3c | `TurnScore`/`StageScore` keyed by `Check`; `StageVerdict.weightedTotal` → `score` | The decision pane now lists wholeness, preservation and fidelity, which have no scorer and so could not appear before |
| — | The promotion floor | `grep -rn promotionFloor --include='*.java' . \| grep -v build` returns nothing |

**Two judgement calls worth your eye:**

1. **The review asked for `checks` to be stripped on read.** It needs no code: `JsonUtil` sets
   `FAIL_ON_UNKNOWN_PROPERTIES` to false, so once the field is gone a stored document's `"checks"` is
   ignored. Removing the field *is* stripping it. Check `JsonUtil.java:193` if you want to confirm.
2. **One scenario could not be kept as it was.**
   `TestScenariosFlow.feedbackCarriedByATransitionReachesTheNextQuestionAskedWhenTheStepItNamesIsSkipped`
   relied on the transform naming fidelity as its only check so that preservation fell to it alone. The one
   model of checks makes that unexpressible — every check that can measure a step judges it. The test's
   actual subject, feedback surviving a jump to a skipped step, is unchanged; the rule simply moved from the
   transform to the parser. Two neighbouring scenarios kept their meaning a different way, by switching
   preservation off on the document (`without(flow, Check.PRESERVATION)`), which is how the new model says
   "don't judge this here". **Have a look at whether you agree with those three rewrites** — they are the
   only places where the new model forced a test to change meaning rather than form.

**3d and 3e finished the job:** `ScorerSetting`, `ScorerType`, `fromScorers`, the document's `scorers` and
the deprecated `Scorecard` constructor are gone (178 call sites across 47 files), and the client's `Scorer*`
classes are `Check*`, with their templates, stylesheet, BEM classes and DOM identities.

**The conversion was proved, not assumed.** `fromScorers` turned an unlisted scorer-backed check `OFF`,
whereas `.checks(...)` fills anything unnamed with *Standard*'s defaults — so a careless rewrite would have
changed tests' meaning while leaving them green. `DemoDocument` was converted first and the demo pack
regenerated; its documents' derived `checks` came out **byte-identical** to the previous pack, with only
`scorers` emptied. That licensed the other 46 files. To check it yourself:

```sh
grep -rn "ScorerSetting\|ScorerType\|promotionFloor\|getScorers\|getWeightedTotal\|ScorerList\|ScorerSet" \
  --include='*.java' --include='*.xml' . | grep -v build
```

**Expect:** nothing.

**A wording trap worth knowing about.** The first pass of 3d had messages reading "quality scored 0.27"
where they had always read "Extraction quality scored 0.27", because `Check.getDisplayValue()` is the
lower-case word a sentence uses. Fifteen scenario tests failed on it. `Check` now carries `getName()` — the
name its scorer had — so every message and list reads exactly as before. Worth a glance at `Check.java` to
see that the two registers are distinct and sensibly chosen.

**Still left, deliberately:** the `shapeshifter_turn.scorer` column, which now holds a `Check` name and
wants a jOOQ regeneration against a live database to rename; and `Scenarios.scorers()`, which returns the
`Scorer` SPI implementations — the code that measures a check, not the old settings.

## H. Track 2 step 2: the Smart Pipelines list (20 November)

Decision N1 option C: a learned stylesheet may use only what is on an allow-list. Covered by part A.

### H1. What it refuses, and in what words

The refusals are fed back to the model, so the wording is the product, not a detail. To see them:

```sh
./gradlew :stroom-learning-flow:stroom-learning-flow:test \
  --tests '*TestLearnedStylesheetCheck*' --tests '*TestPermittedScorer*' --tests '*TestStroomStandIns*'
```

**Expect:** `BUILD SUCCESSFUL`, 52 tests. What they pin, in the module's own words:

```
A learned stylesheet may not call 'stroom:lookup': it reads reference data beyond this stream.
A learned stylesheet may not call 'unparsed-text': it reads files from the node.
A learned stylesheet may not use 'xsl:result-document': it writes files on the node.
A learned stylesheet may not refer to a function by name and arity ('stroom:http-call#'):
  it calls a function without the call being visible.
A learned stylesheet may not call 'current-dateTime': it does not give the same answer twice,
  so judging and serving would disagree.
```

There is a test per refused group of N1's table. The two worth understanding are the last two:
`stroom:http-call#5` never appears as a call, so a check looking for `stroom:http-call(` would miss it
entirely; and `current-dateTime()` reaches nothing, but a candidate promoted on a score the serving run
cannot reproduce was promoted on a measurement that no longer exists.

### H2. It does not refuse what translations actually do

`TestLearnedStylesheetCheck.everyStylesheetTheScenariosAlreadyUsePasses` runs the check over every `.xsl`
in the scenario fixtures and requires no refusals. **This is the test that matters most**: an allow-list
that refuses the translations the engine already learns is worse than none.

### H3. The harness and a node now agree

Before this, every `stroom:` call failed in the scenario harness and succeeded on a node, so a stylesheet
could pass the suite and behave differently in a pipeline — or fail the suite for using something it was
entitled to use. `XsltStep` now stands in for the permitted functions. The case that matters:

```
stroom:format-date('Jun 17 09:06:02', 'MMM dd HH:mm:ss')  →  2020-06-17T09:06:02.000Z
```

A syslog date with no year, completed from the stream's received time — which is Track 1 task 3's whole
problem, and why `format-date` is on the list at all.

### H4. The judgement calls

Four, none of which the plan settled. They are in the work-in-progress document with the reasoning:
`PERMITTED` is a gate a Learning Flow cannot turn down; it is not counted as a *quality* gate (the banner
says 6, the route map says 8); the check refuses what it cannot read; and the harness fails a partial date
rather than reading the clock. **The name is the cheapest to change now and the dearest later** — once the
demo pack ships with `"check": "PERMITTED"` in stored documents it is a migration.

### H5. What it deliberately does not do

- **No run-time enforcement until Track 2 step 6.** N1's accepted position (assumption AS21).
- **25 of the 32 permitted `stroom:` functions have no harness stand-in**, so they pass the check and fail
  in the suite. `StroomStandIns.missing()` returns them and a test pins the list.
- **The `stroom-app` test holding the stand-ins to the real functions is not written**, and cannot be until
  task 3 sets the stream's metadata when judging — until then the real `format-date` reads the clock and
  there is nothing stable to compare against.

## I. Track 1 task 3: the missing year (20 November)

The dev-instance fault of 11 November: syslog writes `Jun 17 09:06:02` with no year, the translation
guessed one, and every event was dated three years out while passing every other check.

### I1. The test that proves the rest

```sh
./gradlew :stroom-app:test --tests '*TestJudgingTakesTheYearFromTheStream*'
```

**Expect:** `BUILD SUCCESSFUL`, 3 tests. It runs a stylesheet calling `stroom:format-date` through the
**real** pipeline, not the module's stand-in.

**I checked it bites rather than trusting it.** Removing the held stream makes it fail with:

```
Expecting actual: "2026-06-17T09:06:02.000Z" to start with: "2023"
```

That is the fault itself — the year coming from the clock instead of the stream. Worth repeating if you
want to see it: comment out the `setMeta` in `theYearComesFromTheStreamAndNotFromTheClock`.

Its second test covers the subtler half of AS6: the worker carries a batch of attempts in **one** pipeline
scope, so an attempt that did not *replace* the previous meta would inherit it silently. Its third holds
the module's stand-in to the real function, which is the comparison Track 2 step 2 could not make until
the metadata was held.

### I2. The two new checks

```sh
./gradlew :stroom-learning-flow:stroom-learning-flow:test \
  --tests '*TestTimesScorer*' --tests '*TestLiteralsScorer*' --tests '*TestResolvedZone*'
```

**Expect:** `BUILD SUCCESSFUL`, 30 tests.

Both are **advice, not gates** — they report and go back to the model but refuse nothing, until they have
run on real feeds without crying wolf. *Strict* gates both. What they say:

> Record 1's EventTime/TimeCreated is 2023-10-19T09:06:02.000Z, about 3 years before the stream was
> received (2026-11-11T09:18:19.000Z). A timestamp that does not carry its year must take it from the
> stream, with stroom:format-date(value, pattern), rather than being given one.

> The translation writes "Production" into its output, which is this stream's Environment. The next
> stream's will differ: read it with stroom:meta('Environment').

**The false positives are the part worth reviewing**, and each has a test: `'yyyy-MM-dd'` is a date
*pattern*, not a date written in — refusing it would refuse the very fix the prompt now recommends;
`concat(@when, 'Z')` is a translation writing a proper ISO instant; and values under three characters
match by accident too easily to report.

### I3. The window is wide on purpose

A year before the stream, a day after. Back-loading from an archive is ordinary and must not be punished.
So this catches **whole-year** errors, which is the clear case, and deliberately **does not** catch
time-zone errors, which are only hours out — no window tight enough for those survives back-loaded data.
For zones the tools are the prompt's rule and the `timeZone` property.

### I4. What needs your decision

N6 says the `timeZone` property is "checked as a zone ID when the pipeline is saved". **There is no hook
for that** — `PipelineStoreImpl.writeDocument` has no element-level validation, and no element in the
codebase validates in its setter. A bad zone saves and is reported as a warning the first time the
pipeline runs. The delivery plan sets out three ways forward under *A decision N6 needs*.

### I5. What was deliberately not done

- **No migration for bindings already in use.** An existing binding keeps serving; the times check reports
  against it as advice; relearning is the fix. Rewriting people's translations to take a year out would be
  changing content a site owns. The reasoning is in the task document.
- **The zone and its source are not on the Learning Status tab** (AS18) — a client change, belonging with
  Track 2 step 4.

## J. Track 1 task 2: declared skips and completeness (20 November)

The thing you decided this morning, built. A translation that legitimately makes fewer events than records
says so; one that quietly loses records no longer passes.

### J1. The decision, and what it costs

```bash
sed -n '1,20p' docs/learning-flow-declared-skips-design.md
```

Reads: option B, a marker in the output, **no cap on the skip rate**, the count shown instead. The
paragraph headed *What that accepts* is the part to disagree with if you are going to — it states plainly
that a translation can declare every record skipped and still pass.

That is not a worry; it is a test:

```bash
./gradlew :stroom-learning-flow:stroom-learning-flow:test --tests "*TestCompletenessScorer*"
```

Ten tests. `aSkipForEveryRecordStillPasses` is the hole, pinned so that it stays a choice.
`aDeclaredSkipMakesTheSumAddUp` is why the hole exists at all: without it, completeness fails every feed
that has a header line, and a check people switch off protects nobody.

### J2. A skip is counted, never confused with an event, and never written

```bash
./gradlew :stroom-learning-flow:stroom-learning-flow:test --tests "*TestDeclaredSkips*" --tests "*TestRecordGateSkips*"
```

Eleven tests between them. The ones to read:

- `aSkipIsNotAnEvent` — the two are counted apart, which is the whole arithmetic.
- `anElementOfThatNameInAnotherNamespaceIsAnEvent` — a target schema with its own `Skipped` element is
  unaffected, which is why the marker has a namespace of its own.
- `theDownstreamNeverSeesAMarker` — the writer drops it by never being given it.
- `strippingTakesAnythingWrittenInsideAMarkerWithIt` — a model that puts an event *inside* a marker does
  not get it validated, counted or written.

### J3. The bug it turned up

```bash
./gradlew :stroom-learning-flow:stroom-learning-flow:test --tests "*TestRecordJoin*"
```

`RecordJoin` puts the per-record outputs back together as one document, and passed on only the **first**
record's namespace declarations. An element a later record declared its own namespace for was written
with no binding at all and read back in whatever namespace surrounded it. The skip marker is the first
thing to use a namespace that varies record to record, which is why nothing had found it.

Left alone, the marker would have counted as an event, failed the Schema-valid gate, and reached the
writer — the opposite of all three things it is for.

### J4. The whole module, and the scenarios

```bash
./gradlew :stroom-learning-flow:stroom-learning-flow:test
```

528 tests. Two scenarios changed meaning rather than being patched: scenario 7's filtering transforms now
**declare** the records they drop, so completeness is satisfied and yield is the one check left with
anything to say. That is the behaviour the feature asks of a real translation, so the scenario now asks it
too.

```bash
./gradlew :stroom-core-client:test
```

The readiness banner says **7 quality gates** and the route map **9 gates** — one more of each, which is
completeness.

### J5. What is not done, and why it matters more than usual

The count does **not** appear on the Learning Status tab, nor beside the daily volume on the Quality
Assurance tab. Both are client work for Track 2 step 4 (the volume row would also need a column on
`shapeshifter_output`).

Normally that would be an ordinary piece of sequencing. Here it is not: **you declined the skip-rate cap
on the grounds that the count would be visible**, so until those two surfaces exist, the mitigation is
thinner than the decision assumed. What a person reads today is the line on the error stream —

> `312 event(s), 4 record(s) declared skipped by the translation: header line`

— and the `ShapeshifterAiSkipped` attribute on the output stream. If you want the cap after all, or want
the UI part brought forward, now is the cheap moment to say so.

## K. Track 1 task 2, fix 1: the stream the translation stopped reading (20 November)

The fault the whole task is named after. Until today a serving run could only say what came **out**, so a
stream whose parser had stopped matching anything looked exactly like a stream with nothing in it — and
was recorded as clean, at the score the translation had on the day it was promoted.

### K1. The six tests that are the fix

```bash
./gradlew :stroom-learning-flow:stroom-learning-flow:test \
  --tests "*TestScenario64TheStreamTheTranslationStoppedReading*"
```

Read them in this order; they are the argument, not just the cover:

1. `aStreamTheParserCanNoLongerReadIsDeferredRatherThanRecordedAsClean` — the dev instance's fault.
2. `recordsThatBecameNeitherAnEventNorADeclaredSkipDeferToo` — the quieter half, caught by completeness.
3. `aCleanStreamIsStillServedAndItsRowHoldsThisRunsScore` — **the regression this fix could most easily
   cause.** A healthy stream deferred costs a relearning on every stream of a working feed.
4. `aFeedTheParserHasStartedReadingBadlyIsHeldBackWhereCoverageIsAGate` and
5. `andIsServedAnywayWhereTheLearningFlowMakesCoverageAdvice` — the same stream, two Learning Flows, two
   answers. This is the one to argue with if you want to.
6. `aStreamStopsOpeningPaidSessionsOnceTheTranslationHasNotMoved` — the money one.

### K2. Records, not characters — the decision you made this morning

Learning measures coverage as a share of **characters** (A36). That cannot be counted over a streamed
input without estimating it, so serving counts the **records the parser cut**, exactly. The two are not
the same number but they measure the same thing, and they come out close: on the scenario whose feed
carries six unmatched alarm lines, characters give 0.695 and records 0.700.

The thing to check is that the two runners count it the same way, or a green unit test proves nothing
about production:

```bash
./gradlew :stroom-app:test --tests "*TestFragmentRunnersAgree*"
```

`bothRunnersCountWhatTheParserMadeOfTheInputTheSameWay` runs the same drifted stream through the stand-in
and through the real pipeline and requires 4 records read and 2 refused from both.

### K3. What defers, and what deliberately does not

| | |
|---|---|
| The parser read **nothing** of a stream that held content | Defers **whatever the checks say** |
| Records read that became neither an event nor a declared skip | Defers where **completeness** is a gate |
| The parser reported records it could not read | Defers where **input coverage** is a gate |

The first is the one to question. It is not a quality threshold — it is the translation not working here
at all — and it is fixed for the same reason the compile gate is. Had it been left to the coverage check,
**the dev instance's own fault would still not be caught**, because `DemoDocument` has input coverage off.

### K4. What it fixed on the way

- **The output row lied.** Every served stream recorded the binding's *promotion* score, so an output
  history could never show a translation getting worse. It now records the run's own score, and
  `Supervision` writes the stream's attributes **after** the stream is served, so a deferred stream
  writes none at all.
- **The relearn loop had no bound.** A feed whose format has changed for good may never get a candidate
  the corpus will accept; the deferred task retries every five minutes, and each retry opened a new paid
  session. Three against one translation, then it waits for a person.

### K5. The skip count, where a person reads it

The last of task 2, and the part that mattered most, since you declined the cap on the grounds that the
count would be visible:

```bash
./gradlew :stroom-core-client:test --tests "*TestFeedSituation*" --tests "*TestQualityFigures*"
./gradlew :stroom-learning-flow:stroom-learning-flow-impl-db:test
```

- The Learning Status tab's row now reads `Scores 98% · 96 of 1,204 skipped`.
- The Quality Assurance tab says *"… 18 of those records were declared skipped by the translation."*
- A translation that skipped nothing says nothing, so the figure means something when it appears.
- `skipsGoWithTheRecordsTheMemoryForgets` is the one to read: the skips are kept over the **same rolling
  memory as the score**, so a translation that skipped badly once stops looking as though it still were.

Two columns went into the unreleased migration (`rolling_skips`, `skip_count`) and jOOQ was regenerated.
The 54 database tests run against a real MySQL and pass, so the migration and the columns are proved, not
merely compiled.

> **To regenerate jOOQ in this container:**
> `./gradlew :stroom-learning-flow:stroom-learning-flow-impl-db:generateJooq -PjooqDbHost=stroom-all-dbs:3306`
>
> `-PjooqDbHost` used to move only the root connection while the migration and the generator still went
> to `localhost:3307`, so this was impossible before today. That is fixed in `gradle/jooq-codegen.gradle`.

## L. Track 2 step 3: the spend breaker and rate limit (20 November)

`simplification-options.md` §5 lists this among the things that must exist before any feed runs
unattended: *"Per-session budgets exist. A per-document and system-wide spend breaker and a request-rate
limit don't, even though spend is already recorded. Unattended learning without them has no upper bound
on cost."*

### L1. The ceilings, and why there are four

```bash
./gradlew :stroom-learning-flow:stroom-learning-flow:test --tests "*TestSpendBreaker*"
```

Seven tests. `oneDocumentCannotSpendAnothersAllowance` is the one that explains the shape: a per-document
ceiling cannot bound a cluster, because a hundred documents each inside their own are a hundred times it.
`theRateLimitIsCountedInQuestionsNotTokens` is the rate limit, for a model priced or throttled by the
call.

### L2. What it does to a stream

```bash
./gradlew :stroom-learning-flow:stroom-learning-flow:test --tests "*TestTheSpendBreakerStopsNewSessions*"
```

- No session is opened, so nothing is paid for.
- Nothing is put to the model.
- The stream **waits** and says why, naming the ceiling and that it clears when the hour rolls.
- `theSameStreamIsLearnedOnceTheHourHasRolled` — it is a pause, not a refusal.

A session already running is deliberately left to finish: it is inside its own budgets, and killing it
would waste what has already been paid for.

### L3. The default, which is the thing to argue with

```bash
grep -n "spendTokensPerHour" stroom-config/stroom-config-app/src/test/resources/stroom/config/app/expected.yaml
```

**10,000,000 tokens and 1,000 calls an hour, cluster-wide, on by default.** Taking the development
instance's 550,000 tokens to learn one feed as the unit, that is roughly eighteen whole learnings an hour
across every document. It is a backstop against a runaway rather than a budget, and it is the number most
likely to want changing once you know a model's prices. Zero turns a ceiling off, and the configuration
says in as many words what that leaves.

### L4. What is not done

The breaker's **state** is not on the readiness banner or Monitoring > Smart Pipelines, and a Learning
Flow's own ceilings have no field on the Setup tab — both are Track 2 step 4. The cluster's ceilings are
configuration and need no screen, so the safety item §5 asks for is met; a document's are set through the
document until then. What a person sees today is the waiting stream's reason on the Learning Status tab.

## M. Track 1 task 5: `claimTasksOnWorker` (20 November)

**The three red `stroom-app` tests are green.** `TestDemoContentPackImports` has failed every time it ran
this session, with *"Pipeline … holds a supervised stage … Set stroom.processor.claimTasksOnWorker to
true"*. That was the requirement this task removes.

```bash
./gradlew :stroom-app:test \
  --tests 'stroom.shapeshifter.*' --tests 'stroom.pipelinefactory.*' \
  --tests 'stroom.processor.impl.TestDeferredTask' \
  --tests 'stroom.processor.impl.TestWorkerTaskClaiming'
```

**Expect:** 67 tests, none failing.

### M1. Nothing refers to the removed types

```bash
grep -rn "SupervisedPipeline\|requireWorkerClaimingForSupervised" --include='*.java' . | grep -v /build/
```

**Expect:** nothing.

### M2. The tests that replace the refusal

- `TestASupervisedPipelineRunsInEitherMode` — a filter over an AI pipeline is made with the setting both
  off and on, including over a pipeline that only *inherits* the element from a parent. That was the
  shape the old check answered "not supervised" for whenever it could not read the parent, so it is the
  one most worth proving.
- `TestScenarioAFromNothingToServed` and `TestScenarioAStepsFourToSix`, parameterised — a whole AI feed
  end to end **with the master queue**: streams defer, learning promotes, the wake returns the tasks to
  `CREATED`, the master hands them out, they complete.
- `TestPipelineFactoryMakesPipelines.aFactoryMakesAnAiPipelineWhicheverWayTasksReachWorkers` — the case
  option H is really about, since a Content Template fires for a feed name the sender chooses.

### M3. The inspection, and what it cannot show

You decided the cluster trial becomes an inspection. §7.2 of
[the task document](smart-pipelines/remove-claim-tasks-on-worker-requirement.md) now answers its four
questions by reading the code:

> A deferral and a wake both pass `null` for the node — `DataProcessorTaskHandler:231` and
> `ProcessorTaskDaoImpl.wakeDeferredTasks:485` — and the master's fill sets `lastTaskId = 0` on **every**
> call and selects on status and filter, never on a node. A woken task is exactly what the next fill
> finds, whoever woke it.

**Read §7.3 as well.** It says what the inspection cannot show: timing and contention on a real cluster,
and whether a few thousand woken rows hold up other filters. The large-wake question is the one where
reading the code is least satisfying — it shows other filters are not starved by construction, and says
nothing about fill latency in practice.

`AS5` in the delivery plan is weakened to match, and is now the assumption most likely to be found wrong
in production rather than in test.

### M4. One test changed in a way the task document did not foresee

`aPipelineWhoseFilterIsRefusedIsDeletedAgain` relied on the AI refusal, and no natural refusal of a
*filter* could be arranged to replace it. Rather than assert against a stub, it is rewritten around the
failure that *is* natural — a factory property whose **value** names a variable the key does not hold,
which is substituted only in `writePipeline`, after the pipeline has been made. It is now
`aPipelineThatCannotBeFinishedIsDeletedAgain`, and it still shows the half-made pipeline being deleted
again.

## N. Track 2 step 3 and Track 1 task 9 (20 November)

### N1. The spend breaker — see part L

Part L covers it. The one thing to argue with is the default: **10,000,000 tokens and 1,000 calls an
hour, cluster-wide**.

### N2. Task 9: the three that matter

```bash
./gradlew :stroom-learning-flow:stroom-learning-flow:test \
  --tests "*TestSampleSizeLimit*" --tests "*TestHeldOutRecords*" \
  --tests "*TestStreamInputsEncoding*"
```

- **The sample size limit (S4).** It was cut twice — to the Learning Flow's limit, then again by a fixed
  **6,000 characters** inside the prompt. With the default of 8,192 the AI never saw the last quarter of
  its own sample *while the parser's split was judged on all of it*, and raising the limit above 6,000
  did nothing. Now one setting, honoured, and counted in **bytes** as its label always claimed.
  `theLimitIsCountedInBytesNotCharacters` is the one to read: a feed in a language that does not fit in
  one byte a character was given up to three times the sample it was set to have, and every model is
  billed by what it is sent.
- **The held-out share (S5).** `TestHeldOutRecords` — a markup or JSON document within the limit was
  shown whole, so A14 did not hold for those feeds though the editor said it did.
- **The worker's encoding (S3).** `aFeedInUtf16IsReadableAtAll` is the sharpest: read as UTF-8, a UTF-16
  stream is not text a parser could cut records from at all, and that is what the worker was learning
  from while serving decoded it correctly.

### N3. One judgement to check (S13)

```bash
grep -n "Shapeshifter Corpus" stroom-config/stroom-config-app/src/test/resources/stroom/config/app/expected.yaml
```

**Expect one line, under `metaTypes`, and none under `receive.metaTypes`.** The corpus type is now
pickable — and so *excludable* from a processor filter written over a feed alone — but is deliberately
not a type a sender may declare on receipt: nobody outside should be able to post a stream claiming to be
a translation's corpus, since the corpus is what the next translation is held to.

### N4. Everything else in task 9

A token budget of 0 meaning unlimited on the server as in the editor (S11); `asDocument` reading the root
element rather than the characters `<Events` (W1-2, `TestTargetChecks`); the chain question no longer
telling the AI that `JSONParser` produces `records:2` when it produces the XSL JSON vocabulary (W5-7);
the learnable element list pinned against the installed runners (S6, `TestLearnableElements`); and the
comments that gave A17's unbuilt redaction as the reason stream text is not stored, which A146 settled.

**Not done:** capping the sample at a share of the model's *Max context window tokens*. The plan calls it
optional, and it is a guess about tokenisation that the byte limit makes unnecessary for the fault S4 was
about.

## O. Track 1 task 4: mixed record elements (20 November)

`GATEWAY-AUDIT` could not be learned **at all** — the one feed in the demo content that simply failed.

### O1. Why it failed, and what changed

```bash
./gradlew :stroom-learning-flow:stroom-learning-flow:test --tests "*TestMixedRecordElements*"
```

Eight tests over the real fixture and all three drift files. The two to read:

- `theMixedChildrenPassTheWholenessCheckThatNoSingleNameCould` — the whole of the fault in one
  assertion. `transfer` holds 41% of the document and `authentication` 20%, so every answer naming a
  real record failed the 90% bar; the mixed children of `<batch>` pass it.
- `aContainerThatOccursOnceIsNotRefused` — the *other* half, and the subtler one. The split is judged on
  a sample; the first `</batch>` is at byte 11,318 and the default sample is 8,192, so `batch` "occurs
  once" there and the old check read a container that repeats in the stream as a container of records.

### O2. End to end

```bash
./gradlew :stroom-learning-flow:stroom-learning-flow:test --tests "*TestScenario65*"
```

The split answered `batch/*`, the boundary stored as **children of batch** — not as the element of that
name — at the right depth; and the flattened drift answered `*`, the root's own children.

### O3. The oversized-document refusal (N2 option E)

```bash
./gradlew :stroom-learning-flow:stroom-learning-flow:test --tests "*TestOneDocumentLargerThanThePrefix*"
```

A stream that is one XML or JSON document larger than the prefix is now refused, with a report saying *a
part of a document is not a document*, that a larger limit is not the answer, and what would make the
feed learnable. Before, the split passed with no boundary and everything after it was judged on nothing.

**The second test is the one that matters.** My first version refused whenever the sample failed to
parse — which caught a CSV stream of a million lines, because a chain whose first element is a transform
reads anything down that branch. That would have refused the feeds this whole feature exists for.

### O4. Not done

Re-running `GATEWAY-AUDIT` (stream 2117) on the dev instance, which needs the MCP server.

## P. Track 1 task 8: sound multi-node learning (20 November)

The audit's four W7 findings about several nodes learning and serving one learner, and the harness it
asked for. **Track 1 is complete except task 7 after this.**

### P1. The harness

```bash
./gradlew :stroom-learning-flow:stroom-learning-flow:test --tests "*TestTwoNodesOneLearner*"
```

Two caches over one store, each with a bus that reaches nobody else — the lost event. The first test
establishes the premise (B goes on serving what it last read); the other three are what B then does with
a write made from that stale copy.

### P2. The four findings

```bash
./gradlew :stroom-learning-flow:stroom-learning-flow-impl-db:test --tests "*TestLearnerBindingsDao*" \
  --tests "*TestDeferralsDao*"
```

- **W7-1**, in one sentence: `expireAfterAccess` never expires a *busy* learner, because every stream
  touches it — so a node that missed the clearing event served an old binding for as long as the feed
  kept arriving, which is exactly when it matters. Both caches now also expire after write.
- **W7-2**: `aSecondLiveBindingForOneLearnerIsRefusedAndTheFirstHandedBack`. The guard is in the
  **database** as well as in Java — `shapeshifter_binding_live` — because two nodes racing should lose
  the race somewhere both can see.
- **W7-3**: `aReplaceFromACopyTheRowHasMovedPastIsRefused`, and
  `aBindingBuiltRatherThanReadIsWrittenAsBefore` beside it, so a promotion whose row was pruned is still
  not lost.
- **W7-4**: `eachLearnerAndTransformWaitedAgainstIsNamedOnce` now includes the unstamped row.

### P3. The trade-off to look at

Including the unstamped rows means the sweep re-wakes a learner whose first-learning rows are still
waiting, every pass, until those streams are served — a deferral goes when its stream is served, not when
it is woken. Three scenario tests record that second wake (`TestScenario30DeferredWorker`,
`TestScenario54TheTaskDecision`) so it is documented rather than a surprise. Waking an already-`CREATED`
task is an update of no rows. The precise alternative — compare the row's wait against the binding's
promotion time — is right in production but untestable here, because the in-memory store uses wall-clock
time while the stage runs on a fixed clock.

### P4. Not covered

**W7-7**: `bind` spans several transactions, so a crash between the append and the close makes the next
worker treat the learner as relearning. It is not in the task's list and the audit calls it minor.

**Nothing has run on an actual cluster.** Two caches in one JVM is as close as a unit test gets.

## Q. Track 2 step 1, most of it (20 November)

### Q1. Review by default, and the permission

`TestShapeshifterAiStoreImpl` and `TestShapeshifterAiDoc` both assert `REVIEW` now, from the creator
and from the builder, so the two sources of defaults agree.

Three new tests in `TestShapeshifterAiStoreImpl`, over a store whose security context can have a
permission taken away part way through (`ShapeshifterAiFixture.restricted()`):

| Test | What it proves |
|---|---|
| `usingNewTranslationsAutomaticallyNeedsThePermission` | The save is refused with a `PermissionException` naming the permission, and the stored document is unchanged |
| `goingBackToWaitingForApprovalDoesNotNeedThePermission` | The safer direction needs only EDIT |
| `editingAFlowThatIsAlreadyAutomaticDoesNotNeedThePermission` | Only the move **to** automatic is guarded |

Because the check is in `writeDocument`, it holds on import too — which is the point of putting it
there rather than in the editor.

### Q2. One promotion path, and A58

`TestScenario56ASnapshotKeepsWhatRan.aRelearningEditsTheFragmentInPlaceAndTheStampMovesWithTheSnapshot`
is the test that holds the design to A58, and it is what made the first implementation wrong: it
asserts the rebound binding keeps the same fragment `DocRef`, that no new fragment and no new stylesheet
were written, and that the learner has exactly two snapshots. Pointing the binding at the draft's
fragment fails all four. `FragmentWriter.copyInto` passes them.

`TestScenario22ReviewMode.aRelearnedCandidateIsADraftBehindTheIncumbentUntilApproved` now asserts the
same thing for a person's approval, and that the store holds one pipeline afterwards.

### Q3. No provisional bindings

`scenario14TooFewRecordsDraftsForAPersonEvenOnAnAutomaticFlow` is the replacement for scenario 14. On an
**automatic** Learning Flow with `minRecordsToLearn(10)` and a stream of six records, it proves: the
decision is `Drafted`; the stream is not served; the five sample records are in the regression set
(A56); a second stream waits behind the draft naming it, without a second session; and after a person
approves, the binding goes live and the next stream is served.

Scenario 28 is retired with a note in `TestScenariosRetractionRelearningAndRelease`, and scenario 38
with a note in `TestScenariosBindingBeforeLearning`.

### Q4. The Pipeline Factory

Nothing is left to test: the module, its tests, the document type, the editor, the stories, the config
and the two tables are gone, and `compileJava compileTestJava` across every module passes without them.
`expected.yaml` and the swagger spec were regenerated and both lost their factory entries.

### Q5. What was run

| Check | Result |
|---|---|
| `:stroom-learning-flow:stroom-learning-flow:test` | Pass |
| `:stroom-learning-flow:stroom-learning-flow-impl-db:test` | Pass, against a real MySQL, with jOOQ regenerated twice (the `provisional` columns, then `record_spans`) |
| `:stroom-core-shared:test`, `:stroom-core-client:test` | Pass |
| `:stroom-config:stroom-config-app:test`, `:stroom-config:stroom-config-global-impl:test` | Pass |
| `:stroom-app:test --tests 'stroom.shapeshifter.*'` | Pass, 42 tests |
| The same with `stroom.config.*` in the batch | **9 failures, none of them code.** The sandbox could not clone `stroom-visualisations-dev.git` for a content pack, and the test database was torn down behind the first failure; every later failure in that JVM reads `Unknown database 'test_…'`. Re-running the Shapeshifter tests alone passes |
| `checkstyleMain checkstyleTest` across the project | Pass |
| `compileJava compileTestJava` across every module | Pass |
| Stories `App/Editors/ShapeshifterAiEditor`, with the leak check | 38 of 38 pass; browser self-test 165 of 165 |

### Q6. What this part does not prove

- **No node has approved a draft.** `copyInto` and `ContentCreator.delete` are proved by the scenario
  suite over the real document stores (`ContentStores`), not by an instance; the explorer node's
  deletion in particular runs through `ExplorerService` only in a node.
- **Nobody has been refused the permission in a browser.** The refusal is a server test; the Setup tab
  still offers the choice and will show the server's message when a save is refused.
- **A `PipelineFactoryDoc` on a dev instance** will be a document of a type the node no longer knows.
  Nothing was tried against such an instance.

## R. N9's recording and resume, and Improve on the worker (20 November)

### R1. A run resumes in its own words

`TestAskedWith` (5 tests) covers the recording: every template resolved, not only the overridden one;
the instructions as the run had them; `UNKNOWN` for a run opened before any of this; a run asked
against an older built-in version saying so; and a template the recording does not name falling back
to the built-in rather than to nothing.

The one that matters is `aRunResumesInItsOwnWordsAfterTheFlowIsEdited`: the Learning Flow's chain
template and instructions are both changed after the run opened, and `QuestionText.of(edited, asked)`
still renders the run's words while `QuestionText.of(edited)` renders the new ones.

`TestAParkedRunResumesInItsOwnWords` proves the hand-off, which is where it could still have been lost:
an `Advisors` that records what it was asked to ask with, and a worker pass over a parked attempt. If
`DeferredWorker` called `advisors.of(doc)` the capture would hold `UNKNOWN` and the test would fail.

`TestLearnerStatesAndAttemptsDao` adds the round trip against a real MySQL, including an attempt
parked and read back through `awaiting`, which is the path the worker actually takes.

### R2. Improve on the worker

| Test | What it proves |
|---|---|
| `askingOpensAnAttemptForTheWorkerAndAsksTheModelNothing` | The ask puts **nothing** to the model, parks the attempt at `AWAITING_MODEL`, records which binding it is improving and records no stream; then the worker carries it on and rebinds |
| `anImprovementWhoseRecordsHaveGoneIsClosedRatherThanAsked` | A binding retracted or pruned while the attempt waited closes it; the model is not asked about nothing |
| `anImprovementRefusesWhatOtherDecisionsHaveSettled` | The four refusals still refuse at ask time — the kill switch, a draft awaiting review, an unknown binding, no accepted records — and the relearn mark is spent when the improvement **promotes**, not when it is asked for |
| `TestScenario53TheCorpusHoldsACandidateToNoWorse` | The corpus gate still holds a candidate to no worse, now through the worker's route |
| `TestScenario53TheCorpusSurvivesARestart` (`stroom-app`) | The same in a real node, through the real `DeferredLearning` job. What the improvement came to is read back from the attempt, since the call that asks no longer returns it |

`Scenarios.improved(...)` is the harness for all of these: it asks, then carries the attempt on exactly
as `DeferredWorker` does, so the tests exercise the real route rather than a short cut past it.

### R3. What this part does not prove

- **No node has carried an improvement.** The route is proved by the scenario harness and the real
  `DeferredWorker`, not by a running instance with a model behind it.
- **Nobody has pressed the button.** The message a person now sees — "Asked. The AI is working on it"
  — is server-side; the client's wording changed with it, but the stories do not cover the Improve
  dialog's alert.
- **No prompt has been rebuilt.** Recording and resume are built; the service that rebuilds a turn's
  prompt for someone with *View Data* is moved to step 4, with the client that reads it.
- **The build version is only recorded, never read.** It is there for the caveat that step 4's rebuild
  will show; nothing shows it yet.

## S. Track 2 step 1b: the QA assessments are gone (20 November)

There is nothing new to test — the point of the step is that a feature goes — so what matters is what
still passes without it, and what a reviewer should look at.

| Check | Result |
|---|---|
| `:stroom-learning-flow:stroom-learning-flow:test` | Pass. `TestQualityService` keeps nine of its twelve tests; the four assessment tests go |
| `:stroom-learning-flow:stroom-learning-flow-impl-db:test` | Pass. `TestAssessmentsDao` deleted with its DAO |
| `:stroom-core-client:test` | Pass. `TestQualityFigures` loses `onlyAnUnresolvedIssueIsOpen` with `isOpenIssue` |
| `compileJava compileTestJava`, every module | Pass |
| `checkstyleMain checkstyleTest`, project-wide | Pass |
| Swagger regenerated | The three `…Assessment…` operations are gone from `stroom.json` and `stroom.yaml` |
| jOOQ regenerated | No `ShapeshifterAssessment` table class remains |

**Worth a reviewer's eye.** `someoneWhoMayNotViewThePipelineReadsNothing` used to prove the permission
refusal through `assessments`; it now proves it through `samples`, which is the same `readable(...)`
check, so the coverage is kept rather than quietly dropped.

**A story failure step 1 left behind, found here.** Running `App/Editors/PipelineQualityAssurance` for
this step caught `Default` still asserting the *Trusted* figure, which went with the provisional state
in step 1. Step 1's own run covered only `App/Editors/ShapeshifterAiEditor`, so nothing had exercised
this title since. The assertion is removed. **The lesson for the next removal: run every story title
that touches the screen, not only the one the work was about.**

## T. Track 2 step 1c: the default setups in JSON, and the client fetch (20 November)

### T1. The setups themselves

`TestFlowExamples` (7 tests) is what now holds the three setups to what the DSL used to spell out: each
reads, each is a flow the stage can hold (A33), and each is pinned to its shape — Direct's fallback to
the exhaustion step, Escalating's yield-short-back-to-split with its `first` and `again` ids, Target
first's parser-back-on-preservation.

The resources were **generated from the existing `FlowExample` steps**, not retyped, so the move cannot
have changed one by a typo.

### T2. The client fetch

| Check | What it proves |
|---|---|
| `TestApproachFlow.theExamplesAreRecognisedAndAnEditIsNot` | A setup is named from the fetched list, an edit is not, and **an empty list names nothing** — before the fetch lands a document reads as having its own approach, never a wrong name |
| `:stroom-core-client:test` | Pass. `Readiness`, `SetupSummary` and the route map all take the fetched list |
| `App/Editors/ShapeshifterAiEditor` stories | The Setup tab draws with the setups stubbed on `GET /shapeshifterAi/v1/flowExamples` |

`FlowExampleFixtures` is the client's own copy of the three setups, since a unit test cannot reach the
server's resources. What the setups *hold* is pinned server-side by `TestFlowExamples`; the client
fixture is there for the client's own job, which is matching and loading.

### T3. What this does not prove

- **No browser has fetched them.** The endpoint and the cache are proved by unit tests and by stories
  with a stubbed route; nothing has watched a real node serve them.
- **The failure path is untested.** `FlowExamplesCache` leaves the setups unfetched when a request
  fails so the next ask retries; no test exercises that, and no story stubs a failing route.
- **Nothing is removed yet.** `FlowExample.steps()` and `LearningFlow.of(FlowExample)` still exist and
  the server and tests still use them, so the client fetch is additive: if it failed, the server would
  carry on exactly as before.
- **The client is clean of them in its own code, but not through `LearningFlow`.** No production class
  in `stroom-core-client` reads `FlowExample.steps()` any more, but `LearningFlow`'s constructor falls
  back to `FlowExample.DIRECT.steps()` for null steps, and `LearningFlow` is compiled into GWT. A flow
  deserialised without steps still becomes Direct in the browser. Settling that belongs to the shared
  cleanup, because the fallback is what makes a step-less document usable until the store fills a new
  document's steps on create.

## F. What this does not prove

Be clear about the edges:

- **Nothing ran against a real Stroom instance.** The Stroom MCP server did not connect in this session (`getaddrinfo EAI_AGAIN ${STROOM_MCP_URL}` — the variable is not being expanded), so the dev-instance item still outstanding from Track 1 task 1, re-learning stream 2152 on `OTEL-TRACES`, is still outstanding.
- **No full build.** `./gradlew clean build shadowJar buildDistribution` and `./buildDockerImages.sh` are the CI gate before a pull request, and neither was run; what was run is listed in part A.
- ~~**The three `stroom-app` failures are still there**~~ — gone, 20 November 2026: Track 1 task 5 removed the `claimTasksOnWorker` requirement that caused them.
- **The warning is client-side only.** A flow saved through the REST API with a rule on a check that is off is accepted in silence; nothing calls `check(List<CheckSetting>)` on the server. That is the right behaviour for a warning, but it does mean the server never mentions it.
- **No screenshot baselines were updated.** `step-card.css` changed, so if `workbenchScreenshotDiff` is part of your routine, expect the step card to differ.
- **Only two story titles were run**, the two filtered in A4. The rest of the workbench's 1,893 stories were not, though nothing outside the Shapeshifter components was touched.
- **The four changelog entries in `unreleased_changes/` still say "Shapeshifter AI"**, and are uncommitted, so they can be reworded before they reach CHANGELOG.md.
- **Stage R is written, not done.** No package, module, table, REST path or document was renamed.
- **The `stroom-app` Shapeshifter tests pass in full** as of task 5.
- **No cluster was used.** Task 5's trial is an inspection; see M3 and §7.3 of its task document.
- **No drifted stream has been through a real node.** `TestFragmentRunnersAgree` proves the two runners
  count the input the same way, which is the thing most likely to differ; nothing has watched a pipeline
  defer a live stream.
- **No declared skip has been through a real pipeline.** The marker is proved by unit tests and by the
  scenario suite's stand-in runner; `PipelineFragmentRunner` uses the same `RecordGate`, so it counts and
  drops the same way, but nothing has watched a node do it.
