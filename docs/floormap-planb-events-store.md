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
| **Condense** | *off* | **leave off** | See the warning below. Turning this on makes stationary entities vanish. |
| **Retention** | *off* (1 year if enabled) | off, or longer than you need to scrub back | Retention deletes old entries. The timeline can only scrub back as far as the data still exists. |
| **Temporal precision** | `Millisecond` | `Millisecond`, or `Second` | Part of the key. Coarser than your event rate merges distinct events into one key. Only coarsen if events are genuinely no denser than that. |
| **Overwrite** | `true` | `true` | Two events for the same entity at the same instant: the later write wins. With `false` the first is kept. Either is defensible; `true` matches re-ingesting corrected data. |
| **Value type** | `Variable` | `Variable` | The payload is a JSON string of unbounded length. Fixed numeric types cannot hold it. |
| **Key type** | *(schema default)* | leave alone unless keys exceed 511 bytes | `String` caps at 511 bytes; `Hash lookup table` is unbounded and deduplicated. Entity ids are normally short. |
| **Max store size** | 10 GiB | raise if you expect more | Per store. |
| **Snapshot settings** | all off | leave off | With `useSnapshotsForQuery` on, queries read a snapshot that may lag behind ingest, so the map shows stale positions. |
| **Synchronise merge** | *(unset)* | leave alone | Ingest-side concern, unrelated to the Floor Map. |

### Do not enable Condense

Condense removes **consecutive entries with identical values** for the same key, older than its
threshold, keeping the earliest of each run.

For state lookups that is lossless — the value at any time `T` is still correct, because you read
the latest entry at or before `T`. **The Floor Map does not read it that way.** The Map tab
queries a trailing 20-second window and takes the latest entry per entity within it. So:

- an entity parked in one place, re-emitting the same location every few seconds, has all those
  repeats condensed away;
- the single surviving entry is older than the window;
- the entity **disappears from the map**, even though the store still says exactly where it is.

Condense is off by default. Keep it that way for a store a Floor Map reads.

### The 20-second window, and what it means for your data

The Map tab does not ask "where is everything now" — it asks "what happened in the last 20
seconds". An entity with no events in that window **is not drawn**.

This is a real behavioural difference from the SQL Temporal Store the events store used to be,
where a snapshot query returned an entity's last known position however old. Plan B applies a
query's time range literally, so the window is the window.

Practically: **emit an event per entity at least every 20 seconds** for anything that should stay
visible while stationary, and do not condense those repeats away. If your data cannot do that,
the window is a constant (`FloorMapMapPresenter.EVENTS_WINDOW_MS`) and matches the trail fade
duration on purpose.

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
| *nothing at all* | No rows in the window. Check ingest, the timeline position, and Condense. |

Note the last row: an empty result and empty facts both produce **silence** rather than a message.
That is a known reporting gap, not a sign that everything is fine.

---

## Quick checklist

- [ ] Name matches `^[a-z_0-9]+$`
- [ ] State Type is **Temporal State**
- [ ] Condense **off**
- [ ] Retention off, or longer than your timeline needs
- [ ] Snapshot settings off
- [ ] Value type `Variable`
- [ ] Ingest uses `<temporal-state>` with an explicit `<time>`
- [ ] `Value` is JSON carrying at least `location`
- [ ] Entities that should stay visible while stationary emit at least every 20s
