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

## Found by running the scenario data

These were found on 26 November 2026 by loading the scenario data sets into the dev instance as Smart
Pipelines (see `docs/learning-flow-translation-review.md`) and then sending the drift files through.
All of them are failures of Learning Flow's own behaviour, not of a translation it learned.

### 17. A translation that fails outright on a changed format is not treated as drift

**Confirmed, reproduced.** `stroom-learning-flow/stroom-learning-flow/src/main/java/stroom/learningflow/stage/Stage.java`
(`stream()`) and `element/Supervision.java` (`decideAndStream`, around line 345).

**What happened.** `GATEWAY-AUDIT-SP` has an approved translation for the gateway audit export. The
drift file `gateway-audit-drift-2-namespace.xml` was then sent to the same feed. It is the same export
from a newer product version, whose only change is the default namespace:

```xml
<export xmlns="urn:example:gateway:audit:3" …>   <!-- what the translation was learned on -->
<export xmlns="urn:example:gateway:audit:4" …>   <!-- drift 2 -->
```

The served XSLT matches records in the old namespace
(`xpath-default-namespace="urn:example:gateway:audit:3"`), so none of its record templates match. Only
its low-priority catch-all does, and on `<export>` that raises a deliberate error. The stream's error
output (stream 2237) is:

```
INFO:  Served by "gateway-audit-sp-pipeline learned"
FATAL: Record export has no readable timestamp
ERROR: Bound to fragment gateway-audit-sp-pipeline learned, which produced nothing for this stream
```

No events were written. Nothing else happened either: no deferral row, no relearning mark, and no
learning session. The learner's state was unchanged and the stream was finished as an error.

**Why.** `Stage.stream()` serves a bound stream and opens a relearning in only two cases:

1. **A record the translation wrote falls short of the bar** (`served.shortfall()`). Here the
   translation wrote no records, so nothing was judged.
2. **The parser did not read all of the input** (`Judging.inputShortfall`), added for
   `docs/learning-flow-parser-drift-not-detected.md`. That measures how much input the parser consumed. It
   does not notice a transform that fails.

When neither applies, the stream is released and recorded as served with 0 records, and the caller only
logs "produced nothing" (`Supervision.java`). A translation that fails completely on a changed format
is the most obvious drift there is, yet it is the one case that never leads to relearning.

**The same happens when every record is declared skipped.** The drift file
`gateway-audit-drift-3-flattened.xml` removes the `<batch>` wrapper, so records sit directly under
`<export>`. The served XSLT has a template for exactly that position, written for stray elements in the
original format:

```xml
<xsl:template match="export/*[not(self::batch)]" priority="2">
  <Skipped xmlns="learningflow:skips" reason="Export-level element outside any batch, not an auditable record"/>
</xsl:template>
```

so every record now matches it. The stream's error output (stream 2239) is:

```
INFO:  Served by "gateway-audit-sp-pipeline learned"
ERROR: Bound to fragment gateway-audit-sp-pipeline learned, which produced nothing for this stream
INFO:  0 event(s), 27 record(s) declared skipped by the translation: Export-level element outside any batch, …
```

Again no deferral, no mark and no session. A declared skip passes the record bar, so 27 skips out of 27
records is a clean run as far as `stream()` is concerned. The learner's rolling score stays at 1.0, so
the Smart Pipelines screen does not show it as below par either.

**Consequences.**

- Every later stream of the new format errors in the same way, silently from Learning Flow's point of
  view. The Smart Pipelines screen shows the learner as serving, and nothing waits.
- The person has to notice the error streams, mark the learner with **Learn again**, and reprocess.
- The same gap applies to any fatal error in a served fragment, e.g. an unguarded `xs:dateTime` cast on
  one malformed record in a whole-document XSLT (the windows-security and otel-traces translations have
  these).

