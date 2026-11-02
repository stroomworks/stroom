# Design 04 — The processing flow: how a stream waits for what it needs

> **Archived.** This is the design as it was written and ruled, kept for its reasoning and history. The current design is [the design set](../README.md); code comments that cite this document by number and section still resolve here.

*Written 2026-10-04, from the owner's rulings of the same day. Design 01 describes what the stage
decides; design 02 the scenarios that force it; design 03 the order the work is done in. None of them
says how a stream gets to the stage, or what becomes of it when the stage is not ready for it. That is
this document. It is a new mechanism for executing AI pipelines, and the only one of these documents
whose work lands substantially outside `stroom-shapeshifter`.*

> *Superseded in part by design 06, phase J (2026-10-07 and 08).* The pipeline is the unit of learning
> (A104): a **learner** is one Shapeshifter AI element in one pipeline (A114), and what serves it is its
> **binding** (A117, A124, A140). Shapes, the learning key, the shape signature, the routing table and its
> selectors, pinning (A138) and the ledger (A131) are gone; a stream waits as a deferred task, which a
> promotion wakes by its pipeline (A129). Where this document says a shape, read a learner; where it
> says a rule, read a binding. It is kept as the record of how the design arrived where it is.

---

## 1. What is wrong now

A processing task is pushed at a pipeline and has two ways to end: it completes, or it errors. The stage
needs a third — *not yet* — and in its absence it has been built out of the two that exist.

**The lock is an error.** `Stage.recording` claims a shape with `attempts.opened`, a unique key on
`(doc_uuid, claim_key)` that admits one learner. The claim itself is sound and cheap. What is not sound
is the consequence of losing it: `sentinel(...)` writes an error stream and a ledger row, and the
backlog is released later by `PipelineReprocessing`, which creates a reprocess filter at priority 1 over
`PARENT_ID IN (…) AND PIPELINE IS …`. So a stream that merely arrived early becomes an error, a ledger
row, and a second pass through task creation. In deferred mode that is the fate of *every* stream of an
unlearned shape.

**Learning holds a processing thread.** In inline mode the node that wins the claim carries the whole
conversation — several model calls, each as slow as the endpoint is — inside a processing task, with the
stream's meta locked for the duration. At any width of thread pool this is what exhausts connections and
makes tasks look stuck.

**Scoring is a lagging trigger.** `Stage.serve` already judges every stream it serves. What it does with
the judgement is the problem:

```java
final OptionalDouble rolling = shapes.scored(doc.getUuid(), shape.id(), judged.score(), …);
if (rolling.isPresent() && rolling.getAsDouble() < doc.getRelearnThreshold()) {
    shapes.markForRelearning(…);
}
return emit(doc, new Bound(rule), shape, input, rule, judged, List.of());
```

It emits whatever the transform produced, records that the shape is drifting, and waits for a later
stream large enough to relearn from. A record the transform has never met is translated badly and the
output kept.

**None of this is a bug.** It is what you get when the only way to make a stream wait is to fail it.

---

## 2. What a shape is, and the two kinds of failure

**A shape is a feed and a type** (A49). All streams of one feed and type are served by one learned
pipeline. Event types *within* a stream are XSLT match treatment: the transform grows a match per
record type as it learns, and nothing outside the transform identifies them. There is no record-level
signature, no coverage set, no novelty measure — a record the transform cannot translate says so by
scoring low, and that is the whole of the signal.

The learning key stays configurable, so an operator may still split a feed by a receipt header where one
feed genuinely carries two unrelated things; feed and type is what it means by default and what the
mechanism below assumes.

**The two stages of a chain fail differently, and only one of them is an error** (A52).

| Stage | Cannot do it | What happens |
|---|---|---|
| Transform | A record type it cannot translate well | The session learns a match for that type whose output is a marked Unknown (A51, A53). The stream then scores and is served. A translation that has the budget to finish always terminates; one that runs out waits for a person (A65) and errors nothing. |
| Parse | Records cannot be got out of the stream at all | A real error. There is nothing to score, nothing to learn against, and nothing to serve. The stream errors and says why. |

A parse cannot be learned when the session's parser candidates are spent without meeting the
document's own input-coverage and yield gates (A54) — no new setting, the thresholds an operator
already sets decide it.

**Two different things run out, and they must not be confused.** A *step* of the plan has its own
number of candidates, and spending them is the plan working as written: it takes the step's transition
— to the exhaustion step for a transform, to giving the shape up for a parse. The *document's* budgets
— `maxAttempts`, `attemptBudgetMs`, `tokenBudget` — bound the whole session, and when one of those runs
out the session has not reached a conclusion at all: it parks for a person (A65), whichever stage it
was in. So a parse is given up because its step was tried in full and failed, never because the
session ran out of money part-way through trying.

So "given up" survives for parse failure alone, or for a person saying so. Everything that reaches
records ends in output or waits for a person — never in an error — and Unknown events are the honest
residue of a session that tried everything else.

---

## 3. The task decision

Every task does the same cheap thing: resolve the feed and type, consult what is known, and take one of
three outcomes — emit, defer, or error.

| What is true | Outcome |
|---|---|
| A deferral row for this stream names the transform it already failed against, and that is still the transform in force | **Defer without running the transform.** One lookup: no transform, no model, no output. If no attempt holds the shape — the last one lapsed or failed, a parked one still holds it — open one, or the stream would wait on nothing. |
| A rule serves this feed and type, and every record clears the bar | **Emit.** The ordinary case; no model, no state written beyond the rolling score. |
| A rule serves it, and any record falls short | **Defer.** Emit nothing. Write the deferral row against the transform in force — one small row, and nothing about which records failed. Open the session if no attempt holds the shape. |
| No rule, and no attempt holds the shape | **Defer**, and open the session with this stream as its subject. |
| No rule, and an attempt holds the shape | **Defer.** |
| The shape is given up — a session found the parse unlearnable, or a person gave it up | **Error the stream**, with the reason. (With the ledger saying so until design 06 A131 removed it.) |

Learning never happens here. A session is the worker's, and the only thing a task does about it is open
it and stand aside. Nor does a task decide that a parse is hopeless: that is a session's conclusion
(§4), and what a task reads afterwards is a shape that has been given up.

**The bar** is per record and is the gates, not the weighted total: a record clears it when every gate
scorer that applies to a record passes on *that* record. The weighted total stays what it always was —
the promotion gate's measure over a whole candidate. Gates alone are the bar (A55): they are what
"this record did not translate" means, and a per-record weighted threshold would be a new setting
holding streams over output that is merely mediocre.

**An Unknown has to be able to clear the bar, and today it cannot.** `ExtractionQualityScorer` counts a
record whose `EventDetail` is `Unknown` against the candidate — rightly, since that is the degeneracy
trap it exists to catch — and the demo document runs it as a gate with `allowUnknownEventDetail` off.
Left as it is, a session that ends in Unknown matches (A51) produces a stream that still fails, and A50
never releases it. The corpus cannot settle this by itself either: it can say that *this* record's
accepted output is Unknown, but tomorrow's record of the same type has different values and no entry.
So the admission is recognisable **in the output** (A53): the exhaustion step asks for an Unknown event
carrying a defined marker that says it is a learned admission — a named `Data` item inside
`EventDetail/Unknown` (A62), so the event explains itself to anyone reading it and to any processing
downstream — and the quality scorer accepts a marked Unknown where it still refuses a bare one, and
leaves that one `Data` item out of its count of untyped elements. The trap stays closed in both directions — a bare Unknown
is refused as it always was, only the plan's exhaustion step may ask the model for the marked form, and
the corpus stops a record that was once translated from becoming Unknown later.

**The transform in force** is the rule's current snapshot. A session improves a transform **in place**
(A58): one fragment pipeline and one stylesheet per shape, growing matches as it learns, which is what
"event types are match treatment" looks like as documents and what a person who has one open would
want. A fragment is several documents, each with a version of its own, so their versions cannot be the
stamp; and as-processed reprocessing (design 01 §7.3), which until now could name a fragment, must now
name a state of one. One thing answers both: every promotion keeps an immutable **snapshot** of the
fragment's configuration (A61), and the snapshot's id is what a rule points at, what a deferral row is
stamped with, what `Outputs` records beside the pipeline, and what an as-processed reprocess runs.

### Deferral is version-stamped

A deferred stream records the snapshot it failed against. On its next attempt:

- the rule still points at that snapshot → defer again immediately, no transform run;
- the rule has moved on → score once more.

A promotion therefore re-scores every deferred stream of that feed and type exactly **once**. The work
per stream is bounded by the number of promotions it lives through, with no counters, no backoff
tuning, and no gate on the shape. Streams whose records all score keep flowing while a session runs,
which costs nothing in correctness: Stroom does not guarantee the order in which streams are processed,
so nothing downstream can be relying on one.

### Streams of any size

A stream may be tens of gigabytes, and two things the code does today cannot survive that: the
supervisor element reads the whole stream into a `String` ("the whole stream is read", `read(inputSource)`),
and a served run is held as an `EventList` of the whole output before any of it is fired. Both go.

- **Scoring streams.** The chain already runs record by record — the learned boundary is a
  `SplitFilter` and the transform is given one record at a time — so each record's output is scored as
  it is produced. The first record short of the bar ends the run: the task defers, and the rest of the
  stream is never read. A stream that passes is read exactly once.
- **Output is written as it is made, and discarded if the run defers.** An output target is locked
  until the task that opened it ends, and `Target` can logically delete it. So a serving run writes
  straight to its output in one pass, in constant memory, and a deferral deletes the target before
  anything could have seen it. This replaces holding output back until the verdict: at this size there
  is no holding anything back, only not committing it.
- **The acceptance test is one full pass, not one per question.** Candidates are judged on the sample
  — a prefix bounded by `sampleSizeLimit`, as today — and the held stream is run in full only for a
  candidate that has passed the sample. A record the full pass finds short of the bar is small, and it
  becomes the next question's sample; the loop is bounded by the number of record types in the stream,
  not by its length.
- **The corpus takes records, never the stream.** A session's entries are the records it put to the
  model — from the sample and from what the full passes turned up — so a first stream of ten gigabytes
  contributes the handful of records that were actually learned for.
- **The worker reads the held stream from the store the same way**, streaming, through `Inputs`.

