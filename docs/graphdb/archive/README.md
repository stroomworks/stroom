# Archived Graph DB documents

**Status:** Superseded. Kept for history.
**Audience:** anyone tracing how a decision was reached.

These documents were written during Graph DB's development. Their content has been absorbed into the user
documentation set in the parent directory, and **that set is now canonical** — where these files and the
current documentation disagree, the current documentation is correct.

They are kept because they record *why* things were built the way they were, and because several are cited
from other engineering documents.

| Document | Absorbed into | What it was |
|---|---|---|
| `graphdb-index.html` | [`../README.md`](../README.md) | The hand-written index of the engineering document set |
| `graphdb-testing-protocol.md` | [`../03-ingest.md`](../03-ingest.md), [`../06-language-reference.md`](../06-language-reference.md) | The original user testing protocol. Framed the feature as "PoC-stage" and predates most of the language surface; superseded by `../../query-graphdb-test-protocol.md` |
| `temporal-cypher-features.html` | [`../02-architecture.md`](../02-architecture.md), [`../06-language-reference.md`](../06-language-reference.md) | The temporal model, `AS OF`/`AROUND`/`BETWEEN`, and comparison with other graph databases |
| `graphdb-analytic-functions-proposal.md` | [`../07-functions.md`](../07-functions.md), [`../12-future-work.md`](../12-future-work.md) | The proposal for aggregation, spatial and path-finding. Aggregation shipped; the rest did not |
| `cypher-language-feature-roadmap.md` | [`../12-future-work.md`](../12-future-work.md) | Survey of unsupported Cypher features with value/cost estimates — the main source for the roadmap |
| `pole-on-stroom-graphdb.md` | [`../08-analysis-examples.md`](../08-analysis-examples.md) | Reproducing the Neo4j POLE tutorial on Graph DB, re-run three times with an honest record of what worked |

## Not archived

Two documents that this set also draws on were **left in place** in `docs/`, because they are cited from
Javadoc in production source files and moving them would mean editing that source for a documentation
change:

- `graphdb-cytoscape-visualisation.html` — cited from `GraphElementExecutor`, `ElementDetail`,
  `GraphTraversalEngine`, `Cypher.g4` and others
- `graphdb-settings-surface.html` — cited from `GraphDbSettingsPresenter` and `GraphNodeTypeMappingsWidget`

The implementation plans, review reports and remaining design documents were also left in place: they are
development records rather than user-facing content, and
[`../13-developer-guide.md`](../13-developer-guide.md) indexes them.
