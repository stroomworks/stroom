# Floor Map query call paths

**Component:** Floor Map — every StroomQL query the feature issues
**Purpose:** reference. What runs, what triggers it, what it sends, and which store behaviour it
gets back.

There are **four** queries. Two of them (1 and 3) run the *same query text* — the document's events
query — and get *different store semantics*, which is the single most surprising thing in here and
is why §7 exists.

Everything below was read from the tree at the commit this document was added; line numbers are
anchors, not guarantees.

---

## 1. At a glance

| # | Query | Owner | Trigger | Cadence | `TimeRange` sent | Row cap | Store behaviour |
|---|---|---|---|---|---|---|---|
| **1** | **Events read** | `FloorMapMapPresenter` | `TimeChangeEvent` | ≤ 1 per 300 ms | `[0, t+1)` | 20 000 | **snapshot** — one row per key at or before `t` |
| **2** | **Facts history** | `FloorMapMapPresenter` | 10 s heartbeat | ≥ 60 s apart | **none** | 20 000 | **all history** |
| **3** | **Histogram** | `FloorMapMapPresenter` | timeline range change | on demand | **none** | 10 000 | **all history** |
| **4** | **Events Query tab** | `FloorMapQueryPresenter` | user presses run | manual | user's toolbar | table's | depends on the user's range |

Query text by source:

| Query | Text comes from |
|---|---|
| 1, 3, 4 | `FloorMapDoc.getEventsQuery()` — user-editable, seeded by `FloorMapEventsQuery.defaultQuery()` ([FloorMapEventsQuery.java:113](../stroom-core-shared/src/main/java/stroom/floormap/shared/FloorMapEventsQuery.java:113)) |
| 2 | **Generated on every call** from the Settings tab's value schema by `FloorMapQueryBuilder.buildFactsQuery` — there is no user-editable facts query |

---

## 2. The shared backbone

All four go through the same client model and the same server pipeline. Only the arguments differ.

```
  <caller>.startNewSearch(componentId, componentName, query, params,
                          timeRange, incremental, storeHistory, queryInfo, null)
      │   QueryModel.java:179
      │     ├─ reset(NO_LONGER_NEEDED)      destroys the previous result store
      │     ├─ builds QueryContext{params, timeRange, queryInfo, dateTimeSettings}
      │     ├─ builds QuerySearchRequest{query, queryContext, incremental, ...}
      │     ├─ applies `timeout` if set     ← STROOMWORKS-LOCAL, QueryModel.java:218
      │     ├─ setSearching(true)
      │     └─ polling = true; poll()
      ▼
  REST  POST .../query/search            QueryResource.java:126
      ▼
  QueryResourceImpl.search                QueryResourceImpl.java:183
      ▼
  QueryServiceImpl.search → processRequest      QueryServiceImpl.java:538, :654
      ├─ mapRequest()                     QueryServiceImpl.java:578
      │    └─ SearchRequestFactory.create(query, sampleRequest, expressionContext)
      │         └─ parses the StroomQL; resolveDataSourceName() resolves
      │            `from param('key')` against the Params   ← STROOMWORKS-LOCAL
      │            SearchRequestFactory.java:271, :625, :644
      └─ searchResponseCreatorManager.search(mappedRequest)
      ▼
  ResultStoreManager.search                ResultStoreManager.java:150
      ├─ picks the SearchProvider by data source
      ├─ addTimeRangeExpression(...)       ResultStoreManager.java:230, :285
      │    ONLY if the provider declares a time field, and ONLY if a TimeRange
      │    was sent. Renders it as expression terms ANDed with the user's:
      │        from → EffectiveTime >= from      (GREATER_THAN_OR_EQUAL_TO)
      │        to   → EffectiveTime <  to        (LESS_THAN)
      └─ searchProvider.createResultStore(request)
      ▼
  <store-specific — see §6>
```

**The `TimeRange` is not a separate channel to the store.** It is flattened into expression terms
by `addTimeRangeExpression` and arrives at the DAO indistinguishable from terms the user wrote in a
`where` clause. That is the whole mechanism behind §7.

