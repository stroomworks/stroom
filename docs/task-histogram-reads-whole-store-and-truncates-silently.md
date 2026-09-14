# The timeline histogram reads the whole store, caps at 10,000 rows, and never says it truncated

**Component:** `stroom-core-client` — `HistogramQueryHelper` and `HistogramDataModel`, as used by the
Floor Map's timeline
**Severity:** medium-to-high. Below the cap it is merely wasteful. At or above it the density bars
are computed from an arbitrary subset of the store and **look completely normal while being wrong**,
which is the worst property a chart can have.
**Status:** open. Independent of the event-expiry work.

---

## What it does

`runHistogramQuery(start, end)` takes the visible time range and uses it for exactly one thing:

```java
private void runHistogramQuery(final long start, final long end) {
    histogramDataModel.setRange(start, end);          // client-side binning bounds

    final String query = getEventsQueryToUse();
    if (query != null && !query.trim().isEmpty()) {
        histogramQueryHelper.run(query, queryParams());   // no range passed
    }
}
```

The query itself is unbounded. `HistogramQueryHelper.run` passes `timeRange = null`, and the binning
happens afterwards in the browser:

```java
// HistogramDataModel.process
if (t < rangeStart || t > rangeEnd) {
    continue;
}
```

So the shape is **fetch everything, discard most of it client-side**. Zooming the timeline to five
minutes transfers exactly as much data as viewing a year.

## Why it is like that, which matters for the fix

This is not an oversight, and the javadoc on `run` says so at length: with a `TimeRange` present the
temporal store's DAO switches to **point-in-time** semantics and returns *one row per key* — the
latest at or before the range end. That is right for the map overlay and useless for a histogram,
which needs every row in a window.

**The interface offers no third option.** There is no way to ask a temporal store for *all rows
between two times*: a lower bound is discarded once an upper bound is present, and an upper bound on
its own means "snapshot". Null is the only setting that returns history at all, so null is what the
histogram passes.

That is the same asymmetry the Floor Map's event expiry runs into from the other direction
(`docs/floormap-event-expiry-requirements.md` §1.1). Any real fix here probably shares its answer.

## The part that is a correctness bug

The fetch is capped:

```java
// HistogramQueryHelper — the ResultComponent's requested range
return new OffsetRange(0, 10000);
```

and **nothing checks whether the cap was hit.** There is no reference to `getTotalResults` anywhere
in the histogram package, though `TableResult.getTotalResults()` is populated independently of the
rows returned and is exactly how the Map tab's own read detects truncation.

Past 10,000 rows the bars are drawn from **the first 10,000 rows the store happened to return**. For
a Plan B events store that is *key order, not time order* — `FloorMapEventsQueryOrder`'s javadoc
records the chain: *"Plan B iterates one LMDB cursor whose key is `<entity><big-endian time>`, an
ungrouped search is keyed by a monotonic insertion id, and neither the write queue nor the result
creator reorders."*

So the truncated read is not a sample of the window. It is **the complete history of the
alphabetically-first entities and nothing at all from the rest** — the same failure mode the Plan B
parity report describes for an unbounded read. The resulting histogram is plausible, wrong, and
silent.

Two further consequences from the same cause:

- **Show All inherits it.** `HistogramDataModel` derives the data extent from the fetched rows and
  hands it to `dataRangeHandler`, which is what enables and drives Show All. On a truncated read,
  Show All fits the timeline to the extent of an arbitrary subset. **This is not a choice the Map
  tab made** — see option 4: the store it reads offers no way to ask for its own extent, so
  inferring it from the rows is the only option available. The Editor tab, reading a SQL Temporal
  Store, asks the store and gets an exact answer.
- **The map overlay is better protected than the histogram.** Its read returns one row per key, so
  its 20,000 cap is a cap on *distinct entities*. The histogram asks for raw history, so its 10,000
  is a cap on *events* — a far lower ceiling on the same store, and the one that will be reached
  first.

## Suggested fix

**Detect the truncation first.** It is a few lines, it is independent of everything else, and it
turns a silent wrong answer into a visible one:

- compare `getTotalResults()` against the row count in `HistogramDataModel.process`;
- report it the way the Map tab reports its own truncation — once, saying that the bars are computed
  from part of the store and are not to be trusted;
