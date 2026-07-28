# Epoch 0 development plan — removing the blockers to production use

**Status:** **Plan, not description.** Nothing in this document exists yet. It describes work required to
make Graph DB production-capable; every other file in this set describes only what is built. No other
document may reference this one in the present tense.
**Audience:** developers and stakeholders planning the work.
**Scope:** the programme to clear the blockers in
[README.md](README.md#production-readiness--known-blockers) — correctness, data safety and operability.
Language-expressiveness gaps are deliberately out of scope.
**Companion documents:** [12-future-work.md](12-future-work.md) (the full roadmap this plan draws from),
[02-architecture.md](02-architecture.md) (why the cluster problem exists),
[14-testing.md](14-testing.md) (the acceptance protocol).

*Research verified 2026-07-28 against branch `sw-query-optimiser`.*

---

## Why this exists

Graph DB is documented as **not production ready**. The most serious problem is that it is **only correct
on one node**: `GraphFilter` writes directly into a live local LMDB environment on whichever node processed
the stream, so a cluster silently accumulates fragments and every query returns an incomplete answer with
nothing reporting it.

Fanning queries out cannot fix this. A traversal can cross a fragment boundary, so merging independent
local traversals does not reconstruct paths — the reasoning is in
[02-architecture.md](02-architecture.md#why-fanning-queries-out-would-not-fix-it). Correctness therefore
requires two things together: every mutation reaching **one authoritative store**, and every query running
against a **complete copy** of it.

## Governing principles

### 1. No backward compatibility is required

The current system is a proof of concept and **existing graph data may be discarded.** This holds for every
phase below and removes all migration work from the programme. Wherever a format changes, the policy is:

> Refuse to open a store written by a different version; the operator wipes and rebuilds
> (`GraphStores.rebuild()`, or reprocesses the source streams).

Anywhere this plan would otherwise have needed a migration path, it does not. This is a deliberate,
time-limited licence — it expires the moment real data exists.

### 2. A store version stamp lands first, and enforces principle 1

**There is no version marker on a graph store today.** Nothing records what format the bytes are in, so a
code change that alters a key layout would silently produce wrong answers rather than failing. Plan B has
this (a `SchemaInfo` in an `info_db` dbi, validated on open and on every merge); Graph DB does not, because
none of its DAOs extends `AbstractDb`.

Every later phase that changes a layout bumps that version, so a mismatch fails loudly at open time.

### 3. Correctness before operability

With one dependency-forced exception: `GraphDbConfig` must land inside Phase 1, because the cluster work
needs `nodeList`.

## Implementability — how to resource this

Roughly four fifths of this plan is mechanical work with a close, working template in
`stroom-planb-impl`, and can be executed by a capable implementer (or a mid-tier coding model) working
increment by increment against the tests in [Verification](#verification). Three items cannot, and one is
mechanical-but-wide in a way that reliably produces misses. Being explicit about which is which is the
difference between this plan being executable and being optimistic.

| Item | Difficulty for a straightforward implementer | Why |
|---|---|---|
| 1.1 store version stamp | **Suitable** | Mirrors `AbstractDb`'s read-validate-write closely |
| **1.2 `GraphStores.merge`** | **Not suitable — needs the strongest available model and close review** | See below |
| 1.3 config and paths | **Suitable** | A fixed recipe, including which generated files to regenerate |
| **1.4 `PartMergeProcessor` extraction** | **Needs review** | A behaviour-preserving refactor of *live Plan B code*; the risk is regressing another subsystem, not failing to compile |
| 1.4 fragment writer, 1.5 transport | **Suitable** | Near-mechanical mirrors of `ShardWriters` and `FileTransferClientImpl` |
| 1.6 query routing | **Suitable** | Small; one new class plus a multibinder change |
| Phase 2 strict ingest | **Suitable, but wide** | ~12 call sites and four inverted tests; the risk is missing a site, not writing bad code |
| Phase 2 ship the XSD | **Suitable** | File moves plus a content-pack entry |
| Phase 3 typed values | **Suitable** | Three lines in `GraphFilter` plus a schema bump; the codec already does the hard part |
| **Phase 3 `ValList`** | **Needs review** | Modifies a sealed hierarchy used product-wide. The compiler finds the two exhaustive switches; it does not find the semantic questions (comparator ordering, group-key hashing, the 255-element cap) |
| **Phase 3 temporal serde swap** | **Mechanical but high miss-rate** | Touches three frozen key layouts and *every hard-coded `6`*. A single missed site is silent corruption, not a compile error |
| Phase 4, all items | **Suitable** | Templates exist for each; guardrails-to-config is nearly free |

### Why 1.2 is different

`GraphStores.merge` is the one place where a plausible-looking implementation fails **silently**:

- **LMDB buffer lifetime is undocumented in this codebase.** A `ByteBuffer` handed to a cursor callback is
  valid only inside that callback, and target writes can move pages. The UID translation loop must copy the
  name buffer before using it (`ByteBuffers.copyToDirectBuffer` exists for this). Nothing in the source tree
  warns about it, and getting it wrong corrupts keys rather than throwing.
- **Four translation maps must agree.** The same logical entity has to resolve to the same target UID across
  the node table, both adjacency tables and the property index. An error here produces a graph that queries
  cleanly and answers wrongly.
- **Commit-batch discipline is invisible.** Out-edge and in-edge rows for one logical edge must fall between
  the same pair of `tryCommit()` calls. Nothing enforces it; violating it means a crash can leave a
  half-edge, and traversal then disagrees forwards versus backwards.
- **Idempotency is a property to preserve, not to add.** It exists only because interning is get-or-create
  and `GraphNodeDb.insert` has no timestamp. Any well-intentioned "improvement" that stamps a write time
  destroys replay-safety and therefore the recovery story in 1.5.

Treat 1.2 as the piece to assign to the strongest model available, implement first (it is standalone and
needs no plumbing), and gate on the full test set below before anything is built on it.

### Guardrails that make delegation safe

The plan is already staged so each increment is independently testable — that is the main protection. Add
three working practices:

1. **The verification tests are not optional and not last.** Byte-identical replay, the mirror invariant and
   tier equivalence are precisely the assertions that catch a subtly wrong merge. Write them alongside 1.2,
   not after it.
2. **`TestGraphTraversalEngine` runs unmodified as the regression gate.** If an increment requires editing
   those 60+ call sites, that is a signal the change went wider than intended.
3. **For wide mechanical edits, use exact string replacements and review the diff before compiling.** Do not
   use regex sweeps across many files. This is learned the hard way: during the documentation work that
   preceded this plan, a generic `()`-collapsing regex silently stripped method-call parentheses from 1,071
   lines across 28 files. It compiled fine in the modules that were checked first, and was only caught by
   diffing. Phase 2's ~12 sites and Phase 3's every-hard-coded-`6` sweep are exactly this shape of risk.

---

## Phase 1 — Correctness on a cluster

`stroom-planb-impl` provides a working template for almost all of this. Six increments; the last two
together deliver cluster correctness.

### 1.1 Store version stamp

Do this first — the cluster work needs it, and it is the safety net for every later format change.

- A `graph-info` dbi in `GraphStores.open`, holding a `SchemaInfo` (reuse `stroom.planb.impl.dao.SchemaInfo`)
  under `AbstractDb`'s byte-key scheme. Implement as a package-private `GraphSchemaDb` — it cannot extend
  `AbstractDb`, which opens its own `"db"` dbi and is `PlanBDoc`-typed.
- Pin: UID widths (6 / 4), the time-serde identity, all four key layouts, the property-index tier format and
  DIRECT maximum length, and the props-codec version.
- Validate on writable open (mismatch ⇒ refuse to open) and again inside `merge` (mismatch ⇒ throw).
- **Fix in passing:** `GraphStores` creates a hash-clash counter and never persists it. Persist it here, as
  Plan B does.

### 1.2 `GraphStores.merge(Path source)` — the core

The hardest code in the programme. Build and test it before any plumbing.

Every graph key embeds **fragment-local** UIDs, so unlike Plan B there is **no raw byte-copy fast path** —
every row is rewritten through per-namespace translation maps (source UID → target UID), built once per
fragment and bounded by one stream's distinct ids.

Two decisions that carry the design:

**Rebuild the property index from the merged node rows; never row-copy it.** `GraphPropertyIndex` only ever
encodes into a key and never decodes out of one — and decisively, `HashLookupDb` keys carry hash-clash
sequence suffixes, so a source hash key is **not portable to the target even for an identical value**.
Row-copying that tier is incorrect, not merely slow. Rebuilding also reuses the ingest code path, so tier
boundaries and clash sequencing are handled by construction. Consequence: only **four** of the five
namespaces need translation (node, label, edge-type, property-key); the two property-value namespaces are
re-interned by the target.

**Derive in-edge rows from out-edge rows** rather than merging both tables. `GraphFilter` dual-writes them in
one per-record transaction, so a fragment's two tables are exact mirrors. Emit each pair between
`tryCommit()` calls so the 10,000-change auto-commit boundary can never split an edge. Guard with an
out-count == in-count check, falling back to independent merge on mismatch.

Supporting changes:

- Extract a shared `anchorValueBytes(Val)` helper used by **both** `GraphFilter` and `merge`, so ingest and
  merge can never disagree on index key bytes.
- Add `UidLookupDb.forEachEntry` — pairwise uid/name iteration. It has `forEachUid` and `forEachName` but
  not both together.
- Add package-private raw iterators and `mergeInsert`/`mergePut` seams to the graph DAOs so property blobs
  copy verbatim rather than round-tripping through decode/encode.

**Merge is idempotent by construction** — get-or-create UID interning, no `Instant.now()` in
`GraphNodeDb.insert`, verbatim value copies, same-key index puts. **Preserve this.** It is what makes the
partial-send hazard survivable, and it is a property Plan B's aggregating stores cannot achieve.

### 1.3 Configuration and paths

- `GraphDbConfig` (`path`, `nodeList`), following `PlanBConfig`. Omit snapshot timings until snapshots exist
  — dead config is worse than a later additive property.
- `GraphPaths`, mirroring `StatePaths`' seven directories.
- Registration is a fixed recipe: five insertions in `AppConfig`, a dependency in two `build.gradle` files,
  then **regenerate** `ConfigProvidersModule` and `expected.yaml` via their `main()` generators (both are
  generated and guarded by tests), and add the new parameter to `TestConfigMapper`'s `TestConfig` constructor.
- Repoint `GraphStoreManagerImpl.directoryFor` at `GraphPaths.getShardDir()`. Existing development stores
  become orphaned — acceptable under principle 1.

### 1.4 Fragment writer and local merge pipeline

- Extract a `PartMergeProcessor` from Plan B's `MergeProcessor`, **in the same package** (`DirQueue` and
  `Dir` have package-private constructors), parameterised by paths plus a target resolver. Plan B's
  `MergeProcessor` becomes a behaviour-preserving façade. `SequentialFileStore`, `DirQueue`, the unzip loop
  and the latches are reused verbatim.
- **Graph DB gets its own staging roots, not Plan B's.** This is not tidiness. Plan B's merge loop deletes a
  part directory on `DocumentNotFoundException`, so graph fragments in Plan B's queues would be **silently
  deleted**, because graph UUIDs are not `PlanBDoc`s. Separate roots make that failure structurally
  impossible rather than dependent on a resolver getting it right.
- `GraphShardWriters` / `GraphShardWriter`, mirroring `ShardWriters`: one LMDB environment per
  (stream × graph doc) under `writer/`, then on close → zip → hash → descriptor → send → delete
  unconditionally.
- Rewire `GraphFilter.startProcessing` / `endProcessing` to write into the per-stream fragment. **Keep the
  per-record commit/abort discipline unchanged** — it now protects the fragment.
- `GraphMergeProcessor` and `GraphPartDestination`, plus a `Graph DB Merge Processor` job on `EVERY_MINUTE`.
- **Diverge from Plan B in one respect:** on merge failure, log at ERROR *with a metric* and retain the
  directory. Plan B only logs, which is a known weakness.

### 1.5 Remote transport

`GraphFileTransferClient` and `GraphFileTransferResource`, adapted from Plan B's equivalents against a
distinct `/graphFileTransfer/v1/sendPart` endpoint. Empty `nodeList` ⇒ local only; otherwise the fragment
goes to **all** configured nodes (full replication) and every one must be enabled. Do not modify
`FileTransferClientImpl` — this keeps Plan B blast radius at zero. Plumb `synchroniseMerge` through the
headers but leave it `false`.

**Write-side cluster correctness is achieved here.**

### 1.6 Query routing

`QueryNodeResolver` is a single Guice binding today, so convert it to a multibinder — or add a small
composite in `stroom-query-impl` to keep the two injection sites single-valued — then add
`GraphQueryNodeResolverImpl` returning `nodeList.getFirst()` for `GraphDbDoc`.

**Read-side cluster correctness is achieved here. Phase 1 is complete.**

### Explicitly not required for correctness

Deferred, and not blockers: snapshots and snapshot-node reads (and with them the `GraphShard` abstraction),
`synchroniseMerge`, load-balanced routing, and the identity-map raw-copy merge optimisation.

---

## Phase 2 — Data safety

**Strict ingest mode.** A `boolean strict` pipeline property on `GraphFilter` — pipeline properties cannot be
enums, so a tri-state severity is not available. The fail mechanism already exists in the same file
(`log(FATAL_ERROR)` followed by `throw LoggedException.create(...)`). Apply it in three places, not one:

- `perRecord`'s catch block;
- the **~9 validation sites that return without throwing** and so bypass that catch entirely;
- the **two `default` branches** that currently drop unrecognised elements with no message at all.

There is no strict/lenient precedent in Stroom to copy — every existing analogue toggles whether to *warn*,
never whether to *fail*. Four `TestGraphFilter` tests encode the lenient contract and must be inverted.

**Ship the XSD** as a real `XMLSchema` document so a `SchemaFilter` can validate in-pipeline. Move it out of
`src/test/resources`, add a `graphdb` content pack to `ContentPacks`, and note that `SchemaFilter`
additionally requires the instance document to carry `xsi:schemaLocation` — the current sample has only
`xmlns`, so the worked XSLT in [`examples/`](examples/event-logging-to-graph.xslt) needs updating too.

**The rebuild trap.** `rebuild()` depends on source streams still existing. Under principle 1 the honest
position is to document it and rely on the new version stamp to force a deliberate rebuild rather than
silent corruption.

## Phase 3 — Correctness surprises

**Typed property values — far cheaper than [12-future-work.md](12-future-work.md) currently states.**
`GraphPropsCodec` is **already typed**, with a passing round-trip test for string, long, double and boolean.
The query side is already type-aware: the row extractors short-circuit on `LONG`/`DATE`, sorting uses
`Val.compareTo`, `min`/`max` preserve type, and the JSON renderer already has unquoted-number branches that
are currently dead code. The work is: type the three lines in `GraphFilter` that force `ValString`, add a
`type` attribute to the vocabulary, and bump the schema version.

> **Keep the property index keyed on lexical text.** Not for compatibility — for correctness. Ingest indexes
> raw XML text and the anchor seek uses the query literal's raw text, so the two agree today. Typed index
> keys need a canonical encoder shared by both sides, or `WHERE n.count = 42` silently stops matching. The
> payoff would be range anchors, which is a language feature and out of scope.

**A real list type for `collect()`.** Add a member to Stroom's sealed `Val` hierarchy: the `permits` clause
and `@JsonSubTypes`, a `Type` constant (**id 8 is free**), the **two** exhaustive `ValSerdeUtil` switches (the
compiler finds them — everything else has a `default`), a `ValSerialiser` entry, and a `ValComparators`
entry. `ValXml` is the shape to copy. Renderers need no change at all; they stringify.

> Note the inherited limit: `ValSerialiser.writeArray` caps at **255 elements**, which becomes `collect()`'s
> real ceiling and must be documented as such.

This touches shared query-language code used by Plan B, dashboards and StroomQL, so it warrants its own
change rather than riding along with graph work.

**Temporal Precision — full serde swap.** Select a `TimeSerde` by precision, exactly as Plan B's temporal
stores do. The serdes have different widths (2–8 bytes), so this changes all three frozen key layouts and
every hard-coded `6`. Under principle 1 that needs no migration, but it **must** bump the schema version so
a store written at one precision refuses to open at another. Two consequences to handle:

- The setting becomes **immutable after provisioning** — enforce it, as Plan B does via schema validation.
- **Drop `NANOSECOND`.** `MillisecondTimeSerde` silently discards nanoseconds and the vocabulary permits only
  three fractional digits, so it is unreachable in any design. The control currently offers one impossible
  value and four no-ops.

## Phase 4 — Operability

**Traversal guardrails to config** — nearly free. `GraphTraversalEngine` already threads four of its five
constants through package-private seam constructors into instance fields, and its own Javadoc anticipates the
change. Promote them to `GraphDbConfig`, make the seam constructor the production path, and add a field for
`MAX_VAR_LENGTH_HOPS`, the only one still read statically. Two call sites; the 60+ existing test call sites
keep working provided the seam signatures are preserved.

**Tunable store size.** `GraphStores` passes `null` where `PlanBEnv` takes a `Long` map size — the only
production call site in the repository that does. Thread `GraphDbConfig.maxStoreSize` through
`open`/`provision`, and mark the getter as requiring a restart: LMDB fixes the map size at environment
creation.

**Condense and compact.** Port the copy-with-compact plus atomic swap from Plan B's `StoreShard`, and
implement a graph `condense` collapsing consecutive identical node and edge versions — the temporal-store
condense is the template. Independent of the cluster work, so it can proceed in parallel if resourced.

**Retention for the property index** and the property-key table, which the current sweep skips. This is why
storage grows monotonically even with retention enabled.

**Evict idle stores.** `GraphStoreManagerImpl` never evicts; `ShardManager.cleanup` is the template.

---

## Coding standards and housekeeping

**Yes, these are mandatory, and the build enforces them.** The repository's shared standards are in
[`../coding-standards.md`](../coding-standards.md); this plan does not restate them, with one exception
below that matters for how you verify work.

The essentials an implementer needs at hand:

- **`./gradlew clean build` is the gate**, not `compileJava`. It runs **Checkstyle at `severity=error`**, so a
  single violation fails the build. `compileJava` does **not** run Checkstyle — see the correction in
  [Verification](#verification).
- Write to the rules from the start rather than fixing them up: **max line length 120**, the custom import
  order with no unused imports, braces on every control statement, one statement per line, canonical
  modifier order, and a `default` on every `switch`.
- Every new file needs the **Apache-2.0 licence header**. `final` on parameters and fields; JSpecify
  `@Nullable` for nullability; builders for multi-field value types; `record`/`sealed` for AST and IR nodes;
  Javadoc in the **Preconditions / Postconditions / Null-status** style used across the query modules.
- **Tests are part of "done"** — JUnit 5 in the module's `src/test/java`. New behaviour without a test is
  incomplete, which for this programme means the assertions in [Verification](#verification) are deliverables,
  not follow-up work.
- **Housekeeping:** feature branch off `master`, and a **CHANGELOG entry via `log_change.sh`** (repo root) per
  `CONTRIBUTING.md`. Flag every user-facing change for `stroom-docs`.
- **"Line numbers are hints; names are contracts."** This plan cites many files. Re-read each before editing
  and confirm the signature still holds — the codebase has moved under this documentation set twice already.

> **Note on location.** `coding-standards.md` sits in `docs/`, outside this directory, and is not Graph DB
> specific. If `docs/` migrates to another repository, that link needs repointing — the standards themselves
> are repository-level and should follow the code, not the documentation.

## Out of scope

Language features (`SKIP`, path variables, `shortestPath`, relationship-type alternation, multi-`MATCH`,
spatial, server-side graph algorithms), genuine partitioning of one graph across nodes, and snapshot
fan-out. All remain in [12-future-work.md](12-future-work.md).

## Verification

**Merge correctness is provable without a cluster**, using two provisioned temporary stores:

| Test | Asserts |
|---|---|
| **Cross-fragment traversal** | Edge a→b in fragment 1, b→c in fragment 2; the merged store answers a two-hop pattern. The headline bug, and the case fan-out can never fix |
| **Partition equivalence** | One mutation set ingested whole vs split *k* ways and merged in every permutation ⇒ identical **logical** dumps. Byte equality does **not** hold — UID assignment order differs |
| **Byte-identical replay** | Merge the same fragment twice; binary-dump every dbi including lookups and info; assert no change. Repeat with an injected mid-merge abort |
| **UID collision** | Two fragments that assign the same UID to different names |
| **Property-index tier equivalence** | Values of length 32 / 33 / 511 / 512 via merge vs via direct ingest |
| **Mirror invariant** | Out-edge and in-edge tables are exact mirrors after merge, tombstones included |
| **Schema gate** | Doctor a source version; assert merge throws and the part directory is **retained** |

**Simulated cluster in one JVM:** two `GraphPaths` roots and two merge processors, with a test transport
client whose remote branch calls the second node's receive method directly. Assert both stores converge to
identical dumps. The cluster cases in [14-testing.md](14-testing.md#cluster-correctness-cases) (C1–C5) are
the acceptance target; **C3 is the discriminating one**.

**Regression gates:** `TestGraphTraversalEngine` (60+ call sites) should need **zero** changes — run it
unmodified. Plan B's existing merge and shard tests must stay green after the `PartMergeProcessor`
extraction.

**Build:** the real gate is **`./gradlew clean build`**, which runs Checkstyle at `severity=error`.
`compileJava`/`compileTestJava` compile but do **not** run Checkstyle, so a clean compile is not evidence the
work is finishable — use `checkstyleMain` on the touched modules for a fast check during development.

Two things to know before reading a red build:

- There is a **pre-existing, unrelated** failure in `stroom-sqlstore`'s `TestSqlTemporalStoreDocStoreImpl`
  (a stale mock signature, present at HEAD). Compile or check the touched modules individually for a clean
  signal.
- Because a full `build` also runs the test suite, expect the Graph DB and Plan B tests named above to be the
  ones that actually gate each increment.

## Risks

| Risk | Handling |
|---|---|
| **Replica divergence on genuinely conflicting writes** — two streams asserting different payloads for the same (node, `validFrom`) resolve last-merge-wins, and merge order differs per node | Inherited from Plan B. Document as a data-authoring constraint rather than solving it |
| **`PartMergeProcessor` extraction regressing Plan B** | Keep it in-package and façade-preserving; Plan B's existing tests are the gate |
| **Anchor-encoding drift** between ingest and merge | The single shared helper, plus the tier-equivalence test |
| **`ValList` blast radius** — modifies a sealed hierarchy used across the product | Its own change, separately reviewable; document the 255-element cap |
| **Mixed-version clusters during rollout** | A new endpoint means an old node returns 404 and the stream task fails loudly — the correct behaviour. Distinct endpoints make misrouting into Plan B staging impossible |

## Documentation to correct as this lands

Two files in this set currently mis-size Phase 3 work, both identified by the research behind this plan:

- **[12-future-work.md](12-future-work.md)** rates typed property values *Hard / High, needs a migration*.
  It does not — the codec is already typed, so it is an ingest and schema change.
- The same file frames `collect()` as needing a new value type through the whole stack. Renderers need no
  change; the cost is the sealed hierarchy and the serialiser.

Already fixed while writing this plan: `../coding-standards.md` pointed at three Graph DB
implementation plans that were retired to git history, leaving dangling links. It now points here.

Also to update as each phase lands: the **store-version policy** (new — nothing records store format today)
in [13-developer-guide.md](13-developer-guide.md) and [14-testing.md](14-testing.md); the strict-ingest and
XSD caveats in [03-ingest.md](03-ingest.md); the fixed-size and no-configuration statements in
[10-limits.md](10-limits.md) and [11-operations.md](11-operations.md); and the cluster blocker in
[README.md](README.md) and [02-architecture.md](02-architecture.md).