Results come back by polling: `QueryModel.poll` re-issues the request until the response says
complete ([QueryModel.java:335](../stroom-core-client/src/main/java/stroom/query/client/presenter/QueryModel.java:335)),
handing each response to the registered `ResultComponent.setData` and then to the error and
searching-state listeners — **in that order**, which §8 depends on.

---

## 3. Query 1 — the Map tab's events read

Draws the animated entity overlay: where everyone was at the selected time.

### Trigger path

```
requestAnimationFrame playback loop            FloorMapTimelinePresenter.java:690
  └─ FloorMapQueryThrottle.shouldQuery(ts)     300 ms, FloorMapTimelinePresenter.java:85,:132
       └─ TimeChangeEvent.fire(timeline, t)
  ... or one of the three discrete jumps, each of which calls fireDiscontinuity() first:
       scrub commit            FloorMapTimelinePresenter.java:176 → :181
       stepBy (buttons + keys) FloorMapTimelinePresenter.java:563 → :565
       stop-at-end             FloorMapTimelinePresenter.java:678 → :679
       ▼
FloorMapMapPresenter TimeChangeEvent handler   FloorMapMapPresenter.java:510
  └─ source guard: only this tab's own timeline (the Editor tab has one too)
       ▼
onTimeChange(t)                                FloorMapMapPresenter.java:821
  ├─ canvas.setCurrentTimeText(...)
  ├─ readFactsHistoryIfDue()   → query 2
  ├─ applyFactSnapshot(t)      → no query; derived from held history
  └─ readEvents(t)             → query 1
       ▼
readEvents(t)                                  FloorMapMapPresenter.java:858
  ├─ query = getEntity().getEventsQuery()      :897
  ├─ blank → return
  └─ if (!eventsQueryHelper.isRunning() || pendingDiscontinuity)
         eventsQueryHelper.run(query, queryParams(), 0L, t)     :864
```

**The in-flight rule.** A routine tick with a read already running is **dropped** — it would ask the
same question about a position at most one tick old, and `startNewSearch` destroys the in-flight
search, so issuing per tick faster than reads complete means none ever completes. A **jump** is the
exception and *does* replace it, because the read in flight is for a position the user has left.

### Request

`FloorMapFullReadQueryHelper.run` → `start` ([FloorMapFullReadQueryHelper.java:284](../stroom-core-client/src/main/java/stroom/floormap/client/presenter/FloorMapFullReadQueryHelper.java:284), [:309](../stroom-core-client/src/main/java/stroom/floormap/client/presenter/FloorMapFullReadQueryHelper.java:309))

| | |
|---|---|
| `componentName` | `"eventsTable"` |
| `queryInfo` | `"Events Query Playback"` |
| `timeRange` | `new TimeRange("CUSTOM", "0", String.valueOf(t + 1))` |
| `params` | `queryParams()` — `FactStore` / `EventStore` |
| `incremental` | `false` |
| `storeHistory` | `false` |
| row cap | `MAX_ROWS` = **20 000** |
| timeout | **30 000 ms** (`QueryModel.setTimeout`) |

**Why the lower bound is `0` and not a window.** Both stores lift the upper bound out as a snapshot
boundary and then discard *every* time term, so a narrower lower bound would be ignored rather than
honoured — `TestTemporalStoreParity` pins that. Zero says what is meant and stays correct if a store
ever stops discarding it. **The `+1` on the upper bound** is because
`addTimeRangeExpression` renders `to` as `LESS_THAN`, so an inclusive `t` needs `t+1`.

### Result path

```
ResultComponent.setData → latestResult                FloorMapFullReadQueryHelper.java:177
addSearchErrorListener  → errored (ERROR and above only)                          :195
addSearchStateListener  → running→idle edge → Outcome{result, failed, to}          :207
       ▼
applyEventsOutcome(outcome)                           FloorMapMapPresenter.java:920
  ├─ failed()    → keep what is drawn, Console.error once per document
  ├─ truncated() → apply anyway, warn once (the cap is now entity count, not history)
  └─ otherwise   → parseEventRows() → publishKnownEntities()      :1016, :977
       ├─ latestPerEntity(...)   belt-and-braces; see below
       ├─ lastRawEventObjects = entities   ← load-bearing, reanchorEventEntities() re-pushes it
       ├─ placeEventEntities() → pushEventEntities()
       ├─ updateAreaMembership()
       └─ FloorMapDataEvent.fire(this, ...)   outbound only; this tab ignores its own
```

