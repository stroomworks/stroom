# 6. Scoring

How a candidate and a served record are judged: the scorers, how a document sets them, the per-record
bar, the promotion gate, and the corpus that stops anything accepted getting worse. Nothing the model says
about its own work counts; only these do. The terms are in the [glossary](README.md#glossary).

## 6.1 Three places scoring is used

| Where | What is judged | By what | Section |
|---|---|---|---|
| A step of the flow | One candidate's output, over the sample (and any held records) | The document's scorers, narrowed to the step's checks (or its kind's defaults), with compile kept | [5](05-learning-flow.md) |
| The promotion gate | The candidate chain over the whole held stream, and over the corpus | Every scorer, then the floor and the corpus | §6.4, §6.5 |
| Serving | Each record a binding's fragment writes | The per-record scorers only: the bar | §6.3 |

## 6.2 The scorers

Each `ScorerType` has one `Scorer` implementation. A scorer measures one signal over one step's output and
returns a score in [0, 1] with diagnostics. The diagnostics are what the model is told when it loses
marks. A scorer with nothing to measure on a step, such as coverage on a transform, returns nothing and is
neither counted nor failed.

| Scorer | Applies to | Measures | Parameters |
|---|---|---|---|
| **Compile** (`CompileScorer`) | Every step | 1 if the step ran, 0 if it could not (the Data Splitter schema refused it, Saxon rejected it). Its diagnostics are the most useful feedback the model gets. Set as a gate | none |
| **Input coverage** (`InputCoverageScorer`) | A parser | The share of the input's *characters* the split consumed (A11, A36). Missed lines are named, but a dropped header costs what it is worth and a dropped record costs its size | none |
| **Yield** (`YieldScorer`) | A step that wrote XML | Output records per unit of input against the expected ratio: `min(actual, expected) / max(actual, expected)`, so too many lose marks as too few do | `expectedRatio`; `basis`: input records, lines or bytes |
| **Schema conformance** (`SchemaConformanceScorer`) | A transform | The share of records valid against the document's schema group, each judged alone by Stroom's own `SchemaFilter`. The feedback adds what the schema says the failing element may contain (`ContentModels`), so the model can finish an element in one step instead of one missing child per turn | `schemaGroup`, e.g. `EVENTS` |
| **Extraction quality** (`ExtractionQualityScorer`) | A transform | Anti-degeneracy (A16): the average of typed elements versus `Data`, records whose `EventDetail` is a real branch rather than `Unknown`, and required fields present. A skeleton event that validates but extracts nothing scores as nothing | `allowUnknownEventDetail`, `requiredFields` |
| **Business rules** (`BusinessRulesScorer`) | A transform | The share of records satisfying every named XPath assertion, optionally counting the stylesheet's own `xsl:message` warnings against it | `assertions` (name, XPath), `includeTransformMessages` |
| Error load, event classification | | **No scorer exists.** A document naming one is refused when scored, and the editor hides them (ISSUES item 20) | |

A **marked Unknown** (A53, A62, `Admission`) is neither a real branch nor a bare Unknown: it clears the
quality scorer, and its marker item is left out of the untyped-element count. A bare `Unknown` is refused
unless the document allows `Unknown`.

## 6.3 How a document sets them

A document's `scorers` is a list of `ScorerSetting`, at most one per type:

| Field | Meaning |
|---|---|
| `type` | The scorer |
| `weight` | Its share of the weighted total; zero for none |
| `threshold` | The score below which the step fails |
| `gate` | Must pass on its own, whatever the total says (§8.3 of the archived design 01: conformance is a gate, not something to maximise) |
| `parameters` | The scorer's own, above |

`Scorecard` applies the settings to a step. A setting whose scorer is not installed, or whose parameters it
refuses, fails when the scorecard is built: a configuration fault is found at once, not stream by stream.
A step's checks narrow the scorecard to the scorers they name, keeping compile (A37).

**Only the scorers a document lists are applied, compile included.** Each judged step's weighted total is
1.0 when nothing carries weight, and a step with no failed gate passes. So a document with no scorers
judges nothing: every candidate passes, clears any floor, and every record clears the bar. A new document
starts that way, and nothing warns about it ([10](10-status-and-future.md)).

The **verdict** (`Verdict`) on a step holds every judgement, the weighted total (1.0 if nothing carries
weight), whether every gate passed, and the feedback: each failing scorer's diagnostics, prefixed by which
scorer and by how much it fell short. Its **outcome** is `passed`, `compile-failed`, or the shortfall of
the first failed gate, then the first other scorer under its threshold, in the document's order. The
**deciding check's score** is recorded on the turn: on a shortfall the one that decided it; on a pass the
gate that passed by the least (A144).

The demo document's set, which learned four unrelated formats in the scenarios (`DemoDocument`):

| Scorer | Weight | Threshold | Gate | Parameters |
|---|---|---|---|---|
| Compile | 0 | 1.0 | yes | |
| Yield | 1 | 0.5 | no | ratio 1.0 per input record |
| Schema conformance | 1 | 1.0 | yes | `EVENTS` |
| Extraction quality | 1 | 0.7 | yes | Unknown not allowed, no required fields |

A new document has no scorers until a person adds them; the Score tab's *Add* offers the first built
scorer type not already in the set.

## 6.4 The bar

The bar is what every record must clear to be served (A55). A record clears it when every **gate** among
the per-record scorers (schema conformance, extraction quality, business rules) passed on that record.
The record-level account is each scorer's `failingRecords`, and the bar's shortfall is their union. A
document with no per-record gate has no bar.