---

## 4. The session

A session is opened by the task that met the record it could not translate, and carried by the worker.

**The held stream is the acceptance test** (A50). The session is finished when the stream that triggered
it scores in full — every record at or above the bar, by whatever mixture of real translations and
learned Unknown matches the model arrived at. That is the exit condition; no coverage criterion and no count of candidates decides that the work is
*done*. The document's budgets — `maxAttempts`, time, tokens — still bound what a session may *spend* (§2). A session
whose budgets run out before the stream passes **parks for a person** (A65): it keeps its lease, its
streams go on waiting, and the Supervisor shows what it stopped at. Nothing is baked as Unknown because
money ran out — an admission is what the plan's exhaustion step produces after the trying is done, not
what a budget produces by ending it. A person may answer the question it stopped at (A28), give it more
to spend, or send it to the exhaustion step.

Parking for a person is not the parking the code has. `AWAITING_MODEL` is an attempt any worker may
take, and `opened()` lapses an attempt whose expiry has passed so that a dead node lets the next in. An
attempt parked for a person needs a status of its own that the worker leaves alone, and a lease that
does not lapse — otherwise the next task of that shape takes it and starts spending again, which is
the loop the budget existed to stop.

- **One stream at a time** (A69). A session learns from the one stream that opened it, and every
  waiting stream is tried again when it promotes. Ten streams arriving together on an unlearned feed
  are one session and ten deferral rows; if six still fall short afterwards, one of the six opens the
  next session and the other five wait again. The rounds cost the waiting streams a transform and a
  score each and no question to the model.
- **Evidence is worked out by the session, not stored by the tasks.** Which records of a stream fell
  short is known to a task for as long as it takes to decide to defer, and is then thrown away. The
  worker finds it again for the one stream it is learning from, by running the transform in force over
  that stream as its first act — `Judged.spans` carries a `TextRange` per record and `Inputs.textOf`
  reads a record back by one. Recording the failing records of every waiting stream would be storing
  a great deal about streams nobody is learning from, all of it stale the moment the transform moves.
  The session learns *every* failing record in its stream, so a stream that shows five unseen types is
  handled once rather than five times.
- **Pace.** A node that takes an attempt carries the session to its end in one pass (A64): it goes on
  asking until the held stream clears the bar, or its budgets are spent and it parks for a person. The job's schedule decides only how soon a new session is picked up. Today
  `DeferredWorker.advance` moves each waiting attempt one step per pass, which was right for an attempt
  that might park at any question and is wrong for one whose end is a stream passing.
- **The lease** is the attempt row, unchanged: one open attempt per `(doc, shape)` (A45), with its
  expiry, its turns, and its visibility in the Supervisor.
- **Which node, and what if it dies.** Learning is an ordinary scheduled job — "Shapeshifter AI Deferred
  Learning" — so it runs on every node the job is enabled on, and a session belongs to whichever of
  them claims its attempt first; `Attempts.claimed` is one conditional update, so two cannot. No node is
  *the* learning node, and one that never comes back takes nothing with it: the attempt, its turns and
  the stream are all in the database and the stream store. What the code does today with a session
  whose node has died is weaker than it should be, in two ways this design has to put right. The worker
  asks only for attempts `AWAITING_MODEL`, so one left `IN_PROGRESS` by a dead node is never picked up
  by a worker — although `claimed` already allows a lapsed one to be taken; it is recovered only when a
  task of that shape next calls `opened`, which marks it abandoned and starts a **new** attempt from
  nothing, with the turns already paid for left behind. And the lease is set once, to the attempt's
  time budget plus a grace period, and never renewed. So: the worker also takes `IN_PROGRESS` attempts
  whose lease has lapsed and **resumes** them by the re-walk A45 already built for a parked attempt;
  and a node renews its lease as each turn completes, so a live session is never mistaken for a dead
  one and a dead one is noticed within one turn's allowance rather than a whole budget's.
- **Regression** is held by the corpus: a candidate must not do worse on any record already accepted
  than what was accepted for it. This is what makes a new event type safe to learn, and it is the one
  prerequisite this design adds to work already listed — §12 item 9's durable store, today in memory
  only, with `RegressionSet.Accepted` carrying `(input, score, targets)` and no produced output.
- **Exits.** A promotion of the improved pipeline, or of the pipeline with its new marked Unknown
  matches; both change the transform in force and wake the deferred streams. A session out of budget
  does not exit: it parks, and holds. A parse that cannot be learned is the
  only exit that errors anything: the session gives the shape up, and the streams waiting on it error
  with that reason.

### 4.1 What is recorded

Three things are written, and the first of them already exists.

**The session** is the attempt row and its turns, which exist: `shapeshifter_attempt` holds the document,
the shape, the feed and type, the stream it was raised on, the node, the status, the decision
and the lease's expiry; `shapeshifter_turn` holds every question as it was put, the answer, who answered
and what the answer scored. A28 built them, the Supervisor reads them, and a session needs nothing more
to be readable or resumable.

**What a waiting stream leaves** is its deferral row and nothing else: the stream, the task and filter
that will be woken, the transform it failed against, and since when. No record of which of its records
failed, and none of its text. A session's own stream needs no more either — what it found wanting is
in the turns, as the questions it put.

**The corpus** is the regression record, per rule, and it is the one place where copying is unavoidable:
the source stream may be deleted by retention long before the next session, and a regression set that
loses its inputs tests nothing. So each accepted record is kept as the pair *(input record, the output
it was accepted with)*, with whether that output was a translation or a marked Unknown, the score it
achieved, the attempt that accepted it, and — where the plan
settled targets — the target it was held to. That is A18's regression stream per rule, inheriting the
source feed's classification and a retention capped by the feed's, with a row per entry naming the
stream and segment so the set can be read without scanning it.

Today `RegressionSet.Accepted` is `(input, score, targets)` where `input` is the **whole stream** — every
call site passes `input.data()` — held in memory only, and §12 item 9's durable form is deferred. Per
record is what this design needs, since the bar is per record and a stream of a million records is not
an entry anybody can afford a hundred of. What is kept (A56) is **only the records a session had to learn for**, each with the output it was
finally accepted with — never a whole stream, and never a record that already translated. Those are
exactly what a later session could break; a record that passed without learning either was learned in
an earlier session, and is in the corpus from then, or has nothing a session could change. The
document's existing `regressionCap` bounds a rule's entries (A63). An entry is a record, and a record
is small: the stream it came from may be tens of gigabytes and contributes only what was learned for. Both gaps matter, for different reasons. Without durability a session two months later has nothing to
regress against. The stored **output** is not what the gate compares — under A66 the gate re-runs a
candidate over each entry's input and asks whether the result clears the bar and whether a translation
has become an Unknown, for which it needs to know only which of the two the entry was. The output is
kept because it is the record the owner asked for: what each event that triggered a session became, so
that a person can see what a later session changed and judge whether "no worse" was true. A candidate is held to every entry in the corpus, and held to **no worse** (A66), not to the same:
every entry's new output must clear the bar, and a record once translated may never become Unknown.
Output may otherwise change — a deliberate improvement of a rule (A46) changes it on purpose — and a
marked Unknown may become a real translation without anybody agreeing, since that is the corpus getting
better. Exact equality would have refused both.

A marked Unknown does not ask to be improved, though (A68). It clears the bar, so records of that type
flow, and nothing retries them on its own: turning an admission into a translation is a person's
*improve* or hint in the Supervisor (A46), not tokens spent on a type already tried.

---

## 5. What `stroom-processor` gains

Four changes, all general, none of them about AI.

### 5.1 A task may end as deferred

The signal has to travel out of a pipeline element, and today **two layers swallow it**. In
`AbstractProcessorTaskExecutor`, `handleProcessingException` turns any `IOException` or
`RuntimeException` into `outputFatalError(e)`, and `exec` wraps the whole of processing in
`catch (final Exception e) { outputFatalError(e); }`. That is why the present mechanism had to make
waiting into an error: an exception from the stage *cannot* reach
`DataProcessorTaskHandler`, which is the only place that decides a task's terminal status.

So the deferral is an exception with a path cut for it, in three places:

1. A dedicated unchecked exception, declared in `stroom-processor-api` so that an element may throw it
   without depending on the implementation, carrying the reason. The throw site in this feature is
   `Supervision.supervise`, at the point where a decision with no bindings is today logged at
   `Severity.ERROR` — the one line that makes a waiting stream an error. Throwing is what stops the pipeline mid-stream; nothing
   downstream of the stage should keep working on a stream that will not be emitted.
2. `handleProcessingException` and `exec` let that one type through instead of calling
   `outputFatalError`, as they already let `Error` through. It is not an error and must not reach the
   error receiver, or the stream acquires fatal markers and an error stream is written — which is the
   behaviour being removed.
3. `DataProcessorTaskHandler.exec` catches it where it catches the rest, and its `finally` gains a third
   outcome beside `COMPLETE` and `FAILED`.

**Whatever has been written is discarded when it is thrown.** A serving run writes its output as it
goes (§3, *Streams of any size*), so by the time a record falls short an output target may be open and
part-written. It is locked, so nothing has seen it; the deferred path logically deletes it. With
nothing logged to the error receiver there is no error stream either. A scenario holds the deferred
path to leaving nothing visible (§8) — including the empty stream a destination that writes a header
on `startStream` would otherwise leave — rather than this being taken on trust.

### 5.2 Deferred is a status, not a predicate on every query

The obvious shape is a `next_attempt_ms` column that every query filtering `status = CREATED` must also
honour. Reading those queries, that is the wrong trade. `ProcessorTaskDaoImpl.getTaskAvailability` —
what tells a worker which of its filters have work — is
`SELECT filter_id, MIN(id) … WHERE filter_id IN (…) AND status = CREATED GROUP BY filter_id`, one index
descent per filter by design, and the claim is `… AND status = CREATED ORDER BY id LIMIT n FOR UPDATE
SKIP LOCKED`. Adding a time predicate to both puts a range condition where the index expects equality,
and leaves a filter whose every task is asleep looking available on every fetch.

Instead, a deferred task **leaves the `CREATED` pool**:

