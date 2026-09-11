# Implementation plan: expiring an entity's last known event (M3)

**Implements:** `docs/floormap-event-expiry-requirements.md` — the mechanism decided at D1.
**Branch:** `enterprise-floor-mapping-events-last-forever`
**Status:** plan. Nothing here is built. One behaviour question (**D3**, the tracking roster) is
still open and is called out where it lands.

---

## The design in one paragraph

The events query carries a `having` clause that drops rows whose effective time is older than a
floor, and the floor arrives as a **query parameter** rather than as text. The Map tab binds it to
`T − D`, where `T` is the scrubber position and `D` the document's configured duration. Every other
execution of the same query — the timeline histogram, the Events Query tab — binds the floor to
**zero**, which is epoch, so the clause passes everything. No query text is rewritten anywhere, and
the filter runs in the LMDB result store above both temporal stores, so Plan B and the SQL store
behave identically without either being changed.

---

## Work items

### W1 — the document field

`FloorMapDoc` gains `eventExpiry`, a `SimpleDuration`.

- `@JsonProperty`, builder entry, and inclusion in `copy()`, matching the existing fields.
- **Read-path default, not a migration.** A document whose JSON has no `eventExpiry` — which is
  every document that exists — reads as **24 hours**, not as "off" (R8′). Put that default in one
  place, on the getter or a small accessor beside it, so no caller can forget it.
- `SimpleDuration` is GWT-safe (`stroom-util-shared`, Jackson plus `Objects` only) and carries
  `getApproxMillis()`, which is what the floor arithmetic needs. "Approx" is exact for everything up
  to `WEEKS` and approximate for `MONTHS`/`YEARS` — fine for a staleness threshold, and worth a
  javadoc line rather than a pretence.

### W2 — the query text

`FloorMapEventsQuery`:

- a new constant for the parameter name, `EXPIRY_FLOOR_PARAM = "ExpiryFloor"`, so the query text and
  the code that binds it cannot drift — the same discipline `defaultQuery()` already applies to the
  column aliases;
- `defaultQuery()` gains the clause. `having` precedes `select` in StroomQL:

```
from param('EventStore')
having "Effective Time" > param('ExpiryFloor')
select EffectiveTime as "Effective Time",
  Key as "Entity ID",
  ...
```

**Prove this first — it is the one thing the whole plan rests on.** The clause references the column
*alias*, which the `select` defines further down. `SearchRequestFactory` collects having-referenced
fields into `additionalFields` (`:401`) and adds a hidden column for any that were not selected
(`:937`–`:945`), so referencing `Effective Time` should resolve whether or not it is selected — and
here it is selected, so no hidden column should be created. That is a reading of the code, not a
demonstration. **Write the test before anything else in this plan.**

### W3 — binding the floor

`FloorMapQueryPresenter.buildQueryVariables` gains `ExpiryFloor → "0"` alongside the two store
names. That is the **neutral** value, and putting it there means every execution that goes through
the ordinary variables map — the Events Query tab, and anything added later — is correct by default
and unfiltered.

`FloorMapMapPresenter.queryParams()` becomes `queryParams(long floorMs)` and **overrides** that
entry for the map's own read. One call site changes meaningfully:

| Call site | Floor |
|---|---|
| `readEvents` — the overlay | `t − eventExpiry.getApproxMillis()` |
| `runHistogramQuery` — density bars | `0` — must read all history (§2 of the spec) |
| `readFactsHistoryIfDue` — facts | `0`; the facts query has no expiry clause, so the binding is inert, and passing it keeps the three sites uniform |
| Events Query tab | `0`, via `buildQueryVariables` |

**Why zero rather than omitting the parameter.** An unbound `param()` in a value position resolves
to null, which becomes an empty term value, which throws `MatchException` during predicate
construction — zero rows plus an error. Binding zero is the difference between "no filter" and "the
query is broken", and they must not look alike.

**Why a bare number is a valid floor.** `DateExpressionParser` parses a `NUMBER` token as epoch
milliseconds (`DateExpressionParser.java:158` → `fromEpochMillis`), so `String.valueOf(floorMs)`
needs no formatting and no date-time settings.

