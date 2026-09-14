# Requirements: expiring an entity's last known event on a Floor Map

**Component:** Floor Map — the Map tab's events read and the entity overlay it draws
**Branch:** `enterprise-floor-mapping-events-last-forever`
**Status:** requirements, revision 4. The mechanism is **decided — M3** (§5, D1); one behaviour
question is narrowed but open (**D3**). Everything else is decided — see §0. Implementation plan:
`docs/floormap-event-expiry-plan.md`. Revision 3 records a
server-side parameter change built and verified on 2026-09-11 (**§9.7**), which supersedes parts of
§9.3, §9.5 and §9.6. Revision 4 settles **D1 in favour of M3** (§5) and points at the
implementation plan.
**Raised by:** "events now stay around forever — the last event for a particular ID should
disappear after a time. Ideally the time should be configurable somehow."

---

## 0. Decisions taken — 2026-09-10

| | Decision | Effect on this document |
|---|---|---|
| Back-compatibility | **Not required.** This is unreleased code; existing documents need not keep working. | **R7 and R8 withdrawn**, **A6 withdrawn**. Unblocks M3. |
| Expiry anchor | **Relative to the scrubber's position on the timeline.** | **A5 confirmed**; R2 is settled, not assumed. |
| Query-text edits (M3) | **Acceptable** — "if the users edit the query to remove that parameter that is their problem." | M3 is a live option. Its remaining objection is different and concrete — see §5. |
| Configuration scope (D2) | **Per document. New documents default to 24 hours.** | R3/R4 settled; **R8′** replaces R8. No system-wide config needed. |
| Number of durations (D4) | **One**, applied to all entity types. | A4 confirmed. |
| Presentation (D5) | **Hidden**, not shown as stale. | R1 settled. |
| `condense` (D6) | **Impractical; specify that it must be turned off.** | **R12** becomes a stated constraint rather than an open question. |
| Where configured (D7) | **Settings tab or, probably better, the timeline settings dialog.** | Explored — §9.2. The timeline dialog is affordable; the plumbing it needs is named. |
| Roster behaviour (A15/D3) | **Needs more exploration.** | Explored — §9.1. Findings and a recommendation; the call is still yours. |
| Mechanism (D1) — *2026-09-11* | **M3**, the `having` clause with the floor as a query parameter. | §5 has the reasoning, including why the objection that had favoured M2 does not hold. |

---

## 1. What happens today, and why

### 1.1 The read

`FloorMapMapPresenter.readEvents` runs the document's events query once per throttled playback tick,
bounded to `[0, t]`:

```java
// stroom-core-client/.../FloorMapMapPresenter.java:886
eventsQueryHelper.run(resolveQueryParams(query), queryParams(), 0L, t);
```

The helper turns that into a `TimeRange("CUSTOM", from, to + 1)` on the search request
(`FloorMapFullReadQueryHelper.java:284`), and `ResultStoreManager.addTimeRangeExpression`
(`ResultStoreManager.java:285`) renders it as
`EffectiveTime >= from AND EffectiveTime < to`.

Both temporal stores then **lift the upper bound out as a snapshot boundary and discard every time
term**:

| Store | Where | What it does |
|---|---|---|
| Plan B `TEMPORAL_STATE` | `PlanBSearchHelper.getQueryTime` (`:64`) → `TemporalStateDb.searchAsAt` (`:301`) | `removeTimeTerms` (`PlanBSearchHelper.java:101`) drops the lower bound; one forward LMDB pass keeps the newest entry per key with `time <= asAt` |
| SQL Temporal Store | `UpdatableTemporalStoreDaoImpl.getQueryTime` (`:580`) | `max(effective_time)` per key with `effective_time <= queryTime` (`:166`, `:492`); `getFilteredExpression` strips all time terms |

So the read means **"the latest row for every key that has ever existed, as at `t`"**. The lower
bound is decoration. `TestTemporalStoreParity.testALowerBoundAddsNothingOnceAnUpperBoundIsPresent`
(`:291`) pins exactly that, deliberately.

### 1.2 Why it reads that way

This is recent and intentional. Commit `09f20c2c5f` retired a client-side state machine
(`FloorMapEventState`, deltas per tick, a six-hour re-baseline horizon) once upstream shipped
server-side latest-per-key. The retirement was correct — but the horizon it deleted was also the
only thing that ever aged an entity out. The read went from "the last 6 hours" to "all of history",
and nothing replaced the bound.

### 1.3 The consequence

**A key that has ever been written appears on the map for ever, at wherever its last event put it.**
There is no notion of an entity having gone. The affected consumers, all fed from the same list via
`publishKnownEntities` (`FloorMapMapPresenter.java:995`):

| Consumer | Effect |
|---|---|
| Canvas glyphs | every entity ever seen is drawn |
| Group occupancy — "*n* of *m*" | counts entities that left days ago |
| Area membership | same |
| Canvas accessible summary | same |
| Tracking roster (`FloorMapEntityList`) | already a deliberate union, never pruned (`:33`, `clear()` at `:278`) — see §9.1 |
| Row cap | `MAX_ROWS = 20_000` (`FloorMapFullReadQueryHelper.java:86`) is now **a cap on distinct entities ever written**, not on history. Past it, live entities are lost in key order |

The row cap is the part that turns a presentation complaint into a correctness one. The read returns
one row per key, so a store that has seen 20 001 identities over its life stops being able to show
where anybody is.

`FloorMapGroupSnapshot` already warns that the positioned count *"is not a head-count of who is
present on site, and must not be labelled as one"* (`:59`). Today's behaviour makes that warning
much harder to live with: the count is monotonic for the life of the store.

---

## 2. Scope

**In scope.** The Map tab's entity overlay and everything derived from it — glyphs, group occupancy,
area membership, the tracking roster, the accessible summary and the empty-state status line.

**Out of scope.**

- Facts (desks, areas, the floor plan). They are static content with their own read path, and an
  expiry must not remove them from the roster — see §9.1.
- The events histogram / timeline density bars. They read whole history on purpose, and any
  mechanism must leave them doing so (this is a live hazard for M3 — see §5).
- Deleting anything from the store. See R10.
- Plan B `retention` and `condense` as features. They are inputs to this problem, not deliverables —
  see A10, A11 and M4.

---

## 3. Requirements

### Functional

**R1 — An entity whose last event is older than a stated duration is not shown.**
Given a configured duration `D`, an entity whose latest event at or before the timeline position `T`
has effective time `< T − D` must not appear in the overlay. It is **hidden**, not greyed or
annotated (D5).

**R2 — Expiry is measured from the scrubber's position on the timeline, not from wall clock.**
The comparison is against `T`, the currently selected time. Scrubbing back to 09:00 yesterday shows
who was present *then* — an entity last seen at 08:55 yesterday is shown, one last seen a week
earlier is not. Confirmed, not assumed.

