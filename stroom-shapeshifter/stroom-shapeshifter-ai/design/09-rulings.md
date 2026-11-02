# 9. Rulings

Every design decision, A1 to A147: what it decided, whether it still stands, and where the current
design describes it. The other documents cite these numbers in brackets, and so do code comments.

The wording here is a summary. The full text of A1–A145, with the reasoning and the alternatives
weighed, is in the archive: A1–A69 in [design 01 §13](history/01-intelligence-in-the-pipeline.md), A70–A80
and A99–A102 in [design 04 §10](history/04-processing-flow.md), A81–A98 in
[design 05 §9](history/05-learning-flow.md), A103–A145 in [design 06 §10](history/06-pipelines-made-for-feeds.md).
A146 and A147 were ruled while this set was written.

**Status:**

| Status | Meaning |
|---|---|
| **Stands** | In force and built, unless the row says otherwise |
| **Restated** | In force with the change the row gives |
| **Superseded** | Replaced by the ruling named; kept for the history |
| **Deferred** | Decided, not built; in [10](10-status-and-future.md) |
| **Decided against** | Will not be built |
| **Moot** | Was open, and the design moved past the question |
| **Process** | About how the work was sliced or tested, not the design |

## 9.1 The supervised stage (A1–A47, September 2026)

| # | Decided | Status | Where |
|---|---|---|---|
| A1 | The replay unit (stream or record) comes from the fragment's chain; scoring granularity follows it | Restated by A99: only the stream unit is used, and the element's position check uses it | [3.1](03-processing.md#31-the-element) |
| A2 | Learned content is branched, never mutated in place | Restated by A58 and A61: a relearning rewrites in place, and snapshots keep what ran | [4.6](04-learning.md#46-what-learning-writes) |
| A3 | The configuration is a Stroom document type, not a separate database | Stands | [1](01-overview.md) |
| A4 | A stream the stage cannot serve gets an error stream with the reason | Restated by A48, A131: only a given-up learner errors streams, and only an error stream is written | [3.8](03-processing.md#38-errors) |
| A5 | Each session has a mandatory budget; a candidate that clears the floor on too few records serves provisionally | Stands; its execution-mode part superseded by A60 | [4.4](04-learning.md#44-budgets), [6.5](06-scoring.md#65-the-promotion-gate) |
| A6 | How to normalise a shape signature | Moot: there are no shapes (A104) | |
| A7 | Whether to propose document version history separately | Moot: snapshots (A61, A70) keep what ran | |
| A8 | The model writes extraction configurations, not just selects them | Stands; A11 is the guard | [5](05-learning-flow.md) |
| A9 | Promotion is automatic, with no human gate | Stands; A25 adds review mode as an option | [6.5](06-scoring.md#65-the-promotion-gate) |
| A10 | Generic over element types; Data Splitter, XSLT, JSON and XML first | Stands; the Chain step's default elements | [5.3](05-learning-flow.md#53-a-step) |
| A11 | Extraction is scored on yield and input coverage | Stands | [6.2](06-scoring.md#62-the-scorers) |
| A12 | A promotion releases what waited | Superseded by A129, A131: a promotion wakes its pipeline's deferred tasks | [3.6](03-processing.md#36-waking) |
| A13 | The model is an OpenAI-compatible endpoint through `stroom-ai`, whatever and wherever it is | Stands | [5.8](05-learning-flow.md#58-talking-to-the-model) |
| A14 | Promotion is judged on records the model never saw | Stands: learned from a prefix, judged on the whole stream | [6.5](06-scoring.md#65-the-promotion-gate) |
| A15 | An absolute floor and no regression against the incumbent | Stands | [6.5](06-scoring.md#65-the-promotion-gate) |
| A16 | An anti-degeneracy scorer; schema conformance is a gate, not a maximand | Stands, built as extraction quality | [6.2](06-scoring.md#62-the-scorers) |
| A17 | Samples are redacted by default | Decided against (A146); the sample size cap stays | [1.6](01-overview.md#16-what-it-is-not-yet) |
| A18 | A regression set per binding; no regression on any accepted record; kept on the feed, under its retention | Stands: the corpus is a stream on the source feed | [6.6](06-scoring.md#66-the-corpus) |
| A19 | A generated Data Splitter configuration may not set `ignoreErrors`; refused at the compile gate | Restated: the question forbids it, but nothing refuses it | [10](10-status-and-future.md) |
| A20 | What is learned is a pipeline fragment with no destination, run as a nested pipeline | Stands | [4.6](04-learning.md#46-what-learning-writes) |
| A21 | Learning is a conversation: the chain first, then each element's configuration with the real output before it | Stands, generalised by A31, A33, A37 | [5.1](05-learning-flow.md#51-the-idea) |
| A22 | Routing by selector over stream metadata, ordered rules | Superseded by A104, A117: a pipeline's filter is its route | |
| A23 | An asynchronous AI review scorer, advisory only | Deferred | [10](10-status-and-future.md) |
| A24 | A spend breaker opening an error mode per document and feed | Deferred: the counter is built (A44); error mode became raised (A57) | [4.4](04-learning.md#44-budgets) |
| A25 | Promotion mode per document: automatic or review; a draft awaits approval | Stands | [4.5](04-learning.md#45-how-a-session-ends) |
| A26 | Runtime state is tables in `stroom-shapeshifter-ai-impl-db` | Stands, reshaped by A111, A117, A124, A131 | [8.3](08-data-and-code-map.md#83-tables) |
| A27 | A filter creates no tasks while its feed is in error or under review | Superseded by A48, A109: deferred tasks fill the filter's budget | [3.5](03-processing.md#35-deferring-a-stream) |
| A28 | Every session is a durable, resumable record; a Supervisor where turns can be answered; the worker carries sessions waiting on the model | Stands; its cross-document list superseded by A143 | [4.2](04-learning.md#42-a-session), [7.2](07-ui.md#72-the-supervisor) |
| A29 | A document's learning key decides shapes; a shape whose rolling score falls below the relearn threshold relearns | Superseded by A104, A106; the threshold now only flags (A93) | [6.7](06-scoring.md#67-the-rolling-score) |
| A30 | The element shows a stage pane in the stepper and runs dry | Stands; read only by A121 | [3.11](03-processing.md#311-stepping) |
| A31 | Learn against targets: proposed events per kind of record; the record boundary its own question; input spans kept | Stands; spans only partly kept | [5.2](05-learning-flow.md#52-the-vocabulary), [3.9](03-processing.md#39-what-an-output-records) |
| A32 | The conversation's shape is a document setting | Superseded by A34 | |
| A33 | The flow is data on the document; kinds, grammars and judges stay in code; override-only templates over versioned built-in text | Stands | [5.1](05-learning-flow.md#51-the-idea), [5.5](05-learning-flow.md#55-what-each-question-carries) |
| A34 | No presets: the document owns its steps; the examples only load into it | Stands | [5.4](05-learning-flow.md#54-the-example-flows) |
| A35 | The split is asked of XML input too: the element that is one record | Stands; JSON likewise | [5.2](05-learning-flow.md#52-the-vocabulary) |
| A36 | Input coverage is the share of characters consumed | Stands | [6.2](06-scoring.md#62-the-scorers) |
| A37 | The flow is a graph over typed kinds and outcomes, with checks and transitions, each taken once | Stands | [5.3](05-learning-flow.md#53-a-step) |
| A38 | How redaction works | Decided against (A146) | |
| A39 | The escalating example asks the split of every input | Stands | [5.4](05-learning-flow.md#54-the-example-flows) |
| A40 | A parser short on yield goes back to the split | Stands, in the escalating example | [5.4](05-learning-flow.md#54-the-example-flows) |
| A41 | The document holds only what a person authors; nothing learned is written to it | Stands | [4.6](04-learning.md#46-what-learning-writes) |
| A42 | One learning lease per shape; a node that loses it does not wait | Superseded by A45 | |
| A43 | One learner at a time; candidates are not learned in parallel and merged | Stands | [4.2](04-learning.md#42-a-session) |
| A44 | Rate limit and spend breaker are cluster-wide counters | Stands for the counter; the policy is deferred | [4.4](04-learning.md#44-budgets) |
| A45 | The open session is the learner's claim, with a heartbeat; a lapsed one is carried on by any node | Stands | [4.3](04-learning.md#43-the-worker) |
| A46 | A person may leave guidance at any point, and ask a serving binding to improve | Stands | [4.7](04-learning.md#47-what-a-person-can-do) |
| A47 | The configuration question shows one record of each kind until targets are settled | Stands | [5.5](05-learning-flow.md#55-what-each-question-carries) |

## 9.2 Processing by deferral (A48–A80, A99–A102, October 2026)

| # | Decided | Status | Where |
|---|---|---|---|
| A48 | Processing pulls with deferral: a task that cannot complete waits; nothing errors to wait; learning never runs on a processing thread | Stands | [3](03-processing.md) |
| A49 | Kinds of event within a stream are XSLT match treatment, needing no mechanism outside the transform | Stands; "a shape is a feed and type" restated by A104 | |
| A50 | A stream is served whole or waits; the held stream is the session's acceptance test | Stands | [4.5](04-learning.md#45-how-a-session-ends) |
| A51 | Unknown is the product of an exhausted session, not a catch-all | Stands | [5.7](05-learning-flow.md#57-marked-unknowns-the-exhaustion-step) |
| A52 | A translation always terminates; an unlearnable parse is the one real error | Stands, qualified by A65 | [4.5](04-learning.md#45-how-a-session-ends) |
| A53 | A learned Unknown is marked; quality accepts it and refuses a bare one | Stands | [5.7](05-learning-flow.md#57-marked-unknowns-the-exhaustion-step) |
| A54 | A parse is unlearnable when its candidates are spent short of coverage and yield | Stands | [4.5](04-learning.md#45-how-a-session-ends) |
| A55 | The bar is the per-record gates and nothing else | Stands | [6.4](06-scoring.md#64-the-bar) |
| A56 | The corpus holds only records a session had to learn for, with their accepted output | Stands | [6.6](06-scoring.md#66-the-corpus) |
| A57 | A stream deferred past the maximum age is raised and goes on waiting | Stands | [3.5](03-processing.md#35-deferring-a-stream) |
| A58 | A session improves a transform in place: one fragment and stylesheet per learner | Stands for an automatic relearning; a first learning and a draft write new, numbered fragments (A132) | [4.6](04-learning.md#46-what-learning-writes) |
| A59 | One configured retry delay; a promotion wakes early; `processor_task` gains no column | Stands | [3.5](03-processing.md#35-deferring-a-stream) |
| A60 | Execution mode is removed; learning never runs in a task | Stands | [4.1](04-learning.md#41-where-learning-happens) |
| A61 | Every promotion keeps an immutable snapshot; outputs record it; as-processed runs it | Stands | [3.10](03-processing.md#310-reprocessing) |
| A62 | The marker is a named `Data` item in `EventDetail/Unknown`, not counted as untyped | Stands | [6.2](06-scoring.md#62-the-scorers) |
| A63 | The document's regression cap bounds a binding's corpus | Stands | [6.6](06-scoring.md#66-the-corpus) |
| A64 | A node carries a session to its end in one pass | Stands; at most 10 rounds | [4.5](04-learning.md#45-how-a-session-ends) |
| A65 | A session out of budget parks for a person; nothing becomes Unknown because money ran out | Stands | [4.5](04-learning.md#45-how-a-session-ends) |
| A66 | The corpus holds a candidate to no worse; a translation never becomes Unknown | Stands | [6.6](06-scoring.md#66-the-corpus) |
| A67 | Nothing new bounds a learner that keeps failing: the spend limit and the deferral age do | Stands, but the spend limit it relies on is not built | [10](10-status-and-future.md) |
| A68 | A marked Unknown is revisited only when a person asks | Stands | [5.7](05-learning-flow.md#57-marked-unknowns-the-exhaustion-step) |
| A69 | A session learns from one stream; waiting streams record only what they wait against | Stands | [3.5](03-processing.md#35-deferring-a-stream) |
| A70 | A snapshot is a row of configuration text; as-processed injects it into the live fragment's structure | Stands | [4.6](04-learning.md#46-what-learning-writes) |
| A71 | A snapshot goes with its binding on retract or reject | Stands | |
| A72 | A hand-edited fragment is snapshotted on its next use: a promotion by hand | Stands | [3.4](03-processing.md#34-serving-a-stream) |
| A73 | The `PARKED` status, a lease that does not lapse, shown in the Supervisor | Stands | [4.2](04-learning.md#42-a-session) |
| A74 | The marker item is named `ShapeshifterUnknown`, its value the kind admitted | Stands | [5.7](05-learning-flow.md#57-marked-unknowns-the-exhaustion-step) |
| A75 | A marked Unknown clears the bar, is held in the corpus, and counts in the rolling score | Stands | [6.6](06-scoring.md#66-the-corpus) |
| A76 | The exhaustion step is reached only by the flow's transition from a spent transform, or a person sending it | Stands | [5.7](05-learning-flow.md#57-marked-unknowns-the-exhaustion-step) |
| A77 | A grant is the session's, not the document's | Stands | [4.4](04-learning.md#44-budgets) |
| A78 | The maximum deferral age is a document setting | Stands | [3.5](03-processing.md#35-deferring-a-stream) |
| A79 | Raised is computed from age when read; only giving up errors a stream | Stands | [3.5](03-processing.md#35-deferring-a-stream) |
| A80 | A given-up shape's stream keeps a ledger row | Superseded by A131 | |
| A99 | One stage, at the parser's position; a task never learns | Stands | [3.1](03-processing.md#31-the-element) |
| A100 | A person's mark relearns on the learner's next stream with enough records, while the binding serves | Stands | [3.7](03-processing.md#37-when-a-stream-has-something-new-in-it) |
| A101 | A provisional binding is not retracted by the floor; enough clean records promote it | Stands | [6.5](06-scoring.md#65-the-promotion-gate) |
| A102 | Improve stays synchronous | Stands; ISSUES item 3 | [4.1](04-learning.md#41-where-learning-happens) |

## 9.3 The learning flow on screen (A81–A98, October 2026)

| # | Decided | Status | Where |
|---|---|---|---|
| A81 | It is called the *learning flow*: `LearningFlow`, `FlowStep`, `flow` | Stands | [5](05-learning-flow.md) |
| A82 | The flow has a tab of its own | Superseded by A91, then A110: it is the Learn tab | |
| A83 | The exits are `end`, `park` and `give-up`; `abandon` takes the step's default | Stands | [5.3](05-learning-flow.md#53-a-step) |
| A84 | Defaults are drawn dashed and labelled | Stands | [7.1](07-ui.md#71-the-shapeshifter-ai-editor) |
| A85 | Question text is per kind of question | Stands | [5.5](05-learning-flow.md#55-what-each-question-carries) |
| A86 | The shared texts open from Start | Stands | [7.1](07-ui.md#71-the-shapeshifter-ai-editor) |
| A87 | The text form is two-way beneath the chart | Stands | [7.1](07-ui.md#71-the-shapeshifter-ai-editor) |
| A88 | A session's path is drawn on the flow in the Supervisor | Stands | [7.2](07-ui.md#72-the-supervisor) |
| A89 | A judgement diamond follows each step | Stands | [7.1](07-ui.md#71-the-shapeshifter-ai-editor) |
| A90 | The chart is inline SVG from a GWT widget, no library | Stands | |
| A91 | Tabs: Route, Score, Learn | Superseded by A110: Score, Learn | |
| A92 | The maximum deferral age is on Route | Superseded by A118 | |
| A93 | The relearn threshold is on Score, as a flag | Stands | [6.7](06-scoring.md#67-the-rolling-score) |
| A94 | The session's settings open from Start | Stands | [7.1](07-ui.md#71-the-shapeshifter-ai-editor) |
| A95 | The chain question's elements are the Chain step's | Stands | [5.3](05-learning-flow.md#53-a-step) |
| A96 | An inherited default shows as a placeholder | Stands | [7.1](07-ui.md#71-the-shapeshifter-ai-editor) |
| A97 | Fields a kind cannot use are hidden, not greyed | Stands | [7.1](07-ui.md#71-the-shapeshifter-ai-editor) |
| A98 | A process header across the tabs | Stands | [7.1](07-ui.md#71-the-shapeshifter-ai-editor) |

## 9.4 Pipelines made for feeds (A103–A145, October 2026)

| # | Decided | Status | Where |
|---|---|---|---|
| A103 | The feature is the Pipeline Factory: a document type and a job | Stands | [2](02-pipeline-factory.md) |
| A104 | The pipeline is the unit of learning; shapes and routing go | Stands | [2.1](02-pipeline-factory.md#21-why-there-is-one) |
| A105 | A made pipeline inherits its template and holds only the factory's property values | Stands | [2.4](02-pipeline-factory.md#24-what-it-makes) |
| A106 | A factory's key is configurable, default feed and type | Stands, limited by A119 | [2.2](02-pipeline-factory.md#22-the-document) |
| A107 | What is learned stays a fragment the element runs | Stands | [2.6](02-pipeline-factory.md#26-how-an-ai-factory-is-set-up) |
| A108 | A factory offers every processor filter setting | Stands | [2.2](02-pipeline-factory.md#22-the-document) |
| A109 | A filter's task budget counts `DEFERRED` with `CREATED` | Stands | [3.5](03-processing.md#35-deferring-a-stream) |
| A110 | The document's tabs are Score and Learn | Stands | [7.1](07-ui.md#71-the-shapeshifter-ai-editor) |
| A111 | The migrations are rewritten, not added to, until released | Stands | [8.3](08-data-and-code-map.md#83-tables) |
| A112 | A settled AI pipeline does not become a plain one | Deferred | [10](10-status-and-future.md) |
| A113 | Names and places are the factory's patterns; the element has learned-content folder and name properties | Stands | [2.6](02-pipeline-factory.md#26-how-an-ai-factory-is-set-up) |
| A114 | A learner is the pipeline's UUID and the element's id; the document is not part of the key | Stands | [3.1](03-processing.md#31-the-element) |
| A115 | The Supervisor is three levels, master to detail | Stands | [7.2](07-ui.md#72-the-supervisor) |
| A116 | A session shows as its flow and its conversation | Stands | [7.2](07-ui.md#72-the-supervisor) |
| A117 | There are no rules; one record per learner | Stands | [4.8](04-learning.md#48-learner-state) |
| A118 | The maximum deferral age is on the Learn tab | Stands, at Start | [7.1](07-ui.md#71-the-shapeshifter-ai-editor) |
| A119 | A factory's key offers only fields a filter can query: feed, type, pipeline | Stands | [2.2](02-pipeline-factory.md#22-the-document) |
| A120 | A deleted pipeline's learning is marked and ages out | Stands | [3.12](03-processing.md#312-when-a-pipeline-is-deleted) |
| A121 | Stepping is read only | Stands | [3.11](03-processing.md#311-stepping) |
| A122 | A factory's changes apply to what it makes next | Stands; "apply to all made" deferred | [2.4](02-pipeline-factory.md#24-what-it-makes) |
| A123 | A factory makes its documents as its run-as user | Stands | [2.4](02-pipeline-factory.md#24-what-it-makes) |
| A124 | A learner's record points at its bindings | Stands; its "pinned" removed by A138 | [8.3](08-data-and-code-map.md#83-tables) |
| A125 | Phase J slice 2 in two slices | Process | |
| A126 | A learner does not know its feed | Stands | [3.1](03-processing.md#31-the-element) |
| A127 | The scenarios name their learners | Process | [8.6](08-data-and-code-map.md#86-tests) |
| A128 | The Supervisor names the learner, not a shape | Stands | [7.2](07-ui.md#72-the-supervisor) |
| A129 | The stage asks for nothing to be reprocessed; it reports how many streams are affected | Stands | [3.6](03-processing.md#36-waking) |
| A130 | The sweep for streams on a replaced transform is per learner | Stands | [3.6](03-processing.md#36-waking) |
| A131 | There is no ledger; the attempt is the record | Stands | [4.2](04-learning.md#42-a-session) |
| A132 | A learner's later fragments are numbered, first free number | Stands | [4.6](04-learning.md#46-what-learning-writes) |
| A133 | Learned content is named for its pipeline with ` learned` after it | Stands | [2.6](02-pipeline-factory.md#26-how-an-ai-factory-is-set-up) |
| A134 | A pipeline's deletion is heard, and checked for nightly | Stands | [3.12](03-processing.md#312-when-a-pipeline-is-deleted) |
| A135 | A deleted pipeline's learning stops at once | Stands | [3.12](03-processing.md#312-when-a-pipeline-is-deleted) |
| A136 | What a deleted pipeline learned stays in the explorer | Stands | [4.9](04-learning.md#49-retention) |
| A137 | The Supervisor hides a deleted pipeline's learning unless asked | Stands | [7.2](07-ui.md#72-the-supervisor) |
| A138 | There is no pinning | Stands | |
| A139 | `minRecordsToLearn`: the records a stream must bring to learn from | Stands | [6.5](06-scoring.md#65-the-promotion-gate) |
| A140 | What serves a learner is its binding; "rule" is gone | Stands | [README](README.md#glossary) |
| A141 | A serving binding is acted on from its pipeline's row | Stands | [7.2](07-ui.md#72-the-supervisor) |
| A142 | The pipelines list is narrowed by document, state and deletion | Stands | [7.2](07-ui.md#72-the-supervisor) |
| A143 | There is no list of every session | Stands | [7.2](07-ui.md#72-the-supervisor) |
| A144 | A turn is scored by the check that decided it | Stands | [6.3](06-scoring.md#63-how-a-document-sets-them) |
| A145 | A session's time budget is per run; tokens over its life | Stands | [4.4](04-learning.md#44-budgets) |

## 9.5 Ruled while writing this set (November 2026)

**A146.** *Samples are never redacted.* Redaction good enough to protect data would itself need a model,
and a model asked to translate redacted data loses the context it translates by. A feed is pointed at a
model only where its data may go to that model. Supersedes A17 and A38, except A17's sample size cap.
Not a blocker for production.

**A147.** *A stylesheet the model writes is checked statically, instead of running under a restricted
function library.* Every candidate is refused, as `compile-failed` with the reason, if it calls any
extension function or reaches outside its input (`document()`, `unparsed-text()`, `collection()`,
`xsl:include`, `xsl:import`, `xsl:result-document`, external entities), and the check is repeated at
promotion. A stylesheet a person later edits by hand is theirs, like any other. Not built yet: ISSUES item
21.
