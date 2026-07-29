# Epoch 0 development plan — removing the blockers to production use

**Status:** **Delivered — a record, not a plan.** All four phases are complete, as is the follow-on work
recorded under *Beyond the plan*. It is kept because the reasoning behind each decision constrains later
changes, and because several premises turned out to be wrong in ways worth knowing. For what is *still*
outstanding, see [12-future-work.md](12-future-work.md); this document is not a to-do list.
**Audience:** developers changing this code, and anyone asking why something is shaped as it is.
**Scope:** the programme that cleared the correctness, data-safety and operability blockers listed in
[README.md](README.md#production-readiness). Language-expressiveness gaps were deliberately out of scope
and remain so.
**Companion documents:** [12-future-work.md](12-future-work.md) (the full roadmap this plan draws from),
[02-architecture.md](02-architecture.md) (why the cluster problem exists),
[14-testing.md](14-testing.md) (the acceptance protocol).

*Research verified 2026-07-28 against branch `sw-query-optimiser`; delivered on the same branch.*

---

## Why this existed

*Written before the work; kept in its original tense below, because the analysis is what the design answers.*

Graph DB was **only correct on one node**: `GraphFilter` wrote directly into a live local LMDB environment on
whichever node processed the stream, so a cluster silently accumulated fragments and every query returned an
incomplete answer with nothing reporting it. That was the most serious of the blockers this programme set out
to clear.

Fanning queries out cannot fix this. A traversal can cross a fragment boundary, so merging independent
local traversals does not reconstruct paths — the reasoning is in
[02-architecture.md](02-architecture.md#why-fanning-queries-out-would-not-have-fixed-it). Correctness therefore
requires two things together: every mutation reaching **one authoritative store**, and every query running
against a **complete copy** of it. Both are now in place.

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
  name buffer before using it (`ByteBufferUtils.copyToDirectBuffer` exists for this). Nothing in the source tree
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

## Phase 1 — Correctness on a cluster — **complete**

`stroom-planb-impl` provides a working template for almost all of this. Six increments; the last two
together deliver cluster correctness.

**All six have landed.** What each increment says below is the design as executed, with three deviations
worth recording:

- **1.2** — the property index is rebuilt rather than row-copied, as planned, but the reason turned out to be
  stronger than "hash keys may not be portable": hash-tier keys carry clash-sequence suffixes, so copying one
  is incorrect rather than merely risky. There is also no byte-copy fast path for *any* graph key.
- **1.4** — `Shard` did not need generalising. The merge lifecycle was extracted from Plan B's
  `MergeProcessor` into a parameterised `PartMergeProcessor` and Plan B left behind a façade, so Graph DB
  reuses the loop without adopting `Shard` at all.
- **1.6** — `QueryNodeResolver` became a multibinder behind a `CompositeQueryNodeResolver` bound in
  `QueryModule` (rather than leaving the binding in `PlanBModule`), so the two injection sites stay
  single-valued and any feature can contribute a resolver.

One thing was added that the plan did not call for: `GraphFileTransferClientImpl` **throws** rather than
sending nowhere when there is no node list and no node identity. Silently discarding a fragment is the exact
failure the phase exists to remove.

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
- ~~Add `UidLookupDb.forEachEntry` — pairwise uid/name iteration.~~ **Not done, and not needed.** Merge
  collects the source uids with the existing `forEachUid`, then reads each name with `getValue(txn, uid)`. That
  is one extra lookup per interned name and it removes the need to touch a shared Plan B class at all — a
  better trade than the plan assumed.
- ~~Add package-private raw iterators and `mergeInsert`/`mergePut` seams to the graph DAOs so property blobs
  copy verbatim rather than round-tripping through decode/encode.~~ **Not done.** Merge decodes each version and
  re-encodes it through the ordinary `insert`. A verbatim-blob fast path remains available as an optimisation,
  and would be worth measuring before building: the property index has to be rebuilt from decoded values
  anyway, so the decode is not avoidable for every table.

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

## Phase 2 — Data safety — **complete**

**Strict ingest mode.** A `boolean strict` pipeline property on `GraphFilter` — pipeline properties cannot be
enums, so a tri-state severity is not available. The fail mechanism already exists in the same file
(`log(FATAL_ERROR)` followed by `throw LoggedException.create(...)`).

Done, but with a **simpler shape than planned**. Rather than applying the escalation at each of the ~9
validation sites, it went into `error()` alone: `error()` throws in strict mode, so every site that previously
reported and returned now fails the stream without knowing the setting exists. Two consequential follow-ons the
plan did not anticipate:

- `perRecord` had to gain a `catch (LoggedException)` **before** its `catch (RuntimeException)`, or the generic
  catch would have swallowed the strict-mode failure and logged it as an ordinary skipped record — defeating the
  whole setting. It rolls back the record's partial writes, then rethrows.
- The `default` branches needed a **known-element set** rather than a blanket complaint, because legitimate
  non-record elements (`<graph>`, `<src>`, `<dst>`, `<label>`) reach them too. Only `startElement` reports, so a
  bad element is not reported twice.

The four `TestGraphFilter` tests did **not** need inverting: `strict` defaults to false, so they still pin the
lenient contract, which is worth keeping. Four strict-mode tests were added alongside them instead — including
one asserting a fully valid document produces no errors in strict mode, since the known-element check is a
denylist inversion and getting the set wrong would reject valid input.

**Ship the XSD.** Moved from `src/test/resources` to `src/main/resources/stroom/graphdb/graph-mutation-v1.0.xsd`,
with a `GraphMutationSchema` accessor so there is one copy: shipped, tested against, and served. `SchemaFilter`
does indeed require `xsi:schemaLocation` — confirmed in `validateSchemaLocations`, which reports
`noSchemaLocations` and fails without it — so the worked XSLT and its expected output now emit it, re-verified
with Saxon 9.9.1-8 and `xmllint` against the shipped schema.

**One plan step was not possible as written.** `ContentPacks` does not hold shippable content: every pack is a
pinned commit of the external `gchq/stroom-content` repository, and packs are referenced only from tests. A
`graphdb` content pack therefore needs an upstream change to that repository first, and is out of this repo's
reach. What was done instead is to make the schema obtainable from a running Stroom —
`GET /api/graphDb/v1/mutationSchema` — and to document creating the `XMLSchema` document by hand, including the
system id it must be registered under. The content pack remains worth doing; it is now an upstream task rather
than a Stroom-repo one.

**The rebuild trap.** Documented as an accepted limitation rather than fixed, per principle 1: graph data is
reproducible from its sources, the format stamp forces a deliberate rebuild rather than silent corruption, and
source-stream retention is therefore part of a graph's recovery plan. Recorded in `11-operations.md` with the
two configurations spelled out, and the in-place compaction path that would remove the coupling left in
`12-future-work.md`.

## Phase 3 — Correctness surprises — **complete**

### 3.1 Typed property values — **complete**, all five types

`GraphPropsCodec` is **already typed** — it delegates to the type-tagged `ValSerdeUtil` — so nothing about the
stored bytes changed. Two of this plan's predictions were wrong, and both matter:

**No schema-version bump was needed.** The plan assumed typing changed the value format. It does not: the codec
already round-trips any `Val`, an older build decodes a `ValLong` correctly because `ValSerdeUtil` is shared,
and for a string property the new anchor derivation produces byte-identical keys to the old one. Bumping would
have invalidated every existing store for no correctness gain.

**"Keep the property index keyed on lexical text" turned out to be impossible as stated, and the resolution
inverts it.** Merge only ever sees *decoded* values, so it cannot reproduce an anchor derived from raw XML
text. Ingest therefore had to move to anchoring on the **decoded** value — `GraphAnchorEncoding.anchorValueBytes(Val)`,
which merge already used — making the two byte-identical by construction. For a string property the raw text
and the rendered value are the same, so nothing changed there.

That inversion is what limits the type set. A query seeks the anchor using the query literal's own text, so a
type whose canonical rendering differs from what an author would naturally write silently finds nothing:
`ValDouble(42.0)` renders as `42`, so `{x: 42.0}` would match no rows and raise no error. **`double` and dates
are therefore deliberately excluded**, with the two viable designs recorded in `12-future-work.md`. `long` and
`boolean` have exactly one sensible rendering, so they are safe.

> **The plan's stated motivation was also wrong, and the docs repeated it.** `ORDER BY` was never lexical:
> `ValComparators` maps `Type.STRING` to `AS_DOUBLE_THEN_..._STRING`, a numeric-first comparator, so `"9"`
> already sorted before `"10"`. What was genuinely broken is that every value's *type* was `STRING` regardless
> of what it held, so JSON output quoted numbers and type-aware code paths saw text. `README.md` and
> `03-ingest.md` have been corrected, including an explicit note that the earlier claim was wrong.

A value that fails to parse as its declared type is reported as a bad record rather than falling back to text —
a silent fallback would reintroduce the very surprise typing removes, but only for the rows that failed.

### 3.2 A real list type for `collect()` — **deferred; `collect()` disabled in the meantime**

**Decision: held until closer to production.** Rather than leave `collect()` returning a comma-joined string,
the keyword is now **rejected at compile time** with a message explaining why. It parses, and the grammar token
and AST constant are retained, so re-enabling is a small, well-marked change. Tests that pinned the old
behaviour were converted to assert the rejection rather than deleted, so re-enabling forces the executor
behaviour to be re-specified instead of silently inherited.

The full analysis — the ~6-file forced change set, the 279 files that need nothing and the evidence for that, the
285-file exposure that is the actual risk, the `Type.XML` precedent sites, the inherited 255-element cap, and the
step-by-step for picking it up — is in [12a-list-value-type.md](12a-list-value-type.md).

The remainder of this section is the original sizing, retained because it proved accurate:

Add a member to Stroom's sealed `Val` hierarchy: the `permits` clause
and `@JsonSubTypes`, a `Type` constant (**id 8 is free**), the **two** exhaustive `ValSerdeUtil` switches (the
compiler finds them — everything else has a `default`), a `ValSerialiser` entry, and a `ValComparators`
entry. `ValXml` is the shape to copy. Renderers need no change at all; they stringify.

> Note the inherited limit: `ValSerialiser.writeArray` caps at **255 elements**, which becomes `collect()`'s
> real ceiling and must be documented as such.

This touches shared query-language code used by Plan B, dashboards and StroomQL, so it warrants its own
change rather than riding along with graph work.

### 3.3 Temporal Precision — full serde swap — **done**

Landed, and **smaller than this plan predicted** in two ways.

**No schema-version bump was needed**, for the same reason as 3.1: the mechanism already existed. The key
schema string carries the serde's name (it read `"timeSerde":"millisecond6"`), so making that string
precision-dependent both distinguishes the layouts *and* gives immutability for free — a store written at one
precision produces a different stamp and is refused under another, with a `Key schema mismatch` error naming
both. `MILLISECOND` still yields the exact string earlier builds wrote, so existing stores keep opening and
nothing is needlessly invalidated. **No separate immutability enforcement was written.**

**The refactor was contained** because all three DAOs are constructed only inside `GraphStores.open` — three
call sites, no test builds them directly. So the whole existing suite passed unchanged, which is the strongest
evidence available that the millisecond path is byte-identical.

**This plan's stated reason for dropping `NANOSECOND` was wrong.** It said `MillisecondTimeSerde` silently
discards nanos — but `NANOSECOND` would use `NanoTimeSerde`, which carries genuine nanoseconds in 8 bytes. The
real reason is the **ingest vocabulary**: `graph-mutation:1` constrains a timestamp to three fractional digits,
so nanoseconds cannot be expressed and the widest key of any option would store guaranteed zeros. That is an
ingest-format limit, not an encoding one, so it is rejected in `GraphTimeSerdes` rather than removed from the
shared `TemporalPrecision` enum — if the vocabulary ever widens, it becomes worth supporting.

Five hard-coded `6`s were the real hazard, and three were not in the width constants at all: two
`keyWidth - 6, 6` buffer slices and a `copyOfRange(keyBytes, 0, keyWidth - 6)` in the retention sweep. Each
would have mis-sliced every key at any other precision, silently. Six helper methods became instance methods
as a result, plus `GraphTraversalEngine.resolveDumpInstant`.

`LATEST` also had to become per-store: it was `Instant.ofEpochMilli((1L << 48) - 1)`, the millisecond ceiling.
At `DAY` precision that wraps rather than saturating, so a "latest" lookup would have resolved to the wrong
version. It now comes from `GraphStores.getLatestRepresentableInstant()`, derived per precision from each
serde's own unit **and epoch** — `SECOND` and `NANOSECOND` count from 2000 while the rest count from 1970,
a Plan B inconsistency that is mirrored rather than corrected because the encodings must stay byte-compatible.

The original sizing follows, retained because its measurement of the blast radius was accurate:

Select a `TimeSerde` by precision, exactly as Plan B's temporal
stores do. The serdes have different widths (2–8 bytes), so this changes all three frozen key layouts and
every hard-coded `6`. Under principle 1 that needs no migration, but it **must** bump the schema version so
a store written at one precision refuses to open at another.

Measured scope, since the plan did not size it: each of `GraphNodeDb`, `GraphAdjacencyDb` and `GraphInEdgeDb`
holds a `private static final MillisecondTimeSerde TIME_SERDE` and a `KEY_WIDTH` constant, with key offsets
computed statically from both — including two `KEY_WIDTH - 6, 6` slices. All of that becomes per-instance state
driven by the owning document, so the three classes that carry the module's most load-bearing invariants are
touched at once. `GraphTraversalEngine`'s `MAX_INSTANT` also becomes serde-derived rather than a constant.

Two consequences to handle:

- The setting becomes **immutable after provisioning** — enforce it, as Plan B does via schema validation.
- **Drop `NANOSECOND`.** `MillisecondTimeSerde` silently discards nanoseconds and the vocabulary permits only
  three fractional digits, so it is unreachable in any design. The control currently offers one impossible
  value and four no-ops.

## Phase 4 — Operability — **complete**

**Traversal guardrails to config** — nearly free. `GraphTraversalEngine` already threads four of its five
constants through package-private seam constructors into instance fields, and its own Javadoc anticipates the
change. Promote them to `GraphDbConfig`, make the seam constructor the production path, and add a field for
`MAX_VAR_LENGTH_HOPS`, the only one still read statically. Two call sites; the 60+ existing test call sites
keep working provided the seam signatures are preserved.

**Tunable store size.** `GraphStores` passes `null` where `PlanBEnv` takes a `Long` map size — the only
production call site in the repository that does. Thread `GraphDbConfig.maxStoreSize` through
`open`/`provision`, and mark the getter as requiring a restart: LMDB fixes the map size at environment
creation.

**Condense — done. Compact — not started, and worth splitting.** The plan treated these as one item; they are
unrelated in both mechanism and risk.

`GraphStores.condense()` collapses runs of consecutive identical node and out/in-edge versions to the **earliest**
of each run. Keeping the earliest rather than the latest is the whole correctness argument: a point-in-time lookup
is a reverse floor scan, so for any instant inside a collapsed run the survivor is what the scan reaches, and it
holds the same value as those removed. Because no answer changes at any instant, condensing needs **no cut-off and
no setting** — unlike Plan B's `condenseBefore`, whose purpose is to avoid churning data still being written — and
it runs for every graph on every maintenance cycle regardless of whether retention is enabled.

The test that carries this is `condensing_changesNoAnswerAtAnyInstant`, which queries every instant across the
history before and after. Validated by sabotage: switching the implementation to keep the *latest* of each run
fails that test and nothing else, which is exactly the point — a row-count assertion would have passed.

**Compaction — done later, after backfill.** LMDB reuses freed pages for new writes, so a condensed or aged store
stops growing but does not shrink on disk. See [Beyond the plan — compaction](#beyond-the-plan--compaction-item-4a)
below; note that the claim in the previous sentence of the original text - that `PlanBEnv` exposes no compaction -
was wrong, and is corrected there.

**Retention for the property index** and the property-key table, which the current sweep skips. This is why
storage grows monotonically even with retention enabled.

**Evict idle stores — the template says don't, and that turned out to be the answer.** `ShardManager.cleanup`
does have an idle branch, but only `SnapshotShard` reaches it: `StoreShard.isIdle()` returns `false` with the note
that store shards are long-lived. Every graph store is authoritative, so the Plan B precedent is *not* to evict,
and the hazard was concrete - `getOrOpen`, as the manager's accessor was then called, handed back a reference the
caller used afterwards, so closing a store because it looked idle could close it under a traversal that already
held it. Doing it safely needs the manager to hand out a lease rather than a raw reference, which at this point
looked to cost more than it saved: an idle store holds file descriptors and reserved address space, not heap.

> That refactor was done later after all, because compaction needed it - see
> [compaction](#beyond-the-plan--compaction-item-4a). There is no `getOrOpen` on the interface any more. Idle
> eviction is now *safe* and still not implemented, on the narrower ground that it is not worth the reopen.

What *was* implemented instead is the branch that genuinely leaks: `cleanupOrphanedStores()` reclaims graph data
whose document no longer exists. A document delete normally reaches `delete(uuid)` via an entity event, but only
on a node running at the time - a node that was down keeps the directory forever, and since nothing will ever ask
for that graph again nothing notices. Bound to the retention job. An unreadable document store is treated as
"document still exists", so a transient failure can never turn into deletion.

**Retention for the property index — done, on a second attempt.** Two properties of the table shaped the design
and neither was in this plan. It has no `validFrom`, so it cannot be aged by time; and `GraphPropertyIndex`
deliberately offers no way to decode a value back out of a stored key, so an anchor cannot be tested against the
surviving versions row by row. Reachability sweeping does not help either - retention keeps a node's last version
at or before the cut-off, so nodes essentially never disappear, and it is the per-version *value* anchors that
accumulate. So the index is cleared and re-derived from the versions that survived.

The trap, found by testing and the reason the first attempt was reverted: the rebuild **must record property-key
usage as it writes**, because the sweep afterwards deletes every key entry not recorded. Skipping that does not
waste space - it leaves live anchors referencing key ids nothing resolves to, and every property-anchored query
then silently returns no rows. Verified by sabotage: removing the single `recordUsed` call fails two of the four
tests.

The **property-value lookup is swept too**, on a follow-up. The blocker was that a value above the inline tier is
interned inside `insert`, so the rebuild could not know which entries were used - and sweeping blind deletes
entries live anchors reference, which makes the affected queries return nothing. The fix is to record where the
knowledge is: `insert` takes an optional recorder and reports the tagged value segment as it writes, which is
exactly what `VariableUsedLookupsRecorder` reads to pick a lookup table and to ignore an inline value. Ingest
passes no recorder, since bookkeeping outside a sweep is consumed by nothing.

Verified by sabotage, and the test needs a value **longer than the 32-byte inline tier** or it exercises nothing:
a short value references no lookup entry, so the case would pass whether or not the sweep worked.

## Phase 5 — Transaction boundaries — **planned**

Found by pre-merge review, after Phases 1–4 were complete. Four sites where a failure is **durably committed**
rather than rolled back, so a rejected or failed write leaves partial state behind and the operator is told
nothing was applied. Phase 2 covered data safety at the *record* level; this is the same concern one level down,
at the transaction.

**One root cause under three of the four.** `LmdbWriter.close()` is `try { commit(); } finally { unlock(); }`, and
`PlanBEnv.write` runs the caller's function in try-with-resources over the writer — so when the function throws,
`close()` **still commits** everything staged since the last `tryCommit()`. This is pre-existing Plan B behaviour
(this branch only *added* `abort()`; `close()` is untouched), and it is safe for Plan B's own single-step writes.
It is not safe for the multi-step functions the graph added on top of it. Note the fix is per call site, not in
`LmdbWriter`: changing `close()` to roll back on an in-flight exception would change behaviour for every existing
Plan B writer, which is a larger decision than this phase should take unilaterally.

**Principle for all four**: a write that reports failure must leave the store as it was. "Reported and partly
applied" is worse than either outcome alone, because the operator's next action is wrong either way.

### 5.1 Retention must not commit a cleared property index

`GraphStores.deleteOldData` runs version deletion → `propertyIndex.clear(writer)` → full anchor rebuild → UID
sweeps, in one transaction with no abort path. Any throw after the `clear` — realistically `MDB_MAP_FULL` while
writing anchors on a large graph, an OOM from the in-memory anchor list, or an interrupt from the nested
`env.read` — commits the deletions *plus* an empty or half-rebuilt index.

The consequence is the one this phase most needs to prevent: `findAnchors` returns nothing for values live nodes
still hold, and the traversal engine's re-check **filters** candidates rather than adding them back, so
`MATCH (n:Person {id: 'x'})` returns no rows and is indistinguishable from the node not existing. Nothing heals it
until a later retention pass happens to reindex, which is gated on `count > 0` and may never run. The retention
job runs every ten minutes, so the window is not narrow.

**Fix**: catch, `writer.abort()`, rethrow. A rebuild-into-a-fresh-table-and-rename-swap would be stronger and is
worth considering later; it is not needed to close the silent-wrong-answer hole. **Done when** a test that injects
a failure part-way through the rebuild leaves a store whose property-anchored queries still return the right rows.

### 5.2 A refused merge must leave nothing behind

`GraphStores.merge` runs `translateNamespace → mergeNodes → mergeEdges` in one transaction, and two things go
wrong:

- The out-edge/in-edge count consistency check — the check that *refuses* a corrupt fragment — runs at the top of
  `mergeEdges`, i.e. **after** `mergeNodes` has written and (through `tryCommit` batching) partly committed every
  node version and anchor. So a fragment rejected as corrupt has all of its nodes committed, with no edges, on
  every node in the cluster. Traversals then see nodes with no relationships, which is indistinguishable from real
  data.
- If `inEdges.insert` throws after `outEdges.insert` succeeded for the same edge, `close()` commits a **half
  edge** — violating the edge-pair atomicity the method's own Javadoc promises. Traversal then answers differently
  forwards and backwards.

**Fix**: validate before writing — move the adjacency-count check (and ideally a full translate-map validation
over both stores) ahead of the first write — and catch/`abort()`/rethrow around the whole merge. **Done when** a
test merges a fragment whose adjacency tables disagree and asserts the store is byte-for-byte unchanged, and a
test proves an edge is never present in one direction only.

### 5.3 Fragments awaiting or failed merge must survive a restart

`PartMergeProcessor.unzipPartFile` moves each fragment into the per-document `DirQueue` under `mergingDir` and
then **deletes the source zip immediately**, without waiting for the merge on the async path. A failed merge
leaves its directory in `mergingDir` — but nothing in-process re-reads it, and **the constructor deletes the
entire contents of `mergingDir` at startup.**

So the documented recovery procedure does not work. `11-operations.md` tells an operator a failed fragment "is
retained deliberately so it can be merged once the cause is fixed"; they raise `maxStoreSize` and restart, and the
restart deletes it. That node's graph is then permanently and silently short of that stream's data — the exact
failure the whole fragment-and-merge design exists to remove. The constructor's own Javadoc ("in-flight work whose
source zip has not yet been deleted, so it is safe to discard and will be redone") is false on both counts.

**The recovery machinery already exists and is dead code**: `DirQueue` initialises its `readId` from the minimum
id on disk, and `merge()` lists `mergingDir` to recreate queues. Both are defeated by the startup wipe.

**Fix**: stop wiping `mergingDir` at startup — only `unzipDir` is genuinely scratch — and let `DirQueue`'s existing
restart recovery requeue the survivors. Also stop the synchronous `merge(storeId)` path deleting the unzip
directory when `mergeDir` failed, which contradicts even its own in-code comment.

**This is the riskiest item in the phase**, and the only one outside `stroom-graphdb`. `PartMergeProcessor` is
shared with Plan B's own state stores, so the change alters restart behaviour for both. Treat Plan B's merge tests
as part of the gate, not just the graph's. **Done when** a fragment left in `mergingDir` is picked up and merged
after a restart, for a Plan B store as well as a graph.

### 5.4 A failed stream must not ship its partial fragment

`AbstractProcessorTaskExecutor` calls `pipeline.endProcessing()` in a `finally`, after
`handleProcessingException`. `GraphFilter.endProcessing` then unconditionally calls `shardWriter.close()`, and
`close()` zips and sends the fragment whenever `dirty` is true. There is no abort or discard path on
`GraphShardWriter` at all.

So when strict mode throws on record N, records 1..N−1 are still shipped and merged into every node's
authoritative store. `GraphShardWriters`' class Javadoc states "a failed or abandoned stream leaves nothing behind
in the real store", and strict mode's whole contract is "rather have no data than quietly incomplete data" — both
inverted: strict mode delivers a **prefix**. Worse, if the operator fixes the translation and reprocesses,
mutations that existed only in the bad run stay in the graph forever, because idempotent merge heals an identical
replay, not a corrected one.

**Fix**: give `GraphShardWriter` a `markFailed()`/`discard()` path; have `GraphFilter` set it when a fatal error
occurred, so `close()` cleans up without sending and the stream is reprocessed whole. **Done when** a strict-mode
failure part-way through a stream ships no fragment, and a lenient-mode run with some bad records still ships one.

**Phase 5 gate**: for each of the four sites, a test that forces the failure and asserts the store or the queue is
left in a state the documented recovery procedure actually recovers from. No new silent path: every one of these
failures is already reported — the bug is what it leaves behind, not whether it is noticed.

## Beyond the plan — backfill (item C1)

Not in this plan, because the plan's cluster scope was "make ingest and query correct on the nodes that are
already there". Adding a node was left to [12-future-work.md](12-future-work.md), and it is the one remaining
way a config edit reintroduces partial answers.

The implementation turned out to be small, for a reason worth recording: **a graph store and an ingest fragment
are the same shape.** A fragment is a complete graph store containing one stream's mutations, so a whole store
can be sent down the identical path — copy, zip, hash, `storePart` — and the receiving node stages and merges it
with no idea it was not produced by ingest. `GraphBackfillService` is therefore about forty lines of orchestration
over machinery that already existed.

Two properties of merge do the real work, and both were deliberate choices from Phase 1 rather than luck. Merge
is **idempotent**, so shipping the store to every configured node — including ones that need nothing, which the
transport cannot distinguish — is harmless. And it is a **union of versions**, so a node holding part of the graph
converges rather than having one copy overwrite the other.

**A correction to an earlier statement in this document.** The compaction section above says `PlanBEnv` "does not
currently expose compaction at all". It does: `PlanBEnv.copy(File, CopyFlags...)` wraps LMDB's own copy, which
takes a consistent snapshot under a read transaction and accepts `MDB_CP_COMPACT`. Item 4a is therefore not
blocked on a missing primitive, only on the atomic swap. Backfill uses that same primitive via
`GraphStores.copyTo`, which is why a backfill needs no quiet period.

**It is deliberately manual.** Detecting that a node has joined and needs backfilling means tracking cluster
membership over time, which nothing in this feature does. `POST /api/graphDb/v1/<uuid>/backfill` requires the
Manage Nodes permission and is documented as a step in the add-a-node procedure in
[11-operations.md](11-operations.md#backfilling-a-node).

The test that carries this is in `TestGraphTwoNodeCluster`, and it starts by asserting the **defect**: a node
detached from the transport, reattached, then asked a traversal spanning old and new data returns nothing while
the node that was always there returns the answer. Backfill then converges it. Validated by sabotage twice —
skipping the send fails only the convergence test, and skipping the cleanup fails only the no-leftover-files test.

## Beyond the plan — compaction (item 4a)

The plan bundled condense and compact as one item. They were split during Phase 4 because they are unrelated in
mechanism and risk; condense shipped then, and this is compact.

**The blocker was never the copy.** It was that `GraphStoreManager.getOrOpen` *returned* a store, so a caller held
a reference the manager knew nothing about. Nothing that needs to close or replace a store - compaction, idle
eviction, deleting a graph - then has a safe moment to act, because a traversal might be part-way through the
environment it is about to close. Note that `delete` had this hazard already; it was not introduced by
compaction, only made visible by looking for it.

So the manager now **lends**: `use(doc, function)` and `useForQuery(doc, function)` run the caller's work against
the store and return its result, holding a per-graph read lock for exactly that long. `compact` and `delete` take
the write lock. Eight call sites, all of which already used the store within a single method - nothing had to be
restructured, which is the sign the reference was never needed in the first place. `getOrOpenUnguarded` remains,
named to discourage use and documented as callable only from `use` and `compact`.

Compaction itself is copy-and-swap, ordered so that **every failure leaves a usable store**:

1. Copy first, original still open and serving. A failed copy changes nothing.
2. Abandon if the copy is not smaller. LMDB always writes a meta and root page, so an already-compact store
   copies to about its own size, and swapping it in would cost a whole rewrite for nothing.
3. Close, move the original aside, move the copy in. If the second move fails the first is undone, so the window
   with no store is two renames on one filesystem.
4. Delete the original last. A failure there leaves a stale directory that `cleanupOrphanedStores` reclaims,
   because no document resolves its name.

Triggered from the maintenance job **only when retention or condense actually removed something**. Compaction
rewrites the whole store and needs room for a second copy, so running it on an unchanged graph spends that on
nothing - and on a graph nothing writes to, those two operations are the only source of free pages.

**Two things the tests found that the design did not anticipate.** Condensing 20,000 versions leaves the file a
page *larger*, because the deletions are themselves writes - so "condensing does not shrink the file" is
understated. And file size is not a usable assertion for "was this store rewritten", because *opening* an
environment writes to it: a freshly compacted 20 KB store is 96 KB after one read. The already-compact test
therefore asserts on the data file's inode, which answers "was it replaced" directly.

Validated by sabotage three times: removing the read lock from `use` fails only the concurrency test, always
swapping fails only the already-compact test, and skipping the superseded-directory cleanup fails only the
no-leftover-directories test.

**Idle eviction remains deliberately unimplemented**, but the reason has changed. It was unsafe; now it is merely
not worth it. An idle store holds file descriptors and reserved address space rather than heap, and reopening it
costs a real query.

## Beyond the plan — compaction cadence, and item C2

Two items found by asking, after the fact, what was still between this and production.

### The compaction cadence was wrong, and it was a regression

Compaction shipped gated on "did retention or condense remove anything", described at the time as leaving an
unchanged graph alone. That holds for a **static** graph. It does not hold for the workload condensing exists
to serve: a graph reloaded on a schedule produces identical versions constantly, so condense removes something
on essentially every cycle - and maintenance runs every ten minutes. On exactly the large, actively-fed graph
that motivated condensing, compaction was therefore running six times an hour, each run a full copy of the
whole store holding the lock that excludes every query on that graph. The gate approximated "always" precisely
where it needed to bite.

**Two changes.** Compaction moved to its own `Graph DB Compaction` job on a nightly off-peak cron, so the
blocking is bounded and the cadence is operator-visible and adjustable without a code change. And the gate
became durable: a `FREED_SPACE_PENDING` flag in `graph-info`, set when retention or condense removes anything
and cleared when a compaction reclaims it. On disk rather than in memory because removal and compaction now run
a day apart, and a restart in between is ordinary - a graph that shed a lot of data and then went quiet is the
one most worth compacting and the one an in-memory flag would forget.

The flag is also checked **before** the copy rather than after. The pre-existing size comparison could already
abandon a pointless compaction, but only once the store had been rewritten, which is the cost being avoided.
That comparison is now unreachable through the public API and stands as a backstop; the test class says so
rather than pretending to cover it.

One consequence worth recording because it surfaced as a test failure: clearing the flag reopens the store, and
opening an LMDB environment writes to it. So the file after a compaction is larger than
`before - reclaimed`. The reclaimed figure is real; a later `du` shows that plus the next open's cost. The test
asserted exact equality and had to be corrected, along with a comment claiming nothing reopened the store.

### C2 — detecting a `graphdb.path` change

The last of the config edits that silently produce wrong answers. The plan's suggestion was "a marker file
recording the expected path", and the reason this took thought is that **the obvious placement cannot work**: a
marker inside `graphdb.path` can only be found by looking inside `graphdb.path`, and not looking at the old
root any more is the entire failure. There is nothing at the new path to distinguish a moved setting from a
deployment nobody has loaded a graph into.

So the last-used root is recorded in `<stroom.home>/graphdb-root.txt` - node-local state that survives a
`graphdb.path` edit - and checked at startup. The check is deliberately not "did the path change", which would
fire on the documented procedure for changing it, but "did the path change **and leave graphs where nothing
will look for them**". A move that took the data with it is logged at INFO and accepted; a move that stranded
data is an ERROR naming both paths and the graphs left behind, and it repeats on every startup, because
stranded data is not a transient condition and reporting it once lets the next restart bury it.

`check()` returns its conclusion as well as logging it. The first version of the test inferred the outcome from
the marker's state afterwards, which meant it would have passed had the class logged nothing at all - a test
asserting against a string the test itself had built. Making the result observable was the fix.

Two limits, both documented: it cannot detect a change made at the same time as `stroom.home` moving, and a
lost marker reads as a fresh install.

Validated by sabotage three times: removing the compaction gate fails the two gate tests; never detecting
stranded data fails the two stranded tests; advancing the marker despite stranded data fails only the
report-on-every-startup test.

## Beyond the plan — typed `double` and `dateTime` (Phase 3.1's remainder)

Phase 3.1 shipped typed `long` and `boolean` and deliberately left these two out. The plan's own note said the
index should stay keyed on lexical text "not for compatibility, but for correctness", because ingest indexed raw
XML text and the seek used the query literal's raw text, so the two agreed. That was accurate, and it is exactly
why `double` could not be added: `ValDouble(42.0).toString()` is `"42"`, so a query for `42.0` would find nothing
and report nothing.

**The plan's two candidate routes were (a) a canonical encoder and (b) index both forms.** Neither was quite
right, and the resolution came from a question asked during review - shouldn't doubles compare with a tolerance?
The answer is no, but working out why produced the rule the design now rests on.

**The rule: the index must agree with the predicate, and where it cannot, err towards matching too much.** An
anchor is only a candidate filter; the engine re-checks every candidate against the node's decoded properties.
So an over-broad anchor costs a little work and an under-broad one silently loses rows. Stroom's `=` on numbers
is exact everywhere (`NumericEquals` is `Objects.equals(Double, Double)`), so the index is exact too.

What that gives:

- **Numbers key by value**, through a canonical order-preserving eight-byte encoding, under a type tag. `42`,
  `42.0` and `42.00` agree; so does `007` with `7`. Dates fold into the same numeric space, since an instant is a
  count of milliseconds - so every spelling of one instant agrees as well.
- **A numeric literal seeks twice**, the text encoding and the number encoding, and unions. That removes the need
  for a per-`(label, property)` type registry, which was the awkward part of route (a). A string property holding
  `"42"` and a numeric one holding 42 are both reachable from the literal `42`.
- **Longs above 2^53 share an encoding.** A collision, not a loss - each still reaches its own anchor, and the
  predicate separates the neighbours. Erring towards matching too much, as the rule requires.
- **Booleans stay text**, because a literal carries `true`, not 1.

**A route considered and withdrawn during design:** not indexing doubles at all, on the grounds that float
equality is fragile. It leaves a silent hole (an integer-form literal against a double property anchors, finds no
entry, returns nothing), it penalises the very type annotation you want people to use, and decisively it does not
help - the predicate is still exact, so a computed `0.30000000000000004` still fails to match `0.3`, just after a
full scan instead of a seek. Fragile float equality is a property of the *predicate*. `approxEquals` is recorded
in [12-future-work.md](12-future-work.md) as the explicit fix.

**Three things testing found that the design did not anticipate.**

- `anchorNeedsReindexing` compared rendered forms, and that is no longer the anchor key. String `"42"` and long
  42 render identically and key differently; long 42 and double 42.0 render differently and key identically. It
  now asks the encoder, and the existing unit test's assertion **inverted** - recorded rather than quietly
  changed, because a reader of that test would otherwise reasonably assume it had always said this.
- The Cypher grammar's `NUMBER` rule has no exponent, so `4.2e1` is not a literal a query can contain. The
  encoder accepts one anyway, so the grammar can gain exponents later without touching the index; the end-to-end
  test says so rather than silently dropping the case.
- Twenty-four test fixtures seeded anchors with raw UTF-8 rather than through the encoder. They all failed, which
  is the right outcome - they were simulating ingest without doing what ingest does - but it is worth knowing that
  the encoder is the only correct way to construct an anchor, in tests as much as in production.

Store format bumped to **version 2**; existing stores refuse to open and must be rebuilt. Validated by sabotage
four times: text-only seek, number-only seek, dropping the order-preserving transform, and keying booleans
numerically each fail a distinct and appropriate set.

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

### To expand later — restore Javadoc validation

**Not yet planned in enough detail to execute. This is a placeholder with the findings, not a task.** Recorded
here rather than in a feature plan because it is repository-level, like `coding-standards.md` itself: it affects
every module, and neither the Graph DB nor the query optimiser owns it.

**What was lost.** Commit `d8e38498c0` ("Fix javadoc for the full build (Gradle 9)") removed
`options.addStringOption('Xdoclint:all,-missing', '-html5')` from the `subprojects` javadoc block in
`build.gradle`, leaving only the `allprojects` setting `Xdoclint:none`. Before that, modular subprojects
validated Javadoc syntax, HTML, and `@link`/`@param`/`@return` **references** — only "missing comment" warnings
were suppressed. Javadoc is now unvalidated everywhere.

**The two halves were separable.** The Gradle 9 `ClassCastException` that motivated the commit came from the
adjacent `options.addStringOption('-module-path', classpath.asPath)` line (module-path is a typed list option, not
a string one). Removing that line fixes the build; removing `Xdoclint` was not required, and was not called out
as a behaviour change.

**Why it matters here.** The bullet list above mandates Javadoc in the Preconditions / Postconditions /
Null-status style, and the section opens by asserting the build enforces these standards. For Javadoc, it no
longer does. A broken `{@link NoSuchMethod}` or a `@param` naming a deleted parameter builds clean. That is not
hypothetical: pre-merge review found four such references across this branch — `BuildSideLookup` and
`LmdbJoinBuildStore` documenting a `get` method that has never existed (five references between them),
`GraphStoreStatsAdapter` citing `GraphStoreManager#getOrOpen`, and `CypherCompiler` citing
`GraphSearchProvider#terminalProject`. All are now fixed, but nothing would have caught them and nothing will
catch the next one.

**Why this is not a one-line revert.** `:stroom-query:stroom-query-planner:javadoc` alone emits **29 warnings**
today (e.g. `TemporalContext.java:29: warning: reference not found: LogicalPlan`). Under doclint those become
errors, so restoring it repo-wide fails the build until every module is cleaned. The scale across all modules is
unmeasured.

**The decision to settle before planning this.** Two shapes, and it should be a deliberate choice rather than
whichever is discovered mid-task:

- **Repo-wide at once** — one cleanup sweep, then `Xdoclint:all,-missing` back in `allprojects`, replacing
  `Xdoclint:none`. Highest assurance, largest and least divisible piece of work, and it blocks on modules this
  programme never touched.
- **Per-module ratchet** — enable it module by module as each is cleaned, starting with the modules this branch
  added (`stroom-query-planner`, `stroom-query-grammar`, `stroom-graphdb-impl`), where the Javadoc contracts are
  newest and the density of `{@link}`s highest. Lands value immediately and never blocks on unrelated code, at
  the cost of a per-module opt-in list to maintain until it is universal.

A narrower third option, if the cleanup cost proves prohibitive: enable only
`Xdoclint:reference,syntax,html` (dropping `accessibility`), which catches the class of defect actually found
above while ignoring the presentational warnings that likely make up most of the 29.

**Do not fold this into an unrelated commit.** Whichever shape is chosen, the cleanup and the build-config change
should land together and alone, so a bisect over a Javadoc-caused build failure lands somewhere informative.

## Decisions taken during implementation

Two questions the plan left open were settled deliberately rather than by default, and both are recorded here
because the reasoning matters more than the outcome.

**The strict-ingest default stays lenient — because `strict` is already a per-pipeline UI choice.**
`@PipelineProperty` becomes a `PipelinePropertyType` in the `ElementRegistry`, which the pipeline property editor
renders, so a pipeline author sees and sets it alongside `graphDb`. A global default therefore decides only what
happens when nobody chose, and flipping it would change behaviour for existing pipelines on upgrade — a
version-bump surprise on data that has been "working".

What made lenient acceptable was removing its silence: a stream that reported any ingest error now ends with one
line naming the count and stating the graph is incomplete. That count is taken in `error()`, **not** in
`perRecord`'s catch — a first attempt put it in the catch and missed almost everything, because a handler's own
validation reports and returns normally without ever reaching it, and those are the common failures.

**Variable-length cycles keep node uniqueness rather than adopting Cypher's relationship uniqueness.** Graph DB
will not return `a→b→a→c` for `(a)-[:R*1..3]->(x)`; Neo4j will, since only the node repeats. Graph DB is
therefore stricter and returns fewer paths, silently.

This is a choice about which failure is preferable. Node uniqueness bounds a path at the number of nodes;
relationship uniqueness is combinatorial in a dense subgraph and would make the 200,000 path-state ceiling far
easier to hit — trading a narrower answer for a failed one. It also cannot be made non-silent: knowing that a
query *would have* matched more paths requires performing the wider traversal.

Since it cannot be signalled at runtime, it is signalled in the documentation instead. It is now a
[README](README.md) blocker with the concrete example, expanded in
[06-language-reference.md](06-language-reference.md#variable-length-hops), and flagged in
[09-gql-and-neo4j.md](09-gql-and-neo4j.md) as something to re-check on any ported query.

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

**Simulated cluster in one JVM** — done, as `TestGraphTwoNodeCluster`: two `GraphPaths` roots, two merge
processors, and a transport that replicates every fragment to both nodes through the real `receiveRemotePart`, so
the staging store's hash verification is exercised rather than stubbed past. Covers C1–C4 and C6, including the
discriminating C3.

One correction to the target above: convergence is asserted on the **logical** node set, not identical dumps —
for the reason the partition-equivalence case already gives, that interned id assignment order differs per node,
so the two stores are legitimately not byte-identical.

Validated by sabotage: with replication reduced to the producing node, the cross-fragment traversal and
convergence cases both fail. Worth re-checking if the test changes, because a cluster test that passes either way
proves nothing.

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

### Done, with Phase 1

- The cluster blocker in [README.md](README.md), [02-architecture.md](02-architecture.md),
  [01-introduction.md](01-introduction.md), [11-operations.md](11-operations.md) and
  [12-future-work.md](12-future-work.md) — rewritten to describe the fragment-and-merge design, keeping the
  fan-out reasoning because it is what constrains any future change here.
- The **store-format-stamp policy** added to [13-developer-guide.md](13-developer-guide.md) (including *bump
  the version if you change any of these layouts*) and [14-testing.md](14-testing.md).
- The **no-configuration** statements in [11-operations.md](11-operations.md) and [README.md](README.md) —
  `graphdb.path` and `graphdb.nodeList` now exist, and the directory layout and merge job are documented.
- The two Phase 3 mis-sizings in [12-future-work.md](12-future-work.md), corrected now so the next phase is
  planned against real numbers: typed property values are *Medium / Low*, not *Hard / High needing a
  migration* (the codec is already typed), and `collect()` needs no renderer changes — its cost is the sealed
  hierarchy and the serialiser, with `ValSerialiser.writeArray`'s 255-element cap becoming its real ceiling.
- Cluster acceptance cases in [14-testing.md](14-testing.md) turned from "will fail today" into real
  acceptance criteria, plus format-stamp cases and the note that cluster-versus-single-node equivalence is
  **logical**, not byte-for-byte.

Already fixed while writing this plan: `../coding-standards.md` pointed at three Graph DB
implementation plans that were retired to git history, leaving dangling links. It now points here.

### Done, with Phase 2

- The strict-ingest and XSD caveats in [03-ingest.md](03-ingest.md) — rewritten as a `strict` property
  reference, an in-pipeline validation recipe, and the `xsi:schemaLocation` requirement.
- The data-safety table in [README.md](README.md) — three rows now record what closed and what is accepted.
- The rebuild trap in [11-operations.md](11-operations.md) as an accepted limitation with its two
  configurations.
- The worked output in [04-event-logging-xslt.md](04-event-logging-xslt.md) and
  [`examples/expected-output.xml`](examples/expected-output.xml), regenerated so they match the XSLT.

### Done, with Phase 3.1

- The `long`/`boolean` type attribute documented in [03-ingest.md](03-ingest.md#property-value-types), with an
  honest table of what declaring a type does and does not buy, and why `double` and dates are excluded.
- **A correction, not just an update:** [README.md](README.md) and [03-ingest.md](03-ingest.md) both claimed
  ordering was lexical (`"10" < "9"`). It never was. Both now say so explicitly rather than quietly changing.
- [12-future-work.md](12-future-work.md) resized: typed `long`/`boolean` struck through, typed `double`/dates
  split out as a separate item blocked on an anchor-encoding decision. **That decision has since been taken**
  and is recorded in [12-future-work.md](12-future-work.md#anchor-encoding-for-typed-values), along with
  `approxEquals` — the explicit tolerant comparison that covers what no encoding can fix.

### Done, later — corrections found after the phases closed
- The fixed-size statements in [10-limits.md](10-limits.md) and [11-operations.md](11-operations.md) —
  **done.** Swept with backfill, because the sizing and scaling sections of
  [11-operations.md](11-operations.md) still said the store "cannot be enlarged" and listed making it
  configurable as future work, and [01-introduction.md](01-introduction.md) and
  [04-event-logging-xslt.md](04-event-logging-xslt.md) repeated it. The Lucene comparison table went with them:
  three of its six rows described the pre-cluster design.
- The add-a-node procedure in [11-operations.md](11-operations.md) — **done**, with backfill; it previously
  told an operator to copy shard directories by hand.
- The *deleted space is never returned* statements — **done**, with compaction, in
  [11-operations.md](11-operations.md), [README.md](README.md) and [12-future-work.md](12-future-work.md). The
  rebuild-trap section keeps its warning, corrected: compaction reclaims free pages and cannot reconstruct data,
  so it does not loosen the source-stream coupling.
- **The store-lending rule added to [13-developer-guide.md](13-developer-guide.md)** — new, and load-bearing.
  Holding a `GraphStores` past the call that obtained it defeats compaction, and nothing in the type system
  stops it.
- The *Temporal Precision is inert* note — **done.** [11-operations.md](11-operations.md) now documents the
  per-precision key widths, savings and representable ceilings, plus the fixed-at-creation rule;
  [README.md](README.md), [10-limits.md](10-limits.md), [12-future-work.md](12-future-work.md) and
  [13-developer-guide.md](13-developer-guide.md) updated to match.
- The string-only-property entry under *Correctness surprises* in [README.md](README.md) — **done.** All
  five property types now exist, so the entry is struck through and records what remains true instead: equality
  on decimals is exact, so a value computed before ingest may not match a literal that looks the same. The
  `collect()` entry is done too: it records the rejection and links to
  [12a-list-value-type.md](12a-list-value-type.md).
- **The property-index rule added to [13-developer-guide.md](13-developer-guide.md)** — that an anchor is a
  candidate filter, so an over-broad one costs work and an under-broad one loses rows silently. It is the
  constraint on anything that touches that index later, and nothing in the code states it.
- [03-ingest.md](03-ingest.md) — the *`double` and dates are not available, and why* section replaced by how
  value-based matching behaves, plus the honest caveat about exact decimal equality.