**R3 — The duration is configurable per document.**
Not a compiled-in constant, and not a system-wide setting. Two floor maps over the same store can
want different durations.

**R4 — One duration for the whole document**, applied to every entity type equally (D4).

**R8′ — Events always expire. New documents default to 24 hours.**
Replaces the withdrawn R8. There is **no "no expiry" option** — no disabled state, no sentinel
value, and no UI affordance for switching it off. Every floor map has a finite duration, and the
control must not accept an empty, zero or unbounded value. Consequences worth stating, because they
follow from the decision rather than from anything else:

- **There is no configuration in which today's behaviour survives.** Every existing document changes
  the first time it is opened after this ships. That is intended — today's behaviour is the defect.
- **The duration is not nullable on the document.** A document read with the field absent (every
  document that exists today) must be treated as the 24-hour default, not as "off". That is a
  read-path default, not a migration.
- **The validation rule is a real requirement, not UI polish.** A zero or blank duration would expire
  everything instantly and present as an empty map, which is indistinguishable from a broken query
  (R11). The control must refuse it.

**R5 — Every consumer of the overlay agrees.**
An expired entity is absent from the glyphs, the group occupancy count, area membership and the
accessible summary in the same read. Partial application — the count dropping while the glyph
persists — is the specific failure the previous horizon had, and it must not recur.

**R6 — Behaviour is identical on both store types.**
A floor map over a Plan B `TEMPORAL_STATE` store and one over a SQL Temporal Store must expire
identically. Divergence here is invisible in the UI and would be diagnosed as a data problem.

~~**R7 — Existing documents keep working without their query text being edited.**~~
**Withdrawn 2026-09-10** — unreleased code, no back-compatibility requirement. This was M3's main
objection; M3 is now judged on §5's grounds instead.

~~**R8 — Disabled by default.**~~ **Withdrawn 2026-09-10** — superseded by R8′.

**R9 — Expiry must be applied before the row cap.**
Filtering after the cap leaves §1.3's failure intact: 20 000 dead entities still crowd out the live
ones (A9). Verified for each mechanism in §5 — M2 and M3 satisfy this, M1 cannot.

**R10 — Nothing is deleted.**
Expiry is a display rule. The store keeps every row, so scrubbing back still shows history, and
raising `D` immediately brings entities back with no re-ingest.

**R11 — An operator can tell why an entity vanished.**
Expiry, "the query is broken" and "the store is empty" must be distinguishable. The configured
duration is visible where it is configured; the existing empty-state status line already covers the
all-expired case (A17).

**R12 — `condense` must be off, and that must be stated where it can be acted on.**
`TemporalStateDb.condense` (`:472`) collapses a run of identical values to its **earliest** entry.
For a stationary entity that keeps re-emitting the same location, that rewrites "last seen" from
*now* back to *when it arrived* — so condense makes a live entity expire. Decided (D6): condense is
impractical alongside expiry and must be turned off. Consequences:

- `docs/floormap-planb-events-store.md` currently says condense is *"safe to enable; it makes no
  difference to what the map reads"*. That becomes false and must be rewritten.
- **Detect it, don't just document it** — decided. The Floor Map must read the selected events
  store's condense setting and say so when it is enabled, rather than leaving the operator to find
  out by watching live entities vanish. Feasible: both the initialisation dialog and the Settings tab
  already fetch the Plan B document through `PlanBDocResource` to validate its `stateType`
  (`FloorMapInitPresenter.java:95`, `FloorMapSettingsPresenter.java:98`), so the settings are already
  in hand at the point the store is chosen — the check is a condition on data already fetched, not a
  new round trip.
- Where to surface it is an implementation choice, not a requirement: at store selection (both
  places that can set the ref), at document read, or both. Selection is the more useful moment
  because that is when the choice can be changed; document read catches a store whose condense
  setting was turned on afterwards, which selection cannot. Both is cheap.
- The detection must fail **safe, not silent**: if the Plan B document cannot be fetched, say
  nothing rather than implying condense is off.

**R13 — A tracked entity that expires must not leave the map and the Tracking panel disagreeing.**
The canvas twice goes out of its way to refuse the state where `trackedObjectId` is set with nothing
highlighted and the Tracking panel's row still selected (`FloorMapCanvasPresenter.java:1153` — a
click on empty canvas is ignored rather than clearing a tracked highlight; `:1398` — Escape is
deliberately unhandled on the Map tab for the same reason). Expiring the entity someone is following
creates exactly that state unless it is handled. §9.1 shows the existing mechanism that handles it.

### Non-functional

**N1 — No additional query per tick.** The overlay already issues one read per throttled tick. The
budget is zero extra round trips.

**N2 — No new persisted client state.** The client-side state machine was just retired for good
reasons; expiry must not reintroduce accumulated positions, a cursor, or a re-baseline cadence.

**N3 — Cost is at worst one pass over the returned rows.** Server-side filtering should be a
comparison per key inside the pass that already runs, not a second scan.

**N4 — Rollback is a config change.** Setting a very long duration restores today's behaviour
without a data migration or a document rewrite.

**N5 — GWT constraints.** Any client-side part must compile under GWT: no `java.util.regex`, no
`java.time`, no server-only utilities. `SimpleDuration` (`stroom-util-shared`) is GWT-safe and
carries `getApproxMillis()` (`:83`); `DurationPicker` (`stroom-core-client-widget`) already
implements `HasValue<SimpleDuration>` and is the obvious control.

---

## 4. Assumptions

Each says what it rests on and what changes if it is wrong.

**A1 — "Events stay around forever" is about the Map tab's drawn overlay**, not about data retention
in the store and not about the Events Query tab's table.
*Basis:* the phrase "the last event for a particular ID should disappear", and the fact that the
overlay is the only consumer that reduces to one row per ID.

**A2 — The intended rule is age of the entity's own last event**, not a fixed window applied to the
store. Hide `X` when `X`'s latest event is older than `D`, evaluated per entity.
*If wrong:* a fixed store window is the retired horizon, and it loses entities that are genuinely
still present but stationary — the defect the horizon was deleted to fix.

**A3 — Expiry is hiding, not deleting.** R10.

**A4 — One duration per document, applied to every entity type equally.** **Confirmed** (D4).

**A5 — Expiry is anchored to the scrubber's position on the timeline.** **Confirmed** — no longer an
assumption. R2.

~~**A6 — The feature ships disabled.**~~ **Withdrawn** — superseded by R8′ (24-hour default, always
active).

**A7 — `SimpleDuration` is the representation and `DurationPicker` the control.**
*Basis:* both are GWT-safe and already used for durations elsewhere (Plan B retention, Pathways
settings).
*Caveat:* `getApproxMillis()` is approximate for `MONTHS` and `YEARS`. Fine for a staleness
threshold; worth not pretending otherwise.

