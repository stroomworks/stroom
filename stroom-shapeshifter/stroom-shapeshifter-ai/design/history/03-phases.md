# Design 03 — Phases: what is built in what order, and what lets the next phase start

> **Archived.** This is the design as it was written and ruled, kept for its reasoning and history. The current design is [the design set](../README.md); code comments that cite this document by number and section still resolve here.

*Written 2026-09-21, revised 2026-09-23 for the MVP scope of design 01 §15. Design 01 §12 is the work list, in dependency order and marked built or not;
design 02 §6 is the same list ordered by the scenario that forces each item. Neither says what a phase
is, when it is finished, or which input formats the feature has been shown to handle. This document
does, and it is the one the slices are now cut from. A phase ends on its exit criterion — a test or a
run, not a judgement — and the next does not start until it has.*

> *Superseded in part by design 06, phase J (2026-10-07 and 08).* The pipeline is the unit of learning
> (A104): a **learner** is one Shapeshifter AI element in one pipeline (A114), and what serves it is its
> **binding** (A117, A124, A140). Shapes, the learning key, the shape signature, the routing table and its
> selectors, pinning (A138) and the ledger (A131) are gone; a stream waits as a deferred task, which a
> promotion wakes by its pipeline (A129). Where this document says a shape, read a learner; where it
> says a rule, read a binding. It is kept as the record of how the design arrived where it is.

## 1. Where it stands

**What is being built is an MVP prototype** (the owner, 2026-09-23): enough of the process and the UI to
explore whether the capability is worth having, on fixture data, in an environment that is not security
sensitive. Design 01 §15 gathers what that defers — redaction, the restricted XSLT function library, the
safety controls for running unattended at volume — with the conditions under which each falls due. The
phases below are read in that light: **A, B, C, D and F are the MVP**; E is deferred and G is the gate to
real data.

Slices 1–37 (design 02 §6.1) have built the document type and its editor, the scorers, the `Stage` with
its routing, learning, provisional binding, promotion, retraction, relearning, ledger and review mode,
the supervisor element in a real pipeline, learning against a target, the plan as data and then as a
graph, the six formats of §3, the A26 tables and the A28 attempt with its deferred worker, and — in
phase D — the two `stroom-pipeline` changes items 1 and 2 asked for, the learned boundary as a
`SplitFilter` with the chain run one record at a time, and markup without a root.

**Phase D is met, 2026-09-23.** Scenarios 18 and 19 run as processor tasks over real pipelines with no
interim glue left in them; scenario 38 reads a record back from its stream by the span that cut it; and
scenario 13's Tier 2 half takes one feed from unknown shape to learned, promoted, released and
reprocessed on a running node. Two things the phase surfaced and did not plan for went with it (§7 slice
4b): the transformation stage has an element it can stand in, and a stream is transformed once rather
than twice.

**Phases A, B and C are met.** Runtime state is rows in `stroom-shapeshifter-ai-impl-db`; attempts are
durable and resumable; a bound rule is judged and served by running the fragment as a real pipeline,
with a Tier 2 scenario holding the Tier 1 stand-in to the same events. The model has been run live
against six formats under three plans, and once more (run 9, design 02 §6.4) to measure what showing it
one record changed.

**What is left of the MVP is the whole of F**, in the order §7 gives: slices 6 to 10, the surfaces.

**The processing flow is a phase of its own, not yet placed** (the owner, 2026-10-04). Design 04 describes
it: a stream that cannot be translated yet waits as a deferred task rather than as an error stream, and
learning leaves the processing thread altogether. It is the largest piece of work outside this feature —
three primitives in `stroom-processor` and worker claiming moving from experiment to requirement — and
it supersedes the sentinel-and-release mechanism phase C built. Where it falls in the order is the
owner's to set; nothing else in the MVP depends on it, and a real feed cannot be pointed at the feature
without it.

## 2. The phases