**Suggested fix.** In `Stage.stream()`, treat a served run that **raised a fatal error, produced no
records from a non-empty stream, or skipped all (or a configurable share) of its records**, as a
shortfall: defer the stream against the fragment in force, mark
the learner for relearning with the error as the reason, and open a session, exactly as for a record that
falls short. The session should be shown the fragment's error, which is what the model needs to adapt.

**Regression test.** A scenario test that binds a learner on one namespace, serves it a stream in another,
and asserts that the stream is deferred and a relearning session opened (it currently finishes as an
error with no session).

**To recover the stream by hand today:** use **Learn again** on the pipeline's Learning Status tab, then
reprocess the errored stream from the pipeline's processor filter. Because the learner is marked, that
stream opens a relearning session. Approve the draft, then reprocess once more.

### 18. A first candidate that fails only on unseen records gives the learner up

**Confirmed.** `stroom-learning-flow/stroom-learning-flow/src/main/java/stroom/learningflow/stage/Learning.java`
(`learnAndBind`, around line 560).

A candidate is learned on a sample and then judged on the whole stream. If it fails a gate on the whole
stream, the learner is **given up** at once, with no further round and no park for a person, even in the
background worker. The `settle()` rounds, which show the model the records that fell short, only start
after a promotion.

This stopped `auditd-ausearch-sp-pipeline`: its parser and XSLT passed on the sample, but on the whole
stream only 65% of records conformed to the schema (`Given up: Candidate failed the gate Schema
conformance (0.6533 of 1.0)`). The file is larger than the default 8 KB sample, so the model never saw
several record types that appear only later in it. Full details are in
`docs/learning-flow-translation-review.md`, *Why auditd-ausearch stopped*.

**Suggested fix.** Send a first candidate that fails on the whole stream into the same rounds as
`settle()`, or at least park it for a person in the worker. Also keep the per-record feedback on the
attempt (its `evidence` column was empty), so that a person can see which records failed.

### 19. A relearning is not told what it must keep reading, so it forgets the old format

**Confirmed, reproduced.** `stroom-learning-flow/stroom-learning-flow/src/main/java/stroom/learningflow/stage/Learning.java`
(`relearnUnderClaim`, around lines 582–640).

**What happened.** `NETFILTER-SP` had an approved translation for syslog-style firewall lines:

```
Oct 19 09:00:03 fw01 kernel: [1393970.694408] FW-IN-DROP: IN=eth0 OUT= …
```

`netfilter-drift-1-iso-timestamps.log` was then sent to the feed. It has the same lines with ISO
timestamps and no kernel uptime field:

```
2026-10-20T09:00:03.112408+01:00 fw01 kernel: FW-IN-DROP: IN=eth0 OUT= …
```

