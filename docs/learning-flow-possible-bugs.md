# Learning Flow: possible bugs found while checking the code comments

*Written 26 November 2026 · branch `ai_translations_server`*

On 26 November 2026 every comment in the Learning Flow code was checked against the code it describes and
rewritten. That covered the server module, impl-db, testFixtures and tests, plus the client, shared and
workbench classes in `learningflow` packages. Where a comment stated an intent that the code does not meet,
the comment was changed to describe what the code does, and **the code was left alone**. This file lists
those cases, and other suspected problems found along the way, so that each can be judged and fixed or
dismissed.

**Status** says how far each item has been checked:

| Status | Meaning |
|---|---|
| **Confirmed** | The code was read again after the review and does what this file says |
| **Reported** | Found by one review pass and not yet read a second time |

Line numbers are approximate. The comment rewrite moved some lines.

## Worth acting on

### 1. The allow-list is not re-checked when most bindings are promoted

**Confirmed.** `stroom-learning-flow/stroom-learning-flow/src/main/java/stroom/learningflow/stage/Learning.java`
(`bind()`, `refusedByTheList`) and `stage/Promotion.java`.

The design says that the list of XSLT functions a learned stylesheet may use is checked again whenever a
stylesheet is promoted, whoever wrote it, so that a hand edit adding a refused function stops the
promotion. In the code, `refusedByTheList` is called only from `Learning.bind()`, which is the path for a
first learning. The following paths never re-check the list:

- a relearning (`relearnUnderClaim` → `writeDraft` → `promote`);
- an improvement (`improveUnderClaim` → `writeDraft` → `promote`);
- approving a draft (`Promotion.approve`);
- the new snapshot taken after a hand edit (`Promotion.freshened`).

These paths rely only on the `PERMITTED` scorer at judging time. A person who hand-edits a draft to add a
refused function and then approves it is not stopped. Nothing enforces the allow-list while a stylesheet
runs either (see item 2 and `scoring/PermittedScorer.java`), so this check is the only guard.

### 2. `XPathCalls` is never used, so call detection is still a text scan

**Confirmed.** `stroom-learning-flow/stroom-learning-flow/src/main/java/stroom/learningflow/transformation/XPathCalls.java`.

Nothing references the class. Its comment explains that function calls in a stylesheet must be found with
Saxon's XPath parser, because a text scan cannot reliably handle `Q{…}` names, XPath comments and `=>`
arrows. `LearnedStylesheetCheck` still finds calls with its `CALL` and `REFERENCE` regular expressions. If
the parser-based reading was meant to replace the regexes, it was never wired in. This needs a security
review. A function call the regexes miss would pass the allow-list check in item 1.

### 3. `Records.counted` does not refuse a DOCTYPE in model output

**Confirmed.** `stroom-learning-flow/stroom-learning-flow/src/main/java/stroom/learningflow/scoring/Records.java:107`.

It parses with `SAXParserFactoryFactory.newInstance()`, which turns on secure processing but does not
refuse a DOCTYPE. `OutputRecords` and `SchemaConformanceScorer` use `ConfinedXml`, which does refuse one.
This is the same finding as part of N9 in `learning-flow-security-review.md`, which also names
`RecordSkeleton.java:147`. It is repeated here because the class comment claimed the opposite: it said
`counted` used "the hardened reader every other parse in this module uses".

### 4. Stepping "as processed" shows the current fragment, not the snapshot

**Reported.** `stroom-learning-flow/stroom-learning-flow/src/main/java/stroom/learningflow/stage/Stage.java`
(`dryRun(..., asProcessed = true)`).

The parameter's comment said it behaves like `reprocess`. `reprocess` runs the code recorded in the
snapshot. `dryRun` calls `judging.judge(...)` with no snapshot code, so it runs the fragment as it is now.
A relearning rewrites the fragment in place. After one, the stepper's as-processed view shows what the
new fragment produces, not what the stream was processed with.

### 5. The in-memory attempts let any node claim a parked attempt

**Confirmed.** `stroom-learning-flow/stroom-learning-flow/src/testFixtures/java/stroom/learningflow/state/InMemoryAttempts.java`
(`claimed`, around line 122).

`AttemptsDao` and its comments say a PARKED attempt cannot be claimed by the worker. Its WHERE clause
allows only AWAITING_MODEL, or IN_PROGRESS on the same node or with an expired lease. `InMemoryAttempts`
counts PARKED as open and rejects only an IN_PROGRESS attempt held by another node, so it lets any node
claim a PARKED attempt. Tests built on the in-memory version can therefore pass on behaviour the database
version does not have.

A smaller difference of the same kind: `InMemoryLearnerStates.reset()` creates a row when the learner has
none, and `LearnerStatesDao.reset()` deliberately does not.

### 6. Two tests cannot fail

- **Confirmed.** `stroom-learning-flow/stroom-learning-flow/src/test/java/stroom/learningflow/doc/TestLearningFlowStoreImpl.java:111`:
  the check described as "polymorphic parameters survive the store" compares the parameters of
  `getChecks().get(6)`. In `LearningFlowFixture.CHECKS`, index 6 is QUALITY, whose parameters are null.
  The `BusinessRulesParameters` are at index 7 (RULES). The assertion compares null with null.
- **Confirmed.** `stroom-app/src/test/java/stroom/learningflow/TestScenario18LearnsACsvFeedInAPipeline.java:167`:
  the check that the operator's document is untouched by learning compares
  `learningFlowStore.readDocument(doc)` with `learningFlowStore.readDocument(doc)`. It needs to compare
  with a copy read before learning.

