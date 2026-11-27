# Shapeshifter AI hard-codes a guessed year when a timestamp has none

## Summary

Shapeshifter AI learned a translation for a syslog-style feed whose timestamps have no year (`Oct 19 09:00:03`).
The model hard-coded a year it invented, 2023, along with a comment presenting it as fact:

```xml
<!-- The syslog timestamp carries no year; the feed's logs are from 2023. -->
<xsl:variable name="year" select="2023"/>
```

It also added `Z` to the local time, treating it as UTC without being told the time zone.

The translation passed every check, was promoted with a score of 1.0, and now writes every event of the feed three years out.
The error is permanent: every year, every stream will be wrong in the same way.

The model had no way to get this right.
It isn't shown when the stream was received, and the prompts don't say how to handle a missing year or time zone.
Stroom's own XSLT function for this, `stroom:format-date`, takes the year from the stream's creation time, but the prompts never mention it.
No scorer checks whether event times are plausible.

## Observed on the dev instance

- Feed `NETFILTER`, pipeline `NETFILTER supervised`, document `Any feed`.
- Input: stream 2118, `netfilter.log` (fixture `stroom-learning-flow/stroom-learning-flow/src/testFixtures/resources/stroom/shapeshifter/ai/scenario/netfilter.log`).
  Its lines start `Oct 19 09:00:03 fw01 kernel: …`.
  The stream was received on 2026-11-11, and the fixture's sibling drift files are dated 2026-10.
- Learned: attempt 44, `PROMOTED`, score 1.0.
  The XSLT is `NETFILTER supervised learned-xsltFilter` (`221da2e3-b80f-4670-bdf1-560ffa972264`).
- Output: Events stream 2133.
  The first event has `<TimeCreated>2023-10-19T09:00:03.000Z</TimeCreated>`.

The learned stylesheet builds the time by concatenating strings:

```xml
<xsl:variable name="isoTime"
              select="concat($year, '-', format-number($month, '00'), '-',
                             format-number(xs:integer($ts[2]), '00'), 'T', $ts[3], '.000Z')"/>
```

## Steps to reproduce

1. Set up the Shapeshifter AI demo (`DemoContentPack`), with a working `Learning model` and its API key secret.
2. Create a feed with a `<FEED> supervised` pipeline and an enabled processor filter, as in the demo.
3. Upload `netfilter.log` and let it be learned.
4. Open the learned `…-xsltFilter` and the Events stream.

**Expected:** event times have the year the logs were written (2026 here), worked out from the stream.
Because the input has no time zone, the translation should use a stated assumption (the feed's or document's time zone), not UTC by default.

**Actual:** the year is a constant chosen by the model, 2023 here, and the local time is written as UTC.

Because the model chooses the year, it will differ between runs and models.
Any year it picks is wrong for every later year of data.

## Why it happens

1. **No date context in the sample.**
   `Sample.SHOWN` (`stroom-learning-flow/stroom-learning-flow/src/main/java/stroom/shapeshifter/ai/learning/Sample.java:43`) limits the stream attributes the model sees to `Feed` and `Type`.
   The model isn't told when the stream was received or created, so it has nothing to infer a year from.
2. **No rule for a missing year or time zone.**
   The transformation rules (`Templates.java`, `TRANSFORMATION_RULES`, line 241) cover the output schema and the required elements.
   They say nothing about timestamps that lack a year or an offset.
3. **Stroom's answer is never mentioned.**
   `stroom:format-date(value, pattern)` (`stroom-pipeline/src/main/java/stroom/pipeline/xsltfunctions/FormatDate.java`) fills in missing fields from a reference time.
   That reference time is the stream's creation time (`getBaseTime()`, line 449), falling back to now.
   If the result would be after the reference time, it moves back a year (line 577).
   A hand-written Stroom translation for syslog would use it.
   No prompt mentions `stroom:` functions, so the model wrote string concatenation instead.
4. **No check catches it.**
   The scorers check compilation, coverage, yield, schema conformance, extraction quality and business rules.
   None compares event times with the stream's own times, so an event dated three years before its stream scores 1.0.

The same prompts also explain an older-schema observation from the same session.
`TRANSFORMATION_RULES` tells the model to write `event-logging:3` with `file://event-logging-v3.0.0.xsd` and `Version="3.0.0"`, which is why the output isn't v4.1.0.
That is a separate decision, noted here only so that it isn't reported as a model error.

## Possible fixes

These fixes work together.
Fixes 1 and 3 are the important ones: one gives the model the right tool, the other catches it when it doesn't use it.

### 1. Tell the model how Stroom handles partial dates (prompt)

Add to `TRANSFORMATION_RULES`, or to a shared timestamp rule used by the configuration and target questions, something like:

> Write EventTime/TimeCreated as an ISO 8601 UTC instant.
> Where the input's timestamp lacks a year, a date or a time zone, parse it with `stroom:format-date(value, pattern)` or `stroom:format-date(value, pattern, timeZone)`.
> Stroom fills in missing fields from the stream's received time.
> Never hard-code a year, and never assume a local time is UTC.

Declare the namespace in the rules (`xmlns:stroom="stroom"`) so that the stylesheet compiles.
Raise `Templates.VERSION`.

