# Shapeshifter AI: design

Shapeshifter AI teaches Stroom to translate a feed it has never seen. A stream that arrives with nothing
able to translate it waits, as a deferred processor task, while a model is asked — one closed question at
a time — for the Data Splitter configuration and the XSLT that turn it into event XML. Every answer is run
as a real pipeline and scored by Stroom's own validators. What passes is written as ordinary Stroom
content and serves every later stream of that feed at full speed, with no model involved. A person can
watch, steer, approve or take over any of it.

These documents describe the system as it now is, from the broad picture down to the tables and classes.
They are written in the present tense. Where a behaviour rests on a design decision, the decision's
number is given in brackets, e.g. (A104); [the rulings](09-rulings.md) list every one with its status and
where it is described.

## Reading order

| # | Document | Read it for |
|---|---|---|
| 1 | [Overview](01-overview.md) | The whole loop on one page: what happens to a stream, who does what, and what is guaranteed |
| 2 | [The Pipeline Factory](02-pipeline-factory.md) | How a pipeline and processor filter come to exist for each feed |
| 3 | [Processing](03-processing.md) | What the Shapeshifter AI element does with a stream: serve, judge, defer, wake |
| 4 | [Learning](04-learning.md) | Sessions, the worker, budgets, parking, the exhaustion step, promotion, and what a person can do |
| 5 | [The learning flow](05-learning-flow.md) | The questions the model is asked, as a graph of steps, checks and outcomes |
| 6 | [Scoring](06-scoring.md) | The scorers, the per-record bar, the promotion gate and the regression corpus |
| 7 | [The user interface](07-ui.md) | The Shapeshifter AI editor, the Supervisor, the stepper pane and the factory's screen |
| 8 | [Data and code](08-data-and-code-map.md) | Modules, seams and their implementations, tables, resources, and how the tests run |
| 9 | [Rulings](09-rulings.md) | A1–A147: what each decided, whether it still stands, and where it is described |
| 10 | [Status and future work](10-status-and-future.md) | What is built, what is deferred and why, and what is owed before a pull request |

Read 1 first. 2 to 6 can then be read in any order, though each assumes the vocabulary below. The live list
of open issues is [ISSUES.md](../ISSUES.md).

[history/](history/) holds the design documents as they were written and ruled, from the first reframing of
`full-design.md` in September 2026 to the Pipeline Factory in October. They are kept for their reasoning,
not as a description: much of what they describe — shapes, routing rules, the ledger — has since been
removed. Code comments that cite "design 01 §12" and the like refer to them.

## Glossary

Terms are listed in the order the [overview](01-overview.md) meets them.