**Why this is exact, unlike a client-side filter.** The `having` clause becomes
`tableSettings.aggregateFilter`, which `TableResultCreator` hands to a `FilteredMapper` (`:99`)
operating on the data store's `Val` objects — *before* any rendering to the viewing user's date-time
preference. The rendered-time hazard that `latestPerEntity` documents does not apply here.

**Why it is ahead of the row cap (R9).** In `LmdbDataStore`, rows flow through
`mapper.create(item).forEach(row -> { … fetchState.length++ … })` (`:1074`, `:1081`). A row the
filter rejects never enters the body, so it never counts against `range.getLength()` and never
increments `totalRowCount` — 20,000 *surviving* rows are returned, and the existing
`totalResults > rows.size()` truncation check stays meaningful.

### W4 — the setting's UI

The timeline settings dialog (D7). `FloorMapTimelineSettingsPresenter` is currently pure view state,
but the Map tab already persists document fields, so the route exists:

- a `DurationPicker` in `FloorMapTimelineSettingsViewImpl` — it implements `HasValue<SimpleDuration>`
  already;
- on change, stage into `FloorMapDocSession` and call `onChange()`, which re-runs `onWrite` and
  diffs against the loaded document to light the save button — the same path group edits take;
- **gate it on the `readOnly` flag** `FloorMapMapPresenter.onRead` already receives and currently
  ignores, because until now this tab had nothing editable. This is the only genuinely new
  obligation, and it needs its own test;
- **reject zero, blank and negative** (R8′). A zero duration expires everything instantly and
  presents as an empty map, which is indistinguishable from a broken query (R11).

### W5 — `condense` detection (R12)

`condense` collapses a run of identical values to its **earliest** entry, so a stationary entity that
keeps re-emitting has its "last seen" rewritten back to when it arrived — and then expires while
still being reported. Decided: condense must be off, and the map should say so rather than leave it
to be discovered.

Both the initialisation dialog and the Settings tab already fetch the Plan B document through
`PlanBDocResource` to validate its `stateType` (`FloorMapInitPresenter.java:95`,
`FloorMapSettingsPresenter.java:98`), so the setting is in hand at the moment the store is chosen —
this is a condition on data already fetched, not a new round trip.

Surface it at **store selection** (the moment the choice can still be changed) and, if cheap, at
document read (which catches a store whose condense was switched on afterwards). Fail **safe, not
silent**: if the Plan B document cannot be fetched, say nothing rather than implying condense is off.

### W6 — the roster (D3, still open)

Expiry makes `FloorMapEntityList`'s union semantics matter for the first time — §9.1 of the spec has
the exploration and a recommendation (prune, event entities only, demoting a promoted fact-and-event
entity back to fact-only). **Do not build this until D3 is answered**, and keep it as its own commit
either way: it is a behaviour change to the Tracking panel, not part of the filter.

### W7 — help text

See the next section. It is a work item, not decoration: the parameter mechanism is the thing a user
has to understand to edit their own events query without breaking it, and nothing currently explains
it anywhere in the product.

---

## Help text

Add `FloorMapEditorHelp.eventsQuery()` and hook it to a help button on the Events Query tab
(`FloorMapQueryPresenter`), which has none today. The style follows the existing entries — a `<p>`
opener, `<h4>` sections, `<ul>`/`<li><strong>…</strong> — …` bodies, `SafeHtmlUtils.fromTrustedString`.