**A8 — The client cannot reliably compute an event's age from the result it receives.**
The effective-time column arrives **rendered to the viewing user's date-time preference**.
`FloorMapQueryPresenter.latestPerEntity` documents this at `:240` and copes by parsing bare epoch
numbers or comparing ISO-8601 text lexicographically; `HistogramDataModel` parses with
`UTCDate.create` (`:124`). Under a user pattern such as `dd/MM/yyyy` both are wrong.
*Consequence:* a purely client-side expiry is inexact for any user with a non-ISO date-time
preference — silently, and per-user, so two people looking at the same map would see different
entities. This is the strongest argument against M1, and it is unaffected by the back-compatibility
decision.

**A9 — The read returns one row per distinct key, so `MAX_ROWS` is an entity-count cap.**
*Basis:* `searchAsAt` emits at most one row per LMDB key prefix; the SQL path groups by key. The
truncation message in `applyEventsOutcome` already says this.

**A10 — `condense` collapses an identical run to its earliest entry.** R12.
*Basis:* `TemporalStateDb.condense` (`:472`) keeps `newState` — the first entry of the run — and
deletes the later duplicates.
*Still worth confirming on a live store* before the docs are rewritten, because the conclusion is
counter-intuitive and the code is dense. Acceptance test 11.

**A11 — Plan B maintenance is not prompt.** Merge runs every minute, maintain (which drives condense
and retention) every ten (`CronExpressions.EVERY_10_MINUTES`, `PlanBModule.java:126`).
*Consequence:* any store-level mechanism (M4) expires on a ten-minute granularity at best.

**A12 — `stroom-planb` and `stroom-sqlstore` are upstream-owned.** A change there is either an
upstream contribution or a local fix carrying the repo's `STROOMWORKS-LOCAL` merge markers.

**A13 — Changing the stores' treatment of a lower bound is a semantic change to a shared path**,
not a floor-map-private one. Any dashboard, query or `View` writing
`where EffectiveTime > X and EffectiveTime <= Y` against a temporal store currently gets
latest-per-key at `≤ Y` with `X` ignored; after M2 it would get that only where the row is also
`>= X`. Arguably what the author wrote and expected, but it is a change, and
`testALowerBoundAddsNothingOnceAnUpperBoundIsPresent` exists to pin the present behaviour. It would
be rewritten, not deleted.

**A14 — There is no production data and existing setups can be rebuilt.** Stated by the user.
Reinforced by the back-compatibility decision: no migration of any kind is in scope.

**A15 — ~~The tracking roster's union semantics are deliberate and may want to stay.~~**
**Explored — see §9.1.** The union's stated justification is now factually obsolete, and the
machinery to prune safely already exists.

**A16 — Timeline history must be unaffected.** Scrubbing to a past `T` shows the state at `T`,
including entities long gone by now. The histogram continues to count every row.

**A17 — The empty-state status line and stage reporter need no new state.** An expiry that hides
every entity presents as "no events at this time", which is accurate. A protocol test rather than
new code.

---

## 5. Candidate mechanisms

**D1 is still open.** Three viable options now that R7 is withdrawn.

### M1 — Filter client-side on the returned rows

Parse the effective-time column, drop rows older than `T − D` in `applyEventsOutcome`.

- Entirely local; no upstream change; one release.
- **Inexact** under any non-ISO user date-time preference (A8), and differently inexact per viewer.
  Two people on the same map see different entities.
- **After the cap** (A9), so it does not fix the entity-count ceiling. **Fails R9.**

### M2 — Honour the lower bound in both stores' point-in-time paths

Plan B: keep the lower bound out of `removeTimeTerms` and, in `searchAsAt`, emit the retained entry
only if its time is at or after the floor — a comparison per key inside the pass that already runs.
SQL: add the same floor to the `max(effective_time)` sub-select.

The floor map then expresses expiry by changing one number at the call site:

```java
eventsQueryHelper.run(..., t - durationMs, t);
```

- **Exact**, store-side, ahead of the cap. Satisfies R1–R6 and R9–R13 cleanly.
- The bound travels in the `TimeRange`, so **no query text is touched** — the histogram and the
  Events Query tab are untouched by construction. That was a nice-to-have under R7; with R7 gone it
  is still the thing that keeps M2 free of M3's hazard below. Note the histogram passes
  `timeRange = null` deliberately (`HistogramQueryHelper.java:138`), precisely so it stays on the
  all-history path — so an M2 lower bound cannot reach it even by accident.
- Costs an upstream change to shared code (A12) and a deliberate semantic change with a parity test
  to rewrite (A13).
- Aligns with `docs/planb-snapshot-read-proposal.md`, the standing conversation with the Plan B
  maintainers about exactly this read.

### M3 — Express the floor in query text (`having`, with the floor as a query param)

> **Updated 2026-09-11 (§9.7).** As written, M3 could not have worked at all: `resolveParam`
> accepted only a bare-word token, so `param('Floor')` threw at parse in every position. The
> server-side parameter change has since fixed that, so M3 is now implementable — but its actual
> objection, the shared histogram query text, is unchanged.

`having` compiles to `tableSettings.aggregateFilter` (`SearchRequestFactory.java:821`), which
`TableResultCreator` hands to a `FilteredMapper` (`:99`) and thence into `dataStore.fetch`.

**Verified: the filter runs ahead of the row cap.** In `LmdbDataStore`, rows flow through
`mapper.create(item).forEach(row -> { … fetchState.length++ … })` (`:1074`, `:1081`) — a row the
filter rejects never enters the body, so it never counts against `range.getLength()` and never
increments `totalRowCount`. Twenty thousand *surviving* rows are returned, and the existing
`totalResults > rows.size()` truncation check stays meaningful. **M3 satisfies R9.**

It is also **exact**: the mapper compares `Val`s inside the data store, before any rendering to the
user's date-time preference. So M3 avoids A8 entirely.

Two real costs, both concrete:

1. **The query text is shared by three executions, and one of them passes no params at all.**
   Traced in full in §9.3. `buildEventsHistogramQuery` returns the events query **verbatim**
   (`FloorMapMapPresenter.java:1700`), and `HistogramQueryHelper.run` passes `null` for params
   (`:137`). An expiry param in the query text therefore arrives at the histogram unbound, and an
   unbound `param()` in a term value becomes an **empty** term value, which throws during predicate
   construction — before any row is fetched. The histogram registers no error listener, so the
   timeline's density bars simply go empty with nothing said. This is now M3's main objection: not a
   compatibility concern but a live defect, and one that would bite any future param the Map tab
   wants to send.
