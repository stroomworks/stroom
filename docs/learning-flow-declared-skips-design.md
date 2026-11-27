# How a translation declares a skipped record

*Written 20 November 2026 · the design item left open by stage 0b group 4 (UM-2) · needed by Track 1 task
2's completeness check*

> **Decided, 20 November 2026: option B, a marker in the output, and no cap on the skip rate.**
> The count is shown instead — in the conversation, on the Learning Status tab and beside the event count
> on the stream's output row — so that a person approving a draft sees *"312 events, 4 skipped"* and
> judges for themselves.
>
> **What that accepts.** Without a cap, declaring a record skipped is strictly easier for the model than
> translating it, and completeness alone will not catch a translation that skips its awkward records: the
> arithmetic still adds up. Visibility is the whole of the mitigation, so the count has to be genuinely
> hard to miss rather than a figure in a detail pane. If skipping is found to be abused in practice, the
> cap described below is the first thing to add, and option D — declaring skips on the Learning Flow,
> where the model cannot reach them — is the fallback.

## Why this is needed

The completeness check (UM-2, a gate by default) is **records in = events out + declared skips**. It is
the check for the fault in
[parser drift not detected](learning-flow-parser-drift-not-detected.md): a record the learned parser
cannot read never becomes an event, so nothing judges it, and the stream is recorded as clean.

But plenty of feeds legitimately make fewer events than records. A header line, a column banner, a
heartbeat, a blank line, a comment, a continuation that belongs to the record before it — none of these
is an event, and a translation is right to produce nothing for them. Without a way to say so,
completeness would fail those feeds on every stream, and users would turn it off. A check people switch
off protects nobody.

So a translation needs a way to say: *this record is deliberately not an event.*

## The thing to be careful about

**A declared skip is a hole in the completeness check that the model controls.**

The check exists to catch records going missing. If the translation can declare a record skipped, then a
translation that cannot handle a record can declare it skipped and score perfectly — which is the fault,
wearing the check's own badge.

This is the same shape as `stroom:log`, which the Smart Pipelines list refuses: an `ERROR` from it drops
records from the output quietly, and *a candidate that drops its awkward records can still score well*.
Whatever is chosen here must not re-open that door.

So the design question is not really which syntax. It is **how to stop the escape hatch becoming the
model's first answer.**

## Options

### A. `xsl:message` in an agreed form

The translation emits `<xsl:message><info>skip: header line</info></xsl:message>` and the stage counts the
messages.

- **For:** no output to clean up; messages already reach the stage through the pipeline's message listener.
- **Against, and this is close to fatal:** in Stroom, `xsl:message` logs to the error receiver as
  **`ERROR`** unless the message wraps its text in a severity element such as `<info>`
  (`XsltFilter.onXsltMessage`). An `ERROR` drops records from the output — the very thing the check is
  meant to catch. The safe form is a wrapped element that a model will get wrong sooner or later, and
  getting it wrong loses data. `terminate="yes"` is worse still: `FATAL`, and the rest of the stream part
  is abandoned.