```java
/**
 * Help for the Events Query tab, covering the parameters the floor map supplies to the query.
 *
 * @return the events-query help HTML body
 */
public static SafeHtml eventsQuery() {
    return SafeHtmlUtils.fromTrustedString(
            "<p>The query that decides where entities are drawn. It runs once per playback "
            + "tick, and returns one row per entity — the latest event at or before the time "
            + "the scrubber is on.</p>"

            + "<h4>Settings arrive as parameters</h4>"
            + "<p>Write <code>param('Name')</code> anywhere in the query and the floor map "
            + "substitutes the matching setting when it runs. The parameters are supplied to "
            + "the server with the query, so the text you see here is exactly the text that "
            + "runs — nothing is rewritten behind your back, and an error is reported against "
            + "the line and column you are looking at.</p>"
            + "<ul>"
            + "<li><code>param('EventStore')</code> — the <strong>Events Store</strong> named "
            + "on the Settings tab. This is how the query finds its data, so it belongs in the "
            + "<code>from</code> clause. Change the store on Settings and every query follows; "
            + "type the store's name literally instead and it will not.</li>"
            + "<li><code>param('FactStore')</code> — the <strong>Facts Store</strong>, the same "
            + "way. The facts query uses it; the events query normally does not.</li>"
            + "<li><code>param('ExpiryFloor')</code> — the earliest time an entity's last event "
            + "may have, as milliseconds since 1970. The floor map works it out from the "
            + "scrubber's position minus the <strong>Event expiry</strong> duration set in the "
            + "timeline's settings, and it changes every tick as you play.</li>"
            + "</ul>"

            + "<h4>How entities expire</h4>"
            + "<p>The default query ends the expiry rule with this line:</p>"
            + "<p><code>having \"Effective Time\" &gt; param('ExpiryFloor')</code></p>"
            + "<p>An entity whose most recent event is older than the expiry duration is dropped "
            + "from the result, so it leaves the map, the group counts and the area membership "
            + "together. Nothing is deleted from the store — scrub back and it reappears at the "
            + "time it was last seen, and lengthening the duration brings it back immediately.</p>"
            + "<p>Delete that line and entities never expire: every identity the store has ever "
            + "held stays on the map for ever. Change the column it names and the rule silently "
            + "stops matching. Both are yours to do, and both are easy to do by accident.</p>"

            + "<h4>Where else this query runs</h4>"
            + "<p>The same text also draws the timeline's density bars and fills the results "
            + "table on this tab. Those need the whole history rather than a moment of it, so "
            + "they run with <code>param('ExpiryFloor')</code> set to zero — the expiry line is "
            + "present but passes everything. That is why the bars still show activity from "
            + "before the expiry window.</p>"

            + "<h4>What the query must return</h4>"
            + "<ul>"
            + "<li>A column matching each role on the Settings tab's column mapping — "
            + "<strong>Entity ID</strong> is required, and at least one of "
            + "<strong>Location</strong> and <strong>Location Ref</strong>.</li>"
            + "<li>A timestamp column the timeline recognises — <code>Effective Time</code> or "
            + "<code>Event Time</code>. Without one the density bars stay empty even though the "
            + "query itself succeeds.</li>"
            + "</ul>");
}
```

**Also add a line to `FloorMapEditorHelp.timeline()`** where it lists the settings (gear) contents,
naming **Event expiry** alongside loop, the date range and Show All, and pointing at the Events
Query tab's help for what it does to the query.

**Two things the help deliberately says out loud.** That deleting the line disables expiry silently
is a consequence of the user's own decision that editing the query is their responsibility — saying
so is cheaper than a support question. And that the histogram runs the same text with a zero floor
pre-empts "why do the bars show data the map does not", which is otherwise a puzzling report.

---

## Verification

**Unit — GWT-free, JVM-tested, mutation-tested, following `TestFloorMapEventState`'s shape.**

1. **First, before anything else:** a `having` clause referencing a selected column alias parses,
   and filters as expected. This is W2's unproven premise.
2. A `having` clause referencing an alias that is *not* selected adds a hidden column and still
   filters (the `additionalFields` path) — worth pinning even though the default query does not need
   it, because a user's own query might.
3. `ExpiryFloor = 0` passes every row.
4. An entity whose last event is `D − 1 ms` before `T` is kept; `D + 1 ms` before `T` is dropped, and
   the test states which side of the boundary is inclusive.
5. The floor is `T − D` for the scrubber position, not `now − D` (R2).
6. An absent `eventExpiry` on the document reads as 24 hours, not as "off" (R8′/W1).
7. Zero, blank and negative durations are rejected by the control (W4).
8. `SimpleDuration` round-trips through the document JSON.
9. Read-only: the picker is disabled, not absent (W4).

**Manual, added to `docs/floormap-test-protocol.md` as a new session.**

10. Two-minute expiry on the bulk map: a stationary entity disappears two minutes after its last
    event, and the group occupancy count falls with it in the same read.
11. Scrub back before that point: the entity returns.
12. The timeline density bars still show activity from before the expiry window (the zero-floor
    path).
13. The Events Query tab still returns rows from before the window.
14. `condense` enabled on the store with a stationary re-emitting entity: record what actually
    happens. **This is the test that turns R12 from reasoning into evidence** — A10's reading of
    `TemporalStateDb.condense` is counter-intuitive and the code is dense.