Check while doing this: when a fragment runs during learning or serving, `FormatDate` needs a `MetaHolder` with the stream's `Meta` to get the right reference time.
`Supervision` holds a `MetaHolder`.
Confirm that `PipelineFragmentRunner`'s runs see it, otherwise `getBaseTime()` falls back to *now*.
For a stream served soon after receipt, *now* is close enough.
For a reprocessed or back-loaded stream, it is not.

### 2. Show the model the stream's times (context)

Add the stream's create or receipt time, and its effective time, to the headers the model is shown (`Sample.SHOWN`, `QuestionText.headers`).
For example:

```
The stream is described by:
- Feed: NETFILTER
- Type: Raw Events
- Received: 2026-11-11T09:18:19Z
```

This helps the model reason about dates and makes an invented year obviously wrong to it.
It doesn't replace fix 1: a translation should *compute* the year per stream, not copy the year of the stream it was learned on.

### 3. A time plausibility check (scoring)

> **Decided, 18 November 2026** (delivery plan, stage 0b group 4): this is a check like the others, **Advice by default**, with its window settable per Learning Flow; a user may make it a gate. As advice it shows beside a draft and goes back to the model, but doesn't reject a candidate or hold back a served stream. Regression corpus records keep the received time of their own stream.

Add a check that each event's `EventTime/TimeCreated` falls in a window around the stream's received time, e.g. no more than one day after and a configurable age before (default one year).
Events outside the window fail with feedback such as:

> Record 1's TimeCreated is 2023-10-19T09:00:03Z, 3 years before the stream was received (2026-11-11T09:18Z).
> A timestamp without a year must take it from the stream, e.g. with stroom:format-date.

It could be a new scorer type or a built-in rule in the record bar.
It should run both while learning, to reject the candidate, and while serving, so that a drift into implausible dates defers and relearns.
That second part depends on the serve-time judging described in `docs/learning-flow-parser-drift-not-detected.md`.

A whole-year error is the clearest case.
A time-zone error is only hours, so the window would need to be tight to catch it, which back-loaded data makes impractical.
For time zones, fix 1 (never assume UTC) and a document- or feed-level time zone setting are the better tools.

### 4. Optionally: a document- or feed-level time zone

> **Decided, 19 November 2026** (delivery plan, stage 0b group 7, N6): per pipeline, not on the document or the feed.
> The zone comes from the stream's header if it has one, otherwise from a `timeZone` text property on the AI Translator element, otherwise UTC.
> The stage puts the resolved zone into the stream's metadata, where the stylesheet reads it with `stroom:meta`, so a change needs no relearning.
> The text below is the original proposal.

Let the Shapeshifter AI document, or the feed, state the time zone of timestamps that have no offset.
Pass it to the model as a header, and to `stroom:format-date` as its third argument.
Without it the right answer can't be known, and the rule in fix 1 should then ask the model to state its assumption in a comment rather than silently write UTC.

## Tests to add

- A scenario test that learns from a syslog sample with a scripted model reply that hard-codes a year.
  The new time check should reject it, with feedback naming the stream's received year.
  A reply using `stroom:format-date` should pass.
- A test that the transformation-rules text mentions `stroom:format-date` and forbids hard-coded years.
  A snapshot of the rendered prompt is enough.
- A test that the headers shown to the model include the received time.
- Unit tests for the time plausibility check: inside the window, a year early, in the future, and a missing or unparseable `TimeCreated`.
- A test that a fragment run gives `stroom:format-date` the stream's creation time, not *now*: a stream with an old create time and a year-less timestamp gets the old year.

## What was built, and what it means for bindings already in use (20 November 2026)

Fixes 1, 3 and 4 are built, with the metadata work assumption AS6 called for. See the delivery plan's
[Task 3 as built](learning-flow-delivery-plan.md#task-3-as-built-so-far-20-november-2026) for the parts
and what is left.

### Bindings learned before this: the rollout

**Nothing breaks, and nothing is relearned on its own.** A binding learned before this keeps serving
exactly as it did. That is deliberate — a translation in use is not changed under a site without their
knowing — but it does mean a binding that hard-codes a year goes on doing so.

What changes for an existing binding:

- **The times check runs against it while it serves**, and is *advice*, so it reports rather than refuses.
  A binding dating its events a year out shows as advice on the pipeline's Learning Status tab rather
  than being withdrawn.
- **The literals check does not look at it** unless it is judged again: it reads the stylesheet when a
  candidate is judged, and an existing binding is not a candidate.
- **A relearn or an Improve picks up all of it** — the new prompt, both checks and the resolved zone —
  because those go through the conversation.

What a site should do, in order of effort:

1. **Look at the advice** on the feeds that carry timestamps without a year — syslog above all. The
   check names the record, what it wrote and how far out it is.
2. **Set the feed's zone** on the pipeline's AI Translator element, or have senders send a `TimeZone`
   header, where times are not UTC. This takes effect without relearning, which is why the zone is
   resolved rather than learned.
3. **Relearn the bindings the advice names.** A relearn gets the prompt that explains partial dates, so
   the model has what it needs to use `stroom:format-date` rather than guess.

**There is no migration, and there should not be.** A stylesheet is a document a person can read and
edit; rewriting people's translations automatically to take a year out would be a change to content a
site owns. The checks make the fault visible, and relearning is the fix.

## Related

- `docs/learning-flow-parser-drift-not-detected.md`: serve-time judging that a time check would rely on to trigger relearning.
- `docs/learning-flow-supervisor-visibility.md`, item 6: "events dated far from the stream's receipt time" as a finding on the pipeline's Learning Status tab.
