# Portable graph queries: a universal `from "GraphDb"` prefix for Cypher

## Goal

Let a temporal-Cypher query name its own target GraphDb with a leading `from "X"` clause, so the **same query
text** can be run from any text-driven surface — a Query doc, the `/csv/search` endpoint, the MCP tools, and
(via an embedded Query doc) a Dashboard — without depending on `SearchRequestSource.ownerDocRef`.

```
from "Test Graph"
MATCH (d:Device {id: 'd-42'})-[:CONNECTED_TO]->(a:Account) RETURN a.id
```

## Background: how routing works today (verified)

Grammar selection is **type-driven, not text-driven**, and happens in one place:
`QueryServiceImpl.mapRequest` ([QueryServiceImpl.java:626-641](../stroom-query/stroom-query-impl/src/main/java/stroom/query/impl/QueryServiceImpl.java)).

- It reads `SearchRequestSource.ownerDocRef`, asks `AlternativeQueryCompilerResolver.resolve(ownerDocRef, …)`
  ([AlternativeQueryCompilerResolver.java:46](../stroom-query/stroom-query-common/src/main/java/stroom/query/language/AlternativeQueryCompilerResolver.java)),
  and if a compiler `supports()` that DocRef's **type** it routes there; otherwise it uses the StroomQL
  `queryCompiler`.
- `GraphCypherQueryCompiler.supports()` returns true only when `ownerDocRef.getType() == GraphDb`
  ([GraphCypherQueryCompiler.java:36](../stroom-graphdb/stroom-graphdb-impl/src/main/java/stroom/graphdb/impl/GraphCypherQueryCompiler.java)).
- Cypher has no `from` today, so `CypherCompiler.create` takes the target graph from the pre-set
  `in.Query.dataSource` (= `ownerDocRef`) and attaches the raw Cypher as a `GraphSpec`
  ([CypherCompiler.java:73-92](../stroom-graphdb/stroom-graphdb-impl/src/main/java/stroom/graphdb/impl/CypherCompiler.java)).

Consequences established by the investigation:

- **The `ownerDocRef` gate is the blocker.** From a generic surface (Query doc, `/csv/search`, MCP) `ownerDocRef`
  is null or is not a GraphDb, so Cypher never routes. A `from "X"` prefix is only useful once dispatch is made
  **text-driven**.
- **`mapRequest` is the single lever.** `validateQuery`, `search`, `csvSearch`, `downloadSearchResults`,
  `getColumnValues`, and `getBestNode` all funnel through it, so one change reaches them all.
- **The classic Dashboard path is structurally expression-only.** `DashboardSearchRequest` / `Search` /
  `QueryComponentSettings` carry an `ExpressionOperator` + datasource DocRef and **no query-text field**;
  `SearchRequestMapper.mapQuery` never invokes a text compiler
  ([SearchRequestMapper.java:86-131](../stroom-dashboard/stroom-dashboard-impl/src/main/java/stroom/dashboard/impl/SearchRequestMapper.java)).
  So the classic Query component cannot carry Cypher without invasive new fields — **out of scope**.
- **Dashboards already run Cypher via the Embedded Query component**, which references a `QueryDoc` and runs it
  through `QueryModel` → `QueryServiceImpl.mapRequest` (the text path). So "run graph queries from dashboards" is
  delivered by this plan automatically, through an embedded Query doc.
- **Datasource resolution is already type-neutral and reusable**:
  `DataSourceResolver.resolveDataSourceRef(name)` tries UUID then name, returns a fully-typed DocRef, and throws
  a clear error on not-found and on ambiguous (multiple docs, any type, same name)
  ([DataSourceResolver.java:42-61](../stroom-query/stroom-query-common/src/main/java/stroom/query/language/DataSourceResolver.java)).

## Core design

A three-step, non-circular flow, decided once in `mapRequest`:

1. **Extract** the leading `from "X"` name from the query text, grammar-agnostically (before choosing a grammar).
2. **Resolve** the name to a typed DocRef via the existing `DataSourceResolver` (UUID-then-name, type-neutral).
3. **Dispatch** by the resolved DocRef's **type**: an `AlternativeQueryCompiler` that `supports()` it (GraphDb →
   Cypher) wins; otherwise StroomQL. `ownerDocRef` remains a fallback so every current flow keeps working.