## Smaller items

### 7. `AttemptsDao` ignores the time it is given

**Confirmed.** `stroom-learning-flow/stroom-learning-flow-impl-db/src/main/java/stroom/learningflow/impl/db/AttemptsDao.java`
(around lines 112, 209 and 426, and the `UPDATE_TIME_MS` updates).

`opened(...)`, `reopened(...)` and others take a `nowMs` and then stamp rows with
`System.currentTimeMillis()`. The interface says `nowMs` must come from the same clock as the lease
expiry. This only matters with an injected clock, for example in tests that move time on.

### 8. Remove withdraws one guidance note when several are selected

**Confirmed.** `stroom-core-client/src/main/java/stroom/learningflow/client/component/GuidanceEditor.java:58`
and `GuidanceEditorController.java:144`.

The grid is built with `addDefaultSelectionModel(true)`, which allows several rows to be selected.
`remove()` withdraws only `getSelected()`. The old comment ("one thing said at most") suggests single
selection was intended.

### 9. `QualityTabController.readOnly` is never read

**Confirmed.** `stroom-core-client/src/main/java/stroom/learningflow/client/component/QualityTabController.java:78`.

The comment said an open issue is not offered for resolution when the pipeline is read-only. The field is
set in `watch(Contexts.READ_ONLY, ...)` and nothing reads it. Either the feature is unfinished or the field
is dead.

### 10. The truncation note is lost when there are other diagnostics

**Reported.** `fragment/PipelineFragmentRunner.java` (`run`) and `element/PipelineStepRunner.java`
(`truncated`), under `stroom-learning-flow/stroom-learning-flow/src/main/java/stroom/learningflow/`.

When the capture is truncated, the "first N records are judged" note is passed as the `failure` argument
to `PipelineRuns.diagnostics`. That method adds the failure only when no other diagnostic exists, so the
note disappears whenever an element has reported anything.

### 11. Fidelity may never match for a target schema other than event-logging

**Reported.** `stroom-learning-flow/stroom-learning-flow/src/main/java/stroom/learningflow/learning/TargetChecks.java`
(`fidelity`, `sourceValues`).

Both call the one-argument `asDocument`, which always wraps the target in event-logging `Events`.
`Conversation.target()` stores the target as the model wrote it. A target for another schema that is
already a whole document, such as `<Sightings>…`, is then wrapped again, so `records()` returns the root
rather than the record. The two-argument overload, which wraps in the target schema's own root, exists
and is not used here.

### 12. A rebuilt corpus uses the first entry's header and footer for every entry

**Reported.** `stroom-learning-flow/stroom-learning-flow/src/main/java/stroom/learningflow/stage/Corpus.java`
(`stream()`).

The corpus is reassembled with the first entry's preamble and postamble. If its entries come from
streams with different headers, the rebuilt stream mixes them. This may not matter in practice.

### 13. Step ids are matched case-sensitively on one side only

**Reported.** `stroom-core-client/src/main/java/stroom/learningflow/client/component/FeedProgress.java`
(`stops()`, `of()`).

Turn step ids are lower-cased before lookup, and flow step ids (`effectiveId()`) are not. If a step id
contains capital letters, no turn matches and the progress strip falls back to the turns' own steps.

### 14. A test may not test what its comments say

**Reported.** `stroom-learning-flow/stroom-learning-flow/src/test/java/stroom/learningflow/scenario/TestServedRunEvents.java`
(`aRunCarriesTheEventsOfItsOwnRunOrNone`).

Its comments and `describedAs` text speak of "a second learner". But stream 3 goes through
`Scenarios.process(...)`, which uses `Scenarios.LEARNER`, the learner that is already bound. The script's
second set of expectations (`Feed=TURNSTILE`) is probably never used, and the test has no
`verifyExhausted()` to catch that.

### 15. `requireReceivedMs` throws a different exception from the one documented

**Reported.** `stroom-learning-flow/stroom-learning-flow/src/main/java/stroom/learningflow/transformation/StreamFacts.java`.

The comment promised `IllegalStateException`. `Objects.requireNonNull` throws `NullPointerException`. The
only caller, a test fixture, catches neither, so this is harmless today. The comment now matches the code.

### 16. Unused code

**Reported.**

- `TestImproveABindingThatIsServing.YIELDS_NOTHING` is never used.
- `LearningFlowDoc.copyOrEmpty` (private, in `stroom-core-shared`) appears to be unused.

## Design codes left in code

The comment rewrite removed every design and process code (A28, N9, §12, "Track 2 step 6" and so on) from
comments. It could not change string literals, so codes remain in:

- `describedAs(...)` assertion messages, in many test classes across the server module, impl-db, stroom-app
  and stroom-core-shared;
- `@Disabled` reasons in `TestLearnedStylesheetCheck` and `TestPipelineStepRunner` ("Security review N1 …
  Track 3 task 1");
- two `@JsonPropertyDescription` strings in `LearningFlowConfig` (A24, A26). Changing these also means
  regenerating `stroom-config/stroom-config-app/src/test/resources/stroom/config/app/expected.yaml`;
- the README text block in `DemoContentPack` ("Design 02 §5 scenario 51").

Some of these messages are also now inaccurate. For example, `"a relearning session, parked for the
worker"` in `TestScenario16…` describes a session that is awaiting the model, not parked.