2. **The floor must be re-injected per tick** as the scrubber moves (R2), so the clause and the
   param have to stay in step with a value that changes 3× a second. Workable — a new search starts
   each tick anyway — but it is state in the query text rather than at the call site.

The user's stance on a user deleting the clause is settled: *"if the users edit the query to remove
that parameter that is their problem."* Noted, and it is no longer counted against M3.

### M4 — Plan B `retention`

Set retention on the store; old rows are deleted, and a key whose rows have all aged out disappears.

- Zero code.
- **Deletes data** (violates R10), is **store-wide** rather than per document (violates R3), is
  shared with every other consumer of that store, and expires on the maintain schedule at best
  (A11).
- Recorded so nobody reaches for it by mistake.

### Comparison

| | M1 client filter | M2 store lower bound | M3 `having` + param | M4 retention |
|---|---|---|---|---|
| Exact (R1) | **no** (A8) | yes | yes | yes |
| Ahead of the row cap (R9) | **no** | yes | yes *(verified)* | yes |
| Per document (R3) | yes | yes | yes | **no** |
| Non-destructive (R10) | yes | yes | yes | **no** |
| Both store types (R6) | yes | yes, if both changed | yes | **Plan B only** |
| Leaves the histogram alone (§2) | yes | yes | **no** — see M3(1) | yes |
| Upstream change needed | no | **yes** | no *(one already made — §9.7)* | no |

**Recommendation — M3, revised 2026-09-11.** Earlier revisions recommended M2 on the strength of
one objection to M3: that the histogram shares the query text and must read all history, so the
expiry clause would have to be *stripped* for it — the "find where the clause ends" problem
`FloorMapEventsQueryOrder` refuses to solve for `sort`.

**That objection was wrong, and it was the load-bearing one.** The clause does not need stripping.
It carries its floor as a parameter, so an execution that must not filter simply **binds the floor to
zero**:

```java
histogramQueryHelper.run(query, queryParams(0L));   // epoch — the clause passes everything
```

`DateExpressionParser` parses a bare number as epoch milliseconds
(`DateExpressionParser.java:158` → `fromEpochMillis`), so the neutral value is just `"0"`. No text
is rewritten, and no clause boundary has to be found. I was reasoning from the shape the problem had
before the parameter work rather than from the current code.

With that removed, two things decide it, and both favour M3:

- **R6 comes free.** M3's filter runs in the LMDB result store, above both temporal stores, so Plan B
  and the SQL store behave identically by construction. M2 needs two separate store changes that
  must be kept in step by hand — and only Plan B has a `searchAsAt` path to change at all.
- **Blast radius.** M2 changes what a lower time bound *means* for **every** consumer of both
  temporal stores (A13) — a dashboard writing `EffectiveTime > X and <= Y` would start honouring
  `X`. That is a large semantic change to shared code in service of one document type's display
  rule. M3 changes one document's query.

There is also an argument from where the rule belongs. Expiry is presentation, not storage (R10 —
nothing is deleted). A filter above the store expresses that; a filter inside the store's
point-in-time path does not.

**M2 remains the better answer** if expiry should be invisible and impossible for a user to edit
out, or if binding the floor at three call sites feels too easy to get wrong. **M1 is not worth
building**: it fails R9 and is wrong per-viewer.

---

## 6. Acceptance criteria

Automated, in the style the feature already uses (GWT-free logic in `stroom-core-shared`,
unit-tested on the JVM, mutation-tested):

1. An entity whose last event is `D − 1 ms` before `T` is shown; `D + 1 ms` before `T` is not.
2. The boundary is inclusive or exclusive by a stated rule, and the test says which.
3. Moving `T` backwards past an entity's last event shows it again (R2/A16).
4. Glyphs, group occupancy, area membership and the accessible summary reflect the same set in the
   same read (R5).
5. Parity: the same fixture over Plan B and over a SQL Temporal Store expires identically (R6) —
   extends `TestTemporalStoreParity`, where the existing lower-bound assertion lives.
6. If M2: `testALowerBoundAddsNothingOnceAnUpperBoundIsPresent` is **replaced** by its inverse, with
   the reasoning recorded.
7. If M3: the histogram still reads whole history and its density bars are unaffected — the
   specific failure traced in §9.3. Worth asserting at the unit level too: an unbound `param()` in a
   `having` term value produces an empty term value and a thrown `MatchException`, so a test that
   pins that behaviour stops the fix regressing silently.
8. A new document is created with a 24-hour duration (R8′), and `SimpleDuration` round-trips through
   the document JSON.
9. Tracking a doomed entity and letting it expire leaves the panel and the canvas in step, with no
   row selected and no `trackedObjectId` (R13/§9.1).

Manual, added to `docs/floormap-test-protocol.md` as a new session:

10. Set a two-minute expiry on the bulk map; a stationary entity disappears two minutes after its
    last event and the group count falls with it.
11. Enable `condense` on the store with a stationary re-emitting entity and record what actually
    happens (A10). This is the test that turns R12 from reasoning into evidence.
12. Scrub back before the expiry point; the entity returns.
13. Verify against the `events-bulk.csv` fixture that the entity count is below `MAX_ROWS` both with
    and without expiry, so test 10's result is not confounded by truncation.
14. Read-only user: the duration control is visible and disabled, not absent (§9.2).

Build gates unchanged: `./gradlew check` (never module-scoped) and, for any client change,
`./gradlew :stroom-app-gwt:gwtDraftCompile`.

---

## 7. Documentation impact

- **`docs/floormap-planb-events-store.md`** — the Condense row says *"safe to enable; it makes no
  difference to what the map reads"*. Wrong under expiry (R12). The Retention row must distinguish
  retention from expiry (M4).
- **`docs/floormap-test-protocol.md`** — a new session per §6.
- **`unreleased_changes/`** — one entry. Its first line is the only text reaching the CHANGELOG.
- **`docs/planb-snapshot-read-proposal.md`** — if M2 is chosen, this becomes the second ask in the
  same conversation and should be folded in rather than raised separately.
- **`docs/floormap-remediation-plan.md`** — record the outcome next to the F13 retirement, since this
  is the gap that retirement opened.

---

## 8. Open decisions

**D1 — Mechanism. Answered 2026-09-11: M3**, and the reasoning is in §5. The objection that had
been carrying M2 — that the histogram would need the expiry clause stripped from shared query text —
does not hold: the clause takes its floor as a parameter, so an execution that must not filter binds
that floor to zero. What is left favours M3, because it satisfies R6 without two store changes
agreeing, and because M2 would change the meaning of a lower time bound for every consumer of both
temporal stores (A13) to fix one document type's display rule.

M2 stays the answer if expiry should be invisible and un-editable. M1 is not recommended. The
implementation plan is `docs/floormap-event-expiry-plan.md`.

