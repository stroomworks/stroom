# Plan B: making the point-in-time read explicit

**To:** the Plan B maintainer
**From:** Stroomworks — Enterprise Floor Mapping
**Date:** 2026-09-14
**What this is:** a design proposal for `TemporalStateDb`, and a disclosure of a local change we
have already made to it that we would like your view on.
**What we are asking for:** your comments, and a decision on whether any of this belongs upstream.
Nothing here is urgent and nothing depends on a quick answer.

> This updates an earlier note of ours, `planb-snapshot-read-proposal.md`. You do not need to have
> read it. The substance is unchanged; what has changed is that we have since implemented something
> ourselves, by a route that note explicitly argued against, and we would rather tell you than let
> you find it in a merge.

---

## In three sentences

`TEMPORAL_STATE` can answer "what was the state of key `k` at time `T`" — `getState` does exactly
that — but has no way to answer it **for every key at once**. We added one locally, and triggered it
by inferring intent from the shape of the time terms in the expression, which we now think is the
wrong design for reasons set out below. We would like to replace that with an explicit request, and
to know whether you would want it upstream or would rather we kept it to ourselves.

## The gap, in Plan B's own terms

`getState(TemporalStateRequest)` seeks to `(key, T)`, iterates in reverse and takes the first entry
whose prefix still matches — the latest entry at or before `T`, for one key. That is the right
answer and the right implementation.

There is no equivalent across keys. A caller wanting "the state of everything as at `T`" has three
options today, none good: call `getState` per key, which needs the key set in advance and is N round
trips; fetch history and reduce client-side, which transfers everything; or bound the query to a
recent window and reduce that, which silently drops any key whose last entry predates the window
even though its state is still current.

We are building a floor map — entities moving around a plan, scrubbed through time — so "where is
everyone at `T`" is the only question we ask of the store, three times a second.

## What we did, and why we think it was wrong

On 2026-08-27, in our fork only, we added to `TemporalStateDb` a `searchAsAt` path and to
`PlanBSearchHelper` a `getQueryTime` / `removeTimeTerms` pair. `search` now lifts a time term out of
the expression and, if it finds one, reduces to one row per key instead of returning history.

The trigger is **inferred**: any `EQUALS`, `<` or `<=` on the time field means "snapshot", and
`removeTimeTerms` then discards every time term including the lower bound. We chose that because of
a constraint you will recognise — `StateSearchProvider.createResultStore` builds
`new ExpressionCriteria(query.getExpression())` and discards everything else on the `Query`, so the
expression is the only caller-supplied channel that reaches the DB. Putting the signal anywhere else
meant adding plumbing, and we did not want to change your interfaces.

We now think we bought the wrong thing with that restraint. Three concrete problems, all live in our
tree:

**Ordinary time-ranged queries change shape.** `ResultStoreManager` injects `EffectiveTime < to` for
any data source exposing a time field, and most dashboard time presets set a `to`. So a dashboard
asking for "all state changes today" would silently start returning one row per key. No error, no
warning, fewer rows.

**The semantics depend on how the date was typed.** We parse the bound with
`DateUtil.parseUnknownString`, which handles epoch millis and ISO-8601. Anything else throws, we
swallow the exception, and the query quietly falls back to full history. Whether a query is a
snapshot therefore depends on the user's date format — which also means our own trigger does not
fire for the relative presets people actually use.

**It reinterprets bounds it should honour.** Because the term is lifted and then stripped, a lower
bound is discarded rather than applied. `where EffectiveTime > X and EffectiveTime <= Y` does not
mean what it says.

None of this is Plan B's fault. It is what happens when one channel carries two intents and the
callee has to guess which was meant.

## What we would like instead

**An explicit request, and ordinary time predicates left alone.** Two capabilities, plus a third
that is smaller and separable:

**1. Snapshot with a staleness tolerance.** One row per key, the latest at or before `T`, and
optionally **only if that latest is no older than `D`**. The tolerance is the part we care about
most: our map should show where someone was if they were seen in the last few hours, and should stop
showing them otherwise. Without it, a key that was written once in 2024 is on the map for ever.

