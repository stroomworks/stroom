# Code structure and extending Graph DB

**Status:** Evaluation / proof of concept — not production ready. See the
[production-readiness blockers](README.md#production-readiness--known-blockers).
**Audience:** developers working on Graph DB itself.
**Scope:** where the code lives, how a query flows through it, and recipes for the extensions people
actually make. Also the index into the engineering records.
**Companion documents:** [02-architecture.md](02-architecture.md) (the storage and temporal model in
user terms), [12-future-work.md](12-future-work.md) (what is worth building).

*Facts verified on 2026-07-28 against branch `sw-query-optimiser`. Class names and call order are more
durable than line numbers; paths are given, line numbers only where a constant is the point.*

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
| `GraphStoreManager` | Per-UUID registry of open stores; resolves `<app path>/graphdb/<uuid>` |

Key widths are fixed (`NODE_UID_WIDTH = 6`, `TYPE_UID_WIDTH = 4`) because prefix range scans depend on
it — a variable-width key would break every traversal. Physical layouts are in each class's Javadoc.

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

If the aggregate returns anything other than a scalar `Val`, you need the list type first
([12-future-work.md](12-future-work.md)) — this is exactly why `collect()` currently returns a joined
string.

### 3. Add a clause or pattern form

The full walk: `Cypher.g4` → `AstCypherBuilder` → a new AST record → `CypherToLogicalPlan` → logical IR
(reuse a node if you can; adding one touches the relational core too) → `GraphTraversalEngine`.

Two rules worth respecting:

- **Delete the rejection message in the same change.** A feature that works but still has its
  `not supported in this version:` guard upstream is worse than either state.
- **If the grammar cannot express it, that is the rejection.** Prefer leaving something out of the grammar
  over accepting and then refusing it — the parse error is clearer and cheaper.

### 4. Extend `graph-mutation`

**Files:** `graph_mutation_v1_0.xsd` (test resources), `GraphFilter`, the store write path, and
[03-ingest.md](03-ingest.md).

`GraphFilter` dispatches on **lower-cased element local names** and does no schema validation, so a new
element is a new case in `startElement`/`endElement`. Bump the schema `version` enumeration for anything
that is not purely additive, and keep the two in step — the XSD is documentation for XSLT authors, so a
drift between it and the filter is a silent trap for them.

Typed property values, the most likely next change, need the codec (`GraphPropsCodec`), the property index
tiering, and the query-side value handling — not just the schema.

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

> **Then make something read it.** `temporalPrecision` is editable, persisted, documented in the UI — and
> consumed by no implementation code. A setting that silently does nothing is worse than a missing one,
> because users reasonably infer behaviour from its presence. Wire the consumer in the same change, or do
> not add the control.

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
