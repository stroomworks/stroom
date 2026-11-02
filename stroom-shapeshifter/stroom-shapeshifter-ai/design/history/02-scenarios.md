# Scenarios: the supervisor's executable specification

> **Archived.** This is the design as it was written and ruled, kept for its reasoning and history. The current design is [the design set](../README.md); code comments that cite this document by number and section still resolve here.

*Drafted 2026-09-17. Companion to `01-intelligence-in-the-pipeline.md`, whose section numbers and
rulings (A1–A29) this document cites. Status: proposed; §7 lists what is owed a decision. Brought
into line with design 01's A23–A29 and its 2026-09-17 revisions the same evening.*

> *Superseded in part by design 06, phase J (2026-10-07 and 08).* The pipeline is the unit of learning
> (A104): a **learner** is one Shapeshifter AI element in one pipeline (A114), and what serves it is its
> **binding** (A117, A124, A140). Shapes, the learning key, the shape signature, the routing table and its
> selectors, pinning (A138) and the ledger (A131) are gone; a stream waits as a deferred task, which a
> promotion wakes by its pipeline (A129). Where this document says a shape, read a learner; where it
> says a rule, read a binding. It is kept as the record of how the design arrived where it is.

## 1. Why scenarios, and what one is

Design 01 describes a stage that routes a stream, learns a fragment when it must, scores what it
learned, promotes it or gives up, and rewrites its routing table. None of that yet runs end to end:
the Shapeshifter AI document, the A21 conversation, two step runners, the fragment writer, the scorer SPI
with three scorers, `Router`, `Quarantine`, `RegressionSet` and `Stage` exist (§6.1); the remaining
scorers, the supervisor element, the runtime tables, durable attempts and the Supervisor view do not
(design 01 §12). The order those are built in matters less than the thing that decides whether they are right.

A **scenario** is that thing. It is a complete, deterministic run of one stage over given input with
a simulated model, asserting on everything the design says should happen. Scenarios are written
before the machinery, and each piece of machinery is built to make the next scenario pass. When the
supervisor element finally exists, the same scenarios run against it inside a real pipeline. The
scenarios are the specification; the design prose is the rationale.

One scenario has four parts:

| Part | What it is | Where it comes from |
|---|---|---|
| **Document** | A `ShapeshifterAiDoc`, built with the builder, exactly as an operator would configure it — scorers with weights and parameters, promotion floor, allowed elements, routing table as it stands before the run | `ShapeshifterAiDoc.builder()` in the scenario; nothing hand-written in JSON |
| **Input** | One or more streams: the data, plus the metadata a stream carries — feed, type and the receipt headers (`Format`, `System`, …) that A22 can route on and that the learning key (A29) may include | The `TestDS3` corpus for extraction; XML records for transformation; hand-written where the corpus has no case |
| **Script** | The simulated model: an ordered list of *expected question → reply*. Every question the conversation asks is checked against the next expectation, and the reply is what the script says. A question the script did not expect, or a script with lines left over, fails the scenario | Replies are real Data Splitter and XSLT documents in test resources, including deliberately bad ones |
| **Expectation** | What must be true afterwards: the questions asked and in what order; what each carried; the fragment written; the output produced; the score of every scorer; whether promotion happened; the routing table after; what was sentinelled and what the ledger holds; the transcript that would be audited | Assertions in the scenario |

The script is what makes a scenario deterministic and what makes it a specification: it says not only
what the model answers but *what the supervisor must have asked* to get there. A scenario in which the
transform question fails to carry the parser's real output (A21 step 2) fails at the script, before
any output is compared.

## 2. Two tiers, one set of scenarios

The same scenarios run in two places, because "the learning loop is right" and "the learning loop is
wired into a Stroom pipeline correctly" are different claims with different costs.

**Tier 1 — the stage in the module.** `stroom-shapeshifter-ai`'s own tests: no database, no node,
in-memory document stores, the step runners in place of `PipelineFactory`. Runs in seconds. This is
where the conversation, scorers, promotion gate, routing decision and quarantine are specified and where
almost every scenario lives. It is honest about one thing: the fragment is run by the step runners,
which drive the Data Splitter and Saxon directly, so an element outside `DSParser`/`XSLTFilter` and
any Stroom-specific behaviour of those elements (XSLT functions, reference data, `xsltNamePattern`)
is out of reach here.

**Tier 2 — the stage in a pipeline.** In `stroom-app`, extending `AbstractProcessIntegrationTest` as
`TestXMLTransformer` does: a real database, real stores with explorer nodes, the real
`PipelineFactory` building a pipeline that holds the supervisor element, real `SchemaFilter` against
the event-logging content pack, output to a real stream. Runs in minutes. This is where the claims
Tier 1 cannot make are made: that the learned fragment merges and runs as a pipeline; that the
supervisor element routes, captures and emits; that bindings land in output-stream metadata (§7.3
rule 3); that the sentinel is a real error stream and the ledger a real row (A26). A handful of scenarios run here —
the happy path, one failure, one promotion — not the catalogue.

The scenario definitions — the script DSL, the simulated model, the document builders, the input and
reply resources — live in the module's `testFixtures` source set (Gradle `java-test-fixtures`, which
`stroom-query-language` already uses), so both tiers consume one definition and cannot drift.

## 3. The simulated model

```java
Script.of()
    .expect(chain().allowing("DSParser", "XSLTFilter").withKey("Format", "CSV"))
        .reply("DSParser -> XSLTFilter")
    .expect(configuration("DSParser").withInput(sample))
        .reply(fenced(corpus("001_csv_with_header").configuration()))
    .expect(configuration("XSLTFilter").withInput(corpus("001_csv_with_header").expectedRecords()))
        .reply(fenced(resource("csv-logon.xsl")))
```

An expectation is a predicate over the `Question` plus a description; a mismatch fails with both the
expectation and the actual question printed. `Script` implements `Advisor`. It records the transcript
it saw, so a scenario can also assert on the whole conversation afterwards — what feedback the third
question carried, that the chain question was not re-asked.

Multi-round scenarios are just longer scripts. The model's second answer is scripted knowing the
first will fail, and the expectation on the second question asserts *why* it was re-asked: the
feedback must name the compile error, or the scorer that lost the marks, or the assertion that
failed. That is the design's promise in §10 — feedback goes to the step that failed, with the
diagnostics — turned into a check.

The script is deliberately dumb. It does not look at the question to decide what to say; it says the
next line. Anything cleverer would be a model, and the point is that the test's outcome is fixed by
its author. One exception, and it keeps the rule's point: since A31 a conversation may ask what one record
is and what each kind of record becomes before it asks for any configuration, and a script that had to
spell those out would restate its own splitter and stylesheet by hand. `Structure` answers them
*from* the configurations the script will give — it runs the scripted splitter over the sample, runs
the scripted stylesheet, and returns the event at the record's position, or `none` — so the answers are
still fixed by the author, derived rather than typed, and a scenario about the split or the target
scripts those lines itself.

## 4. What a run does

A Tier 1 run is one call, `Stage.run(policy, streams)`, over machinery that mostly does not exist
yet. Spelling it out fixes what has to be built:

1. **Route.** For each stream, compute its learning-key value — feed and type by default, with the
   shape signature of the stage's *input* where the document's key includes it (A29) — and evaluate
   the routing table's selectors in order against the stream's meta fields, headers and signature
   (A22). A match binds the fragment. A match on a reserved rule, or a shape the ledger records as
   given up, is a sentinel. No match is an unknown shape: the variants already bound for the same
   feed and type are tried first (design 01 §6), and one that clears the floor is bound
   *provisionally* and handles the stream with no question asked; only if none fits, and
   `learningMode` is `AUTOMATIC`, does the stage learn — with `DISABLED` it is a sentinel.
2. **Learn.** Split the shape's records into a learning sample and a held-out sample (A14). Run the
   document's plan (A33: its steps — direct, target-first, or its own — in its words) over the
   learning sample. Score each candidate with the document's scorer set. Feedback to the failing step
   carries the scorer's diagnostics. Stop at the step's candidate limit (`maxAttempts` unless the step
   says otherwise) or at a candidate whose gates all pass and whose weighted total meets every
   threshold. A bound shape whose rolling per-record score has fallen below `relearnThreshold`
   learns the same way, its incumbent serving meanwhile (A29). In `DEFERRED` mode the attempt is
   recorded `AWAITING_MODEL` and the stream sentinelled; the worker runs the conversation later (A28).
3. **Judge.** Score the candidate on the held-out sample. Promote if the weighted total reaches the
   floor (A15), every gate passes, and — where an incumbent is bound — the candidate is not worse
   than it on the held-out sample nor on the regression set (A18). Otherwise keep the incumbent, or
   give the shape up if there is none. Where the shape has too few records for a held-out split, a
   candidate that clears the floor on what there is is bound *provisionally* — it handles the stream
   and waits for held-out records (A5, design 01 §6) — rather than left unbound.
4. **Write.** On promotion: write the fragment and its documents (new documents, §7.3 rule 1), append
   a rule whose selector is the document's learning key (A29) to the routing table unless a pinned
   rule already covers the selector — as a *draft* in review mode (A25) — record the promotion time and score, add the accepted records to the
   regression set, and release the shape: clear its ledger entry and request reprocessing of the
   inputs it named (A12, as §5.2 restates it — nothing was held; a draft releases nothing until
   Approve). Save the document.
5. **Emit.** Run the bound fragment over the stream and produce output, with the bindings that
   produced it — provisional or not — recorded alongside (§7.3 rule 3); or, for a shape the stage
   will not process, write the sentinel: an `ERROR` naming the shape, the candidate scores and the
   reason, and a ledger row (A4 as restated).

In Tier 1 the streams, sentinels, ledger, reprocess requests and regression set are in-memory
records that the scenario inspects. In Tier 2 they are what the supervisor element does inside a
pipeline: error streams, rows in the A26 tables, and real reprocess filters.

## 5. The scenario catalogue

Ordered by what each needs built; each one is unlocked by the machinery the previous one forced.
"Corpus" is `stroom-pipeline`'s `TestDS3`.

> *Design 06 A131 (2026-10-07): there is no ledger. Where a scenario below says a stream is ledgered,
> released or "on the ledger", read that it errors as an ordinary error stream (a given-up learner) or
> waits as a deferred task that a promotion wakes (A129). The scenarios' code asserts it so.*