- the reporting channel now exists: `HistogramQueryHelper` gained an error listener, though it
  currently writes only to the browser console
  (`docs/task-histogram-failures-are-console-only.md`).

**Then decide how to bound the read**, which is the larger conversation:

1. **Server-side binning.** The histogram wants counts per bucket, not rows. A `group by` on a
   rounded time with `count()` would return one row per bin — a hundred rows instead of ten
   thousand, with no cap problem and no client-side scan. This is the answer that fits what the
   feature actually needs, and it is expressible in StroomQL today — `floorSecond` through
   `floorYear` all exist, and `TestSearchRequestFactory2` already parses a query using
   `floorMinute`.

   **Two costs, neither fatal but both real.** The query text stops being the events query verbatim
   and becomes the histogram's own, which the Floor Map deliberately unified — see
   `docs/floormap-event-expiry-requirements.md` §9.4. And the bins change shape: today
   `HistogramDataModel` cuts the visible range into a fixed 100 equal slices, whereas rounding gives
   *fixed-width* buckets. The rounding function would have to be chosen from the zoom level —
   seconds when the range is minutes, days when it is a year — which yields roughly rather than
   exactly a hundred bars, and needs a rule for picking it.
2. **A range the store honours.** Give the temporal stores a way to express "all rows between two
   times", distinct from the point-in-time snapshot. This is the same missing capability the event
   expiry needs, and solving it once would serve both.
3. **Raise the cap.** Not a fix. It moves the threshold and keeps the silence.

4. **Give Plan B a `getTimeRange`, and use it for Show All.** This fixes a *different symptom* from
   the other three — it leaves the bars exactly as they are and corrects only the data extent — but
   it is worth listing here because that extent is the part with a known-good implementation sitting
   next to it.

   The Editor tab's Show All is exact, because facts live in a SQL Temporal Store and
   `UpdatableTemporalStoreDaoImpl.getTimeRange` answers with a single aggregate:
   `SELECT MIN(effective_time), MAX(effective_time) WHERE doc_uuid = ?`. One row, no cap, no
   client-side scan. The Map tab cannot do the same because **Plan B has no equivalent** — the
   events store is always a `PlanBDoc`, and nothing in `stroom-planb` exposes a store's time extent.

   **It is not as cheap as first-and-last-key, which is the tempting wrong answer.** A temporal key
   is written prefix-then-time (`LimitedStringKeySerde.write` puts the key bytes down and appends
   the time; the read takes the time from the trailing bytes), so LMDB orders by **entity, then
   time**. The first key in the database is the alphabetically-first entity's *earliest* entry, not
   the store's earliest — the same key-order trap that makes a truncated read unrepresentative.

   Two honest implementations:

   - **A cursor scan over keys**, tracking min and max time. `O(n)` in keys but it deserialises no
     values, runs once per call rather than per tick, and its result is eminently cacheable — a
     store's extent changes only on ingest.
   - **Maintained metadata**, a min/max pair updated as entries are written or merged. Plan B
     already does exactly this shape elsewhere: `TraceStats` is *"maintained incrementally as spans
     arrive, so the merge-cycle finalize is O(1) per trace instead of re-scanning every span"*, and
     carries running `maxEnd` / `lastActivityMs` values. The same reasoning applies, and the merge
     processor is already rewriting the shard.

   The second is the better shape and the larger change. The first is enough to make Show All exact
   and is independent of everything else in this document.

**Option 1 is the one worth costing** for the bars: it removes the cap problem rather than reporting
it, and makes the transfer proportional to the *chart* rather than to the store. **Option 4 is the
one worth doing first for Show All**, since it is self-contained and restores parity with a tab that
already gets this right.

## Verification

- A store with more than 10,000 rows in the events query's range reports that the histogram is
  truncated, rather than drawing bars from a subset.
- Below the cap, the bars are unchanged.
- If option 1 is taken: the bars match the current ones for a store below the cap — the grouped
  query must not change what is plotted, only how it is counted — and the number of rows transferred
  is the bin count rather than the event count.
- If option 4 is taken: Show All on the Map tab fits the timeline to the store's true extent on a
  store past the cap, where today it fits to a subset; and the extent matches what the Editor tab
  reports for a store holding the same times.

There is currently no automated coverage of `HistogramDataModel` or `HistogramQueryHelper` at all.