We would express it, if it were a grammar clause, as

```
from people_events as at '09:45' within 3h
```

with `within` requiring an `as at`, and meaning `[T − D, T]` anchored on the snapshot instant rather
than on `now()` — the two differ precisely when someone scrubs back, which is our common case. An
absolute form (`since '06:45'`) lowers onto the same criteria.

**2. A range read that is honoured literally.** "Every row with `A ≤ time < B`" — what a time
predicate already looks like it means. We need this for a density histogram over a visible window;
today we pass no range at all and read the whole store, because any upper bound would turn the query
into a snapshot.

*To be clear about what we are and are not asking here:* we know a honoured range would bound what
crosses the wire, not necessarily what is scanned. Your own `TODO` at `PlanBSearchHelper:61` —
*"it would be faster if we limit the iteration to keys based on the criteria"* — is about exactly
that, and for `TEMPORAL_STATE` it looks hard to us: the key is `<prefix><time>`, so time is the
suffix and a time range cannot narrow an LMDB key range without a second, time-ordered index. We are
not asking for that. Bounding the transfer is the part we need; if the scan stays full-store, that
is fine at our volumes.

**3. A store time extent — smaller, separable, and possibly not worth your trouble.** `MIN` and
`MAX` of the effective time across a store, in one call. We use it to offer a "show all data"
control on a timeline: fit the visible range to what the store actually holds.

Our SQL-backed store answers this with a single aggregate. For Plan B we currently infer it from
whatever rows a query happened to return, which is wrong as soon as that result is capped. We had
assumed first-and-last-key would do it and it does not, for the reason above — the first key is the
alphabetically-first entity's *earliest* entry, not the store's earliest. So it would want either a
key scan or a maintained pair, and `TraceStats` suggests you already have a pattern for the latter.

We raise it because it is the same class of question — something a temporal store knows and cannot
currently be asked — but it is independent of the read-mode question and we would not want it to
complicate that decision.

**The mechanism matters less to us than the explicitness.** A reserved field the store consumes
(`where StateAt = …`), a clause, or a request object beside the expression would all do. The clause
reads best; the request object avoids a grammar change but needs the plumbing mentioned above. We
have no strong preference and would follow yours.

## One result you may find useful either way

Where a tolerance is given, **filtering rows first and reducing afterwards gives the same answer as
reducing first and discarding a stale survivor.** Let `L(k)` be the latest row for key `k` at or
before `T`:

- if `L(k) ≥ F`, then `L(k)` lies in `[F, T]` and is also the latest row *in* that window — both
  orders return the same row;
- if `L(k) < F`, no row for `k` exists in `[F, T]` at all, since any such row would be ≤ `T` and
  later than `L(k)` — both orders return nothing.

So an implementation is free to apply the floor at the retention test inside the single forward pass,
which costs one comparison per entry and no extra structure. That is where we would put it.

## What we are asking

1. **Does the gap look real to you** — is "state of everything as at `T`" a question you would want
   `TEMPORAL_STATE` to answer, or is it deliberately out of scope? (And separately, the time extent
   in (3) — a yes or no on that one would help us decide whether to work around it.)
2. **If it is in scope, does the staleness tolerance belong in the store** or is it a caller's
   concern? We think it is a store question — "as at `T`, discounting anything not confirmed since
   `F`" is a normal thing to ask of a state store — but it is your API.
3. **Which mechanism would you prefer**, given `ExpressionCriteria` is currently the only channel
   into the DB?
4. **Would you want any of this upstream?** We are happy either way; we would rather contribute it
   than maintain a divergence, but we recognise the design is yours to set.

## What we will do regardless

We will mark our local changes with the merge markers this repo uses for fork-local edits to
upstream files, which we should have done at the time and did not — so that if you do add something
here, the conflict is visible rather than silent. And we will not change the inferred trigger into
anything else in your files until we have heard from you.

Happy to talk it through, or to prototype whichever shape you prefer so you can see it before
deciding.