**D3 — Does an expired entity leave the tracking roster? Narrowed, still yours.** §9.1 explores it:
the union's justification is obsolete, pruning is already handled safely by existing code, and there
is one genuinely open sub-question about promoted fact-and-event entities.

*Answered and folded into §0/§3:* D2 (per document, 24-hour default), D4 (one duration), D5
(hidden), D6 (condense off), D7 (timeline settings dialog — §9.2).

---

## 9. Explorations

### 9.1 The tracking roster (A15, D3)

**What the roster is.** `FloorMapEntityList` is a union of everything seen since the last `clear()`,
which happens on document (re-)read (`FloorMapMapPresenter.java:700`). It holds **both** event
entities (`update`, fed from `placed` at `:1492`) and static facts (`updateFacts`, at `:1283`) —
objects, backgrounds and areas. The Tracking panel lists event entities by default and folds the
fact-only rows in behind a **Show Facts** toggle. Selecting a row highlights the entity, centres the
camera on it and follows it.

**Finding 1 — the union's stated reason no longer holds.** The javadoc says entities are never
removed when absent because *"the events query at a given playback instant only returns entities
with events near that time"* (`:33`). That was true of the retired windowed read. It is false now:
the read is latest-per-key over all history, so every event entity is returned on every read. **The
union is currently a no-op for event entities** — the roster and the events list are the same set,
which is why nobody has noticed. Expiry is the first thing that would make the union do anything at
all, and what it would do is retain exactly the entities expiry exists to remove.

**Finding 2 — pruning is already handled safely, and the code documents why.**
`FloorMapTrackingPresenter.applyFilter()` (`:523`) ends:

```java
final EntityEntry selected = selectionModel.getSelectedObject();
if (selected != null && !visible.contains(selected)) {
    selectionModel.clear();          // :541
}
```

with the reason stated above it: *"Hiding the facts while a fact row is being tracked would leave
the canvas following something the panel no longer lists, so that selection is cleared — which stops
tracking through the usual selection handler."* That is precisely R13's requirement, already built.
An entity dropping out of the roster flows `setData` → `applyFilter` → `selectionModel.clear()` →
the selection handler → `selectionConsumer.accept(null)` → `setTrackedObjectId(null)`, leaving the
panel and the canvas in step. Nothing new is needed.

**Finding 3 — *keeping* expired entities is the worse option.** It would leave the panel listing
entities that are not on the map; the panel is *named as the map's text alternative*, so a
disagreement between them is an accessibility defect, not a cosmetic one. It would also let a user
start tracking an entity with no position — creating the "tracking with no highlight" state the
canvas refuses to create twice over (`FloorMapCanvasPresenter.java:1153`, `:1398`). And the roster
would grow unboundedly across a long session, since nothing prunes it until a re-read.

**Recommendation.** Prune the roster, restricted to event entities, and rewrite the javadoc to say
why the union no longer applies. Facts must stay (§2) — they are not events and are not subject to
expiry.

**The one open sub-question.** `admit` *promotes* a fact-only entity to from-events when it turns up
in the events stream, and explicitly never demotes: *"an event entity is not demoted by a fact
carrying the same key."* So for an entity that is both a fact and an event — a tracked asset that is
also a placed object — expiry has three possible answers:

| | Behaviour | Consequence |
|---|---|---|
| **a** | Demote to fact-only | Row survives behind Show Facts; the map still shows the static object. Coherent, and needs a new demotion path that the current javadoc forbids. |
| **b** | Remove entirely | Simplest, but loses a row for a thing still on the map as a fact. |
| **c** | Leave promoted entities alone | No new code; expiry silently doesn't apply to this class of entity. |

**(a)** is the coherent answer and my recommendation, but it is a deliberate reversal of a
documented rule, so it wants your agreement rather than my judgement.

### 9.2 Where the control goes (D7)

You leaned toward the timeline settings dialog. It is affordable, and here is exactly what it costs.

**The dialog is currently pure view state.** `FloorMapTimelineSettingsPresenter` holds loop playback
and the date range in the view and never touches the document — no `DirtyUiHandlers`, no
`ReadOnlyChangeHandler`, no `FloorMapDoc`. Compare `FloorMapSettingsPresenter`, which implements
`HasUiHandlers<DirtyUiHandlers>, ReadOnlyChangeHandler` and is the document editor. So a **persisted**
field there is a new kind of thing for that popup.

**But the Map tab already persists document fields**, which is the part that makes this cheap.
`FloorMapMapPresenter` extends `DocPresenter`, so it already has `setDirty` (`DocPresenter.java:63`)
and an `onWrite` that delegates to the session: `docSession.applyToWrite(document)`
(`FloorMapMapPresenter.java:772`). Group edits made from the Map tab already travel this route —
stage into `FloorMapDocSession`, call `onChange()`, which re-runs `onWrite` and diffs against the
loaded document to light the save button (`:790`–`:796`).

**So the work is:** a `SimpleDuration` field on `FloorMapDoc`; a `DurationPicker` in the timeline
settings view; stage-and-`onChange()` on value change; and gate the picker on the `readOnly` flag
that `onRead` already receives (`:685`) but currently ignores, because until now the Map tab had
nothing editable. That last point is the only genuinely new obligation, and it is one line plus a
test (acceptance test 14).

**Recommendation.** The timeline settings dialog, as you suggested — it is where a user thinks about
time, and the plumbing is already three-quarters built. The Settings tab remains the fallback if you
would rather not have that popup edit the document at all, and costs nothing extra to implement
there instead.

### 9.3 Does the histogram use the events query, and what does it pass?

**Yes, verbatim, and it passes nothing.** Both halves matter for M3, so here is the whole chain.

**The query text is the same text.** `buildEventsHistogramQuery()` returns
`getEntity().getEventsQuery()` unchanged when one is configured
(`FloorMapMapPresenter.java:1700`–`:1704`). The minimal `Key`/`EffectiveTime` fallback over the
**facts** store is reached only when the document has no events query at all. So the histogram, the
Map overlay and the Events Query tab's table are three executions of one string.

**And that is the normal case, not an edge case.** Expiry only matters to a document that has an
events query; a document without one has no entities to expire. So in every configuration where this
feature does anything, the histogram is running the same text the expiry clause would be added to.
M3's hazard is therefore the default path, not a corner.

**A correction to the code's own account of this.** `runHistogramQuery`'s javadoc says only one query
runs *"to avoid double-counting when both events and facts are sourced from the same data store"*,
and the inline comment says the events query *"typically selects from the same store as the facts
query"*. **Both premises are impossible** — see §9.4.

**It passes no parameters and no time range.** `HistogramQueryHelper.run(query)` calls
`startNewSearch` with `null` for params and `null` for timeRange (`:133`–`:142`). Both are
deliberate: the null time range is documented as what keeps the read on the DAO's standard path,
returning every historical entry rather than one row per key — which is exactly what a density
display needs.

