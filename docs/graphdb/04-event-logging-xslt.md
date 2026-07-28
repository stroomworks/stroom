# Converting Stroom event logging to graph mutations

**Status:** Evaluation / proof of concept — not production ready. See the
[production-readiness blockers](README.md#production-readiness--known-blockers).
The XSLT here was executed and its output schema-validated (see [Verification](#verification)); it is
**not** covered by automated tests, so re-verify it if the schemas change.
**Audience:** translation authors turning event-logging XML into a graph.
**Scope:** a complete worked translation from `event-logging:3` (v4.1.0) to `graph-mutation:1`, and the
modelling decisions behind it.
**Companion documents:** [03-ingest.md](03-ingest.md) (the target vocabulary),
[08-analysis-examples.md](08-analysis-examples.md) (querying the result).

*Facts verified on 2026-07-28 against branch `sw-query-optimiser`. Transform run with Saxon-HE 9.9.1-8.*

---

Stroom's normalised event format is rich, deeply nested and *event*-shaped. A graph is *entity*-shaped. The
translation between them is the interesting part of putting event data into a graph, and it is where all
the modelling decisions live.

> **A note on namespaces.** The schema file is named `event_logging_v4_1_0.xsd`, but its target namespace
> is **`event-logging:3`**. The namespace did not change at v4. Bind `evt` to `event-logging:3` or nothing
> will match.

## The modelling decision: event as edge, or event as node?

This choice determines everything else, so make it deliberately.

**Event as edge** — each event becomes a relationship between entities that already exist:

```
(user:alice) -[:ACCESSED {action: 'VIEW'}]-> (file:/finance/salaries.xlsx)
```

**Event as node** — each event becomes a node, with edges to its participants:

```
(user:alice) -[:PERFORMED]-> (event:12345) -[:ON]-> (file:/finance/salaries.xlsx)
```

| | Event as edge | Event as node |
|---|---|---|
| Elements per event | ~1 | ~3 |
| Path length between two entities | 1 hop | 2 hops |
| Can hold many attributes | Limited | Yes |
| Repeated identical actions | Become versions of one edge | Stay distinct |

**Recommendation: event as edge**, which is what the translation below does. It produces roughly a third of
the elements, keeps patterns short, and suits the temporal model — an edge that recurs simply gains
versions, which is exactly what `AS OF` and `DIFF` are for. Given the fixed 10 GiB store
([10-limits.md](10-limits.md)), the size difference is not academic.

Choose event-as-node when the event itself carries attributes you need to filter or aggregate on, or when
you must count repeated occurrences of the same action on the same pair separately. You can mix: model most
events as edges and promote just the significant ones to nodes.

## The target model

| Node label | Id form | From |
|---|---|---|
| `User` | `user:{id}` | `EventSource/User/Id` |
| `Device` | `device:{hostname}` | `EventSource/Device`, `/Client`, `/Server` |
| `Session` | `session:{id}` | `EventSource/SessionId` |
| `File` | `file:{path}` | any `File` under `EventDetail` |
| `Application` | `app:{name}` | `EventSource/System/Name` |

| Edge type | From → To | Emitted for |
|---|---|---|
| `USED` | User → Application | every event |
| `AUTHENTICATED_ON` | User → Device | `Authenticate` |
| `STARTED_SESSION` | User → Session | `Authenticate` |
| `SESSION_ON` | Session → Device | `Authenticate` |
| `ACCESSED` | User → File | `View`, `Create`, `Update`, `Delete`, … |
| `HOSTED_ON` | File → Device | any file action |
| `CONNECTED_TO` | Device → Device | `Network` with `Client` and `Server` |

## Building the translation

### Identity must be stable

Node ids are the identity across every load, for all time. They must be **deterministic** — derived only
from natural keys in the data, never from stream position, row number or processing time. Get this wrong
and each load creates a fresh set of near-duplicate nodes that never connect.

```xslt
<xsl:template name="stable-id" as="xs:string">
  <xsl:param name="kind" as="xs:string"/>
  <xsl:param name="key" as="xs:string"/>
  <xsl:value-of select="concat($kind, ':', lower-case(normalize-space($key)))"/>
</xsl:template>
```

Lower-casing and whitespace normalisation matter more than they look: `ALICE`, `alice ` and `alice` are one
person, and without normalisation they become three nodes.

For devices, prefer a hostname and fall back to an IP address, so the same machine does not fragment:

```xslt
<xsl:function name="gm:device-id" as="xs:string?">
  <xsl:param name="device" as="element()?"/>
  <xsl:sequence select="if (empty($device)) then ()
                        else if ($device/evt:HostName)
                        then concat('device:', lower-case(normalize-space($device/evt:HostName)))
                        else if ($device/evt:IPAddress)
                        then concat('device:', lower-case(normalize-space($device/evt:IPAddress)))
                        else ()"/>
</xsl:function>
```

### Timestamps are the biggest hazard

`validFrom` must be **exactly** `yyyy-MM-ddThh:mm:ss.sssZ` — three fractional digits, literal `Z`. A value
that does not match fails that record, and only that record, which is then logged and skipped while the
stream carries on. Timestamps are therefore the most likely cause of a graph that is quietly missing data.

Event-logging times are `xs:dateTime`, which permits a timezone offset and any number of fractional digits,
so normalise rather than copying through:

```xslt
<xsl:function name="gm:when" as="xs:string">
  <xsl:param name="event" as="element(evt:Event)"/>
  <xsl:variable name="utc"
                select="adjust-dateTime-to-timezone(
                          xs:dateTime($event/evt:EventTime/evt:TimeCreated),
                          xs:dayTimeDuration('PT0S'))"/>
  <xsl:value-of
      select="format-dateTime($utc, '[Y0001]-[M01]-[D01]T[H01]:[m01]:[s01].[f001]Z')"/>
</xsl:function>
```

`adjust-dateTime-to-timezone(…, PT0S)` converts to UTC; `[f001]` forces exactly three fractional digits.
Both halves are needed.

### Emit each node once, not once per event

The naive approach emits a `User` node on every event that mentions it. That is not *wrong* — the store is
keyed by (id, `validFrom`), so it produces a version per event — but it is pure bloat: thousands of
identical versions of one node, in a store that never condenses versions and cannot be resized.

Instead, gather node references first, then emit each distinct id **once**, dated at the earliest time it
was seen in the batch:

```xslt
<xsl:variable name="refs" as="element(gm:ref)*">
  <xsl:apply-templates select="evt:Event" mode="nodes"/>
</xsl:variable>

<xsl:for-each-group select="$refs" group-by="@id">
  <xsl:variable name="earliest"
                select="min(for $t in current-group()/@ts return xs:string($t))"/>
  <node id="{current-grouping-key()}" validFrom="{$earliest}">
    <xsl:for-each select="distinct-values(current-group()/@label)">
      <label><xsl:value-of select="."/></label>
    </xsl:for-each>
    <xsl:for-each-group select="current-group()/gm:p" group-by="@n">
      <property name="{current-grouping-key()}">
        <xsl:value-of select="current-group()[1]"/>
      </property>
    </xsl:for-each-group>
  </node>
</xsl:for-each-group>
```

`min()` over ISO-8601 strings works because that format sorts lexically in chronological order — one of the
reasons [03-ingest.md](03-ingest.md) recommends it for stored values too. Note the explicit
`xs:string($t)`: attributes are untyped, and `min()` would otherwise try to treat them as numbers.

This dedupes **within a batch**. Across batches the same node will be emitted again at a new timestamp,
which is correct: that is a genuine new version recording that the entity was still present.

### Dispatch on the event type

Templates matched on the shape of `EventDetail` keep the translation extensible — adding an event type is a
new template, not an edit to a growing `choose`:

```xslt
<xsl:template match="evt:EventDetail[evt:Authenticate]" mode="edges"> … </xsl:template>
<xsl:template match="evt:EventDetail[.//evt:File]" mode="edges" priority="2"> … </xsl:template>
<xsl:template match="evt:EventDetail[evt:Network]" mode="edges"> … </xsl:template>

<!-- Fall-through: an event type this translation does not model. -->
<xsl:template match="evt:EventDetail" mode="edges"/>
```

The `priority="2"` matters: a `Create` event containing a `File` matches both the file template and any
type-specific one, and priority resolves it. Without an explicit priority Saxon reports an ambiguous rule
match.

The file template deliberately matches on **`.//evt:File`** rather than on each verb. `View`, `Create`,
`Update` and `Delete` all wrap their file differently — `Update` puts it inside `<After>` — so matching the
file wherever it appears and reading the verb separately is far more robust than one template per verb:

```xslt
<xsl:function name="gm:action" as="xs:string">
  <xsl:param name="event" as="element(evt:Event)"/>
  <xsl:variable name="verb"
                select="local-name(($event/evt:EventDetail/*[local-name() = (
                          'View','Create','Update','Delete','Copy','Move',
                          'Import','Export','Print')])[1])"/>
  <xsl:sequence select="if ($verb) then upper-case($verb) else 'UNKNOWN'"/>
</xsl:function>
```

### The silent fall-through

The empty catch-all template above is correct — you cannot model every event type — but it is *silent*, and
so is the Graph Filter about events you never emitted. An event type you forgot simply never appears in the
graph, with nothing anywhere reporting it.

Before trusting a translation, find out what you are dropping:

```bash
# event types present in the source
xmllint --xpath '//*[local-name()="TypeId"]/text()' sample-events.xml | sort -u
```

Compare that against the types your templates handle. Do this whenever the source system changes.

## The complete stylesheet

Three files accompany this document:

| File | What it is |
|---|---|
| [`examples/event-logging-to-graph.xslt`](examples/event-logging-to-graph.xslt) | The complete transform |
| [`examples/sample-events.xml`](examples/sample-events.xml) | The seven-event corpus, valid against the event-logging schema |
| [`examples/expected-output.xml`](examples/expected-output.xml) | What the transform produces, valid against `graph-mutation:1` |

Because the transform is deterministic, re-running it over the corpus should reproduce
`expected-output.xml` byte for byte — which makes the three files a manual regression test.

Copy the stylesheet into an XSLT document in Stroom and put it before the Graph Filter:

```
Source → XML Parser → XSLT Filter (this stylesheet) → Graph Filter
```

## What it produces

From seven events (a logon, a file view, an update, a create, a failed delete, a network connection, and a
search the translation does not model) it produces **14 nodes and 19 edges**:

```xml
<graph xmlns="graph-mutation:1" version="1.0">
   <node id="app:payroll" validFrom="2026-07-01T09:15:00.000Z">
      <label>Application</label>
      <property name="name">Payroll</property>
      <property name="environment">Production</property>
   </node>
   <node id="device:ws-001.example.org" validFrom="2026-07-01T09:15:00.000Z">
      <label>Device</label>
      <property name="hostName">ws-001.example.org</property>
      <property name="ipAddress">10.0.0.11</property>
   </node>
   …
   <edge type="ACCESSED" validFrom="2026-07-01T09:20:00.000Z">
      <src>user:alice</src>
      <dst>file:/finance/salaries.xlsx</dst>
      <property name="action">VIEW</property>
      <property name="outcome">SUCCESS</property>
   </edge>
   …
</graph>
```

Two details worth noting in that output. The failed delete is preserved rather than dropped, carrying
`outcome=FAILURE` — failed attempts are usually the interesting ones. And the unmodelled `Search` event
still produced its `USED` edge, because that edge is emitted for every event regardless of type; only the
action-specific edges were skipped.

## Verification

The transform was executed and its output validated before this document was written. Re-run this if either
schema changes.

**Use Saxon-HE 9.9.1-8** — the version Stroom itself runs (`gradle/libs.versions.toml`). A stylesheet that
only works on a newer Saxon will fail inside the pipeline.

```bash
# 1. validate the input corpus against the event-logging schema
xmllint --noout --schema event_logging_v4_1_0.xsd sample-events.xml

# 2. transform, with the same Saxon version Stroom uses
java -jar Saxon-HE-9.9.1-8.jar \
     -s:sample-events.xml -xsl:event-logging-to-graph.xslt -o:out.xml

# 3. validate the output against the ingest schema
xmllint --noout --schema graph_mutation_v1_0.xsd out.xml
```

Step 3 is the one that makes the example trustworthy, and it is the reason
[03-ingest.md](03-ingest.md) recommends adding it to your own development loop — the Graph Filter performs
no validation of its own.

Then assert the things the schema cannot express:

| Assertion | Result |
|---|---|
| Every `validFrom` matches `yyyy-MM-ddThh:mm:ss.sssZ` | 33 values, all pass |
| Every edge `src`/`dst` resolves to a node in the same output | 11 endpoints, all resolve |
| No property value exceeds 511 bytes | 32 values, all pass |
| No node carries more than 255 labels | max 1 |
| Output is byte-identical across two runs (ids are stable) | pass |

The dangling-edge check is the valuable one. An edge whose endpoint was never emitted is accepted by the
Graph Filter without complaint — it interns the unknown id and creates an edge to a node that has no labels
or properties. Queries then return partial results for reasons that are very hard to see.

> **Not covered by automated tests.** This stylesheet lives in documentation, not in the build. Landing it
> and its corpus as a test in `stroom-graphdb-impl` is proposed in
> [12-future-work.md](12-future-work.md).

## Next

- [08-analysis-examples.md](08-analysis-examples.md) — querying the graph this produces
- [03-ingest.md](03-ingest.md) — the target vocabulary in full
- [10-limits.md](10-limits.md) — the size constraints that motivate event-as-edge