| # | Scenario | Given | Script | Then | Needs |
|---|---|---|---|---|---|
| 1 | **Learns a CSV feed** | Document: default; scorers Compile (gate), Input coverage, Yield. Stream: corpus 001, `Format: CSV` | chain, DS3, XSLT — all right first time | 3 questions in order; transform question carries the parser output; fragment `Source → DSParser → XSLTFilter`; output equals golden events; routing table gains a `Feed AND Type` rule (the default learning key, A29); nothing sentinelled | Scorecard, yield scorer, routing decision, held-out split, promotion, table write |
| 2 | **Header names the parser** | Stream `Format: JSON` with a JSON body; learning key `Feed AND Type AND Format`; allowed elements include `JSONParser` | chain reply `JSONParser -> XSLTFilter` | chain question carried the `Format` value, because it is in the key and shown means bound (A29); no configuration question for the parser; fragment has `JSONParser` with no document; the learned rule binds on `Format` too | `JSONParser` step runner |
| 3 | **Compile failure, then success** | as 1 | DS3 = corpus `008_invalid_xml_FAIL`, then corpus 001 | 4 questions; 3rd is DSParser again with the compile diagnostic and the previous configuration; chain not re-asked | already built (`TestConversation`); re-homed as a scenario |
| 4 | **Discarded input, then coverage fixed** | scorers add Input coverage threshold 0.9 | a DS3 that drops the header line and half the fields, then a full one | re-ask carries coverage score and the uncovered ranges; second passes | coverage as a scorer with feedback, not only a measurement |
| 5 | **Degenerate transform is refused** (§8.3, A16) | scorers add Schema conformance (gate) and Extraction quality (gate, required `EventSource/User/Id`) | XSLT that emits the skeleton + `Unknown` + every field as `Data`, then a real one | first candidate validates (schema gate passes) but fails extraction quality with typed-element ratio and `Unknown` rate in the feedback; second promoted | schema-conformance scorer over event-logging pack; extraction-quality scorer |
| 6 | **Business rule** | scorers add Business rules: "interactive events name the user" | XSLT that omits `User/Id` on logons, then one that includes it | feedback names the assertion; second promoted | business-rules scorer |
| 7 | **Below the floor is not promoted** | `promotionFloor` 0.95; Yield threshold 0.5 | XSLT that drops every other record (yield 0.5) three times | abandoned at the candidate limit; shape given up — sentinel and ledger row; routing table unchanged; transcript has 5 questions; the last feedback shows yield 0.5 against expected 1.0 | ledger; abandonment path |
| 8 | **Not a regression** (A15, A18) | routing table already binds v1 (score 0.90); regression set holds 10 accepted records | a v2 that scores 0.97 on the learning sample but 0.85 on held-out | v1 kept; v2's documents not written; transcript audited | held-out judgement; regression set |
| 9 | **A better candidate replaces the incumbent** | as 8 | a v2 that scores higher on held-out and on the regression set | rule rebound to v2; v1's documents untouched; promotion time and score updated | table rewrite semantics |
| 10 | **Pinned rule is never rebound** | as 9 with the rule pinned | as 9 | v2 written? no — no learning is attempted for a pinned, matched rule | pin honoured at routing |
| 11 | **Given-up shape does not consult the model** | shape given up by scenario 7 | *empty script* | stream sentinelled; zero questions | ledger consulted before learning |
| 12 | **Learning mode disabled** | `learningMode: DISABLED`, no matching rule, no bound variant for the feed and type | *empty script* | sentinel; zero questions | §11 opt-in |
| 13 | *(revised 2026-10-06: the streams that waited are woken by the promotion, not released from a ledger)* **Promotion releases the quarantine** (A12) | a shape in the ledger with two input streams recorded against it; then a run that learns it | as 1 | the ledger entry is cleared and a reprocess request names exactly those two inputs; nothing was held — the earlier runs produced error streams naming the shape | ledger; release as a reprocess request (Tier 1 records the request; Tier 2 creates the filter, and a second pass over the task queue processes exactly the stream that waited — built 2026-09-23) |
| 14 | **Too few records to judge** (A14) | `minRecordsPerShape` 10; stream of 3 records | as 1 | learned; the candidate clears the floor on the 3 records so it is bound *provisionally* and handles the stream, with the binding marked provisional in the output; not promoted; attempt `PROVISIONAL`; a later stream of 10 records supplies the held-out split and promotes it | provisional binding (A5, design 01 §6) |
| 15 | **Every budget question** | `maxAttempts` 1 | one bad reply | abandoned after one question, reason names the step | already built |
| 16 | **Model reply is not a document / not a chain** | | prose, then a document | refused with feedback | already built (`TestConversation`); re-homed |
| 17 | **Fragment with a destination is refused** | routing table hand-edited to a full pipeline | — | the save fails naming the element | already built (`TestFragmentCheckImpl`); Tier 2 makes it real |
| 18 | **(Tier 2) Learns a CSV feed in a pipeline** | as 1, but the document, feed and streams are real content and the supervisor element sits in a real pipeline | as 1 | output stream equals golden; bindings in the output stream's meta; fragment opens in the explorer under the feed's folder | supervisor element, `PipelineFactory` harness (§12 items 1, 2, 4), bindings metadata (item 7) |
| 19 | **(Tier 2) Degenerate transform in a pipeline** | as 5 | as 5 | as 5, with the real `SchemaFilter` doing the validating | as 18 |
| 20 | *(revised 2026-10-07, design 06 A131: there is no ledger; the stream waits on a deferral row)* **(Tier 2) Sentinel is an error stream and a ledger row** | as 7 | as 7 | no output stream; an error stream with one `ERROR` naming the shape, the candidate scores and the reason; a `shapeshifter_ledger` row naming the input's meta id; nothing held anywhere | as 18, the A26 tables |
| 21 | **Two supervised stages: extract, then transform** (design 01 §3's own picture) | Two documents, both with the signature in their learning key. *Extraction*, at the source so its variants replay per stream (A1 revised): allowed `DSParser`/`JSONParser`/`XMLParser`, scorers Compile (gate), Input coverage, Yield per line. *Transformation*, after the parser so per record: allowed `XSLTFilter` only, scorers Compile (gate), Schema conformance (gate), Extraction quality (gate), Yield per record. Stream: corpus 001 with `Format: CSV` | stage 1: chain, DS3. Stage 2: XSLT only — no chain question, one allowed element | Stage 2's input is stage 1's `records:2` output with the headers carried through; stage 2's signature is the XML element skeleton of its input record, stage 1's the text one (computed on each stage's input, design 01 §4); stage 2 learns on a seeded shuffle of the records and is judged on the held-out records, rebuilt as `records:2` documents; each document's routing table gains its own rule; the final output equals the golden events. A second stream binds stage 1 without learning while stage 2 is still learning, and a stage-2 abandonment gives the record shape up without re-asking stage 1 | records split and rebuild; a stages runner; for the full form, the stage-2 scorers of scenarios 5–6; the structural form runs with Compile + Yield alone |
| 22 | **Review mode: a draft waits, Approve promotes** (A25) | document `promotionMode: REVIEW`, otherwise as 1 | as 1 | rule appended as a draft; a second stream of the shape is *not* bound, produces an ERROR naming the draft rule, fragment and shape, and enters the ledger with no question asked; Approve — from the Routing tab or the Supervisor view (A28) — makes the rule active, sets promotion time, and issues a reprocess request for the ledger's inputs; Reject leaves the shape given up with the reason recorded | `promotionMode`, `draft`, router skipping drafts, the ledger, approve/reject operations |
| 23 | *(withdrawn 2026-10-06: A57 restates error mode as the raised flag of the backlog, and `errorModeAfter` is gone from the document)* **Error mode after a streak** (A24) | document `errorModeAfter: 2`; two streams of different shapes | two abandoned attempts | after the second, the feed is in error mode; a third stream produces a fatal error stream and *no question*; reset from the Supervisor view's status strip closes it and the third stream learns normally | `shapeshifter_feed_state`, fatal error output, reset (A24, A28); half-open retry as a variant |
| 24 | *(deferred with A23 — design 01 §15.2)* **AI review flags a dishonest transform** (A23) | scorers add AI review (sample rate 1000/1000, threshold 0.7); a promoted fragment that swaps logon and logoff | judge replies scoring 0.2 with a critique | the sampling job writes a finding; the shape is marked for relearning; the next attempt's question carries the critique as feedback; promotion was never blocked by the judge alone | `AI_REVIEW` scorer, the sampling job, `Critique` question, relearn trigger |
| 25 | **An existing binding fits a new shape** (design 01 §6) | key includes the signature; a rule binds v1 for shape X; a stream of shape Y arrives | *empty script* | v1 is run over Y and clears the floor; Y bound to v1 provisionally, zero questions; once Y has `minRecordsPerShape`, promoted with held-out satisfied by construction | bound-variant trial before any call |
| 26 | **A reserved rule gives the shape up** (design 01 §3) | first rule `Feed = f` with no fragment | *empty script* | sentinel with reason *reserved*; ledger row; zero questions | reserved-rule routing |
| 27 | **A falling score triggers relearning** (A29) | default key; a rule bound; `relearnThreshold` 0.8; a stream in which a second record kind fails schema conformance for 30% of records | *empty*, then as 1 | no new shape; the failing records go to the error stream and not the ledger; the shape's rolling score falls below 0.8 and it is marked for relearning; the next stream starts an attempt while the incumbent still serves it; the promoted candidate replaces the rule | rolling score on the shape row; relearn trigger |
| 28 | *(revised 2026-10-06, A101: a provisional rule is not retracted by the floor; the scenario now holds that it serves as it stands)* **A provisional rule is retracted** (design 01 §6) | scenario 14 after its provisional binding; then 10 records that the candidate scores below the floor on | as 1, then *empty* | the rule is retracted, the shape is unknown again, and a reprocess request names the streams that carried the provisional binding, as-current | retraction path |
| 29 | **Disabled still selects** (design 01 §11.1) | `learningMode: DISABLED`; as 25 | *empty script* | Y bound to v1 provisionally and later promoted; the model never asked | selection without a model |
| 30 | **Deferred: the worker learns later** (A5, A28) | `executionMode: DEFERRED`; unknown shape, nothing fits | as 1, but answered by the worker | the stream is sentinelled and an attempt recorded `AWAITING_MODEL` with zero questions asked in the task; the worker advances the attempt against the script; promotion issues a reprocess request for the ledger's inputs | durable attempts; the worker |
| 31 | **A person answers a turn** (A28) | as 3 | the model's bad DS3, then a person's good one | the attempt pauses after the failed candidate; a person's *edit and re-run* replaces the DSParser answer and the conversation resumes from that turn; the transcript records who answered each turn; promoted | resumable conversation; per-turn answerer |
| 32 | *(deferred with A27 — design 01 §15.2)* **(Tier 2) The processor waits** (A27) | a filter depending on the document; scenario 23's feed in error mode | — | no tasks are created for that feed while `shapeshifter_feed_state` says `ERROR`; another feed under the same document is processed; reset resumes task creation from where it stopped | A27 in `ProcessorTaskCreatorImpl` |
| 33 | **(Tier 2) Stepping the supervisor element** (A30, design 01 §11.7) | scenario 18's pipeline, with a second rule that does not match the stream; a stream of an unknown shape as a variant | step to the element with the bound stream, then with the unknown one | the element's step data carries `ShapeshifterAiStepDetails`: the shape, the match path (rule 1 missed on its failing term, rule 2 matched), the decision `Bound`, the fragment and its verdicts, an empty transcript; the stepping tree shows `DSParser` and `XSLTFilter` under the element with real input and output; for the unknown shape the decision reads *would learn* — and the script was never consulted, no document written, no ledger row, no request | the `details` slot on `SharedElementData`; the dry-run rule under a `SteppingController`; the tree expansion |
| 34 | **A target is proposed and refused, then accepted** (A31) | as 5, with the target question in the conversation | targets: first the degenerate event (skeleton, `Unknown`, fields as `Data`), then a real one; then DS3 and XSLT right first time | the target question carries one representative record per line kind, the schema rules and the instructions; the first target validates and fails extraction quality with the typed-element ratio and `Unknown` rate in the re-ask — before any configuration was written; the second is accepted; the parser and transform questions both carry the targets; promoted with the targets on the regression set as goldens | `Question.Target`; representative records by signature; targets on the attempt and the regression set |
| 35 | **A field lost at extraction is caught there** (A31) | as 34 | a splitter that consumes every line but captures two of four fields; then a full one | coverage is 1.0 and field preservation is not: the re-ask goes to the *parser*, naming the target values the records lack; the transform is not asked until the records carry them | field-preservation scorer; feedback attributed to the step that lost the value |
| 36 | **The transform must reproduce the target** (A31) | as 34 | an XSLT that produces a valid event with `where` in the wrong element; then the right one | target fidelity fails naming the record and the difference; the stream-level scorers alone would have passed it | target-fidelity scorer, canonical tree comparison |
| 37 | **Record boundaries first** (A31, A35) | a multi-line record shape — corpus 003 — with the target question in the conversation; as a variant, an XML document whose records sit two levels down (`nested-entries.xml`) | *Split* reply that cuts one record per unit, then targets, then the rest | the split question is asked before any target, for the CSV of scenario 34 as much as here; targets are proposed for whole records, not lines; a split that cuts mid-record is re-asked on yield and coverage before a target is ever proposed; for the XML variant the split names the element that is one record | `Question.Split` for every kind of input; boundary judged without a target |
| 38 | **Relearning from the millionth event** (A31, design 01 §10.1) | a bound shape; a stream of many records with a fault in one late record kind; bindings carry each record's input span | as 27, the relearn's target question carrying the faulty record's raw text | the record's raw text is read back from the source by its recorded span, not by re-running the parser; the relearn's target and transform questions carry that record; the incumbent serves meanwhile | input spans in the bindings; read-back by span |
| 39 | **The plan is the document's** (A33, A37, design 01 §10.2) | two documents over one feed: one with steps `CHAIN, TARGET kinds 1, CONFIGURE` (a target from the raw sample, no split), one with the direct steps | as 34 for the first; as 1 for the second | the first asks chain, one target, two configurations and no split, the target's record being a line of the sample; the second asks chain and two configurations; a definition with `CONFIGURE` before `CHAIN`, two `SPLIT`s, a `goto` naming no step, or two steps with one id is refused on save naming the rule, and a document that reaches the stage with one is abandoned before the model is asked | `ConversationDefinition` on the document; the step interpreter; validation on save and at the stage |
| 40 | **A template override reaches the model; the rest follows the built-in** (A33) | a document overriding the `CHAIN` template with text using `${elements}` and `${sample}`, with the target-first steps | as 1 | the chain question put to the model is the override with both blocks rendered; the split, target and configuration questions are the built-in text; a template naming `${nothing}` is refused on save naming the template and the variable; the built-ins' version is on the saved document | override-only templates; block variables; the templates resource |
| 41 | **Escalation: a target only when the feed needs one** (A37) | the *escalating* example's steps, as 5 otherwise; two runs over the CSV feed | first: as 1; second: chain, DS3, then the degenerate transform twice, then a target, then DS3 and XSLT right | the first run is learned by chain and two configurations, no split or target asked, `on passed goto end` ending the plan — direct's cost; in the second the transform step spends its two candidates on `quality-short`, `on spent goto target` fires, one target per kind is proposed, the parser is re-asked against the targets, the transform is asked with them and promoted; the transcript names each step by id — `chain, parser, first, first, target, again, transform` — and each candidate's outcome | outcome kinds; `on passed`, `on spent`, `end`; roles on `CONFIGURE`; the interpreter |
| 42 | **A shortfall at the transform sends the parser back** (A37, design 01 §10.1 rule 6) | the target-first example's steps; as 35, but the parser step's checks set to `coverage,yield` so preservation is not judged there, and the transform's to `fidelity` so the stream-level scorers do not fail it first on the fields it cannot find | a splitter keeping two fields of four; the stylesheet, which cannot find `logon` or `office`; then a full splitter and the stylesheet again | the parser passes; the transform's fidelity check finds the values absent from its *input* and reports `preservation-short`; `on preservation-short goto parser` fires at once, the parser is re-asked naming the values the records lack, then the transform, and the shape is promoted; where the parser keeps two fields again, the second `preservation-short` at the transform would take the jump a second time and the attempt is abandoned naming the transition and the step | transitions at once; once-per-transition; outcome attribution in the interpreter's judge |
| 43 | **Syslog: two forms in one feed** (design 03 §3) | fixture `syslog.log`: RFC 3164 and RFC 5424 lines from two senders; `System` header; golden `syslog.events.xml`; first with the default key, then with the signature in it | as 1, the DS3 handling both forms; then, under the signature key, two attempts | with the default key one shape, one rule, one variant that emits every line as a record — a DS3 that handles only 3164 fails coverage and is re-asked naming the 5424 lines; with the signature in the key two shapes, two attempts, two rules, each learned from its own representative | the fixture and golden; `Format` in the chain question |
| 44 | **auditd: a record is several lines** (A31, A35, design 03 §3) | fixture `auditd.log`: interpreted audit records, no separators, events of 2–4 lines sharing `msg=audit(time:serial)`; the target-first plan; yield per line expected 0.35, threshold 0.6 — the document's way of saying a record is several lines | a line-per-record split, then one that joins by serial; targets; a parser that drops the EXECVE's quoted arguments, then the full one; XSLT | the first split consumes every character and is refused on yield alone — 1.0 per line against 0.35 — before any target is asked; the split by serial passes; targets are proposed for whole events, every line of the record sharing one serial; the parser without the arguments passes coverage and is caught by preservation naming `/etc/hosts`; the full parser and the stylesheet promote and the output equals the golden | yield against the input's structure; a lines basis judging the transform record for record; the fixture and golden |
| 45 | **Windows security events: `Data[@Name]` to typed fields** (A16, A35, design 03 §3) | fixture `windows-security.xml`: fifteen `Event` elements in the Windows namespace under an `Events` root, each a `System` block and `EventData/Data[@Name]`, `EventID` 4624, 4634 and 4688; the target-first plan; scorers Schema conformance (gate), Extraction quality (gate, `EventSource/User/Id` required) | chain `XSLTFilter`; split `EventData`, then `Event`; a stylesheet that copies each named `Data` to a `Data` under `Unknown`; then one mapping the `EventID` to Authenticate or Process and the named fields to typed elements | `EventData` — most of an event but not its System block — is refused on wholeness; `Event` is accepted; the degenerate transform validates and is refused on extraction quality alone, the typed ratio and the `Unknown` rate in the re-ask; the typed one is promoted outright, the `Event`s being the root's children, and the output equals the golden with all three `TypeId`s; one target stood for the three kinds of event, since kinds of XML record are told apart by structure and these share one — a finding for phase B | the fixture and golden; a kind of record for XML that structure cannot tell apart is owed |
| 46 | **JSON: lines and an array** (A31, design 03 §3; scenario 2's promise) | fixtures `records.jsonl` and `records.json`, twelve logins and logouts with a nested `client`; `Format: JSON`; allowed elements include `JSONParser`; the target-first plan; scorers Yield (records), Schema conformance (gate), Extraction quality (gate, `EventSource/User/Id` required), Business rules | chain `JSONParser -> XSLTFilter`; for the lines the split is skipped — every top-level value is a record, `root` — and the transform is asked over two targets; for the document the split names `client`, then `events`; XSLT | no configuration question for the parser; the split guard `json` beside `text` and `xml`; the split of the document refuses a key that is no array's and takes the array; a login and a logout are two kinds, told apart by their keys, and two targets are asked; the transform is asked with the parser's real XML in the XSL/json vocabulary; the lines are promoted outright; the document is learned whole, counts as one record at the stage and binds provisionally as the nested XML of scenario 37 does — yield by records is not asked of it until the count is by the array's items (design 03 §5) | the `JSONParser` step runner; the `json` guard and `SPLIT_JSON` template; the array split; the fixtures, stylesheet and golden |
| 47 | **Fixed-width: nothing to split on** (A11, A36, design 03 §3) | fixture `fixed-width.log`: thirty sign-on lines of six columns by position and no delimiter — time, user, terminal, action, result and a reason that holds spaces; the escalating plan; scorers Yield (records), Schema conformance (gate), Extraction quality (gate), Business rules with "a sign-on decision states its outcome" | chain `DSParser -> XSLTFilter`; a positional regex capturing four columns and matching the rest of the line; a stylesheet over the four, twice; then, against two targets, the four-column regex again and the six-column one; XSLT | coverage is 1.0 with four columns — every character is consumed — and never speaks; the four-column transform cannot state an outcome and the rule refuses it, twice, so the plan escalates to a target; two kinds of line, a reason of two words and of one, and a target for each; asked again against them the four-column parser is caught by preservation — the records carry no value for `PASSWORD OK`, for `REVOKED` — and the six-column one passes; promoted outright, the output the golden | the fixture, two splitters, two stylesheets and the golden; the escalating plan on a real shortfall |
| 49 | **XML fragments, no root** (design 01 §12 item 26; the owner's question) | fixture `events-fragments.xml`: one `<Event>…</Event>` per line in the Windows namespace, no root; allowed elements include `XMLFragmentParser`; the target-first plan | chain `XMLFragmentParser -> XSLTFilter`; the split names `Event`; targets; XSLT | no configuration question for the fragment parser, which wraps the fragments in a root; the split question is the XML one, since the parser's output is records XML — not the JSON one because the parser is run only; the rule carries `element Event`; the stream counts its events; promoted | the `XMLFragmentParser` step runner with a built-in wrapper; the walk's kind from the parser's output; the fixture and golden |
| 48 | **CSV with embedded newlines** (A36, design 03 §3) | fixture `csv-multiline.csv`: twenty headerless records of a document store's audit — time, user, workstation, action, document, note — the note quoted where it holds a comma, a doubled quote or a line break, six spanning two lines; the target-first plan; Yield by lines, expected 0.77 | a line-based DS3, then one regex over the stream honouring the quoting, the record as one field; targets; a six-field DS3 of the same regex; XSLT | the line split cuts a record in two: it consumes every character, so coverage says nothing, and loses none, so wholeness — a character share — says nothing either; yield against the lines a record takes refuses it, before any target is asked; the quoting split passes; targets are whole records, one spanning lines; promoted outright, the golden holding the note with its line break intact and its doubled quotes as one | the fixture, two splitters, stylesheet and golden; the DS3 rules teaching a quoted field |
| 50 | *(withdrawn 2026-10-06: the filter form is removed, A99; the stage has one shape, the parser's, whose task defers rather than learns inline)* **A supervised stage at the transformation position, and the pair** (design 01 §3, §12 item 4; item 18's audit) | the CSV fixture behind a hand-written `DSParser`, a document allowing `XSLTFilter` alone, Tier 2 only; and again as `Source -> ShapeshifterAi -> ShapeshifterAiFilter`, two documents, one stage learning the splitter and one the stylesheet; the scripted stylesheet has something to say about every record | no chain question — one allowed element is no choice — the split names `record`, targets, XSLT | the filter-shaped supervisor stands below a parser, which the parser-shaped one cannot; the events it is given become the text the stage sees; the written fragment holds no parser (A1); the stylesheet's warning reaches this pipeline's error stream once for the stream (A20), on the stream that learned and on the stream the rule then bound | `ShapeshifterAiFilter`; `Supervision`; a fragment run with a parser put in front of it; both stages on the output stream's attributes, the one nearest the source under the plain names |
| 51 | **One document, any format, any feed** (design 03 §7; the content pack's basis) | one document — `AUTOMATIC`, the escalating plan, allowed `DSParser`, `JSONParser`, `XMLFragmentParser`, `XSLTFilter`, and one set of scorers: Compile (gate), Yield, Schema conformance (gate), Extraction quality (gate) — and four feeds of four formats: `DOOR-ACCESS` the corpus CSV, `APP-EVENTS` `records.jsonl`, `FIREWALL` `syslog.log`, `MAINFRAME` `fixed-width.log` | per format, the chain reply that format needs, then its split, targets where the plan escalates, and its configurations | four shapes, four attempts, four rules, none provisional, each serving its own feed without asking again; every chain question carried all four allowed elements, and what followed was shaped by the answer — no configuration question for the JSON parser, one for the others, and the split question in the vocabulary of the parser's output; one scorer set judged all four and each promoted on its own evidence, the output each format's golden | nothing new: every part exists by scenario 50. What it proves is that they hold together in one document, which every format scenario before it avoided by narrowing `allowedElements` to the parser it needed. At Tier 2 the same four run through one real pipeline as processor tasks, which is the demo content pack's arrangement |
| 52 | *(revised 2026-10-06: the shadow rows are real since phase 4; what remains here is the row a stream of an unlearned shape waits on)* **The records that fell short** (design 04 §3, A55; phase 2's exit) | the demo document; a rule learned from the corpus CSV — logons alone — with a stylesheet that translates a logon fully and leaves any other record as an `Event` holding nothing but a `TypeId`; then `csv-mixed.csv`, six records of which the third and fifth are logoffs | nothing: the rule serves it | the stream is served as it always was — six events, the two thin ones among them — and the transform step's verdict names records 2 and 4, zero-based, as the ones that fell short, the schema gate naming both and extraction quality (no Unknown, no required field) naming none; each is its own text back out of the stream by its span; the shadow deferral table holds one row for the stream — against the rule's fragment, two of six, no task — and none for the stream that learned; a shape nothing is learned for, parked for a worker, is a row with nothing it failed against; scenario 51's four formats leave the table empty | `Score.failingRecords`, `Verdict.failingRecords` (the union over gates), `Deferrals` and its in-memory and table forms, `Stage.shadow` |
| 53 | **The corpus holds a candidate to no worse** (design 04 §4.1, A56, A66; phase 3's exit) | the demo document allowing Unknown in `EventDetail` and holding nothing out; `csv-mixed.csv`, six records, two logoffs; two stylesheets, one translating both kinds and one admitting a logoff as an `Unknown`; the later sessions are improvements asked for by hand (A46), which learn from the corpus put back together | the learning replies, then the improvement's | a rule learned from the admitting stylesheet holds six entries — the record as the parser cut it, its own text and what the stream wrapped it in, the output it was accepted with, and its kind: translation, translation, unknown, translation, unknown, translation; a candidate that turns an admission into a translation is rebound and the corpus grows by six more; a candidate that would turn a translated record into an Unknown is kept out, the reason naming *record 3 of 6 in the corpus* and the attempt that accepted it; the cap lets the oldest entries go. At Tier 2 the rule is learned in a processor task and the corpus is a stream of its own type on the source feed, a line per entry, with a row per entry in the database; a stage built afresh, as the Supervisor builds one, finds it and is held to it | `RegressionSet.Accepted` per record with `Kind`; `Corpus` (the entries, the gate, the stream put back together); `CorpusIndex`, `CorpusIndexDao` and `shapeshifter_corpus`; `StoredRegressionSet` |
| 54 | **The task decision** (design 04 §3; its §8 scenarios 1, 2, 3, 7 and 9 at Tier 1; phase 4's first slice) | the demo document holding nothing out; `csv-mixed.csv`; the logon-only and the translate-both stylesheets; a stage whose fragment runner counts its runs | nothing in the task, which never asks; the learning replies, for the worker | a stream of an unseen shape is *deferred* — no output, no sentinel, no ledger row — with a session opened on it and parked at its first question; a second stream of the shape waits on that session and opens no second; the worker carries it, the promotion wakes both rows, and the next decision on each emits it and takes its row off. Under a rule, a stream with a record short of the bar is deferred against the rule's fragment, scored once, and not scored again while that fragment stands; a stream of the shape whose records all clear flows meanwhile; the rebinding wakes the held stream and it is served. A shape given up errors; a pinned rule serves whatever the bar says. At Tier 2 (design 04 §8.1, test A steps 1 to 3, `TestScenarioAFromNothingToServed`): two streams of an unseen feed through real processor tasks end `DEFERRED` with nothing written for either and one session open; the worker learns; both tasks wake to `CREATED` and complete with their six events | `Stage.decide`, `Decision.Deferred`, `Deferrals.of` and `waiting`, `Wake` (`TaskWake` over `ProcessorTaskService.wakeDeferredTasks`), `Supervision` throwing `DeferTaskException` |
| 55 | **A serving run writes as it goes** (design 04 §3 *streams of any size*, §8 scenario 16's first half; phase 2's streaming half) | the logon-only rule of scenario 52; `csv-mixed.csv`; a sink standing for the pipeline's writer | nothing: the rule serves | at Tier 1 (in scenario 54): the task's decision binds the stream without reading it, and serving it runs the fragment over it once, judging each record of the output as it is written — the two records before the first logoff reach the sink, the third stops the run, the stream is deferred with *Record 3 fell short of the bar* and the session opened on it, and the stream is not read again; the next decision defers by the stamp, without a run. At Tier 2 (`TestScenario16AServingRunWritesAsItGoes`, real tasks): the task ends `DEFERRED` and what the writer made of the first two records is discarded — no visible child of the input — then the relearning wakes the task and the stream is served in full, six events | `Input.open` with a bounded prefix; `FragmentRunner.serve`; `RecordGate`; `RecordJudge`; `RecordShortfall`; `ServedStream`; `Stage.stream`; `Supervision` streaming the parser-position stage; the executor discarding a deferred task's output |
| 56 | **A promotion keeps a snapshot, and a session edits in place** (A58, A61, A70–A72; design 04 §8 scenario 11) | the demo document holding nothing out; the logon-only and translate-both stylesheets; `csv-mixed.csv` | the learning replies, then the relearning's | a learned rule points at a snapshot of its fragment's structure and code; a stream held against it is stamped with it; the relearning edits the same fragment — one pipeline document, one stylesheet document, the text replaced — under a new snapshot, and the held stream is scored again and clears; an as-processed reprocess of the first stream runs the first snapshot's code, logon-only, though the fragment now translates both; a stylesheet edited by hand is snapshotted the first time the rule serves and the edit is what serves; a retracted rule takes its snapshots with it | `RoutingRule.snapshot`, `Snapshots` and `shapeshifter_snapshot`, `FragmentCodes`, `FragmentWriter.rewrite`, `FragmentRunner.run` with code, `Bindings.snapshotId` and the output's attribute |
| 57 | **The session runs the held stream in full** (A64, A65, A73; design 04 §4, its §8 scenarios 1 and 12) | the demo document holding nothing out; a stream of twelve records whose four logoffs lie beyond the prefix the task reads as text — at Tier 2, beyond the first sixty-four kibibytes of a real stream; the logon-only and translate-both stylesheets | the learning replies, answered logon-only; then a chain question whose feedback carries the records that fell short on the held stream — each as the parser cut it, with what the transform made of it — answered translate-both; for the parking case, the direct plan with one candidate a step and a stylesheet that does not compile | the first candidate clears the prefix and is promoted; the worker's full pass over the stream finds the logoffs and the next question opens with them; the second candidate clears the full pass; one attempt, `PROMOTED`, one fragment edited in place under two snapshots; the stream is woken and served in full, logoffs among its events. A session out of candidates is `PARKED`: its shape held, its stream still waiting, the worker leaving it alone, a task of the shape deferring without opening another; a person answering the turn it stopped at carries it on, and the re-walk promotes and wakes | `Shortfalls`, the collecting judge; `RecordTap` and `FragmentTapFilter`, the parser tapped in the pipeline; `Stage.settle`; `AttemptStatus.PARKED` and `Attempts.parked` |
| 58 | **A session whose node dies is carried on by another** (design 04 §4; its §8 scenarios 13 and 15) | a deferred document; corpus 001; two nodes, by name, over one set of state | node one's worker answers the chain and the parser and dies at the transform question — an error nothing catches; node two's worker answers the transform alone | the attempt is left `IN_PROGRESS` under node one with the turns it was answered; a second stream of the shape waits on it and opens no other; while its lease runs no other worker takes it; once the lease lapses node two's worker finds it, replays the recorded turns without asking the model, asks the one question the dead node stopped at, and finishes it — one attempt, `PROMOTED`, under node two's name, its turns in order — and every waiting stream is woken. At the DAO: a lapsed attempt still holds its shape, is listed as lapsed for the worker and not as waiting, and opening beside it is refused; taken, it is listed no more | `Attempts.lapsed`; `opened` and `open` no longer lapsing a claim; `DeferredWorker` over the lapsed as well as the waiting; the lease a node's `sessionLease`, renewed before and after every turn |
| 59 | **An exhausted session admits a marked Unknown** (A51–A53, A62, A74–A76; design 04 §4 and its §8 scenarios 4 and 14) | the demo document under a plan whose transform has one candidate before the exhaustion step; the logons as the prefix and the logoffs beyond it; the logon-only stylesheet and one that admits a logoff as a marked Unknown | round one answered logon-only; round two answered logon-only again, which is held to the logoff records and falls short; the admission question, answered with the admitting stylesheet. For the refusal case, the logoffs in the sample and the admitting stylesheet offered at the transform's own question | the transform's one candidate is spent on the records the full pass kept, the exhaustion step is asked with those records in front of it, the admission clears; one attempt, `PROMOTED`, an `ADMIT` turn among its turns; the corpus holds the logoffs as admissions beside the logon translations; the stream is served with marked Unknown events among its own; a later stream of the same kinds is served at once with no session opened. The admitted form offered before the exhaustion step is `REFUSED` with the reason, and accepted at that step | `QuestionKind.ADMIT`, `LearnedUnknown`, `Template.ADMISSION`, `Admission` in the scorers, the conversation holding candidates to the held records, `Corpus.heldEntries` |
| 60 | **A parse that cannot be learned errors its streams** (A52, A54; design 04 §4 and its §8 scenario 5) | the demo document with its yield counted per line and a plan whose parser has one candidate; two streams of one shape | the chain, then a splitter that cuts the whole stream as one record | the parser's candidate is spent short of the yield gate; the attempt is `ABANDONED`, the shape given up with the reason, nothing bound; both waiting streams are woken, each decides to a sentinel naming the reason, its row released; no session opens again and the model is not asked | `Outcome.Abandoned.parse`, the stage giving up and waking on it, a given-up shape releasing the deferral row |
| 61 | **A parked session given more to spend carries on** (A65, A77; design 04 §4 and §6) | the demo document with a token budget of two; a scripted model that charges a token a question | the learning replies; the transform's reply alone for the carrying on | the session parks at the transform's question with the budget spent; a grant to a running or finished attempt is refused and a grant of nothing is refused; the parked attempt takes ten tokens, recorded against it alone, the document untouched, and is opened again; the worker replays the recorded turns for nothing, asks the transform, and promotes within the grant, which stands on the row; the stream is woken | `Attempts.Grant` and `granted`, `Stage.grant`, the conversation's budget with the grant, `Script.charging` |
| 62 | **A parked session sent to the exhaustion step carries on** (A65, A76; design 04 §4, §6 and §7) | the demo document with a token budget of four under a plan whose transform has one candidate and goes to `ADMIT` on spent; a scripted model that charges a token a question; a stream of eight logons and four logoffs whose sample holds logons alone | round one's replies and round two's chain and parser for the learning; the admitted form alone for the carrying on | the session is promoted in round one, the full pass finds the logoffs, and round two parks at the parser with the budget spent; a sending to the exhaustion step opens the attempt again with the sending on its row, and a sending to anything but a parked attempt is refused; carried on, the budget refuses the first question and the session parks again with the sending standing; granted enough, the worker replays the recorded turns, puts no transform question, goes to the exhaustion step, admits the logoffs, and is promoted with the sending taken off the closed row; a plan whose transform has no `on spent` transition parks the session again saying so, round one's fragment stays bound and the stream waits | `Attempts.exhaustNext`, `Stage.exhaust`, `Conversation.exhaustFirst`, `Advisor.replaying`, `SupervisorResource.exhaust` |
| 63 | **The flow names its exits** (A83; design 05 §2) | the demo document under a flow whose parser says `on spent goto park`, and another whose transform says `on spent goto give-up`; a scripted model whose one parser candidate cuts the stream as one record, or whose one transform candidate does not compile | the chain and the one candidate | the parser's spent candidates park the session where the flow says park: the shape is not given up, the stream goes on waiting, nothing is woken; the transform's spent candidates give the shape up where the flow says so: the attempt is abandoned, the shape's reason is recorded, every waiting stream is woken to error, and a later task of the shape is sentinelled | `FlowExit`, `Transition.namedExit`, the conversation's default exit by role, `Abandoned.givesUp` |

**The inline walk retired** (2026-10-06; design 04 §10, A99–A102). The Tier 1 scenarios process a stream
as production does, through `Scenarios.process`: the task decides, the session it opened is carried as
the worker carries it, and the stream is served once its task decides again. Most kept their assertions.
The rest now say what production does: a stream behind a draft or behind another learner waits on a row
rather than being sentinelled (22, 25–29, 13); spent candidates park rather than give the shape up (7,
42, 46); the stream a rule was learned on waits and is woken (54, 30); a marked shape is relearned on
its next stream while the incumbent serves (8, 9, 22, 27; A100); a provisional rule is not retracted
(28, now *a provisional rule is not retracted by the floor*, A101). The incumbent scenarios weigh
extraction quality rather than gate on it, and gate on the user rule instead, so that the flawed
incumbent's flaw is a lower score and not a record short of the bar. Scenario 52's three shadow tests
are retired: phase 4 made the shadow rows real, and what they held is held by 54 and 57.

Scenarios 3, 15, 16 and 17 exist today as unit tests of one component; they become scenarios so
that the catalogue is the one place the behaviour is stated.

## 6. What the catalogue forces, in order

Each item is the smallest thing that lets the next scenario pass. The design's §12 list is the
superset; this is its test-driven ordering.

1. **`Scorecard`** — the document's `ScorerSetting`s applied to one candidate's output: per-scorer score in
   [0, 1] with its diagnostics, gate outcomes, weighted total, and the *first* failing step to feed
   back to. Weights and thresholds come from the settings; the scorers are an SPI keyed by
   `ScorerType`. Compile and input coverage wrap what exists.
2. **Scorers** in catalogue order: yield (record count against `YieldParameters`); schema
   conformance (a `SchemaFilter` over the event-logging pack, per record, as the calibration tests
   stand the Data Splitter schema up today); extraction quality (XPath over the output: typed ratio,
   `Unknown` rate, required fields); business rules (XPath assertions plus captured `xsl:message`s);
   error load; event classification.
3. **`Conversation` scores.** A step passes when its scorecard passes, not merely when it compiles;
   feedback carries the scorecard's diagnostics. Budgets enforced under a clock the scenario controls.
4. **Learning key** — `Feed AND Type` by default, and a provisional shape-signature normalisation
   for documents that put the signature in the key (A29), so that routing and the ledger have a
   key. A6 is open and needs real feeds; scenarios need only that the same record shape yields the
   same signature and a different one does not. Token classes for text, element skeleton for XML,
   as §5 says.
5. **`Stage`** — route, learn, judge, write, emit (§4 above), over in-memory streams, quarantine and
   regression set. This is the supervisor's logic without the pipeline element around it, and it is
   what the element will delegate to.
6. **Tier 1 scenarios 1–17, 21–29 and 31.** Scenario 21 needs nothing scenario 2 does and can follow
   scenario 1 directly: the records split, a stages runner, and — for its full form — the stage-2
   scorers that scenarios 5 and 6 force. Its structural form, with Compile and Yield alone, proves
   the chaining and the record-unit split before those scorers exist.
7. **§12 items 1, 2, 4, 7** — the `stroom-pipeline` harness, the supervisor element, bindings
   metadata — and **Tier 2 scenarios 18–20**; then §12 item 19, the stage pane in the stepper, and
   **scenario 33**.
7a. **Learning against a target** (A31, §12 items 20 and 21) — scenarios 34–38 — before the tables and
   the durable attempts, since it changes what an attempt's turns are and what the regression set holds.
7b. **The plan as data** (A33, §12 item 22) — scenarios 39–40 — straight after, before the stepper
   pane and the durable attempts, so the document settles before either shows it; then **the plan
   as a graph** (A37, §12 item 23) — scenarios 41–42 — for the same reason, and because the turn rows
   of item 8 record the step id and outcome it defines.
7c. **Format breadth** (design 03 §3, phase B) — scenarios 2 and 43–48 — before the tables, since what
   the model can and cannot learn across formats decides how much of the rest is worth building first.
8. **The A26 tables** (§12 item 8) — scenario 20 — where the record element of A35 also reaches the
   rule; then **durable attempts and the worker** (A28, §12 item 15) — scenario 30 — which extend the
   tables; then A27 under scenario 32 (§12 item 16).

Redaction (A17) is not on this list: a scripted model does not care whether the sample is redacted,
so scenarios do not force it. It stays on §12's list as item 6's neighbour and gets its own test.

## 6.1 Where it stands

Built 2026-09-17, the first slice: the `Scorer` SPI with `Scorecard`, `Verdict` and the compile,
input-coverage and yield scorers; the conversation judging each step by scorecard rather than by compile
alone; a provisional `ShapeSignature`; `Router` over `ExpressionMatcher`; `Quarantine` and
`RegressionSet` as interfaces with in-memory implementations; `FragmentRunner`, which runs a written
fragment back through the step runners; and `Stage` — route, learn, judge, write, emit. The fixtures
of §3 exist as `Script`, `QuestionMatcher` and `Scenarios` in the module's `testFixtures`.

Scenario 1 passes, twice over: the learning run, and a second stream of the same shape bound by the
learned rule with the script never consulted and the written fragment producing the same translation.
Writing it found two things worth recording. The corpus's CSV case has six records, not the ten a
default document waits for, so the scenario lowers `minRecordsPerShape`; and its golden splitter reads
the header into a variable, which coverage counts as discarded — design 01 §9.1's finding, met again
— so the coverage threshold sits at 0.8, and the script's own check caught it: the transform question
never came because the splitter was being re-asked with the coverage feedback.

Later on 2026-09-17 the document and the UI were brought up to the day's rulings (design 01 §12 items
17 and 18): scenario 1 now asserts a `Feed AND Type` rule with a `uuid`, the chain question carries
the key's values (`Feed`, `Type`) rather than a fixed header list, and the fixture's `shape()` is
`chain()`.

The Stage was then brought up to §4 as rewritten (the second slice, after the first commit): a shape is
one value of the learning key (`Shape`); the ledger and regression set are keyed on document-and-shape
and on rule `uuid` (A26, A18); an unknown shape is tried against the fragments bound for its feed and
type before any question (`Router.compatible` reads the selector's top-level `Feed`/`Type` terms);
a candidate that clears the floor is bound provisionally when the shape has too few records, and a
provisional rule is promoted in place the first time a stream brings enough; a matching reserved rule
gives the shape up; a matching draft is sentinelled naming it; `DISABLED` still selects. Scenarios 14,
25, 26 and 29 pass, and the routing half of 22.

The third slice, 2026-09-18, is what happens to a binding after it is made — scenarios 28, 27 and 13 —
and the runtime state it needs, given to the `Stage` as the seams of A26: `Shapes` (the shape row:
given up, marked for relearning, rolling score), `Ledger` (sentinelled inputs), `Outputs` (the bindings
each output carries, §7.3 rule 3, as a `Bindings` record on every `StageRun`) and `Reprocessing`
(requests, as-current). Every sentinel now writes a ledger row (A4), including a given-up shape's own
stream; binding a shape takes its inputs off the ledger and requests them (A12); a provisional rule
that fails the gate is retracted — out of the table, the shape unknown again, the inputs whose output it
produced requested, the failing stream sentinelled — and the next stream of the shape learns afresh. A
promoted rule's score over each stream it serves feeds the shape's rolling score, and once that is
below `relearnThreshold` the next stream is relearned while the incumbent serves it: the candidate
replaces the incumbent's fragment under the same rule when it clears the floor, is no worse than the
incumbent on that stream (A15) and no worse on any record the rule was accepted on (A18); otherwise the
incumbent is kept and nothing is written. Four things were decided in the writing and are worth the
owner's eye:

- **A chain's score is the product of its steps' totals**, not their mean. The mean let a perfect
  transform lift a split that discarded a seventh of the input to 0.93; a record must survive every
  step, and the product says so. The scenario documents' floors sit at 0.85 in consequence, below the
  6/7 that the corpus's header-discarding splitter scores — §9.1's finding, met a third time.
- **The records a stream brings are counted on the input** — its non-blank lines, or its records where
  it is already XML — not on the output, so that a variant which extracts nothing from ten records is
  judged on ten and retracted, not excused as too few to judge. Scenario 14's six records are seven
  lines.
- **The rolling score is a running mean whose memory is capped at `minRecordsPerShape` records** — the
  two columns a shape row can hold — so a shape that has been good for a year is judged on what it has
  done lately, and is not acted on until a memory's worth has been seen. Relearning waits for a stream
  that brings `minRecordsPerShape` records, so the candidate meets A14 as a fresh one must; it spends
  the mark whatever the outcome, so a shape that cannot be fixed is relearned again only after another
  memory's worth of records has fallen below the threshold. A pinned rule is served and nothing else. The
  scenario drives the score with input coverage, since the schema-conformance scorer the catalogue
  names is scenario 5's; the trigger does not care which scorer moved it.
- **A provisional binding releases the ledger too**, not only a promotion: the ledger's inputs are the
  very records the provisional rule is waiting on to meet A14, and if it is wrong about them its
  outputs are retracted and requested again. A draft still releases nothing until Approve.

Also: a fragment step that produced output with errors is now followed, as a pipeline would follow it,
the errors being the failing records' (design 01 §5); only a step that produced nothing stops the chain.

The fourth slice, also 2026-09-18, closes the routing table's story: scenario 22 and the relearn
question. In review mode (A25) `bind` writes the rule as a draft — fragment written, regression records
kept under the draft's `uuid`, nothing released, the stream itself onto the ledger — and every later
stream of the shape is the sentinel the catalogue names. `Stage.approve` is the promotion: the rule goes
live with time and score and the shape's ledger is released as a reprocess request; `Stage.reject`
drops the rule and its records, leaves its documents, and gives the shape up with the reason so the
model is not asked again until an operator says otherwise. The case the catalogue leaves implicit is
covered too: a *relearned* candidate under review is a draft with the incumbent's selector, appended
behind it so the incumbent keeps matching first and serving; while it waits the shape is neither scored
nor relearned again; Approve rebinds the incumbent to the draft's fragment, keeping the incumbent's
`uuid` and folding the draft's regression records into its history, and is refused while the incumbent
is pinned; Reject leaves the incumbent serving, and gives the shape up so that the same falling score
does not draft the same candidate again. A draft's regression records are kept however few, since a
person approving a draft on too few records is §6's exception to A14. The relearn conversation now opens with why the incumbent fell short — the mark's reason, the
incumbent's score on the stream, and the failing scorers' diagnostics — as the feedback of every
question's first asking (`Conversation.run` with an opening). Approve and Reject are operations on the
`Stage`; the Routing tab's buttons and the Supervisor view (A28) will call them.

The fifth slice, 2026-09-18, is Tier 2: scenario 18 passes with the supervisor element in a real
pipeline, processed as a processor task under `AbstractProcessIntegrationTest`
(`TestScenario18LearnsACsvFeedInAPipeline` in `stroom-app`). `ShapeshifterAiParser` sits where a parser
sits — `Source → ShapeshifterAi → SchemaFilter → RecordOutputFilter → RecordCountFilter → XMLWriter →
StreamAppender` — reads the stream, lets the `Stage` decide, writes the document back when the routing
table changed, and either runs the bound fragment or logs the refusal as an `ERROR` naming the shape.
The fragment runs as a **nested pipeline** (A20, design 01 §3): its merged `PipelineData` with a
`ShapeshifterAiOutput` filter linked from its tail, built by the same `PipelineFactory` in the same
pipeline scope, so its events reach the element's targets and its errors this pipeline's error stream.
The bindings go into the output stream's attributes through `MetaData` (§7.3 rule 3), and the real
`SchemaFilter` validates the learned transform's events against the event-logging schema with nothing
to report. The second stream is bound with the script never consulted. Five things to know:

- **The fragment runs twice on a stream** for now: once through the step runners for the score the
  stage decides on, once as a pipeline for the output. A fragment runner over the nested pipeline with
  per-element capture (design 01 §12 item 2) removes the first run; until then Tier 2 pays double.
- **Runtime state in a node is in-memory and node-local**: the `InMemory*` implementations moved from
  test fixtures to `stroom.shapeshifter.ai.state`, synchronised, and bound as singletons until the A26
  module exists. A cluster does not yet share what a stage has learned about a shape, and a restart
  forgets it; the routing table, being on the document, survives both. **A reprocess request is real
  already**: `PipelineReprocessing` creates a reprocess filter over the outputs this pipeline made from
  the named inputs — error streams, or a retracted rule's output — at the lowest priority, which task
  creation turns back into their inputs and runs as-current, exactly as an operator's reprocess does.
  It is not yet exercised by a Tier 2 scenario, since the mock processor filter service's `reprocess`
  is a stub; scenario 13 in Tier 2 waits on that.
- **The advisor is an optional Guice binding** whose default, `NoModelAdvisor`, fails a stream loudly
  when a document in `AUTOMATIC` mode would ask; the test node binds the scenario's `Script` through an
  `AdvisorHolder`. Wiring `stroom-ai` in is design 01 §12 item 6.
- **Two tasks learning the same shape at once race on the document write**; the loser's
  `DataChangedException` fails its stream rather than overwriting. The attempt's claim on its shape
  (A45) is the fix, and the rules leaving the document for rows of their own (A41) removes the write.
- **A stream of several parts** is served part by part, but the output stream's attributes are one set:
  the first part's bindings stand for the stream and a later part bound differently is reported as a
  warning, since the attributes cannot say so and an as-processed reprocess of it would be misled.
- **Two mocks were made truthful for this**: `MockStore` now keeps the attributes a target closed with,
  as the real store hands them to the meta service, and `MockExplorerService.create` creates the
  document through the type's handler where one is registered, as the real service does, returning
  null only for a type this environment has no handler for (which the content store setup relies on).

The sixth slice, 2026-09-18, is the scorers of §6 item 2 and the scenarios they unlock: 4, 5, 6, 7, 8,
9 and 10 in Tier 1 (`TestScenariosScoring`, `TestScenariosIncumbent`) and 19 in Tier 2. Schema
conformance validates per record through Stroom's own `SchemaFilter` behind a `SplitFilter`, as
`SchemaFilterSplit` does, with a counter between them so that each error lands on its record; the
feedback is the first failing records' messages as Stroom post-processes them. Extraction quality is
the mean of the typed-element ratio, the proportion of records not naming `Unknown`, and the presence of
each required field, with a diagnostic for each shortfall. Business rules is the proportion of records
holding every assertion, a transform `xsl:message` at warning or above counting against one record —
`XsltStep` now captures messages as Stroom's `XSLTFilter` reads them. Coverage's feedback names the
first lines nothing consumed. Writing the scenarios found four things:

- **The scorers of meaning apply only to a step that is not a parser.** Left to judge every XML output
  they refused the parser's `records:2` document against the events schema. Design 01 §4 says where
  each scorer sits — stream-level after a parser, per record after a filter — and the scorers now
  honour it: a step runner says whether it parses (`StepRunner.parser`), the fact travels with the
  attempted step, and the meaning scorers leave a parser's records alone whatever its input looked like
  (a parser over line-delimited XML fragments has markup for input and is still a parser).
- **The 3.0.0 schema is stricter than the flawed candidates the catalogue imagined.** `Authenticate`
  requires one of `LogonType`/`User`/`Device`, and `EventSource` one of `Device`/`Client`/`Server`/`Door`,
  so a transform that *drops* the user or the device fails conformance first, and the business rule or
  the required field never gets to speak. The scenarios' flawed candidates emit the element empty
  instead — schema-valid, and wrong in the way the later scorer exists to catch — which is also a truer
  picture of what a model produces.
- **A wholly broken rule must score zero.** The first formula counted "no transform messages" as a
  passed check per record, so a rule every record broke scored 0.5; the score is now the proportion of
  records holding every rule.
- **Feedback numbers are the learning prefix's**, not the stream's: scenario 4's re-ask says the split
  consumed 3 of 5 lines and names lines 2 and 4, because the model learns from the prefix. The
  catalogue's rows read as if over the whole stream; the tests state what is actually said.

Also from the audit of the slice: the output a model's stylesheet produced is parsed by the same
DOCTYPE-refusing reader the step already applies to what a model wrote (`ConfinedXml`), so nothing in
it can make the harness fetch anything; a required-field path or a rule's XPath that does not compile is
refused when the scorecard is built (`Scorer.validate`), before a model is asked, rather than failing
every stream; and a record the validator never reached counts as failing, not conforming.

Scenario 7 runs both ways: a candidate that never clears its yield threshold is abandoned at the
candidate limit after five questions and the shape given up with a ledger row, and one that clears
every threshold but not the floor is given up after three. Scenario 8 runs both halves of the gate: a
candidate worse than the incumbent on the held-out stream (A15) and one worse on a record the rule was
accepted on (A18), each kept without a document written. The Tier 2 scorer set is the same six, the
schema-conformance scorer taking the node's `SchemaFilter` and so the node's whole schema store.

The seventh slice, 2026-09-18, is the harness for the **live smoke** — the scenarios' machinery driven
by a real model, to learn what no script can say: whether the questions as put draw replies the grammar
accepts, whether the feedback steers a second candidate to a passing one within the candidate limit,
whether the prefix and the thresholds are workable, and what an attempt costs in tokens and seconds.
Three pieces:

- **`QuestionText`** (main) renders each typed question as design 01 §10's prompt contract says it
  must be put: the system text carries the stage's objective and the document's `instructions`; the
  chain question the key's values, the allowed elements with a line each, the sample and the
  {@code A -> B} grammar; a configuration question the element and document type, the real input the
  element will receive, the previous configuration on a re-ask, what fell short, and the one-fenced-block
  grammar — with §9.1's `schemaLocation` and `ignoreErrors` rules for extraction and §8.2's failure
  modes and §8.3's trap for transformation. A node's advisor over `stroom-ai` (§12 item 6) will use the
  same text, so the harness measures the prompt the node will send.
- **`LiveAdvisor`** (test fixtures) is the `Advisor` seam over an OpenAI-compatible endpoint, configured
  from the environment as `TestExtractionReconstruction` already is — `SHAPESHIFTER_AI_BASE_URL`,
  `SHAPESHIFTER_LEARNING_MODEL`, `SHAPESHIFTER_AI_API_KEY` — putting each question after the system text
  and the attempt's transcript so far, and counting tokens and time. Samples go unredacted (A17 is not
  built): the endpoint must be one the data may be sent to.
- **`TestLiveScenarios`** runs five scenarios with it and writes a report and every transcript under
  the module's `build/live`: scenario 1 (the corpus's CSV with its header); a headerless CSV against the
  full scorer set (does the first transform mean something, or is it steered out of the degeneracy
  trap?); two record kinds in one stream at coverage 0.9; scenario 27 live (learn, fall, relearn); and
  the corpus's `123 [abc] text` shape. It is opt-in — enabled only when the environment names an
  endpoint — and it is a report, not a verdict: it fails only if the harness itself breaks, and a run's
  own failure is recorded and the others go on. Run it with
  `SHAPESHIFTER_AI_BASE_URL=… SHAPESHIFTER_LEARNING_MODEL=… ./gradlew :stroom-shapeshifter:stroom-shapeshifter-ai:test --tests '*TestLiveScenarios*'`;
  what the first run found belongs here, beside §9.1's account of the extraction harness.

One thing to know: the document's `instructions` reach the model through the advisor's construction,
not through the `Question`, since the seam carries no document; the node's advisor will be built per
stage with the document in hand, and A28's persisted turns should record the system text with the rest.

### 6.2 What the first live run found

Run 2026-09-18 against `claude-sonnet-5` through Anthropic's OpenAI-compatible endpoint, three times,
75 questions and about 435k tokens in all. The transcripts are kept beside the module's build output
(`build/live-runs/`); the numbers below are from the reports.

| Run | Questions as put | 01 header CSV | 02 headerless, full scorers | 03 two record kinds | 04 scenario 27 | 05 regex corpus |
|---|---|---|---|---|---|---|
| 1 | as first written, 3 candidates | given up at the splitter | given up at the splitter | given up at the splitter | given up at the splitter | given up at the transform |
| 2 | + a worked Data Splitter example, 3 candidates | **promoted 0.857** | **promoted 1.0** | given up at the transform | promoted 1.0; marked; relearn kept | **promoted 0.938** |
| 3 | the same, 5 candidates | promoted 0.857 | promoted 1.0 | given up at the transform | promoted 1.0; marked; **rebound 0.981** | promoted 1.0 |

- **The grammar holds.** Seventy-five replies, every one a single fenced block or an `A -> B` chain
  exactly as asked; the reply parsers were never exercised in refusal. The endpoint refused
  `temperature` for this model, so the advisor sends it only when asked to.
- **Design 01 §4.1 was right about extraction.** Without a worked example the model does not know the
  Data Splitter's structure — it wraps the `data` elements in a `group` inside the `regex`, or drops the
  group's `value` — and the compile gate's `cvc-complex-type` messages teach it one constraint per
  candidate, so it thrashes and is abandoned: five out of five. With one worked example in the question
  (a four-field CSV, the shape the design calls the strict one), five out of five compiled first time,
  including a two-`regex` splitter for the stream with alarm lines that the example only hinted at.
  Diagnostics are the wrong teacher for extraction; the example is the right one.
- **Transformation converges by the schema's feedback, one constraint per candidate**, and the number
  of candidates is what decides it. A door-access CSV against the full scorer set — schema gate,
  extraction quality with two required fields, a business rule — was promoted at 1.0 on the first
  stylesheet (run 02). Where the model needed rarer branches it climbed: the corpus's `123 [abc] text`
  shape wanted `Other` (not a 3.0.0 branch), then had `Description` before `Action`, and passed on the
  third or fourth stylesheet. **Scenario 27 ran live end to end** at five candidates: learned at 1.0,
  served the alarm stream and fell to a rolling 0.7, was marked, relearned with the incumbent serving,
  and rebound at 0.981 — the whole loop, with a real model, once.
- **The one failure left is a ladder.** For the alarm lines the model chose `EventSource/Door` — the
  right element for a badge reader — and `EventDetail/Alert`, also right. `Door` in 3.0.0 has a long
  mandatory sequence (`Name, Description, Location, SingleEntry, RemoveAll, …`), and the validator
  names one missing child per candidate; the model added one per turn and could not finish in five.
  `Device`, with `Name` alone, would have passed at once. The fix is to the feedback, not the model:
  when conformance reports *the content of element X is not complete*, carry X's whole content model
  from the XSD — its children in order with what is required — so that a candidate can finish in one
  step. §8.2 already has the schema's post-processed messages as feedback; this is the next enrichment,
  and the operator's part is that `instructions` and the required-field list agree with each other and
  with what the schema makes cheap (the run's instructions said *device*, its required field said
  `Device/Name`, and the model still, reasonably, chose `Door`).
- **Cost and time.** A promoted first-time attempt was 6k tokens and 14 seconds; a relearn with five
  candidates 94k tokens and just over three minutes; the failed ladder 77k. On this endpoint a question
  took 5–30 seconds. The candidate limit's default of 3 is right for a simple feed and one short for a
  feed with a rarer branch; five let scenario 27 through. Deferred mode (A5) is the mode for anything
  that is not a backfill, as the design said.
- **Two things the harness itself learnt.** A given-up decision now carries the last candidate's
  diagnostics — the transcript could not say *why* the third attempt failed, and neither could the
  sentinel (§4 step 5). And Gradle does not count an environment variable as a test input, so a second
  live run must be `cleanTest test --tests '*TestLiveScenarios*'` or it reports the last one.

**What the run settles about the questions, round by round.** The *chain* question is right as
written — key values, a line per allowed element, the sample, the `A -> B` grammar — and needs nothing.
The *splitter* question is rules, a worked example, the real input, and only on a re-ask the diagnostics:
the example is what teaches, the diagnostics only correct. The *transform* question must name the target
schema's branches and their order up front, must carry the real `records:2` output (every stylesheet's
XPaths were right), and on a re-ask should carry the content model of the element that failed, which it
does not yet. Feedback steers well when it is specific to the thing that fell short — coverage's
uncovered lines, the rule's name, *required field X present in 0 of 16* — and one rung at a time when it
is the validator's alone. The candidate limit's default should be five, with the attempt budget (A5) as
the real bound.

**And about the scorers.** Coverage did its teaching in the first round of every extraction. Schema
conformance as a gate is the transform's workhorse and the scorer whose feedback most wants enriching.
Extraction quality earned its place as the A16 gate: in run 2's two-kind stream it was what failed
(0.67 against 0.7) when the schema passed — §8.3's trap, met live — and its required-field line is what
steered the model toward the fields the operator wanted. It also showed that the operator's
`instructions` and required-field list must agree with each other and with what the schema makes cheap;
the Learning and Scoring tabs should say so. The business rule fired once and was fixed in one turn.
Yield never mattered here — no candidate dropped records — which is what it is for elsewhere.

What this does not yet say: how the extraction stage fares on the corpus's other shapes (the
reconstruction test of §9.1 measures that, and has not been re-run with the worked example), what a
cheaper or a stronger model does (the same three runs, one line of environment away — a small model is
the cost curve at volume), and whether a person's `instructions` can carry the schema hint the feedback
should. Follow-ups, in order of value: the content-model hint in conformance feedback; the default
candidate limit to five; `QuestionText` as the node advisor's prompt (design 01 §12 item 6 — it is in
main for that reason); the reconstruction test re-run with the new extraction question; a small-model
run.

The eighth slice, 2026-09-18, closes what the live run found. **The schema's feedback now carries the
content model** of what fell short: `ContentModels` reads the group's XSD text and writes an element as a
model can use it — children in order, `?`/`*`/`+`, a choice as `(A | B)` with a run inside a choice kept
as a run, a required complex child opened one level — and `SchemaConformanceScorer` appends up to three
such hints after the record messages: the whole model of an element reported incomplete, and, for
*invalid content starting with X, one of {Y} expected*, the parents that could hold both, ranked by
whether they require what was expected (which picks `Alert` out of seventeen elements with an optional
`Type`). Against the real 3.0.0 schema: `Door (in EventSource) contains, in order: Name, Description?,
Location { Country?, Site?, … }, SingleEntry, RemoveAll, AddAccess { AccessZone+ }`. The hint is best
effort — read as the processing user, a schema that does not parse left out, nothing in it able to fail a
score — and the Tier 1 scenario reproduces run 3's ladder exactly and shows both hints reaching the
re-ask. **The default candidate limit is five.** The §9.1 reconstruction test now builds its prompt from
`QuestionText`'s extraction rules, so the two measure the same words.

**The fourth live run**, later the same day with the hint in place (five candidates): scenario 03 — the
ladder — **promoted at 0.981** in five questions where run 3 had given up after seven; 01, 02 and 05
promoted as before (01 at 0.964 this time). Run 04's relearn failed, though: the relearned splitter
was right first time (two regexes), and the stylesheet climbed `Door` again — `Description, Location`,
`Location`, `SingleEntry`, `RemoveAll` — with the hint present on every re-ask. It was the last of six
lines, at INFO, in a notation the model was not told, and the validator's own "one of {Location}" was
followed instead. So the hint now comes **first**, at WARNING, with a legend for the notation, the
instruction *add every required child at once, not one per attempt*, and — where the element is one
option of a choice in its parent — the alternatives, since `Device` needs a `Name` where `Door` needs
six things: `It is one alternative of ((Device, Client?, Server?) | Door); another may be simpler`. That
form has not yet been run live.

The reconstruction test across the corpus's shapes was attempted twice and measured nothing: the first
run was spoiled by a concurrent Gradle build in the same module overwriting its results after 22 minutes
of model time, and the second found the key's credit spent. It gained a robustness fix — a case that fails
is recorded as `FAILED` and the others go on, so a run always yields its table — and the measurement is
still owed.

Not yet: the reconstruction run; scenario 20 (needs the A26 tables for its ledger row); everything
from scenario 30 on.

### 6.3 Direct against target-first

Run 2026-09-18 against `claude-sonnet-5`, the five runs of §6.2 in each shape of the conversation, twice:
run 5a as slice 10 left it, run 5b after the fixes 5a called for. Archived as `build/live-runs/
sonnet-5-run5{a,b}-{direct,target_first}`. Scores are the stream score; questions and tokens are the
whole run (04 is three streams).

| Run | 5a direct | 5a target-first | 5b direct | 5b target-first |
|---|---|---|---|---|
| 01 header CSV | 0.902 · 5 q · 21.6k | **given up 0.80** · 8 q · 41.7k | 0.964 · 3 q · 7.1k | 0.857 · 7 q · 30.7k |
| 02 headerless | 1.000 · 3 q · 6.2k | 1.000 · 5 q · 15.7k | 1.000 · 3 q · 6.4k | 1.000 · 6 q · 26.1k |
| 03 two kinds | 0.981 · 6 q · 53.0k | 0.981 · 11 q · 82.1k | 1.000 · 5 q · 39.5k | 0.925 · 6 q · 26.0k |
| 04 scenario 27 | 0.981 · 10 q · 85.8k | 0.981 · 18 q · 127.9k | 0.981 · 7 q · 30.6k | **1.000** · 13 q · 69.7k |
| 05 regex corpus | 1.000 · 7 q · 54.9k | 0.938 · 7 q · 25.4k | 1.000 · 5 q · 23.4k | 1.000 · 8 q · 49.4k |

**Run 5a did not show target-first winning**: the same or lower scores at one and a half to two times
the questions and tokens, and one stream given up. The transcripts laid the cost at four doors, three of
them this side of the conversation:

- *Fidelity compared serialisations.* 01's first transform wrote `evt:Event` — the same tree in the same
  namespace — and was refused, then re-asked at 16k tokens and 49 seconds. `TargetChecks.canonical` now
  walks the tree by namespace and local name, attributes sorted, whitespace-only text dropped; and the
  refusal shows the nearest event produced beside the target, so the model sees the difference rather
  than the demand.
- *The schema ladder moved, as A31 meant it to, but was still a ladder.* The alarm kind's target in 03
  climbed Door-incomplete, Alert-needs-Type, `Type="Alarm"`-not-in-the-enumeration, accepted: four
  target questions at 4k to 8k tokens where the direct run had spent five transform questions at 10k to
  19k on the same rungs. The content-model hint now carries the values of a required child whose type is
  an enumeration — `Type [Vulnerability | IDS | Malware | Network | Change | Error | Other]` — so that
  rung is gone.
- *A namespace slip read as nonsense.* 03's transform declared `xmlns="event-logging:3"` on the `Events`
  literal alone, so every `Event` beneath was in no namespace; the validator said *Event was found where
  Event is expected* and the hint added *Events contains Event\**. The scorer now sees the found name
  among the expected and says so: wrong namespace or none, declare the default namespace on
  `xsl:stylesheet`. In run 5b that hint fired three times across both shapes and was taken first time
  each.
- *The required fields never reached the model.* Both shapes put the reader's location under
  `Device/Location` and scored extraction quality 0.75 on 01 — `EventSource/Device/Name` is required by
  the document, but the list reached the model only as feedback, and 0.75 clears the 0.7 gate, so it
  never did. `QuestionText.system(doc)` now states the document's required fields and business-rule
  XPaths in the system text; `ModelAdvisors` and `LiveAdvisor` both use it.

**Run 5b, with those in, is the measurement.** Direct improved most: 01 right first time at 3
questions and 7k tokens (from 5 and 21.6k), 03 and 05 at 1.000. Target-first promoted every stream and
03 went from 11 questions to 6 — both targets accepted first time, both configurations right first time
— and 04's relearn rebound at 1.000 where direct rebound at 0.981. It still costs more: 5 to 8 questions
against 3 to 5 for one stream, and about twice the tokens, because every question carries the whole
transcript and the split and target exchanges compound into every later one.

Two things it lost on are findings, not verdicts:

- *01 at 0.857 under target-first, 0.964 direct*: the same header line, lost either way. Target-first's
  target for the header kind is `none`, and the parser the model then wrote dropped the header in DS3 —
  coverage 0.857 in the parser step, which has no other weighted scorer, so the step product carries the
  whole 0.857. Direct's parser kept the header as a record and the transform dropped it — yield 0.857 in
  the transform step, averaged against three 1.0s. Same information lost, a different score by where
  it was lost. That is §9.1's finding meeting the step product, and it wants a ruling: should a line
  whose kind's target is `none` count against coverage at all?
- *03 at 0.925 under target-first*: the alarm kind's target was `none` — the model, now told every
  event is scored on carrying `EventSource/User/Id`, judged a record with no user to be no event. The
  yield scorer caught it at the end (14 events from 20 records, 0.7), but only after promotion was
  decided. The system text now says the fields are scored as a share and a record without one is still
  an event; and the `none` reply is an unguarded exit from the target question, which the scripted
  scenarios do not yet test. A guard — a kind that is more than a small share of the sample is asked
  once more before `none` is taken — is the obvious shape, not yet built.

What the comparison said for A31 at that point: target-first does what it was designed to do — the
schema is learned on the cheap question, the transform is held to a concrete event, and the relearn
came back at a higher score — at about twice the token cost, and the two streams it scored lower on
were scored lower by the scoring, not by the events. On that evidence the node's default was set to
**direct** (`Conversation.Shape.DIRECT`; the scripted fixture stays target-first so every scenario
exercises the fuller conversation), the `none` reply was guarded — a kind seen more than once in the sample
has its first `none` questioned and its second taken — and the parser question was told to emit a
`none` kind as a record still, so the loss lands where direct's does.

**Run 6** added the feeds A31 was designed for: 06, corpus 003's Linux audit blocks (records of one and
five lines between `----` separators), and 07, a nested XML audit log (`nested-audit.xml`: eight
entries of two kinds, actor, session and document as subtrees). Both shapes, all seven feeds:

