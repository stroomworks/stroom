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
./gradlew :stroom-shapeshifter:stroom-shapeshifter-ai:test
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

Open [shapeshifter-ai-delivery-plan.md](shapeshifter-ai-delivery-plan.md).

### C1. The names

Read the new **The names** section (it is the second section, after the summary).
Check you agree with:

- *Shapeshifter AI* → **Learning Flow** for the document type, and *AI Translations* → **Smart Pipelines** for the feature and for a pipeline holding the AI element.
- The note that "learning flow" deliberately keeps its second meaning (the ordered list of questions), which is what you chose. The consequence is written down: where a sentence could be read either way, it says "the Learning Flow document" or "the order of questions".
- The convention the document now follows: prose, screen names and permission names use the new names; file paths, branch names, class names, table names and the links to other design documents are left alone so they still match the code and still resolve.

### C2. The rename reached the prose

```sh
grep -n "AI pipeline" docs/shapeshifter-ai-delivery-plan.md
```

**Expect:** nothing. Every one became "Smart Pipeline".

```sh
grep -n "AI Translation" docs/shapeshifter-ai-delivery-plan.md
grep -n "Shapeshifter" docs/shapeshifter-ai-delivery-plan.md \
  | grep -v "shapeshifter-ai-\|stroom-shapeshifter\|ShapeshifterAiDoc\|ShapeshifterAiSerialiser\|shapeshifter_\|stroom/shapeshifter"
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
s = open('docs/shapeshifter-ai-delivery-plan.md', encoding='utf-8').read()
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

## F. What this does not prove

Be clear about the edges:

- **Nothing ran against a real Stroom instance.** The Stroom MCP server did not connect in this session (`getaddrinfo EAI_AGAIN ${STROOM_MCP_URL}` — the variable is not being expanded), so the dev-instance item still outstanding from Track 1 task 1, re-learning stream 2152 on `OTEL-TRACES`, is still outstanding.
- **No full build.** `./gradlew clean build shadowJar buildDistribution` and `./buildDockerImages.sh` are the CI gate before a pull request, and neither was run; what was run is listed in part A.
- **The three `stroom-app` failures are still there** (`TestDemoContentPackImports`), unchanged and unrelated: they are the `claimTasksOnWorker` requirement, which Track 1 task 5 removes.
- **The warning is client-side only.** A flow saved through the REST API with a rule on a check that is off is accepted in silence; nothing calls `check(List<CheckSetting>)` on the server. That is the right behaviour for a warning, but it does mean the server never mentions it.
- **No screenshot baselines were updated.** `step-card.css` changed, so if `workbenchScreenshotDiff` is part of your routine, expect the step card to differ.
- **Only two story titles were run**, the two filtered in A4. The rest of the workbench's 1,893 stories were not, though nothing outside the Shapeshifter components was touched.
- **The four changelog entries in `unreleased_changes/` still say "Shapeshifter AI"**, and are uncommitted, so they can be reworded before they reach CHANGELOG.md.
- **Stage R is written, not done.** No package, module, table, REST path or document was renamed.
- **The `stroom-app` Shapeshifter tests were not run** after step 3. They compile; the three
  `TestDemoContentPackImports` failures that predate all of this are unchanged and unrelated.