**So how does `from param('EventStore')` work today?** The caller substitutes it **textually**
before the helper is reached: `runHistogramQuery` calls
`resolveQueryParams(buildEventsHistogramQuery())`, and `resolveQueryParams` does a plain
`String.replace("param('K')", "\"value\"")` over the keys from
`FloorMapQueryPresenter.buildQueryVariables` (`:612`) — which supplies exactly two, `FactStore` and
`EventStore`. Any other `param('X')` survives into the query as literal text with nothing bound to
it.

**What an unbound param then does — traced, not assumed:**

| Step | Where | Result |
|---|---|---|
> **Superseded 2026-09-11 — see §9.7.** This table describes what happens *now*, after the
> server-side change. It did **not** describe the behaviour at the time it was written: back then
> `resolveParam` accepted only a bare-word token, so `param('X')` — the quoted form every caller
> writes — threw `"Expected param name"` at the first row and never reached `paramMap` at all.

| `param('X')` in a term value | `SearchRequestFactory.resolveParam` (`:610`) | `paramMap.get("X")` → **null** |
| null appended to the value | `addValue` (`:526`) — `if (val != null)` | nothing appended |
| term built | `:398` — `.value(value.toString().trim())` | value is **`""`**, not null |
| null-guard | `ExpressionPredicateFactory.ifValue` (`:681`) | guards `null` only, so `""` **passes** |
| date parse | `getTermDate` (`:492`–`:509`) | throws `MatchException("… but was given string \"\"")` |
| where it lands | `TableResultCreator` outer `catch (RuntimeException)` | error consumed; the throw happened during mapper construction, so `dataStore.fetch` is never reached — **zero rows** |
| who sees it | `HistogramQueryHelper` | ~~registers **no error listener**~~ — since 2026-09-11 it does, so this now reaches the browser console |

Net effect: **empty density bars, no message.** My revision-2 text attributed this to the from-clause
failing, which is what the code comment at `FloorMapMapPresenter.java:1649` describes. The from
clause does fail on an unresolved param, but by a different route: `param('X')` is a
`FUNCTION_GROUP` token, and a from clause requires `TokenType.isString`
(`SearchRequestFactory.java:271`) — which covers `STRING`, the quoted strings and `TokenType.PARAM`
(the `${…}` syntax), but not `FUNCTION_GROUP`. So an unsubstituted `from param('X')` throws
`"Expected a token of type string"` at parse time. The chain traced above is the **term value**
case, which is the one an expiry floor would take.

**Which explained why the helper's null params was harmless, and why that was luck.** All of this
has since changed: the `from` clause now resolves `param()` server-side, the textual substitution is
gone, and the histogram passes params like every other read. §9.7 records what was done and what it
means for M3.

**The alternative injection route is worse, not better.** Adding a third key to
`buildQueryVariables` would reach the histogram, since `resolveQueryParams` runs on its query too.
But that method is `static` and takes only the `FloorMapDoc`, so it has no access to `T` — and the
floor moves with the scrubber three times a second. Reshaping it to carry `T` would then leave the
histogram expiry-filtered, which is wrong for a density display (§2).

**Conclusion.** If M3 is chosen, the histogram path must be fixed deliberately — most simply by
having `buildEventsHistogramQuery` strip the expiry clause rather than pass the query through
untouched. That is the same "find where the clause ends" problem that
`FloorMapEventsQueryOrder`'s javadoc records as the reason it detects `sort` rather than removing
it — so the fix is not free. M2 has no equivalent obligation.

### 9.4 The two stores cannot be the same store — and one histogram fallback is dead

Checked at the user's prompting, because `runHistogramQuery`'s reasoning depends on it.

**The store references are type-disjoint, enforced in both places that can set them.**

| | Constraint | Where |
|---|---|---|
| Facts Store | `SqlTemporalStoreDoc.TYPE` only | `FloorMapInitPresenter.java:137`, `FloorMapSettingsPresenter.java:159` |
| Events Store | `PlanBDoc.TYPE` only | `FloorMapInitPresenter.java:144`, `FloorMapSettingsPresenter.java:153` |

`FloorMapInitPresenter`'s own javadoc states the reason (`:63`–`:70`): the facts store *"has to be a
SqlTemporalStoreDoc, because the Editor tab writes spatial data back to it"*, while the events store
is *"a PlanBDoc, which is only ever read"* — and that the two are *"deliberately not
interchangeable"*. Facts are edited in place through `SqlTemporalStoreResource`, which only the SQL
store implements.

**So `runHistogramQuery`'s two comments are both wrong.** Double-counting from a shared store cannot
arise; the events query does not "typically" select from the facts store, and by default cannot —
its from clause is `param('EventStore')`. Only a hand-edited events query naming the SQL store by
literal name could produce the overlap the comments assume, and the refs would still be disjoint.

**The real reason only one query runs** is simpler and worth writing down instead: the timeline's
density bars show *event* activity, and the facts query is a **fallback for a document with no events
query** — not a second source that would need adding up. Since the stores are different, counting
both would not double-count; whether it *should* is a product question nobody has asked, and
events-only is defensible for a scrubbing timeline.

**The else-branch is unreachable in any working configuration, and broken where it is reachable.**

```java
final String eventsHistQuery = resolveQueryParams(buildEventsHistogramQuery());
if (eventsHistQuery != null && !eventsHistQuery.trim().isEmpty()) {
    histogramQueryHelper.run(eventsHistQuery);
} else {
    final String factsHistQuery = resolveQueryParams(getFactsQueryToUse());
    if (factsHistQuery != null && !factsHistQuery.trim().isEmpty()) {
        factsHistogramQueryHelper.run(factsHistQuery);   // never usefully reached
    }
}
```

`buildEventsHistogramQuery()` **already falls back to the facts store itself** — it emits
`from "<factsStoreName>" select Key, EffectiveTime` and runs it through the *events* helper. So the
`else` needs that method to return null, which requires **no events query and no usable
`factsStoreRef`**. In exactly that state, `getFactsQueryToUse()` builds
`from param('FactStore') …` (`FloorMapQueryBuilder.java:70`), and `buildQueryVariables` only
contributes a `FactStore` key when `factsStoreRef` and its name are present — the same condition that
just failed. So `param('FactStore')` reaches the server unresolved with no params bound, the data
source resolves to null, and the query fails. `factsHistogramQueryHelper` therefore runs only where
it is guaranteed to produce nothing.

**Resolved 2026-09-11 — the facts path is deleted.** Decided: the histogram queries the events
store and nothing else; a map with no events query shows no bars. Removed from
`FloorMapMapPresenter`:

- the `factsHistogramQueryHelper` field, its construction, its `init`/`reset` in `onRead`, and its
  `reset` in `onClose`;
