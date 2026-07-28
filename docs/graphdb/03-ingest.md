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

## Property value types

A property is a string unless you declare otherwise:

```xml
<property name="surname">Powell</property>              <!-- string, the default -->
<property name="age" type="long">42</property>          <!-- whole number -->
<property name="active" type="boolean">true</property>  <!-- true / false -->
```

Only `string`, `long` and `boolean` are available. A value that does not parse as its declared type is a bad
record, reported like any other — it is not quietly kept as text.

### What declaring a type actually gets you

It is worth being precise, because this is easy to over-sell:

| | Untyped string | Declared type |
|---|---|---|
| **Value's type when read back** | always `STRING` | the declared type — so JSON output renders `42` and `true` unquoted rather than as `"42"` and `"true"`, and type-aware code paths see a number |
| **`ORDER BY`** | already numeric for numeric-looking text (see below) | numeric | 
| **Equality anchor lookup** | on the text as written | on the value's canonical form, so `007` and `7` are the same value |
| **Functions like `toInteger()`** | needed | unnecessary |

> **Ordering was already numeric, contrary to what this document previously said.** Stroom compares
> `STRING` values with a numeric-first comparator that falls back to text, so `"9"` already sorted before
> `"10"`. What was genuinely wrong is that *every* value's type was `STRING` regardless of what it held, so
> anything reading a value back saw text. That — not sort order — is what declaring a type fixes.

### `double` and dates are not available, and why

Both are deliberately absent rather than merely unimplemented.

The equality anchor index is keyed on a value's **rendered text**, and a query seeks it using the query
literal's **own text**. Those must agree or the node is silently not found. A double breaks the agreement:
`42.0` renders canonically as `42`, so a query for `42.0` would find nothing and report no error. Dates have
the same problem in a worse form, since one instant has many valid renderings.

Making them safe needs a canonical encoder shared by the ingest, merge and query sides — tracked in
[12-future-work.md](12-future-work.md).

### Encoding advice for what is not typed

For dates and decimals, encode so that text order is the order you want:

| Kind of value | Emit as | Why |
|---|---|---|
| Timestamps and dates | ISO-8601, zero-padded, UTC — `2026-07-28T14:03:00.000Z` | Text order equals chronological order |
| Decimals you will sort or range-filter | Zero-padded to a fixed width — `000042.50` | Text order equals numeric order |
| Booleans | `type="boolean"` | Now a real type; no encoding needed |
| Whole numbers | `type="long"` | Now a real type; no zero-padding needed |

At query time you can still convert with `toInteger()`, `toFloat()`, `toBoolean()` and parse dates with
`stroom.parseDate()` — see [07-functions.md](07-functions.md). Conversion happens per row during evaluation,
so it does not help the anchor lookup; if you need to filter on a value efficiently, store it in the form you
will filter on.

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

> **The graph is resolved by UUID.** Renaming a graph does not break a pipeline pointing at it, and two graphs
> sharing a name is no longer a problem for ingest. The property stores a document reference, and it is the
> reference — not the name — that the filter follows.

One filter writes to exactly one graph; there is no per-record routing to different graphs.

### The `strict` property

`strict` decides what a bad record costs. It defaults to `false`.

| `strict` | A bad record is… | Choose it when |
|---|---|---|
| `false` (default) | logged at `ERROR` and **skipped**; the stream continues | A feed must keep flowing and you monitor error counts. This is also how the Plan B filter behaves |
| `true` | logged at `FATAL_ERROR` and **fails the whole stream** | The graph's completeness is load-bearing — you would rather have no data than quietly incomplete data |

Neither is simply better. Lenient loses data quietly, so a graph can look healthy while missing part of its
input. Strict cannot lose data quietly, but one malformed record blocks a feed until someone fixes it.

> **If you are loading a graph whose answers people will act on, turn `strict` on.** The failure you cannot
> detect is worse than the one that wakes you up. Reprocessing a stream is cheap; discovering six months later
> that a subset of edges never loaded is not.

## What happens when something is wrong

### Always fatal — the whole stream fails

Configuration problems, raised before any record is processed, regardless of `strict`:

| Message | Cause |
|---|---|
| `Graph DB has not been set` | The `graphDb` property is empty |
| `Unable to load graph db …` | It points at a graph that cannot be resolved — deleted, renamed, or ambiguous |

### Per-record — skipped, or fatal in strict mode

Everything else. Each record is committed as its own all-or-nothing unit; if one fails it is rolled back and
then either logged and skipped (lenient) or fails the stream (strict).

| Message | Cause |
|---|---|
| `<node> requires both id and validFrom` | Missing attribute |
| `<node-delete> requires both id and validFrom` | Missing attribute |
| `<edge> requires type, validFrom, src and dst` | Missing attribute or child |
| `<edge-delete> requires type, validFrom, src and dst` | Missing attribute or child |
| `<label> is only valid inside a <node> element` | Misplaced element |
| `<property> is only valid inside a <node> or <edge> element` | Misplaced element |
| `<property> requires a name attribute` | Missing attribute |
| `<…> is not a graph-mutation element` | An element the vocabulary does not define — usually a typo |
| `Unable to parse validFrom "…"` | Timestamp not in the required format |
| `Failed to write <…>` | A storage-level failure, e.g. a value too large for a key, or more than 255 labels |

> **In lenient mode this is the data-safety issue to keep in mind.** A partially-loaded graph looks exactly
> like a fully loaded one. Nothing turns red. After any load, check the stream's error count rather than
> assuming success, and treat a non-zero count as data loss until proven otherwise.

Unrecognised elements used to be the quietest failure of all — ignored with no message, so a misspelled
`<nodee>` contributed nothing and reported nothing. They are now reported like any other bad record. Note
that only element *names* are checked; nesting is the schema's job, so use a `SchemaFilter` for that.

## Validating against the schema

The `graph-mutation:1` XSD is shipped, and you can fetch its source from a running Stroom:

```bash
curl -s -H "Authorization: Bearer $TOKEN" https://<stroom>/api/graphDb/v1/mutationSchema
```

### In-pipeline validation

1. Create an **XMLSchema** document and paste the XSD source into it.
2. Set its **Namespace URI** to `graph-mutation:1` and its **System Id** to `graph-mutation-v1.0.xsd`.
3. Add a **SchemaFilter** to your pipeline between the translation and the Graph Filter.

> **Your translation must emit `xsi:schemaLocation`.** `SchemaFilter` rejects a document that does not declare
> where its namespace's schema is, so without it validation fails outright rather than passing silently. The
> root element needs:
>
> ```xml
> <graph xmlns="graph-mutation:1"
>        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
>        xsi:schemaLocation="graph-mutation:1 graph-mutation-v1.0.xsd"
>        version="1.0">
> ```
>
> The system id must match the one you registered the XMLSchema document under. The worked example in
> [04-event-logging-xslt.md](04-event-logging-xslt.md) emits this.

There is no prebuilt content pack for this — Stroom's content packs come from the separate
`gchq/stroom-content` repository, so the XMLSchema document is created by hand (or by import) for now.

### Offline validation

Still worth doing while developing a translation, because it needs no Stroom at all:

```bash
xmllint --noout --schema graph-mutation-v1.0.xsd my-output.xml
```

Validation is not a substitute for `strict`, and neither replaces the other: the schema catches shape errors
before ingest, while `strict` catches what a schema cannot — a value too large for a key, more than 255 labels,
or any other failure that only appears once a record reaches storage.

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
