# Shapeshifter AI does not relearn when a learned parser stops reading the input

## Summary

Shapeshifter AI decides whether to relearn a feed's translation by judging the events the translation produces.
A record that the learned parser cannot read never becomes an event, so it is never judged.
That gap leads to two failures:

- **A total parse failure goes unnoticed.**
  When a format change means the parser matches nothing, the stream produces no events.
  The stage writes `Bound to fragment ..., which produced nothing for this stream` to the Error stream and does nothing else.
  It doesn't defer the stream, mark the learner for relearning, feed the rolling score or open a session.
  The output is recorded with the translation's original score of 1.0, and the pipeline's Learning Status tab (and Monitoring > Smart Pipelines) still shows it as *Translating*.
- **A partial parse failure loses records silently.**
  A record the parser can't read is dropped, with an `ERROR` on the Error stream, and the stream still counts as clean.

Coping with format drift is the main job of this feature, and drift is exactly what breaks a learned parser.
At the moment a parser-breaking change is only visible to someone who reads the Error streams.

## Observed on the dev instance

Feed `NETFILTER`, pipeline `NETFILTER supervised` (`3d17affa-ea75-4bb6-99ec-9dd6eec9e240`), document `Any feed`.

| Stream | Input | Result |
|---|---|---|
| 2118 | `netfilter.log` (syslog `Oct 19 09:00:03` timestamps) | Learned (attempt 44, `PROMOTED`, score 1.0, 13 calls, about 551k tokens). Events stream 2133 holds **29 events from 30 lines**. Error stream 2132 has `dsParser ERROR ... unmatched content: [FRAG:185]` for line 25. |
| 2134 | `netfilter-drift-1-iso-timestamps.log` (same records, ISO 8601 timestamps) | **No Events stream.** Error stream 2135 has `dsParser` "unmatched content" errors for every line it logs (lines 1–20; the log stops there), then `Bound to fragment NETFILTER supervised learned, which produced nothing for this stream`. |

The database after stream 2134 had been processed (read-only queries):

```
shapeshifter_learner_state  rolling_score=1, rolling_records=30, relearn_reason=NULL,
                            given_up_reason=NULL, update_time=09:22:00   (unchanged since stream 2118)
shapeshifter_attempt        only attempt 44 (stream 2118); nothing for 2134
shapeshifter_deferral       no rows
shapeshifter_output         id 11, input_meta_id=2134, score=1   ← recorded as if it were a good run
```

## Steps to reproduce

### On a running Stroom

1. Set up the Shapeshifter AI demo (see `DemoContentPack`), with a working `Learning model` and its API key secret.
2. Create a feed, e.g. `NETFILTER`, with a pipeline built like the demo's `<FEED> supervised` pipelines and an enabled processor filter on the feed's `Raw Events`.
3. Upload `stroom-learning-flow/stroom-learning-flow/src/testFixtures/resources/stroom/shapeshifter/ai/scenario/netfilter.log`.
   Wait for it to be learned and promoted (a `<FEED> supervised learned` pipeline appears).
4. Upload `netfilter-drift-1-iso-timestamps.log` from the same directory.
5. Look at the output.

**Expected:** the stage sees that the translation no longer reads the feed.
It should defer the stream, mark the learner for relearning, and start a session that learns the ISO timestamp format.
Once the new translation is promoted, the deferred stream should be processed again.

**Actual:** no events, one line in the Error stream, and no change in the learner's state.
The learner stays *Serving*, and every later stream in the new format will fail the same way.

### In a unit test

`Stage.stream` can be exercised with a stand-in fragment runner (`StandInFragmentRunner`) that returns a `ServedStream` with `records() == 0` and parser diagnostics.
The test should assert that the stream is deferred and the learner is marked for relearning.
With the current code, it is released and nothing is marked.

A second test should have the fragment read N−1 of N input records and assert that the shortfall is detected.

## Where it happens

`stroom-learning-flow/stroom-learning-flow/src/main/java/stroom/shapeshifter/ai/stage/Stage.java`, `stream(...)` (line 946):

- The only way to defer and relearn is `served.shortfall()`, which is set when a record **that was output** falls short of the record bar (`recordBar`: the scorers that judge one record).
  An input record the parser can't read produces no output record, so it can't fall short.
- When there is no shortfall, `deferrals.released(...)` runs (line 969), as if the stream had cleared.
- The rolling score is only fed `else if (served.records() > 0)` (line 981).
  The comment there says *"A run that made nothing is said on the error stream by the caller and scores nothing."*
- `outputs.emitted(...)` (line 988) records the output with `producedBy(doc, binding)`, which takes **the binding's promotion score** (line 1001), not the score of this run.
  This is why output row 11 shows 1.0.

`stroom-learning-flow/stroom-learning-flow/src/main/java/stroom/shapeshifter/ai/element/Supervision.java`, `decideAndStream(...)` (line 322):

- The fragment's diagnostics (the `dsParser` errors) are copied to the Error stream, and `served.records() == 0` (line 347) adds the "produced nothing" error.
  Neither is fed back to the stage.

The stream-level scorers (compile, coverage, yield) judge a candidate while it is being learned.
`recordBar` deliberately leaves them out when a bound stream is served (*"The scorers that measure a stream — compile, coverage, yield — judged the candidate when it was promoted and have nothing to say about one record."*).
They are exactly the scorers that would catch this.

## Possible fixes

### 1. Judge coverage of the input as well as the events (recommended)

