# Code structure and extending Graph DB

**Status:** Evaluation / proof of concept — not production ready. See the
[production-readiness blockers](README.md#production-readiness).
**Audience:** developers working on Graph DB itself.
**Scope:** where the code lives, how a query flows through it, and recipes for the extensions people
actually make. Also the index into the engineering records.
**Companion documents:** [02-architecture.md](02-architecture.md) (the storage and temporal model in
user terms), [12-future-work.md](12-future-work.md) (what is worth building).

*Class inventory re-verified against the code on 2026-07-29, branch `sw-query-optimiser`.*

---

## Part A — the code map

Graph DB spans four areas. The split is not arbitrary: the **language** lives with the rest of Stroom's
query stack so that dispatch and the logical IR are shared, while the **engine and storage** are
self-contained.

| Area | Module | Contains |
|---|---|---|
| Engine, storage, ingest | `stroom-graphdb/stroom-graphdb-impl` | Everything that touches LMDB or executes a plan |
| Shared types | `stroom-core-shared` (`stroom.graphdb.shared`) | The document, REST contract, wire records |
| UI | `stroom-core-client` (`stroom.graphdb.client`) + `stroom-app/src/main/resources/ui/graph.js` | GWT presenters and the Cytoscape sandbox |
| Language | `stroom-query/stroom-query-grammar`, `stroom-query/stroom-query-planner` | Grammar, AST, compiler |

### Storage layer — `stroom.graphdb.impl`

| Class | Role |
|---|---|
| `GraphStores` | Opens and owns every internal store for one document as a unit. Also `delete()`, `rebuild()`, `deleteOldData()` |
| `GraphNodeDb` | Node versions, keyed by node then `validFrom`. Reverse-cursor floor scan for point-in-time |
| `GraphAdjacencyDb` | Out-edge versions, keyed source → type → destination → `validFrom` |
| `GraphInEdgeDb` | The in-edge mirror. **Callers must dual-write** — this is the easiest invariant to break |
| `GraphPropertyIndex` | (label, property key, value) → node. The only seekable access path |
| `GraphPropsCodec` | Property map ↔ blob |
| `GraphStoreManager` | Per-UUID registry of open stores; resolves `<graphdb.path>/shards/<uuid>`. **Lends** a store for the duration of a call — see below |
| `GraphSchemaDb` | The `graph-info` table: the store's format stamp, and the persisted hash-clash counter |
| `GraphAnchorEncoding` | The single definition of how a property value becomes property-index key bytes, for both the write and the seek side. **Read it before touching the index** |
| `GraphTimeSerdes` | Selects the `validFrom` encoding for a document's Temporal Precision, and rejects `NANOSECOND` |

Key widths are fixed (`NODE_UID_WIDTH = 6`, `TYPE_UID_WIDTH = 4`) because prefix range scans depend on
it — a variable-width key would break every traversal. Physical layouts are in each class's Javadoc.

#### The property index must agree with the predicate

An anchor is only a **candidate** filter: `GraphTraversalEngine` re-checks every candidate it returns against
the node's real decoded properties before it becomes a row. The two failure directions are therefore not
symmetric.

- An anchor that returns **too much** costs a little wasted work.
- An anchor that returns **too little** silently loses rows, and nothing distinguishes that from a node that
  is genuinely absent.

So when a change makes it unclear whether some value should match, **make it match** and let the predicate
decide. That is why numbers are keyed by value rather than text, why a numeric literal seeks both the text and
the number encoding rather than consulting a type registry, and why longs above 2<sup>53</sup> are allowed to
share an encoding.

The corollary is that the index must never be *looser* than the predicate either. Stroom's `=` on numbers is
exact (`Objects.equals(Double, Double)`), so the index is exact. If you are tempted to make the index tolerant
of floating-point error, that belongs in the predicate — and the predicate is product-wide, not Graph DB's to
change unilaterally.

#### Never hold a `GraphStores` past the call that got it

`GraphStoreManager` has no method that returns a store. Everything goes through:

```java
final long count = graphStoreManager.use(doc, stores -> stores.read(stores.getNodes()::count));
```

with `useForQuery` as the read-side variant that additionally reports a node holding nothing for the graph.
Work with no result returns `null`, matching `GraphStores.write`.

The reason is `compact`, which replaces a store's file on disk. It takes the graph's write lock, so it waits
for every in-flight `use` to return and cannot start while one is running — but only because no reference
exists outside those calls. A store stashed in a field, returned from a method, or captured by a lambda that
outlives the call is a reference the manager cannot see, and compaction will pull the file out from under it.
The same applies to `delete`.

`getOrOpenUnguarded` exists and is named to be unattractive. Only `use` and `compact` may call it.

### Cluster layer — `stroom.graphdb.impl`

| Class | Role |
|---|---|
| `GraphShardWriters` | Creates the per-stream fragment ingest writes into; ships and deletes it on close |
| `GraphFileTransferClient` / `…ClientImpl` | Replicates a fragment to every node in `graphdb.nodeList` |
| `GraphFileTransferResource` / `…ResourceImpl` | `/graphFileTransfer/v1/sendPart` — receives a fragment |
| `GraphPartDestination` | Lands a fragment (local or remote) and hands it to the merge processor |
| `GraphMergeProcessor` | Merges staged fragments into each graph's authoritative store |
| `GraphQueryNodeResolverImpl` | Pins a graph query to a node that holds graph data |
| `GraphBackfillService` | Copies a whole graph to every configured node, for one added after the data was loaded. Reuses the fragment transport, because a fragment and a store are the same shape |
| `GraphRootMarker` | Startup check: reports a `graphdb.path` changed without the data being moved. Its marker lives under `stroom.home`, deliberately *outside* the graph root |
| `GraphPaths` / `GraphDbConfig` | The directory layout, and the eight settings that drive it and the traversal guardrails |

The receive-stage-unzip-merge loop itself is **not** here: it is
`stroom.planb.impl.data.PartMergeProcessor`, shared with Plan B. It lives in Plan B's package because
`DirQueue` and `Dir` have package-private constructors and their durable, restart-recoverable, id-ordered
queue semantics are the substance of what is being reused. Plan B's `MergeProcessor` is a thin façade over
the same engine.

### The store format stamp — read before changing any layout

`GraphSchemaDb` records a `CURRENT_SCHEMA_VERSION` plus a textual description of the key and value layouts in
a `graph-info` table. It is validated when a store is opened for writing and again before a merge, and a
mismatch **refuses the operation** rather than proceeding.

**If you change any of the following, bump `CURRENT_SCHEMA_VERSION`:** either UID width, the time serde, any
of the four key layouts, the property-index tier format or its DIRECT length limit, the **anchor value
encoding** (`GraphAnchorEncoding`), or the props codec.

Version 2 was the anchor encoding gaining a type tag and numbers being keyed by value rather than by their
rendered text.

There is deliberately **no migration path**. Existing graph data is treated as reproducible — a store written
by a different build is wiped and rebuilt (`GraphStores.rebuild()`, or by reprocessing the source streams).
The stamp exists so that a layout change fails loudly at open time instead of reading old bytes as though they
were new ones and returning wrong answers silently.

### Merge — what makes it correct

Worth understanding before touching it, because two of its properties are load-bearing and neither is obvious:

- **No byte-copy fast path exists.** Every key embeds fragment-local interned ids, so every row is decoded,
  translated through a per-namespace id map and re-encoded.
- **The property index is rebuilt, never row-copied.** Its hash-tier keys carry clash-sequence suffixes that
  are meaningful only in the store that assigned them, so copying a hash-tier row into another store is
  incorrect rather than merely slow. Rebuilding also reuses the ingest encoding path, which is why both sides
  go through `GraphAnchorEncoding`.
- **Merge is idempotent by construction** — id interning is get-or-create, no timestamp is generated during
  merge, and index puts are same-key. Preserve this: it is what makes a fragment delivered twice harmless.
- **Out-edge and in-edge rows for one edge are written between commit boundaries**, so an auto-commit can
  never split a pair and leave a one-sided edge.

### Query layer

| Class | Role |
|---|---|
| `GraphTraversalEngine` | ~2,500 lines and the heart of it. Anchor resolution, chain and variable-length traversal, temporal dispatch, projection, aggregation, every guardrail |
| `GraphElementExecutor` | Renders `RETURN GRAPH` element rows, plain and diff-annotated |
| `DiffExecutor` / `DiffOperator` / `ChangeKind` | Two-instant comparison and classification |
| `GraphSearchProvider` | The `SearchProvider` for `GraphDbDoc`. **Synchronous** — the engine is an in-memory call over one read transaction |
| `CypherCompiler` / `GraphCypherQueryCompiler` | The `AlternativeQueryCompiler` binding that routes Cypher to graph documents |
| `GraphSchemaService` / `GraphExpandService` | Back the Discover panel and Expand neighbours |

### Language stack — `stroom-query`

```
Cypher.g4                    ANTLR grammar. Omission IS the rejection mechanism
   ↓
AstCypherBuilder             parse tree → AstCypherStatement
   ↓
CypherToLogicalPlan          ~1,900 lines. Validates and lowers; throws CypherCompileException
   ↓
logical IR                   NodeScan, Expand, VarLengthExpand, Filter, Project, Sort, Limit
                             — shared with the relational core
   ↓
GraphTraversalEngine         executes against one document's LMDB
```

The grammar is deliberately narrow: anything absent from `Cypher.g4` is a parse error rather than a
semantic check. Writes are unsupported because `SET`/`CREATE`/`MERGE`/`DELETE` are simply not keywords.

`CypherToLogicalPlan` holds 64 explicit rejections, all prefixed `not in PoC subset:` or
`not supported in this version:`. When adding a feature you are usually **removing** one of those and
implementing the path behind it.

### Request flow

```
query text
  → CypherQueryParser.parseStatement        (ANTLR → AST)
  → CypherToLogicalPlan.compileStatement    (→ CompiledCypherStatement)
  → CypherCompiler.create                   (→ SearchRequest; Cypher text rides in Query.graphSpec)
  → GraphSearchProvider.createResultStore    (re-parses and re-compiles at execution time)
  → GraphTraversalEngine.execute             (→ List<Val[]>)
  → coprocessors → ResultStore
```

Note the double compile: the compiler produces a `SearchRequest` carrying the **raw Cypher text**, and the
search provider compiles it again at execution. That keeps `SearchRequest` serialisable across the cluster
boundary.

### How a query reaches Cypher rather than StroomQL

In `QueryServiceImpl.mapRequest`, dispatch is **by resolved DocRef type — never by sniffing the text**:

1. `SearchRequestSource.ownerDocRef` if present (the GraphDb document's own tabs).
2. Otherwise the leading `from "X"` name, extracted by `LeadingDataSourceExtractor` — grammar-agnostic, it
   reuses the legacy tokeniser and reads the first `FROM` group's first child without parsing the body.
3. `AlternativeQueryCompilerResolver.resolve` picks the first compiler whose `supports(ref)` is true.
   `GraphCypherQueryCompiler.supports` is exactly `GraphDbDoc.TYPE.equals(ref.getType())`.
4. No match → StroomQL, unchanged.

This is why the same Cypher text runs from a Query document, a dashboard, `/csv/search` and MCP.

### The UI bridge

The Cytoscape view is a sandboxed same-origin iframe (`ui/graph.html` + `ui/graph.js`) driven over the same
postMessage transport as dashboard visualisations. `GraphFrame` hosts it; `GraphResultWidget` ships rows in
and handles the reverse channel.

The reverse channel is worth understanding before touching the UI — see recipe 5.

## Part B — extension recipes

### 1. Add a scalar function

**Files:** `CypherFunctions.java`, then `CypherToLogicalPlan`.

For a Stroom function, add its name to the `STROOM` set — that is the whole change, since `stroom.`-prefixed
names render straight through. For a bare Cypher name that maps 1:1 onto a Stroom function, add a pair to
`CYPHER_STANDARD`. If the signature differs, add a case to `renderCypherAdaptedFunction` (see how
`substring`, `left` and `coalesce` are handled).

**Test:** a compile test asserting the rendered expression, plus an execution test through
`TestGraphTraversalEngine`.

### 2. Add an aggregate

**Files:** `Cypher.g4` (token, and the `aggregateCall` rule), `AstAggregateFunction`,
`CypherToLogicalPlan.compileAggregateColumn`, `GraphTraversalEngine`.

The engine part is a `reduceX` method alongside `reduceCount`/`reduceSum`. Decide the empty-group result
deliberately — the existing ones differ on purpose (`sum` → 0, `avg` → null), and that asymmetry is
Cypher's, not an accident.

If the aggregate returns anything other than a scalar `Val`, you need the list type first — this is exactly why
`collect()` is currently rejected at compile time rather than shipping a joined string
([12a-list-value-type.md](12a-list-value-type.md)).

### 3. Add a clause or pattern form

The full walk: `Cypher.g4` → `AstCypherBuilder` → a new AST record → `CypherToLogicalPlan` → logical IR
(reuse a node if you can; adding one touches the relational core too) → `GraphTraversalEngine`.

Two rules worth respecting:

- **Delete the rejection message in the same change.** A feature that works but still has its
  `not supported in this version:` guard upstream is worse than either state.
- **If the grammar cannot express it, that is the rejection.** Prefer leaving something out of the grammar
  over accepting and then refusing it — the parse error is clearer and cheaper.

### 4. Extend `graph-mutation`

**Files:** `src/main/resources/stroom/graphdb/graph-mutation-v1.0.xsd`, `GraphFilter`, the store write path,
and [03-ingest.md](03-ingest.md).

`GraphFilter` dispatches on **lower-cased element local names** and does no schema validation, so a new
element is a new case in `startElement`/`endElement`. Bump the schema `version` enumeration for anything
that is not purely additive, and keep the two in step — the XSD is documentation for XSLT authors, so a
drift between it and the filter is a silent trap for them.

**Adding a property type** is more than a new `case` in `GraphFilter.toVal`. Ask what a query literal for
that type looks like, and make sure `GraphAnchorEncoding` maps both the stored value and that literal to the
same bytes — see the rule below. A type whose anchor no literal can reach is not a broken query, it is an
empty result with no error.

### 5. Add a Cytoscape toolbar control

This is the best-trodden path, with four precedents. The graph runs in a sandboxed iframe and cannot call
into Stroom, so a control that needs server work **relays a command up the reverse channel**:

```js
// ui/graph.js — in buildToolbar()
var btn = document.createElement('button');
btn.textContent = 'My control';
btn.onclick = function () {
    stroom.select([{__stroomMyCommand: 'value'}]);
};
```

```java
// GraphResultWidget.onSelection — decode and dispatch
final String v = selection.getParamValue("__stroomMyCommand");
if (v != null && onMyCommand != null) {
    onMyCommand.run();
    return;
}
```

…then the presenter (`GraphDbExplorePresenter`) supplies the callback.

Existing commands to copy from: `__stroomExpand` and `__stroomFocus` (both identity-based, going through
`GraphExpandService`), `__stroomTimeTravel` (pure UI — toggles the parent's temporal panel) and
`__stroomDiscover` (toggles the discovery panel).

For a control that is *purely* visual — layout, colour, filtering — keep it inside `graph.js` entirely and
do not involve the parent at all.

Note `graph.js` is a plain JS resource, so changes take effect on reload without a GWT compile. The Java
side does need one.

### 6. Add a GraphDb setting

**Files:** `GraphDbDoc` (field + builder), `GraphDbDocSerialiser` if the shape is unusual,
`GraphDbSettingsPresenter` and its view, and [11-operations.md](11-operations.md).

> **Then make something read it.** `temporalPrecision` used to be editable, persisted, documented in the UI —
> and consumed by no implementation code. A setting that silently does nothing is worse than a missing one,
> because users reasonably infer behaviour from its presence. Wire the consumer in the same change, or do
> not add the control.
>
> If the setting affects a key layout, it must also become part of the schema stamp
> (`GraphStores.keySchema`). That is what makes it immutable after provisioning, for free: a store written under
> one value refuses to open under another instead of reinterpreting every key. See
> [10-limits.md](10-limits.md) for the settings that work this way.

### 7. Add an internal store

**Files:** `GraphStores.open()`, plus a new `*Db` class following `GraphNodeDb`'s shape.

Watch three things: the `MAX_DBS = 32` budget for the environment; the fixed UID widths, since prefix scans
depend on them; and the **dual-write obligation** — anything mirroring adjacency must be written and
tombstoned in step with its counterpart, or backwards traversal silently disagrees with forwards.

New stores should also participate in `deleteOldData()`. The property index does not, which is precisely
why it grows without bound ([12-future-work.md](12-future-work.md)).

> **If you are ever asked to make stores mergeable** — for cluster ingest, or to combine two graphs — read
> [the architectural note in 12-future-work.md](12-future-work.md#architectural-note-how-far-up-the-plan-b-stack-should-graph-db-sit)
> first. The non-obvious hazard is that UIDs come from a *local* sequential counter, so two independently
> built stores assign the same UID to different node ids, and every key embeds UIDs. Plan B's
> `SessionDb.merge` and `MetricDb.merge` already solve this: decode each key to its logical form through the
> source store's serde, re-encode through the target's, and byte-copy only where no lookup is involved.
> Do not write a byte-level merge.

## Testing

| Test | Covers |
|---|---|
| `TestGraphFilter` | Ingest parsing, per-record error handling |
| `TestGraphMutationSchema` | Sample documents against the XSD |
| `TestGraphTraversalEngine` | Traversal, temporal semantics, guardrails |
| `TestGraphSearchProvider` | End-to-end query execution, including `UNION` |
| `TestCypherQueryParser` | Grammar and AST |
| `TestGraphDbSettingsPresenter` | Settings UI |

### The test-seam constructor

Every guardrail in `GraphTraversalEngine` is a `private static final` — the million-row ceiling, the
30-second budget, the 200,000 path states. Testing them by generating a million rows would be absurd, so
the engine has a **package-private constructor** taking overrides. Use it rather than lowering a production
constant.

If you add a guardrail, add it to that constructor too, and note the value in
[10-limits.md](10-limits.md) — which is the canonical record and carries each constant's name for
grep-verification.

## House rules

`docs/coding-standards.md` governs: Javadoc on public types, runtime precondition and null checks, JSpecify
annotations on server code only. Graph DB follows them closely — the existing Javadoc explains *why* a
design is as it is, which is where most of the reasoning behind the frozen key layouts is recorded.

**The documentation is tested.** `TestDocumentationQueries` compiles every fenced `cypher` block in
`docs/graphdb/`, and `TestDocumentationReferences` checks every source constant, code-map class and `graphdb.*`
setting it cites — so renaming a constant this guide names, or a class in the tables above, fails the build. Separate independent statements
with a blank line, and annotate a deliberately-invalid one `-- rejected`, or `-- rejected at runtime` for the
shapes the grammar accepts and the engine refuses. If this documentation moves repositories that test fails
deliberately rather than skipping — see [14-testing.md](14-testing.md#documented-queries).

## Design history

Graph DB was built from a set of design proposals, implementation plans and review reports that lived in
`docs/`. **Those have been retired** — their content is either absorbed into this documentation set or
superseded by the code itself, and keeping them risked two sources of truth diverging.

They remain in the repository's git history. To find the reasoning behind a specific decision:

```bash
git log --diff-filter=D --name-only -- 'docs/*.md' 'docs/*.html'
git show <commit>:docs/temporal-cypher-graph-implementation-plan.md
```

The documents most worth retrieving, by question:

| Question | Retired document |
|---|---|
| Why LMDB / Plan B, and why this key layout? | `temporal-cypher-graph.md` — the original architecture proposal |
| Why does `DIFF` classify the way it does? | `temporal-cypher-diff-operator.md` |
| Why is the language subset drawn where it is? | `cypher-subset-extension-implementation-plan.md` |
| Why does `RETURN GRAPH` have that fixed column set? | `graphdb-cytoscape-visualisation.html` |
| Why only three settings on the Settings tab? | `graphdb-settings-surface.html` |
| What did the pre-production review find? | `query-graphdb-review-report.md`, `query-graphdb-review-findings.md` |
| How was POLE reproduced, and what failed? | `pole-on-stroom-graphdb.md` — the live-verified tutorial run |
| What was considered and rejected for the language? | `cypher-language-feature-roadmap.md` — value/cost per feature |
| How did the temporal model compare to other graph DBs? | `temporal-cypher-features.html` |

Two of those reviews' findings are recorded as live caveats rather than history — the node-based cycle
guard ([06-language-reference.md](06-language-reference.md)) and the string-only property storage
([03-ingest.md](03-ingest.md)) — because they still affect what a query returns.

**Javadoc no longer cites any of these.** Comments were made self-contained when the documents were
retired; if you find a reference to a markdown file in source, it is a leftover and should be inlined or
removed rather than repaired.

There is no archive directory: git history is the archive. This documentation set is the whole of the
current Graph DB documentation.

**Still in `docs/`:** the query-optimiser and join-scalability material, which is a separate subsystem with
its own records.


## Next

- [02-architecture.md](02-architecture.md) — the same storage model in user terms
- [12-future-work.md](12-future-work.md) — what is worth building, with difficulty and risk
- [10-limits.md](10-limits.md) — the constants, with their names