`latestPerEntity` reduces to one row per entity. `arrivalOrderTrusted()`
([:1051](../stroom-core-client/src/main/java/stroom/floormap/client/presenter/FloorMapMapPresenter.java:1051))
decides whether that reduction goes by **arrival order** (last row wins) or by comparing the
rendered `Effective Time` column; `FloorMapEventsQueryOrder` answers it, and a `sort` clause or an
entity id not bound to the store `Key` forces the weaker time comparison. Since the read now passes
an upper bound the result should already be one row per key, so this reduction is **insurance**,
kept deliberately.

---

## 4. Query 2 — the Map tab's facts history read

Reads **every version of every fact**, once a minute, and holds them client-side. The floor plan at
any timeline position is then derived from the held history with no query at all — which is what
makes scrub, step and playback zero-query for facts.

### Trigger path

```
factsCadenceTimer, every 10 s while the Map is visible   FloorMapMapPresenter.java:260, :737
  │   (a timer is genuinely needed: TimeChangeEvent only fires while PLAYING, so
  │    on a paused map the cadence would never be asked — see the field javadoc)
  └─ readFactsHistoryIfDue()                             FloorMapMapPresenter.java:1267
onTimeChange(t) also calls it                            FloorMapMapPresenter.java:821
       ▼
readFactsHistoryIfDue()
  ├─ factsHistoryQueryHelper.isRunning() → return
  ├─ factHistory.needsRead(now)          → REFETCH_INTERVAL_MS = 60 s
  ├─ query = FloorMapQueryBuilder.buildFactsQuery(schema, valueFormat)    :805
  ├─ factHistory.markReadIssued(now)
  └─ factsHistoryQueryHelper.runAll(query, queryParams())                 :1280
```

### Request

`FloorMapFullReadQueryHelper.runAll` ([:305](../stroom-core-client/src/main/java/stroom/floormap/client/presenter/FloorMapFullReadQueryHelper.java:305))

| | |
|---|---|
| `componentName` | `"factsHistoryTable"` |
| `queryInfo` | `"Facts History"` |
| `timeRange` | **`null`** — this is what makes it whole-history rather than a snapshot |
| row cap | `FloorMapFactHistory.MAX_ROWS` = **20 000** |
| timeout | 30 000 ms |

The generated query always selects `Key`, `EffectiveTime`, and `toLong(EffectiveTime) as "Effective
Time Ms"`, plus one `jq()`/`xpath()` column per schema mapping. The extra epoch-millis column exists
because the snapshot derivation compares times and `EffectiveTime` arrives as **text**.

### Result path

```
applyFactsHistoryOutcome(outcome)                 FloorMapMapPresenter.java:1292
  ├─ failed() → keep the previous history, Console.error once
  │             (stale beats blank: nothing else draws facts)
  ├─ factHistory.setHistory(columns, rows, truncated)
  ├─ truncated / missing-time-column warnings
  └─ applyFactSnapshot(selectedTime)              FloorMapMapPresenter.java:1343
        └─ unchanged-rows guard: identical snapshot → skip everything downstream
```

---

## 5. Query 3 — the timeline histogram

Draws the density bars under the timeline: when entities were active.

### Trigger path — three sites, all in `FloorMapMapPresenter`

| Site | When |
|---|---|
| `updateTimelineRange()` → [:1588](../stroom-core-client/src/main/java/stroom/floormap/client/presenter/FloorMapMapPresenter.java:1588) | first `onRead` — range defaults to ±24 h around the selected time |
| `setTimeRangeChangeHandler` → [:542](../stroom-core-client/src/main/java/stroom/floormap/client/presenter/FloorMapMapPresenter.java:542) | user changes the visible range in the timeline settings popup |
| `onRead` re-read branch → [:731](../stroom-core-client/src/main/java/stroom/floormap/client/presenter/FloorMapMapPresenter.java:731) | save-triggered re-read, keeping the user's range |