The bar is used:

- **in serving**, by `RecordGate` at the fragment's tail, record by record as the fragment writes them
  ([3.4](03-processing.md#34-serving-a-stream)). One record short and the stream defers;
- **in settling**, by the full pass of the held stream after a promotion
  ([4.5](04-learning.md#45-how-a-session-ends)), which keeps up to five records that fell short as
  evidence for the next round;
- **in the corpus check** (§6.6).

Compile, coverage and yield are not part of the bar: they measure a whole candidate, judged it when it
was promoted, and say nothing about one record.

## 6.5 The promotion gate

A candidate chain becomes a binding only through the gate (A14, A15). It is judged over the **whole held
stream**, not just the sample it learned from (`Judged`):

- the chain is re-run step by step over the stream, and each step's verdict taken;
- its **score** is the product, over the steps, of each step's weighted total, or 0 if any step failed a
  gate;
- it **clears the floor** if every gate passed and the score is at least the document's
  `promotionFloor` (default 0.9).

Then:

| Path | Also required | If not |
|---|---|---|
| First learning | nothing more | The learner gives up: below the floor on its own stream |
| Relearning | A score no lower than the incumbent's on this stream; no worse on the corpus (§6.6) | The incumbent is kept |
| Improve | No worse on every corpus record, since there is no stream (A46, A102) | The incumbent is kept |

**Provisional (A5, A14, A101).** The held stream is learned from a prefix (the first 80% of lines by
default, `heldOutFraction`) and judged on the whole, so the remainder is held out. A stream with fewer
than `minRecordsToLearn` records (default 10) is too small to mean much, so a binding learned from it is
provisional: it serves, and is promoted once it has served that many records with a mean score at or above
the floor ([3.4](03-processing.md#34-serving-a-stream)). A person can accept it sooner. The gate does not
retract a provisional binding; one that falls short defers its streams like any other
([10](10-status-and-future.md)).

**Review mode (A25).** With `promotionMode` set to review, a candidate that clears the gate becomes a draft
for a person to approve or reject ([4.7](04-learning.md#47-what-a-person-can-do)).

## 6.6 The corpus

The corpus, or regression set, is the records a binding had to learn for, each with the output it was
accepted with (A18, A56). It is kept per binding (`RegressionSet`, keyed by the binding's UUID), so a
rebound binding keeps its history; a draft's entries move to the incumbent when it is approved.

**What goes in.** On every binding and rebinding, including drafts and provisional bindings:

- a first learning: every record of the sample it learned from;
- a relearning: the records of the held stream that fell short under the incumbent, as the full pass kept
  them; or, where nothing named a record (a shortfall in yield or coverage), the sample.

At most `regressionCap` entries per binding (default 100); the oldest go first (A63).

**No worse (A66).** A candidate in a relearning or an improvement is run, transform only, over every
entry. It fails on an entry if:

- the entry was a translation and the candidate makes it an `Unknown`;
- the entry yielded an event and the candidate makes nothing of it;
- any gate fails or any record falls short of the bar.

Output may otherwise change; the accepted output is kept so a person can see what changed, not so the
gate can refuse a change. A marked Unknown may become a translation; a translation may never become an
Unknown.

**Where it is kept (A18).** In a node (`StoredRegressionSet`) each promotion's entries are one stream of
type `Shapeshifter Corpus` **on the source feed**, one segment per entry. So the copy carries the feed's
classification and is subject to the feed's retention: a corpus that outlived the data it came from would
be that data, kept. `shapeshifter_corpus` indexes the entries by stream and segment (`CorpusIndex`). When
the cap drops rows, a stream no row names any more is logically deleted. Scenarios use
`InMemoryRegressionSet`.

**For improve**, the corpus is put back together as a stream (the wrapper, then every record as received)
so a session with no stream of its own has one to learn from.

## 6.7 The rolling score

Each stream a binding serves feeds the learner's rolling score. It triggers nothing. The Supervisor flags
a serving learner whose rolling score is below the document's `relearnThreshold` (default 0.8, *Flag a
serving binding below* on the Score tab), as a candidate for a person to improve.

## 6.8 Document settings

On the Score tab:

| Setting | Default | Meaning |
|---|---|---|
| `scorers` | none | §6.3 |
| `promotionMode` | automatic | Automatic, or review (A25) |
| `promotionFloor` | 0.9 | The score a candidate must reach |
| `heldOutFraction` | 0.2 | The share of a text stream's lines kept out of the sample |
| `minRecordsToLearn` | 10 | Below this, a binding is provisional |
| `regressionCap` | 100 | Corpus entries per binding |
| `regressionRetentionDays` | none | **Read by nothing.** The corpus follows the feed's retention ([10](10-status-and-future.md)) |
| `relearnThreshold` | 0.8 | §6.7 |

## 6.9 Code

| Class | Role |
|---|---|
| `scoring.Scorer` and the six implementations | The signals |
| `scoring.Scorecard`, `Verdict`, `Judgement`, `Score` | Applying a document's settings to a step |
| `scoring.OutputRecords`, `Records`, `ContentModels`, `Admission`, `ConfinedXml` | What the scorers read |
| `fragment.RecordGate`, `Shortfalls` | The bar while serving and in the full pass |
| `stage.Stage.Judged`, `candidateScore` | The gate's score and floor |
| `stage.Corpus`, `RegressionSet`, `CorpusIndex`; `element.StoredRegressionSet` | The corpus |
| `shared.ScorerType`, `ScorerSetting` and the `*Parameters` classes | The settings |