| Phase | Delivers | Closes (design 01 §12 / design 02 §5) | Exit criterion |
|---|---|---|---|
| **A. The plan mechanism** | A37: outcomes and checks as closed lists; `PlanStep` with id, role, checks, transitions; `Conversation` as an interpreter; the *escalating* example; the renames of §12 item 23; the Learning tab's grammar; `SHAPESHIFTER_LIVE_PLAN` | item 23; scenarios 39–42 | every scenario green in both tiers; the 2026-10-01 live run over feeds 06 and 07 with all three plans, transcripts under `build/live-runs`, findings in design 02 §6.3 |
| **B. Format breadth** | one fixture, golden event set, scripted scenario and live row per format of §3; the `JSONParser` runner (scenario 2); the coverage floor and yield bases set per format from what the goldens score | scenarios 2, 43–48; thresholds' first values (design 01 §2.1) | each of the six learned scripted; at least four of the six learned live to the floor with the default plan or the escalating one |
| **C. Durable state** | the A26 module and tables as A41–A44 revise them — the learned rules as rows with the document holding only authored configuration (the `Rules` seam and the document's emptying built in slice 20, the five tables and their DAOs in slice 21, scenario 20 against them in slice 22, all 2026-09-22), the lease per shape with sentinelling losers, the cluster-wide spend counters, and the caches that keep the hot path off the database; the A35 record element on the rule and in the stage's count and yield (built early, slice 19, on run 7's evidence); A28's attempt and turn tables, the deferred worker (slice 26 in the module, slice 27 in a node with its job and Tier-2 scenario, both 2026-09-22), the Supervisor view and its resource; A18's check reading targets as goldens | items 8, 9, 15, 20's remainder; scenarios 13, 20, 30, 31 | **Met 2026-09-22**: scenarios 20 and 30 green in Tier 2 against MySQL, 31 in Tier 1 (slice 28), and an attempt paused, answered by a person and resumed. The phase's remaining deliverables — the caches, the prune job, `Outputs` as rows, the Supervisor and its resource, A18's targets — built in slice 29. Owed out of the phase: scenario 13's Tier-2 filter (the harness mocked the processor filter service — built in D, 2026-09-23), the Supervisor's buttons (with phase F's other interactions) and the regression set as a stream (phase E) |
| **D. Pipeline integration** | §12 items 1 and 2 in `stroom-pipeline`, built as part of this work (design 01 §12); the learned boundary as a `SplitFilter` in the written fragment and the transform asked per record (item 25); the `XMLFragmentParser` step for markup without a root (item 26, scenario 49); the fragment run as a pipeline for judging and for serving; the reprocessing mode that reads bindings (item 7); input spans (item 21); replay unit derived and checked (item 18); the transformation stage's element and one transform per stream (item 4's remainder) | items 1, 2, 4, 7, 18, 21; scenarios 13, 18, 19, 38, 50 | **Met 2026-09-23**: scenarios 18 and 19 run without the interim glue design 02 §6.1 records; scenario 38 reads a record back by span; scenario 13's Tier 2 half takes one feed from unknown shape to learned, promoted, released and reprocessed on a running node. With them, item 4's second element (scenario 50) and one transform per stream — §7 slice 4b |
| **E. Safety at volume** *(deferred — design 01 §15.2)* | error mode (A24) beyond the document setting and the feed-state table that C already built; the A27 processor gate; rate limiting across documents beyond A44's spend breaker, which C built and which stays; the A23 review job, its audit stream and the `Critique` kind; the regression stream as a real stream with retention | items 12, 14, 16 and item 6's remainder; scenarios 23, 24, 32 | **Not part of the MVP.** The prototype is one node with a person driving it; what it needs instead — that a failed attempt loses no data — is the sentinel and the ledger, built in C |
| **F. Operator surfaces** | the plan editor (item 24); the stage pane in the stepper (A30, item 19); Approve and Reject on the Routing tab (item 13's remainder); the ledger view grouped by shape; the supervisor's message and *improve* over a rule that is already serving (A46, item 29) | items 13, 19, 24, 29; scenario 33 | GWT compiled; scenario 33; a person learns, reviews and approves a feed from the UI alone, no REST calls by hand, and improves a rule that was already serving — the hint reaching the model and the incumbent serving throughout |
| **G. Real-scale trial** *(after the MVP)* | design 01 §15.1's conditions of deployment first — redaction (A38) built and measured as a harness dimension, the restricted XSLT function library (item 10), the regression stream's retention — then one real feed with an existing, thousand-line stylesheet as the incumbent, replayed through the feature; thresholds and A6 set from its evidence; the rulings of §4 given from what it shows | design 01 §15.1 and §15.3; A6, A16, A19; the thresholds design 01 §2.1 left open | the promotion gate holds on real data — no promotion a person reviewing the transcript would have refused; the review-mode target page judged readable by an operator who did not build it |

| **H. The learning flow** (design 05; A81–A90) | the plan renamed the learning flow, with the exits `end`, `park` and `give-up` and the flow's check naming the step each problem is about; a Flow tab drawing the flow as a vertical flowchart in SVG, read-only first and then edited in place with the question text in context; the attempt's path drawn in the Supervisor; the grid retired | design 01 §12 item 24's remainder: the transitions drawn, the problems inline, the paths over the graph | a person composes the escalating example on the chart from an empty flow without typing the grammar, and reads a parked session's path in the Supervisor |

**The MVP is A, B, C, D and F.** Phases A and B are the proof that the mechanism works and works across
formats; C and D make it real machinery rather than a harness; F is the UI without which there is
nothing to explore. C and D are independent of each other and may interleave; F depends on C and D.

**E is deferred and G comes after** (design 01 §15, added 2026-09-23 at the owner's direction). What is
being built is a prototype on fixture data in an environment that is not security sensitive: E's
controls are for a feature running unattended at volume, and G is the gate to real data, with §15.1's
conditions of deployment as its entry. G's fixture — a real feed and its stylesheet — should still be
sought early, since finding one takes longer than building anything here.

## 3. Formats, and what each one tests

Each format is a fixture drawn from public documentation samples, never from real data; a golden
event set hand-written against the event-logging schema; a scripted scenario that states what the
plan must ask and the stage must do; and a row in the live harness. Design 02's catalogue carries
them as scenarios 43–48. What each is *for*:

| Format | Fixture | What it tests that nothing before it did |
|---|---|---|
| **Syslog** (RFC 3164 and RFC 5424 in one feed) | ~40 lines from two senders, both forms, with a `System` header | Two record kinds under one shape with the default key — one plan and one variant must cope with both (design 01 §5) — and the same feed with the signature in the key, where two rules result. The chain question with a `Format: syslog` header |
| **auditd** (`key=value`, multi-line events) | ~30 events of 2–4 records each, joined by `msg=audit(ts:serial)` | The split question in earnest: a record is several lines that share a serial, so a line-per-record split scores full coverage and fails wholeness and yield against the input's own structure. Field preservation on `a0`–`a3` hex arguments the target needs decoded |
| **Windows security events** (XML export) | ~20 `Event` elements: `System`, `EventData/Data[@Name]`, several `EventID`s (4624, 4634, 4688) | The XML split on a real nested document; `Data[@Name]` to typed fields is the anti-degeneracy case in the wild — a transform that dumps `Data` as `Data` validates and extracts nothing; the `EventID` decides the `EventDetail` branch, so event classification is exercised |
| **JSON** (lines, and one array document) | ~30 records, two kinds, nested objects | The `JSONParser` runner that scenario 2 has promised since the catalogue was written; the split question for JSON — the array whose items are records (A31) — as a third guard beside `text` and `xml`; no configuration question for the parser |
| **Fixed-width** | ~30 lines, six columns by position, no delimiter | A split with nothing to split on: the regex must be positional, coverage by characters counts every column, and a column dropped at the parser is caught by preservation, not coverage |
| **CSV with embedded newlines and quotes** | ~20 records, a quoted field spanning lines, escaped quotes | The boundary case A36 was tuned on: a line-based split cuts a record in two, scores well on line coverage and fails wholeness; the golden proves the DS3 quoting rules the worked example must teach |

Each fixture is also the seed of the regression stream a promotion would write (A18), so the goldens
are worth writing carefully: they are what "correct" means for that format until a real feed says
otherwise.

## 4. Rulings owed, and where they fall due

| Ruling | Status | Falls due |
|---|---|---|
| A20, A21, A22 | Ruled as built, 2026-09-22 (design 01 §13) | — |
| A39, A40 | Ruled 2026-09-22 (design 01 §13): the escalating example splits every input; a parser refused on yield goes back to the split | Measured on the next live comparison of plans |
| A16 anti-degeneracy | Proposed; built as a gate | Phase G, from real evidence (design 01 §15.3); the Windows fixture in phase B is the rehearsal |
| A19 no `ignoreErrors` | Proposed; enforced at the compile gate | Phase G, with A16 (design 01 §15.3) |
| A6 signature normalisation | Open | Phase G (design 01 §15.3); the syslog fixture in phase B is the rehearsal |
| A23–A28, A30 | Proposed, the owner's | C built A26 and A28; F builds A30 and A25's buttons. A23, A24 and A27 are deferred with phase E — design 01 §15.2 |
| A41–A44 | Ruled 2026-09-22 (design 01 §11.4): rules as rows, lease per shape, one learner, cluster-wide spend | Built in C: the rules as rows in slices 20–21, the lease and the spend counter in slice 23, the lease becoming the attempt's claim (A45) in the audit of slice 25; the spend's policy with A24 in phase E |
| A45 | Ruled 2026-09-22 (design 01 §13): the attempt row is the lease, once attempts are durable | Built with A28, in C: the claim in slice 25, and the shape's lease columns dropped in its audit |
| A46 | Proposed 2026-09-22 (design 01 §13), the owner's, from the live run of item 25: a supervisor's message into the learning, and improving a rule that is already serving. Its three decisions ruled the same day | Phase F (§12 item 29), with the view's other interactions; the `GUIDANCE` rows and the override's ledger entry belong with the first slice that touches those tables |
| A47 | Ruled 2026-09-22 (design 01 §13), the owner's, closing the audit of item 25: the configuration question shows one record of each kind the split found, where the plan has not yet settled its targets | Next, in D: it is a change to the question and its scenarios, not to the fragment |
| Thresholds (floor, coverage, yield, relearn) | First values recorded, design 01 §2.2, from the goldens and run 7; coverage never decided a phase B outcome, yield per line did | Settled in phase G from real feeds |

## 5. Where phase B stands

| Format | Slice | State |
|---|---|---|
| Syslog | 13, 2026-09-21 | Fixture, golden, two splitters, stylesheet; scenario 43 in both keyings green; live row `08-syslog` awaiting the 2026-10-01 run. Finding: a `<PRI>` prefix must not read as markup (`YieldScorer`) |
| auditd | 14, 2026-09-21 | Fixture, golden, three splitters, stylesheet; scenario 44 green; live row `09-auditd`. Findings: a multi-line record is stated through the expected yield per line; a lines basis must judge the transform record for record; DS3 group expressions anchor with `^` |
| Windows security events | 15, 2026-09-21 | Fixture, golden, two stylesheets; scenario 45 green; live row `10-windows-security`. Finding: kinds of XML record are told apart by structure, and events of different `EventID`s share one — met in part by slice 16, where a markup record's kind carries its naming attributes (`Data/@Name`) and the three kinds are three targets; a kind by a discriminating value (`EventID`) is still owed |
| JSON | 16, 2026-09-21 | Fixtures, golden, stylesheet; the `JSONParser` runner, the `json` guard, the array split (`root` reaches a top-level array's items); scenarios 2 and 46 green; live rows `11-json-lines` and `12-json-document`. Run 7 (design 02 §6.3): the lines promoted under both plans; the document was given up on yield under target-first and promoted at 1.000 under escalating with one event from twelve — a wrong promotion. **Slice 19, 2026-09-22: the boundary on the rule and in the stage's count and yield; run 8 promoted the document under both plans, all twelve events.** Still owed: the learning sample cut at the array's items, as `wholeChildren` does for XML |
| Fixed-width | 17, 2026-09-21 | Fixture, golden, two splitters, two stylesheets; scenario 47 green under the escalating plan; live row `13-fixed-width`. No code changed: coverage at 1.0 says nothing, the rule on the outcome escalates, preservation catches the dropped columns |
| CSV with embedded newlines | 18, 2026-09-21 | Fixture, golden, two splitters, stylesheet; scenario 48 green; live row `14-csv-multiline`; the DS3 rules teach a quoted field. Finding: the line split is refused by yield per line, not wholeness, which is a character share. Owed: the learning prefix is cut by lines and can cut a multi-line text record at the sample's end — cut it at the settled boundary once a split is learned |
| Redaction (A17, A38) | deferred — design 01 §15.1 | Ruled 2026-09-21 how it works; the build was deferred the same day, and 2026-09-23 it became a condition of deployment rather than a phase: it is due before any real feed is pointed at any model, and the prototype's samples are this repository's fixtures. The document setting was withdrawn 2026-10-03: a document that said `REDACTED` while nothing redacted was a promise the build did not keep |

Phase B's exit criterion was met by run 7 (2026-09-21 to 22, design 02 §6.3): five of the six formats
learned live to the floor under each of target-first and escalating, all six under one or the other;
run 8 added the JSON document under both after slice 19. The first values of the thresholds are in
design 01 §2.2. What B leaves: the learning sample cut at the settled boundary (above), the two plan
questions A39 and A40, and the real feed to be sought for phase G.

## 6. How a slice is cut from this

A slice is the smallest piece of a phase that leaves every scenario green and design 02 §6.1 able to
say what it did. Slice 12 was the whole of phase A; phase B was six slices, one per format, each
carrying its fixture, golden, scenario and live row. Since C the rhythm has been one slice, one audit
(the owner's code review), one commit — which has caught more than the scenarios did, and is why the
audit is part of the slice rather than a phase of its own.

What is left is cut from §7 rather than from a phase, because the remaining work is a sequence and not
a set: each of the ten leaves the feature usable, and stopping after any of them leaves something that
can be judged.

## 7. Finishing the MVP: the order, and why

*Added 2026-09-23, after design 01 §15 scoped the work to a prototype. The phases say what belongs
together; this says what to build next and why that order and not another. The principle: **close the
loop before dressing it**. Every surface in F shows something the loop does, so a surface built over a
loop that does not close shows half a story — and the one thing an MVP has to do is let someone watch
the whole thing happen.*

| # | Slice | Phase | Why here |
|---|---|---|---|
| 1 | **The reprocessing mode that reads bindings** (item 7) | D | The loop's last missing link. A stream that arrives before its shape is learned is sentinelled and recorded in the ledger, and a promotion releases it — but nothing yet reprocesses what was released, so the payoff of "nothing is held" (§5.2) cannot be seen. Everything in F is more interesting once this works |
| 2 | **Replay unit derived and checked** (item 18) | D | Small, and item 7 needs it: what is reprocessed is the fragment's replay unit, and today it is neither derived from the fragment nor checked against the stage's position |
| 3 | **The conversation's candidate runs on the real pipeline** (item 2's remainder) | D | The bound path already runs as a pipeline; a candidate is still tried with the module's step runners while it is being written. A node should judge with the thing that will run, Tier 1 keeping the stand-in, with an agreement test per input shape (the owner's decision, 2026-09-23). Third rather than first because the promotion gate already runs the pipeline, so a candidate the pipeline refuses is caught before it is promoted — this improves the *feedback*, not the safety |
| 4 | **Input spans in the bindings** (item 21, scenario 38) | D | The evidence trail: a record's start and end in the source, so a fault at any event can be read back and relearned with its input in hand. It is also what the Supervisor view and the stepper pane show, so it comes before them and after the loop |
| 4b | **The transformation stage's element, and one transform per stream** (item 4) | D | Not in the original ten: the two things phase D surfaced and did not schedule. The `RECORD` position of A1 could not be drawn, because a supervisor declares the parser role and the editor refuses a parser under a parser; and the stream was transformed twice on a node, once to judge and once to serve. Taken together because the same run now does both jobs and the second element is what needs it. Done 2026-09-23, design 02 §6.1 |
| 5 | **Scenario 13's Tier 2 filter** | owed out of C | The processor-filter half of the ledger-and-release scenario, which the C harness mocked. Cheap once the reprocessing of slice 1 exists, and it is what proves the release end to end . Done 2026-09-23, design 02 §6.1 |
| 6a | **Stepping is a dry run, and the details slot** (A30, item 19) | F | The server half, and the dry run before anything else: until it existed, opening the stepper on an untaught feed would have taught it — a model call spent and a rule bound by looking. Done 2026-09-23, design 02 §6.1 |
| 6b | **The stage pane itself** (A30, item 19) | F | The first surface, because it is where a person already goes when a pipeline puzzles them: shape, decision, fragment, verdicts and conversation, in the place they debug. Done 2026-09-23, design 02 §6.1 |
| 6c | **Stepping into the fragment** (A30, item 19) | F | The tree expanding a supervisor to its fragment's chain, so that `DSParser -> XSLTFilter` step like any other elements. Separated from the pane once it was clear it is not a client change: the fragment runs as a *nested* pipeline whose elements are not in the stepped pipeline's model. **Done 2026-09-23** (design 02 §6.1, scenario 33) — and *not* by building the fragment under the stepping controller, which the note above guessed at: that controller inserts a split the fragment does not have, which is exactly what `PipelineCapture.captureSplitDepth` warns must not happen to a run being judged, and the run being stepped is the run being served. The fragment goes on running under its own capture and hands what it captured to the step, through the details slot 6a built |
| 7 | **Approve and Reject on the Routing tab** (item 13's remainder) | F | Review mode is built in the stage and has no buttons. Two buttons and a confirmation turn a built behaviour into a usable one . Done 2026-09-23, design 02 §6.1 |
| 8 | **The Supervisor view's actions and the ledger view** (A28's remainder; the ledger view since removed, design 06 A131) | F | The cross-document table exists; what it lacks is the doing — answer instead, re-run from here, retract, widen, re-learn — and the ledger grouped by shape beside it. **Done 2026-09-23** (design 02 §6.1): Approve, Reject, re-learn and the ledger, then *answer instead* / *edit and re-run from here* as one editor on the selected turn, and *retract*. *Widen selector* turned out to be built already — widening is editing a rule's expression, which the Routing tab's Edit does — so what was owed was the Supervisor's own acts, not an endpoint |
| 9 | **A supervisor's message, and improve** (A46, item 29) | F | The newest ruling, and the one that wants everything above it: a hint is guidance the next question carries, and *improve* is a relearning from the incumbent over the regression set. It is the first thing in the plan that makes the feature collaborative rather than automatic. **Done 2026-09-23** in three (design 02 §6.1): 9a the message, 9b the improvement, 9c the surfaces — the serving-rules list ordered by traffic with *improve* and *hint* on the row, and *hint* on an attempt. Owed: an improvement should be taken by the deferred worker and runs in the request instead, which wants the attempt to remember that it is one and to find its sample in the regression set |
| 10 | **The plan editor** (item 24) | F | Last, deliberately. It edits the mechanism itself, and by this point the mechanism has been driven through every other surface — so the editor is built over something proven rather than over a grammar that might still move. **Done 2026-09-24** (design 02 §6.1), at the owner's direction and about a week before item 24's own sequencing advises: that note waits for the 2026-10-01 live run over an escalating graph. The bounded risk was taken knowingly — the forms are driven by the closed lists, so an added or renamed value flows through, and only a structural change to a step would mean rework |

**What ends the MVP.** A person can drive the whole loop from the UI on a running node, against a feed
of fixture data: an unknown shape is learned and bound, a stream that arrived too early is released and
reprocessed, a rule is reviewed and approved, a rule that is serving is hinted at and improved, and a
failed learn is visible as a sentinel with its reason. No REST calls by hand, and nothing in the loop
that only a test can reach.

**What it does not include**, and should not be confused for readiness: design 01 §15.1's conditions of
deployment. The MVP ends with a prototype worth judging, not a feature worth switching on.