```
runHistogramQuery(start, end)                    FloorMapMapPresenter.java:1626
  ├─ histogramDataModel.setRange(start, end)     client-side filter bounds
  ├─ query = getEventsQueryToUse()               ← the SAME text as query 1
  └─ histogramQueryHelper.run(query, queryParams())
       ▼
HistogramQueryHelper.run                         HistogramQueryHelper.java:182
  └─ queryModel.startNewSearch(..., timeRange = null, false, false, "Histogram Query", null)
       ▼
setData → HistogramDataModel.process(tableResult) → int[] bins
       └─ dataHandler  → floorMapTimelinePresenter.setHistogramData
          dataRangeHandler → floorMapTimelinePresenter.setDataRange   (arms "Show All")
```

| | |
|---|---|
| `timeRange` | **`null`** — deliberately, see §7 |
| row cap | **10 000** ([HistogramQueryHelper.java:81](../stroom-core-client/src/main/java/stroom/widget/histogram/client/HistogramQueryHelper.java:81)) |
| timeout | **default (1 s)** — no `setTimeout` call |
| bins | `HISTOGRAM_BINS` = 100 |

**Events only.** There used to be a facts fallback; it was deleted. The bars answer "when were
entities active", which only the events store knows, so a map with no events query correctly shows
no bars.

**Known defect:** this reads the whole store and truncates silently at 10 000 rows with no
lower bound — written up in `docs/task-histogram-reads-whole-store-and-truncates-silently.md`.
The `start`/`end` passed in bound only the *client-side* bucketing, not the read.

---

## 6. Query 4 — the Events Query tab

The user's own editor and results table. **A separate execution** from queries 1 and 3, which is
what lets the map treat the text differently without changing what the user sees.

```
user presses run
  └─ QueryEditPresenter.run(...)                 QueryEditPresenter.java:411
       ├─ queryModel.reset(NO_LONGER_NEEDED)
       ├─ params built from queryVariables       ← STROOMWORKS-LOCAL, :431
       └─ startNewSearch(null, null, editorText, params,
                         queryToolbarPresenter.getTimeRange(),
                         incremental = TRUE, storeHistory = TRUE, ...)      :438
       ▼
results land in the shared QueryResultTablePresenter
       ▼
FloorMapQueryPresenter
  ├─ tablePresenter.addUpdateHandler → column dropdowns follow every poll   :115
  └─ addSearchStateListener → running→idle edge only → publishMapObjects()  :137, :177
        └─ FloorMapDataEvent.fire(this, docUuid, objects)
              → the Map tab's canvas (NOT its state) draws them
```

**Two things differ from the other three:**

- **`incremental = true`.** The table updates on every poll of a still-filling store, so partial
  result sets are normal here. Publishing each of them to the canvas walked entities across the
  floor for as long as the search ran — hence publishing only on the searching→idle edge.
- **The `TimeRange` is the user's**, from the query toolbar, persisted as
  `FloorMapDoc.getEventsQueryTimeRange()`. So this tab can get either store behaviour depending on
  what the user set.

This tab does **not** follow the timeline, deliberately: it is created lazily when its tab is first
opened, and the Map's overlay must not depend on that.

---

## 7. The one thing to understand: `TimeRange` selects the store's behaviour

Queries 1 and 3 send **identical query text** and get **completely different results**, because of
one argument.

```
Plan B                                     SQL Temporal Store
TemporalStateDb.search           :257      UpdatableTemporalStoreDaoImpl.search   :472
  asAt = PlanBSearchHelper                   queryTime = getQueryTime(criteria)   :580
         .getQueryTime(criteria,             │
                       EffectiveTime) :262   │  EQUALS | LESS_THAN | LESS_THAN_OR_EQUAL_TO
  │                                          │  on the time field → that instant
  ├─ asAt != null → searchAsAt()      :301   ├─ queryTime != null → correlated-subquery
  │    ├─ removeTimeTerms(expr)       :101   │     MAX(EFFECTIVE_TIME) per key <= queryTime
  │    └─ one LMDB forward pass, keeping     │     getFilteredExpression() drops time terms :607
  │       the newest entry per key prefix    │
  └─ asAt == null → PlanBSearchHelper        └─ queryTime == null → plain select,
       .search(): every historical row            every historical row
```

