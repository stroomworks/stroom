# 10. Status and future work

Where the work stands as of November 2026: what is built, what is still to build before real data, what
is designed and deferred, what was found while this set was written, and what is owed before a pull
request. [ISSUES.md](../ISSUES.md) is the live list and is kept up to date; this document explains it
and puts it in order. Its item numbers are cited as *ISSUES n*.

## 10.1 What is built

Everything in documents [2](02-pipeline-factory.md) to [7](07-ui.md) is built and tested, except where
those documents say otherwise. In outline:

| Area | Built | Tested by |
|---|---|---|
| Pipeline Factory | The document, its editor, the job, the combination and scan tables, forgetting | Unit tests, DAO tests, the factory's scenario tests |
| Processing | The element, the task decision, serving through the bar, deferral as a task status, waking by pipeline and the superseded sweep, output attributes and rows, as-processed reprocessing, stepping | Tier 1 and Tier 2 scenarios (`TestScenario54TheTaskDecision`, the serving and deferral scenarios), DAO tests |
| Deferred tasks in the processor | `DEFERRED` counted with `CREATED` against a filter's budget (A109), the reaper's retry after the delay | `stroom-processor` tests (phase K) |
| Learning | The worker, claim and heartbeat, resume from recorded turns, budgets and grants, settling, first learning and relearning, review mode, improve, guidance, give up, retention | Tier 1 scenarios for each path, Tier 2 against the database |
| The learning flow | The five kinds, checks and outcomes, the step grammar, the three examples, editable templates | Flow and grammar unit tests, scenarios over each example |
| Scoring | Six scorers, scorecards, the bar, the promotion gate, the corpus stored on the source feed | Scorer unit tests, corpus and gate scenarios |
| The client | The editor's tabs, the Supervisor's three levels, the stage pane, the factory editor | Workbench stories with the leak check |
| Schema | One migration, `V08_00_00_001__shapeshifter_ai.sql`, and the factory's own | DAO tests on MySQL |