| Run | 6 direct | 6 target-first |
|---|---|---|
| 01 header CSV | 0.857 · 4 q · 14.8k | 0.964 · 7 q · 31.8k |
| 02 headerless | 1.000 · 3 q · 6.4k | 1.000 · 5 q · 16.1k |
| 03 two kinds | 0.963 · 4 q · 19.2k | 0.981 · 8 q · 53.1k |
| 04 scenario 27 | 0.981 · 7 q · 29.2k | 0.981 · 14 q · 70.5k |
| 05 regex corpus | 1.000 · 5 q · 21.4k | 1.000 · 8 q · 45.1k |
| 06 multi-line audit | 0.881 · 4 q · 45.3k · 183 s | **given up 0.85** · 8 q · 95.2k · 324 s |
| 07 nested XML | **given up** · 6 q · 102.9k · 578 s | — quota reached at question 3 |

- *01 swapped places*: this time direct's parser dropped the header in DS3 (coverage 0.857) and
  target-first's kept it (0.964) — the same asymmetry as 5b, the other way round. It is model variance
  meeting the scoring, not a property of either shape. The extraction rules now say a header line is a
  record too, emitted with its fields as data for the transform to drop; and the A11 question stands:
  coverage is `min(chars, lines)`, and on a sample of seven lines one header line is a seventh, where
  by characters it is a sixteenth. Whether coverage should be the character ratio alone is a ruling.
- *06 is the first feed where target-first's events were plainly better and it still lost*: yield 1.0
  against 0.69, extraction quality 0.917 against 0.833, conformance right first time against a re-ask
  — and given up at 0.85 on parser coverage 0.846, because its block split relied on a trailing
  separator and dropped the last record (line 13) with the leading `----` (line 1). Direct's parser
  took every line as a record and let the transform sort them, at the cost of yield. The loss of the
  last block is real and rightly penalised; a split judged on the sample alone cannot see that the
  stream's last record has no terminator, which is an argument for the split question to say so. The
  cost is also plain: the split question took 68 seconds and 10k tokens, the parser configuration 154
  seconds and 30k, on a twelve-line sample.
- *07 exposed the chain question, not the conversation*: direct chose `DSParser -> XSLTFilter` for XML
  input — the question's one example was that chain — and spent five parser candidates and 578 seconds
  failing to cut XML with the Data Splitter. Target-first made the same choice and then met the key's
  monthly usage limit at its third question. The chain question now says raw text needs a parser and a
  stream that is already XML begins with the transform, with an example of each. The XML feed is
  unmeasured in both shapes.

The key's usage limit closed the run: no more live questions this month. Where it stands: on clean
single-line feeds direct is as good and half the price; on the multi-line feed target-first's events
were better but its split was not, and the scoring made the split decisive; the nested feed has not
been measured. The owner's ruling on this (A32): the shape is not a choice to make in code but a
setting on the document beside the model — different models, trained differently, may want different
conversations, and the harness exists to find out which. That was first built as a `ConversationShape` field on
the document, then (A33, slice 11) as a preset a step list inherited from, and then — the coherence
audit finding a preset is a mode in all but name — taken out (A34): the document owns its steps, and
the two conversations are `ConversationExample`s the Learning tab loads from. `Conversation` reads the steps from
the document it is learning for, and the constructors and fixtures that carried a shape are gone. The
scripted scenarios give their documents the target-first steps, so the fuller conversation stays exercised.
The next live run, when the
key allows, should be 06 and 07 in both shapes with the chain steer and header rule in, and — if 06
repeats — the split question told that a record may end the stream without its terminator.

**Run 7**, 2026-09-21 to 22, is phase B's live run (design 03 §2): the six formats of slices 13–18 and
the JSON pair, rows 08–14, on sonnet-5, under target-first and then escalating, five candidates a step,
the harness restricted to those rows (`SHAPESHIFTER_LIVE_ROWS`). Archived as
`build/live-runs/sonnet-5-run7-{target_first,escalating}`. Score · questions · tokens · seconds:

| Run | 7 target-first | 7 escalating |
|---|---|---|
| 08 syslog | 1.000 · 7 q · 56k · 131 s | 1.000 · 4 q · 38k · 98 s |
| 09 auditd | 0.997 · 11 q · 181k · 274 s | **given up** · 6 q · 114k · 543 s |
| 10 Windows security | 0.997 · 13 q · 415k · 849 s | 1.000 · 2 q · 28k · 165 s |
| 11 JSON lines | 0.999 · 9 q · 80k · 180 s | 0.938 · 6 q · 83k · 311 s |
| 12 JSON document | **given up** · 10 q · 215k · 883 s | **1.000, wrongly** · 6 q · 144k · 1727 s |
| 13 fixed-width | 1.000 · 8 q · 68k · 71 s | 1.000 · 4 q · 30k · 52 s |
| 14 CSV, embedded newlines | 1.000 · 15 q · 258k · 166 s | 1.000 · 4 q · 30k · 64 s |

Phase B's exit criterion — at least four of the six formats learned live to the floor with the default
plan or the escalating one — is met by either plan alone: five of six under target-first, five of six
under escalating, and every format under one or the other. The positional regex, the quoted-field
regex, the syslog and auditd splits and the Windows `Data[@Name]` mapping were all written by the model
from the rules and the instructions. What the run found:

- *The JSON document is the run's one real failure, and it is ours.* Under target-first the transform
  was refused five times on yield alone — "12 records from 1 input record is 12.0 per unit against an
  expected 1.0" — because the parser's XML has one root child, the map holding the array, and the stage
  counts a JSON document as one record (scenario 46 dropped the yield scorer for this case; the live
  row carried the default). Under escalating the same wall stood, and on its last candidate the model
  did what the scorer asked: a stylesheet that emits *one* event from the array's first item, with a
  comment saying the document is one record so one event is produced. Yield 1.0, every gate passed,
  **promoted at 1.000** with eleven of twelve events dropped — the promotion a person reviewing the
  transcript would have refused, which is phase G's exit criterion failing in rehearsal. The one target
  had been proposed for the whole document, since with no split step the root's one child was the
  record, so fidelity was satisfied by the one event. Everything follows from one miscount: a JSON
  document's records are its array's items, at the split, the target, the stage's count and yield.
  Owed in design 03 §5 since slice 16; now urgent, and the strongest evidence yet that a scorer wrong
  about the unit steers the model to a degenerate output the gate then admits (A16's concern, from the
  other side).
- *auditd under escalating*: with no split step the parser must cut and extract at once, and the model's
  line-per-record parser scored yield 0.35 against the 0.6 the document asks (0.35 per line stated),
  then its two attempts to join lines by serial left content unmatched. Target-first learned the same
  feed in eleven questions. A multi-line record wants the split question asked on its own.
- *Windows security under escalating*: promoted in two questions and 28k tokens against thirteen and
  415k under target-first — the target-first conversation re-asked the split and the transform several
  times on the way to the same result. On a feed that is already XML with one obvious record element,
  the split and target steps cost more than they found.
- *Cost*: target-first spent 1.27M tokens over the seven rows, escalating 0.47M, for the same five
  promotions each; the escalating plan's savings are on the clean feeds (13, 14, 08), where the direct
  route passed first time and no target was ever asked.

Where it stands: the formats are learned; the plan that suits a feed is the one whose first question is
the feed's hard part — the split for multi-line text, the transform for XML — which is the case for the
plan as a per-document setting (A32) rather than one default. The JSON document count is the next
thing to build, before anything else is measured against it.

**Run 8**, 2026-09-22, is row 12 alone under both plans after slice 19 built the count (§6.1):

| Run | 7 target-first | 8 target-first | 7 escalating | 8 escalating |
|---|---|---|---|---|
| 12 JSON document | given up · 10 q · 215k · 883 s | 0.999 · 7 q · 60k · 106 s | 1.000 with one event · 6 q · 144k · 1727 s | 1.000 · 4 q · 53k · 322 s |

Under target-first the split named `events` and the transform's twelve events were a yield of one per
record; under escalating the new `SPLIT when json` step took one turn of 3k tokens, and the promoted
stylesheet applies templates over `//array[@key='events']/map` — every item, not the first. The
harness's own assertion that it ran seven rows, true of run 7 by coincidence, now says at least the
selected ones. Archived as `build/live-runs/sonnet-5-run8-json-*`.

The ninth slice, 2026-09-18, is the node's advisor (design 01 §12 item 6). `Advisors` gives a stage its
advisor per document — the document names the model and the instructions — and `ModelAdvisors`, the
node's default, answers with `ModelAdvisor` over `stroom-ai`'s chat model for the document's model: the
key resolved through the credentials service, the base URL guarded against SSRF, the HTTP client as
configured, all as `stroom-ai` already does them. It puts `QuestionText`'s words after the system text
and the attempt's transcript, counts the tokens the model charges, and audits every call against the
Shapeshifter AI document through `DocumentEventLog` — model, kind of question, turn, tokens, outcome; not
the sample, since A17's redaction is not built. No response cache stands between it and the model, which
is §10's cache bypass; transport retries are the client's own, so a dropped connection is not a failed
candidate. A document naming no model gets an advisor that fails the stream naming the Learning tab. The
`Conversation` now holds an attempt to its budgets (A5): wall-clock from `attemptBudgetMs` and tokens from
`tokenBudget` through `Advisor.tokensUsed()`, checked around every question, exhaustion an abandonment
with the reason. A node can now learn for real; what stops it is only a model document and credit.

The tenth slice, 2026-09-18, is **learning against a target** (A31; design 01 §10.1) — scenarios 34 to 37
scripted, 38 not yet. The conversation has a shape, `DIRECT` (A21, kept for the comparison) or
`TARGET_FIRST`, at that point the node's default — the comparison of §6.3 reversed that (A32) and
slice 11 made the shape data on the document, and A34 an example a document loads its steps from,
no more. Target-first goes: the chain question as before; then, for a parser,
the **split** question — a DS3 configuration that cuts the sample into whole records and nothing more,
judged by compile, coverage, yield and a *wholeness* check that the record text emitted is at least nine
tenths of the input, since coverage counts a consumed group as covered whatever it kept (scenario 37's
bad split, `maxMatch="1"` with `<all/>`, passes coverage and fails wholeness); then one **target**
question per record kind — kinds by `ShapeSignature`'s text skeleton of the record's first line, first
seen, at most three — asking for the event that record becomes, or `none`, each reply validated and
scored against the document's scorers as a one-event document before any configuration is written
(scenario 34's degenerate target is refused with the extraction-quality feedback); then the
**configuration** questions, each carrying the settled split and the targets. Two checks follow the
scorecard in judging a configuration: **preservation** for a parser — every leaf and attribute value the
target takes from its record must appear in some emitted record's data, the re-ask naming the values the
records lack (scenario 35: a splitter keeping two fields of four passes coverage at 1.0 and is sent back
for `logon` and `office`); **fidelity** for a transform — some event produced must equal the target,
canonically (inter-tag whitespace and namespace declarations set aside), the re-ask quoting the target
(scenario 36: `where` in `User/Id` is valid and wrong). The targets ride the `Learned` outcome onto the
regression set's `Accepted` rows, where the A18 check will read them as goldens.

The fixtures grew a **`Structure`**: a script built with `scenarios.script(splitter, stylesheet)` answers
the split and target questions from the configurations it will give — running the splitter over the
whole sample, finding the record the question's representative came from, running the stylesheet and
returning the event at that index — so a scenario states only what it is about, and `scripted()` is the
questions the scenario wrote. The Tier 2 tests build theirs with the node's parser factory, reached
inside the processing pipeline's scope. Every earlier scenario passes with the new questions in the
conversation; 126 in the module, two in `stroom-app`.

The live comparison — the five runs of §6.2 with `SHAPESHIFTER_LIVE_DIALOGUE=DIRECT` and then
`TARGET_FIRST`, same model, same feeds, transcripts under `build/live/<shape>` — ran twice the same day;
§6.3 has what it found, and what it changed between the two.

The eleventh slice, 2026-09-18, is **the conversation as data** (A33; design 01 §10.2; scenarios 39–40).
On the document, `ConversationDefinition`: the steps — `ConversationStep`s of a `QuestionKind`, a `StepGuard`
and per-step limits, written and
read as one line each, `TARGET when text candidates 3 kinds 2` — and override-only templates by
`Template`, with the built-ins' version stamped on save. Structural problems (`CHAIN` first and once,
`CONFIGURE` last and once, `SPLIT` and `TARGET` at most once) are the shared class's, so the client can
say them too; a template's variables are checked by the server's `Templates`, which holds the built-in
text of every template with its `${variable}` slots and renders in one pass, so a sample that happens
to contain `${amount}` is not read as a slot. `QuestionText` is now an instance over a document's
templates: it computes the blocks the variables stand for — headers, elements, sample, feedback, split,
targets, previous, rules — and `Templates` fills them; both advisors take a `QuestionText` and put the
same words. `Conversation` walks the effective steps: chain, then whatever the list asks between, each under
its guard (raw text is a parser with a configuration to write; otherwise the input is already records)
and its candidate limit, then the configurations; a target asked before any split is proposed over the
sample's lines. A definition it cannot hold is abandoned before the model is asked, naming the rule
and the Learning tab; the store refuses the same on save. The Learning tab shows the steps as text, with
the two measured conversations as examples to load from (`ConversationExample`, A34 — the preset the slice
first carried was taken out the same day), and the templates one at a time — the effective text, editable, "Use built-in" to drop a
change, the built-in version and the version the document was last saved against — the built-ins
served by a `templates` resource. `TestQuestionText` covers the override, the refused variable and the
slot-lookalike; the store test the refusals and the stamp; scenario 39 the reordered steps and the
abandonment. 134 tests in the module, 12 in `stroom-core-shared`, two in `stroom-app`.

The twelfth slice, 2026-09-21, is **the plan as a graph** (A37; design 01 §10.2; scenarios 41–42), and
the rename: the document's `DialogueDefinition` is its `LearningPlan`, `DialogueStep` a `PlanStep`,
`ConversationExample` a `PlanExample`, the harness variable `SHAPESHIFTER_LIVE_PLAN`; `Conversation`, the run,
keeps its name, and the document's JSON property is `plan`. Two closed lists join the question kinds in
`stroom-core-shared`: `Check` — the ten checks a step may name, seven wrapping a scorer of §8.4 — and
`StepOutcome` — `passed`, `refused`, `compile-failed`, `run-failed` and one `<check>-short` each. A
`PlanStep` carries an id (the kind's name, or the role's, when unset), a `ConfigureRole` for `CONFIGURE`,
its checks and its `Transition`s, in the one-line grammar of §10.2; `LearningPlan.problems()` adds unique
ids, every `goto` naming a step or `end`, and CONFIGURE free to repeat. `Conversation` is now an interpreter
walking the steps: each kind is a unit of work asked candidate by candidate through one driver, which
records the step id, candidate and outcome on every turn (`Exchange`), takes a transition the step
declares on an outcome at once, takes `on spent` when the candidates are gone, and abandons a transition
taken twice; a pass goes to the next line unless `on passed goto` says where, and `goto end` ends the plan
— which the escalating example needs, since its direct transform's pass must not fall through to the
target. A `CONFIGURE` step's role picks the chain positions it configures; an element learned again forgets
what was learned after it, and an element still unconfigured when the plan ends abandons the attempt. The
routing of §10.1 rule 6 left the code: a transform whose fidelity check fails looks at its own input, and
where the parser's records lack the target's values reports `preservation-short` for the plan to route;
the target-first example routes it `goto parser`. Scenario 41 runs the escalating plan twice — a clean
feed learned direct in three questions, and the degenerate transform twice spending the step and
escalating to a target; scenario 42 puts preservation at the transform by its checks, sees the jump
taken, and sees it refused the second time. Every earlier scenario passes unchanged, the two examples
reading into the graph with the same meaning. 140 tests in the module, 14 in `stroom-core-shared`, two
in `stroom-app`; the Learning tab's help text carries the grammar and the third example.

Audited the same day (code review, high): nine findings, all fixed. `on passed goto` fired after a step's
first unit — a `CONFIGURE` over the whole chain would leave the rest unconfigured — and now fires once the
whole step has passed, with `on passed abandon` abandoning as it says; an explicit id that shadowed another
step's default name passed validation and now collides; a `goto chain` kept the old chain's split, records
and targets and now forgets them; markup over the sample size limit was cut mid-document and is now cut at
a record boundary — the root's tag, whole children, the root closed — and text is bounded by whole lines;
a record element nested inside one of its own name counted twice and now counts its outermost occurrences,
which is what wholeness measured; `Templates.VERSION` is 2, since `SPLIT_XML` joined the built-ins; a
document saved with the plan under its old name `conversation` reads as it was written; `recordElement`
evaluates its XPath once; the new methods' javadoc is markdown. Regression tests for each.

The thirteenth slice, 2026-09-21, opens phase B (design 03 §3) with **syslog** (scenario 43). The fixture,
`syslog.log`, is twenty sshd authentication lines from two gateways on one feed — one sending RFC 3164,
the BSD form with a month and day and no year, the other RFC 5424 with a version and an ISO timestamp —
with a golden `syslog.events.xml` of Authenticate logons naming the user, the client address and the
gateway. Two splitters: `syslog.ds3.xml` takes both forms, two regexes in one group, each sub-parsing the
message; `syslog-3164-only.ds3.xml` takes the BSD form and drops the rest with `<all/>`, scenario 4's
idiom. The stylesheet composes the BSD form's timestamp with the year the instructions supply. Under the
default key the feed is one shape: the one-form splitter scores coverage short over the learning prefix
and is re-asked naming lines 2, 4, 6 …; the two-form splitter promotes, and the output equals the golden.
With the signature in the key each form is a stream of its own shape: the first learned with the BSD
splitter, the second finds that variant dropping every line under the bound-variant trial and learns
from its own representative, and two rules result. The live harness gains row `08-syslog`. One finding,
the first of the format phase: syslog's priority prefix, `<38>`, begins with the character a naive
markup sniff looks for, and `YieldScorer` read a syslog stream as XML — zero input records, yield zero,
no split could pass. It now asks `ShapeSignature.isMarkup`, which wants an element, a declaration or a
comment after the bracket, as `OutputRecords` already did. 147 tests in the module.

The fourteenth slice, 2026-09-21, is **auditd** (scenario 44): `auditd.log`, eight interpreted audit events
of two to four lines each — logins and execve calls — with no separator, the boundary being only that
consecutive lines share a `msg=audit(time:serial)`. Three Data Splitter configurations: `auditd-split.ds3.xml`
answers the split question — one regex whose lookahead carries the serial as a back-reference, the run as
one field; `auditd.ds3.xml` parses each line of the run into a data of its type with its `key=value` pairs
and those inside a quoted `msg='…'`; `auditd-no-args.ds3.xml` the same but matching and dropping the
EXECVE's quoted arguments. `auditd.xsl` makes an Authenticate event of a login and a Process event of an
execve, the time from the epoch seconds. Two things the slice settled. A feed whose record spans several
lines says so through yield: the document's expected yield per line — 0.35 here — is what refuses a split
of one record per line, which coverage and wholeness both pass; so `YieldScorer` now judges a step whose
input is already records record for record whatever the document's basis, since a lines basis was made
for the raw input and would have judged the transform by lines of XML. And Data Splitter tries a group's
expressions in order at the current position — one that matches later than the start is an error, one that
does not match is skipped — so a `key=value` grammar anchors each expression with `^`. The live harness
gains row `09-auditd`. 148 tests in the module.