The parser-coverage check caught it correctly ("The parser read no records from this stream: the
translation no longer reads this feed."), the stream was deferred and a relearning session opened. The
model then learned a parser and XSLT for the **new** format only. Before a relearned translation is
promoted, every record the current one was accepted on (the regression corpus, here 24 old-format
records) must still clear the bar, so the candidate was refused:

```
Kept the incumbent: Candidate makes nothing of a record that yielded an event — record 1 of 24 in the
corpus, accepted by attempt 16
```

The same happened on the next session for the same stream:

| Attempt | Stream | Tokens | Outcome |
|---|---|---|---|
| 24 | drift-1 (2240) | 700,823 | Kept the incumbent: record 1 of 24 in the corpus |
| 26 | drift-1 (2240) | 234,296 | Kept the incumbent: record 1 of 24 in the corpus |
| 29 | drift-1 (2240) | 234,660 | Kept the incumbent: record 1 of 51 in the corpus |

The corpus rule did its job: the old format was protected. The problem is that the model had no chance
of meeting it.

**Why.** `relearnUnderClaim` opens the conversation with the stream's failing records and the fragment's
diagnostics, and passes the held records that fell short to `learn`:

```java
opening.add(new StoredError(Severity.ERROR, null, STAGE, "Relearning: " + marked + …));
served.verdicts().forEach(verdict -> opening.addAll(verdict.feedback()));
opening.addAll(served.diagnostics());
final Outcome outcome = learn(doc, learner, input, scorecard, opening, served.held(), recorder);
```

The model is shown the **new** stream and what fell short in it. It is not shown the serving
translation's parser and XSLT, nor any of the corpus records it must go on reading. The corpus is only
checked after a candidate has been learned, and a candidate that fails it ends the session as `Kept`
(recorded as `ABANDONED`). There is no further round in which the model is told which old record it
broke. So when a format changes outright, as here, each session learns the new format, fails the corpus
on the first old record, and ends.

**Suggested fix.**

1. Show the model the serving translation's configuration and a sample of corpus records in the
   relearning's first question, with an instruction that the result must read both the old records and
   the new ones.
2. When a candidate fails the corpus check, feed the failing corpus record back as another round of the
   same session (as the held-stream rounds already do), rather than ending the session.

A note left on the pipeline (guidance) saying "keep reading the old format" is the workaround today, since
guidance is carried into every question.

### 20. The cap on relearning sessions is per stream, and resets whenever the translation changes

**Confirmed.** `stroom-learning-flow/stroom-learning-flow/src/main/java/stroom/learningflow/stage/Stage.java`
(`session()`, and `MOST_SESSIONS_PER_STREAM = 3`, around line 800).

To stop a stream that cannot be fixed from opening a paid session on every retry, `session()` refuses to
open another once that stream has been relearned for three times against the same translation:

```java
if (tried.isPresent()
    && stamp.equals(tried.get().failedAgainst())
    && tried.get().attempts() >= MOST_SESSIONS_PER_STREAM) {
    return deferred(…, " (relearned for … times against this translation without it changing; it now waits for a person)");
}
```

Two things make this cap much weaker than it looks:

- **It counts per stream, not per learner.** Every deferred stream has its own three sessions, so ten
  drifted streams waiting on one learner can open thirty sessions.
- **It resets whenever the translation changes.** The count only applies while the stream's
  `failed_against` stamp matches the translation in force. Promoting or approving any new translation for
  the learner (for example one learned from a *different* stream) changes the stamp, so the stream is
  judged again and can open three more sessions.

**Seen on `NETFILTER-SP`.** While drift-1's stream (2240) was waiting, drift-3's stream (2242) opened its
own session (attempt 27, 138,573 tokens), which learned a translation for its records and was approved.
That replaced the translation drift-1 had been failing against, so drift-1's stream was judged again,
fell short again, and opened a fourth session (attempt 29) even though its deferral row already shows
`attempts = 4`. Relearning on this one pipeline had by then spent over a million tokens:

| Attempt | Stream | Tokens | Outcome |
|---|---|---|---|
| 24 | 2240 (drift-1) | 700,823 | Rejected by the corpus |
| 26 | 2240 (drift-1) | 234,296 | Rejected by the corpus |
| 27 | 2242 (drift-3) | 138,573 | Promoted, approved |
| 29 | 2240 (drift-1) | 234,660 | Rejected by the corpus (now 51 records, after attempt 27 added its own) |

In all, 1,308,352 tokens were spent relearning this one pipeline, of which 1,169,779 went on three
sessions that were each refused for the same reason (item 19). After attempt 29 the stream's deferral
row showed `attempts = 7`, and no further session opened: the cap finally applied because no new
translation had been approved in the meantime.

The spend breaker (per-hour token and question ceilings on the Learning Flow document) is the only other
limit.

**Suggested fix.** Count sessions per learner rather than per stream, and keep the count across
translation changes unless the new translation actually reads the stream (or reset it only when a person
acts on the learner). Show the count and the tokens spent on the pipeline's Learning Status tab, so that
a person can see a relearning loop while it is happening.

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