- a new `TaskStatus.DEFERRED` with primitive value **30** — no status is compared by order, so the
  number is only an identity, and 30 is above every value this enum has ever carried (0, 1, 2, 3, 10,
  22, 99 across its whole history) rather than in the gaps at 4–9 and 11–21, which were left on purpose
  and which a row written by an older release might still hold — and **no new column**: the time a task was deferred is its `status_time_ms`, and the
  minimum time before retry is a configured delay, as it is for the queue sweep;
- a sweep that is `sweepQueuedTasks` with one status changed —
  `UPDATE processor_task SET status = CREATED, … WHERE status = DEFERRED AND status_time_ms < :before` —
  served by the existing index `processor_task_status_time_ms_status_idx`;
- **queueing and claiming are untouched.** The master queue, `getTaskAvailability`, the claimer,
  `EligibleFilters`, the fetch backoffs: none of them change, because a sleeping task is simply not
  `CREATED`. The only new work in the hot path is none.

The cost is that a retry time is honoured at sweep granularity rather than exactly, and is the same for
every task rather than chosen by the deferrer; with the version stamp making a premature retry one
lookup, and the wake making a promotion prompt, neither matters. One configured delay is the ruling (A59); a per-task time is a column to add later if something needs one.

**What the existing statements do with the new status**, read rather than assumed: the dead-task reap
takes `PROCESSING` tasks with a stale status time, so a deferred task is not reaped;
`ACTIVE_TASKS_STATUS_CONDITION` is `QUEUED, ASSIGNED, PROCESSING`, so it is not counted as running;
the logical deletes take everything not `DELETED`, `COMPLETE` or `FAILED`, so deleting a filter takes
its deferred tasks with it. The one that needs a decision is task creation, which bounds itself on
`countTasksForFilter(filter, CREATED)`: deferred tasks are not counted, so creation does **not** stall
behind a session. That is the right default — a filter over several feeds keeps creating for the feeds
that are fine — and it means the deferred population is bounded by what arrives, not by a budget.

### 5.3 A wake, and visibility

**The wake** is the same statement with a narrower `WHERE`: the AI, on promotion, moves its own
deferred tasks back to `CREATED` for the filter and meta ids it knows are waiting on that shape, rather
than letting them sleep out their retry time. Ordinary FIFO by task id then processes them oldest first —
after `skipEmptyFilterFetchDuration`, where the node last found the filter empty, since a wake changes
the rows and not the node's memory of them.

**Visibility**: deferred counts per filter in the processing screens and in sysinfo, and a maximum
deferral age past which a task is **raised, not failed** (A57): flagged in the Supervisor and counted,
and still waiting. A slow model or a paused session must not turn into data somebody has to reprocess
by hand; giving a shape up is a person's decision. So a feed stuck behind a session that never finishes cannot hold data *silently*, and this is what
A24's error mode becomes. The *reason* a task waits is the deferrer's to hold — here, the deferral
row — since the task row gains no column.

Task *creation* is left exactly as it is. `ProcessorTaskCreatorImpl.createTasksFromCriteria` scanning
`tracker.getMinMetaId()` to `getMaxMetaId(...)` is the right shape, and `getMaxMetaId` already knows how
to wait without failing anything — feed dependencies hold its window back and the progress monitor
reports `WAIT_FOR_READY_STREAMS`. Nothing here needs a second task table, a second creator or a second
notion of a tracker.

**Worker claiming stops being experimental.** This flow is a per-node pull, so `ProcessorTaskClaimer`
and `stroom.processor.claimTasksOnWorker` move from an off-by-default experiment to the mode AI
pipelines require. The master queue's cluster-wide guess — `getMaxConcurrentTasks()` sweeping every
enabled node — is the wrong instrument for a document whose work is mostly deferred.

---

## 6. What the stage gains, and what it loses

**Gains.** Per-record scores out of the scorers at serve time: the scorers that are inherently per
record — schema conformance, extraction quality and business rules, which are the three that exist;
`ERROR_LOAD` and `EVENT_CLASSIFICATION` are scorer types with settings and no scorer — reporting the
record's own outcome beside the mean, so the bar is per record and the evidence is a record rather than
a stream. A deferral table — `(doc_uuid, meta_id, filter_id, task_id, feed, type, failed_against, reason,
first_deferred_ms, attempts)`, the filter and task read from `StreamProcessorHolder` — which is what the
wake addresses and what the Supervisor shows as a backlog. It is the only thing a waiting stream writes.

**What it does not need.** A state machine per shape. The version stamp removes the reason for one: the
lease is the attempt row, which already exists and already means "a session is open", and everything
else a task needs is in the deferral row it reads first. Today's implicit state — `reasonGivenUp`,
`relearnReason`, `draftAwaiting`, the rolling score — is left exactly as it is.

**Loses.** The sentinel as a lock, and with it most of the ledger's traffic. `PipelineReprocessing` as a
release mechanism — it survives for deliberate remediation, which is what A12 always meant. Error mode
counting sentinelled streams, since nothing is sentinelled to make it wait. The rolling score and
`relearnThreshold` as a trigger: they become what A46 wanted them for, the Supervisor's "could this rule
be better?" list.

Execution mode goes from the document and the Settings tab (A60), as `sampleRedaction` did and for the
same reason: once learning never runs on a processing thread, the setting would offer a choice that
changes nothing. Learning in stepping mode is its own discussion and gets its own design.

---

## 7. The plan, in phases

As design 03 has it: a phase ends on a test or a run, not a judgement, and the next does not start until
it has. Phases 1, 2 and 3 do not depend on one another and may be built in any order or together; phase
4 needs all three. Nothing changes for a stream until phase 4.

### Phase 1 — A task may be deferred *(`stroom-processor`, `stroom-core`; nothing about AI)*

- `TaskStatus.DEFERRED`; `ProcessorTaskDao` gains *defer* (to `DEFERRED`, node cleared, status time
  now), *sweep* (the `sweepQueuedTasks` shape) and *wake* (to `CREATED` for a filter and a set of meta
  ids); the sweep scheduled beside the queue sweep; `ProcessorConfig` gains the retry delay.
- The exception in `stroom-processor-api`; `AbstractProcessorTaskExecutor.handleProcessingException`
  and `exec` let it through; `DataProcessorTaskHandler` catches it and its `finally` writes the third
  outcome, deregistering the heartbeat as it does for the other two.
- The status shown wherever task statuses are listed, and counted per filter in sysinfo.
- **Exit:** processor tests in both master-queue and worker-claim mode — a task whose element throws
  the exception ends `DEFERRED`, leaves no output stream, no error stream and no markers, is not
  reaped, returns to `CREATED` after the delay or on a wake, and then completes. A filter deleted with
  deferred tasks takes them with it.

This is the part that needs review outside the feature, and the only part that touches a table every
installation has. It adds a status value and no column, so its migration is nothing.