Audited the same day (code review, high) with slice 12's later fixes: ten findings, all fixed. A document
saved with a pre-A34 *preset* and no steps read as direct — the serialiser now gives it the example's steps;
the yield scorer's new markup test treated text that merely looks like markup, and a document of *no*
records, alike as text — now a document is a document however few its records (`Records.isDocument`), and
fragments or `<word>`-led lines are text, in the sample cut too; a single occurrence with a repeated *leaf*
child — one `<record>` of several `<data>` — was refused as a container, and now only a repeated child with
structure of its own makes one; feedback a transition carried was dropped when the step it named was
skipped by its guard, and now reaches the next question asked; the settled boundary is typed
(`Boundary`: a configuration or an element) rather than sniffed from a leading bracket in two places; step
ids and `goto` names are matched without regard to case; new files carry this year's header, new methods
their javadoc. Regression tests for each. 154 tests in the module.

The fifteenth slice, 2026-09-21, is **Windows security events** (scenario 45): `windows-security.xml`, fifteen
events in the Windows event namespace under an `Events` root — logons, logoffs and process creations by
`EventID` — with `windows-security.xsl` mapping the `EventID` to Authenticate or Process and the named
`Data` to typed fields, `windows-security-degenerate.xsl` copying every `Data` to a `Data` under `Unknown`,
and the golden. The split refuses `EventData` on wholeness and takes `Event`; the degenerate transform
validates and is refused on extraction quality; the typed one promotes outright, since the `Event`s are the
root's children and the stream's count is right. One finding, the second of phase B: the kinds of record a
target is proposed for are told apart by structure — the text skeleton of a line, the element skeleton of
a record — and a logon, a logoff and a process creation are one structure in this log, a System block and
a run of named `Data`, so one target stood for all three. The transform was still judged over the whole
stream and its output carries all three `TypeId`s, but the target step did not see the kinds the feed
has. What tells them apart is a value — the `EventID`, or the `Name` attributes of the `Data` — and how a
kind of XML record is recognised by a discriminating value is owed (design 03 §5). The live harness gains
row `10-windows-security`. 155 tests in the module.

The sixteenth slice, 2026-09-21, is **JSON** (scenarios 2 and 46): `records.jsonl` and `records.json`, twelve
API-gateway logins and logouts, one object per line and one document holding them in an `events` array,
`records.xsl` mapping the XSL/json vocabulary the parser emits to Authenticate events, and the golden.
The `JSONParser` step runner is run only — no configuration, no document — so the plan asks nothing
of it and the transform is asked over the parser's real XML; the `json` guard and the `SPLIT_JSON`
template join `text` and `xml`, and the walk knows its kind from the chain's first element: a parser
with a document to write is text, a parser without is JSON, no parser is XML. The split of a document
names the array whose items are records by its key, or `root` where every top-level value is one, as
JSON lines are; it is judged by whether such an array occurs and whether its items hold the document
whole. For the lines the split is skipped and the transform asked at once over two targets — a login
and a logout, two kinds — and the stream is promoted outright. For the document the split refuses
`client`, no array's key, and takes `events`; the document is learned whole, and at the stage counts as
one record, so it binds provisionally as the nested XML of scenario 37 does: the sample cut at the
array's items, and the stream's count by them, are owed (design 03 §5). Slice 15's finding is met in
part: a markup record's kind is its element skeleton with the values of its naming attributes — `Name`,
`key` — so the three Windows event kinds of scenario 45 are now three targets, and JSON maps with
different keys are different kinds; a kind by a discriminating value such as `EventID` is still owed.
The live harness gains rows `11-json-lines` and `12-json-document`. 161 tests in the module, 14 shared.

Audited the same day (code review, high): five findings, four fixed and one left as the convention it
questioned. A JSON document was told by its first bracket, so a log whose lines open with
`[Mon Sep 21 ...]` was learned whole, unheld-out and unbounded, and counted as one record, never enough
to promote — the value is now parsed, one object or array and nothing after it. A top-level JSON array is
wrapped by the parser in a keyless array under the root map, which no key reply could name, so the
split could not settle the commonest batch shape — `root` now reaches such an array's items. The record
skeleton was every element in document order with every naming value, so an array of three roles and
one of four were two kinds and a lone `<User Name="alice"/>` made every user a kind, starving later real
kinds of targets under the `kinds` cap — a record's kind is now its parsed tree with repeated siblings
of one skeleton counted once, a `key` always a field's name and a `Name` one only where the element
repeats among its siblings. Representatives were chosen by the record skeleton but counted by the text
skeleton of the first line, the same for every markup record, so a kind seen once was questioned as if
seen by all — one kind function serves both. Not changed: a run-only parser that cannot read the sample
abandons the attempt from the split as it does from `CONFIGURE`, outside the candidate loop and the
step's transitions; whether a wrong choice of parser should route back to the chain question is a
ruling for the plan, not this slice. 165 tests in the module.

The seventeenth slice, 2026-09-21, is **fixed-width** (scenario 47): `fixed-width.log`, thirty mainframe
sign-on lines of six columns by position with no delimiter — a fourteen-digit time, the user, the terminal,
the action, the result and a reason that holds spaces — with `fixed-width.ds3.xml` capturing the six by
width, `fixed-width-four.ds3.xml` capturing four and matching the rest of the line away, the stylesheet
over each, and the golden. Under the escalating plan the four-column parser passes: every character is
consumed, so coverage is 1.0 and has nothing to say, which is the point of the format. The transform
written over four columns cannot state an outcome, the business rule refuses it twice and `on spent goto
target` fires; the lines are two kinds — a reason of two words and of one — and a target is proposed for
each; asked again against the targets, the four-column parser is caught by preservation, the records
carrying no value for `PASSWORD OK` or `REVOKED`, and the six-column parser passes; the transform over
it is promoted outright, its output the golden. Nothing in the module needed to change: the escalating
plan, preservation and the positional regex were already there, and the slice is the fixture, the
scenario and live row `13-fixed-width`, whose document carries the outcome rule. 166 tests in the
module.