- `buildEventsHistogramQuery()` entirely — with the facts fallback gone it was
  `getEventsQueryToUse()` spelled twice, so `runHistogramQuery` now calls that directly;
- both comments asserting the impossible premise, replaced by what is actually true and why the
  fallback was wrong rather than merely redundant.

Two counts became correct as a side effect: the class javadoc's "Three `QueryModel`-based searches"
was already wrong at four, and `onClose`'s "five result stores" was left over from the retired
events state machine. Both now read three, which is what there are.

**One behaviour follows from the deletion and is worth stating rather than discovering.**
`setShowAllEnabled(true)` is reached only from `setDataRange`
(`FloorMapTimelinePresenter.java:723`–`:727`), which the histogram's `dataRangeHandler` drives. So a
map with no events query now has **Show All disabled** where previously the facts fallback enabled
it. That is the better behaviour — Show All would otherwise fit the timeline to the extent of
*floor-plan edits*, which is not what an events timeline should span — but it is a visible change for
a document with no events query.

**Bearing on the expiry requirements:** none. No requirement changes; §9.3's account of the
histogram is now simpler, because the events query is the histogram's only source in every
configuration rather than merely the normal one. That makes M3's contamination unconditional.

### 9.5 Why `HistogramQueryHelper` passes `null` for params

Asked directly, and the answer is **no reason — it is inherited, not chosen.** The history, since the
distinction between "deliberate" and "never revisited" decides how much weight the line can bear.

| Date | Commit | What it did |
|---|---|---|
| 2026-07-02 | `d2ecee9a35` "Use parameters in queries to allow changing of stores and queries" | Introduced `param('EventStore')` and `buildQueryVariables` |
| 2026-07-06 | `622e52670c` "Separate histogram component" | Extracted the helper out of `FloorMapMapPresenter`. The inline code it replaced already passed `null` for params — from a time when the histogram query text carried no params at all — and the extraction carried that over verbatim |
| 2026-07-14 | `1653eb2d76` "Bug fixes and testing" | Added `resolveQueryParams` and wrapped **both** histogram queries in it. This fixed the breakage at the **call site**, by substituting into the text, rather than by passing params to the helper |
| 2026-08-27 | `bc9abcedf4` "Correct javadoc that describes behaviour the code does not have" | Touched the same call, but only to fix the argument *labels* after `timeRange`, which were shifted by one and named a `fireEvents` parameter that does not exist. `null   // params` was not examined |

`HistogramQueryHelper` has had exactly one consumer throughout — `FloorMapMapPresenter`. Pathways'
trace histogram is a separate widget (`TraceHistogramWidget`) on its own data path, so no other
caller has ever exercised the parameter.

**The telling asymmetry.** The `timeRange` null carries a javadoc paragraph explaining that it is
load-bearing — it keeps the read on the all-history path. The `params` null carries a bare `// params`
label, because there was never a decision to document.

**And it could not have helped — for a stronger reason than I first gave.** I wrote that the from
clause could not take a `param('X')` token, so substitution was required there regardless. True, but
it understates it: `resolveParam` accepted only a **bare-word** token, so `param('X')` was
unresolvable server-side in *every* position, value positions included. The native `Param` list was
not merely redundant for this syntax — it never served it at all. The null was harmless because
nothing it could have carried would have worked.

**Superseded 2026-09-11.** Both halves are fixed and the helper now takes params — see §9.7.

### 9.6 The `param()` situation in full — and the bug it is holding

> **Historical from 2026-09-11.** This section describes the mechanism as it stood when the
> question was asked. It has since been replaced — see §9.7. It is kept because the reasoning is
> what led to the change, and because the "two mechanisms" table is still the clearest statement of
> what the two syntaxes do.

There are **two parameter mechanisms** in play on the same query text, serving two different
syntaxes, and the three executions of a floor map's events query treat them inconsistently. Today
that is harmless by coincidence. Expiry is the kind of change that ends the coincidence.

#### The two mechanisms

| Syntax | Who resolves it | When | Where it works |
|---|---|---|---|
| `${key}` | **Upstream.** `ParamUtil.replaceParameters` (`:135`), tokeniser-aware via `BasicTokeniser`, supports a default-value separator | Server-side, on expression terms | Value positions in the `where` expression |
| `param('key')` | **Local.** A blind `String.replace` on the query text before it is sent — `QueryEditPresenter:431`–`:440` (marked `STROOMWORKS-LOCAL`, added by `d2ecee9a35`) and a second copy in `FloorMapMapPresenter.resolveQueryParams` | Client-side, pre-parse | **Anywhere in the text** |
| `param('key')` *left unsubstituted* | Upstream's `SearchRequestFactory.resolveParam` (`:600`) against the request's `Param` list | Server-side, at parse | **Value positions only** |

`QueryEditPresenter`'s own javadoc states the design: *every* occurrence is substituted textually
*"not just those in the `from` clause, though that clause is the reason it is needed, since it
accepts only string literals. The parameters are additionally passed natively … which therefore
**only ever serves parameter references written in some other form**."*

That last clause is the crux. **The native `Param` list is dead weight for `param('key')`** — by the
time the parser sees the text, every such reference for a known key is already a quoted literal. The
native list exists for `${key}`.

#### The three executions, and the odd one out

| Execution | Textual substitution | Native params |
|---|---|---|
| Events Query tab — `QueryEditPresenter:441` | yes, every `param('key')` | **yes** |
| Map overlay — `readEvents` → `resolveQueryParams` + `queryParams()` | yes | **yes** |
| **Histogram** — `runHistogramQuery` → `resolveQueryParams` | yes | **no — `null`** |

#### Worked examples

**A. The default query — works everywhere.**
`from param('EventStore')` is substituted to `from "floor_map_events"` client-side. The parser never
sees a param. The histogram's null params is irrelevant, because there is nothing left to resolve.

**B. A key the substitution map does not know — fails everywhere, equally.**
`buildQueryVariables` hardcodes exactly two keys, `FactStore` and `EventStore`
(`FloorMapQueryPresenter.java:612`). So `from param('MyStore')` is not substituted and reaches the
parser as a `FUNCTION_GROUP`, which fails `TokenType.isString` (`SearchRequestFactory.java:271`) —
`TokenException: Expected a token of type string`. Same in all three executions. **The difference is
who notices:** the Events Query tab shows the error; the histogram has no error listener, so its bars
just empty.

**C. A native-only param in a value position — the latent bug, in one line.**
Add a param through `queryParams()` without adding it to the substitution map, and write
`having "Effective Time" > param('Floor')`:

