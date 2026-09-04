# Creating a Plan B events store for a Floor Map

A Floor Map reads its events from a **Plan B** document and its facts from a **SQL Temporal
Store**. This covers the events half: what the Floor Map requires of the store, which settings
matter, and how to get data in.

The asymmetry is deliberate. The Editor tab writes spatial data back to the facts store, so that
one has to be a SQL Temporal Store. The events store is only ever read.

---

## 1. Create the document

**Explorer → New → Plan B**

### The name has a hard constraint

Plan B names must match `^[a-z_0-9]+$` — lowercase letters, digits and underscores only. No
spaces, no capitals, no hyphens. `floor_map_events` is fine; `Floor Map Events` is rejected.

This is enforced twice, at creation (`PlanBDocStoreImpl`) and again at ingest
(`ShardWriters`), so a bad name fails immediately rather than silently.

It matters beyond validation: the Floor Map substitutes the store's **name** into the
`param('EventStore')` placeholder of its events query, so the name ends up in a `from` clause.

### State type must be Temporal State

Set **State Type** to `Temporal State`. Not negotiable, and the reason is worth understanding.

Of the eight Plan B state types, only `TEMPORAL_STATE` records an effective time per entry and
exposes the three fields the Floor Map's query selects:

| Field | Used for |
|---|---|
| `Key` | the entity identity — who or what moved |
| `EffectiveTime` | when — drives the timeline and playback |
| `Value` | the event payload as JSON — where, what type, status, message |

Pick anything else and the events query fails with an unknown-field error. The Floor Map's
initialisation dialog now checks this when you create the document and refuses to save a mismatch,
but the **Settings tab does not yet check**, so a store swapped there can still be wrong.

---

## 2. Settings

Every default is safe for a Floor Map except where noted. The two that will actually break your
map are **Condense** and **Retention**.

| Setting | Default | Use | Why |
|---|---|---|---|
| **Condense** | *off* | **safe to enable, but it will not reduce what the map reads** | It was unsafe before — see below. It is now harmless, and also close to irrelevant to the Floor Map: its shortest available threshold is longer than the horizon. |
| **Retention** | *off* (1 year if enabled) | off, or longer than you need to scrub back | Retention deletes old entries. The timeline can only scrub back as far as the data still exists. |
| **Temporal precision** | `Millisecond` | `Millisecond`, or `Second` | Part of the key. Coarser than your event rate merges distinct events into one key. Only coarsen if events are genuinely no denser than that. |
| **Overwrite** | `true` | `true` | Two events for the same entity at the same instant: the later write wins. With `false` the first is kept. Either is defensible; `true` matches re-ingesting corrected data. |
| **Value type** | `Variable` | `Variable` | The payload is a JSON string of unbounded length. Fixed numeric types cannot hold it. |
| **Key type** | *(schema default)* | leave alone unless keys exceed 511 bytes | `String` caps at 511 bytes; `Hash lookup table` is unbounded and deduplicated. Entity ids are normally short. |
| **Max store size** | 10 GiB | raise if you expect more | Per store. |
| **Snapshot settings** | all off | leave off | With `useSnapshotsForQuery` on, queries read a snapshot that may lag behind ingest, so the map shows stale positions. |
| **Synchronise merge** | *(unset)* | leave alone | Ingest-side concern, unrelated to the Floor Map. |

### Condense is now safe — it was not before

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

**But it will not make the periodic re-read cheaper**, and an earlier version of this guide
wrongly said it would. Condense only collapses runs **older than its threshold**
(`TemporalStateDb.condense` skips anything at or after it), and the shortest threshold the Plan B
settings offer is **1 day** — the unit dropdown starts at days. The horizon is six hours. So
everything the map reads at a live timeline position is newer than any threshold you can set, and
none of it is ever condensed.

Where condense does apply is playback further back than its threshold. That is also the only place
it can still hurt: a stationary entity's run is collapsed to its earliest entry, and if that entry
falls outside the six hours before the scrubbed-to position, the entity is not drawn there. So
condense trades a storage saving for a gap in deep historical playback, and buys the Floor Map
nothing at the live end.

