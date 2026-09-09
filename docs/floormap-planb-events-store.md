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
| **Condense** | *off* | **safe to enable; it makes no difference to what the map reads** | It was unsafe before — see below. The map now reads one row per entity regardless of how many versions the store holds, so condensing changes storage only. |
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

**But it does not make the map's read cheaper either.** Condense collapses runs **older than its
threshold** (`TemporalStateDb.condense` skips anything at or after it), and the shortest threshold
the Plan B settings offer is **1 day** — the unit dropdown starts at days. So nothing the map reads
at a live timeline position is ever condensed. It changes what the store costs to keep, not what a
query returns.

Where condense can still hurt is playback further back than its threshold: a stationary entity's
run is collapsed to its earliest entry, so its reported position is that entry's rather than the
run's. The entity is still drawn, because the read takes its latest row at or before the selected
time whatever that row happens to be.

### How far back the map can see

**All the way.** The read asks for every entity's latest row at or before the selected time, with no
lower bound, and Plan B answers that in one pass — so an entity that last emitted a year ago is
still drawn, at the position it last reported.

That is a change. Until 2026-09-09 Plan B had no latest-per-key read, so the map held positions as
client-side state and corrected them with a bounded re-read reaching six hours back; the failure
mode then was *"an entity with no events in the last six hours is not shown."* Plan B gained the
read — see `planb-snapshot-read-proposal.md`, which proposed it — and the horizon, the periodic
re-baseline and the per-tick delta went with it.

One consequence is worth stating, because it is the counterpart of that bound disappearing: the
positioned count is **not** a head-count of who is on site, and is less so now than before. An
entity that stopped emitting a year ago still counts. Nothing prunes on absence, because absence is
no longer distinguishable from stillness.

**What this means for your data:** nothing. Emit at whatever rate suits the source. There is no
20-second obligation, and no horizon to stay inside.

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
| `the events query failed` | The store is unreachable or has never been written to. Positions from before the failure stay on screen. Reported once per document. |
| `the events query hit its 20000-row limit` | The store holds more distinct **entities** than the cap allows — the read is one row per entity, so this is not about history depth. A higher cap is the only fix; **not** Condense. Reported once per document. |
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