The Cypher grammar gains an **optional** `from` clause so the Cypher parser accepts and consumes the same prefix;
`CypherCompiler` prefers the datasource `mapRequest` already resolved (`in.Query.dataSource`) and only falls back
to resolving its own `from` clause when used standalone.

## Phased plan

### Phase 1 — grammar-agnostic leading-`from` extractor (foundation)
- **New** `LeadingDataSourceExtractor` (stroom-query-common) that returns the leading `from` source name from any
  query string without a full parse. **Reuse the legacy `Tokeniser` + `StructureBuilder`** to read the first
  `FROM` keyword-group's first child (the exact pattern in `SearchRequestFactory.addDataSource`,
  [SearchRequestFactory.java:249-278](../stroom-query/stroom-query-common/src/main/java/stroom/query/language/SearchRequestFactory.java))
  — this guarantees consistency with the two existing `from` readers (legacy `Tokeniser` and ANTLR `AstToken`)
  and reuses `QuotedStringUtil` unescaping and HIDDEN-channel comment/whitespace skipping.
- Only the **left/first** source is needed (enough to choose a grammar); ignore any `join`/`as` for this step.
- Return `Optional<String>` (empty when there is no leading `from`).
- Tests: quoted/bareword/single-quoted names; leading `//` and `/* */` comments; no-`from`; a Cypher body after
  `from "X"`; a `from A join B` (returns `A`).

### Phase 2 — text-driven dispatch in `mapRequest`
- In `QueryServiceImpl.mapRequest` ([:626-641](../stroom-query/stroom-query-impl/src/main/java/stroom/query/impl/QueryServiceImpl.java)):
  1. `dataSourceRef` = `ownerDocRef` if present (unchanged behaviour), **else** resolve the leading `from` name
     (Phase 1) via `DataSourceResolver` inside `securityContext.useAsReadResult`.
  2. `AlternativeQueryCompilerResolver.resolve(dataSourceRef, …)` (unchanged) picks Cypher vs StroomQL by type.
  3. When routed to an alternative compiler, set `Query.dataSource(dataSourceRef)` on the sample request (as
     today) so the compiler inherits the resolved graph.
- StroomQL path is untouched when there's no owner and a normal `from` (it resolves its own datasource as before)
  — preserving byte-for-byte parity.
- Tests: `mapRequest` routes `from "GraphDb" MATCH…` to Cypher with no `ownerDocRef`; routes `from "Index" …`
  StroomQL; `ownerDocRef` still wins when set.

### Phase 3 — optional `from` clause in the Cypher grammar
- `Cypher.g4` ([grammar dir](../stroom-query/stroom-query-grammar/src/main/antlr/stroom/query/grammar/antlr/Cypher.g4)):
  add a `FROM` lexer token (declared before `NAME`), a `fromClause : FROM STRING ;`, and make
  `query : fromClause? readingClause+ returnClause EOF ;`. Reuse the existing `STRING` token for `"X"`.
  ANTLR regenerates automatically via `generateGrammarSource` on build.
- `AstCypherQuery`: add a nullable `String dataSourceName`
  ([AstCypherQuery.java:36-47](../stroom-query/stroom-query-grammar/src/main/java/stroom/query/grammar/ast/cypher/AstCypherQuery.java)).
- `AstCypherBuilder.build` ([:90-97](../stroom-query/stroom-query-grammar/src/main/java/stroom/query/grammar/ast/cypher/AstCypherBuilder.java)):
  read `ctx.fromClause()`, `unescapeString` its `STRING`, pass through.
- `CypherToLogicalPlan` needs **no change** — the `from` clause is purely a datasource selector.
- Tests: Cypher with and without `from` parses; `from` name reaches the AST unescaped.

### Phase 4 — `CypherCompiler` datasource resolution + wiring
- `CypherCompiler.create` ([:73-92](../stroom-graphdb/stroom-graphdb-impl/src/main/java/stroom/graphdb/impl/CypherCompiler.java)):
  split the parse from the compile; datasource precedence = **`in.Query.dataSource` if set** (the `mapRequest`
  path), else resolve `ast.dataSourceName()` to a DocRef; error clearly if neither yields a GraphDb.
