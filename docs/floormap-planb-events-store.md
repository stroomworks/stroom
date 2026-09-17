# Creating an events store for a Floor Map

> **Revised 2026-09-17.** The events store is now its own document type — **FloorMap Event
> Store** — rather than a general-purpose Plan B document. It is still a Plan B store underneath,
> and everything below about names, ingest and retention still applies. What has changed is that the
> settings which must never be wrong are no longer settings: the **key type**, **temporal
> precision** and **value type** are fixed by the type, so §2's table no longer lists them. The
> reason is in `docs/floormap-events-backend-design.md` §13 — the map's read seeks to each entity's
> answer, which is only correct over one particular key encoding.

A Floor Map reads its events from a **FloorMap Event Store** and its facts from a **SQL Temporal
Store**. This covers the events half: what the Floor Map requires of the store, which settings
matter, and how to get data in.

The asymmetry is deliberate. The Editor tab writes spatial data back to the facts store, so that
one has to be a SQL Temporal Store. The events store is only ever read.

---

> **Upgrading an existing floor map.** A map created before this document type existed points at a
> plain Plan B store. Entities will not be read: the Map tab refuses, and says so on the canvas and
> in the console, rather than quietly returning every row in the store. The floor plan and the
> density bars still draw, so the symptom is a map with bars and nothing on it. Create a FloorMap Event Store, point the ingest pipeline
> at its name, and select it on the map's Settings tab. There is no automatic migration, and the old
> store's data does not move.

## 1. Create the document

**Explorer → New → FloorMap Event Store**

### The name has a hard constraint

Plan B names must match `^[a-z_0-9]+$` — lowercase letters, digits and underscores only. No
spaces, no capitals, no hyphens. `floor_map_events` is fine; `Floor Map Events` is rejected.

This is enforced at creation and rename (`FloorMapEventStoreStoreImpl`, using Plan B's own
`PlanBNameValidator`) and again at ingest (`ShardWriters`), so a bad name fails immediately rather
than silently. Note that **import does not go through that check** — it writes the document
directly, exactly as Plan B's own store does — so an imported store can carry a name ingest will
never resolve.

It matters beyond validation: the Floor Map substitutes the store's **name** into the
`param('EventStore')` placeholder of its events query, so the name ends up in a `from` clause.

> **Do not give it the same name as a Plan B store.** Names are shared across every Plan B document
> type, and nothing prevents a clash — the check each store makes only looks at its own type. If two
> documents of different types share a name, `PlanBDocCache` refuses to resolve either of them, and
> **both** stores stop working for ingest *and* for queries, with "Unexpectedly found more than one
> state doc with key: &lt;name&gt;" as the only clue. This is a known Stroom limitation rather than
> something this store introduces, but it is worth a moment's care when naming.

### The state type is not a choice

The document fixes it. A FloorMap Event Store is always a temporal state store, because that is the
only kind that records an effective time per entry — which is what the timeline reads. There is no
dropdown to get wrong and nothing for the Settings tab to check, which is the point of it being a
document type of its own rather than a general-purpose Plan B store.

| Field | Used for |
|---|---|
| `Key` | the entity identity — who or what moved |
| `EffectiveTime` | when — drives the timeline and playback |
| `Value` | the event payload as JSON — where, what type, status, message |

Pick anything else and the events query fails with an unknown-field error. Both places that can
select a store offer only FloorMap Event Stores, so the fields are always these three.

---

## 2. Settings

Every default is safe for a Floor Map except where noted. The two that will actually break your
map are **Condense** and **Retention**.

| Setting | Default | Use | Why |
|---|---|---|---|
| **Event expiry** | 24 hours | as long as an entity may go quiet and still count as present | How long an entity stays on the map after its last event, measured from the scrubber rather than from now. Cannot be turned off. Must not exceed retention, which is checked on save. |
| **Condense** | *off* | enable only if you accept the warning | **It makes repeating events disappear from the map** — see below. It is offered because collapsing repeats is how a store of stationary entities stays small, but the cost is real and the editor says so. |
| **Retention** | *off* (1 year if enabled) | off, or longer than you need to scrub back | Retention deletes old entries. The timeline can only scrub back as far as the data still exists. Must not be shorter than the event expiry, which is checked on save. |
| **Overwrite** | `true` | `true` | Two events for the same entity at the same instant: the later write wins. With `false` the first is kept. Either is defensible; `true` matches re-ingesting corrected data. |
| ~~Key type, temporal precision, value type~~ | — | **not shown** | Fixed by the document type. The key encoding is what makes the map's per-key seek valid, and a Plan B schema is immutable once data is written, so it is not a choice to get wrong. |
| **Max store size** | 10 GiB | raise if you expect more | Per store. |
| **Snapshot settings** | all off | leave off | With `useSnapshotsForQuery` on, queries read a snapshot that may lag behind ingest, so the map shows stale positions. Not shown on the event store's Settings tab, but preserved across a save and honoured by the query router. |
| **Synchronise merge** | *(unset)* | leave alone | Ingest-side concern, unrelated to the Floor Map. |