| Query | `TimeRange` sent | Terms `addTimeRangeExpression` adds | `getQueryTime` sees | Result |
|---|---|---|---|---|
| **1 Events** | `[0, t+1)` | `>= 0` **and `< t+1`** | the `LESS_THAN` → snapshot at `t` | **one row per entity** |
| **2 Facts** | none | none | nothing | **every version of every fact** |
| **3 Histogram** | none | none | nothing | **every event, ever** |
| **4 Query tab** | the user's | whatever they set | maybe | either |

Three consequences worth stating plainly:

1. **A lower bound is not honoured on the snapshot path.** Both stores *remove every time term*
   before applying the rest of the expression, so query 1's `>= 0` is discarded. Narrowing it
   changes nothing. This is why event expiry cannot be done by narrowing the read — see
   `docs/floormap-event-expiry-requirements.md`.
2. **One channel carries two intents.** "Filter rows" and "snapshot boundary" are the same wire, and
   which one you get is inferred from the *shape* of the term. `docs/planb-explicit-read-mode-proposal.md`
   is the proposal to separate them.
3. **The histogram's `null` is load-bearing, not an oversight.** With a `TimeRange` it would get one
   row per key and draw one bar per entity instead of an activity profile. Both
   `HistogramQueryHelper.run` and `FloorMapFullReadQueryHelper.runAll` say so in their javadoc.

Neither store narrows its iteration by the time range —
`PlanBSearchHelper.search` has a standing `TODO` at
[PlanBSearchHelper.java:134](../stroom-planb/stroom-planb-impl/src/main/java/stroom/planb/impl/dao/PlanBSearchHelper.java:134)
saying so. **All four queries scan the whole store.** The range bounds rows *extracted, serialised
and transferred*, not rows scanned.

---

## 8. Cross-cutting

### Store references travel as `Param`s, not substituted text

All four pass `FactStore` / `EventStore` params built by
`FloorMapQueryPresenter.buildQueryVariables` ([:612](../stroom-core-client/src/main/java/stroom/floormap/client/presenter/FloorMapQueryPresenter.java:612)).
The query text is sent **exactly as written** — `from param('EventStore')` resolves server-side in
`SearchRequestFactory.resolveDataSourceName`. It used to be blind `String.replace` on the client;
that also broke the editor's error offsets.

Drop the params and the `from` clause will not resolve — which produces zero rows and an error
nothing was listening for, which is why the error listeners in §8 exist.

### Why the result is taken on the searching→idle edge, not from `setData`

Queries 1, 2 and 4 all do this, for the same two reasons:

- `StateSearchProvider` catches a scan failure, calls `addError`, and **then still calls
  `signalComplete()`** ([StateSearchProvider.java:281](../stroom-planb/stroom-planb-impl/src/main/java/stroom/planb/impl/StateSearchProvider.java:281)) —
  so a broken or never-written store is indistinguishable from an empty one by its rows.
- `QueryModel` calls `setData` ([:427](../stroom-core-client/src/main/java/stroom/query/client/presenter/QueryModel.java:427)) **before** `setErrors` ([:439](../stroom-core-client/src/main/java/stroom/query/client/presenter/QueryModel.java:439)), so at `setData` time the error state
  is not yet known. `setSearching(false)` fires after both ([:447](../stroom-core-client/src/main/java/stroom/query/client/presenter/QueryModel.java:447)).

Queries 1 and 2 replace their state wholesale, so applying a partial or failed result would drop
entities or blank the floor plan. Query 4 publishes to the canvas, where partial results walked
entities across the floor.

Query 3 has no state to protect and takes its result straight from `setData`.

### Failure reporting

| Query | On failure | De-duplication |
|---|---|---|
| 1 Events | keep drawn positions, `Console.error` | once per document |
| 2 Facts | keep previous history, `Console.error` | once per document |
| 3 Histogram | no bars, `Console.error` | once per helper, re-armed on `reset()` |
| 4 Query tab | the editor shows markers/indicators | n/a |