The read is capped at 20 000 rows, which over six hours is 0.93 events/second sustained — a hundred
entities emitting once a minute already exceeds it, and the map warns when it does. The remedies
that actually apply are a **shorter horizon**, a **higher row cap**, or the upstream latest-per-key
read; not condense.

### How far back the map can see

The re-read reaches **six hours** back from the selected time (`FloorMapEventState.HORIZON_MS`).
So the failure mode, stated plainly: **an entity with no events in the last six hours is not
shown.**

That bound is deliberate rather than incidental. Without it, Plan B returns every event of the
alphabetically-first entities and truncates in key order, because it scans the whole store applying
a row predicate — so "some entities, chosen by key order" would be the alternative, which is not
something an operator can act on. It also keeps an invariant the code documents: the positioned
count is not a head-count of who is on site, and state that never shrank would quietly turn it into
one.

**What this means for your data:** nothing, for anything emitting at all regularly. There is no
longer a 20-second obligation. Emit at whatever rate suits the source.

The horizon disappears when Plan B gains a server-side latest-per-key read — see
`planb-snapshot-read-proposal.md`. Until then it is the cost of Plan B applying a query's time
range literally where the SQL Temporal Store reinterprets it as a snapshot.

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
        <value>{"location":"B-GND, 120.5, 340","type":"person","status":"ok","message":"badge in"}</value>
    </temporal-state>
</referenceData>
```

- **`<map>`** is the Plan B document's **name**, resolved at ingest.
- **`<key>`** becomes the `Key` column — the entity identity the Floor Map groups by.
- **`<time>`** becomes `EffectiveTime`. If omitted, the **stream's effective time** is used
  instead; if there is neither, ingest errors with `Temporal state 'time' is null`. For movement
  data you almost always want an explicit per-event `<time>`, or every event in a stream lands at
  the same instant.
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
  jq(Value, '.location') as "Location ID",
  jq(Value, '.type') as "Event Type",
  jq(Value, '.status') as "Status",
  jq(Value, '.message') as "Message"
```

So `Value` should be a JSON object with `location`, `type`, `status` and `message`. Only
`location` is load-bearing — the others are display columns. Edit the query on the Events Query
tab if your payload differs.

### `location` takes two forms

| Form | Example | Behaviour |
|---|---|---|
| Coordinates | `"B-GND, 120.5, 340"` | Drawn exactly there. |
| A fact key | `"desk-114"` | Resolved against the facts store at the current time. |

The second form is the more useful one: the entity is placed wherever that fact currently is, so
**moving a desk in the Editor moves everyone recorded as being at it**. Baked coordinates cannot
do that.

A `location` naming a fact key that does not exist at the selected time is silently dropped —
there is nowhere to draw it.

---

## 4. Verifying it works

In order, because each step depends on the one before:

1. **Plan B document → Data tab.** Rows present? If not, the problem is ingest, not the Floor Map.
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
| `the events baseline query failed` | The store is unreachable or has never been written to. Reported once per document, not once a minute. |
| `the events baseline hit its 20000-row limit` | The store produces more events than one baseline can carry. A shorter horizon or a higher cap; **not** Condense, which cannot reach this data. Reported once per document. |
| *nothing at all* | No rows at all. Check ingest and the timeline position. |

Note the last row: an empty result and empty facts both produce **silence** rather than a message.
That is a known reporting gap, not a sign that everything is fine.

---

## Quick checklist

- [ ] Name matches `^[a-z_0-9]+$`
- [ ] State Type is **Temporal State**
- [ ] Condense — **off or on, both fine**; it makes no difference to what the map reads
- [ ] Retention off, or longer than your timeline needs
- [ ] Snapshot settings off
- [ ] Value type `Variable`
- [ ] Ingest uses `<temporal-state>` with an explicit `<time>`
- [ ] `Value` is JSON carrying at least `location`
- [ ] Entities that should stay visible emit at least once every six hours