- Inject a name→DocRef resolver: reuse `DocFinder` (already used by `GraphSearchProvider`,
  [GraphSearchProvider.java:107-115](../stroom-graphdb/stroom-graphdb-impl/src/main/java/stroom/graphdb/impl/GraphSearchProvider.java))
  via `findByName(GraphDbDoc.TYPE, name)`. Give `GraphCypherQueryCompiler` an `@Inject` constructor taking
  `DocFinder` and pass it into `CypherCompiler`; `GraphDbModule` multibinder entry
  ([GraphDbModule.java:77](../stroom-graphdb/stroom-graphdb-impl/src/main/java/stroom/graphdb/impl/GraphDbModule.java))
  is unchanged (DocFinder is already bound).
- No change to `GraphSearchProvider` / `GraphDbDocCache` — they still select the store by the DocRef's name.
- Tests: Cypher-from-standalone resolves the graph; `mapRequest`-pre-resolved path ignores the redundant
  re-resolution; unknown/ambiguous name → clear error.

### Phase 5 — validation, help & fields (follow-up, non-blocking)
- `validateQuery` / `getReferencedDataSource` will resolve `from "GraphDb"` once the extractor is grammar-agnostic
  (Phase 1) and dispatch is text-driven (Phase 2).
- `getQueryHelpContext` ([QueryServiceImpl.java:873-968](../stroom-query/stroom-query-impl/src/main/java/stroom/query/impl/QueryServiceImpl.java))
  is StroomQL-token-hardwired; grammar-aware help (offering Cypher structure after `from "GraphDb"`) is a
  **separate follow-up**, not required for execution.
- **Field help for a GraphDb returns empty today** — `GraphSearchProvider.getFieldInfo/getFieldCount` return
  empty/zero ([GraphSearchProvider.java:132-148](../stroom-graphdb/stroom-graphdb-impl/src/main/java/stroom/graphdb/impl/GraphSearchProvider.java)).
  A GraphDb field-introspection feature is out of scope here; note it so `from "GraphDb"` help is expected to be
  empty, not broken.

### Phase 6 — Dashboards (via embedded Query doc) + docs
- **No classic-dashboard change.** Document that a Dashboard runs graph queries by embedding a **Query doc** whose
  text is `from "GraphDb" …` — this rides the Phase 2 text path automatically.
- Update `docs/graphdb-testing-protocol.md` and the MCP tool descriptions to show the portable `from "…"` form.
- **Confirm the MCP server** (source is **outside this repo** — the `stroom-mcp-server` container): its
  `searchStroom`/`csvQuery`/`validateQuery` tools are text-only and should "just work" with a `from`-prefixed
  Cypher, but verify in that codebase.

## Risks & decisions

- **Name ambiguity.** A shared `from "X"` inherits `DataSourceResolver`'s "Multiple data sources found" error when
  a GraphDb and (say) an Index share a name — there is no type-disambiguation syntax today. Decision: accept the
  clear error for v1; consider an optional type hint later.
- **Third `from`-reader consistency.** The new extractor must agree with the legacy `Tokeniser` and ANTLR
  `AstToken` readers. Mitigation: build it *on* the legacy `Tokeniser`, not a fresh regex.
- **Back-compat.** `from` is optional for Cypher and `ownerDocRef` stays the fallback, so the GraphDb Data tab and
  every existing StroomQL flow are unchanged. StroomQL grammar is not modified.
- **Security.** Keep all resolution inside `securityContext.useAsReadResult` so datasource permissions are
  enforced exactly as today.
- **Blast radius.** Additive only: a new extractor, an optional Cypher clause, a dispatch fallback, and one Guice
  injection. Existing StroomQL parity suites (`TestQueryCompilerParity`, generative parity) guard regressions.

## What already works (no change needed)
- Running Cypher from a **GraphDb doc's Data tab** (ownerDocRef is the GraphDb).
- Executing a `GraphSpec` search end-to-end: `GraphSearchProvider` selects the store by DocRef name; INNER/LEFT
  temporal queries, `AS OF`/`BETWEEN`/`AROUND`, `ORDER BY`/`DISTINCT`, and the documented rejections all verified
  live during the graph-DB testing-protocol run.