| | Result |
|---|---|
| Map overlay | **works** — `paramMap.get("Floor")` resolves at parse |
| Events Query tab | fails — it builds its params from `queryVariables`, which is the same two keys |
| Histogram | fails **silently** — params are null, so `resolveParam` returns null, `addValue` appends nothing (`:526`), the term value is `""`, `ifValue` guards only against `null` (`:681`), and `getTermDate(term, "")` throws `MatchException`. `TableResultCreator` catches it into the error consumer *before* `dataStore.fetch` is reached, so zero rows come back with an error nobody is listening for |

**This is the shape M3 has.** An expiry floor is a value-position param that changes every tick. Route
it natively and the histogram empties; route it through the substitution map and the histogram gets
expiry-filtered — and `buildQueryVariables` is `static` and takes only the document, so it cannot see
`T` anyway.

#### Two smaller hazards in the same mechanism

**The substitution is not tokeniser-aware.** It is a plain `String.replace` over the whole text, so
`param('EventStore')` inside a quoted literal or a comment is substituted too. This repo already
knows that is wrong in general: `FloorMapEventsQueryOrder` deliberately runs `BasicTokeniser` first
precisely so a `sort` inside a quoted alias is not mistaken for a clause, and upstream's own
`ParamUtil.replaceParameters` is tokeniser-aware. The local substitution is the one place that is
not.

**It is duplicated.** `QueryEditPresenter`'s block and `FloorMapMapPresenter.resolveQueryParams` are
the same logic, written twice, and must stay in step. The former is in an upstream-owned file and
carries the `STROOMWORKS-LOCAL` marker; the latter is in a wholly local file and correctly does not
need one. But a fix applied to one will not reach the other.

#### What to do

Nothing is broken today, so this is not a prerequisite for expiry. It is a reason to **prefer M2**:
M2 puts the bound in the `TimeRange`, creates no value-position param, and so never meets any of the
above. If M3 is chosen instead, then fixing this stops being optional — at minimum the histogram
needs an error listener, so that the next parameter mistake is visible rather than an empty timeline.

### 9.7 What was actually built, 2026-09-11 — and what it changes

The `param()` analysis in §9.3, §9.5 and §9.6 argued that the client-side substitution was a
workaround with sharp edges and that the server could do the job instead. That change has now been
made and verified, so those sections are superseded in the specifics. This records what was done,
what the work turned up, and how it bears on **D1**.

#### What changed

| File | Change |
|---|---|
| `SearchRequestFactory` | New `resolveDataSourceName`: a `from` clause now resolves a `param('key')` reference through `paramMap`, throwing a named error when the key is unbound. `resolveParam` widened to accept quoted param names. Both hunks `STROOMWORKS-LOCAL`. |
| `QueryEditPresenter` | The `STROOMWORKS-LOCAL` text-substitution block deleted; it still passes the params natively. |
| `FloorMapMapPresenter` | `resolveQueryParams` deleted. All four reads send the query text as written, with `queryParams()`. |
| `HistogramQueryHelper` | `run` takes a `List<Param>`. It was the only read passing `null`. |

`TestSearchRequestFactoryDataSourceParam` covers it: a bound param resolves, an unbound one throws
by name, a value containing quotes and spaces is used verbatim, literal and unquoted data sources
are unaffected, and `${…}` is pinned to its existing behaviour.

#### What the work turned up — and a correction to §9.3

Writing the test found that **`param('key')` was never resolvable server-side in any position**.
`resolveParam` required a bare-word `TokenType.STRING`, and every caller writes the quoted form, so
it threw `"Expected param name"` before reaching `paramMap`. Three of the seven tests failed on the
first run against my own change.

That corrects §9.3's traced chain. The chain — null value, empty term value, `MatchException` — is
what happens **now**, for an unbound quoted param. It is not what happened before: the parse threw
first. The practical difference is that the client-side substitution was not merely the reason
`param()` worked in a `from` clause; it was the only thing that ever made that spelling work
**anywhere**.

Two consequences follow, and they point in opposite directions:

- **M3 was never implementable as written.** Its `having "Effective Time" > param('Floor')` would
  have thrown at parse, bound or not. The spec presented it as a live option on the strength of an
  analysis that assumed a resolution path that did not exist.
- **M3 is implementable now.** The same change that removed the substitution also made the syntax
  work in a value position.

#### What it does *not* change, which is the part that decides D1

**M3's real objection is untouched.** It was never that the parameter would fail to resolve. It is
that the histogram runs **the same query text** (§9.4: verbatim, and it is the events query in every
configuration where expiry does anything) while needing to read **all** history for its density
bars. An expiry clause in that text filters precisely the thing that must not be filtered. And the
alternative injection route is still blocked: `buildQueryVariables` is `static` over the
`FloorMapDoc`, so it cannot see `T`, which moves three times a second.

So M3 goes from *not implementable* to *implementable but still wrong for the histogram*. That is
not an improvement in its standing relative to M2.

**M2 is unaffected and remains the recommendation.** The bound travels in the `TimeRange`, touches
no query text, creates no value-position param, and cannot reach the histogram even accidentally
because `HistogramQueryHelper` passes `timeRange = null` deliberately.

#### What genuinely shifts the balance

Not the mechanics — the **precedent**. M2's only real objection was that it means changing
upstream-owned store code (A12, A13). This session has now made, marked and verified exactly that
kind of change in upstream-owned query code, with tests. The ownership boundary is the same; the
question of whether we are willing to cross it has been answered once in the affirmative, which
makes M2's cost easier to size:

- a change of comparable scale, in `PlanBSearchHelper.removeTimeTerms` / `TemporalStateDb.searchAsAt`
  and the SQL store's sub-select;
- carrying `STROOMWORKS-LOCAL` markers the same way;
- with `TestTemporalStoreParity` rewritten rather than deleted (acceptance criterion 6).

**D1 stands open**, but the argument for M2 is stronger than it was, and M3's is not.

#### Also now recorded outside this spec

Three write-ups came out of the same work, none of which block expiry:

- `docs/task-query-validate-drops-parameters.md` — `validateQuery` sends no params, so a
  parameterised query fails validation while running fine.
- `docs/task-query-dollar-param-in-from-clause-resolves-key.md` — `from ${X}` resolves the key name,
  silently.
- `docs/task-histogram-failures-are-console-only.md` — the new error listener writes to devtools and
  nowhere the user looks.

---

## 10. Facts

Facts raise a related question — a floor plan accumulates every desk ever placed, and the only way to
remove one erases it from history — but it is **a different problem with a different answer**, so it
has its own document: `docs/floormap-fact-removal-requirements.md`.

The short version, because it bears on the language used here: events are *observations*, so silence
means an entity may be gone and an age-based rule is the right shape. Facts are *declarations*, so
silence means nothing at all; a floor plan that has not changed in three years is stable, not stale.
**Facts do not expire, they are retired.** Nothing in this document's mechanism should be reused for
them.