| Term | Meaning |
|---|---|
| **Pipeline Factory** | A document type and a scheduled job that make one pipeline and one processor filter for each feed and type it finds in Stroom (A103). It knows nothing of AI |
| **Combination** | One set of key values a factory has met — by default a feed and a stream type — for which it makes one pipeline (A106, A119) |
| **Template** | The pipeline a factory's pipelines inherit from (A105). An AI template has the Shapeshifter AI element at its head |
| **Shapeshifter AI document** (`ShapeshifterAiDoc`) | The settings learning runs under: the model, the learning flow, the scorers and their thresholds, the promotion mode, the budgets. It holds only what a person authors; nothing learned is written to it (A41) |
| **Shapeshifter AI element** (`ShapeshifterAi`) | The parser-position pipeline element that serves, judges and defers streams. It points at a Shapeshifter AI document |
| **AI pipeline** | Any pipeline whose chain holds a Shapeshifter AI element. Nothing else marks it (§4.6 of the archived design 06) |
| **Learner** | One Shapeshifter AI element in one pipeline: the unit of learning, keyed by the pipeline's UUID and the element's id (A104, A114). Its record holds its state, the binding that serves it and any draft awaiting review |
| **Learner state** | Serving, learning, waiting for a person, given up, or learning off (`LearnerState`) |
| **Fragment** | A Pipeline document holding what was learned — e.g. `Source → DSParser → XSLTFilter`, or `Source → JSONParser → SplitFilter → XSLTFilter` — with no writer or destination, its parser and XSLT documents beside it. The element runs it as a nested pipeline (A20, A107) |
| **Binding** | What serves a learner: a fragment, the snapshot of its configuration, its score, whether it is provisional, and its record boundary (A117, A124, A140) |
| **Draft** | A binding awaiting a person's approval, under review mode (A25) |
| **Snapshot** | The immutable text of a fragment's configuration at a promotion, keyed by version. Every output names the snapshot that produced it, so a stream can be reprocessed exactly as it was first processed (A61, A70) |
| **The bar** | The per-record gates every served record must clear (A55). One record short and the stream is not served |
| **Deferred task** | A processor task whose status is `DEFERRED` because its stream could not be translated yet. It holds its place and is retried later; nothing is errored to make it wait (A48, A59) |
| **Wake** | Moving a pipeline's deferred tasks back to `CREATED`, so they are processed again — done on every promotion (A129) |
| **Raised** | A waiting stream older than the document's maximum deferral age. It is flagged and goes on waiting (A57, A79) |
| **Session** (attempt) | One run of learning for one learner, from the stream that opened it to a promotion, a park or a give-up. Durable and resumable (A28) |
| **Turn** | One question put to the model, or answered by a person instead, and its outcome |
| **Worker** | The *Shapeshifter AI Deferred Learning* job, which carries sessions that await the model. Learning never runs on a processing thread (A48, A60) |
| **Claim** | The session row's lease on its learner: one open session per learner, pushed out by a heartbeat during long model calls (A45) |
| **Held stream** | The stream that opened a session. The session is done only when this whole stream clears the bar (A50) |
| **Park** | A session stopped for a person — out of budget, or sent by its flow — keeping its claim while its streams go on waiting (A65) |
| **Grant** | More tokens or minutes given to a parked session, for that session alone (A77, A145) |
| **Give up** | A learner learns no more and streams nothing serves error with a reason, until a person sends it back (A52, A54) |
| **Learning flow** | The graph of steps a session walks: what each step asks, how it is judged, where each outcome goes. Held on the document as data (A37, A81) |
| **Step** | A node of the learning flow: one kind of question — Chain, Split, Target, Configure or Admit — with its checks, candidates and transitions |
| **Outcome** | How a step's answer was judged: `passed`, `refused`, `compile-failed`, or a named shortfall such as `coverage-short` (`StepOutcome`) |
| **Exit** | Where a session leaves the flow: `end`, `park` or `give-up` (A83) |
| **Candidate** | One answer to a step. Each step has a limited number before it is spent |
| **Scorer** | A measure of a candidate or a served record — compile, input coverage, yield, schema conformance, extraction quality, business rules (`ScorerType`) |
| **Check** | A scorer, or one of the learning-only judgements (wholeness, preservation, fidelity), named on a step |
| **Promotion** | Making a candidate a learner's binding, once it clears the gate: the floor, no regression on the corpus, and the held stream in full (A14, A15) |
| **Corpus** (regression set) | The records a learner had to learn for, each with the output it was accepted with. No later binding may do worse on any of them (A56, A66) |
| **Marked Unknown** | An `Unknown` event carrying a `ShapeshifterUnknown` marker, which the exhaustion step learns for a kind of record nothing better could be done with (A51, A53, A74) |
| **Exhaustion step** | The flow's Admit step, reached when the transform's candidates are spent with records still short: it learns marked Unknowns for what remains (A76) |
| **Guidance** | A message a person leaves for a learner, carried into every question after it (A46) |
| **Improve** | A person asking a serving learner to do better: a session that starts from the serving binding and must beat it on its corpus (A46, A102) |
| **Supervisor** | The screen that lists learning pipelines, their sessions and waiting streams, and a session's flow and conversation (A115, A116) |