### Condense: what it costs, and why it is still offered

Condense removes **consecutive entries with identical values** for the same key, older than its
threshold, keeping the earliest of each run.

For state lookups that is lossless: the value at any time `T` is still correct, because you read the
latest entry at or before `T`. **The Map tab used not to read it that way.** It queried a trailing
20-second window and took the latest entry per entity within it, so an entity parked in one place
and re-emitting the same location had all those repeats condensed away, the survivor fell outside
the window, and the entity **disappeared from the map** while the store still said exactly where it
was.

**That window is gone.** The Map tab now keeps each entity's last known position client-side: every
playback tick reads only what changed since the last one and updates what it holds, and a periodic
re-read of the last six hours corrects it. An entity that stops emitting keeps its position instead
of vanishing, so condensing its repeats away costs nothing.

**And it now costs something the map can see.** Condense collapses a run of identical values to the
run's **earliest** entry, so a stationary entity's "last seen" time stops advancing while it is still
emitting. Under event expiry that entity ages out and disappears from the map. This is why the store's
settings tab warns *"This will cause repeating events to disappear from the map"* next to the setting.

Condense only touches runs **older than its threshold** (`TemporalStateDb.condense` skips anything at
or after it) and the shortest threshold the settings offer is **1 day**, so a live timeline position
is unaffected. The damage is to playback further back than the threshold — which, with expiry
measured from the scrubber, is exactly where it shows.

### How far back the map can see

**As far as the data goes, and no further back than the expiry.** The read asks for every entity's
latest row at or before the selected time, then drops any entity whose latest row is older than the
store's **event expiry**. Retention bounds how far back data exists at all. Between those two the
read has no other horizon: Plan B answers it in one pass, whatever the depth of history.

Two consequences follow, and they pull in opposite directions.

**An entity that has stopped emitting disappears**, once its last event falls outside the expiry
measured from the scrubber. That is the point of expiry — before it existed, an entity that last
reported a year ago was still drawn at the position it last gave. So the positioned count is much
closer to a head-count of who is present than it used to be, though it is still a count of who has
reported recently rather than of who is there.

**So an entity must keep emitting to stay on the map.** Emit at least once per expiry period. This
is a real obligation on the source, and it is the one the expiry setting exists to let you tune: set
it longer than the longest quiet period you expect from a source you still consider present.

> **Historical note.** Until 2026-08-27 Plan B had no latest-per-key read, so the map held positions
> as client-side state and corrected them with a bounded re-read reaching six hours back. That
> machinery, and its own six-hour obligation, is gone. Expiry replaces it with a rule you set rather
> than one the implementation imposed.

---

## 3. Getting data in

Add a **Plan B Filter** to the pipeline that processes your event feed. It takes
`reference-data:2` XML:

```xml
<referenceData xmlns="reference-data:2">
    <temporal-state>
        <map>floor_map_events</map>
        <key>joe.blogs@example.org</key>
        <time>2026-09-01T10:00:05.000Z</time>
        <value>{"locationRef":"desk-114","type":"person","status":"ok","message":"badge in"}</value>
    </temporal-state>
</referenceData>
```

- **`<map>`** is the Plan B document's **name**, resolved at ingest.
- **`<key>`** becomes the `Key` column — the entity identity the Floor Map groups by.
- **`<time>`** becomes `EffectiveTime`, and **must be ISO 8601** — `PlanBFilter` parses it with
  `DateUtil.parseNormalDateTimeStringToInstant`, which rejects epoch millis however plausible they
  look. `2026-09-01T10:00:05.000Z` is fine; `1788500000001` fails with `Unable to parse string
  "…" as datetime`, once per entry. (`SqlStoreFilter` behaves identically, so the constraint is the
  same on both stores.) If omitted, the **stream's effective time** is used instead; if there is
  neither, ingest errors with `Temporal state 'time' is null`. For movement data you almost always
  want an explicit per-event `<time>`, or every event in a stream lands at the same instant.
- **`<value>`** becomes `Value`.

**Prefer `<temporal-state>` over the generic `<reference>` element.** Both reach the same code,
but `<temporal-state>` asserts the store's state type and reports
`Unexpected Plan B store type for Temporal State: …` if it is wrong. `<reference>` dispatches on
whatever the store happens to be, so a misconfigured store fails later and less clearly.

### What `Value` has to contain

The default events query the Floor Map writes reads the payload with `jq`:

```
from param('EventStore')
select EffectiveTime as "Effective Time",
  Key as "Entity ID",
  jq(Value, '.location') as "Location",
  jq(Value, '.locationRef') as "Location Ref",
  jq(Value, '.type') as "Type",
  jq(Value, '.status') as "Status",
  jq(Value, '.message') as "Message"
```