**All of it is console-only** — there is no on-screen indication that a Floor Map query failed.
Written up in `docs/task-histogram-failures-are-console-only.md`.

Queries 1, 2 and 3 all gate on `Severity.ERROR` **and above**. A `WARNING` must not be able to
refuse a read, because refusing keeps stale positions and a recurring warning would freeze the map
indefinitely.

### `isRunning()` is the helper's own flag, never `QueryModel.isSearching()`

`QueryModel`'s REST-failure path sets errors and stops polling **without** clearing `searching` —
that flag is cleared only by `stop`, `reset`, a null response and completion. Delegating to it would
let one network blip suppress every future read, silently, until the document was re-read.
([FloorMapFullReadQueryHelper.java:106](../stroom-core-client/src/main/java/stroom/floormap/client/presenter/FloorMapFullReadQueryHelper.java:106))

### Each query needs its own `QueryModel`

`QueryModel` state is single-valued — one `currentSearch`, one `currentQueryKey`, one searching flag
— and `startNewSearch` destroys the previous result store. Three separate models exist on the Map
tab (events, facts, histogram) for exactly this reason; sharing one would have the 300 ms events
tick destroy the facts read every time.

---

## 9. Quick index

| Class | Role |
|---|---|
| [FloorMapMapPresenter](../stroom-core-client/src/main/java/stroom/floormap/client/presenter/FloorMapMapPresenter.java) | owns queries 1, 2 and 3 |
| [FloorMapFullReadQueryHelper](../stroom-core-client/src/main/java/stroom/floormap/client/presenter/FloorMapFullReadQueryHelper.java) | queries 1 and 2 — wholesale-replacement reads |
| [HistogramQueryHelper](../stroom-core-client/src/main/java/stroom/widget/histogram/client/HistogramQueryHelper.java) | query 3 |
| [FloorMapQueryPresenter](../stroom-core-client/src/main/java/stroom/floormap/client/presenter/FloorMapQueryPresenter.java) | query 4 + `buildQueryVariables` + `parseRows`/`latestPerEntity` |
| [QueryEditPresenter](../stroom-core-client/src/main/java/stroom/query/client/presenter/QueryEditPresenter.java) | query 4's execution |
| [FloorMapQueryBuilder](../stroom-core-client/src/main/java/stroom/floormap/client/presenter/FloorMapQueryBuilder.java) | generates query 2's text |
| [FloorMapTimelinePresenter](../stroom-core-client/src/main/java/stroom/floormap/client/presenter/FloorMapTimelinePresenter.java) | fires `TimeChangeEvent` and the discontinuity callback |
| [QueryModel](../stroom-core-client/src/main/java/stroom/query/client/presenter/QueryModel.java) | the shared client search model |
| [ResultStoreManager](../stroom-query/stroom-query-common/src/main/java/stroom/query/common/v2/ResultStoreManager.java) | flattens `TimeRange` into expression terms |
| [PlanBSearchHelper](../stroom-planb/stroom-planb-impl/src/main/java/stroom/planb/impl/dao/PlanBSearchHelper.java) | `getQueryTime` / `removeTimeTerms` |
| [TemporalStateDb](../stroom-planb/stroom-planb-impl/src/main/java/stroom/planb/impl/dao/temporalstate/TemporalStateDb.java) | Plan B snapshot vs all-history |
| [UpdatableTemporalStoreDaoImpl](../stroom-sqlstore/stroom-sqlstore-impl-db/src/main/java/stroom/sqlstore/impl/db/UpdatableTemporalStoreDaoImpl.java) | the SQL store's equivalent |

### Related documents

- `docs/temporal-store-parity-report.md` — where the two stores differ, with a live head-to-head test
- `docs/planb-explicit-read-mode-proposal.md` — the proposal to stop inferring read mode from term shape
- `docs/floormap-event-expiry-requirements.md` — why a lower bound cannot expire events
- `docs/task-histogram-reads-whole-store-and-truncates-silently.md`
- `docs/task-histogram-failures-are-console-only.md`
