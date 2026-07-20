# StroomQL joins — what's built, and what could come next

*Audience: engineers and stakeholders deciding where to invest next in the StroomQL join engine.*
*Companion documents: [stroomql-join-scalability-report.md](stroomql-join-scalability-report.md) (the original
options survey), [join-scalability-implementation-plan.md](join-scalability-implementation-plan.md) (the
engineering plan and decisions for what's built so far).*

---

## 1. Where things stand today

A first version of join scalability work has shipped: **guardrails, per-side predicate push-down, projection
pruning, time-partition pruning, and a fast path for the common "enrich big data with a small reference store"
case.** Concretely:

- **Guardrails.** A join can no longer silently exhaust memory. Configurable row caps
  (`stroom.query.join.maxSideRows`/`.maxOutputRows`) abort an oversized join with a clear error instead of an
  out-of-memory crash.
- **Predicate push-down.** A `WHERE` term that references only one side of the join (e.g.
  `a.StreamId = 1`) is now pushed into that side's own scan, so the datasource filters before the join runs rather
  than after every row has already been fetched. `LEFT` joins are handled carefully: a predicate is never pushed
  onto the side that gets null-padded, which would otherwise silently change the answer.
- **Projection pruning.** Each side now fetches only the columns the query actually needs (its join key, plus
  whatever the query selects, filters, or sorts by from it), instead of fetching every column the datasource has.
- **Time-partition pruning.** A pushed time-range predicate now also narrows *which time partitions get scanned*
  at all, not just which rows survive - the same shard-skipping ordinary time-bounded searches already get.
- **Enrichment joins.** When one side of a join is a keyed lookup store (a "Plan B" reference-data store) and the
  join key is that store's own key, the query no longer scans the store at all - it does one direct lookup per
  row of the other side instead. This is the shape most "enrich events with reference data" queries take, and it
  is now effectively free regardless of how large the reference store is.

**What this does *not* yet solve**: two large datasources joined on a key with no filtering predicate on either
side (call it "big⋈big"). That shape still runs the original approach - read both sides fully into memory,
combine them - just protected by the new guardrails, so it now fails cleanly with a clear message instead of
crashing, rather than actually succeeding at scale.

---

## 2. What's still on the table, and why we'd want it

Everything below is the general options survey from the original report, refined now that we've seen the v1 work
land. They're ordered by our recommendation, not by how they were originally grouped.

### 2.1 Let a join spill to disk instead of running out of memory

**The idea:** today, combining the two sides of a join happens entirely in server memory. Make it use the same
disk-backed storage that ordinary large searches already rely on, so a join that's too big for memory can still
complete - just more slowly - instead of failing.

**What to expect:** this is about **raising the ceiling, not making things faster**. A join that already
completes today won't get quicker; a join that's currently too big to run at all will start succeeding, at
perhaps a third to a tenth of the speed per row (disk is slower than memory). Combined with this, results could
start streaming back as they're found rather than only once the whole join finishes, which also means a query
asking for "just the first 20 rows" can genuinely stop early instead of doing all the work anyway.

### 2.2 Always build the smaller side of the join

**The idea:** today the join always builds its lookup structure from a fixed side, regardless of size. Once
results can stream (see above), we get a natural, cheap opportunity to always pick whichever side is actually
smaller.

**What to expect:** a real, low-effort speed win, particularly when the two sides are very different sizes. If
one side has ten times as many rows as the other, choosing correctly can mean roughly a tenth of the memory and
build time for that step - with very little engineering risk.

### 2.3 Reduce the big side even when there's no explicit filter

**The idea:** predicate push-down (already built) only helps when the query has an explicit condition like
`a.StreamId = 1`. It doesn't help a query like "join today's events to yesterday's active users," where the only
thing narrowing the results is the join itself, not any separate filter. This option computes the smaller side's
set of matching keys first, then tells the larger side's scan "only bring back rows whose key is possibly in this
set" - filtering at the source without needing the user to write any extra `WHERE` clause.

**What to expect:** this is where genuinely large, everyday joins (not just filtered ones) get meaningfully
cheaper. How much cheaper depends entirely on how much overlap there really is between the two sides' keys - it
could be a modest win or a dramatic one, but unlike push-down it applies automatically to a much larger set of
real-world queries.

### 2.4 Spread a join across the cluster

**The idea:** every option above still runs the join on a single machine. Stroom's ordinary searches already
split work across every node in the cluster and run it in parallel; joins have never used that machinery. This
option would send a copy of one (small-enough) side to every node, so each node can join it locally against its
own slice of the big side, all at once.

**What to expect:** this is the first option that changes the *shape* of the speed-up, not just the ceiling. If a
join used to take one core's worth of time, spreading it across, say, ten cluster nodes could realistically bring
that down close to a tenth of the time for the heavy scanning/matching part of the work (the coordination and
final assembly of results don't speed up quite as cleanly, so the whole query won't be a full ten times faster,
but the dominant cost usually will be). This is also the most involved piece of engineering on this list, since
the join code has never been wired into the cluster's distributed-search machinery before.

### 2.5 Teach the optimiser to choose between strategies automatically

**The idea:** once there's more than one way to run a join (build the small side, spill to disk, broadcast across
the cluster, and so on), something needs to decide which to use for a given query. This option is about giving
the query planner real information about how big each side actually is (today, that information mostly doesn't
exist for indexed or reference-data sources) and having it pick the cheapest strategy automatically.

**What to expect:** this isn't primarily about speed - it's about **avoiding bad decisions**, like broadcasting a
side across the cluster that turns out to be far bigger than expected. It only becomes valuable once there are
multiple real strategies in place to choose between, which is why it's sequenced after the options above rather
than before them.

### 2.6 Handle the case where neither side is ever small

**The idea:** for the rare case where two genuinely enormous datasources need to be joined with no useful
filtering or asymmetry at all, there are established database techniques (sorting both sides and merging them in
a single pass, or hash-partitioning both sides across the cluster so matching keys always land on the same
machine). These are the most powerful options on the list, and also the most expensive to build correctly.

**What to expect:** we'd only recommend investing here if real usage shows the options above hitting a genuine
wall - most real joins have *some* filtering or size difference between their two sides that the cheaper options
already exploit well.

---

## 3. Our recommendation

Do the options in roughly the order listed above, and pause to check real usage after spreading joins across the
cluster (§2.4) before committing to the most expensive, most specialised options (§2.6). The first two options
(disk spilling, smaller-side selection) are about **reliability and headroom** - they stop today's guardrail from
being the practical ceiling. The next two (unfiltered-join reduction, cluster spreading) are where **actual
queries get faster**, not just safer. The last two (automatic strategy selection, exotic big-both-sides handling)
are refinements best reserved until there's a concrete, measured need for them.
