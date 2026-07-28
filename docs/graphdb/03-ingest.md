# Loading data: the `graph-mutation:1` format and Graph Filter

**Status:** Evaluation / proof of concept — not production ready. See the
[production-readiness blockers](README.md#production-readiness--known-blockers).
**Audience:** pipeline and translation authors.
**Scope:** the only way data enters a graph. Canonical for the `graph-mutation:1` vocabulary, the Graph
Filter's configuration and error behaviour, and data-modelling guidance.
**Companion documents:** [02-architecture.md](02-architecture.md) (why versions work this way),
[04-event-logging-xslt.md](04-event-logging-xslt.md) (a worked translation),
[10-limits.md](10-limits.md) (size ceilings).

*Facts verified on 2026-07-28 against branch `sw-query-optimiser`.*

---

Data enters a graph in exactly one way: as **`graph-mutation:1` XML**, passed through a pipeline containing
a **Graph Filter** element. There is no bulk loader, no API write endpoint, and no way to write from the
query language.

Your translation's job is therefore to turn whatever your source data looks like into this vocabulary.

## The format at a glance

```xml
<graph xmlns="graph-mutation:1" version="1.0">
  <node id="p-jack" validFrom="2020-01-01T00:00:00.000Z">
    <label>Person</label>
    <property name="name">Jack</property>
    <property name="surname">Powell</property>
  </node>
  <node id="c1" validFrom="2020-01-01T00:00:00.000Z">
    <label>Crime</label>
    <property name="type">Drugs</property>
  </node>
  <edge type="PARTY_TO" validFrom="2020-01-01T00:00:00.000Z">
    <src>p-jack</src>
    <dst>c1</dst>
  </edge>
</graph>
```

Four record types may appear inside `<graph>`, in **any order and any number**:

| Element | Effect |
|---|---|
| `<node>` | Write a node version — a complete labels-and-properties snapshot effective from `validFrom` |
| `<node-delete>` | Write a tombstone — the node is absent from `validFrom` onward |
| `<edge>` | Write an edge version |
| `<edge-delete>` | Write an edge tombstone |

## Element reference

### `<graph>`

The document element. Namespace **`graph-mutation:1`**.

| Attribute | Required | Notes |
|---|---|---|
| `version` | Yes | Must be `1.0` — the only value the schema permits |

### `<node>`

| Attribute | Required | Notes |
|---|---|---|
| `id` | Yes | The node's external identifier. Non-empty. This is the identity: two `<node>` elements with the same `id` are two versions of one node |
| `validFrom` | Yes | See [Timestamps](#timestamps) |

| Child | Cardinality | Notes |
|---|---|---|
| `<label>` | 0..n | One label name per element. Maximum 255 per node version |
| `<property name="…">` | 0..n | Value is the element's text content |

**A node version replaces, it does not merge.** Whatever labels and properties you emit are the complete
state of that node from `validFrom` onward. Omitting a property that a previous version had removes it as
of this version — it is not carried forward.

### `<node-delete>`

| Attribute | Required |
|---|---|
| `id` | Yes |
| `validFrom` | Yes |

No children. Writes a tombstone. The node remains visible to queries asking about earlier instants — see
[02-architecture.md](02-architecture.md).

### `<edge>`

| Attribute | Required | Notes |
|---|---|---|
| `type` | Yes | The relationship type, e.g. `KNOWS`. Non-empty. Exactly one per edge |
| `validFrom` | Yes | |

| Child | Cardinality | Notes |
|---|---|---|
| `<src>` | Exactly 1 | The source node's `id` |
| `<dst>` | Exactly 1 | The destination node's `id` |
| `<property name="…">` | 0..n | |

An edge's identity is the triple (`src`, `type`, `dst`). Emitting the same triple again writes a new
version of that edge, not a second parallel edge. If you need to record repeated occurrences of the same
relationship separately, model the occurrence as a node — see [Data modelling](#data-modelling).

Edges are written to both the out-edge and in-edge stores, so traversal is equally cheap in either
direction.

### `<edge-delete>`

| Attribute | Required |
|---|---|
| `type` | Yes |
| `validFrom` | Yes |

Requires `<src>` and `<dst>` children. Writes an edge tombstone.

### `<property>`

| Attribute | Required |
|---|---|
| `name` | Yes, non-empty |

The value is the element's text content, and is **always a string** — see below.

## Timestamps

`validFrom` is required on every record and is **never defaulted** — not from event time, not from receipt
time, not from the stream. If you do not supply it, the record is rejected.

The format is strict:

```
yyyy-MM-ddThh:mm:ss.sssZ
```

Exactly three fractional-second digits, and a literal `Z`. `2020-01-01T00:00:00Z` is **not** valid —
it lacks the milliseconds. Neither is an offset like `+01:00`; convert to UTC.

This is the single most common cause of silently missing data, because a malformed timestamp fails that
record and only that record, and the stream continues. Normalising timestamps deserves its own named
template in your XSLT.

> The value you choose determines where the data lands in history, and therefore what every `AS OF` query
> returns. Use the time the fact became true in the real world, not the time you happened to process it.

## Property values are strings

In this version every property value is stored as a string. There are no numeric, boolean or date
property types.

This has a consequence that will bite you if unprepared: **comparison and ordering are lexical**. `"10"`
sorts before `"9"`. `"2020-1-5"` sorts after `"2020-11-05"`.

Encode at ingest so that lexical order is the order you want:

| Kind of value | Emit as | Why |
|---|---|---|
| Timestamps and dates | ISO-8601, zero-padded, UTC — `2026-07-28T14:03:00.000Z` | Lexical order equals chronological order |
| Integers you will sort or range-filter | Zero-padded to a fixed width — `000042` | Lexical order equals numeric order |
| Integers you will only compare for equality or convert | Plain — `42` | `toInteger(n.count)` works at query time |
| Booleans | `true` / `false` | `toBoolean()` recognises these |

At query time you can convert with `toInteger()`, `toFloat()`, `toBoolean()` and parse dates with
`stroom.parseDate()` — see [07-functions.md](07-functions.md). Conversion happens per row
during evaluation, so it does not help the anchor lookup; if you need to filter on a value efficiently,
store it in the form you will filter on.

## Configuring the Graph Filter

The **Graph Filter** is a pipeline element in the *Filter* category. It consumes `graph-mutation:1` XML and
writes into a graph. A minimal pipeline is:

```
Source → XML Parser → Graph Filter
```

In practice your source data is not already in this vocabulary, so the real shape is:

```
Source → <parser for your format> → XSLT Filter → Graph Filter
```

The Graph Filter is a terminal element — it writes into the graph store rather than producing output for a
writer, so **there is no `Events` output stream**. A successful run produces no output stream at all, which
is worth knowing before you go looking for one.

### The `graphDb` property

One property, `graphDb`, a document reference to the target `GraphDb`.

> **The graph is resolved by *name*, not by UUID.** Two graphs sharing a name make ingest fail outright,
> and renaming a graph silently breaks every pipeline pointing at it. Give graphs distinctive names and
> treat them as part of your pipeline contract.

One filter writes to exactly one graph; there is no per-record routing to different graphs.

## What happens when something is wrong

The error behaviour has two tiers, and the difference matters a great deal.

### Fatal — the whole stream fails

Only configuration problems, both raised before any record is processed:

| Message | Cause |
|---|---|
| `Graph DB has not been set` | The `graphDb` property is empty |
| `Unable to load graph db …` | It points at a graph that cannot be resolved — deleted, renamed, or ambiguous |

### Per-record — logged and skipped

Everything else. Each record is committed as its own all-or-nothing unit; if one fails it is rolled back,
logged at `ERROR`, and **the stream carries on with the next record**.

| Message | Cause |
|---|---|
| `<node> requires both id and validFrom` | Missing attribute |
| `<node-delete> requires both id and validFrom` | Missing attribute |
| `<edge> requires type, validFrom, src and dst` | Missing attribute or child |
| `<edge-delete> requires type, validFrom, src and dst` | Missing attribute or child |
| `<label> is only valid inside a <node> element` | Misplaced element |
| `<property> is only valid inside a <node> or <edge> element` | Misplaced element |
| `<property> requires a name attribute` | Missing attribute |
| `Unable to parse validFrom "…"` | Timestamp not in the required format |
| `Failed to write <…>` | A storage-level failure, e.g. a value too large for a key, or more than 255 labels |

> **This is the data-safety issue to keep in mind.** A partially-loaded graph looks exactly like a fully
> loaded one. Nothing turns red. After any load, check the stream's error count rather than assuming
> success, and treat a non-zero count as data loss until proven otherwise.

There is a third, quieter failure mode with no message at all: **elements the filter does not recognise are
simply ignored.** The filter dispatches on lower-cased element local names and performs no schema
validation, so `<nodes>` or `<Node-Delete >` with a typo contributes nothing and reports nothing. Validating
your output offline is the only protection.

## Validating your output offline

The `graph-mutation:1` schema exists in the source tree at:

```
stroom-graphdb/stroom-graphdb-impl/src/test/resources/TestGraphFilter/graph_mutation_v1_0.xsd
```

> **Not shipped.** It is a test resource. It is not registered as an XMLSchema document in Stroom, so you
> cannot add a `SchemaFilter` to validate against it in-pipeline, and the Graph Filter does not validate
> either.

The practical workaround is to copy it out and validate your translation's output as part of development:

```bash
xmllint --noout --schema graph_mutation_v1_0.xsd my-output.xml
```

Given the silent-skip behaviour above, this is strongly worth doing before trusting any new translation.

## Data modelling

A few decisions come up every time.

**Node or property?** Make something a node if you want to traverse to it, count things attached to it, or
find other things connected to it. Make it a property if you only ever read it once you have arrived. A
person's surname is a property. The location they live at is a node, because you will want to ask who else
lives there.

**Event as edge, or event as node?** If an event connects two things and carries little of its own
interest, model it as an edge (`(user)-[:ACCESSED]->(file)`). If it carries attributes you want to filter
or count on, or if the same pair can be related many times and you need each occurrence separately, model
the event as a node with edges to its participants. Edges are considerably cheaper; prefer them unless you
need otherwise.

**Choosing node ids.** Ids are the identity across every load, so they must be **stable and deterministic**
— derived from natural keys in the data, never from stream position, row number or ingest time. A prefix
per kind (`user:alice`, `file:/etc/passwd`) keeps them readable and collision-free. Unstable ids are the
classic way to end up with a graph full of near-duplicate nodes that never connect to each other.

**Keep property values short.** Values participate in index keys, with a hard ceiling around 511 bytes and
progressively less efficient handling as they grow. Short, high-selectivity values index best — see
[10-limits.md](10-limits.md).

## Versioning of the format

The `version` attribute is `1.0`, and the schema permits no other value. A future revision — typed property
values is the most likely first change — would raise it. Emit `1.0` explicitly rather than relying on a
default, so that a later version change is a visible edit to your translation rather than a silent
behaviour change.

## Loading your first graph

End to end, with no source data required.

**1. Create the documents.** In the explorer, create:

- a **GraphDb** document, with a distinctive name (`POLE Graph`);
- a **Feed** to receive data (`POLE-GRAPH`) — the defaults are fine: status `Receive`, `Raw Events`, UTF-8;
- a **Pipeline** with three elements wired `Source → XML Parser → Graph Filter`, and the Graph Filter's
  `graphDb` property set to your GraphDb document.

**2. Check the wiring.** The pipeline's dependencies should all resolve. A broken `graphDb` reference here
is the fatal error above, and it is much easier to spot now than after a failed load.

**3. Upload some data.** Use the feed's **Upload** button with a small `graph-mutation:1` document — the
example at the top of this file will do.

**4. Process it.** Create a processor filter on the pipeline for that feed. Processing is asynchronous, so
it will not be instant.

**5. Verify.** Remember there is no `Events` output stream — success looks like *nothing*. So check
instead that:

- no **Error** stream has appeared for the feed, and
- the graph actually has data in it. Open the GraphDb document's **Explore** tab and run:

```cypher
MATCH (n) RETURN GRAPH
```

If nodes appear, the load worked. If the graph is empty and there is no error stream, the most likely cause
is unrecognised element names being silently ignored — validate your XML against the schema as above.

## Next

- [04-event-logging-xslt.md](04-event-logging-xslt.md) — a full worked translation from Stroom
  event-logging XML
- [06-language-reference.md](06-language-reference.md) — querying what you have loaded
- [10-limits.md](10-limits.md) — the size ceilings referenced above
