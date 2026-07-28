# Running queries: the Explore and Data tabs

**Status:** Evaluation / proof of concept — not production ready. See the
[production-readiness blockers](README.md#production-readiness--known-blockers).
**Audience:** analysts.
**Scope:** where to type a query and what you get back. Canonical for the graph view's controls.
**Companion documents:** [06-language-reference.md](06-language-reference.md) (what to type),
[08-analysis-examples.md](08-analysis-examples.md) (worked examples),
[10-limits.md](10-limits.md) (display caps).

*Facts verified on 2026-07-28 against branch `sw-query-optimiser`.*

---

A `GraphDb` document has five tabs: **Settings**, **Explore**, **Data**, **Documentation** and
**Permissions**. Two of them run queries.

| Tab | Result | Use it for |
|---|---|---|
| **Explore** | An interactive node-link diagram | Following relationships, finding structure, time travel |
| **Data** | A table of rows | Counting, ranking, exporting, anything with many rows |

Both run the same language against the same store. The difference is what comes back and how you read it.

## The Data tab — tabular results

A query editor over a results table, like any other Stroom query surface.

Use it when the answer is a list or a number rather than a shape: rankings, counts, aggregates, or anything
you intend to export. A table also handles far more rows than a diagram can usefully show.

```cypher
MATCH (c:Crime) RETURN c.type AS crime_type, count(c) AS total ORDER BY total DESC
```

**Create Dashboard is disabled here.** Building a dashboard parses the query text as StroomQL, and this tab's
query is Cypher, so the resulting dashboard could not run it. The button is left visible but greyed to
signal the intent rather than the capability ([12-future-work.md](12-future-work.md)).

## The Explore tab — the graph view

A query editor whose result is drawn as a graph. There is no table here — that is the Data tab's job.

To get a graph rather than rows, the query must end in **`RETURN GRAPH`**:

```cypher
MATCH (p:Person {surname: 'Powell'})-[:PARTY_TO]->(c:Crime) RETURN GRAPH
```

The default query when you open the tab is `MATCH (n) RETURN GRAPH LIMIT 100` — a whole-graph preview, so
you can see immediately whether a graph holds data and what shape it takes.

> Without an explicit `LIMIT`, that unanchored preview form caps at **100 nodes** silently. Every other
> limit reports itself; this one does not ([10-limits.md](10-limits.md)).

### Toolbar

| Control | What it does |
|---|---|
| **Layout** | Force, Hierarchy, Concentric, Tree, or basic force |
| **Fit** | Frame the whole graph in the view |
| **Size by degree** | Scale each node by how many edges it has, so hubs stand out |
| **Edge labels** | Show or hide relationship-type labels |
| **Declutter** | Auto-hide labels when zoomed out |
| **Time travel** | Reveal the temporal controls (below) |
| **Discover** | Reveal the schema panel (below) |
| **Node caption** | Choose which property, label or id captions each node |
| **Edge width by property** | Map a numeric edge property to line thickness |
| **Export** | PNG (transparent, 2×), the element table as CSV, or the elements as JSON |
| **Search** | Highlight nodes matching an id, label or any property value, and zoom to them |

### Reading the diagram

Each node label gets a colour and shape on first sight, and keeps them for the session — so a `Person` node
looks the same across re-runs and expansions, and you can learn the palette of your own dataset.

The **legend doubles as a filter**: click a node label or relationship type to hide or show that kind
wholesale. This is the fastest way to simplify a busy diagram without editing the query.

For `DIFF` results, the change classification overrides the usual styling so added, removed, modified and
unchanged elements are visually distinct.

### Working with nodes

**Right-click** a node:

| Action | Effect |
|---|---|
| **Expand neighbours** | Fetch its neighbours and **merge** them in, keeping the current view |
| **Focus** | **Replace** the view with this node and its neighbours |
| **Highlight neighbourhood** | Fade everything else |
| **Highlight component** | Fade everything outside its connected component |
| **Pin / Unpin** | Fix the node's position against re-layouts |
| **Hide** | Remove it from the view |
| **Reset view** | Clear highlighting and re-fit |

Expanding is additive and preserves the positions of nodes already on screen, so the diagram grows around
what you were looking at rather than rearranging itself. Expansion is capped at **50 neighbours** per node,
and re-runs at the same temporal instant as the query that produced the current graph — so expanding inside
an `AS OF` view stays in that view.

Focus is identity-based, so it always resolves and can never blank the diagram.

**Click** a node to open the inspector, which shows the full property table plus, for nodes, **metrics** —
degree, and pageRank and betweenness computed over the loaded graph. It also offers **Shortest path**: arm
one node, then pick another.

> **Shortest path runs in the browser, over the nodes currently drawn.** A genuinely shorter path through
> nodes you have not loaded will not be found, and the answer changes as you expand. There is no
> `shortestPath()` in the query language. Treat it as a visual aid, not an analytic result.

Clicking empty canvas closes the inspector and clears any highlight.

### Discover

For a graph whose contents you do not know yet, the **Discover** panel turns the schema into clickable
starter queries:

- **Start here** — show the whole graph
- **Node labels** — a chip per label, each running a preview of that label
- **Relationship types** — the edge types present, to use in your own patterns
- **Property keys** — what you can put in a `RETURN` or `WHERE`
- **Example nodes** — click one to focus on it

The label and whole-graph previews are generated with `LIMIT 100`, and the example nodes are a sample of 20
— enough to orient yourself, not a census.

### Time travel

The **Time travel** toggle reveals the temporal controls, which drive the clauses described in
[06-language-reference.md](06-language-reference.md) without your having to write them:

| Control | Effect |
|---|---|
| **From** / **To** | The window, as ISO-8601 UTC. Invalid input is reported inline |
| **◀ / ▶** | Step one slider position |
| **Play / Pause** | Advance automatically through the window |
| **Slider** | 20 positions across the window; each re-runs the query `AS OF` that instant |
| **Compare** | Run a `DIFF` between the window's start and end |

The window defaults to the last seven days. Each movement re-runs the query and re-renders, so the graph
you see is genuinely the graph as it was at that instant, not a filtered version of the current one.
Switching Time travel off restores the live view.

## Running queries elsewhere

Cypher is not confined to the GraphDb document. Any text-driven query surface works, provided the query
names its graph with a leading `from` clause:

```cypher
from "POLE Graph"
MATCH (p:Person {surname: 'Powell'})-[:PARTY_TO]->(c:Crime) RETURN c.type
```

| Surface | Notes |
|---|---|
| **GraphDb Explore / Data tabs** | No `from` needed — the document is implied |
| **Query document** | `from` required |
| **Dashboard** | Via an embedded Query document |
| **`/csv/search` API** | `from` required |
| **MCP tools** | `from` required |

For REST calls use a **Bearer API key**, not a session cookie — cookie-authenticated `POST`s are rejected
by CSRF protection.

## Choosing a view

| You want to… | Use |
|---|---|
| Count, rank or aggregate | Data tab |
| Export results | Data tab |
| See how things connect | Explore tab |
| Follow a trail outward from one entity | Explore tab, with Expand neighbours |
| Find out what is in an unfamiliar graph | Explore tab, with Discover |
| See what changed between two dates | Explore tab, with Compare |
| Inspect more than a couple of thousand results | Data tab — the diagram caps at 2,000 elements |

## Next

- [06-language-reference.md](06-language-reference.md) — the query language
- [08-analysis-examples.md](08-analysis-examples.md) — worked examples in both views
- [10-limits.md](10-limits.md) — the display and expansion caps