*Built 2026-10-05.* `TaskStatus.DEFERRED` (30); `DeferTaskException` in `stroom-processor-api` with an
`unwrap` that finds a deferral under the `ProcessException` and `XPathException` the pipeline wraps
things in; `sweepDeferredTasks` and `wakeDeferredTasks` on the DAO and its mock; the sweep in
`ProcessorTaskReaper` in both modes, on `stroom.processor.deferredTaskRetryDelay` (5m by default);
the third outcome in `DataProcessorTaskHandler`. The path out of a pipeline turned out to run through
**five** places that swallow exceptions, not the two §5.1 counted: `AbstractProcessorTaskExecutor` has
three (`exec`, `process`, and the stream loop's `handleProcessingException`), `AbstractParser`'s
catch walks a cause chain and logs a fatal error, and both `MultiWayProcessor`s — one in
`ProcessorFactoryImpl`, one in `SimpleProcessorFactory` — run each branch of a pipeline on another
thread and report its exception as a fatal error without rethrowing anything, so a deferral thrown on
a branch is now kept and thrown by the calling thread once every branch has finished.
`TestDeferredTask` in `stroom-app` is the exit: a parser that defers each stream once, in both modes,
with no child stream of any kind after the deferral and one output stream after the retry, by the sweep
and by a wake. (One thing it taught about the test rather than the flow: `XMLWriter` writes a record
when an element closes at depth one, so a document with only a root writes nothing.) The DAO tests
hold the sweep, the wake, and what the reap and the deletes do with the status; the reaper test holds
the delay in both modes. Sysinfo's claim view counts the deferred population across the cluster, with a note saying what returns it.

*Its own audit found four, 2026-10-05.* The one that mattered: the executor deletes a stream's earlier
output from the same pipeline whenever a task ends, so a **reprocess that deferred would have deleted
the output it was going to supersede and replaced it with nothing** — the deferred path now leaves
that deletion to the run that completes, and a scenario holds it. The others: the handler recognised
only a bare deferral and not one wrapped by an executor other than the pipeline's; a deferral thrown
while a parser was starting, rather than parsing, was still a fatal error; and the mock DAO compared a
boxed id with `==`. One limit recorded rather than fixed, because phase 4 owns it: a deferral
guarantees that the branch which deferred wrote nothing, and a pipeline whose *other* branches write —
a raw writer beside a parser — is not yet held to leaving nothing; the discard of §5.1 is where that
is paid.

### Phase 2 — The score is per record *(the feature; no behaviour change)*

- `SchemaConformanceScorer`, `ExtractionQualityScorer` and `BusinessRulesScorer` report each record's
  outcome beside the mean they report now; `Judged` carries them against the spans it already has.
- The supervisor element stops reading the whole stream into memory: input is streamed and a serving
  run is scored record by record as its output is produced, stopping at the first record short of the
  bar. The whole-output `EventList` goes with it.
- The deferral table, written in **shadow**: `Stage.serve` records which streams
  it *would* defer — against the fragment as it is named today, snapshots not existing until phase 4 —
  and goes on emitting as it does today.
- **Exit:** a scenario in which a stream of known and unknown record types yields exactly the failing
  records, by span, with the gate that failed each; and a shadow run over the six formats of design 03
  §3 whose would-defer rows are read and agreed before phase 4 turns them into behaviour.

*Built 2026-10-05, all but the streaming.* `Score` carries `failingRecords` beside its value, zero-based
in output order: the schema scorer names the records the validator failed and the ones it never reached,
extraction quality names a record that says Unknown where the document forbids it or lacks a required
field, business rules names the records an assertion fell short on (a transform's own messages are laid
at no record). `Verdict.failingRecords` is the union over the gates (A55) and `Judged.failingRecords` is
the last step's, since that is what is emitted. `Deferrals` is the table of §6 — `shapeshifter_deferral`,
one row per stream and document, upserted on the unique key with `first_deferred_ms` kept and
`attempts` counted; `task_id` and `filter_id` are what phase 4 wakes, and `Input` now carries them from
`StreamProcessorHolder`, null in the stepper. `Stage.shadow` writes a row for a served stream with
records short of the bar, against the rule's fragment as named today, and the unknown-shape path writes
one with nothing failed against; both go on sentinelling and emitting exactly as before. Scenario 52
(design 02 §5) is the exit's first half: the records by index, the gate that named each, the text by
span, the rows; scenario 51 is the shadow run over the four formats of the pack, whose table is empty.

*Its own audit found three, 2026-10-05.* The schema scorer keyed an error raised before any record
began — the validator failing to start — as record zero, which the zero-based account would have named
as record minus one; such an error now names no record. A shadow row, once written, was never taken
off, so the table would have shown every stream that ever fell short and not the ones waiting: a stream
emitted with every record clearing the bar now releases its row, which is a delete per emitted stream
beside the output row already written per stream. And the per-record sets had no test below the
scenario; `TestPerRecordScoring` holds each scorer's, the union over gates, and the validator's
one-based count. Two limits recorded rather than fixed, because phase 4 owns them: the stream that
brings a relearning still goes out under the incumbent, so its row stands until a reprocess serves it
clean (scenario 52 shows both halves), and a stream under a *pinned* rule writes a row like any other,
which phase 4 must decide what to do with — a rule a person has pinned is one nobody will relearn. And
one premise inherited rather than introduced: a failing record's index is an index into the output, and
the span it is read back by is the parser's; they are the same record only where the transform is one
to one, which scenario 38 already assumed and the yield scorer is what notices otherwise.

*The second bullet, built 2026-10-05, after phase 4's first slice.* The parser-position element hands the
stream itself to `Supervision`, which reads a bounded prefix (`Input.PREFIX_BYTES`, 64 KiB) for the
signature and the sample and takes the task decision on it; `Stage.decide` binds a served stream without
running anything, and `Stage.stream` serves it as it is read: `FragmentRunner.serve` runs the fragment over
the whole stream once as a pipeline, with a `RecordGate` at the tail that judges each record of the output
as it is produced — a `RecordJudge` built from the document's record-judging gates, over the record as a
document of its own — and hands each that clears to the pipeline's downstream, which writes as it goes.
The first record short of the bar is thrown out of the fragment as `RecordShortfall`, a deferral by type,
caught by the runner and handed to the stage with the record and its verdict; the stage defers the stream
with what was found — no second read — and throws the deferral; `AbstractProcessorTaskExecutor` then
discards every child of the input this task wrote. The whole-output `EventList` survives only on the paths
that still read whole: a step, an as-processed reprocess, a stage fed by a parser, and a session's own
judgement of a candidate over its sample. The stand-in runner serves through the same gate over its
step-by-step output, so Tier 1 asserts the verdicts a node decides on. `StreamInputs` reads a held stream
the same way: the prefix, and the rest opened again for a fragment. Scenario 55 (design 02 §5) and
`TestScenario16AServingRunWritesAsItGoes` are the exit; what neither can show is memory, which is the
gate's construction.

Three things the build found. The path out of a pipeline had a sixth place that swallowed a deferral,
beyond phase 1's five: `XsltFilter` catches whatever its transform's output handler throws, logs it as a
fatal error of the transform and throws a `LoggedException` — and the gate stands at the tail of a
fragment whose last element is that filter, so the first shortfall came back as a fatal error on the
task's error stream and a task that completed with errors rather than deferring. The filter now lets a
deferral through unlogged, as the parser and the executor do. A split fragment's tail emits one document
per record, each with the root around it, which the whole-output path's downstream merged without
anybody noticing; the gate reads them as one document and closes the root once, when the run ends. And a
stream small enough to read whole was at first served the old way, which would have meant a six-record
scenario exercising a path a ten-gigabyte stream never meets; every task stream now goes through the
gate.

*Its own audit found three, 2026-10-05.* A stream stopped at a short record was handed to the relearning
session with the mean of the records before it as its score, so a candidate scoring below that mean would
have been kept out by an incumbent that had just failed; a stream with a record short of the bar scores
nothing (A55). A run that made nothing fed the rolling score a sample of zero records; it feeds it
nothing. And a fragment given the bytes as they are was given the byte order mark too, which the
whole-text path had always taken off; the runner takes it off the same way the combined parser does. One
thing checked rather than changed: the per-record bar's schema filter comes from an unscoped provider, so
judging a record while the pipeline below the gate is mid-document touches no filter of that pipeline.

**The loudest limit, owed to A64 and the next slice's first job.** A relearning session opened by a
shortfall learns from the stream's *prefix* — the sample the model is shown, and the only text the worker
reads — so where the record that fell short lies beyond the prefix, the session cannot see it, promotes
on what it can, the woken task falls short at the same record, and another session opens on the same
prefix: rounds bounded only by the spend breaker (A67). §4 already says what ends this — the worker's
first act on a held stream is to run the transform in force over the whole of it and make the records
that fall short the next question's sample — and nothing less does.

Two limits besides. A stream of characters rather than bytes, or in an encoding other than UTF-8, is
read whole, since the fragment is given the bytes as they are. And the per-record bar builds a schema
filter per record through the scorer's provider, which is what the scorer did for a whole output and is
the first thing to measure on a stream of millions of records.

### Phase 3 — The corpus is durable and holds output *(the feature; design 01 §12 item 9)*

- `RegressionSet.Accepted` becomes a record's input, the output it was accepted with, whether that was
  a translation or a marked Unknown, its score, the attempt that accepted it and its targets; entries
  per record, not per stream.
- The durable form: A18's stream per rule with a row per entry, classification and retention inherited
  from the source feed and capped by it. Kept per A56: only the records a session learned for, with
  their accepted output, within `regressionCap` (A63).
- The promotion gate holds a candidate to every entry: no worse, and never from a translation to an
  Unknown (A66).
- **Exit:** scenario 10 of §8 in Tier 2, with the corpus surviving a restart between the session that
  filled it and the one it refuses, and the entry named in the feedback.

*Built 2026-10-05.* `RegressionSet.Accepted` is a record: the record as the parser cut it, as one
document the transform is given (`RecordSplit`'s form); its own text and the stream's preamble and
postamble where the parser said where it cut; the output it was accepted with; its `Kind` — translation,
Unknown, none; the score, the attempt and the target. `Corpus` builds the entries at every promotion —
the sample's records on a first learning or a draft, the records that fell short on a relearning, the
sample again where none was named — and holds a candidate to them (A66) by running the chain's transform
steps over each: the output must clear the bar, a translation must not become an Unknown, a record that
yielded must still yield; the refusal names the entry and the attempt that accepted it. A provisional
rule's entries go in at binding and a promotion on a later stream adds nothing; Approve re-keys a draft's
rows to the incumbent. In a node, `StoredRegressionSet` writes a promotion's entries as one stream on the
source feed of type `Shapeshifter Corpus`, one entry per segment as a line of JSON, and `CorpusIndexDao`
keeps a row per entry in `shapeshifter_corpus`; the cap drops rows and logically deletes a stream nothing
names. Scenario 53 is the exit at both tiers: at Tier 2 the rule is learned in a processor task and two
stages built afresh — one accepted, one refused by name — find the corpus with nothing but the rows and
the stream carried over.

What is recorded rather than built. `regressionRetentionDays` is still a setting with nothing behind it:
the corpus lives under the feed's retention rules, which is A18's cap, and the document's own shorter
retention is not applied. An improvement from the corpus needs every entry's text, which only the data
splitter supplies, so a rule learned over the JSON or XML parser cannot be improved from its corpus and
says so. The entries' one-record documents are indexed by the parser's record order, which is the
candidate's parser's too only while the parser is unchanged — the premise phase 2 recorded. The stream
type is created on demand (`MetaDaoImpl.create` asks the type DAO to get or create it), and creating a
stream checks no permission, but reading one does: the corpus is the feed's data, so a person asking for
an improvement must be able to view the feed's data, or the corpus reads as empty and the improvement is
refused for having nothing to learn from. That is the classification doing what A18 asked of it, and
the refusal could say so better than it does.

*Its own audit found two, 2026-10-05.* A chain that is a parser alone — its output is its records — was
held to nothing: the gate ran its transform steps, of which there are none, and read the empty result as
a record that now yielded nothing. Such a chain is now held to the record it emits. And a corpus stream
whose write failed part-way would have stayed on the feed as data nothing names; it is logically deleted
before the failure is reported.

### Phase 4 — Streams wait instead of failing *(the feature, on phases 1 to 3)*

- A `Deferred` decision. `Stage.serve` defers where a record falls short of the bar instead of marking
  the shape and emitting; the unknown-shape path defers instead of sentinelling; a deferral row whose
  stamp matches the transform in force defers without running anything.
- `Supervision.supervise` throws for a `Deferred` decision where it logs an error today, after
  logically deleting the output target the run had opened.
- A session edits the rule's fragment and stylesheet in place rather than writing new ones (A58), and
  each promotion snapshots the configuration (A61): the rule points at the snapshot, a deferral row is
  stamped with it, `Outputs` records it, and `asProcessed` runs it.
- A dead node's session is resumed, not restarted: the worker takes `IN_PROGRESS` attempts whose lease
  has lapsed and re-walks them, and a running session renews its lease turn by turn (§4).
- Learning leaves the task: the attempt is opened and parked, and `DeferredWorker` carries a taken
  attempt to its end rather than one step (A64) — iterating, under A50, until the held stream clears
  the bar. A promotion calls the wake for the deferral rows of that shape.
- Parking for a person (A65): an attempt status the worker leaves alone, a lease that does not lapse,
  and the Supervisor's actions on it — answer, give more to spend. It is here and not later because it
  is the safe ending for every session that cannot finish: until phase 5 gives the plan an exhaustion
  step, a transform whose steps are spent parks too, rather than being abandoned and erroring its
  streams as it would today.
- **Exit:** steps 1 to 3 of test A (§8.1), and test B over the known types; scenarios 1, 2, 3, 6, 7, 9,
  11, 12, 13, 15 and 16 of §8 in both tiers, and the live run of design 02 §6.3
  repeated over a feed whose later streams carry record types its first did not.

*Built 2026-10-05, the first slice: the task decision.* `Stage.decide` is what a task calls (`Supervision`,
for a stage fed by the source): resolve the shape, consult what is known, emit, defer or error, never
learn. A rule serving with every record clearing the bar emits, and promotes a provisional rule as before;
any record short of the bar defers, with a row stamped against the rule's fragment — the stamp until A61's
snapshots exist, which moves because a rebinding writes a new fragment — the shape marked with why, and a
relearning session opened and parked where none holds the shape; a row whose stamp is still in force
defers again without a run. No rule defers and opens the session on the stream; learning disabled defers
and opens nothing; a draft awaiting review defers; a shape given up, a reserved selector or a stream with
no value for the key errors, with the ledger saying so. A pinned rule serves whatever the bar says.
`Decision.Deferred` is thrown out of `Supervision` as `DeferTaskException`; a promotion — a binding, a
rebinding, an approval — wakes every row of its shape through `Wake`, which in a node is
`ProcessorTaskService.wakeDeferredTasks` over the phase 1 DAO; the woken task decides again, and a serve
takes the row off. The `ExecutionMode` setting is still on the document and no longer read by the task
path; phase 6 removes it. Scenario 54 holds the decision at Tier 1 and
`TestScenarioAFromNothingToServed` holds test A's steps 1 to 3 against real tasks and a real database.

Two things the build found. A node that has found a filter empty waits `skipEmptyFilterFetchDuration`
before asking it again, so a wake is honoured within that backoff rather than at once — the tests zero
it, a node lives with it, and it is worth a line in the processing screens' help when phase 6 gets to
them. And the mock harness the Tier 2 scenarios run on offers each stream once, from a tracker, whatever
the task rows say; its wake now winds the tracker back, in a test-only service, so the woken stream is
offered again.

*Its own audit found two, 2026-10-05.* An approval woke nothing: a draft awaiting review holds its
shape's streams waiting, and Approve — a promotion like any other — now wakes them. And a stream tried
again while its session was still open had the note of each try appended to its row's reason, so a row
grew a clause per retry; the row now keeps only why the stream waits, and the note of what the try found
the session doing goes to the decision alone. Two limits recorded: the deferral row is one per stream and
document, so two supervised pipelines under one document processing one stream share a row and the wake
moves whichever task wrote it last — a second filter's task comes back by the retry delay; and Reject
wakes nothing, so a stream waiting on a rejected draft errors at its next retry rather than at once.

*Built 2026-10-05, the second slice: snapshots and in-place editing (A58, A61, A70–A72).*
`shapeshifter_snapshot` holds a promotion's configuration as text — the fragment's `PipelineData` as
JSON, element id to code, document uuid to version — keyed by a snapshot id; `RoutingRule.snapshot` points
at it, `Bindings.snapshotId` records it on every output and as a stream attribute, and the deferral stamp
is it. `FragmentWriter.rewrite` edits a fragment in place for a rebinding or an improvement, overwriting
the configuration documents the chain still has elements for and writing a new one only for an element
the fragment did not have; a draft stays a sibling fragment until approved, and Approve carries the
draft's snapshot and snapshots to the incumbent. `FragmentCodes` reads a fragment as text, which is what a
snapshot is taken from and what a hand edit is noticed by: `Stage.decideServed` compares the live
documents' versions with the rule's snapshot before comparing the stamp, and takes a new snapshot where
they differ or where the rule has none. An as-processed reprocess runs the live fragment with the
snapshot's code injected per element through `InjectedCode`, which both runners now take. Retract and
reject discard the rule's snapshots. Scenario 56 is the exit.

*Its own audit found two, 2026-10-05.* A fragment bound for a neighbouring shape by looking (design 01
§6) was one that a relearning for either shape would have edited in place, so one shape's session would
have moved the other shape's transform under it — the neighbour's rule noticing a "hand edit" and
snapshotting what somebody else's session wrote. A fragment any other rule of the document names is not
edited in place: the relearning writes its shape a fragment of its own and the neighbour serves on with
what it had. And a rule whose fragment's documents cannot be read would have failed its task on the
snapshot rather than on the serve; it is served as it stands and the serve says what it finds.

Two limits. A snapshot's code is injected into the live fragment's *structure* (A70): a session that
changes the chain's shape — another parser, an element added — leaves earlier snapshots' code with no
element to land on, and a reprocess of those outputs runs the live structure with what code still fits;
the structure a snapshot kept is recorded and not yet run. And noticing a hand edit reads the fragment's
documents on every served stream — three reads through the document stores — which is the price of A72
as ruled and the next thing to measure at volume after the per-record schema filter.

What the first slice did not do, each owed to the slices after it: in-place editing and snapshots (A58,
A61 — the second slice); the worker carrying a session to its end (A64 — the third); parking for a person
(A65 — the third, at its minimum); resuming a lapsed session with the lease renewed per turn (§4, still
owed); and the stage fed by a parser, which still learns in the task because the records the parser above
it made are its stream and the worker cannot read those back from the store — the attempt would have to
carry its own sample, which is a change to the attempt row worth a ruling.

*Built 2026-10-05, the third slice: the session runs the held stream in full (A64, A65 at its minimum,
A73).* A worker that takes an attempt carries it to the stream clearing: after each promotion or
rebinding the stage runs the held stream in full through the rule's fragment with a judge that collects
rather than stops — `Shortfalls`, up to five records kept, each as the parser cut it, what the transform
made of it and the verdict — and where any fell short, opens the next question with them as its
feedback and learns the transform again in place — a candidate kept out, below the floor or no better
than the incumbent or short on the corpus, costs a round and the next question says why — up to ten
rounds, after which, or when the plan's candidates are spent, the attempt is `PARKED` with its reason, a status the worker's listing leaves out
and `Attempts.parked` gives a lease that does not lapse. In a pipeline the evidence comes from a tap:
`PipelineFragmentRunner.serve` adds a `ShapeshifterAiTap` filter to the fragment's parser for the run,
through `FragmentTap` as `FragmentOutput` already borrowed the tail, and `RecordTap` holds the parser's
current record so that the gate's judge can ask for it by index; the stand-in cuts the parser's output
by the boundary instead. A task of a parked shape defers and opens nothing. Scenario 57 is the exit, at
both tiers: `TestScenario57TheSessionRunsTheHeldStreamInFullInAPipeline` puts the records that fall
short beyond the sixty-four kibibyte prefix of a real stream, which is the case the slice exists for.

Four things the build found. A chain beginning with the Data Splitter carried no `SplitFilter`: the
transform was given the whole parsed stream as one document — held, which §3 says nothing may be — and
the stand-in ran it whole while the pipeline would have run it whole too. `Stage.boundary` settles one
record as one `record`, one deep, for such a chain where the plan settled nothing, so every learned
fragment now splits; the joined output of a per-record run is laid out as the one document again so the
goldens hold; a stream brings as many records as the parser cut, and a parser that cut none is judged on
the lines it was given. The prefix a task reads as text ends at its last line break (`StreamInputs`),
since a line cut in the middle judged as a record failed a candidate every whole record cleared. With
the tap the parser has two targets, and the pipeline's fork between them wrapped what the split's branch
threw — the shortfall, from inside a record — as a processing failure whose cause the parser could not
see, so the parser logged it as fatal and wrote the error stream a deferral must not leave (§8 scenario
6): `XMLFilterFork` now lets a deferral up as it is, and `AbstractParser` looks for one in its SAX branch
as it already did in the other. And the budgets of A65 as the Tier 1 fixture can see them are
the plan's candidates: its clock is fixed and
its scripted model spends no tokens, so the parking scenario spends the plan, which is the one ending
of a session that is not a promotion and not a parse given up.

*Its own audit found two, 2026-10-05.* The tap was read one record late in a pipeline: the split hands a
record to the transform as the record's last event reaches it, and the parser's fork had told the split
before it told the tap, so the tap still held the record before — the evidence put to the model said a
logon record had produced a logoff event, which is the kind of wrong a model will learn from. The tap
now shows the record it is in the middle of, closed, which at the record's last event is the whole of it;
the Tier 2 scenario asserts the evidence names the record whose output fell short and not its
neighbour, and failed against the tap as it was. And a session's rounds overwrote one another's turns:
each walk numbers its turns from one and the attempt kept them by number, so after two rounds the record
of the session was the second round's alone, and a re-walk — a resume, a person's answer — would have
replayed the second round's answers to the first round's questions. The recorder now numbers a walk's
turns on from where the last left off, and the Tier 1 scenario asserts both rounds' turns stand, in
order. One thing noted and left: a candidate kept out under the worker is recorded by `emit` as an
output of the stream, as it was before this slice, though the worker wrote nothing; it is bookkeeping a
reprocess reads and is worth a look when the Supervisor's view of a session is built.

What the third slice did not do: give the Supervisor its actions on a parked attempt — answer, more to
spend, send to the exhaustion step — which phase 6 owes with the screen; and tell a session out of
budget from one out of candidates in anything but its reason. Nothing is done with a stream that cannot
be parsed beyond what phase 5 rules.

*Built 2026-10-05, the fourth slice: a session whose node dies is carried on by another (§4).* The lease
is a turn's allowance: `ShapeshifterAiConfig.sessionLease`, ten minutes by default, taken when an attempt
is claimed and renewed before every question and as every turn is recorded, so a live session never
lapses and a dead one is noticed within a turn. A lapsed lease no longer frees the shape: `opened` does
not abandon the holder and `open` does not look at the expiry, so the next stream of the shape waits on
the session and nothing opens beside it. `Attempts.lapsed` lists what a node was carrying whose lease
has run out, and `DeferredWorker.advance` takes those after the attempts awaiting the model: `claimed`
already let any node take a lapsed attempt, and `resume` already re-walked the recorded turns, so the
dead node's session is finished by whichever worker finds it, asking the model only what it had not
been answered. Scenario 58 is the exit, at Tier 1 with two node names over one set of state and at the
DAO against the table. Added to the expected configuration, as every setting is.

*Its own audit found two, 2026-10-05.* A person's answer to an older attempt still swept a lapsed session
aside: `reopened` abandoned whatever lapsed attempt held the shape so that the reopened one could take it
back — the old release, kept — where the rule is now that a lapsed session is resumed; the clause is gone
in both implementations, and a reopen is refused while any attempt holds the shape, as its contract now
says. And the worker took lapsed sessions only with what was left of its batch after the attempts
awaiting the model, so a cluster whose batch was always full of parked attempts would never have reached
one; the two kinds are listed together, oldest first, and the batch cut from that.

What this slice does not do: nothing at Tier 2 beyond the DAO, since the test harness has one node
name — the takeover is the DAO's `claimed` and the worker's listing, both held — and nothing for a
session whose node dies *inside* a model call longer than the lease, which is taken for dead and
walked by two nodes at once until the first finishes; the lease is to be set longer than the slowest
call expected, and the setting's description says so.

### Phase 5 — A session always ends *(the feature)*

- The exhaustion step: a plan step, reached only when the others are spent, that asks for a match on
  the record type whose output is the admitted form; the built-in text for it; the marker — a named `Data` item inside `Unknown` (A62) —
  defined once, in shared code, and checked to validate against the `EVENTS` schema group, so that the text that asks for it and the scorer that accepts it cannot drift; the
  quality scorer accepting a marked Unknown and no other.
- The Supervisor's third action on a parked session — send it to the exhaustion step — beside the
  two phase 4 gave it.
- The parse that cannot be learned: the session gives the shape up, and the streams deferred on it
  error with the reason. It is unlearnable when the parser step is spent short of the document's
  input-coverage and yield gates (A54).
- **Exit:** the whole of test A and test B with the unseen and untranslatable types arriving
  mid-run (§8.1); scenarios 4, 5 and 14 of §8, and a scripted run in which a model that asks for the admitted
  form before the exhaustion step is refused it.

*Built 2026-10-05: the exhaustion step and the unlearnable parse (A51–A54, A62, A74–A76).* The marker is
`LearnedUnknown.MARKER`, `ShapeshifterUnknown`, in shared code: a `Data` item of that name inside
`EventDetail/Unknown` whose value is the model's name for the record kind admitted (A74), and the event
schema allows it — `Unknown` holds any number of `Data` items, checked before anything else was written.
`ADMIT` is a plan step kind, the transform's configuration asked for once more with the admitted form in
its text (`Template.ADMISSION`, which raised the built-in version), entered only by a transition and never
by the step above it ending; the example plans reach it `on spent` from their last transform step, the
direct plan now having a parser step and a transform step rather than one over both. The quality scorer
accepts a marked Unknown and leaves the marker out of its count of untyped data; a bare Unknown is refused
as it always was; and the conversation refuses the admitted form at any step but `ADMIT` (`REFUSED`), so a
model cannot take the short way out of a record it could translate. A marked Unknown clears the bar when
served and is held in the corpus as an admission (A75), which A66 lets a later translation replace and
refuses to let a translation become. A parser whose candidates are spent is the one failure nothing can
serve (A54): the abandonment says so, the session gives the shape up, and the streams waiting on it are
woken to error with the reason, their rows released. Scenarios 59 and 60 hold it at Tier 1 and test A's
steps 4 to 6 at Tier 2, on the CSV feed with a badge-fault kind standing for the untranslatable type.

Building it found the gap that would have made the step unreachable: a relearning judged its candidates
on the sample, which is a prefix the records that fell short are usually not in, so a transform that
ignored them passed its step and the session looped to parking without ever spending a candidate. The
conversation now holds every candidate for the chain's last element to the records the full pass kept, as
the parser cut them, and a candidate short on one is judged short with the record in its feedback; the
corpus takes those records as its entries for that round, each without its own text, since they lie
beyond the text in hand. The exhaustion step is reached by the plan's candidates being spent (A76), which
with the direct plan's single candidate per step is after one refusal; budgets out before that still park
(A65), and the Supervisor's action that sends a parked session to the step waits for phase 6's screen.

What this does not do: test B; a relearning whose *parser* candidates are spent parks rather than giving
the shape up, since an incumbent is serving and A54 was written for a shape nothing serves; and the
admission's record-kind value is the model's word, checked for nothing but its presence.

### Phase 6 — What an operator sees, and what is taken out

- The Supervisor's backlog from the deferral table: what waits, on which transform and since when. The maximum deferral age (scenario 8), past which a stream is
  raised and goes on waiting (A57); A24's error mode restated as that flag.
- Removed: the sentinel as a lock and the ledger rows it wrote; `PipelineReprocessing` as a release;
  `ExecutionMode` from the document, its tab and the attempt row (A60); the rolling score as a trigger. Kept: the ledger and reprocessing for
  deliberate remediation, the rolling score for A46's list.
- Worker claiming required for a filter over a supervised pipeline, and said so where a filter is made.
- **Exit:** a person takes a feed from unseen to served from the UI, watching the backlog form and
  drain, with no error stream written at any point; and the full suite with the removed code gone.

*Built 2026-10-06, the first slice: the backlog, the maximum deferral age, and more to spend (A77–A79).*
The Supervisor has a backlog pane under its attempts: a row per stream waiting on a session, from the
deferral table — document, feed and type, stream id, waiting since, how many times a task has decided it
and held it again, the snapshot it was held against, the reason — the raised rows first and then the
longest waiting. Raised is computed as the rows are read, against the document's new
`maxDeferralAgeMs` (a day by default, edited on the Learning tab beside the budgets, A78): past it a
stream is flagged and counted and goes on waiting, and nothing but giving its shape up errors it (A57,
A79). A parked attempt takes a grant (A77): tokens, asked for beside the attempt, added to what the
document allows that attempt alone — `granted_tokens` and `granted_ms` on its row, read into the
conversation's budget — and the attempt is opened again for the worker, which carries it on from its
recorded turns, replaying them for nothing and spending the grant on what remains. Scenario 61 holds the
grant at Tier 1 with a scripted model that charges a token a question; the DAO's test holds the row; the
raised flag has a test of its own. The demo pack was regenerated for the document's new field.

*Its own audit, 2026-10-06.* Two small things changed: a document deleted between being listed and being
read would have taken the whole backlog down with it, and its rows are now shown against the default
age; and test B's second wave was loaded only after the first had wholly drained, which is not "while",
so it is loaded as soon as any of the first is served. Two things noted: the backlog reads every readable
document's rows and pages them in memory, as the first version of a view may, and a cluster with a
backlog of thousands will want the page in the query; and "counted" is the page's total, not a count of
the raised rows alone, which the screen does not yet show.

What this slice does not do: send a parked session to the exhaustion step from the screen, which needs
the re-walk to know when the recorded turns are exhausted before it may skip a step, and is the next
slice; grant time from the screen (the resource takes milliseconds, the button asks for tokens); and
anything of the removals.

*Built 2026-10-06, the second slice: the removals (A60, A80).* Execution mode is gone — from the document
and its Settings tab, from the attempt row (migration 019 drops the column), from the attempt criteria and
the Supervisor's filter and list, and from every fixture and test that set it; a task decides and never
learns, so there was one mode left and a setting that offered a choice would have changed nothing. What
learns inline is `Stage.run`, which a stage fed by a parser and the Tier 1 scenarios call, and it now
always asks as it goes. The rolling score is no longer a trigger: `Stage.served` feeds it for the
Supervisor's list of what could be better (A46) and marks nothing, since what sends a shape back to the
model is a record short of the bar or a person. The task path's refusals — a reserved selector, a stream
with no value for the key, a selector that cannot be built — error with the reason and write no ledger
row, since nothing a promotion could release would change them; the one refusal that keeps its row is the
given-up shape (A80), because the ledger is the door a person learns it again through. The ledger and
reprocessing stay for that, and for retraction.

*Its own audit, 2026-10-06, found two settings the removals had left dead.* `errorModeAfter` (A24) was
read by nothing — A57 restated error mode as the raised flag — so it is gone from the document and the
Settings tab, as execution mode is; a document saved with either key still reads, since the store's
mapper ignores what it does not know. And `relearnThreshold` was read by nothing once the trigger went,
where design 04 §6 says it becomes the serving list's criterion: the Supervisor's serving rows now carry
`belowThreshold`, computed against the row's document as the list is read, and say so in their state.
Two things noted: the REST layer does fail on unknown properties, so a browser tab left open across this
deployment gets a refusal from the Supervisor's filter until it is refreshed, as it would for any field a
deployment drops; and `Stage.run`, which a stage fed by a parser still calls from a task, still
sentinels a stream while another attempt learns its shape — the parser-fed stage's deferral is the open
ruling of phase 4's note, and this is one more thing it settles. *Settled 2026-10-06 by removal (A99).*

*Built 2026-10-06, the third slice: send to the exhaustion step, and worker claiming required (A65,
A76; §7).* The Supervisor's third action on a parked session is beside the grant: send it to the
exhaustion step. It sets `exhaust_next` on the attempt's row (migration 020) and opens the attempt again
for the worker, which carries it on from its recorded turns and, at the first transform question it
would put to the model — the record's turns replayed first, which the advisor says by `replaying()` — puts
nothing and takes the step's `on spent` transition instead, as though its candidates were gone: to
`ADMIT`, in every plan the examples give, which asks for the admitted form for what the held stream still
cannot translate (A76 is kept: the exhaustion step is reached by the plan's transition, and a person's
sending only brings the spending forward). A plan whose transform step has no `on spent` transition parks
the session again, saying so. The sending stands on the row until the attempt closes: a session parked
for its budget has no budget for the exhaustion step's own question either, so the worker parks it again
at once, and the grant that follows reopens it with the sending still to be taken — a grant and a
sending are two things a person says, in either order. Scenario 62 holds it at Tier 1, the DAO's test
holds the row. And a filter over a pipeline with a Shapeshifter AI stage in it is refused where it is
made unless the node claims its own tasks: `ProcessorFilterServiceImpl` asks every `SupervisedPipelines`
binding — an interface in `stroom-processor-api`, bound by this module through a multibinder, read from
the pipeline as it is merged so an inherited stage counts — and names
`stroom.processor.claimTasksOnWorker` in the refusal. Every Tier 2 test on the real harness sets it;
a test of its own holds the refusal, the plain pipeline that is not refused, and the supervised one that
is made once the node claims.

Two faults were found on the way, neither of this slice's making. A round that followed another in one
carrying was under a fresh budget, since each round built a conversation whose count began at the
advisor's count then: A5 says the budget is the attempt's, so the recorder now carries what the carrying
has spent and when it began into every round. And a session that bound a fragment of its own in round
one and parked in round two could not be resumed at all: the re-walk found the rule it had bound, took
the relearning path, and its first question carried an opening shortfall that round one's, asked before
anything was bound, had not — so the replay refused turn one as diverged. The comparison now holds a
question's *identity* — its kind and what it is about — and leaves out how much the step had been told,
which the summary still records for a person to read; an answer kept is an answer to the question,
whatever feedback it was asked with.

*Its own audit, 2026-10-06.* Four things changed. The sending was checked once, before the transform's
candidates, so a session parked after the first of several candidates would have replayed that turn and
then asked the model for the second with the sending ignored: the check is now before each candidate
that would be put to the model, inside the loop, and scenario 62 has a third test that parks there. The
sending was written after the reopening, which left a moment in which a worker could take the attempt
without it: the flag is written first, since on a parked row it stands harmlessly. The import path makes
a filter through the plain `create(ProcessorFilter)`, which had no claiming check: it has one now,
and a pipeline the check cannot read, as an import's filter may name before its pipeline has
arrived, is not judged rather than failing the import (found by the pack's import test, 2026-10-06). And
the migration's comment said the flag was cleared by a person's answer, which was the first design and
not the built one. One thing noted: a pipeline that cannot be merged — a parent missing, say — fails the
claiming check with that fault rather than being passed over, which is the right outcome and an
unfamiliar place to meet it.

What this slice does not do: grant time from the screen; show the sending on the attempt row in the
Supervisor beyond the state it is in; or any of the phase's exit walkthrough, which is next.

The tests behind the mode were rewritten rather than deleted. Scenario 30's deferred document became the
task decision — `decide` opens the session and holds the stream on a row, the worker asks, the promotion
wakes — and the one test that had a marked shape park a candidate while its incumbent served now holds
that a mark alone opens nothing. Scenario 31's "a turn an attempt stopped at is recorded unanswered"
gets its unanswered turn from a worker with nobody to answer, since a task no longer asks. Scenario 27's
falling rolling score is now shown and not acted on, and the relearning it goes on to test is a person's
mark; the incumbent scenarios mark by hand likewise.

## 8. The scenarios this has to pass

Numbered into design 02 §5 when the work is cut into slices.

1. **A stream with an unseen event type is held, learned and served in full.** One stream, one record
   type the transform has never met: nothing is emitted, a session opens, and after promotion the same
   stream is processed and every record scores. Tier 1 for the decision, Tier 2 as a processor task.
2. **A stream of the same feed and type whose records all score flows while the session runs.** No gate
   on the shape, and no dependency on processing order.
3. **A deferred stream is not re-scored until the version moves.** The transform runs once at failure
   and once per promotion, and the test counts the runs.
4. **An exhausted session bakes an Unknown match and the held stream then passes.** The transform
   carries a match for that record type; the output validates; the stream is served.
5. **A stream whose records cannot be parsed errors, and says so.** The only terminal failure.
6. **A deferred stream leaves nothing behind.** No output stream, no error stream, no fatal markers on
   the input's meta, and the record counts unchanged — the check that the exception travelled as a
   deferral and not as a failure.
7. **Nothing is sentinelled to make it wait.** A regression test over the whole learning path: no error
   stream is written for a stream that is merely early.
8. **A deferral older than the maximum is raised and still waits.** It shows in the Supervisor and in
   the counts, nothing errors, and giving the shape up is what errors it.
9. **A promotion wakes the backlog in order.** The deferred streams of that feed and type are processed
   after it, oldest first.
10. **The corpus holds a candidate to no worse.** A later session that would turn a translated record
   into an Unknown, or leave any entry short of the bar, is refused; one that turns a marked Unknown
   into a real translation is accepted.
11. **A stream is reprocessed as it was processed after its transform has moved on.** The output names a
   snapshot, the fragment has since been improved in place, and the reprocess produces what it produced
   before.
12. **A session out of budget parks and holds.** Its streams stay deferred, no other task takes the
   shape or spends on it, nothing is baked as Unknown, and a person's answer carries it on.
13. **An attempt that lapses or fails does not strand its streams.** The next retry opens another.
16. **A stream larger than memory is scored, deferred and served without being held.** Input streamed,
   output written as made; the run that defers leaves nothing visible and the run that serves reads the
   stream once; memory stays flat throughout.
15. **A session whose node dies is carried on by another.** The attempt is left in progress with its
   lease lapsed; a worker under another node name takes it, re-walks the turns already recorded without
   asking the model for them again, finishes, and the streams are released. No second attempt is opened.
14. **A marked Unknown is not retried.** Later streams carrying that record type are served, and no
   session opens for it until a person asks.

### 8.1 The two tests that say the whole thing works

The scenarios above each hold one decision. These two hold the flow, end to end, in `stroom-app` against
a real database, with processor tasks created by a real filter and the scripted model of design 02 §3
in place of a live one. They are the owner's (2026-10-05), and no phase from 4 onward is finished
without the part of them it makes possible.

**Fixtures.** `windows-security.xml` already carries three event types — 4624, 4634 and 4688, five
records of each — with its stylesheet and golden events. It is cut into streams so that the types
arrive in the order the test needs, and gains one record type that cannot be translated: an event whose
payload gives the model nothing to classify, for the Unknown leg.

**Test A — one feed, from nothing to served, twice over.**

1. Two streams of 4624 and 4634 arrive on a feed nothing has been learned for. Both tasks end
   `DEFERRED`; one session is open, not two; no output stream and no error stream exists for either.
2. The worker carries the session. The scripted model answers; a pipeline is written for the feed and
   type and promoted; the corpus holds the records that held the stream.
3. Both tasks are woken, run again and complete. Each stream's output is its golden events, and carries
   the bindings that say what produced it.
4. A third stream arrives carrying 4688 and the untranslatable type beside the types already known. Its
   task defers — nothing is emitted for the records that would have translated either — and a second
   session opens on that stream. No record of which of its records failed is stored.
5. The model translates 4688 and, at the exhaustion step, gives a marked Unknown for the other. The
   *same* pipeline is improved in place and a new snapshot promoted; the candidate was held to the
   corpus and the 4624 and 4634 entries are no worse.
6. The third task wakes and completes: 4688 as its golden, the untranslatable records as marked
   Unknown events, the rest as before. A fourth stream of all four types is served at once with no
   session opened.

**Test B — many streams, many threads.** The same feed and fixtures, with enough streams arriving at
once that the race is real, processed by many task threads on one node in worker-claim mode. It does
not run more than one node (the owner, 2026-10-05): what has to be safe across nodes is the same set of
statements that has to be safe across threads — the task claim, the attempt lease, the deferral rows,
the wake — and they are contended here through the one database exactly as a second node would contend
for them.

It asserts, after the backlog has drained:

- **one session per learning event** — the number of attempts opened equals the number of times the
  transform actually had to change, however many tasks met the gap at once;
- **every stream processed exactly once** — one completed task and one output stream per input, none
  missing and none twice;
- **no partial output** — every output stream holds a whole stream's events, equal to its golden; none
  exists for a task that ended deferred, at any point the test samples;
- **no error stream that should not exist** — none at all in this test, since nothing in it is
  unparseable; and no fatal or error markers on any input's meta;
- **no task left behind** — nothing `DEFERRED`, `PROCESSING` or `CREATED` at the end, no attempt still
  open, no deferral row unresolved.

The invariants are checked *while* it runs as well as at the end, by sampling: a partial output that is
later completed, or an error stream that is later deleted, is a failure the final state would hide.

*Built 2026-10-06: `TestScenarioBManyStreamsManyThreads`.* Twelve streams of one shape at once, then eight
more carrying a kind the transform has not met, loaded as soon as the promotion has served any of the
first and so while the rest are still draining; four task threads
claiming from the real task table under one lock and processing outside it, the learning job on a fifth
thread, a sampler on a sixth. It asserts what §8.1 lists, and one thing more: that the race was real —
at least half the first wave is seen `DEFERRED` by the sampler, since a test in which every stream
happened to arrive after the promotion would prove nothing. Two sessions, both promoted; one task and one
visible output per stream, each the whole stream's events; no task logged an error; nothing deferred,
waiting or open at the end. Three runs in a row, 22 to 110 seconds on a box with a gigabyte free. What
it does not assert: *no error stream at all*, since the stage says what it decided at INFO on the error
receiver and Stroom keeps that as a stream of its own; it asserts no task produced an error or fatal
marker instead.

---

## 9. Open

- **The routing table.** With one pipeline per feed and type it could collapse to a lookup with
  operator-authored overrides. Not now, by the owner's decision; recorded so the option is not lost.
- **Learning in stepping mode**, which the owner has said is a different discussion.
- **How long a snapshot is kept** (A61). It must outlive every output that names it, so it follows the
  retention of the feed's processed data; the rule for that is not written.
- **Whether deferred tasks count against a filter's task budget** (raised 2026-10-06). Creation bounds
  itself on `countTasksForFilter(filter, CREATED)` (§5.2), so `DEFERRED` tasks are outside the budget:
  the deferred population is bounded by what arrives, and a wake or a sweep can return a backlog to
  `CREATED` far past the limit, after which creation pauses until it drains. Options: count `CREATED`
  and `DEFERRED` together, which brings back the stall §5.2 avoided; or a separate per-filter cap on
  deferred tasks past which creation pauses. Left as it is until the real-scale trial shows whether the
  deferred population matters.
- ~~**That a marked Unknown validates.**~~ Checked, 2026-10-05: `Unknown` holds any number of `Data`
  items, each with a required `Name` and an optional `Value`, so the marker of A62 validates as written.

---

## 10. Rulings this document records

The owner's, 2026-10-04, proposed for design 01 §13:

**A48.** *Execution of an AI pipeline is a pull with deferral, not a push with errors. A task that cannot
be completed now returns to `CREATED` with a minimum time before it is retried; nothing is errored in
order to make it wait. Learning never runs on a processing thread.*

**A49.** *A shape, for learning, is a feed and a type. Event types within a stream are XSLT match
treatment and need no mechanism outside the transform to identify them.*

**A50.** *A stream with even one record that cannot be translated is held until it scores in full, and
the session learns every event in it. The held stream is the session's acceptance test.*

**A51.** *Unknown is the product of an exhausted session: a learned match for a record type the model
could do nothing better with, baked into the transform. It is not a fallback for anything unmatched, and
the feature does not count them — statistics over converted streams are ordinary downstream processing.*

**A52.** *A translation always terminates: what cannot be translated becomes Unknown and is served. A
parse that cannot be learned is the one real error, because there is nothing to score and nothing to
serve.*

**A53.** *A learned Unknown is marked: the exhaustion step asks for an Unknown event carrying a defined
marker, and the quality scorer accepts a marked Unknown where it refuses a bare one.*

**A54.** *A parse is unlearnable when the session's parser candidates are spent without meeting the
document's input-coverage and yield gates. No new setting decides it.*

**A55.** *The bar a served record must clear is the gates that apply per record, and nothing else.*

**A56.** *The regression corpus is per record and holds only the records a session had to learn for,
each with the output it was accepted with — never a whole stream and never a record that already
translated.* (Restated 2026-10-05: the sample of passing records was dropped.)

**A57.** *A stream deferred past the maximum age is raised and goes on waiting. Nothing errors because a
session is slow; giving a shape up is a person's decision. This is what error mode (A24) becomes.*

**A58.** *A session improves a transform in place: one fragment and one stylesheet per feed and type,
growing matches as it learns.*

**A59.** *The minimum time before a deferred task is retried is one configured delay, and a promotion
wakes its tasks early. `processor_task` gains no column.*

**A60.** *Execution mode is removed from the document: learning never runs in a processing task, and a
setting that offered the choice would change nothing.*

**A61.** *Every promotion keeps an immutable snapshot of the fragment's configuration, keyed by version;
`Outputs` records the version, and an as-processed reprocess runs the snapshot.*

**A62.** *The marker of a learned Unknown is a named `Data` item inside `EventDetail/Unknown`, which the
quality scorer leaves out of its count of untyped elements.*

**A63.** *The document's regression cap bounds a rule's corpus.*

**A64.** *A node that takes an attempt carries the session to its end in one pass — until the held
stream passes, the budgets are spent, or it parks for a person.*

**A65.** *A session whose budgets run out before its held stream passes parks for a person. It keeps its
lease and its streams go on waiting; nothing is baked as Unknown because a budget ended. This qualifies
A52: a translation terminates when its plan is carried through, and waits when its budget is not
enough to carry it.*

**A66.** *The corpus holds a candidate to no worse: every entry's new output clears the bar and a record
once translated never becomes Unknown. Output may otherwise change, and a marked Unknown may become a
real translation without anybody agreeing.*

**A67.** *Nothing new bounds a shape that keeps failing to release its streams: the cluster-wide spend
limit (A44) stops a document asking, and the deferral age raises what has waited too long (A57).*

**A68.** *A marked Unknown is revisited only when a person asks. It clears the bar, and nothing retries
it on its own.*

**A69.** *A session learns from one stream, and every waiting stream is tried again when it promotes.
A waiting stream records that it waits and against which transform, and nothing about which of its
records failed: the session works that out for its own stream, and for the rest it would be stale.*
Ruled 2026-10-05, the owner's, on four questions put with recommendations, for the snapshot and session
slices:

**A70.** *A snapshot (A61) is a row of configuration text — the fragment's structure and each element's
code — keyed by a version id, and an as-processed reprocess runs the live fragment's structure with the
snapshot's code injected per element, as the stepper injects an edit.* No documents are copied.

**A71.** *A snapshot lives as long as its rule and goes with it on retract or reject.* It is a few
kilobytes per promotion; nothing about it is worth a setting.

**A72.** *A fragment edited by hand is snapshotted on the first serve after the edit: the stage keeps the
document versions a snapshot was taken from, and a live fragment whose versions differ is snapshotted
before it serves — a promotion by hand.* Outputs are stamped with what produced them, and the deferral
stamp moves, so waiting streams are scored against the edit.

**A73.** *The A64 full pass is built with the minimum of A65: a `PARKED` attempt status the worker leaves
alone, a lease that does not lapse, and the Supervisor showing it.* A session out of budget mid-iteration
stops there rather than being abandoned into another round.

The owner's, 2026-10-05, for phase 5:

**A74.** *The marker of a learned Unknown is one `Data` item named `ShapeshifterUnknown` inside
`EventDetail/Unknown`, whose value is the model's name for the record kind it admitted.* One constant in
shared code; the value says what was admitted, for a reader and for a later improvement.

**A75.** *A marked Unknown clears the bar once served, is held in the corpus as an admission, and counts
towards the shape's rolling score as any served record does.* A66 then lets a later improvement replace it
with a translation and refuses the reverse; nothing is counted (A51), and nothing drags the shape towards
relearning for admissions alone (§8 scenario 14).

**A76.** *A session goes to the exhaustion step when the plan's candidates for the transform are spent
with records of the held stream still short — by the plan's own transition, and by no other route.*
Budgets spent before that park (A65); a person's sending a parked session there waits for the screen.

The owner's, 2026-10-06, for phase 6:

**A77.** *More to spend is granted to the attempt, not the document: tokens and time recorded on the
attempt's row, added to the document's budgets for that attempt alone, and shown with it.*

**A78.** *The maximum deferral age is a document setting, beside its budgets.*

**A79.** *Raised is a flag on the row, computed from its age when read, and counted in the Supervisor's
backlog; the stream goes on waiting, and nothing but giving the shape up errors it.*

**A80.** *A stream of a given-up shape keeps its ledger row; every other refusal errors with its reason and
writes none.* The ledger's purpose is the door through which a person learns a given-up shape again, and
the row is what that asks to be processed again. *Superseded by design 06 A131 (2026-10-07): there is no
ledger. The given-up shape's stream errors with its reason and nothing else is written; the door is the
attempt that gave up.*

**A99.** *The stage has one shape, the parser's, and a task never learns inline.* The filter form of the
stage — the supervised stage fed by a parser (design 01 §12 item 4, scenario 50) — is removed, with the
position walk, the record-unit stage and the inline learning path that only it took from a task. It was
the last place a task asked the model and sentinelled a stream while another session held its shape.
What it was for, a transform over records a parser made, is now one chain the single stage learns
whole, its parser included — `DSParser -> XSLTFilter` — since the stage is fed by the source and its
position check refuses allowed elements that cannot parse. `Stage.run` remains as the harness's inline
walk, which no element calls; the shared `ReplayUnit` keeps its two units, since a hand-bound fragment
is still checked against what the document's chains can make. *Extended the same day: `Stage.run` is
gone too.* Its last callers were the Tier 1 scenarios, which now process a stream as production does —
`Scenarios.process`: the task decides; a session the stream opened is carried as the worker carries it,
by `Stage.resume`; and the stream is served once its task is decided again — so a scenario can no longer
pass on behaviour no pipeline reaches. With it went the serving logic only it reached: the inline
promote, retract and relearn of a marked shape, and the shadow deferral rows of phase 3. Converting
the scenarios found what the inline walk had been hiding, ruled as A100–A102, and two faults in the
task path: a rejected draft gave its shape up without waking the streams waiting behind it, which would
have waited for a review that was no longer coming; and the variant trial held a stream's records to
every gate scorer on the document, where serving holds them to the record bar alone, so a disabled
document refused a variant that would have served the stream cleanly. Both are fixed. One thing is
noted and left: what a parser drops — lines its splitter does not match — is reported on the error
stream but judged by nothing while a stream is served, so a feed that changes its format under a rule
is not sent back to be learned until a person marks it (A100).

**A100.** *A person's mark relearns the shape on its next stream.* When a task decides a stream of a
shape a person marked for relearning, and the rule is neither pinned nor behind a draft, learning is
on, no session holds the shape, and the stream brings enough records to judge a candidate on, the
relearning session is opened and parked for the worker, opening with the mark's reason and the
stream's verdict; the stream itself is served by the incumbent (A29). Before, a mark opened nothing
on the task path, and only the inline walk acted on it.

**A101.** *A provisional rule is not retracted by the floor.* It is held to the record bar like any
other: a stream whose records clear it is served, one whose records fall short sends the shape back to
be learned, and enough clean records promote it in place. The retraction of design 01 §6 is withdrawn.

**A102.** *Improving a serving rule stays synchronous.* It is a person's deliberate action against the
rule's accepted corpus, which the worker cannot read back as a stream, and its budget bounds it; it is
the one place the model is asked outside the worker. Moving it to the worker needs the attempt to
carry its own sample, which is a change to the attempt row for a later slice.

*The claim during a model call, 2026-10-06.* A session's claim was pushed out before every question and
after every answer, but not while a question was with the model, so one call longer than the lease let
another node take the attempt and ask the same question again. `HeartbeatingAdvisor` wraps the advisor
the conversation is given: while a question is out, it pushes the claim out every third of the lease on
one daemon thread for the node, so two beats can be missed before the claim lapses, and it stops the
moment the answer or a failure comes back. A heartbeat is one short write and already logs rather than
throws, so a failed write on the timer thread fails nothing. *Its audit:* cancelling a beat does not wait for one that is running, so a beat
could have landed after the question returned — on a session that had since parked, rewriting its
never-lapsing claim. Nothing reads a parked claim's expiry, but each question's beats now run under a lock
the question takes as it returns, so once it has returned none is running and none can start.

*The conversion's own audit, the same day:* the stepper's dry run still predicted the old outcomes — a
provisional rule below the floor "would be retracted", a stream behind a draft "would be sentinelled" —
and now says what production does: the provisional rule serves as it stands, the stream waits on the
review. The mark path also passes over a given-up shape, as the inline serve did, since a given-up
shape is the ledger's to send back (A80). Comments that named the removed walk were reworded, and
design 02's rows 13, 28 and 52 say how they changed. Noted: the mark path judges a stream's prefix on
a node, where the task has only the prefix in hand, so a marked shape is relearned on the first stream
whose prefix brings enough records rather than the first whose whole does.