So `Value` should be a JSON object with `locationRef` (or `location`), `type`, `status` and
`message`. `Status` and `Message` are selected but not read by anything — they are there for you to
use in the results table. Edit the query on the Events Query tab if your payload differs.

**If you rename an alias, change the mapping to match.** The Events Query tab has one dropdown per
meaning — Entity ID, Location, Location Ref, Type — and each must name a column the query selects.
The defaults above and the default mapping are generated from the same constants, so a document
created by the init dialog already agrees; a hand-edited query does not until you say so.

### Location is two separate fields

Set **exactly one** per event.

| Field | Example | Behaviour |
|---|---|---|
| `locationRef` | `"desk-114"` | Resolved against the facts store at the current time. |
| `location` | `"120.5, 340"` | Drawn exactly there, whatever the facts say. |

`locationRef` is the one you usually want: the entity is placed wherever that fact currently is, so
**moving a desk in the Editor moves everyone recorded as being at it**, retroactively. Baked
coordinates cannot do that — they are frozen at ingest, so an entity keeps visiting a place nothing
occupies any more.

**Both set is contradictory data**, since only one can be true. `location` wins, because it needs no
lookup, and the map reports the clash once in the console. Fix the data, or unmap one of the two
roles on the Events Query tab.

A `location` naming a fact key that does not exist at the selected time is silently dropped —
there is nowhere to draw it.

> **Format change, 2026-09-07 — this breaks existing data.** A location used to be **one** field
> whose two readings were told apart by shape: two comma-separated numbers meant a position,
> anything else meant a fact key. Coordinates additionally carried a leading map or building token
> (`"B-GND, 120.5, 340"`) that no line of code ever read.
>
> Sniffing the shape had real costs. A fact key that happened to look like two numbers, or to
> contain a comma, could not be expressed at all. The part-count rule had to be documented, learned
> and preserved. And a malformed coordinate silently became a reference to a fact that did not
> exist, so the map reported a missing desk rather than a bad number.
>
> **Re-ingest events as `locationRef` (or `location` for real coordinates), with the leading token
> dropped.** A map whose events still use the old single field will draw no entities and say so in
> the console. Nothing migrates automatically.
>
> If your data used that token to distinguish floors, note it never had any effect: one floor map is
> one coordinate space, and every zone was already drawn together.

---

## 4. Verifying it works

In order, because each step depends on the one before:

1. **Is anything in the store?** A FloorMap Event Store has no Data tab — that belongs to the
   general-purpose Plan B document. Query it instead: open the Floor Map's Events Query tab, or run
   `from <store name> select Key, EffectiveTime, Value` in a new Query. No rows means the problem is
   ingest, not the Floor Map. (The store's shards are also absent from the Plan B shard-info screen,
   which only lists `PlanBDoc` stores.)
   Check the pipeline's processing errors for `Temporal state 'time' is null` or an unexpected
   store type.
2. **Floor Map → Events Query tab → run.** Rows, and are `Entity ID` and `Location ID` populated
   in the dropdowns? A new Floor Map sets both automatically; an older one may need them picked
   once and saved.
3. **Floor Map → Map tab.** Entities drawn and animating over the timeline.
4. **Browser console (F12)** if not. Three messages discriminate:

| Console says | Meaning |
|---|---|
| `returned N rows but no entities` | The entity/location column names do not match the query's columns. |
| `none of the N event entities could be placed … facts query returned keys like 'X'` | `location` values name fact keys that do not exist. |
| `the events query failed` | The store is unreachable, or its shard directory has gone missing — a deleted directory or an unmounted volume. **Not** simply an empty store: a Plan B document that has never been written to still gets an empty shard, so it reads as no rows rather than as a failure. Positions from before the failure stay on screen. Reported once per document. |
| `the events query hit its 20000-row limit` | The store holds more distinct **entities** than the cap allows — the read is one row per entity, so this is not about history depth. A higher cap is the only fix; **not** Condense. Reported once per document. |
| *nothing at all* | No rows at all. Check ingest and the timeline position. |

Note the last row: an empty result and empty facts both produce **silence** rather than a message.
That is a known reporting gap, not a sign that everything is fine.

---

## Quick checklist

- [ ] Name matches `^[a-z_0-9]+$`
- [ ] Event expiry is long enough that a quiet entity still counts as present
- [ ] Retention is **not shorter than the expiry**, and is longer than your timeline needs
- [ ] Condense off — or on, having read what it costs above
- [ ] Snapshot settings off
- [ ] Ingest uses `<temporal-state>` with an explicit `<time>`
- [ ] `Value` is JSON carrying at least `location`
- [ ] Entities that should stay visible emit at least once per expiry period — this is a real obligation on the source

State type, key type, temporal precision and value type are no longer on this list: the document type
fixes all four.