While a bound stream is served, count the input records alongside the output records.
The binding already knows its boundary: one line per record for this parser, or `RecordBoundary` for XML and JSON.
`ServedStream` would also report what was read, and `Stage.stream` would treat these as a shortfall:

- no events from a stream that has input (records in > 0, events out = 0);
- input coverage below the document's yield or coverage threshold (e.g. 29 of 30 is 0.97);
- any `ERROR` or `FATAL_ERROR` from the fragment's parser element.

All three take the existing route: `session(...)`, then `deferred(...)`, then `DeferTaskException`.
The stream waits, the learner is marked with a reason such as *"The parser read 0 of 30 records: unmatched content at line 1"*, and the relearn session gets the failing lines as feedback.
That is how a shortfall on a produced record is already handled, so the Learning Status tab and its waiting-streams list need no new concepts.

The threshold should come from the document's existing yield or coverage setting rather than a new one.
A strict zero-tolerance default would defer a stream over one bad line, which may be too eager for noisy feeds.

### 2. Defer on zero events only

A smaller change.
In `Stage.stream`, treat `served.records() == 0` on a stream with non-empty input as a shortfall.
This fixes the total-failure case (drift 1) but not silent partial loss (the `FRAG:185` line).

### 3. Feed parser errors into the rolling score

Count dropped records against the rolling score, and have the relearn threshold (`relearnThreshold`) trigger relearning.
Today the threshold only flags the learner as *Below par* on its Learning Status tab and in the pipeline lists (`SupervisorResourceImpl.belowThreshold`), and the rolling score is documented as triggering nothing in the bound path, so this would be a change of behaviour.
Relearning would also be delayed until the average dropped far enough.
A total failure should not wait for an average to fall.

### Either way

- `outputs.emitted` should record the score of **this** run, not the binding's promotion score, so that an empty run doesn't appear in the output history with 1.0.
- The decision written to the Error stream (`Served by binding …`) should say what happened to the stream as a whole, e.g. *"Served by binding f123…: 0 of 30 records read; deferred for relearning"*.
  See the separate visibility write-up, `docs/learning-flow-supervisor-visibility.md`.

## Tests to add

- `Stage.stream` with a fragment that reads none of the input: the stream is deferred, the learner is marked with a reason that names the parser error, and no output row is written.
- `Stage.stream` with a fragment that reads N−1 of N records, where the threshold is below (N−1)/N and where it is above: served in one case, deferred in the other.
- `Stage.stream` with a fragment that reads everything but logs a parser `ERROR`: deferred under option 1.
- A scenario test: learn `netfilter.log`, then serve `netfilter-drift-1-iso-timestamps.log`.
  Expect a relearn session that is promoted, after which the drift stream is processed again and produces 30 events.
- A regression test that a clean bound stream is still released, and that the rolling score is fed as before.

## Related observations (same run)

- **Wrong year.** The translation learned from `netfilter.log` dates events 2023, a year the model hard-coded.
  See `docs/learning-flow-missing-year-hard-coded.md`.
- **Old schema version.** The learned translation writes `event-logging:3` v3.0.0, though v4.1.0 is installed.
  This isn't a model error: `TRANSFORMATION_RULES` in `Templates.java` tells it to.
- **GATEWAY-AUDIT has a separate bug** (`docs/learning-flow-mixed-record-elements.md`).


## What was built (20 November 2026)

**Half of fix 1, and the half that was blocked on a decision.**

**Completeness is now a check** (`Check.COMPLETENESS`, `CompletenessScorer`), a **gate in every preset**:
`records in = events out + declared skips`. A record that the translation produced nothing for now fails
the candidate and goes back to the model with what is missing and how to say it was deliberate — which is
the gap this document is about, since every other check judges what came out and none of them asks whether
anything went missing on the way.

**How a translation declares a skip** was the design this waited on, settled the same day as ruling A148
and written up in [the declared-skips design](learning-flow-declared-skips-design.md): the translation
writes `<Skipped xmlns="shapeshifter:skips" reason="..."/>` in place of the event. It is counted beside the
events, ignored by the Schema-valid check, and dropped before the writer. There is **no cap** on how many
a translation may declare; the count is shown instead.

**Fix 1 is now built as well.** A serving run says what the parser made of the **input**: the records it
cut, counted by a filter on the parser's own fork; the errors it reported, counted before they are
de-duplicated and capped; and whether the stream held any content at all. `Stage.stream`, the worker's
full pass and `settle` all judge it, so a relearned stream is not woken into the same deferral it came
from.

- A stream the parser read **nothing** of is deferred whatever the Learning Flow's checks say. That is not
  a quality threshold; it is the translation not working here, and it is stream 2134's fault exactly.
- Records that were read but became neither an event nor a declared skip defer where **completeness** is
  a gate.
- A parser reporting records it could not read defers where **input coverage** is a gate, measured in
  records rather than characters (decision of 20 November 2026, audit finding W4-3).
- The output row and the `ShapeshifterAiScore` attribute now carry **this run's** score, written after the
  stream is served, so an empty run no longer appears in the history at 1.0 (W4-6).
- A stream may open three relearning sessions against the same translation; after that it waits for a
  person instead of paying for a new one every five minutes (W4-5).

Held by `TestScenario64TheStreamTheTranslationStoppedReading` and, for the two runners agreeing,
`TestFragmentRunnersAgree.bothRunnersCountWhatTheParserMadeOfTheInputTheSameWay`.