Audited the same day, the range since upstream (the owner's code review): six findings, all fixed. A
plan could say `CHAIN on refused goto end` and be held, and a refused chain then left the interpreter
with no chain and a null to dereference rather than an abandoned attempt — `CHAIN` takes no transition,
checked with the other constraints. A run-only element consulted `on <outcome> goto` but not `on spent
goto`, though its one run is its only candidate — both are consulted, from `CONFIGURE` and from the JSON
split alike. A split that consumed every line and emitted no records was judged wholeness-short whatever
checks the step named — it is refused, as a reply that is no split. A step id arriving as JSON was
trimmed and lower-cased but not checked, so `my step` saved by the API could not be read back by the
Learning tab — one pattern serves both forms. Markup that is not one document — a fragment per line, no
root — was learned whole, unheld-out, because its first character said markup — a document is one that
parses, and anything else is lines. A run-only element that could not read its input was reported as
compile-failed, a gate it has no configuration to fail — run-failed, so `on run-failed` routes. 169
tests in the module, 15 shared; GWT draft compiled.

The eighteenth slice, 2026-09-21, is **CSV with embedded newlines** (scenario 48), the last format of
phase B: `csv-multiline.csv`, twenty headerless records of a document store's audit whose note is quoted
where it holds a comma, a doubled quote or a line break, six of them spanning two lines;
`csv-multiline-split.ds3.xml`, one regex over the stream matching a record from its start with
`"(?:[^"]|"")*"` for the quoted field and emitting it whole; `csv-multiline.ds3.xml`, the same regex
into six fields; the stylesheet, which unquotes the note and makes a View or Delete on a Document; and
the golden, the line break intact. The catalogue expected the line split to fail *wholeness*, and it
does not: wholeness is a character share (A36's tuning), and a split of one record per line loses no
characters of a quoted record, only its boundary. What refuses it is yield against the lines a record
takes, which the document states as 0.77 per line and the line split scores 1.0 against — the same road
auditd took in slice 14 — before any target is asked. The DS3 rules now say how a quoted field is
matched, in the split's and the parser's text alike. One thing arranged rather than solved: the learning
prefix is cut by lines, so a quoted record can be cut in two at the sample's end; the fixture's six
two-line records are placed so that the four-fifths cut lands on a boundary, and cutting the prefix at
the settled boundary is owed (design 03 §5). Live row `14-csv-multiline`. 170 tests in the module.
Phase B's scripted half is complete: six formats, six scenarios green.

The nineteenth slice, 2026-09-22, is **the record boundary on the rule and in the stage's count and
yield** (A35), pulled forward from phase C on run 7's evidence (§6.3). `RecordBoundary`, shared: the
element that is one record in XML, or the key of the JSON array whose items are records, `root` for
top-level values; a routing rule carries the one its variant was learned with, null for raw text and for
rules from before. The conversation's learned outcome carries the split's boundary; `Attempted` carries it to
the scorers, so the yield scorer counts a records input by it — `OutputRecords.recordsBy`, which the
target checks now read through too — and the stage counts the records a stream brought by it, on the
parser's XML for JSON and on the input for XML, on the learning stream and, through the fragment runner,
on every stream the rule serves, at binding, promotion, retraction, relearning and the regression set.
The escalating example gains `SPLIT when json`, one cheap question without which a JSON document is one
record to that plan (design 01 §10.2; whether XML should have the same is the owner's). Scenario 46's
document case is promoted with yield judged and the rule carrying `array events`; scenario 37's nested
XML is promoted rather than provisional, its rule carrying `element entry`; and run 7's trap is a test —
a transform emitting one event from the first item is "1 record from 12 input records", refused. Run 8
(§6.3) confirmed it live under both plans. 175 tests in the module, 15 shared.

The same day the owner ruled A20–A22 as built and, from run 7, A39 and A40 (design 01 §13): the
escalating example asks the split of every input — one question, on which the count, the target and
yield rest — and a parser refused on yield goes back to the split (`CONFIGURE parser on yield-short
goto split`) rather than being re-asked blind. Scenarios 41, 42 and 47 gained the split turn; scenario
44 gained the escalating case run 7 gave up on — the split by serial holds, a parser cut per line is
refused on yield and sent back, the split holds again and the parser asked again is held to it —
promoted in six questions with no target asked. Design 01 §2.2 records phase B's first values of the
thresholds. 176 tests in the module.

The same day, in discussion, the owner asked whether the split carries what a record needs
downstream — the header with every CSV record, as a DS3 parser does; the parser then `SplitFilter`
for XML and JSON — and whether fragments without a root are handled. Three answers (design 01 §12
items 25 and 26). The header: the corpus's `001_csv_with_header` reads the header into a `var` and
names every record's data `$heading$1`, and scenario 1's script replies with it, but the rules taught
"a header line is a record too" and the live model hard-coded the names every time — a reordered
column would misfile silently. The extraction rules now carry a second worked example, the `var`
form, and say the header is then not a record; a test runs both examples and shows the columns
reordered still land in the right fields (built-in templates version 5). The `SplitFilter`: the
learned boundary is exactly its setting, and it belongs in the written fragment between parser and
transform, with the transform asked over one record's document — phase D, item 25. Fragments: XML
without a root is text to the stage today and its attempt is abandoned; the `XMLFragmentParser` as a
run-only step, the walk's kind following the parser's output, is scenario 49, phase D; JSON without
a root is the parser's root map and the split's `root`, handled by scenario 46. 177 tests in the
module.

Audited the same day, the working tree and the range (the owner's code review): seven findings, six
fixed and one kept by decision. The stage counted a stream of markup that is not one document — a
fragment per line — as bringing no records, the failed parse clamped to zero, so such a shape bound
provisionally and stayed so; it brings its lines, as the yield scorer counts them. The DS3 rules' text
changed without the built-in version rising — 4 now. The Learning tab threw on an empty steps box on
every change, since dirtiness is computed on each; an empty box keeps the saved steps, and the store
refuses a plan without CHAIN and CONFIGURE on save. A JSON document over the sample size limit was sent
whole — cut by lines beyond the limit until the cut by the array's items. Two transitions on one outcome,
or two on spent, were held with the first silently winning — refused. A byte order mark made XML or
JSON read as text to every classification — stripped where the stream is read, and ignored by the
classifications. Kept: an XML document with a DOCTYPE is not one document to the module, since the
hardened reader every parse here uses refuses it, and that refusal is deliberate (XXE); such a feed
needs the declaration removed before the stage, and the module treats it as text until then. 173
tests in the module.

Audited the same day (code review, high): nine findings, eight fixed and one found not to hold. The
serialiser threw where a document had no meta asset and parsed every document's JSON twice — it now
returns as the delegate does and looks for the old name in the bytes before parsing; feedback a transition
carried to a `CONFIGURE` step over a run-only element — a `JSONParser`, scenario 46's shape — was consumed
there and never reached the transform, and a run-only element is now run and judged outside the candidate
loop, leaving what was carried for the next question asked; `Scorecard.outcome` named whichever failing
scorer the document listed first, so a non-gate listed before a gate masked the gate's `-short` and the
plan's transition on it — a failed gate now decides; the yield scorer parsed a records input twice; an
at-once `on <outcome> abandon` reported the step's candidates as spent when one had been asked; the
Windows fixture and golden carried doubled backslashes in paths; three test classes' javadoc was
block-style. The claim that a one-record sample with a repeated structured grandchild is refused as a
container did not hold — the check looks at an element's own children — and a test now says so. 158
tests in the module.

The twentieth slice, 2026-09-22, is **the rules as rows** (A41), the first of phase C and the one that
had to come before the tables: `Rules`, a seam beside `Shapes`, `Ledger`, `Outputs`, `Reprocessing` and
`RegressionSet` — the document's rules in router order, appended, replaced, removed by uuid — with
`InMemoryRules` behind it until the A26 module lands. The routing table is gone from
`ShapeshifterAiDoc`: the stage reads the rules for the document it is learning for and writes one row
at a time at binding, promotion, retraction, rebinding, drafting, approval and rejection; `approve` and
`reject` no longer return a document; and the supervisor element's `writeDocument` is gone, so learning
never touches the document at all. What that buys is what A41 asked for: two nodes promoting two shapes
of one document write two rows instead of racing to rewrite one document, and the operator editing the
Learning tab no longer shares an optimistic lock with the machine.

The document's dependencies are now its model alone — an exported document carries its configuration
and none of what it learned, as a processor filter's state is not part of the pipeline it runs — and the
store no longer names or numbers rules. The Routing tab fetches the rules for the document through
`GET /shapeshifterAi/v1/{uuid}/rules` and saves them with the document through `PUT …/rules`, which
checks each fragment is a fragment (A20) as the store used to on save; a row-level Routing tab, each
action its own call, is owed with the operator surfaces of phase F. The scenario tests read the rules
from the seam rather than from the document they got back, which is also how they now seed a reserved,
pinned or draft rule. One test had to be told that rules outlive a stage: two runs against one feed in
one scenario now bind what the first promoted, which is what a cluster would do. 177 tests in the
module, 15 shared, 2 in the app; GWT compiled.

Audited the same day (the owner's code review): seven findings, six fixed and one accepted. Two were
the slice's own and serious. The whole-table `PUT` applied neither the operator's order nor their
position — an existing rule was replaced in place and a new one appended — so *Move up* did nothing and
a rule added at the top, "the operator has just decided it is the most specific", landed *behind* every
learned rule, where the router's first match never reaches it: their precedence was inverted. And the
two new endpoints went straight to the seam, so any authenticated user could read or rewrite any
document's rules by uuid, pointing a rule at a pipeline of their choosing to run on that feed's data.
The answer to both is the row-level Routing tab that was owed to phase F, brought forward: `POST
/rules?at=`, `PUT /rules/{uuid}`, `PUT /rules/{uuid}/position?to=`, `DELETE /rules/{uuid}`, each
checking `VIEW` or `EDIT` on the document and each answering with the table as the server now holds it.
That also closes the third finding — the tab saved the snapshot it fetched on open, on every document
save, so a rule promoted meanwhile was deleted as absent — since the tab no longer saves a table at
all: one action, one call, and the answer re-renders the list, so a rule promoted while it was open
appears rather than being overwritten. A document that learned before A41 lost every rule on upgrade,
the serialiser migrating `conversation` but not `routingTable`; the first read of such a document now puts
its rules where they belong, once, and only where the rows have none, at the cost of one `contains`
over bytes already in hand on every other read — the A26 module will do it as a migration of its own.
A preset this build does not know now leaves the document openable, with the default plan's steps,
rather than unopenable and so unrepairable. The store's dead fragment check and stale javadoc are gone.
Accepted: `InMemoryRules` is a node-local map until slice 21 gives it tables — which slice 20 made
observable by putting it behind the UI, and is what phase C exists to fix. 185 tests in the module.

The twenty-first slice, 2026-09-22, is **the tables** (A26): `stroom-shapeshifter-ai-impl-db`, in the
pattern of `stroom-ai-impl-db` — a Flyway migration, jOOQ codegen against a temporary database, its own
connection provider and config — holding the five tables A41–A44 settled on. `shapeshifter_rule` is one
row per rule with a `sort_order`, since the router takes the first match and an operator may move a rule
above the learned ones; the selector is the JSON of its expression, read and written whole because
nothing queries into it. `shapeshifter_shape` is one row per `(doc, shape)` with what the stage knows —
given up, marked, awaiting review — its rolling score, and the lease columns A42 will use.
`shapeshifter_ledger` is one row per sentinelled input. `shapeshifter_feed_state` and
`shapeshifter_spend` are written now and used when A24 and A44 are built.

`RulesDao`, `ShapesDao` and `LedgerDao` implement the seams the stage has used since slice 8. A
promotion is one insert and a rebinding one update, so two nodes promoting two shapes of one document do
not contend; an insert at a position moves the rules below it down in one statement; a rolling score is
folded inside a transaction on the row, so two nodes serving the same shape do not each read the old
mean and write over the other's; and the ledger's release reads and deletes under `for update`, so two
nodes promoting the same shape cannot both replay the same backlog. A shape row is made with
`onDuplicateKeyIgnore`: two nodes meeting a new shape at once both insert, the unique key decides, and
the loser reads what the winner wrote.

The node now takes its rules, shapes and ledger from the tables — `DbConnectionsModule` installs the
module, and the element module no longer binds the in-memory ones — while a harness without a database
installs `InMemoryStateModule` instead, which is what the mock-service tests of `stroom-app` do. Eight
tests run against MySQL 8.4 in the module's own source set: a rule survives the round trip whole and in
its order, an operator's rule sits where they put it, a rule moves and the others close behind it, a
replace keeps a rule's place and a promotion whose row was pruned goes back in, one document's rules are
its own, a shape remembers why it was given up, the rolling score is of what it has done lately, and the
ledger releases once. What is owed to finish phase C's exit criterion: the Tier-2 scenarios of design 02
§2 run under the mock-service harness, so they exercise the stage against memory; running scenarios 20,
30 and 31 against the tables needs the `CoreTestModule` harness, and is the next slice's, with the lease
(A42) and the cluster-wide spend (A44) that the shape and spend tables are now ready for.

Audited the same day (the owner's code review), fifteen findings over the branch, all fixed. The one
that mattered most was mine and simple: the module's config was not reachable from `AppConfig`, so
`./gradlew build` failed on the presence test and an operator could never have pointed the tables at
another database — a `ShapeshifterAiConfig` now carries the DB config under `shapeshifterAi`, and the
expected YAML is regenerated. I had run the touched modules, not the build.

Two in the learning path. A JSON stream over the sample size limit was cut by lines, which makes JSON
that will not parse, so the split abandoned the attempt: every real feed over 8 KB failed, and nothing
caught it because the fixtures are 2.9 KB. It is now cut at the items of its widest array and closed
again, as a long XML document is cut at the root's children — the cut design 03 §5 had owed since slice
16, now load-bearing. And the array key the model replies with goes into an XPath predicate: the reply
was validated as anything without spaces or angle brackets, so `a]|//x[` reached Saxon, which threw
where nothing catches — one bad reply failed the stream instead of refusing a candidate. A key is a
word now, at the reply and again in `recordsBy`, which names nothing rather than building a predicate
it cannot quote.

Four in the tables. `remove` left a hole in `sort_order` while `insert` and `move` read it as a place in
a list, so after a delete an appended rule landed above the last one and a move to the end did nothing;
the rules below now close up. `insert` and `move` ran their statements outside a transaction, unlike
`scored` and `release`, so a shove that committed without its insert would leave a hole and no rule.
`replace` bumped a version it never compared, so a supervisor rebinding a rule and an operator editing
its selector both succeeded and one was lost; they are serialised on the row. The ledger had no
uniqueness, so a stream sentinelled twice would be replayed twice — the thing its own javadoc warns
against. And `shape_id varchar(500)` could not hold what a learning key naming a sender-supplied header
produces: rows are keyed on the id's hash now, with the id beside it for a person reading the row.

The rest: yield counted the output by the root's children while counting the input by the boundary, so
a parser's XML of one map holding twelve items scored one record in twelve lines; a `read` wrote to the
database, which made an import's confirmation screen write rules for a pack the operator then cancelled
— a read is a read now, and the store's `readDocument` does the migration; the Routing tab's copy
dropped the record boundary, which is not history but how a bound stream is counted; the Learning tab's
modal fired on every dirty check, and the fault is shown beside the steps; a boxed `Integer` was
compared by reference; and the rule dialog turned a catch-all's null selector into an empty `AND`, which
the grid and the draft-pairing both read differently. 186 tests in the module, 11 against MySQL.

The twenty-second slice, 2026-09-22, is **scenario 20 against the tables**, the first of phase C's exit
criterion. Tier 2 had been running under the mock-service harness, which has no database, so what it
proved about the ledger it proved about a map in one node's heap.
`TestScenario20SentinelInAPipeline` extends `AbstractCoreIntegrationTest` instead — the node's own
wiring, `DbConnectionsModule` and all — and asserts what it is testing with: the injected `Ledger` is a
`LedgerDao`, the injected `Rules` a `RulesDao`. A stream of an unknown shape under a document with
learning off produces no output stream, one `ERROR` naming the shape, no rule, and one row in
`shapeshifter_ledger` naming the input's meta id, which `release` then hands back — the replay A12
promises, from a row that survives the node. Scenarios 18 and 19 stay under the mock harness, where
they are about the pipeline rather than the tables, and are quick.

What phase C still owes: scenarios 30 and 31, which need A28's durable attempts — the attempt and turn
tables, the conversation as a state machine that stops at a question and resumes on any answerer, and the
worker that advances one awaiting the model — and the lease (A42) and cluster-wide spend (A44), whose
columns the shape and spend tables already carry.

The twenty-third slice, 2026-09-22, is **the lease and the spend counter** (A42, A44), which make those
two rulings real rather than designed. The lease is a conditional update on the shape row: one learner
per shape across the cluster, taken before the conversation and given back in a `finally`, its expiry the
attempt's own budget and a little more, so a node still working keeps it and one that died lets the
next in soon after it would have finished. A node that does not win it does not wait — that would hold
a processing thread for the length of an attempt, and at hundreds of threads a shape's first minute
would stall the cluster — it sentinels its stream, and the winner's promotion releases the backlog as
A12 releases any other. The stage learns which node it is from `NodeInfo`.

The spend is a `Spend` seam and a row per document: what an attempt cost — the tokens the model charged
and the questions put to it — added to the document's window inside a transaction, so that two nodes
finishing at once do not each add to the same old total. It is counted whether the attempt learned
anything or not, since an attempt that abandons costs what it asked. The window is an hour for now; the
policy that reads the count — the per-document rate limit and the spend breaker that opens error mode —
is A24's, and is built with it in phase E, which is why this slice adds a counter and no refusal.

Five tests against MySQL: one node learns a shape and the other is told to go away, the holder may take
its own lease again, a different shape is a different lease, a node cannot release what it does not
hold, an expired lease is free, resetting a shape leaves the lease alone — that last because a
promotion resets the shape while the attempt that promoted it still holds its lease — and every node's
spend is counted in one place, one document's being its own and a window that has run out starting
afresh. Two scenarios in the module: a second node meeting a shape sentinels rather than waiting, and
its stream is released by the winner's promotion; an expired lease lets the next node learn. 188 tests
in the module, 15 against MySQL.

Audited the same day (the owner's code review), seven findings, all fixed, and the first of them showed
that the paragraph above said something the code did not do. The lease was released as soon as the
conversation ended, not when the rule was written, so between the two — the judgement over the whole
stream, the fragment's documents, the rule's row — another node found no rule for the shape, won the
lease and learned it again, and both appended a rule for one selector: the router serves the first and
the second's fragment is orphaned. It is held now until `bind` or `givenUp` has run. The test that
pinned the old claim had been written from the claim rather than against the code; the new one puts a
second stage's run at the moment the rule is appended, and fails if the lease is released any earlier.

The expiry was set from the stage's clock and judged against the wall clock, so a scenario whose clock
is fixed — every scenario — took leases that were expired on arrival and excluded nobody; the two
scenarios hid it by planting the competing lease by hand. The seam takes the caller's *now* as well as
its *until* now, and the new scenario lets a second stage take its own. Relearning called the model
without any lease at all, so two nodes could relearn one shape and, in review mode, each write a draft
and overwrite the other's `awaitReview` — leaving a draft that every later stream sentinels on and that
approval cannot resolve; it takes the lease too, and a node that does not win it serves the stream from
the incumbent, which is what an incumbent is for. Nothing extended the lease during an attempt, though
this section called re-taking it the heartbeat: the conversation now heartbeats before every question, so a
slow model call cannot cost a node its lease.

`InMemoryShapes.reset` dropped the whole row and with it the lease, where the row keeps it, so the two
implementations of one seam disagreed exactly where A42 matters. The spend was recorded after the
conversation returned rather than in a `finally`, so an attempt whose model call threw — the runaway A24's
breaker exists to see — showed no spend at all. And both DAOs inserted with `ON DUPLICATE KEY IGNORE`
before selecting `FOR UPDATE`, which on the steady path leaves a shared lock the select must upgrade:
two nodes recording spend for one document at once would deadlock on that upgrade, which is the very
thing the table is for. The row is looked for before it is made. 189 tests in the module.

The twenty-fourth slice, 2026-09-22, is **the attempt as a record** (A28), the first of the four that
A28 needs. `shapeshifter_attempt` and `shapeshifter_turn` join the A26 module: one row per attempt —
which document, which shape, which stream raised it, which node took it, both modes, its status, what
it came to and the rule it wrote — and one per turn, with the question, the answer, who answered it and
what the answer scored. An `Attempts` seam, a DAO and an in-memory twin as every other seam has. The
stage opens an attempt when it commits to learning a shape, writes a turn for every exchange of the
transcript, and closes it with the decision's own words; an attempt whose node, model or database threw
is closed `ERROR` rather than vanishing. Nothing about how the conversation runs has changed: this slice is
the record, and the resuming is the next one's.

Two things it does not do, for reasons worth stating. It does not store the rendered prompt, only a
line saying what the turn asked — the kind, what it was about, and how much the step had been told —
because the prompt carries the stream's own text, which may not be stored until A17's redaction is
built (A38), and which `stroom-ai` audits in any case. And the attempt is not yet the claim on the
shape that A45 makes it: the shape row's lease still holds that, and the attempt row carries the expiry
column that will take it over when the conversation can be resumed. A node that does not win the lease
opens no attempt, since it learned nothing.

Ten tests against MySQL now, including that an attempt and its turns read back whole and in order, that
a shape id longer than any column is still one attempt, and that the newest attempt of a document comes
first — which is what the Supervisor view lists. In the module: every attempt is recorded with its
turns and what it came to, an attempt that bound nothing says so, and a node that lost the lease
records none. 191 tests in the module.

Audited the same day, at the owner's asking, before the resuming is built on these tables: eight
findings, all fixed. The worst was invisible to every test: the node makes a new advisor for each call,
each counting its own tokens, so an attempt that asked one and read another recorded nothing spent —
every row said zero, and the harness could not tell because its advisor is one shared object. One
advisor is now resolved per attempt and passed to the conversation, which is what `learn` already did and
what the recording did not. The same re-resolution inside the catch block could throw over the failure
that brought it there, leaving the attempt stuck at `IN_PROGRESS` — the state that block exists to
prevent.

Two were about what a record may cost. The turns and the closing row were written after the rule was
bound and the output emitted, so a record that failed would fail a stream that had succeeded — a
document whose model reference carries no name would have done exactly that, against a column that may
not be null. Bookkeeping is now guarded: it logs what it cannot write, and never throws over the work
it describes. And this section's own claim that "a turn is inserted as it is answered" was not true —
they were written in a loop at the end, so an attempt still running showed none and one that threw lost
every question it had asked. The conversation now reports each turn as it is answered and again as it is
judged, and the row is written by number, so an attempt that fell over keeps the transcript a person
most needs.

The rest: a decision was recorded as its own `toString`, which for a shortfall carries the scorers'
diagnostics and so the stream's own text — the very thing the turn's description withholds until
redaction exists (A38); it is the decision's own words now. An attempt that wrote a draft stayed
`AWAITING_REVIEW` for ever, since approval and rejection said nothing to the record, and `REJECTED` was
written by nothing at all. `forDocument` asked for each attempt's turns in its own query, which the
Supervisor view would have done a hundred times a page. And a relearn where the incumbent won is
recorded as abandoned, which the decision's words distinguish but the status does not — whether that
deserves a status of its own is for the Supervisor view slice, since A28's list of statuses is the
owner's. 194 tests in the module, 16 against MySQL.

The twenty-fifth slice, 2026-09-22, is **the attempt resumed** (A28), and the shape of it is the point.
The conversation is not recast as a state machine that saves its workings: it is re-walked from the start
with the answers it was given. `RecordedAdvisor` answers from the attempt's turns in the order they
were put and then hands on — to the model, for a worker carrying an attempt forward; to nobody, for one
that is to stop and wait. Everything those answers produced is re-derived on the way: the chain, the
boundary, the records, the targets, each element's configuration and its output, because all of it
follows from the sample and the answers, both of which are kept. What the model said is a record; what
running produced is a consequence, cheaper to re-derive than to store and the stream's own text besides
(A38). A replayed answer is judged exactly as it was judged the first time; that the walk has reached
the same place is checked rather than assumed — see the audit below.

A45 is built with it: the claim on a shape moves from the shape row to the attempt. One open attempt
per `(doc, shape)` is what one learner means; a parked attempt still holds it, since it is still
learning; one whose expiry passes has lapsed and the next node takes the shape. A stage that reaches a
question nobody present can answer parks the attempt `AWAITING_MODEL`, sentinels its stream and
returns, which is deferred mode's shape without the worker that will drive it (slice 26).

The clock caught it again: the claim's expiry was set from the stage's clock and judged against the
wall clock, exactly as the lease was before the last audit, and every scenario's claim was lapsed on
arrival. This time a test found it rather than a review — the scenario that says a waiting attempt
holds its shape failed, because it did not. The seam takes the caller's *now*, as the lease does.
195 tests in the module, 17 against MySQL.

The audit of slice 25 (the owner's code review) found eleven, all fixed, and the first of them was a
sentence in the paragraph above. "The replay is its own check" was written of code that checked
nothing: `RecordedAdvisor` handed out its answers in order, to whatever was asked. A document edited
while its attempt waited — one more allowed element, a changed instruction — puts different questions,
and the answers kept would have been given to them, judged, configured and possibly bound. Every
question now carries a one-line `summary()` of itself, which is what a turn has always recorded, and a
replayed answer is given only to the question whose summary matches the one it answered. A walk that
asks anything else has diverged: the attempt is refused with `ReplayDiverged` and recorded as an
error, rather than carried on from a mixture of two walks. Everything in that line is re-derived by
walking the same plan over the same sample, so two walks that have reached the same place write the
same line, and the check costs nothing.

`resume` itself took no notice of who held the attempt or what it was about: it would carry on one that
another node was learning, one that had finished, and one whose stream was of another shape entirely.
It now takes the claim before it walks — `claimed`, which succeeds only for the holder or for an
attempt that has lapsed — compares the stream's shape with the attempt's, and sentinels or refuses
rather than learning beside another node.

The deeper finding was two mechanisms for one rule. The shape's lease (A42, slice 23) and the attempt's
claim (A45, slice 25) both said who was learning a shape, and the stage took both: `run` leased the
shape, `recording` opened the attempt, `resume` opened neither and leaked the lease on every path.
Two rows that must agree are a bug waiting for a schedule, which is exactly what A45 was ruled to
prevent, so the lease is gone — the seam, both implementations, its tests, and the columns, dropped in
`V07_13_00_004`. What is left is one claim, and it is the database that holds the rule: `claim_key`
carries the shape's hash while an attempt is open and nothing once it has closed, under a unique key on
`(doc_uuid, claim_key)`, so two nodes meeting a new shape are decided by the key and not by what each
read. The heartbeat, the lapse and the release all move to the attempt row with it.

The rest were of a kind: a parked attempt's tokens overwrote what it had spent before rather than
adding to it, so a resumed attempt's cost was only its last leg, and its A5 token budget started again
each time it woke — `Conversation.alreadySpent` carries it across. A replayed turn was rewritten with the
model's name over whoever had answered it, which would have made a person's answer the model's in the
record. A draft awaiting review was counted as still holding its shape, though it holds a rule instead
and a person's decision is what moves it. `parked` would raise a finished attempt back into life. And
the in-memory twins had drifted from the tables in three places — the newest open attempt, the claim
cleared on close, a stream sentinelled twice for one shape being one row — each now as the DAO has it,
since a scenario that passes over a twin that lies proves nothing. 197 tests in the module, 16 against
MySQL.

The twenty-sixth slice, 2026-09-22, is **deferred mode and its worker** (A5, A28), which closes scenario
30. Until now every document learned in the task whatever its mode, and deferred is the *default* mode —
an LLM call inside a processing task holds a task slot and a volume handle for as long as the model
takes, which is the whole reason A5 exists. A deferred document now asks nothing in the task: the stage
opens the attempt, parks it at its first question and sentinels the stream, and `DeferredWorker` carries
it on outside the task, at whatever pace the model answers. What it promotes releases the shape's ledger
as any other promotion does, so the streams that waited are reprocessed (A12).

The worker needs two things the stage was always handed: the document, and the stream the attempt was
raised on. Both become seams — `Documents` and `Inputs` — with in-memory implementations for the
harness, because the sample is not copied into the attempt: a stream's own text may not be stored until
redaction is built (A38), and a stream is already kept where streams are kept. An attempt whose stream
has been aged off, or whose document has been deleted, is abandoned with the reason rather than left
waiting for ever, since the shape it holds is a shape nothing else may learn. One attempt's failure is
its own — a pass that stopped at the first bad attempt would never reach the good ones behind it.

Writing the relearn case found a real fault. An attempt is resumed by re-walking the conversation, and
`resume` walked every attempt as though it were learning an unknown shape: a *relearn* (A29) carried on
by the worker appended a second rule for one selector instead of rebinding the incumbent, leaving the
first rule's fragment orphaned behind it — exactly what the claim exists to prevent, arrived at from the
other side. What an attempt is doing is not stored either, and need not be: it is read from the rules as
they stand, and a shape a rule already binds is being relearned. With that, the shape's mark moves too —
it is spent when the relearning has happened rather than when it was scheduled, or an attempt that parks
would find the shape changed under it and its re-walk would ask a different question. While it waits,
the shape's other streams are served by the incumbent, as they are served throughout a relearning, and
the stream in front of the stage is `Kept` rather than sentinelled: a bound shape's stream does not
belong on the ledger.

A parked attempt is nobody's — no thread is behind it — so any node's worker may take it up; a *running*
attempt is its own node's until its claim lapses (A45). That is one line of the claim rule, and it is
what lets the job be a cluster job rather than a node's own business.

Every scenario document now says `INLINE` for itself, through one fixture: the catalogue's scenarios are
about what learning does, not about when it happens, and scenario 30 is the one that is about when.
202 tests in the module, 16 against MySQL, 3 in Tier 2.

The twenty-seventh slice, 2026-09-22, is **the worker in a node**: what slice 26 owed. `StageFactory`
builds the stage that both the supervisor element and the job run — one list of runners and scorers, not
two — `StreamInputs` reads a stream back out of the store by the id the attempt kept, `StoreDocuments`
reads the document, and *Shapeshifter AI Deferred Learning* is a scheduled job that carries a bounded
batch on as the processing user, once a minute by default and editable in Jobs like any other. Scenario
30 is now green in Tier 2 as well: the processing task asks nothing and writes its error stream, the job
runs with no task in front of it, and the next stream of the shape is bound by what the job learned.

Two things had to give first. A stage cannot be a singleton: the Data Splitter compiler and the schema
scorer report through the pipeline-scoped `ErrorReceiverProxy`, so the job enters a pipeline scope of
its own — no pipeline in it, but a candidate configuration is compiled and run there exactly as it is in
a task.

And the reprocess request had to stop guessing its pipeline. It took the pipeline from the task it was
running in, which is no answer at all for a job outside one, and was already the wrong answer for a
document used by two pipelines — a release names streams that several pipelines may have sentinelled,
and a stream replayed through a pipeline that never saw it produces something nobody asked for. The
ledger now records where each stream it names was being processed; a release hands back rows rather than
ids, and the stage asks once per pipeline. A stream sentinelled by no pipeline — the harness, the worker
itself — is released and nothing is asked for it.

The wiring found two things already broken on this branch, both from slice 21 and both invisible to the
module's own tests: `TestConfigMapper`'s `AppConfig` subclass never gained the Shapeshifter AI config
that was added to `AppConfig`, so `stroom-config-global-impl` did not compile, and
`ConfigProvidersModule` — generated — had not been regenerated, so the config had no provider. Both are
fixed, the second by running its generator. 202 tests in the module, 16 against MySQL, 4 in Tier 2.

**Still owed.** The batch size is a constant in the job rather than a configuration property: the
feature's `ShapeshifterAiConfig` lives in the impl-db module, which the module the job lives in cannot
depend on, so giving an operator that dial means moving the config class first — worth doing when there
is a second thing to put in it.

The audit of slices 26 and 27 (my own, 2026-09-22) found one thing that mattered and several small ones.
**`resume` never asked what the shape had become while its attempt waited.** An attempt may wait hours;
in that time an operator may reserve the selector or turn learning off, a person may have a draft of the
shape in front of them, or it may have been given up — and the worker would carry the attempt on
regardless, appending a rule for a selector an operator had just decided binds nothing. The shape's
state is now read exactly as `run` reads it for a stream, and an attempt the shape no longer wants is
closed with the reason rather than carried on.

The worker counted a refused attempt as one it had carried on: an attempt another node had taken, or one
the shape no longer wanted, came back as a sentinel and was counted as progress. And `awaiting` hid a
parked attempt whose claim had lapsed, which is the wrong reading of the claim: nobody is behind a
parked attempt, so there is nothing to take it from, and a worker that was down while the claim lapsed
must still pick it up or the shape waits for a stream that may never come. The expiry now says who may
carry an attempt, not whether it is still wanted.

The rest were housekeeping: three constructor parameters left behind in the element when the stage moved
to its factory, two accessors nothing calls, a doubled Javadoc block and a doubled catch.

Three things are noted rather than changed. `claimed` lets a node take an attempt it is already running,
which is what a node re-entering its own attempt must do; what keeps two of a node's own threads off one
attempt is that a scheduled job cannot overlap itself, and that is now said where the method is. The
worker's promotion records an output binding for an output nobody wrote, since the worker emits no
stream — an artefact of the in-memory `Outputs`, which is a meta search over output attributes in a node
once its table arrives, where the phantom cannot exist. And a retraction still replays through the
pipeline of the stream in front of the stage rather than each output's own, which the ledger now knows
how to do and `Outputs` does not; it is the same approximation as before, and worth closing when
`Outputs` becomes rows. 204 tests in the module, 16 against MySQL, 4 in Tier 2.

The twenty-eighth slice, 2026-09-22, is **a person answering a turn** (A28), which closes scenario 31
and with it phase C's exit criterion. *Answer instead* and *edit and re-run from here* turn out to be
one operation over the machinery slice 25 built: write an answer against a turn, drop the turns after it
— what they were is a consequence of an answer that has changed, and re-walking derives them again —
and leave the attempt waiting for the worker. Nothing runs when a person clicks: their request must not
wait on a model, and the attempt is picked up as any other waiting attempt is.

For that to be answerable, an attempt that stops must say what it stopped at. It now records the
question as a turn with no answer and no answerer — which is also what a person needs to see in the
Supervisor view, and what the replay compares its re-walk against once they have answered. The conversation
is what knows where a walk stopped, so it fills in the step, the candidate and the number as the
`AwaitingAnswer` passes through it; `answered_by` becomes nullable, since nobody has.

An attempt that had finished is opened again and takes its shape back under the same unique key that
admits one open attempt per shape (A45), so re-running an attempt whose shape another is now learning is
refused and the person is told. Re-running also resets the shape: a person saying "run this again with
my answer" is saying the shape is not settled, and without that the re-run would be refused by the
give-up its own first run wrote.

The scenario itself: the model spends its one candidate on a splitter that drops the fields the event
needs, the attempt is given up, a person puts the right configuration in place of the model's, and the
attempt runs again — everything before their turn replayed from the record, their answer given where the
model's was, and only the transform asked. The transcript says who answered each turn, model and person
alike. 209 tests in the module, 20 against MySQL, 4 in Tier 2.

The audit of slice 28 (the owner's code review) found six, all in the new pair of operations and all
fixed. `amend` opened the attempt again *before* it checked that the turn a person named exists, and
nothing undid that: a mistyped or stale turn number would turn a promoted attempt into an open one with
its outcome wiped, tell the person their request had failed, and leave the deferred worker to pick it
up. The check comes first now, from the record already read.

An attempt a node is walking *at this moment* was quietly amended underneath it. Reopening treated any
open attempt as nothing to do, so the answer was written, the walk's next turn overwrote it with the
model's, and nothing ever carried the person's answer on — the worker only picks up what is awaiting.
Reopening now refuses an attempt whose claim is live and whose walk is running, and takes one whose
walk has stopped.

The claim was read too strictly in one direction and too loosely in the other. Reopening did not release
a lapsed claim, so an attempt held by a node that had died blocked its shape from ever being run again —
"another attempt is learning this shape", for ever, until some unrelated stream arrived and swept it.
And where the attempt being reopened was itself parked, its claim was not pushed out, so the next stream
of that shape could sweep away the very answer a person had just given. Both are the release that
opening a new attempt has always performed; reopening performs it too.

The last two were the twins disagreeing: the in-memory `amended` deleted the turns after the one named
before finding out that the turn did not exist, where the table's two writes are one transaction and
roll back; and `amend` took the document on trust, so the wrong document's attempt budget was used and
another document's shape row was reset. Both now do what the other did.

**Phase C's exit criterion.** Scenarios 20, 30 and 31 are green, 20 and 30 in Tier 2 against MySQL, and
an attempt paused, answered by a person and resumed is what scenario 31 is. Scenario 31 has no Tier-2
run of its own: it needs no node machinery the other two have not already proved — the same worker, the
same tables — and what it adds is the Supervisor view's to drive, which is where its REST resource
belongs.

The twenty-ninth slice, 2026-09-22, is **the rest of phase C**: the six things the phase delivers that
its exit criterion did not cover.

**The hot path came off the database.** Every stream of every shape read the routing table and three
shape-state columns from MySQL — a query per stream per node, which at hundreds of threads a node is the
first thing to give. `CachedRules` and `CachedShapes` sit in front of the rows; what writes them clears
this node's copy at once and fires an entity event that clears every other node's, because the node that
learns a rule is rarely the only node routing against it. The expiry in the configuration is a backstop,
not the mechanism. The rolling scores are deliberately *not* cached: they are counted across the cluster
(A44's reasoning applied to A23's scores), every served stream adds to them, and a node's own copy would
be wrong the moment another node served a stream.

**The feature got its own settings.** `ShapeshifterAiConfig` moved from the database module to the
feature's, which is what lets it hold anything but a database: the cache sizes, the worker's batch size —
a constant since slice 27, and owed — and how long a finished attempt's record is kept. The database
module depends on the feature's and not the other way about, so the config could not live where it was
and be read by the code that needs it.

**`Outputs` became rows, and the design's guess about how was wrong.** §12 item 8 has it as "a meta
search over the output stream's attributes"; stroom can only query the meta fields it registers, and a
custom stream attribute is not one of them. Registering five Shapeshifter fields in stroom's core meta
field list, where every meta query in the product would see them, is a worse price than a table, so
`shapeshifter_output` is a table like its neighbours — and it carries the pipeline, which closes the
approximation the slice-27 audit left open: a retraction now replays each output through the pipeline
that produced it rather than through whichever pipeline happens to be running. The bindings stay on the
output stream's attributes as well, where a person reads them.

**A prune job**, since one row per attempt and one per turn is the fastest-growing thing this feature
writes. An attempt still learning, still waiting for the model, or waiting for a person is never pruned
however old it is: age is not what says an attempt is over.

**The Supervisor** (A28) is a screen of its own beside Jobs, not a tab on a document, because what a
person wants is every attempt every document has made. `SupervisorResource` finds them across documents,
narrowed by document, feed, shape, mode or what they came to, and opens one to its conversation turn by
turn; behind it are *answer instead*, *edit and re-run*, approve, reject and re-learn. A person sees the
attempts of the documents they may see: a transcript carries what the model was told about a feed's
data, so it is read by the document's permission and not by one of its own. The GWT screen lists the
attempts and shows the selected one's turns.

**A18's targets as goldens** was built and then unbuilt as a gate. The check is real — the score is a
number over a whole record and two different events can reach it, so a candidate that scores the same
while writing something else is worth catching — but §7.4 rules the stored output *score-not-lower, not
byte-equal*, because a better variant legitimately produces different output. Scenario 9, which is
exactly "a better candidate replaces the incumbent", failed the moment the gate existed, which is the
design telling the code it was wrong. It is a signal now, said out loud where a person can act on it.

The audit of slice 29 (the owner's code review) found fourteen, all fixed, and three of them were the
kind that a demonstration would never show.

**The Supervisor's actions were held to the wrong permission.** Approving a draft binds a rule,
rejecting one gives a shape up, and re-running an attempt spends a model's tokens — all edits of a
document's routing, and all reachable by anyone who could merely *view* the document, because every
action went through the same read check. They are held to `EDIT` now, as editing the routing table by
hand always was.

**The page count was of what exists, not of what the asker may see.** Filtering happened after the
query, so a person who may see one document of ten was told how many attempts the other nine had made,
and their pages came back short with no way to reach the end. The documents a person may see now go
*into* the query, which makes the count theirs and the paging work. And "no such attempt" answered
differently from "not yours to see", which disclosed existence and served a 404 as a 500; they answer
the same way, as the method always claimed they did.

**A retraction replayed every generation of a rebound rule.** A rule keeps its uuid when it is rebound
(§7.3 rule 3) and the output rows are durable now, so `boundBy(rule)` reached back to everything that
rule had ever produced, under bindings that were correct when they ran. It is `boundBy(rule, fragment)`:
a retraction is of the binding in front of us. The in-memory `Outputs` lost this on restart, which is
why it had never been seen.

The caches fired `EntityAction.UPDATE`, which means *the document changed* — so every rule learned and
every shape marked would have re-indexed a document nobody had edited, on every node, and dropped its
name caches with it. `CLEAR_CACHE` is the action for a cache, and both already handled it on receipt.
The cached routing table was also the rows' own mutable list, handed to every routing thread on the
node: one caller sorting what it was given would have corrupted routing for every stream until the next
invalidation.

The prune job could never catch up — one bounded batch a night against a cluster making more attempts
than that in a day — so it takes batches until one comes back short. It prunes the output rows too,
which is the answer to the per-stream write that making `Outputs` durable introduced: what a retraction
can still reach is what a node is told to keep, and the alternative to a bounded table was an unbounded
one. Paging on both seams unboxed a `PageRequest`'s nullable offset and length, which a POST with
`{"pageRequest":{"offset":0}}` turns into a 500; the project's own `JooqUtil` helpers null-check, and
this code had reimplemented them without the guard. The Supervisor's request bodies were not
null-checked. `deferredLearningBatchSize` had no minimum, and zero silently stops all deferred learning.
The in-memory `found` handed back transcripts the rows do not, so no scenario could catch a regression
in the list/detail split. The turns pane had no ordering guard and no failure path, so clicking down a
list could leave one attempt's transcript under another's row. And `prune` had no test in either
implementation — including the rule that an attempt awaiting review is never pruned, however old.
215 tests in the module, 23 against MySQL.

**What phase C does not close.** Scenario 13's Tier-2 claim — that the release *creates a filter* — is
still owed: the Tier-2 harness binds a mock `ProcessorFilterService` whose `reprocess` does nothing, so
asserting a real filter needs an integration test against the DB-backed processor service. The
Supervisor's per-turn and per-attempt *actions* are REST-complete and tested but have no buttons yet;
they belong with phase F's other interactions, whose exit criterion is a person doing all of this from
the UI alone. And the regression set is still one node's memory: a stream per rule is phase E's.
212 tests in the module, 22 against MySQL, 4 in Tier 2, and the GWT UI compiles.

The thirtieth slice, 2026-09-22, opens **phase D** with §12 item 25: **the learned boundary as a
`SplitFilter` in the written fragment**. Stroom's own shape for markup is parser, then split, then
transform — the filter cuts the parsed stream into one document per record, so the stylesheet sees one
record as a person writing one is shown one, memory is bounded by the record rather than the stream, and
an error is isolated to the record that raised it. What this feature wrote was `JSONParser → XSLTFilter`
or `XSLTFilter` alone, with the stylesheet iterating the whole parsed document: right at fixture size,
wrong at stream size.

The filter goes in front of the first element that is not a parser — after the parser where there is
one, straight after the source where the input is already markup — and only where the rule carries a
record boundary. Raw text keeps none: the Data Splitter *is* the splitter, and a chain that cuts with a
configuration has no boundary of this kind.

The depth is **read, not assumed**. A boundary is a name, and how deep the records it names sit depends
on the document: the children of a root are one down, but an item of an array under a key turns out to
be *three*, because the parser wraps its output in a records root. So the conversation records the depth
when the split is settled, against the document it settled it against, and the boundary carries it to
the rule and on to the filter — and into the rule's row, since a rule read back without it would write a
fragment that splits in the wrong place and `equals` ignores the depth, so nothing else would notice.

The audit of slice 30 (the owner's code review) found three, all fixed, and the first of them was a
break this slice had just made. **The written fragment carried an element the fragment runner refused to
run.** The runner walks the fragment's elements and asks for a runner per type; there is none for a
`SplitFilter`, so it threw — meaning any JSON or XML shape would learn and bind on its first stream and
blow up on its *second*, which is when a bound rule is served. No scenario caught it because the
serve-again scenarios are all CSV, where there is no boundary and no filter. The runner now passes over
an element that shapes the stream without changing what any record says, and a JSON shape serving its
second stream is a scenario — one that throws against the code as this slice first wrote it.

The depth was also never persisted: the rules' columns are the name and nothing else, so every rule read
back from the durable store reported no depth. And the fallback was wrong in its own terms — a boundary
with no depth was written with a filter at the usual depth of one, which for JSON splits at the single
top-level map and for nested XML at its one wrapper: one document for the whole stream, bounding
nothing. No depth now means no filter, exactly as it was before item 25, until the rule is learned again.

The thirty-first slice, 2026-09-22, is the half item 25 owed: **the chain is run the way the fragment
will run it**. From the first element that is not a parser, the chain is run one record at a time — in
the promotion gate and in the bound fragment alike — because that is how the pipeline will run it.

The cutting is stroom's own `SplitFilter`, driven rather than copied, so that the documents the transform
is given here are the documents it will be given there. What it writes for each record is joined back
into one document for the scorers and the goldens, which is not something a pipeline does — there each
record's output goes downstream on its own and the writer segments them — but the events are the same
events in the same order, and one document is what the scorers read.

**The point of it, in one scenario.** A stylesheet that reaches into the document's envelope for a value
— the source name at the top of a JSON document, say — is *perfect* over the whole document: every event
names its user, every check passes, and nothing in the conversation objects, because the conversation puts its
questions over the whole document too. The filter replicates the structure above a record but not the
envelope's other contents, so run as the pipeline will run it, every event names nobody. Scored the old
way that candidate is `Promoted`; scored this way it is refused at the floor. The scenario asserts the
refusal, and promotes the same candidate when the per-record run is taken out — which is the whole
argument for the slice.

The fixtures did not change, which is the other half of the evidence: a correct stylesheet writes the
same events either way. What did change is that four scenarios compare the events rather than the
spacing between them, since joining the pieces re-serialises them.

The thirty-second slice, 2026-09-22, finishes item 25: **the transform is shown one record**, because
one record is what it will be given. The conversation puts the question over a single record's document, cut
exactly as the fragment's filter will cut it, runs every candidate over every record, and says so in
words: *the input below is one record; this configuration is run once for each record of the stream;
produce the one event for the record you are given, and do not look outside it — there is nothing
outside it to look at.*

Two scenarios had to be rewritten, and both because they had been stating something that is no longer
true.

**Run 7's trap cannot be written any more.** A transform that emitted one event from the array's first
item was promoted at a yield of one per record when the document counted as one record; slice 19 made
the count the array's items, so it read as one in twelve and was re-asked. Shown one record and run over
every record, "the first item" *is* the record it was given, and the same stylesheet writes twelve
events. A whole class of degeneracy stops being expressible when the transform cannot see past its own
record. The scenario now says that, and a second one keeps what the yield scorer is still for: a
transform can still write nothing at all for the record it was given, and is still re-asked for it.

**And the envelope-reading stylesheet is caught earlier.** The scenario of slice 31 had it refused at the
promotion gate, because the conversation asked its questions over the whole document and only the gate ran
per record. Now the conversation runs per record too, so the candidate is refused at the step that wrote it,
with the shortfall in front of the model that can fix it. That is the better place: feedback about a
record is feedback the next candidate can act on.

225 tests in the module, 24 against MySQL, 4 in Tier 2. **Item 25 is complete**, and what it changed
about the prompt is the one thing here a scripted scenario cannot judge: whether a model shown one
record writes better stylesheets than one shown the whole document. That is a live run, and it is next.

The audit of the three slices together (the owner's code review) found eight, all fixed, and the first
would have undone the item for the streams most likely to meet it.

**A stream carrying one record was run as though it carried none.** The fallback asked for two records
before it would run per record, so a JSON stream whose array holds a single item was learned from the
whole of its envelope — and then written with a `SplitFilter`, because a single record is enough to
settle a depth. The model would be shown the envelope, reach into it for the source name, pass every
check in the conversation and at the gate, and name nobody in production: exactly the failure the slice's own
scenario exists to catch, walking past it. "One record" and "no records" are now different answers:
`RecordSplit` reports what it actually found at the depth, and only a document with nothing there is run
whole. A document with an empty envelope is not a record, and a stream with one record is run as the one
record it is.

**A chain with two elements after the split was run twice at the parser's depth.** A repeated element is
a legal chain, and the second transform was being handed the *joined* document and re-split where the
parser's records had been — inside an `<Event>` rather than on a record boundary. A fragment carries one
`SplitFilter`, not one per element: what follows the first per-record element is given what that element
wrote for each record, which is one record deep (`PerRecord.WRITTEN`). Fixed in all three places that run
a chain — the conversation, the gate and the bound fragment — which is the point of them agreeing.

**Ten thousand records raising the same error said it ten thousand times.** Diagnostics were concatenated
across records and rendered whole into the next prompt. They are now told once each and capped at twenty:
a step that has gone wrong in twenty different ways has gone wrong, and a re-ask that repeats one sentence
ten thousand times says no more than one copy of it and costs a budget (A44) to send.

**Running per record compiled per record.** This was the thing owed after slice 31 and it is now paid on
the learning path: `StepRunner.prepare` is the seam for a configuration compiled once and run many times,
`XsltStep` overrides it to build its `Templates` once and a `Transformer` per record, and `PerRecord`
prepares once per candidate. A stylesheet that will not compile now fails once rather than once per
record. The default still compiles per input, which is right for an element that compiles nothing. What
remains owed is the *bound* path going through stroom's own `XsltPool`, as the schema scorer already goes
through `SchemaPool`.

**The one record shown is one record's worth of evidence, and the question now says so.** Under the
escalating plan the transform is asked before there are any targets, so a stream mixing logins and
logouts would show the model a login, tell it nothing of the rest, and spend both candidates on a
question it could not have answered. The question now carries how many records the stream holds and how
many shapes they are of — *"handle every shape it may be given, not only this one"*. Showing one record
*per kind* would be better still, and is not done here: what is fixed is that the variety is no longer
hidden.

Three smaller ones with it: the input was split twice per candidate, once to show a record and once
inside every attempt, so the cut list is now made once and passed in; the run-only branch ran over the
whole document while the gate ran it per record — unreachable today, since the only run-only runner is a
parser, and no longer waiting for the first one that is not; and the "this is one record" wording sat
behind the branch that describes the boundary, which is a fact about the split rather than about the
input in front of the model, so it is now said whether or not there is a boundary to describe.

234 tests in the module after the audit, 24 against MySQL, 4 in Tier 2. Each fix that could be held by a
test is: a one-record document split as one record, a two-transform chain run at the right depth for each,
a shortfall every record shares told once, and a configuration prepared once and run once per record —
the last against a counting runner, since compiling once and compiling ten thousand times produce the same
answer and differ only in what they cost.

The thirty-third slice, 2026-09-22, is A47, which the live run asked for: **one record of each kind the
split found** — but only where the plan has not settled its targets. Under target-first every kind is
already in front of the model twice, as a record and as the event it must become, so repeating them in
the question is a budget (A44) spent saying the same thing. Under the escalating plan the transform is
asked before any target exists, and the run of 2026-09-22 showed what that costs: a stream of logins and
logouts put one login in front of the model and a sentence saying seven records of another shape
followed. Saying a second kind exists is not showing it.

The question now carries them — `Configuration.otherKinds`, one representative per kind by the same
discrimination the split uses, capped at three — and the text says what they are for: *"The stream
carries one other kind of record, and this configuration is run over those too. One of each."* The
counts stay, since how many records follow is worth saying whether or not their shapes are shown.

Two scenarios hold it, one either way: the escalating plan over the JSON document is shown a login and a
logout, and target-first over the same stream is shown the login alone with two targets beside it. The
first fails against the code as it was.

The audit of the slice found five, all fixed, and the first was the slice defeating its own purpose.
**The question promised one of each kind and showed at most three.** The representatives are capped at
three and the first of them is the record already shown, so at most two others reach the question —
while the count beside them is uncapped. A feed of five shapes would have read *"the stream holds 40
records of 5 different shapes"* and, a line later, *"one of each"*, which tells a model it has seen every
shape when it has seen three: a configuration that drops two shapes, written with an assurance attached,
which is worse than the silence A47 replaced. The question now says *"one record of 2 of the 4 other
shapes; there are 2 more this question does not show"* whenever the cap bites, and the record's own
contract says the list is not exhaustive.

**And the block was in the wrong place.** Appended to the input, it landed between the record and the
sentence that describes it — *"The input below is **one record** … do not look outside it, there is
nothing outside it to look at"* — so "below" had no referent and the instruction contradicted the list
above it. It now comes after that sentence and after the counts, and says what those records are: *"It
will be given the stream's other shapes in the same way — one record at a time, each on its own run."*
They are examples of other runs, not context to read across, which is the whole point of the one-record
framing.

Three smaller ones: the examples now share one record's worth of the question's budget between them
rather than taking a full one each, since this block is resent with every re-ask and a feed of
kilobyte records would otherwise treble the question (A44); the counts are taken over the records the
element will actually be given rather than the stream's own, which for an element after the first are
what the one before it wrote; and a block javadoc that had come loose from `shown()` was put back.

242 tests in the module, 24 against MySQL, 4 in Tier 2.

The thirty-fourth slice, 2026-09-23, is the first of the two changes to `stroom-pipeline` that phase D
rests on (§12 item 1): **an element runs the configuration it was handed, without that being a stepping
session and without the configuration having been written anywhere**.

Stepping could always do this — a person edits a stylesheet in the stepper and the pipeline runs with
their edit — but the only way to say so was to *be* a stepping session, because `PipelineFactory` read
the code out of the stepping request while setting a property. Anything else had to fake a session or
write the document first, and writing before scoring is what design §7.3 forbids: the supervisor must
judge a candidate before anything is promoted.

So the code travels in its own carrier, `InjectedCode` — a pipeline-scoped map of element id to
configuration text, set by whoever is building the pipeline. Two details are what make it useful rather
than a rename:

- **It is applied to the element, not to a property.** The old path only reached an element while
  setting a `DocRef` property, so an element that references nothing was never offered the code at all.
  A candidate references nothing. The factory now applies the carrier as it builds each element.
- **An element with no document runs the code as the whole of its configuration.** `XsltFilter` and the
  three text-converter parsers built a transient document from injected code only where they had
  already found one to copy; where they find none they now build one from the code rather than failing
  with "no data splitter is configured". It is never pooled: it is nobody else's stylesheet.

The stepper says what it wants the same way anything else does — `StreamCaptureDriver` and
`ReprocessDriver` set the carrier from the step request — and `PipelineFactory.setProperty` is back to
setting a property, with the `SteppingController` parameter gone from it.

Two tests in `stroom-app`, one either way: a pipeline whose `XSLTFilter` references no XSLT at all
transforms its input when the carrier holds a stylesheet, and passes the input through untouched when it
does not. The second is what makes the first mean anything.

This is a change to `stroom-pipeline` proper and should be proposed on its own merits, as §12 says of
both items: the stepper is its other beneficiary, since a configuration override that does not imply a
stepping session is what lets the stepper's own machinery be used outside it. Item 2, the headless
capture-and-score harness, is the other half and is next.

The audit of the slice, before that: the question it had to answer is whether stepping still honours a
person's edit, since the old path was removed rather than added to, and nothing about it would throw if
it had been missed — the edit would simply stop arriving. It does, and an existing test proves it:
`TestFilteredStepAfterEdit` steps a stream with `code` set for an element and asserts on what the
element then produced, and it passes with the code arriving through the carrier instead of the request.
Ten stepping test classes run green beside it.

Two findings, both fixed. `setProperty`'s `id` parameter was read only by the injection it no longer
does, so it was a dead parameter on a public static method: removed, with its two call sites. And the
`CombinedParser` reaches injected code only where its `type` property is set — the legacy configuration
names the kind of converter by the document it references, and with no document there is nothing to name
it by, so it parses as XML exactly as it did before. That is a real limit and is now said in the code
rather than left to be discovered.

The thirty-fifth slice, 2026-09-23, is the capture half of §12 item 2: **running a pipeline and reading
what each element made of it, without being a stepping session**.

Stepping already does this better than anything we would write: `PipelineFactory` wraps every steppable
element with input and output recorders, a record detector says where one record ends, and a controller
takes each element's IO at that boundary. The whole of it was reachable only by being a stepping
session, because the factory took a `SteppingController` — a class that persists to the step data store,
answers a person's step, and reads the step request for what to filter.

So the seam is `PipelineCapture`: register a monitor, drive a record detector, be told when a record has
finished, and say what filters an element's output. `SteppingController` implements it and is unchanged
in what it does. `HeadlessCapture` is the other implementation — it keeps what it captured in memory,
one entry per record, each element's input and output as text, rendered exactly as the stepper renders
them for a person, so that what a supervisor judges is what a person would have read. It never
terminates a parse and involves no session, no request and no store.

The factory stops depending on the stepping request altogether: where it used to ask
`controller.getRequest().getStepFilterSettings(id)` it now asks the capture, which answers null when
nobody is stepping. That is the coupling item 2 named.

One limit, stated where it will be met: an element whose recorder needs the source highlight to say what
it read — the readers above the parser — gives back nothing unless something is tracking the record's
position. Parsers, filters and writers capture regardless, and they are what a configuration is judged
on.

The test is the two items together, which is also the shape the supervisor will take: a candidate
stylesheet that exists in no store is injected into an `XSLTFilter` that references nothing, a
`SplitFilter` makes each `<record>` a record, and the capture hands back two records — `<out>alpha</out>`
and `<out>beta</out>` — with each element's input beside its output. 1,155 tests in `stroom-pipeline`,
135 in `stroom-app`'s pipeline and shapeshifter packages, including ten stepping classes, all green.

The audit of the slice found six, all fixed, and four of them were the same mistake in different
clothes: a capture that is not a stepping session still has to be as careful as one.

**What an element said was reported against every record after it.** Indicators accumulate on the error
receiver for the life of the receiver, and stepping clears them at each record boundary; this did not,
so one record's fatal error would have been attached to every later record's capture — and then dropped
anyway, since what was handed back held only input and output. Both halves fixed: indicators are cleared
per record and carried on the captured IO, where a scorer can read them. The test says a complaint about
the second of three records belongs to the second alone, and it fails against the code as it was.

**A record that produced nothing lost its place.** The convenience accessors filtered out the records an
element wrote nothing for, so a list of outputs could be shorter than the list of records and every
position after the gap was wrong. A scorer pairing an input with an output has to be able to trust the
position. Every record now carries an entry for every element watched, and the accessors return one
entry per record with null where there was nothing. In the test's chain every element does capture
something for every record, so this is a guarantee by construction rather than one the test demonstrates.

**Records of a segmented source shared their numbers.** The detector restarts its record index at zero
for each part, so a capture over a multi-part stream held several records numbered 0. A record now
carries its own sequence across the whole capture as well as the detector's index.

**A null highlight would have turned a recorder's failure into a crash.** Where nothing tracks the
source position — the usual case here — the capture passed null, and the path that logs a recorder's
failure dereferences it. Stepping substitutes a default range for exactly this reason, and so does this
now.

Two beyond the capture itself: it held every record with no cap, which for a class whose doc promises to
run the whole stream is an out-of-memory waiting for a real feed — there is now a cap, the parse still
runs to the end, and `isTruncated` says when more went past than was kept. And the injected-code path
of item 1 treated "no document found" as "references nothing", which is also true of a name pattern that
has stopped resolving: a person whose converter reference broke would have had the editor's pane
silently used instead. It is now the code only where the element names no document at all.

What remains of item 2 is the scoring half, which is the stage's work rather than the pipeline's: the
module's `StepRunner` and `FragmentRunner` become callers of this, and the conversation's questions are
answered from captured output instead of from a headless stand-in.

The thirty-sixth slice, 2026-09-23, is the scoring half of §12 item 2: **a node judges and serves a
fragment by running it as a pipeline**.

`FragmentRunner` is now a seam with two implementations. `PipelineFragmentRunner` builds the fragment
with the real factory, hands it a `HeadlessCapture`, processes the stream and reads each element's output
back — the real elements, the real pools, the real filters, and whatever the fragment inherits from its
parent. `StandInFragmentRunner` is the old one, walking the chain with the module's step runners, which
needs no node and is what the Tier 1 scenarios run on. The node's `StageFactory` builds the first; the
scenarios' fixture builds the second.

Each element's *input* is taken as what the one before it wrote rather than from the capture, because
the recorders above the parser report what they read by source span and a headless run has no span to
give them. The first element's input is the stream itself, which is what input coverage needs (A11).
What comes from the capture is what each element *wrote*, which is the thing a stand-in can be wrong
about.

**The test is the point of the slice.** A stand-in that has drifted from the thing it stands in for is
worse than no stand-in: every scenario would still pass while the pipeline refused what they promoted.
So a fragment written the way a promotion writes it — `JSONParser`, a `SplitFilter` at the settled
depth, an `XSLTFilter` — is run both ways over the same JSON document and the events compared. They
agree, first time, which is also the first independent check of item 25's per-record equivalence: the
`SplitFilter` the fragment carries and the `RecordSplit` the module drives cut the same records, and the
stylesheet writes the same twelve events either way.

That also settles what was owed on the bound path: a rule served through the pipeline compiles its
stylesheet through stroom's `XsltPool`, because it is `XsltFilter` doing it. There is no separate
pooling to build.

The audit of the slice found seven, all fixed, and one of them was the reason the agreement test existed.

**The capture was splitting streams the pipeline does not split.** When a build is given a capture, the
factory inserts a `SplitFilter` after the parser so that a person can step record by record — at the
pipeline's own split depth where it has one, and at depth 1 where it has none. A fragment for raw text
has none: the Data Splitter cuts the records and the transform is given the parsed document whole. So
the judging run would have handed the transform one record at a time where the serving run hands it the
whole stream, and judged the candidate on a difference it will never see — the same class of mistake as
item 25, the other way round. A capture now says what it wants: stepping keeps its record-by-record
split, and a capture that is judging takes the pipeline's own shape, splitting only where the pipeline
does. The scenario is a CSV fragment whose stylesheet counts what it is given: both runners answer six,
and against the code as it was the pipeline answered one, six times.

**A candidate that would not compile killed the attempt.** Nothing installed an error receiver, so the
first thing a failing element said was a `NullPointerException` from the proxy — and, since indicators
are only collected when the ambient receiver records them, nothing that did survive would have reached
the model. The run now installs a receiver of its own and puts the previous one back, and a stylesheet
that will not compile comes back as a step that produced nothing and said why. Two more with it: what an
element logs outside a record boundary — a refusal before any record, a complaint on the way out — is
read from the receiver at the end rather than lost, and a pipeline that stops carries the reason on the
step that stopped it, since the element that fails is often not the one that produced nothing.

Three smaller ones: an element the fragment links to but does not define was skipped silently, leaving
the next step scored against its grandparent's output, and now throws as the stand-in does; the capture
was uncapped, where a judgement is made on a sample; and the stand-in's three stores stayed injected
into `StageFactory` with nothing left to read them.

What remains of item 2 is the conversation's own runs: a candidate is still tried with the module's step
runners while it is being written, and only the written fragment goes through the pipeline. The seam is
the same shape, and the agreement test is what makes moving it safe.

The thirty-seventh slice, 2026-09-23, is §12 item 26: **a stream of markup fragments is a stream of
records**. One `<Event>…</Event>` per line with no root is not a document, so until now the stage read
it as text, had nothing to answer the split question over, and abandoned the attempt.

`XmlFragmentStep` is Stroom's `XMLFragmentParser` as a step: it wraps the fragments in a root and reads
the result, so the records are the root's children and everything downstream sees ordinary XML. Three
things had to move with it:

- **A configuration that is not the model's to write.** The wrapper is the element's own business — the
  pipeline's parser takes a `TextConverter` whose document is a root with `&fragment;` where the stream
  goes — so `StepRunner.fixedConfiguration` says what an element always runs with. No candidate is spent
  asking for it, and the fragment is still written with the document, which is what a pipeline needs to
  run the element.
- **Which split question to ask.** It read as "a parser with a configuration to write means text, a
  parser without means JSON", which made a fragment parser look like JSON. Each parser now declares what
  it *consumes* — text for the Data Splitter, JSON for the JSON parser, markup for this one — and the
  chain's first element says what the stream is.
- **What the split is settled over.** For markup the question was put over the stream itself; where a
  parser stands in front it is now put over what that parser wrote, because a run of fragments is not a
  document until it has been wrapped.

The fixture is the Windows security export with its root taken off, one event per line — which is also
what makes the sample cut safe, since design 01 §4 already says rootless markup is held out and cut by
lines. It learns the same stylesheet and produces the same events as scenario 45: a stream is the
records it carries, whether or not anything wrapped them. Two scenarios say so — the events, and that
nothing was asked about the wrapper while the rule still carries `element Event`.

244 tests in the module. One thing the slice found: the wrapper's DOCTYPE declares the entity in an
internal subset, which holds angle brackets of its own, so stripping it with a non-greedy match to the
first `>` left `]>` in front of the root — "content is not allowed in prolog", from a step whose whole
job is to make a document. The declaration ends at `]>`.

The thirty-eighth slice, 2026-09-23, is the first of design 03 §7's ten and the rest of §12 item 7:
**reprocessing as it was processed**.

Half of item 7 was built in slice 9: every output carries the bindings that produced it — the document,
the rule, the fragment, whether the binding was provisional and what it scored — on the output stream's
attributes and in the `Outputs` seam. What was missing is the half that reads them. Design 01 §7.3 has
two modes and the difference matters: **as-current** resolves the selector against today's routing
table, which is what "we have fixed it, run the backlog again" wants and the mode a release (A12)
reprocesses in; **as-processed** runs each stream through the fragment that produced its output, which
is what an audit wants and what rule 1 makes possible by never letting a document that has run be
edited.

`Outputs.asProcessed(inputId, pipeline)` is the lookup — the last thing recorded for that input on that
pipeline — with the DAO taking the newest row and the in-memory implementation the last. `Stage.reprocess`
is the run: no routing table, no learning, no scoring, no rolling score, no relearn. A rule that has
since been rebound, retracted or given up makes no difference, which is the point. Where nothing is
recorded it sentinels and says so rather than quietly serving as-current, because an input nothing
remembers cannot be served as it was and answering a different question silently is worse than refusing.

The mode is a property on the supervisor element rather than something the reprocess request carries,
and that is worth stating plainly: a reprocess in Stroom names a pipeline, not a mode. An operator who
wants history re-run as it happened points a pipeline whose supervisor has `asProcessed` set at the
streams in question. If the request ever learns to carry a mode, the element reads it there instead and
nothing else changes.

Two scenarios, and the first is only a test because a rule is rebound in the middle of it: a stream is
learned and served by one fragment, the rule is rebound to another that would produce different events,
and the same stream reprocessed as-processed comes back through the first fragment with the output it
had. Against a `reprocess` that delegates to `run`, both fail.

The audit of the slice found nine, and four of them were the same oversight from different angles: **an
output is not described by its fragment alone.**

**The boundary had to be recorded with it.** A rebind keeps the rule's uuid and replaces its boundary,
and the boundary is what says where the chain is cut and so what the transform is given — the same
fragment under another boundary is another chain. As-processed was taking the fragment from the row and
the boundary from today's rule, which is a run that never happened; where the rule had been retracted it
took no boundary at all. `Bindings` now carries the boundary, the row remembers it, and the stream's
attributes carry it for a person to read.

**The pipeline had to be part of what a row is.** The unique key was `(rule, input)`, so one document
used by two pipelines meant the second run overwrote the first's row and made the first pipeline's
streams unreprocessable — they would have refused with "nothing is recorded". The key now carries the
pipeline, an output of no pipeline is the empty string rather than NULL so that a unique key can hold
it, and a retraction asks for both outputs because both are still there.

**And the id does not say which row was written last.** A stream served again under the same rule
updates its row in place and keeps the id it was inserted with, so "the newest row" ordered by id was
the order rows were *first* written. There is a produced time now, updated on every emit, and the
lookup orders by it.

The fourth was the ledger. A refusal that no promotion can answer — an as-processed reprocess of an
input nothing remembers — was going onto the ledger, which is the list a shape's settling releases. It
would have gone on, never come off, and been asked for again at every future release of that shape,
writing itself a fresh row each time. It is refused without a ledger row now, and the scenario says the
ledger stays empty.

Three smaller ones with them: a row whose score was never written unboxed to a `NullPointerException`
where it should have read as nothing; `Bound` was constructed with a null rule through a needlessly
clever expression; and the javadoc that belonged to `run` had been left attached to `reprocess`.

One finding is recorded rather than fixed: on a node the stream is transformed twice, once by the stage
and once by the element, and for an as-processed reprocess nothing is scored so the first run is pure
cost. The bound path has the same shape and there the first run produces the scorecard, so the fix
belongs to both together rather than to this slice. 247 tests in the module, 24 against MySQL.

Proving the boundary fix took three attempts, which is worth recording because the first two *passed*.
A CSV feed has no boundary at all, so rebinding one changes nothing; a JSON feed has one, but the
stylesheet selects globally, so cutting the stream differently produces the same events. Neither test
was testing anything. What discriminates is watching the seam: a `FragmentRunner` that records the
boundary it was asked to run under, injected through a new fixture hook, and an assertion that the
depth it was given is the depth the row remembers.

The thirty-ninth slice, 2026-09-23, is §12 item 18, the second of design 03 §7's ten: **the replay unit
is derived and the stage's position is checked**.

The unit came off the document in 2026-09-17 (A1 revised) and nothing has derived it since. It is
decided by the chain and not by anyone's preference: a parser in it means the stream, because the bytes
above a parser cannot be replayed from a record; no parser means one record, which is what the elements
below a parser are given anyway. `ReplayUnit` is the enum, `ReplayUnits.ofElements` the derivation, and
it is asked in the three places that have to agree — of a written fragment when a rule binds one, of a
document's allowed elements, which say what its stage may learn, and of a stage's position in a
pipeline, which says what it may host.

Two checks follow, and both fail early rather than late:

- **A rule's fragment must be replayable over what its stage is given.** `FragmentCheck` already
  refused a pipeline that is not a fragment; it now returns the unit it derived, and the resource holds
  that against what the document's allowed elements imply. A fragment that parses cannot be bound to a
  stage fed by a parser — *"there is nothing left to parse"* — and one that does not cannot be bound to
  a stage fed by the source — *"something must parse it"*.
- **A document's allowed elements must match where its supervisor stands.** The element reads the
  pipeline it is running in as the pipeline is built, walks up from itself to see whether anything above
  it parses, and refuses a document that could only learn chains of the wrong kind. Before a model is
  asked, not after: a document that cannot learn anything usable here should say so before it has spent
  a call finding out.

Where the pipeline cannot be read the check is skipped rather than failed — a check that cannot be made
is not a check that failed — and a document with no allowed elements is left alone, since it constrains
nothing.

The audit of the slice found six, five fixed and one that is not a defect but a collision.

**The element types that parse were a hardcoded list, in two places.** I wrote one because "the registry
is a node's and this is a resource", which was simply wrong: `FragmentCheckImpl`, injected into that very
resource, injects `ElementRegistryFactory`. Both places now ask the registry, as the fragment check
does, so the three derivations the class exists to keep in agreement cannot disagree. The list would
have missed `CombinedParser` and the supervisor element itself, both of which declare the parser role.

**"Cannot be read" was being answered as "fed by the source".** The javadoc said a check that cannot be
made is not a check that failed, and then returned false, which is not nothing — it is `STREAM`, and a
transform-only document would have been refused for a fabricated reason. The walk now returns an
`Optional` and the comparison is skipped when it is empty, as it is skipped when a document names no
allowed elements at all.

**And the message named the wrong thing.** `mismatch` switched on the stage and ignored the unit it was
given, so the element's refusal read "element shapeshifterAi has no parser" when what has no parser is
the document's allowed-element list. It switches on the subject now and the element names the list.

**The collision, which is the owner's to rule on.** A supervisor element declares the parser role, and
the pipeline editor refuses a parser under a parser (`StructureValidationUtil`). So a supervisor cannot
be placed below a parser — or below another supervisor — in a pipeline built through the UI, which means
the `RECORD` position of A1 is unreachable there and the extract-then-transform pair of §3 (`S1 → S2`)
cannot be drawn. The check is still worth having: its `STREAM` branch catches a real operator error, a
transform-only document on a stage that is handed raw bytes. But the other half describes a position the
product cannot currently express, and making it expressible means a second element type for the
transformation stage — a filter rather than a parser — which is §12 item 4's ground and not this slice's.

254 tests in the module, including the pipeline walk: a supervisor under a parser, one under the source,
an element that is not in the pipeline at all, and a pipeline that loops.

The fortieth slice, 2026-09-23, is the third of design 03 §7's ten and the last of §12 item 2: **a node
judges a candidate with the element that will run it**.

The bound path moved onto the pipeline in slice 36; a candidate was still being tried with the module's
own step runners while it was being written. Those runners were written to stand in for elements, and
they stand in well — but a stylesheet judged by Saxon configured one way and run by Saxon configured
another is judged on a difference the model cannot see, and the feedback it gets is about a fault the
pipeline does not have.

`PipelineStepRunner` is the element as the pipeline runs it, wrapped in the smallest pipeline that can
be given text: the element behind the source, with a parser between them where the element is a filter,
since a filter is pushed events and what the conversation hands it is the previous element's output as text.
The candidate's configuration is injected rather than written (§12 item 1), the run is captured (§12
item 2), and what the element wrote comes back joined. It is a decorator: the module's runner still says
what the element is called, what document it takes, whether it parses and what it consumes — only what
it *does* is replaced.

The node builds these; Tier 1 keeps the module's, so the scenario suite still runs in seconds. The guard
is a Tier 2 scenario per kind of element, because each stands in for something different — Saxon's
configuration for a stylesheet, the DS3 factory for a splitter, the JSON reader, the fragment wrapper —
and all four agree.

Writing that guard found a real fault in the slice: an element whose configuration is its own business
(§12 item 26) was being given nothing, because the runner only injected what it was passed and the
fragment parser is never passed anything. The stand-in reached for its built-in wrapper and the pipeline
reached for a text converter that was not there, so one produced a document and the other produced
nothing. The runner asks for the fixed configuration where there is no candidate, as the conversation does.

The four Tier 2 scenarios — 18, 19, 20 and 30 — now run their candidates through the real elements
without a line changing in them, which is the other half of the evidence.

The audit of the slice found five, and the first was the very drift the slice exists to prevent —
which my own agreement test had passed, because it compared only what each side *wrote*.

**The wrapper dropped what a parser consumed, and with it the coverage gate.** A `StepResult` carries
record ranges as well as output, the Data Splitter's stand-in fills them from the DS3 locator, and input
coverage (A11) is scored on them; a pipeline reports no such thing. Wrapping the parsers therefore
turned a gate off in silence: a configuration consuming a tenth of its input would no longer have been
penalised or even warned about. The fix is to wrap less. The parsers in the module are not
re-implementations at all — they drive stroom's own DS3 factory, its JSON reader and its XML reader —
so there was nothing to stand in for and something to lose. **The transform is the one place the module
drives Saxon itself**, with its own configuration, no pool and no function library, and it is the one
wrapped now.

**A prepared candidate was being rebuilt for every record.** `PerRecord` asks for a prepared
configuration precisely so that compiling is paid once, which item 25's audit established for the
stand-in; the wrapper inherited the default and rebuilt the pipeline per record. It now builds once,
starts the pipeline, gives it record after record, and closes it — and `StepRunner.Prepared` is
closeable for that reason, since what preparing takes (a built pipeline, a pooled stylesheet) has to be
given back.

**A refusal by the parser in front went unheard.** A transform is given text and the wrapper puts a
parser in front of it; text that is not well formed is refused *there*, and the diagnostics were being
read only for the element under test. The step came back having failed with nothing to say — the silence
`PerRecord.unjoined` was written to stop. Every element of the little chain is heard now.

**And the carrier was cleared rather than restored.** `InjectedCode` is pipeline-scoped and shared with
whatever built the enclosing pipeline: a supervisor judging a candidate inside a stepping session was
wiping the edits the person stepping was running with. What was there is put back, as the error receiver
beside it already was.

The agreement test compares diagnostics as well as output now, and three cases were added for what it
could not see: a candidate that will not compile, input that will not parse, and a prepared candidate
given record after record. 254 tests in the module, 14 in Tier 2.

The forty-first slice, 2026-09-23, is §12 item 21 and the read-back of scenario 38: **a record's own
text, from the stream it came from, by the span the parser recorded**.

Design 01 §10.1 states the problem plainly: a stream may be thirty gigabytes and a fault found at its
millionth event, and relearning against that event needs the event's *input*. The Data Splitter's
locator already reports where each record began and ended — the extraction harness has read it for
coverage since §9.1 — so the spans exist at the moment a stream is served and are thrown away.

They are kept now. The parser step's spans travel with the judgement to `Outputs.emitted`, which keeps
them beside the bindings that produced the output, and `Outputs.span(input, pipeline, record)` gives one
back. `Inputs.textOf(input, span)` reads the record's text out of the stream: the stream is kept where
every stream is kept, and keeping the record as well would be keeping real data twice, which A17 has not
yet been built to make safe.

Two limits, stated where they will be met. A span is a line and a column, because that is what the
locator reports, so reading one still means reading the stream down to that line — cheaper than
parsing it again, and not free; byte offsets would be better and the locator does not give them. And
the spans of one output are kept as one list in one column rather than a row per record, because a
stream of a million records is a million spans and a row apiece would make the history of a stream
larger than the stream's history; the list is capped, and past the cap a record has no span, which reads
as "not recorded" and never as a wrong one.

My own audit of the slice, the owner's code review having been interrupted, found one defect and it was
in the path most likely to meet it. **An as-processed reprocess erased the spans of the run it was
auditing.** A reprocess runs the written fragment and so has no parser to ask; it recorded its output
with no spans, and the row's spans were overwritten with nothing. The stream somebody reprocesses as it
was processed is precisely the stream they are investigating, and the investigation would have destroyed
its own evidence on the way in. Knowing nothing must not erase what was known: both stores now leave the
spans where they are when a run has none to offer, and a scenario and a DAO case say so — the scenario
fails against the code as it was.

258 tests in the module and 24 against MySQL. What scenario 38 still wants beyond the read-back is a
fault to be *reported* against an event — by a scorer, by a reviewer, by a person — which is the
Supervisor's to do (A46, item 29): the reading back is the part phase D owed.
### 6.4 What the one-record run found

Item 25 changed what the model is shown, and no scripted scenario can say whether that makes it write
better stylesheets. The run of 2026-09-22 asked: `claude-sonnet-5`, the four rows that carry a record
boundary — the two JSON shapes, the Windows export and the nested XML — under both plans, against the
runs of 2026-09-21/22 on the same model and rows. The CSV rows have no boundary and nothing about them
changed, so they were not run. Both runs are archived under `build/live-runs/sonnet-5-run9-one-record-*`.

| Row | TARGET_FIRST before | TARGET_FIRST after | ESCALATING before | ESCALATING after |
|---|---|---|---|---|
| 07 nested XML | — | 0.999 · 9 q · 73k | — | **given up** · 14 q · 209k |
| 10 Windows | 0.997 · 13 q · 415k | **1.000** · 13 q · 341k | 1.000 · 2 q · 28k | 0.995 · 11 q · 247k |
| 11 JSON lines | 0.999 · 9 q · 80k | 0.938 · 6 q · 46k | 0.938 · 6 q · 83k | **1.000** · 3 q · 28k |
| 12 JSON document | given up · 10 q · 215k (run 7); 0.999 · 7 q · 60k (run 8) | 0.999 · 6 q · 50k | 1.000 · 6 q · 144k; 1.000 · 4 q · 53k (run 8) | 1.000 · 4 q · 36k |

**The best result is the one the item was built for.** Row 11 under the escalating plan: chain, split,
one configuration question, promoted at 1.000 with every scorer at 1.000 — where the same row shown the
whole stream took two candidates and settled at 0.938, its extraction quality 0.75. Shown one record,
the first stylesheet was right. Row 12 is the same story more cheaply than before in both plans, and
row 10 under target-first scored higher on fewer tokens, because a configuration prompt now carries one
`<Event>` rather than the whole export.

**The cost is a question, and sometimes an answer.** Row 10 under the escalating plan promoted at 1.000
in *two* questions before item 25: chain, then a transform written over the whole document, accepted.
The boundary has to be settled before one record can be shown, so the split question now comes first,
and this run's first two candidates failed — eleven questions and 247k tokens for 0.995. That trade is
the item: the two-question candidate was judged on a document it will never be given, and being right
about the sample is what item 25 exists to stop counting as being right.

**Told not to look outside its record, the model looks anyway.** Row 12's promoted stylesheet selects
`//json:array[@key='events']/json:map` — a path anchored at the document root — after being told *"do
not look outside it — there is nothing outside it to look at."* It works only because the filter
replicates a record's ancestors, so that path finds the one map in the record's own document. What
protects the result is the structure the split preserves, not the sentence.

Two defects, both found by reading the transcripts rather than the table, and both now fixed:

- **A re-ask that said nothing.** Row 07 spent five attempts and gave up; four of them carried
  *"Your previous configuration was…"* and no shortfall at all. The cause is item 25's own: what the
  transform wrote for each record is read back to make one document, and `RecordJoin` returned null for
  anything that would not parse — silently. A stylesheet whose templates match nothing writes the
  input's text and no elements, which Saxon performs without complaint, so the step produced nothing and
  had nothing to say about it. It now says which: nothing written for any record, or written and not
  readable back. Before item 25 that same stylesheet's text output reached the scorers and they reported
  it; the silence was new, and it cost the row.
- **A prompt that stated something untrue.** The transformation rules say the stylesheet reads
  `records:2`, which is right whenever a parser precedes it and wrong for a stream that is already XML,
  where the transform is handed the feed's own markup. The model believed it, set
  `xpath-default-namespace="records:2"`, and matched nothing — seven attempts across two rows. The
  configuration question now says, next to the input and only where it is true, that this is the
  stream's own markup and not `records:2`. The rules template is unchanged, since it describes the usual
  case and is the owner's to edit (A33).

Neither defect is visible in a table of scores: row 07's target-first run shows 0.999 because the model
corrected the namespace by itself on its second try. That is the argument for reading transcripts.

Not measured: one record *per kind* rather than the first (the audit's open point), any model but
`claude-sonnet-5`, and whether row 11's target-first drop from 0.999 to 0.938 is more than the target
stage negotiating two fewer targets in this run. 238 tests in the module after the two fixes.

The forty-second slice, 2026-09-23, clears the two things phase D surfaced and did not schedule: **the
transformation stage gets an element it can stand in, and a stream is transformed once**.

**The collision.** Item 18's audit found that the `RECORD` position of A1 could not be drawn. A
supervisor declares the parser role, the pipeline editor refuses a parser under a parser
(`StructureValidationUtil`), so the extract-then-transform pair of design 01 §3 had nowhere to put its
second stage. The remedy is the one the audit named and §12 item 4 owns: a second element of the same
stage, shaped as a filter. `ShapeshifterAiFilter` declares no role the editor refuses below a parser, is
a target with targets of its own, and does nothing the parser does not — everything but the shape moved
into `Supervision`, which both elements now are.

What it does with the events it is given is serialise them back to text before the stage sees them,
because text is what a stage puts to a model, judges, scores and records (design 01 §4). One document in
is one stream to the stage: where a `SplitFilter` stands above, that is a record at a time; where
nothing splits, it is the whole of what the parser wrote. Scenario 50 runs the position end to end —
`Source -> DSParser -> ShapeshifterAiFilter -> …` — and the stage learns a stylesheet, promotes it,
writes a fragment with no parser in it, and serves the next stream from the rule.

**The double transform.** The stream used to be transformed twice on a node: once by the stage, for the
score it decides on, and once by the supervisor element, for the output. The element does not run
anything now. A fragment run keeps the events its tail emitted — the runner puts the output filter at the
tail itself — and the stage carries them on the `StageRun` of the run it decided on, which is the only
way to know which run is the one being served: a candidate that fails the floor is run and discarded,
and its events must not be the ones played on.

Three things came out of doing it.

**A build that captures drops any element without stepping visibility.** `PipelineFactory` links straight
past such an element to its children, and the output filter has no children, so the tail's events reached
nobody and every Tier 2 scenario wrote an empty stream. A fragment is always run under a capture, because
a run is judged as well as served, so the filter needs that visibility to exist at all.

**A learned stream has no events to serve.** A chain is judged as it is learned — step by step, over the
element runners, before it has been written anywhere — so the fragment a promotion writes has not been
run as a pipeline when the stream that taught it needs serving. `Stage.serve` runs it once for them,
which is the run that stream would have made had a rule already bound it, and not a second one.

**And the fragment would have gone quiet.** A fragment runs under an error receiver of its own so that
the complaints of candidates nobody keeps go to the model rather than to the operator. The element's
second run did not, which is how a served fragment's warnings used to reach the pipeline's error stream
(A20) — and with that run gone they would simply have stopped arriving. The run that is served now
carries its diagnostics with it and the supervision puts them on the error stream, each named by the
element of the fragment that raised it, once per distinct message over the stream. Scenario 50 holds it
with a stylesheet that has something to say about every record, on the stream that learned and on the
stream that was bound, and fails against the code without it.

`TestTheJudgedRunIsTheServedRun` is the discriminating one for the run itself: the events a run kept are,
event for event, the output that run was judged on. The two are compared event by event and not whole,
because a fragment that splits emits one document per record — as any pipeline serving records does —
while what is judged is those documents joined. `TestSupervisorPositions` holds the roles the editor's
rule turns on, stated here rather than called because that rule lives in the GWT client and a server test
cannot reach it.

262 tests in the module, 24 against MySQL, 7 in Tier 2.


The forty-third slice, 2026-09-23, is design 03 §7's fifth and the last thing phase D owes:
**scenario 13's other half — the promotion releases the stream that waited, and it is processed**.

Tier 1 has recorded the reprocess request since the ledger existed, and phase C's harness mocked the
rest. The request is real: `PipelineReprocessing` builds a reprocess filter over the outputs the named
pipeline made from the named inputs, and task creation turns those back into their inputs. What had
never been run was the whole of it against a database and a processor.

`TestScenario13ReleaseInAPipeline` runs it. Learning off, the first stream cannot bind: an error stream
naming the shape, no output, a ledger row. Learning on, the next stream of the shape is learned and
promoted, and the promotion releases the ledger. A second pass over the task queue then finds one task —
for the stream that waited, and for nothing else — which produces its six events with the model never
asked, because the rule that settled the shape is what serves it (A12). Design 01 §5.2's claim, that
nothing is held, is now a thing that happens rather than a thing the design says.

One thing worth recording, because it cost an hour and will cost somebody else one: **the ledger's only
reader is its release, and a release spends it**. The first draft asserted the ledger row before the
promotion, which took it off, so the promotion had nothing to release and the reprocess filter was never
created — silently, since a release over no inputs asks for nothing and reports nothing. That the row is
there is scenario 20's to say; this scenario waits for it to be spent by the thing that is supposed to
spend it.

262 tests in the module, 24 against MySQL, 17 in Tier 2.


The audit of the forty-second and forty-third slices (the owner's code review) found six, five fixed and
one recorded.

**The judgement of a learned chain was taking another stream's events.** The critical one, and the fix
this slice exists for made it possible. The events of a run are taken from the runner, which keeps only
the last, and a helper took them for *every* judgement — including the three that are made by re-running
a chain over the element runners, which run no fragment at all. So a shape learned behind anything that
had already run a fragment took that run's events: a variant tried and rejected earlier in the very same
call, or an earlier record of the same pipeline scope. The stream would have been served the wrong
stream's output, with its own output in the row beside it. A judgement made over the runners now carries
no events by construction, and `TestServedRunEvents` runs one stage over three streams — learned, served,
then a second shape learned behind it — and fails against the code as it was.

**The guard for "produced nothing" could not fire.** A runner sets its event list before it processes, so
the list is never null — empty, where a fragment stopped or emitted nothing. The check the supervision
makes was for null, so a fragment that produced nothing fell through it and fired no events at a
downstream, writing the empty stream the check exists to prevent. A run with no events now says it has
none.

**A fragment's head was asked of the wrong authority.** Whether the first element of a chain parses
decided whether a parser is spliced in front of it, and it was asked of the step runners rather than of
the element registry that `ReplayUnits` and the fragment check ask. Any parser no runner stands in for —
stroom's XML parser, the combined parser, anything a person put there by hand — would have been taken for
a filter and given a parser of its own, producing a pipeline that cannot be linked. It asks the registry
now, as everything else does.

**And two that the pair itself opened.** A pipeline may now hold two supervised stages, and they have one
set of stream attributes and one output table between them. The second stage's bindings were being
dropped — the first had taken the attribute names — and it logged a warning about stream *parts* on every
stream, because the part check was comparing it with the stage above it rather than with itself. Each
stage is remembered by itself now, and the stage nearest the source takes the plain attribute names while
any behind it are named by their element. The output rows were distinct, being keyed by the rule, but the
read-back was not: an as-processed reprocess asked by input and pipeline alone would have answered the
extraction stage with the transformation stage's fragment and run a stylesheet over raw bytes. It asks by
document too — a document belongs to one stage, because what it may learn must match where it stands —
and a DAO case says so.

Finding both of those needed the pair to exist, and proving them needed it to run, so scenario 50 gained
`Source -> ShapeshifterAi -> ShapeshifterAiFilter -> …`: two documents, two stages, one learning the
splitter and one the stylesheet, and both on the stream's attributes at the end. Making it run needed one
thing of the test fixtures — a script may now carry a structure per stage, each saying whether a question
is about its kind of input, since a stage given raw text and a stage given markup answer the split and
target questions in different terms.

**Recorded rather than fixed:** the served output is now buffered as events before any of it is fired.
The element used to stream the nested pipeline's events straight to its targets; one run doing both jobs
means the run must finish before the decision is known, so a stream's whole output is held. The judged
runs were always samples; a served run is a production stream, and this is a new allocation on the
serving path. It is the price of the run that is judged being the run that is served, and the way out is
to know the decision before the run rather than after — which is a ruling about when a stream may be
emitted and then retracted, and the owner's.

263 tests in the module, 24 against MySQL, 18 in Tier 2.


The forty-fourth slice, 2026-09-23, opens phase F with the server half of design 03 §7's sixth, §12 item
19: **stepping is a dry run, and a supervised stage has something to say about a step**.

**The dry run first, because it is a hazard and not a feature.** A person opening the stepper on a feed
nobody has taught yet would, until now, have taught it by looking: a model call spent, a fragment
written, a rule bound and serving live data, none of it asked for. `Stage.dryRun` routes and serves and
does nothing else — no model, no fragment, no rule bound, promoted, retracted or relearned, no rolling
score, no output row, no ledger row, no reprocess request — and a shape no rule binds is *reported*, as
a new `Decision.Would`, rather than learned. The element takes that path when `PipelineContext` says it
is stepping, and logs what it would do at INFO rather than ERROR, because a shape nobody has taught yet
is the ordinary state of the feed someone has opened the stepper on.

It is a separate walk rather than a flag through `run`, and deliberately: every branch of that walk
writes something, and a flag threaded through all of them would be one `if` away from a stepping session
that learned a shape and bound it.

**Then the slot A30 asks for.** `SharedElementData` gains an optional, JSON-typed `details`, an
`ElementStepDetails` with `ShapeshifterAiStepDetails` as its first subtype: the shape and the learning
key's values, the decision and its reason, the rule, the fragment as a `DocRef` so the pane can offer it
as a link, the record boundary, the scorecard's verdicts step by step, and the transcript — as
`SupervisorTurn`, the same type the Supervisor view carries, so that a person reading a turn in the
stepper and a turn in the Supervisor is reading the same thing.

How an element supplies them was the one open question. The capture asks the element itself, through a
`HasStepDetails` interface, rather than being handed something to carry: an element that has details has
them for the record it has just processed, and nothing between it and the capture would know when that
is. The stepper reads its data back from a store rather than from the run, so the details travel with it
— through `CapturedElementData`, its binary framing and the mapper that makes the wire form.

`StepDetails` is the mapping, and it is pure: `StageRun` was written as everything a scenario asserts on
(design 02 §1), and that is the same list a person wants to see. The one thing it adds is whether this
was a step, because a dry run's decision is what the stage *would* have done and the pane has to say so.

**And then the pane itself.** `ShapeshifterAiStepPresenter` stands where a supervisor's code pane would:
the decision in a line, the shape's key values, the rule, the score, the record boundary, the scorers
step by step, the conversation turn by turn, and the fragment as a link that opens it. Where the step was a
dry run it reads *would* — a pane that showed what would happen as what did would be lying about a rule
that does not exist.

Two things it needed of the stepper. `ElementStepDetailsPresenterRegistry` says which elements show a
pane of their own and what shows it, and the feature registers its own two element types as its plugin
loads; the stepper knows nothing about either. And the registry is keyed by *element type* rather than
by the details' type, which is not where this started: the stepper's layout is built once, when the
element is loaded, and the details do not arrive until a step is taken — what the pane is for has to be
known before there is anything to put in it. The element type names moved to `ShapeshifterAiElements` in
shared code so that the client can say which elements those are without a duplicated string.

The pane is read-only. The actions A30 puts beside the evidence — Approve, Reject, pin, retract — belong
to the Routing tab and the Supervisor view and arrive with them (design 03 §7 slices 7 and 8).

**What is left of item 19 is stepping *into* the fragment**, and separating it was worth doing: it is
not a client change. A fragment runs as a nested pipeline whose elements are not in the stepped
pipeline's model at all, so the tree cannot simply show them — the nested pipeline would have to be
built under the stepping controller rather than a headless capture, with its element ids namespaced
against the outer pipeline's and only one record detector driving. It is §5.2's reviewer flow and it is
worth having; it is design 03 §7's slice 6c.

The audit of the slice (the owner's code review) found five, all fixed.

**The pane said "Would served by rule X".** The view prefixed every dry run's line with *Would*, but
only a hypothetical decision reads as one — a step over a shape a rule already binds really does run the
fragment, and its output is in the pane beside the words. The hypothetical belongs to the decision, so
`Decision.Would` says itself now and both the pane and the error stream read it the same way.

**A step the element had nothing to say about kept the step before.** The pane is a presenter that lives
across steps, and it ignored details it did not recognise — including none at all. Step onto a record
the supervisor never ran on and it went on showing the previous record's decision, rule, scores and
transcript as though they were this one's. It clears now, and the stepper clears it where there is no
element data at all. With it, a narrower case of the same thing: the details a step shows are kept per
element and were never cleared, so a record the element began and never finished — one the parser above
it refused — would have shown the record before it. The element forgets at the start of a record.

**A variant it said it would bind showed an empty output pane.** The dry run tries the fragments already
learned for a shape's neighbours, because trying one costs no question, and reports that it would bind
the one that fits. It had run it and had its output — and the supervision returned before firing
anything, because nothing was *bound*. What is served follows the run's events now and not its bindings:
a step may have a real output with no binding behind it, and the one thing the person opened the stepper
to see was that output.

**And a provisional rule about to be retracted said it was being served.** A provisional rule is on
trial (A14): the first stream bringing enough records to judge it either promotes it or retracts it, and
retraction is the one branch of serving where the stream gets no output at all — the rule goes and the
stream is sentinelled. The dry run reported *served by rule X* and showed its output, which is the
opposite of what would happen. It now says which of the two the stream would cause, and shows no output
where there would be none.

271 tests in the module. The pane itself has no test but the GWT compile: a presenter that renders is
not a thing this codebase tests, and what it renders — the mapping from `StageRun` — is tested where it
is made. One fix is not covered either: that a variant's output reaches the panes is a property of
`Supervision`, which needs a node and a stepping session to exercise, and the Tier 1 test can only hold
the stage to reporting the variant and carrying its output.


The forty-fifth slice, 2026-09-23, is design 03 §7's seventh: **Approve and Reject on the Routing tab**
(A25, §12 item 13's remainder).

Review mode has been built in the stage since 2026-09-18 and has had no buttons: `Stage.approve` is the
promotion a draft was waiting for — the rule goes live, or the incumbent is rebound to its fragment and
keeps its history, and the shape's ledger is released as a reprocess request so that nothing which
arrived while it waited is lost — and `Stage.reject` takes the rule with its records and gives the shape
up with the reason. Both are now reachable from the table that holds the draft, which is where a person
meets it.

Two endpoints on the document's resource, running the stage in a pipeline scope as the Supervisor's own
actions do; two buttons on the Routing tab, enabled only where the selected rule is a draft. Approve
asks first, because it puts a learned transform in front of live data and releases every stream that
waited. Reject asks *why*, and refuses a blank: the shape is given up with the reason, and the person
who finds it given up next month has that reason and nothing else to go on.

`RejectAttemptRequest` became `RejectRequest`. The same rejection is offered in two places now — beside
the draft on the Routing tab, and beside the attempt that drafted it in the Supervisor view — and it is
one act either way.

**And the slice found a regression in the one before it**, which is worth recording because of where it
was found. The supervision's `reason()` had a switch of its own with a `default` that threw, and that was
harmless while it was only ever called for a decision that bound nothing. The audit of the previous slice
made it log the decision unconditionally — including the ones that *did* bind — so every successful
stream fataled at the supervisor. Only Tier 2 saw it: the module tier has no element.

There is one description of a decision now, exhaustive over the sealed type with no default, read by the
error stream and the stage pane alike, and a test holds one of every kind of decision to having a line
— with the count checked against the permitted subclasses, so a new outcome cannot be added and
forgotten. A second switch elsewhere was a second place to forget an outcome, and it was.

The audit of this slice (the owner's code review) found four, all fixed, and the first was the same kind
of mistake as the regression above: **a new reader of old state.** Enabling the buttons meant asking
which rule is selected, which the toolbar had never asked before — it had only ever asked *whether*
something was. A selection is a row and a row is a position, and the table is rebuilt from the server's
answer after every action, so a rule that has just gone leaves the selection pointing at a different
rule or past the end of the table altogether: approving a draft in the last position threw inside the
REST callback, and deleting a middle row quietly enabled Approve against the wrong rule. The selection
is cleared before the rebuild now rather than after it, and asking for a rule past the end answers
nothing rather than throwing.

Three smaller ones with it. The resource logged the decision *before* making it, in the one log somebody
reads to find out who decided what — and the stage refuses a draft another operator has already decided,
or one whose incumbent is pinned, so the line could record an approval that never happened. A draft's
fragment can be cleared by hand on the Edit dialog, and the confirmation named it without checking. And
Reject wore the Delete icon, three buttons along from Delete itself: one removes a row, the other gives a
shape up for good, and only the tooltip said which was which.


The forty-sixth slice, 2026-09-23, is design 03 §7's eighth: **the Supervisor's decisions, and the
ledger beside them** (A28's remainder).

The cross-document table of every attempt has existed since 2026-09-22 and its endpoints with it —
approve, reject, amend, relearn, all by attempt id — and none of them could be reached. Three are now
buttons on the attempt list's own toolbar: Approve and Reject where an attempt awaits review (A25), and
*learn this shape again* for one that was given up or bound something nobody is content with. Rejecting
asks why and refuses a blank, and uses the prompt the Routing tab uses, so that a rejection reads the
same wherever a person meets it.

**The ledger needed a way to be read.** It had two methods — put a row on, and take a shape's rows off —
and the second is what a promotion does. `Ledger.waiting` is a read, and it is a method of its own
rather than a mode of `release` for exactly that reason: a view that answered by releasing would put a
backlog through the pipeline because somebody opened a screen. That is not hypothetical. Scenario 13's
first draft asserted the ledger row before the promotion, which spent it, and the promotion then had
nothing to release.

A row per shape: how many streams are waiting, since when, and what the newest of them was told. Grouped
by document *and* shape, because the view is over every document and two documents may have shapes of
one name that settle separately. In the table it is one round trip — a grouping joined back to its own
newest row — so ten thousand waiting shapes are ten thousand rows rather than ten thousand queries, and
a shape with ten thousand waiting streams is one row, which is the whole reason the view is grouped.

Two implementations of the ledger exist, the rows a node keeps and the list the scenarios run on, and a
view written against one and served by the other is a view that lies. A test holds them to the same
answers over the same sequence; the grouping key and the ordering are the two things that would have
drifted in silence.

What the column headings say, because it is the thing most easily misread: **nothing is held**. Every
stream counted on the ledger was processed to an error stream and is where it always was. The view is a
list of what a promotion would release, not a queue of anything being kept.

The audit of the slice (the owner's code review) found four, all fixed, and the first was the day's
fourth instance of one mistake: **a reader meeting state that was not built for it.**

A selection holds the row *object* it was made from, and refreshing a list builds new ones without
touching it. So an attempt just approved still answered *awaiting review* to the guard that exists to
decide whether Reject is offered — and rejecting it would have taken the rule that had just been
promoted out of the routing table and given its shape up. The selection is cleared before anything else
now, which is not tidiness: it is what makes the guard see the truth. The Routing tab never had the hole,
because its own round trip ends in the same `updateButtons` the buttons were set from.

**The ordering the agreement test pins was not an ordering.** `order by newest desc` had no tiebreaker,
so shapes last added to in the same millisecond — routine against a local database — came back in
whatever order the database felt like, while the list in memory sorted stably and fell back to insertion
order. The test that exists to hold the two implementations together was a coin flip on the very thing
it holds. Both break ties by the newest row's id now.

**And the view could not show more than a page.** The grid was handed the whole list with no provider
behind it: past a hundred shapes the pager offers a second page that nothing serves, which flatly
contradicts the ten thousand the query was written for. It is paged properly now — the same `ResultPage`
and `RestDataProvider` as the attempts beside it, limit and offset in the query, a count for the total —
and the documents a person may see go *into* the query rather than filtering its answer, so the total
does not count shapes on documents they cannot see and their pages do not come back short. That
reasoning was already written down for the attempts; it had not been carried across.

The fourth was in the tests themselves, and worth recording because of what it would have said. Both new
tests wrote rows for a second shape and released them on the last line of the test body, so an assertion
that failed partway left them behind — and the next test would then have reported that the table and the
heap had diverged, when what had happened is that the table started with more in it. A false report from
the one test whose job is to detect a real divergence. The cleanup happens before each test now.

**Owed out of the slice**: *answer instead* and *edit and re-run from here*, which A28 puts on a turn
rather than on an attempt — the `amend` endpoint is built and what it needs is an editor in the turns
grid, which is a different shape of work from a button; and *retract* and *widen selector*, which A28
lists and which have no endpoint yet.


The forty-seventh slice, 2026-09-23, is design 03 §7's ninth, ruling A46 and §12 item 29: **a
supervisor's message, and improving a rule that is already good**. Built in two halves, and recorded as
two.

**The message** (committed separately). A hint, a correction, or a fact about the feed the sample does
not show, attached to the **shape** — which is the whole of A46's first decision. What a person knows is
about the feed, not about turn 7 of attempt 412, so nothing has to be timed, nothing is refused for
arriving at the wrong moment, and a hint outlives the attempt that first used it. It goes into the
system text beside the document's own instructions and attributed, because that is what it is: a
standing fact rather than a turn of the conversation. Read as a walk begins, so a deferred attempt's
next pass carries what was said between passes without a select per question.

Its audit found six, and the first was the day's fourth instance of one mistake: guidance never reached
the model in deferred mode, because the new method was carried through the model's advisor and not
through the one that wraps it on every resumed attempt — while the turn went on recording that it had.
Behind it, a re-walk was stamping today's guidance onto turns answered from the record, which is the
opposite of what its own comment promised. The advisor says what an answer was asked with now, because
only it knows whether it put the question or replayed one.

**The improvement.** `Stage.improve` is the door A46 says does not exist: a rule serving at 0.93 is above
every threshold, nothing has flagged it, and there was no way to ask for better. It is not `relearn`,
which marks a shape and waits for the next stream to do the work. An improvement does not wait for
traffic — the records a rule was accepted on are kept per rule (A18) with the score each achieved, so an
attempt has both a sample to learn from and the bar to beat with no stream arriving. A feed that ships
once a day can be improved at eleven in the morning.

It opens from what the incumbent wrote rather than from nothing, the incumbent serves throughout, and a
candidate takes over only through the ordinary gate: clear the floor, and be no worse on **every** record
the rule was accepted on. Every one, not one — with no stream to be better on, those records are the
whole of the evidence. The person's message is recorded as guidance before the attempt opens, so the
same machinery carries it into every question and leaves it standing for the relearning after this one.

**It needed one thing of the rule: its shape.** An improvement begins from a rule, and a rule recorded
no shape. Its selector holds the shape's values, but as a matcher expression with the values escaped, so
reading a shape back out of one is guesswork. Migration 013 puts the shape on the rule — which the view
of serving rules needs on every row in any case — and a rule that has none, because an operator wrote it
by hand or because it predates the column, is refused plainly rather than guessed at.

**The limit, stated rather than leaned on.** The regression set is still in memory and node-local, and it
is exactly what an improvement samples from and is judged against. After a restart a rule has no accepted
records, and `improve` refuses it — which is the right failure rather than improving against nothing, but
it means the door only opens for rules promoted since the node last started. Making the regression set a
stream is design 01 §15.2's, deferred with phase E; it is the one item there the MVP puts on screen.

281 tests in the module, 27 against MySQL, 18 in Tier 2.

**The surfaces.** The third half, and the one that makes the other two reachable: until it existed
neither `Guidance.given` nor `Stage.improve` had a caller outside a test. A46 names the way in — *a
filter over serving rules by rolling score, ordered by the traffic each carries, with improve beside
re-learn on the row; and hint on an attempt* — and the Supervisor gains all of it.

**Why the list is ordered by traffic and only filtered by score.** Every other surface in the feature is
reached because something went wrong: a shape was given up, a draft awaits review, a rolling score fell
through the floor. A rule serving at 0.93 is above every threshold and nothing will ever raise it, so a
person who wants it better has to go and find it — and the rule worth an hour is the one carrying the
most streams. Sorting by score would put the worst rule in the installation at the top whether it
carried one stream a month or a million.

**A seam of its own, because it is none of the three tables and all of them.** `Serving` joins the rules
to the shape state each was learned for and to what has been said about it. Not a method on `Rules`,
which would have made the rule store depend on shape state it knows nothing about; and not a read above
the seams, which would have meant loading every rule of a document to sort it in Java — the one thing a
document with ten thousand shapes cannot afford. The node answers with one statement and the in-memory
sibling does the same work over three maps, held together by an agreement test on the order, the paging
and the totals, as the ledger's two are.

**Migration 014, and why the rule needed a second column for its shape.** 013 put the shape's *id* on the
rule, which is what a person reads and what an improvement claims its attempt against. The view needs to
*join* on it, and `shape_id` is a `longtext` because a learning key may name a sender-supplied header — a
join on which cannot use an index. So the hash goes beside it, written wherever the id is, and the
existing rows are backfilled in the migration itself: MySQL's `SHA2(x, 256)` is the same digest over the
same UTF-8 bytes, down to the lower-case hex, as the hash `shapeshifter_shape` is keyed by.

**What the view leaves out, and why each.** A draft is decided rather than improved (A25); a reserved
rule binds nothing; a rule an operator wrote by hand came from no shape and has nothing to learn again.
A pinned rule *is* shown — it is serving, and hiding it would be a lie about what is running — but the
button says why it cannot be improved rather than being offered and then refusing. A rule promoted a
minute ago is shown with no score at all rather than a zero: the promotion resets the shape, so a
binding nobody has seen work reads as untested rather than as bad, which is also the row most worth a
look.

**One thing is not yet what A46 asks for**, and it is the same debt the stage already carries: an
improvement should be taken by the deferred worker, and it runs while the request is open. The worker
would need the attempt to remember that it is an improvement and to find its sample in the regression set
rather than in the stream store. Nothing waits on it meanwhile — the incumbent serves every stream
throughout, and a candidate takes over only through the ordinary gate — but a person clicking the button
holds a request while a model is asked.

**Two pre-existing defects found by finishing the loop**, both in this feature's own shared types and
neither introduced here: `PlanStep` and `RecordBoundary` validate in their constructors, so
`TestJsonSerialisation` could not build one, and `RecordBoundary.splitDepth()` carried a `@JsonIgnore`
Jackson never looked at. Both now have the `@SerialisationTestConstructor` the test asks for.

**Its audit found six.** The first was the one that mattered: the improve prompt's callback ran on
Cancel as well as on OK, because `Window.prompt` answers `null` when a person changes their mind and
nothing was reading the difference — so changing your mind spent a model run and, in automatic mode,
could rebind the rule. Every sibling handler guarded; this one could not use their guard, because an
*empty* message is legitimate here and means "ask again from what it already does". Null is Cancel;
empty is a question with nothing added.

Behind it: the filter took `NaN` and `Infinity`, which `Double.valueOf` parses without complaint and
the database refuses to bind, so a typing mistake answered with a server error instead of the message
written for it; filtering from page three re-read offset forty of a three-row answer and showed an
empty grid; and a blank-but-not-null shape id was hashed by the rows and skipped by the heap, so the
two implementations would have disagreed about whether to list a row whose improve button the stage
then refuses — the "button that lies" again, latent because nothing writes one.

The sixth was the surfaces' own omission: the list showed how much had been said about a shape and
offered no way to read it or to take any of it back, though `withdraw` had been built and its own
comment said a wrong hint "is carried into every question about the shape until it is withdrawn". A
hint that turned out to be wrong is worse than no hint, and a count is not a way to find one. *Hint*
now opens what is standing — oldest first, with who wrote each and when — and adds or withdraws from
there, so that a person about to say something sees what has already been said.

Also said rather than left bare: an improvement runs while the request is open, so a request that times
out has not necessarily failed. The failure now says to look for the attempt before asking again,
because asking twice spends a second model run.

288 tests in the module, 33 against MySQL, 18 in Tier 2.


The forty-eighth slice, 2026-09-23, is design 03 §7's sixth-c, ruling A30 and §12 item 19: **stepping
into the fragment** — scenario 33.

A supervised stage looks, from outside, like one element with a stream going in and events coming out.
Everything that decided the shape of them — the records the learned parser cut, what the learned
transform made of each — happens inside it and is invisible in the one place a person already goes when
a pipeline puzzles them. The chain now hangs in the stepping tree beneath the stage, each element with
what it was given and what it wrote for the record at the cursor.

**It is not built the way the phase note guessed.** That note said the fragment would have to run under
the stepping controller rather than under its own capture, with its ids namespaced and one record
detector driving. Reading the machinery said otherwise, and decisively: `PipelineCapture` has two
implementations *because* they answer `captureSplitDepth` differently — stepping inserts a split at the
parser's records whatever the pipeline does, and a capture that is judging a configuration must not,
because "a split the pipeline does not have would give the transform one record where the pipeline gives
it the whole stream, and the configuration would then be judged on the difference". The run being
stepped is the run being served (the judged run is the served run, slice 6a). Building the fragment
under the stepping controller would therefore have changed what the stepper shows from what actually
runs — the one thing a stepper may never do.

So the fragment goes on running exactly as it does when serving, and hands what its own capture already
holds — per element, per record, input and output — to the step through the details slot slice 6a built.
Nothing was needed in the store, the fingerprints or the step result: details are already serialised
into the store with the element's own IO and read back under the element's own fingerprint, which is the
right key, because the fragment's work *is* the stage's work. What the pipeline gained is one generic
thing: `ElementStepDetails.getNested()`, so that the stepper can draw the chain without knowing what
kind of element it is looking at, and `HasStepDetails.getStepDetails(recordIndex)`, because an element
that runs a chain decides once and works record by record and nothing else could say which record was
being asked about.

**What the test taught, which no amount of reading would have.** A learned chain for raw CSV carries no
`SplitFilter` — the boundary is cut by the parser's own configuration, so there is nothing for a filter
to split on — which means the fragment's capture holds *one* record for the whole stream while the
stepper walks six. The first version of the test asserted that stepping forward moved the chain's record
on; it does not, and cannot. One run did all of it: the learned parser read the whole stream and the
learned transform was handed the whole of what that produced, and the six records being stepped were cut
from the far end afterwards. A fragment for JSON or XML *does* carry a split (§12 item 25) and does
capture per record, so the behaviour varies by format rather than by position.

The answer is to say so, not to fake it: each row carries `wholeStream`, and the pane logs a note where
it is set. Cutting the element's output up for display would have shown a person something the element
never produced, which is the same mistake as building the fragment under the stepping controller, made
one level further out.

The nested elements are in no pipeline, and everything that edits, filters or saves now asks first: no
code pane, no properties, no document behind them, no step filters, no context menu. Their ids carry the
stage's name, because a fragment may hold an `XSLTFilter` and so may the pipeline it is running inside.
The tree is the stepper's own — `NestedPipelineTreeBuilder` over a copy of the model's child map — so a
pipeline a person saves is still the one they drew.

**Its audit found five**, and the first is the third appearance of one mistake. `Stage.lastFragment()`
*read* what the fragment runner was holding, and a stage decides many things without running a fragment
at all — a reserved rule matches, a draft awaits review, the shape has been given up. A stage pane that
read the last capture would hang the previous stream's `DSParser → XSLTFilter` beneath a stage saying
nothing was bound. This is the same fault as serving a rejected candidate's events (slice 6a) and as a
re-walk stamping today's guidance onto yesterday's turns (slice 9a): **a reader meeting state that was
not built for it**. The capture is now *taken* and not read — the runner is left holding nothing, so the
second ask finds nothing, which is the truth.

Writing the test for it found something worth keeping. The case cannot be reached through a stage
standing where a parser stands: a stage that binds nothing emits nothing, so the pipeline produces no
record, so there is nothing to step and no pane to be wrong. It bites where the stage stands where a
filter stands and the records come from a parser above it. The contract is pinned where it lives, on the
runner, and the test fails without the fix.

The third was the one with teeth. These details are stored against **every record** of a stream, and a
chain handed the stream has one run to show against all of them — so carrying what that run read and
wrote in full writes the whole stream into the step store once per record of the stream. A 50 MB stream
would have thrown on the first record against the store's own 100 MiB per-record cap; a 10 MB one would
have exhausted the 2 GiB per-stream cap in a hundred records. A whole-stream chain's text is now carried
as an excerpt and says it is one; a chain that ran for one record is carried whole, because its text is
one record's and no more of a burden than any other element's. What the excerpt leaves out is not lost:
the fragment is a pipeline document and steps like any other.

Behind them: the tree read this step's answer while every pane read the *effective* one, so stepping off
the end of a stream left the panes showing the last record found and the chain gone from beneath them;
the migration's backfill hashed a blank shape id where `RulesDao` deliberately does not, which would
have listed a rule as serving that `improve` then refuses — the button that lies, again; and
`formatInput` was false for every nested element rather than for the first alone, so an `XSLTFilter`
inside a fragment showed unformatted XML where the same element elsewhere in the stepper is
pretty-printed.

288 tests in the module, 33 against MySQL, 21 in Tier 2 — and the 1,155 of `stroom-pipeline`, which this
slice reached into.


The forty-ninth slice, 2026-09-23, is the rest of design 03 §7's eighth (A28, design 01 §11.6): **what
a person may do to an attempt and to a rule**, which the Supervisor had listed and could not do.

**Answering a turn instead, and editing one and running the attempt again from there**, are one act with
one button, because the difference between them is only whether there is an answer there already: a
person editing turn 7 of a finished attempt is doing exactly what a person answering turn 7 of a parked
one is. `Stage.amend` had been built since slice 8 and had no caller; what was missing was somewhere to
type. An editor and not a prompt box, because an answer is usually a configuration — a Data Splitter
document, a stylesheet — and a single-line box cannot hold one, let alone show a person what they are
editing. Nothing runs when it is written: the attempt is left waiting for the worker to carry on from
what it has now been told, because a person's request must not wait on a model.

**Retracting** is the act the gate already had and a person did not. The automatic retraction of §6 is
what a provisional rule gets when it fails; this is the same act for a reason no gate can see — somebody
has read what the rule is producing and decided it should not be — and what follows is deliberately
identical: the rule goes, the shape is unknown again, and everything the rule produced is asked to be
processed again as it would be now (A12).

It is neither of the two things that look like it, and saying which is most of the work. `remove`, on
the Routing tab, is exactly what it says: a rule taken out of a table, leaving the shape thinking it is
bound and the streams it produced standing as though they were right. `reject` is A25's decision about a
draft that served nothing, and gives the shape up so it is not learned again. **A retracted shape is
learned again** — the rule was wrong, the shape is not — and that is what makes retracting safe to offer
for a rule that is merely wrong rather than a shape that cannot be learned. It refuses a draft (decide
it), a reserved rule (nothing is serving) and a pinned one (§7.3 rule 2), and it answers with how many
streams it asked for again, which is the one consequence the person pressing it cannot see for
themselves.

**Widening a selector turned out to be built.** §7 listed it as owed with no endpoint; widening is
editing a rule's expression, and the Routing tab's Edit has done that since the tab existed. What was
actually owed was the Supervisor's own acts.

**An audit of the whole branch**, 2026-09-24, the first over all fifty commits rather than one slice:
fifteen findings, all fixed. Four of them would have reached a running node.

- **Every text converter was written as a Data Splitter.** `TextConverterDoc` carries its kind and the
  element that reads it refuses the wrong one — `XMLFragmentParser` throws "The assigned text converter
  is not an XML fragment" — so a learned `XMLFragmentParser` chain (scenario 49) passed every scorer and
  would have failed on every stream. Tier 1 could not see it: the stand-in runner reads the text and
  never the kind. The step says which kind it writes now, and the writer refuses a configuration that
  does not say.
- **`prune` deleted by the wrong clock.** Migration 009 added `produce_time_ms` precisely because a
  stream served again updates its row in place; pruning on the *insert* time would have deleted the
  bindings and record spans of anything first processed sixty days ago and reprocessed this morning —
  the very rows migration 010 exists to keep.
- **Amending an attempt left the draft it had written.** The shape was reset, which clears the pointer
  saying what awaits review, and the rule stayed: approve and reject would throw, every stream of the
  shape would be sentinelled by a draft nothing awaits, and the only way out was deleting the rule by
  hand. The draft goes with the answer that produced it, as the later turns already did.
- **A non-modal editor resolved its attempt when OK was pressed.** Clicking another attempt in the list
  behind it made the answer land in that one — reopening it and taking its shape back. The attempt is
  taken when the editor opens. A reader meeting state that was not built for it, in a window.

And a fifth that was the slice above's own: **a supervised stage that binds nothing could not be
stepped at all.** It emits nothing, the stepper stops at records, a record is an `endDocument` reaching
the detector below the element — so an untaught feed produced no records, and the stage pane that says
*would learn* was unreachable in the one case it was written for. One empty document, on a step and only
on a step, because a step writes nothing anywhere and in a task the same document would be an empty
stream. The Tier 2 test for it fails without the fix.

The rest: injected code leaked into the fragment's own pipeline, so stepping a pipeline whose
`XSLTFilter` is called `xsltFilter` — which is what this feature names every transform it writes — ran
the person's edit in place of what was learned; `RecordJoin` handed a single record back without parsing
it, so a one-record stream whose transform wrote bare text reported success with no diagnostic; the
fragment runner asked the step runners whether an element parses where it documents that the element
registry must answer; `RETRACTED` was written through the door A25's decisions use, which filters to
drafts awaiting review, so it never matched anything; `whyNotToCarryOn` was the only mutation path that
did not check for a pin; the import confirmation screen's read of a *pack* left that pack's rules where
the next read of the local document would take them, even after a cancelled import; the ledger and
serving endpoints took any page length a request asked for; appending a rule was not one transaction, so
two nodes promoting at once could take the same position; `XsltFilter` fabricated a document whenever
code was injected, masking a name pattern that had stopped resolving, where the three parsers changed
beside it are careful not to; and one `HeadlessCapture` served every run of a prepared chain, so past ten
thousand records every later run read back as having produced nothing.

294 tests in the module, 33 against MySQL, 22 in Tier 2, and the 1,155 of `stroom-pipeline`. Three of
the fifteen could be held by a test that fails without its fix and passes with it — the converter's
kind, the retraction reaching the attempt that bound the rule, and the draft an amended attempt takes
back — and one more, the untaught feed that could not be stepped, in Tier 2. The rest are guards,
clamps and a column name: what holds them is the reasoning written where they are.

**Noted, not acted on.** `Stage` crossed checkstyle's 2,000-line file length with this slice — a warning
rather than an error, and the only hand-written file in these modules to do so. The operator's decisions
on a rule (`approve`, `reject`, `retract`) are the natural thing to lift out, since they are not stage
*runs* at all: they route nothing, learn nothing and judge nothing. They are not lifted here, because
they read and write almost everything the stage holds, so the split is of one object's state rather than
along a seam — and a refactor of the feature's heart, made casually and immediately before a review, is
worse than a warning.


The fiftieth slice, 2026-09-24, is design 03 §7's tenth and last, ruling A37 and §12 item 24: **the
plan editor** — and, with it, a check over every other thing the UI is meant to let a person see and
change.

**Built a week before its own sequencing advises**, at the owner's direction. Item 24 waits for the
2026-10-01 live run over an escalating graph, "so that the editor is built over a mechanism that has
been seen to work rather than over a grammar that may still move". The risk was taken knowingly and is
bounded: every form is driven by the closed lists — `QuestionKind`, `ConfigureRole`, `StepGuard`,
`Check`, `StepOutcome` — so a value added or renamed by what the run shows flows through without a line
changing here. Only a *structural* change to a step, a new field, would mean rework.

**What the editor is for.** The text grammar is exact and unforgiving, and a person learning it by
having a save rejected is learning it the hard way. Now the order is the list's, every closed list is a
picker, and a transition may only go to a step that is there — so most of what the grammar can get wrong
cannot be written at all. A step is a form; its transitions are a list inside that form, each one two
pickers; the copy of a step drops its id, because two steps of one name leave a transition unable to say
which it means.

**What a form per step cannot catch, and where it is caught.** A plan is a graph, and what is wrong with
one is a property of the whole of it: a CHAIN first, a CONFIGURE last, SPLIT and TARGET at most once, a
`goto` that names a step which is there. `LearningPlan.problems()` already said all of that for the
store, so the tab says it from the same place as the list is edited — the tab and the save cannot
disagree about what is wrong, because there is only one of them.

**The text grammar has not gone**, as item 24 asks: it is what the harness and import/export carry, and
it is shown read-only beneath the list so that a person can read the whole plan at once or paste it
somewhere.

**And the rest of the ask turned out to be built.** Checked against the document rather than assumed:
the question text of every template is already selectable, editable and resettable to the built-in
(A33); the scorers already have their type, weight, threshold, gate and per-type parameters; the model
reference is already a document picker on the Learning tab (A13); and the learning key, instructions,
allowed elements, attempt and token budgets and the sample limit are all there. What was missing
was one thing: **a learned fragment could not be opened.** The Routing tab and the serving list now open
it, and from a pipeline document stroom's own editor reaches the Data Splitter and the stylesheet the
model wrote — which is the only place those are worth reading, since nothing here should reimplement a
pipeline editor.

The GWT compile is the check item 24 names for the `.ui.xml` bindings, and it passes.

**Its own audit found five**, before anybody opened it. The one that mattered: a `PlanStep` is a *value*
and two of them can be equal, so `List.indexOf` answers with the first of a pair whichever was
selected — and **Copy makes exactly that pair in one click**, since the copy drops the id and a step
that never had one is copied into its own twin. Editing, moving or removing the second would have done
it to the first. Rows are found by identity now, which is the right question: the grid's rows are the
very objects in the list.

Behind it: loading an example would have made a read-only document editable, since the handler passed
`false` rather than asking; a transition whose step had been renamed came back blank when it was opened,
and a blank target is *abandon*, so merely looking at a dangling transition would have quietly turned it
into one that gives the attempt up — the missing step is offered anyway now, still wrong and still
flagged; the inline check was asking the whole plan, templates and all, when what can be wrong with one
is a property of its steps alone, which tied it to the order the tab happens to fill its fields in; and
a step was always appended where the routing table beside it inserts after the selection.

The binding question was asked and answered rather than assumed: GWTP's `HandlerContainerImpl` takes its
`automaticBind` through an `@Inject` method, so any presenter widget GIN instantiates is bound and its
`onBind` runs. These are all GIN-injected, so their buttons are live.


The fifty-first slice, 2026-09-24, is **the Supervisor finished against its own ruling**: A28's §11.6
read line by line and what was missing built.

**The screen is reached from the Monitoring menu**, "Shapeshifter AI Attempts", as A28 asks —
"top-level beside Processors and Jobs rather than a tab on one document". It needs `VIEW_DATA`, and
each document's rows are then filtered by the reader's permission on that document, inside the query.

**The six filters.** A28 says "filterable by document, feed, shape, execution mode, promotion mode and
status". The server had done all six since the view was built; the screen passed all nulls and offered
nothing. They are behind one button now, because the form is longer than a toolbar holds, and the
button says when a filter is on — a screen quietly showing a tenth of what a person expects is a screen
they will not trust twice. Filtering goes back to the first page, for the reason the serving list
already did: an offset into a list that has just changed length shows an empty grid.

**Learning a given-up shape from the ledger**, which is the door the ruling leans on: "they raise an
attempt for a given-up shape from the same screen, so no on-request learning mode is needed". Building
it found that `relearn` could not have done it. A shape that was given up stays given up, and every
path reads the give-up *before* it looks at the relearn mark — so marking a given-up shape said nothing
at all, and the Re-learn button on a rejected attempt was a no-op. Sending a shape back now clears the
give-up, because that is what the act means.

And a mark alone would only have said "learn it when the feed next ships". A shape given up has streams
waiting on the ledger *because* nothing bound it, and they are the traffic: they are asked for again,
exactly as a promotion asks for them (A12), so the first of them through learns the shape. Nothing is
held and nothing is lost — each was processed to an error stream and is where it always was.

**Accepting a provisional binding** (§6): a candidate that cleared the floor on too few records to
judge serves, marked, until enough arrive. For a feed that ships a handful a day that is a long wait,
and a person who has read what it is producing may not want to take it. What is skipped is the wait,
not the floor — which it has already cleared — and the attempt records that a person did it, so a rule
promoted this way is not mistaken for one the gate promoted. The row says which state it is in, because
that is what decides what may be done to it.

**Not built, because there is nothing to show yet.** The other half of §11.6's status strip — feeds in
error mode with reason and reset — is A24's, and A24 is deferred with phase E: `shapeshifter_feed_state`
does not exist. A strip over a table that is not there would be a lie. The row also lacks the
*candidates used* and the *cost* the ruling names; tokens are there, and cost is derived nowhere in the
feature.

Two places where what was built differs from the ruling's words, both deliberate: **retract** is on the
serving list rather than the attempt row, because what is retracted is a *rule*; and **widen selector**
is the document's Routing tab, where editing a rule's expression has always lived.

**Its own audit found six**, before anybody opened it, and one of them is not a bug but a limit worth
stating.

**A rule accepted by hand can never be improved.** A provisional binding deliberately records nothing
in the regression set — `bind` records only for a promotion or a draft — and the stream it was bound
from is long gone by the time anybody accepts it, so there is no text to record and no per-record score
to record it with. The consequence is real and two-sided: `improve` refuses such a rule and asks for a
relearning instead, and A18's "no worse on any record it was accepted on" has nothing to hold a later
relearning to. Both are the honest consequence of promoting without evidence and both are now written
where the promoting happens. Giving a provisional binding its record at bind time would fix it, and
that is a change to the gate rather than to this button.

**Learning a shape whose draft is waiting would have spun.** It is not given up, so nothing refused it:
the streams would be released, reprocessed, meet the draft and be sentinelled by it again — the same
rows back on the ledger and nothing learned. A shape awaiting review needs a decision, not another
attempt, and says so now.

Then three of one kind, which is the kind this feature keeps producing: **a selection outliving what it
was made on.** Filtering the attempts left the selection on an attempt the filter had just excluded, so
Approve, Reject and the rest stayed lit for a row no longer on the screen. Learning a shape left the
selection on a ledger row that had just been released. Accepting or retracting a rule left the
selection holding the old row, so Accept stayed lit for a rule that was no longer provisional and
Improve for one no longer in the table — each would have been told no by the server, which is the right
answer arriving in the wrong way. Hinting deliberately keeps its row, since only the count changes.

And one taken back rather than kept: the grids' columns were narrowed and the panel widened because
every one of them totals more width than it is given. The owner's answer is that overflowing grids
scroll, which is true, so the change went back out — a fix for a problem that was not one is churn.

296 tests in the module, 33 against MySQL, 22 in Tier 2.

Checked and cleared, so that the next reader need not: `automaticBind` runs for GIN-instantiated
presenter widgets, so the new lists' buttons are live; `Integer` returns have ample precedent in
stroom's own resources; `MultiSelectionModelImpl.clear()` fires a change, so the parent's buttons
follow a cleared child selection; and the document picker decorates a uuid-only `DocRef`, so a filter
re-opened shows the document's name rather than a blank.


**And the ledger itself, checked against phase F** (the owner's question, 2026-09-24). Phase F turned
the ledger from a record into a screen people act on, and a row's reason is written once — when the
stream is sentinelled — so anything that changes *why* a shape is unsettled without settling it leaves
every row saying what has stopped being true.

Every such path was walked. Giving a shape up, and the gate's own retraction, sentinel the stream that
caused them, so the newest row — which is the one the view shows — is right. Approving, binding, and
learning a shape again all *release*. Retracting by hand has no waiting streams to speak of, and the
outputs it replays are sentinelled afresh when they find nothing bound. Accepting a provisional binding
touches nothing, because the ledger was released when it was bound.

**Rejecting a draft was the one that did not.** The streams that had been sentinelled "awaiting review"
went on saying so — for a draft that no longer exists and a shape that is now given up — on the very
screen a person would use to start it learning again. `Ledger.restate` says something else about a
shape's rows without releasing any of them, and rejecting uses it. The time each was sentinelled is
left alone: a stream has been waiting since it arrived, and a new reason does not make it newly late,
which is what keeps "waiting since" honest.


**The word, again** (the owner's, 2026-09-24). What an attempt does with the model is a
**conversation**, and that is what it is called: `Conversation` in `stroom.shapeshifter.ai.learning`,
`TestConversation`, the variables, the comments, the design, and the heading on the stage pane. It was
a *dialogue* from 2026-09-16 until today, and the two words mean the same thing here — the change is to
say it in the plainer one.

A37's distinction is untouched, because this renames only the second half of it: what the document
holds is still a *learning plan* and what an attempt does with it is the run. The vocabulary beneath is
unchanged and still reads — a conversation of **turns**, each turn one **exchange** with the model.

Nothing changed but names. No column or table was ever called `dialogue` — it appeared in the
migrations only in comments — so there is no schema change and nothing to migrate. `Conversation` was
free: `stroom-ai` has a local variable and a private method by that name and no type. The one thing
that moves in front of a person besides the pane's heading is the element id errors from the run are
attributed to on the error stream, which was `Dialogue` and is now `Conversation`.

**The audit of the rename found one, and it was the one worth finding.** `ShapeshifterAiSerialiser`
holds `LEGACY_PLAN`, the name the plan was saved under before A37 — and that is a name in **stored
data**, which no decision about what to call things today can change: a document written then has
`"dialogue"` in its JSON for ever. The rename took it with everything else, so every pre-A37 document
would have silently lost its plan and fallen back to the default.

Nothing caught it because **the test was renamed in lockstep**: it went on constructing a legacy
document with a `"conversation"` key and migrating it, which passes perfectly and proves nothing, since
no document ever written contains that key. A green test asserting a thing that cannot happen. The key
is back, the test is back, and the test now fails against the broken constant — checked, because a
guard nobody has seen fail is not a guard.

No other serialised identity moved: no `@JsonProperty`, no enum constant, no stream attribute.

**Two mentions were left alone on purpose**, both comments inside migration `V07_13_00_002`. A
migration that has been applied is checksummed by Flyway, and editing it — even a comment — fails
validation against every database that has already run it. A stale word in the history of a schema is
the cheaper of the two.

**History keeps the names of the day**, which the wholesale rewrite broke and which is repaired: the
records of A21 being built as `Dialogue`, of A33's `DialogueDefinition`/`DialogueStep`/`DialogueExample`
being renamed to the plan's classes in slice 12, and of A37 ruling that the run was "still a dialogue",
all say what they said. Each now carries the date it stopped being true.


**One document over every format** (2026-09-24), the scenario the content pack is built from. Scenarios
43 to 50 each proved a format, and each of them quietly narrowed `allowedElements` to the one parser it
needed — so nothing had yet shown the stage working with the whole list in front of it, which is what a
document someone points at an unknown feed actually has. Scenario 51 is one document, unmodified, met by
CSV, JSON lines, syslog and fixed-width in turn: four shapes, four rules, none provisional, one set of
scorers judging all four.

What a scripted model can prove here is everything around the choice of parser, and that is worth
stating plainly, because the scenario's name promises more than a script can give. Proved: the chain
question offers all four elements; the split question is asked in the vocabulary of the parser the
answer named; a `JSONParser` is asked for no configuration where a `DSParser` is; one scorer set reaches
a verdict on four formats; and each shape promotes on its own evidence and writes its format's golden.
Not proved, and not provable without a model: that a model *picks* well from that list. That is phase
B's live run, and this scenario is what makes the run worth doing.


**The demo content pack** (2026-09-24, the owner's request), and what had to come first.

The owner's correction, mid-build, set the order: *the test is supposed to prove the full multi-data
end-to-end process; once that works, the test becomes a content pack.* The pack is not the deliverable a
test produces — it is what an arrangement that has been seen to work is written down as.

So scenario 51 was built at Tier 2 as well: `TestScenario51AnyFormatFromAnyFeedInAPipeline`, one
document and one pipeline meeting four feeds of four unrelated formats in turn as processor tasks over
real feeds and real streams. Each is learned, each fragment is a pipeline document anyone can open, each
output stream equals its format's golden and carries the bindings that say what produced it, and a
second stream of a format already learned is served by its rule with nothing asked. Tier 1 says the
stage reaches the right decisions; this says the whole arrangement works.

The pack is that arrangement. `DemoContentPack` holds the document, the pipeline, the filter and the
feeds, and the Tier 2 test runs *those*, so the demo is not a rehearsal of what is shipped but the thing
itself — with one difference, the model, which is scripted here and is the live run's to answer. The
settings beneath come from `DemoDocument`, which Tier 1's scenario 51 is also run with, so a pack that
drifted from either run fails a test.

Writing it out is [GenerateDemoContentPack], run rather than tested, because a deliverable is not a side
effect of a test run. `TestDemoContentPack` reads every file back with the classes the importer reads
them with, so a property that does not exist, a document that will not deserialise, an asset with no
`.node` beside it or a reference naming nothing in the pack all fail there. What that cannot reach is
the importer's own behaviour, which needs a database.

It is written rather than exported for the same reason. The format is the one this build reads: a
`.node` per document naming its uuid, type, name and explorer path, with the document's assets beside it
under the same prefix. It carries no secret — the model document names a stored secret and the person
running the demo creates it with their own key.

Three things the building of it found, each of which would have made a demo fail for a reason that has
nothing to do with what is being demonstrated:

- A filter whose feed term was a single `IN` listing the four feeds **created no tasks at all**. A term
  per feed under an `OR` does, and it is also what a person editing the filter would see.
- A document written outside the store carries no stamp of the built-in question text it was saved
  against, because the stamp is written by `ShapeshifterAiStoreImpl` on save. An imported document
  without it would say its templates were of no known version until someone saved it. The pack is
  written as a saved document is.
- A processor filter is the one document in the pack with no name of its own, and the exporter gives it
  one made of its pipeline and the head of its uuid. The pack writes that name, so it reads as an export
  of itself.


**The node would not start** (the owner's report, 2026-09-24), and the reason is worth keeping because
nothing in this branch's tests could see it. `ShapeshifterAiDbModule` bound the caches in front of the
rows, and a database module is not installed where the rest of the feature is: it goes in the *bootstrap*
injector, which exists only to open the data sources and run the Flyway migrations before the
application does, and the application's injector is a child of that one and inherits what it binds. The
bootstrap injector has no cache manager and no entity event bus, so Guice refused to build it and Stroom
stopped on startup with two missing implementations.

Stroom's own arrangement says where each belongs, and the feature now follows it: a `*DbModule` holds
the migrations and the connection and nothing else, and a `*DaoModule` beside it holds the bindings,
installed by `CoreModule` in the injector that has an application in it. The cache bindings, the `@Rows`
bindings behind them and both multibinder contributions moved across unchanged — and the `Clearable` and
`EntityEvent.Handler` contributions are better for it, since they now land in the same injector as the
things that read those sets.

`TestShapeshifterAiModules` is the guard, and it needs no database: it builds an injector over the
database module with only what the bootstrap injector holds, which fails with exactly the owner's two
errors on the arrangement that shipped, and one over the DAO module with what a node holds, which must
give the caches in front of the rows. `TestBootStrapModule` would also have caught it, but only where
there is a database to run it against.


**The pack imported and the document would not open** (the owner's report, 2026-09-24), which is the
fault the pack's own tests were built to catch and could not. `TestDemoContentPack` reads every file
back with the classes the importer reads them with; but reading a file is not importing it, and what
went wrong happened after the read.

Two things, both of them things an *exported* document carries and a hand-written one has to be told to:

- **A version.** Every document in the store has one — it is what a save checks before it writes — and
  the column it lands in does not take null. The insert failed for the document, the pipeline and all
  four feeds with `Column 'version' cannot be null`, and `StoreImpl.importDocument` records a failure
  against the document rather than throwing, so the explorer entry was made and nothing was behind it.
  A person sees a pack that imported and a document that will not open.
- **A processor for the filter.** An exported filter names the processor it runs under, and the importer
  makes that processor where the installation has none. Ours named none, so `Processor.getDocRef(null)`
  threw `UUID not set` and the filter was the one part of the pack that did not arrive at all.

The guard is `TestDemoContentPackImports` in `stroom-app`, which needed the database the earlier tests
were built to avoid: it writes the pack, imports it exactly as the import screen does — a confirmation
pass, every change agreed to, then the import — and then opens what landed. It reproduces both faults
on the pack as shipped. `TestDemoContentPack` gained the two cheap guards that catch the same thing
without a database: every document carries a version, and the filter names its processor.

It also settled a question the README had answered wrongly. An imported filter arrives **enabled only
when the import is told to enable filters** — the `content_pack_import` directory does, and the import
screen does when **Enable Processor Filters** is ticked. The README said filters always arrive enabled,
which would have left a demo posting data into a pipeline that made no tasks.

The pack is committed at the root of the repository now, at the owner's direction, so that what a demo
is run from is the thing under review rather than a zip somebody generated once. That only works if the
zip is the one the code writes, which `TestDemoContentPack` checks file by file, and if writing it twice
writes the same bytes — it did not: the entries were stamped with the moment of writing, and both the
sample files and the pipeline's two assets came out of a `Map.of`, whose iteration order differs in
every JVM. A pack that changed in three ways every time it was written would have made its own diffs
unreadable.


**Redaction's setting was withdrawn** (the owner's, 2026-10-03), which is a removal worth recording
because of what it says about the rest. `sampleRedaction` was on the document and on the Learning tab
from the first slice, defaulting to `REDACTED`; A38 ruled how redaction works and the owner deferred
building it. Nothing in the stage ever read the setting — the mentions in `Stage`, `Inputs`, `Sample`,
`Question` and `ModelAdvisor` are comments explaining what is *not* done while A38 is unbuilt — so every
document asserted that samples were reduced to token classes while they were sent to the model as they
arrived. On a feature whose conditions of deployment (01 §15.1) turn on that very property, a control
that states it and does not deliver it is worse than no control.

Removing it touched ten files and about seventy-five lines, more than half of them one enum: the field
and its eleven mentions on `ShapeshifterAiDoc`, the picker and its plumbing on the Learning tab, two
assertions, and the demo document's override. No migration — there is no column, the setting lives in
the document's JSON, and the shared mapper does not fail on unknown properties, so documents and
exported packs that carry it still read and simply lose the key when next saved. The content pack was
regenerated, which `TestDemoContentPack` insists on.

A17 and A38 stay in §13 word for word, since a ruling records a decision and not a build, with a note
saying the setting is gone until the build happens. What remains built of A17 is its size cap, which is
still a document setting.

**The bar moved to the record** (2026-10-05; design 04 §7, phase 2). Scenario 52 is the first of design
04's scenarios to be built, and it was built in shadow: the stage still serves, sentinels and emits as it
did, and what is new is what it *knows* — which records of a served stream fell short of which gate, and
which streams it would have held. The scorers that judge records one by one now name them (`Score.
failingRecords`), a verdict's failing records are the union over its gates and no more (A55), and the
deferral table of design 04 §6 exists and is written, one row per stream and document, with nothing
about which records and none of their text (A69).

Two things the scenario taught. The reason on a row counts the records the parser cut, not the lines the
judgement rests on (A14): the mixed stream is seven lines and six records, and "2 of 7" is a number
nobody can check against the output. And the second gate the scenario expected to name the thin records
— extraction quality — names none of them, rightly: the stylesheet left them typed and empty rather than
Unknown, and the demo document requires no field, so the schema gate is the one that caught them. That
is why the bar is the union of the gates and not any one of them, and the scenario says so rather than
pretending otherwise.

The streaming half of phase 2 — scoring record by record as the output is produced, the whole-output
`EventList` gone — is not in this slice; design 04 §7 records where it stands.

**The fragment moves in place, and a snapshot keeps what ran** (2026-10-05; A58, A61, A70–A72). A
promotion keeps a snapshot: the fragment's structure and each element's code as text, with the versions of
the documents it was read from, keyed by an id the rule points at. A relearning or an improvement edits
the rule's fragment in place — the same pipeline document and the same stylesheet, their text replaced —
under a new snapshot; a draft awaiting review is still its own fragment until Approve rebinds the
incumbent to it, since a person has not yet said yes to overwriting what serves. The deferral stamp is
the snapshot, so a stream held against one is scored again when the next is taken; an output records the
snapshot that produced it, and an as-processed reprocess runs that snapshot's code injected into the live
fragment's structure, as the stepper injects an edit. A fragment edited by hand is snapshotted the first
time its rule serves after the edit, before the stamp is compared, so the edit is what serves and what
waiting streams are scored against. Snapshots go with their rule. Scenario 56 holds it.

Design 01 §7.3 rule 1 — *new siblings, never edits* — is superseded by A58 for a rule's own fragment:
what ran before is the snapshot's to keep, not a sibling document's. Scenarios 9 and 27 now say so.

**Streams of any size** (2026-10-05; design 04 §3, phase 2's streaming half). A task no longer reads the
stream into a string. The parser-position element hands the stream itself to supervision, which reads its
prefix — 64 KiB, far above what the shape's signature and the sample the model is shown need — takes the
decision of §3 on it, and where a rule binds the stream serves it as it is read: the fragment runs over
the whole stream once as a pipeline, a gate at its tail judges each record of the output as the fragment
writes it, by the gates that judge a record (A55), and hands each that clears on to the pipeline's own
downstream, which writes as it goes. The first record short of the bar stops the run — the gate throws
the shortfall as a deferral, which the pipeline's layers already let through — and the stream is deferred
with what was found, the session opened on it, and the stream not read again; the task's executor then
discards what the writer had made of the records before it, so a stream that waits leaves nothing anyone
can see. Nothing larger than one record is held. Scenario 55 holds it, at Tier 1 inside scenario 54 and
at Tier 2 against real tasks and a real writer.

Every stream a task sees goes this way, small or large: the one path, so that a six-record scenario
exercises what a ten-gigabyte stream will meet. What is still read whole, and why: a step and an
as-processed reprocess, which are bounded by the person asking; a stage fed by a parser, whose stream is
one record; a stream of characters rather than bytes, or not UTF-8, since the fragment is given the bytes
as they are; and a session's sample, which is the prefix and so bounded already. The worker's read of a held stream is bounded the same way, with the rest opened
again from the store for a fragment to run over. Not yet done: the candidate judged on the sample and
then run over the held stream in full (A64), which this is the groundwork for.

**Streams wait instead of failing — the task decision** (2026-10-05; design 04 §7, phase 4's first slice).
Scenario 54 and test A's first three steps hold it. A processing task now takes the decision of design
04 §3 and never learns: a shape nothing has learned, or a record short of the bar, defers the task — the
exception phase 1 cut the path for — with a deferral row stamped against the fragment in force, and the
session is opened and parked for the worker, which was the deferred execution mode's path and is now
every document's. A promotion wakes the rows of its shape through the processor task service, and the
woken task decides again: served, and its row goes. Nothing is sentinelled to make a stream wait; what
errors is a shape given up or reserved.

Every Tier 2 scenario that learned inside a task had the worker step put in — task, worker, task — which
is the flow as designed and read as such in each. Scenario 20, *the sentinel is an error stream and the
ledger is a row*, became *the deferral is a row*: no error, and a row in `shapeshifter_deferral` naming
the task a wake will move. Two things the build found: the worker's session was releasing the held
stream's row when it emitted — phase 2's audit put the release in `emit`, which is right for a served
run and wrong for a session whose stream the woken task will serve — so the release now sits on the
serving paths alone; and a node that has found a filter empty waits `skipEmptyFilterFetchDuration`
before asking it again, so a wake is honoured only after that backoff, which the tests zero and which
design 04 §5.3 now says. One thing kept as it was, by decision rather than by accident: a stage fed by a
parser (scenario 50's transformation position) still learns in the task, because the stream it would
hold is the records the parser above it made, which the worker cannot read back from the store.

**The corpus became real** (2026-10-05; design 04 §7, phase 3). Scenario 53 holds it at both tiers. An
entry is a record and not a stream (A56): what a session learned for — the sample the model was shown on
a first learning, the records that fell short on a relearning — each as the one document the transform
is given, with the output the candidate made of it and what that output is. A candidate is held to every
entry to no worse (A66): its output must clear the bar, a translation may not become an Unknown, and a
record that yielded an event must still yield one; output may otherwise change, and the refusal names the
entry. In a node the entries of each promotion are one stream on the source feed, of the corpus's own
type, a segment per entry, with a row per entry in `shapeshifter_corpus` naming the stream and segment
(design 04 §4.1): on the feed, so that the copy carries the feed's classification and lives under its
retention rules.

Three things building it settled. The sample is the learning prefix, so a stream of six records yields
five entries under the default held-out fifth — the records the model was shown, not the records the
stream brought — and the scenarios that counted streams now count those. A relearning that names no
record, because the shape fell short on yield or coverage, learns for the sample again, as a first
learning does. And an improvement (A46), which has no stream of its own, learns from the corpus put back
together — each record's own text between what the stream wrapped its records in — which only the data
splitter can supply, since it alone says where it cut: a rule learned over the JSON or XML parser can be
relearned from a stream but not improved from its corpus, and the stage says so rather than guessing.

**The session runs the held stream in full** (2026-10-05; design 04 §4 and §7, phase 4's third slice; A64,
A65 at its minimum, A73). Scenario 57 holds it at both tiers. A session's first act after a promotion is
a full pass of the held stream through the promoted fragment — nothing handed on, nothing written — with
a judge that goes on past a record that falls short and keeps the first few of them: the record as the
parser cut it, what the transform made of it, and the verdict. Those open the next question as its
feedback, the transform is learned again in place, and the pass is run again; the session ends when the
stream clears, or parks for a person when the plan is spent or ten rounds have not cleared it. In the
pipeline the parser is tapped — a filter the runner adds to the fragment's parser for the run, holding
the current record and no more — so the evidence is the parser's own cut, and the stand-in cuts the
parser's output by the boundary to say the same. A parked attempt is a status the worker does not list
and a lease that does not lapse: a task of the shape defers and opens nothing, and nothing takes the
shape from it but a person.

Three things building it found. A chain beginning with the Data Splitter had no split: its transform was
given the whole parsed stream as one document, which a stream of any size cannot have, and the stand-in
ran it whole while the pipeline would not have. Every such chain now carries a `SplitFilter` and one
record is one `record`, one deep — so the goldens are what a per-record run produces (the joined output
is laid out as one document again), a stream brings as many records as the parser cut, a parser that
cuts none is judged on what the stream brought rather than excused as too few to judge, and a stylesheet
that filtered by position no longer filters at all, which is §12 item 25 seen from the other side and
is why scenario 7 now filters by what the record says. The prefix a task reads as text ends at its last
line break, since a line cut in the middle is not a record and judged as one it failed a candidate every
whole record cleared. And at Tier 1 the conversation was judging the candidate over the whole stream,
because a scenario's input had always been the whole text: scenario 57 hands the stage a prefix and an
opener, as a task does, which is the only way the full pass has anything the sample did not. Its audit
found the pipeline's tap one record behind the judgement and a session's rounds writing over one
another's turns; both are held by the scenario now (design 04 §7, phase 4's third slice).

**A session whose node dies is carried on by another** (2026-10-05; design 04 §4 and §7, phase 4's fourth
slice). Scenario 58 holds it. A lease is now a turn's allowance — a node's `sessionLease`, ten minutes
by default — renewed before every question and as every turn is recorded, rather than the attempt's whole
budget set once; and a lease that has lapsed no longer frees the shape. An attempt whose node has died
holds its shape as it did alive: the next stream of the shape waits on it, nothing opens beside it, and
the next pass of the worker on any node finds it among the lapsed, takes it, and re-walks it from the
turns already recorded — asking the model only what the dead node had not been answered. What the old
behaviour would have done with the same attempt is abandon it on the next stream's arrival and open a
new one that asked everything again; the DAO's tests of that are rewritten to the resume.

Two things worth saying. The scenario kills its node with an `Error` thrown from the model's reply,
which nothing in the stage catches or records, because that is what a JVM dying looks like from the
table's side: a row left `IN_PROGRESS` with a lease that runs out. And the Tier 1 clock is fixed, so a
lapse is made by pushing the lease back by hand, exactly as the earlier lapse scenarios did; the DAO's
own tests do the same against the table.

**A session always ends** (2026-10-05; design 04 §7, phase 5; A51–A54, A62, A74–A76). Scenarios 59 and 60
hold it, and test A's steps 4 to 6 hold the flow at Tier 2. The exhaustion step is a plan step kind,
`ADMIT`, reached only by a transition from a spent transform step; its question is the configuration
question with the admitted form — an `EventDetail/Unknown` carrying a `Data` item named
`ShapeshifterUnknown` whose value is the record kind — in its text, and it is the one step the form may
be offered at. The quality scorer accepts the marked Unknown and the corpus holds it as an admission; a
parser whose candidates are spent gives the shape up and errors what waited on it.

What building it found is in design 04's phase note, and one of it changed the tests behind it: a
relearning's candidates were judged on the sample alone, which the records that fell short are not in,
so the conversation now holds every transform candidate to those records too, and a candidate short on
one says which. Scenarios that spent a transform's candidates on purpose — 7, 46, 57's parking case — now
meet the exhaustion step after the spending and script its question, answered as badly as before, and
their abandonment reads *no admission* where it read *no passing configuration*. The direct plan every
document starts from gained the step, so a document saved before this learns to admit without being
touched; the demo pack was regenerated, by a build task that now exists for it.

**Test B** (2026-10-06; design 04 §8.1) is `TestScenarioBManyStreamsManyThreads`: twenty streams in two
waves, four task threads and the learning job beside them, over the real task table; one session per
learning event, every stream served once and whole, nothing left behind, and the invariants sampled as it
runs — with the sampler also required to have seen the race, so that a quiet run cannot pass by luck.

**What an operator sees, the first slice** (2026-10-06; design 04 §7, phase 6; A77–A79). Scenario 61
holds the grant. The backlog pane, the raised flag and the document's maximum deferral age are in the
screen and the resource, and the flag's arithmetic has a unit test; the screen itself is held by the GWT
compile and by a person, as phase 6's exit says.

**The removals** (2026-10-06; design 04 §6 and §7, phase 6's second slice; A60, A80). No new scenario:
what this took out was held by scenarios that now say the opposite, and design 04's phase note records
which. Execution mode is gone from everything that named it; the rolling score is shown and not acted
on; a refusal in the task path writes no ledger row unless the shape was given up.

## 7. Decisions taken

Ruled 2026-09-17, each as recommended:

- **The held-out split is a seeded shuffle**, the seed supplied by the `Stage`'s caller and fixed in
  scenarios; a node draws one per learning round. An order-based split would let sorted input game
  the gate. Where the input is raw text and records do not yet exist, the learning sample is a
  prefix and the held-out judgement is the whole stream — design 01 §4's probe prefix — because
  lines cannot be shuffled without knowing where records begin.
- **Tier 2 starts now with a hand-glued pipeline**: a fragment the module wrote, spliced into a
  pipeline by hand in an `AbstractProcessIntegrationTest` and run through `PipelineFactory`. The
  cheapest proof that A20's fragments are real pipelines; it does not wait on §12 items 1–2.
- **Quarantine and regression set are interfaces the `Stage` is given** — `Quarantine`,
  `RegressionSet` — with in-memory implementations for scenarios and stream-backed ones in Tier 2.
- **First slice: `Scorecard`, the scorers scenario 1 needs, and scenario 1 end to end in Tier 1.**
- **Shape signature normalisation** is a strategy the document does not yet expose, provisional per
  design 01 §5 and revisited under A6 with real feeds; scenarios depend only on same-shape-same-key.
- **The 2026-09-17 rulings in design 01 §14** — provisional bindings, the learning key, the relearn
  trigger, reserved rules, durable attempts and the Supervisor view, the per-feed processor gate —
  are taken as read here; scenarios 25–32 are their statement as tests.

Ruled 2026-09-18, the owner's:

- **The scripted fixtures derive the split and target answers** (`Structure`, §3) rather than
  script them, so that scenarios written before A31 state only what they are about; a scenario about
  the boundary or the target scripts those lines itself (34–37, 39).
- **The conversation is measured, not chosen** (A32): both shapes stay, the live harness runs either on
  the same feeds (`SHAPESHIFTER_LIVE_DIALOGUE`), and §6.3 is where the evidence goes.
- **The conversation is the document's** (A33, A34): scenarios 39 and 40 state it; a document owns its
  steps, the two measured conversations are examples to load from, and the scripted scenarios give their
  documents the target-first steps so the fuller conversation stays exercised while a new document starts
  direct.
- **The XML split is built, not waived** (A35): scenario 37's XML variant, `nested-entries.xml` with
  records two levels down — the container refused, the element accepted, the transform told. It binds
  *provisionally*, since the stage still counts the root's children as the stream's records; that
  count follows the record element onto the rule with the A26 tables.
- **Coverage is by characters** (A36): the header case's golden scores its sixteenth, not its seventh,
  and the line is still named; `TestExtractionDegeneracy` states it.
- **Next live run**: feeds 06 and 07 in both conversations, when the key's limit resets on 2026-10-01,
  with everything since run 6 — the chain steer, the header rule, the none guard, the XML split,
  whole-document samples for markup, coverage by characters. **Slice 12**: the A26 tables.

Ruled 2026-09-21, the owner's, on four questions put with recommendations:

- **The plan is a graph** (A37): the list of A34 was one path through a flow the document could not
  express, and 02 §6.3's finding — direct cheaper on clean feeds, a target better on hard ones — wants a
  plan that escalates. Steps gain ids, checks from a closed list, a role on `CONFIGURE`, and
  transitions over typed outcomes: `on <outcome> goto <id>` at once, `on spent goto <id>` when the
  candidates are gone; self re-ask stays the default; each transition is taken at most once. Checks
  are declared per step, thresholds stay on the Scoring tab. The parser–transform routing of design 01
  §10.1 rule 6 moves from `TargetChecks` into the examples as a transition. Scenarios 41 and 42 state
  it; 39 grows the new refusals.
- **Sequencing**: A37 is specified now and built as slice 12, ahead of the A26 tables, so that the
  attempt and turn tables are cut once around step ids and outcomes; the A35 record element reaches
  the rule with the tables, one slice later than Friday's order said.
- **The word**: what the document holds is a *learning plan* — `LearningPlan`, `PlanStep`,
  `PlanExample`, `SHAPESHIFTER_LIVE_PLAN` — and the run an attempt follows it with is still a
  *dialogue*; the classes are renamed in slice 12, and this file's history keeps the names of the day.
  (*The run became a* conversation *on 2026-09-24 — see below.*)
- **A phased plan** exists from today as design 03: phases A–G with exit criteria, the six formats
  of §3 as scenarios 43–48 (phase B, after slice 12), and the rulings owed with the phase each falls
  due in.
- **Redaction deferred** (A38): the owner ruled how it works — vocabulary kept, values classed; every text
  the model sees and every comparison against what it wrote; a harness dimension — and deferred the build
  until the formats are proven. Phase B runs raw; it is owed before phase G's real feed.