The scenario suites run against a scripted model. `TestLiveScenarios` runs the same formats against a real
one when its environment is set ([8.6](08-data-and-code-map.md#86-tests)); it has not been run since the
flow was last changed (ISSUES 13).

## 10.2 Still to build before real data

One item, from A147 (ISSUES 21): **a static check of every stylesheet the model writes.** On a node a
candidate runs in the real `XSLTFilter` with the whole `stroom:` function library, so a stylesheet could
call `http-call`, `fetch-json` or `ask-ai` while it is judged, and go on calling it once promoted.

What to build:

- A check of the stylesheet's text, run before the candidate is judged. It refuses the stylesheet as
  `compile-failed`, with the reason as the diagnostics, if the stylesheet:
  - calls any extension function;
  - names `document()`, `unparsed-text()` or `collection()`;
  - uses `xsl:include`, `xsl:import` or `xsl:result-document`;
  - declares an external entity.
- The same check again at promotion, so a binding never holds a stylesheet that would fail it.
- The model sees the refusal as it sees any compile failure, and can try again.

`XsltStep`, the Tier 1 stand-in, already refuses all of these by configuring Saxon. The check is to do the
same to the text, so that it holds whichever runner judges the candidate. The natural place is beside
`CompileScorer`, so that it is part of the compile gate.

Samples are not redacted, and that is decided (A146), not owed: see [1.6](01-overview.md#16-what-it-is-not-yet).

## 10.3 Designed and deferred

| What | Ruling | State | Why deferred |
|---|---|---|---|
| **The spend breaker and rate limit** | A24, A44 | The per-document spend counter (`shapeshifter_spend`, one-hour window) is written by every model call; nothing reads it | Budgets per session bound each session; across sessions, nothing does. A67 says a learner that keeps failing needs no new limit because the spend limit and the deferral age bound it, so until the breaker exists only the deferral age does. Build before anything runs unattended at volume |
| **Error mode per feed** | A24 | Became *raised* (A57): a stream waiting past the maximum deferral age is shown as raised in the Supervisor | Raised is shown, not acted on |
| **A processor gate** | A27 | Superseded by A48 and A109: deferred tasks fill the filter's budget, so a feed that cannot be served stops creating tasks by itself | Nothing more is needed unless the trial shows otherwise |
| **An AI review scorer** | A23 | Not built | Advisory only; worth having once the deterministic scorers are proven on real formats |
| **A settled AI pipeline becomes a plain one** | A112 | Not built | A learned pipeline keeps the element at its head; the fragment it runs could be inlined once nothing is learned for a season |
| **"Apply to all made"** | A122 | A factory's changes apply to what it makes next | Changing what is already made needs a ruling on what happens to a hand-edited pipeline |
| **Improve in the worker** | A102 | Improve asks the model inside the Supervisor's request (ISSUES 3) | The worker reads its sample from the stream store; an improvement's sample is the corpus. Moving it needs the attempt to carry its own sample |
| **The two unbuilt scorer types** | | `ERROR_LOAD` and `EVENT_CLASSIFICATION` exist in `ScorerType` with parameters and checks, and nothing implements them (ISSUES 20) | The editor hides them (`ScorerType.isBuilt`). Build them or remove them |

## 10.4 Found while writing this set

Each was checked against the code. Those that are not already in ISSUES.md have been added to it as
items 22 to 25.

**Gaps:**

1. **A document with no scorers judges nothing** (ISSUES 22). Only the scorers a document lists are
   applied, compile included ([6.3](06-scoring.md#63-how-a-document-sets-them)). A new document has
   none, so every candidate passes, clears any floor, and every record clears the bar, and nothing says
   so. The smallest fix is a warning on the Score tab and refusing to learn with an empty set. A larger
   one is a new document starting with the demo set (`DemoDocument`).
2. **A19 is only half built** (ISSUES 23). The built-in question text (`Templates`, parser and extraction
   rules) tells the model not to set `ignoreErrors` on a Data Splitter configuration, but nothing refuses
   one that does. A19 put the refusal
   at the compile gate. It belongs with the A147 check (§10.2): a static refusal of the text, reported as
   `compile-failed`.
3. **`regressionRetentionDays` is read by nothing** (ISSUES 24). It is on the document and the Score
   tab, whose help text says it sets how long regression records are kept; nothing keeps or prunes by it.
   The corpus follows the source feed's retention (A18), which is the stronger rule. Recommended: remove
   the field from `ShapeshifterAiDoc` and the tab.
4. **Record spans are only kept for the learned stream** ([3.9](03-processing.md#39-what-an-output-records)).
   `shapeshifter_output` can hold each record's span in the input, but spans are written only for the
   stream a fragment was learned on, and only a scenario reads them back (`TestScenario38ReadBackBySpan`). They were for relearning one faulty event
   from its own text. Either write them when serving and use them, or drop the column. Noted, not owed.
5. **A format change under a binding goes unnoticed** (ISSUES 2). Lines a parser drops while serving are
   reported on the error stream but judged by nothing. The rolling score cannot see them, since they
   never became records.

**Stale texts** (ISSUES 25, fixed 2026-11-02). Words left from designs the work has since moved past
have been brought up to date:

- the Retract button's tooltip, which said what the binding produced would be processed again (A129 ended
  that); it now says what the dialog says;
- the *Regression retention* help text on the Score tab, which said it set how long regression records
  are kept; it now says it is not yet applied (gap 3 is what remains);
- the *Shapeshifter AI Deferred Learning* job's description and the `attemptRetention` and
  `deferredLearningBatchSize` config descriptions, which spoke of a deferred execution mode and of
  retraction asking for reprocessing;
- `Decision.Retracted`, which nothing constructed after A101 and A129, removed with its cases;
- "deferred mode's worker" in class comments, now "the worker".

**Behaviour that is meant, though it may surprise:**

- **A provisional binding is never taken back by the gate** (A101). One that falls short defers its
  streams like any other, and a person retracts it if it should go.
- **Forgetting a combination while its pipeline exists** makes a second pipeline for it
  ([2.5](02-pipeline-factory.md#25-what-it-remembers)).
- **`relearnThreshold` only flags** ([6.7](06-scoring.md#67-the-rolling-score)): a person decides.

## 10.5 Before a pull request

In this order:

1. **Phase L's audit** (design 06 phase L step 5): `gwtCompile` and a look at every screen in a browser
   against a running Stroom, not only in the workbench.
2. **The A147 check** (§10.2), and the small fixes in §10.4 that the reviewer will want: the
   empty-scorers warning, A19, and removing the regression retention field.
3. **One live-model run** (ISSUES 13), with the env file the harness reads
   ([8.6](08-data-and-code-map.md#86-tests)).
4. **The walkthroughs** (ISSUES 15, 16, 19). Phase M (design 06) replaces ISSUES 15: a factory over
   `Raw Events` with the AI template takes three feeds from unseen to served from the UI, each paused,
   prioritised and put on a node group through its own filter, one reprocessed, and its learning watched
   in the Supervisor from its first session. ISSUES 16 composes the escalating example on the Learn tab
   without typing the grammar. ISSUES 19 makes, deletes and forgets a factory's pipelines.
5. **The full build and the Docker images** (ISSUES 12): `./gradlew clean build shadowJar
   buildDistribution` and `./buildDockerImages.sh`.
6. **A changelog entry** (ISSUES 14) through `./log_change.sh`, once there is a GitHub issue number.

The branch is `stroom-shapeshifter-ai`, targeting Stroom 8.0; its migrations are numbered `V08_00_00_*`.

## 10.6 For the real-scale trial

What only volume will show:

- **The learning pipelines list** groups the whole attempts table for each page and again for its
  total; the backlog count in the document's header still reads in memory (ISSUES 11).
- **The deferred population.** Every filter counts `DEFERRED` with `CREATED` (A109). Watch whether a feed
  that cannot be served holds its filter's budget long enough to matter.
- **Spend.** With no breaker (§10.3), read `shapeshifter_spend` by hand during the trial.
- **What a marked learner relearns on** (ISSUES 10): its first stream whose prefix is big enough, which
  may not be the stream that showed the fault.

## 10.7 Beyond the MVP

Ideas that have come up and have no ruling. They are not owed:

- Scorers for error load and event classification (§10.3), and the AI review scorer (A23).
- Detecting a format change under a binding: judging what the parser drops while serving (ISSUES 2).
- Naming a kind's own checks on the step panel by moving the table into the shared model (ISSUES 8).
- Keeping an unreadable step edit on the Learn tab when another step is clicked (ISSUES 7).