- It also means putting `xsl:message` on the Smart Pipelines list, which
  [Track 2 step 2](learning-flow-delivery-plan.md#step-2-as-built-20-november-2026) deliberately refused
  on these grounds. It could be allowed in the non-terminating form only — the static check can see a
  `terminate` attribute — but the severity trap stays.

### B. A marker in the output that the writer drops (chosen)

The translation emits a marker in place of an event, in Shapeshifter's own namespace, e.g.

```xml
<Skipped xmlns="shapeshifter:skips" reason="header line"/>
```

The module already parses the output and counts records, so it counts these with them. The writer drops
them, so nothing downstream sees them.

- **For:** it is data, not a side effect, so it cannot drop a record by being written wrongly. It is
  visible in the output the module already reads, countable by the code that already counts events, and
  showable in the conversation and on the Learning Status tab. It needs no change to the allow-list.
- **Against:** the marker must not break the Schema-valid gate, so it needs its own namespace and the
  check must ignore it; and the serving pipeline must drop it before the writer. Both are real work but
  neither is subtle.

### C. No declaration: let the yield check's ratio express it

`YieldParameters` already has an `expectedRatio` — a feed yielding 0.9 events per record says so.

- **For:** nothing new at all. No syntax, no allow-list change, no output to strip.
- **Against:** it cannot tell *"skipped the header deliberately"* from *"lost 10% of records to a parse
  failure"*, and those are the two cases the whole task is about separating. A tolerance wide enough for
  the header is wide enough to hide the drift.

### D. Declare the skips on the Learning Flow, not in the translation

The document names patterns that are known non-events — a regex for header lines, say — and completeness
subtracts them.

- **For:** it is configuration, visible and editable without relearning, and **the model cannot reach
  it**, so the escape hatch is closed by construction.
- **Against:** record-shaping knowledge then lives in two places, the model cannot adapt it when a feed
  changes, and it needs a new field and UI. A feed whose non-events are not a simple pattern cannot be
  expressed at all.

## Recommendation as made, and what was decided

Option **B** was recommended with two things that matter as much as the syntax. The first was taken; the
second was not, which is the trade-off recorded above.

1. **A skip is counted, shown and scored — never silent.** The number of skipped records goes into the
   conversation, the Learning Status tab and the stream's output row, beside the event count. A reviewer
   approving a draft sees "312 events, 4 skipped", not "312 events".

2. ~~**A skip rate of its own is a finding.**~~ **Not adopted (20 November 2026).** The recommendation was
   a separate threshold capping the share of records that may be skipped, defaulting to around 10% and
   settable per Learning Flow, so that a translation declaring a third of the stream skipped failed
   however neatly its arithmetic added up. The decision was to rely on the count being shown instead.
   Recorded here because it is the first thing to reach for if skipping is abused.

**Why not D**, which closes the hole completely: a feed's non-events are not always a pattern someone can
write in advance, and the cost of being wrong is a check that fails honest feeds — which is how checks get
switched off. B keeps the model able to adapt. If it turns out to be gamed in practice, D is the fallback,
and the two can coexist.

## What is built (20 November 2026)

| | Where |
|---|---|
| The marker namespace `shapeshifter:skips` and its `Skipped` element, with a free-text `reason` | `DeclaredSkips` |
| Counted apart from the events, with the reasons kept | `Records.skips`/`skipReasons`, `OutputRecords.skips` |
| The Schema-valid check ignoring it — no schema knows the marker | `SchemaConformanceScorer`, through `DeclaredSkips.stripping` |
| Dropped before the writer, by never being handed on | `RecordGate` |
| Carried out of a serving run | `ServedStream.skipped`/`skipReasons` |
| Completeness itself: `records in = events out + declared skips`, a **gate** in every preset | `CompletenessScorer`, `Check.COMPLETENESS`, `StepOutcome.COMPLETENESS_SHORT` |
| The count said even when the check passes, which is what reaches a person | `Verdict.notices`, and on the error stream through `Judged.of` and `Supervision` |
| On the served stream's own attributes | `ShapeshifterAiSkipped` (`Produced.SKIPPED_ATTRIBUTE`) |
| Taught to the model | `Templates.TRANSFORMATION_RULES`, `VERSION` 7 → 8 |
| Kept over the rolling memory, and per stream | `shapeshifter_learner_state.rolling_skips`, `shapeshifter_output.skip_count` |
| Shown to a person | `FeedSituation.listSummary`, `QualityFigures.volume` |

No skip-rate threshold, per the decision above.

**And now shown where a person reads it** (20 November 2026), which was the whole of the mitigation and
so was not left for later:

| Where | What it says |
|---|---|
| The Learning Status tab's row for a feed | `Scores 98% · 96 of 1,204 skipped` (`FeedSituation.listSummary`) |
| The Quality Assurance tab, beside the volume | *"1,204 records in 4 streams over the last 2 days. 18 of those records were declared skipped by the translation."* (`QualityFigures.volume`) |
| The Error stream, as a stream is served | `312 event(s), 4 record(s) declared skipped by the translation: header line` |
| The served stream's own attributes | `ShapeshifterAiSkipped` |

Both of the first two are kept over the same rolling memory as the score, so a translation that skipped
badly once stops looking as though it still were: `shapeshifter_learner_state.rolling_skips` carries the
skips of the records the memory still holds, and `shapeshifter_output.skip_count` the stream's own.
A translation that skipped nothing says nothing, so the figure means something when it appears.

### What it turned up

Joining the per-record outputs back into one document (`RecordJoin`) passed on only the *first* record's
namespace declarations. A marker in any later record was therefore written with no binding at all and read
back in whatever namespace surrounded it — so it counted as an event, failed the Schema-valid gate, and
would have reached the writer. Fixed, with `TestRecordJoin` holding it. Nothing before this used a
namespace that varied record to record, which is why it had never shown.

## Still to settle after this

- Whether a skipped record's reason is free text or a short list. Free text is friendlier to the model;
  a list is countable and would let the Quality Assurance tab say *"4 skipped: 4 header lines"*. Built as
  free text for now, which a list can be narrowed to later without changing the marker.