15. Verify against `events-bulk.csv` that the entity count is below `MAX_ROWS` both with and without
    expiry, so test 10's result is not confounded by truncation.

**Build gates:** `./gradlew check` (never module-scoped) and `./gradlew :stroom-app-gwt:gwtDraftCompile`.
Module-scoped `checkstyleMain`/`checkstyleTest` are worth running first as a pre-flight — they are
seconds rather than minutes, and this session lost two full builds to style violations that a
pre-flight would have caught.

---

## Assumptions

Each says what it rests on and what changes if it is wrong.

**A1 — `having` can reference a column alias defined in the `select` below it.**
*Rests on:* `additionalFields` (`SearchRequestFactory.java:401`, `:937`) collecting having-referenced
fields and adding columns for them.
*If wrong:* the clause must reference the underlying field (`EffectiveTime`) rather than the alias,
or move into a `where` — and a `where` on a time field is lifted as a snapshot boundary by both
stores, which would break the read entirely. **This is the assumption most likely to be wrong and
the cheapest to test.** Test 1 exists for it.

**A2 — an expiry clause in the events query is inert when its floor is zero.**
*Rests on:* `DateExpressionParser` reading a bare number as epoch millis, and epoch preceding every
real event.
*If wrong:* the histogram and the Events Query tab filter when they must not, and the whole
"no stripping needed" argument that decided D1 in M3's favour collapses back to M2.

**A3 — the `having` filter runs ahead of the fetch row cap.** Verified in `LmdbDataStore`
(`:1074`, `:1081`), but it is behaviour rather than contract and could change upstream.

**A4 — the filter compares `Val`s, not rendered strings**, so it is exact regardless of the viewing
user's date-time preference.
*If wrong:* expiry becomes per-viewer inexact — the defect that ruled out M1.

**A5 — one duration for the whole document**, applied to every entity type (D4).

**A6 — the duration is a `SimpleDuration` and `getApproxMillis()` is accurate enough.** Exact to
`WEEKS`; approximate for `MONTHS`/`YEARS`.

**A7 — expiry is presentation, not deletion.** Nothing is removed from the store, so scrubbing back
still shows history and lengthening the duration restores entities with no re-ingest (R10).

**A8 — `condense` collapses a run to its *earliest* entry**, which is why it conflicts with expiry.
*Rests on:* a reading of `TemporalStateDb.condense` (`:472`).
*Still unproven on a live store* — manual test 14 exists for exactly this, and the docs rewrite in
R12 should wait for it.

**A9 — every execution of the events query is one we control.** Four are known: the overlay, the
histogram, the facts history read and the Events Query tab. A fifth added later that forgets to bind
a floor gets an error, not silence — which is the right failure, but only because of A2.

**A10 — no back-compatibility is required.** Unreleased code; existing documents may be edited by
hand, and every one changes behaviour the first time it is opened (R8′).

**A11 — there is no production data**, so no migration of any kind is in scope.

**A12 — the timeline settings dialog may edit the document.** It holds only view state today; the
Map tab's `DocPresenter` plumbing (`setDirty`, `onWrite` → `docSession.applyToWrite`) already
supports it, and group edits already travel that route.

---

## Sequencing

1. **Test A1 first.** If `having` cannot reference the alias, the shape of W2 changes and everything
   downstream moves with it.
2. W1 (document field) and W2 (query text) together — neither is useful alone.
3. W3 (binding), which is where the behaviour first becomes visible.
4. W4 (the control), after which it is configurable rather than compiled in.
5. W5 (`condense` detection), independent of the rest.
6. W7 (help text) — with W4, so the setting and its explanation ship together.
7. W6 (roster) only once D3 is answered, as its own commit.

**Rollback** is a configuration change, not a revert: a very long duration restores today's
behaviour. There is no persisted state beyond one document field and no migration to undo.

---

## Documentation impact

- **`docs/floormap-planb-events-store.md`** — the Condense row says *"safe to enable; it makes no
  difference to what the map reads"*. That becomes false under expiry (R12), and the Retention row
  should distinguish retention from expiry.
- **`docs/floormap-test-protocol.md`** — the new session above.
- **`unreleased_changes/`** — one entry. Its first line is the only text that reaches the CHANGELOG,
  and it must say that every existing floor map starts expiring entities at 24 hours.
