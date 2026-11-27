# Shapeshifter AI: show what is happening, and what might be going wrong

*Written 11 November 2026 · updated 18 November 2026 for the final UI ([ui-design.md](smart-pipelines/ui-design.md))*

## Summary

When a person points Shapeshifter AI at a feed, they want quick answers to a few questions:

1. Is it working?
2. If not, what is it doing about it: learning, waiting for something, or nothing?
3. If it is stuck, why, and what can I do?

The information to answer these mostly exists already, in the `shapeshifter_*` tables.
The UI doesn't put it together.
In practice the answers come from reading Error streams, explorer timestamps and, in the end, the database.
This write-up records what made that hard on the dev instance and proposes changes to the screens, the Error-stream messages and the API.

The proposals were first written against the Supervisor.
The UI rework has since replaced it: a Learning Flow's Pipelines tab and Monitoring > Smart Pipelines list the Smart Pipelines, and each pipeline's **Learning Status** tab is where its learning is followed and acted on, with a **Quality Assurance** tab beside it.
That design is final, so the proposals below are re-scoped against it.

| # | Proposal | Status with the final UI |
|---|---|---|
| 1 | A plain-language status line | **Largely done**: *Where it stands* on the Learning Status tab, and the chip and detail line in the list. Still client-side; moving it to the server is part of 6. |
| 2 | A Streams view | **Open**, but partly met by the Quality Assurance tab's volume and samples. Proposed as a tab under *Learning history*. |
| 3 | The run's decision says why | **Largely done**: stop reports with details and suggestions. Gaps listed below. |
| 4 | Error-stream messages that stand on their own | **Open**. Server only. |
| 5 | Correct the waiting reason | **Done**, to be checked for all three states. |
| 6 | A "what might be wrong" check | **Open**. Its findings would feed *Where it stands* and the Quality Assurance figures. |
| 7 | Get there from where people are | **Largely done**: the Learning Status tab is on the pipeline itself, *What it learned* on the Structure tab links to it, and the lists open it. The data browser link is open. |
| 8 | The same through the API and MCP | **Open**. |

It is a usability problem, separate from the two functional bugs found in the same session:
`docs/learning-flow-mixed-record-elements.md` and `docs/learning-flow-parser-drift-not-detected.md`.

## What happened

Two feeds, followed from the outside (data browser, explorer, MCP tools) and then confirmed from the database.

### NETFILTER: looked healthy, wasn't

| What a person could see | What was actually true (database) |
|---|---|
| Error stream 2132: `Served by binding f123a0c8-7a43-42fe-a1b4-c808d38911f1` | That binding had been learned **from this stream**, moments earlier. The message names a UUID, not a translation, and doesn't say it was just learned. This was misread as reusing another feed's translation. |
| Events stream 2133: 29 events | The input had 30 lines. Nothing says one was dropped, except a `dsParser` error further down the Error stream. |
| Error stream 2135: 20 parser errors, then `…which produced nothing for this stream` | The learner was unchanged: still *Serving*, rolling score 1.0, no relearn and no deferral. Output row 11 recorded the empty run with score 1.0. Nothing was going to happen. |
| Supervisor learner row (by its design): State *Serving*, Serving score 1.0. The final UI would show the same thing as *Translating* | The opposite of the truth. |

### GATEWAY-AUDIT: stuck, with a misleading reason

| What a person could see | What was actually true (database) |
|---|---|
| Session decision: `No record boundary after 5 candidates` | The model tried `batch`, `authentication`, `batch` and `export`, then replied *"No single element name fits. The records are the mixed children of `batch` …"*. That was a correct diagnosis, and it was refused as "not an element name". No answer could have passed (the sample held one `batch` only). None of this shows in the decision. |
| Waiting stream 2117: `Another node is learning for this pipeline; this stream waits for what it learns` | No node is learning. Attempt 14 is `PARKED`, waiting for a person. The stream has been retried 34 times against that parked claim. |
| Processor filter #52: polled every minute, version 159 | It keeps polling for nothing. |

In both cases, finding out what was going on took reading code and querying MySQL.
An operator shouldn't need either.

## What the screens show

### When this was written (the Supervisor)

The Supervisor had a learner list (`LearnerList`), a session list (`SessionList`), waiting streams (`WaitingStreamList`), turns (`TurnList`, `TurnTranscript`, `FlowChart`), a process header (`ProcessHeader`) and, while stepping, the stage decision pane (`StageDecisionPane`).
All of it was organised around **sessions** (learning attempts).
Nothing was organised around **streams**, which is what an operator watches.
After a binding was promoted, a stream that was served badly left no trace in the Supervisor at all.

### Now (the final UI)

From `stroom-core-client/src/main/java/stroom/shapeshifter/client/component/`; see [ui-design.md](smart-pipelines/ui-design.md) for the full description.

- **The list of Smart Pipelines** (`FeedList`), on a Learning Flow's Pipelines tab and on Monitoring > Smart Pipelines: the pipeline, a chip (*Translating*, *On trial*, *Below par*, *Learning*, *Needs you*, *Stopped*, *Off*, *Deleted*) and a line of detail.
- **A pipeline's Learning Status tab** (`FeedDetail`): *Where it stands* as a chip, a sentence and a sentence of detail (`FeedSituation`); a stopped run's **Details** and **Suggestions** (`StopReport`); the actions that fit; *Translating with*; *Flagged by QA*; *Note to the AI*; and, under *Learning history*, the run chooser, **Progress** over the flow's steps (`FeedProgress`), the **conversation** (`ConversationList`) and the **waiting streams** (`WaitingStreamList`, with a *Tries* column).
- **A pipeline's Quality Assurance tab** (`QualityTab`): figures, volume day by day, what its records became, samples, assessments and each run's score.
- **The readiness banner** (`Readiness`) on the Pipelines tab: whether the Learning Flow will learn anything at all.
- **The stage decision pane**, while stepping, as before.

The screens are still organised around learning runs, but the Quality Assurance tab now gives a per-day view of what was served.
What is still missing is a per-**stream** view: in NETFILTER's case, the empty stream 2135 would show only as a lower volume on the Quality Assurance tab, and *Where it stands* would still say *Translating*, because the server doesn't yet treat it as drift (see the drift bug).

## Proposals

They are ordered by how much they help against how much they cost.
Items 1–3 need little or no new data.

### 1. A plain-language status line per learner

**Status: largely done.**
*Where it stands* on the Learning Status tab is this status line: a chip, a sentence and a sentence of detail, with the actions that fit as buttons.
It is worked out in the client (`FeedSituation`) from the learner row and its latest run, not on the server from the last few outputs and deferrals as proposed, so it can't yet say that the last stream produced nothing.
That part moves to proposal 6.

The original proposal: one or two sentences, built on the server from the learner state, the last few outputs, the open attempt and the deferrals.

```
NETFILTER supervised — Serving "NETFILTER supervised learned" (learned 09:21 from stream 2118).
⚠ The last stream (2134, 09:29) produced no events: the parser matched none of its 30 lines.
  Nothing has been done about it.                                   [Relearn]  [Open stream]
```

```
GATEWAY-AUDIT supervised — Waiting for a person.
Session 14 stopped at step "split": no record boundary after 5 candidates
(batch ✗ wholeness, authentication ✗ wholeness, batch ✗ wholeness, export ✗ root, ✗ not a name).
The model said: "No single element name fits. The records are the mixed children of batch …"
1 stream waiting (2117, retried 34 times).                 [Open session]  [Retry]  [Give up]
```

These sentences should read as answers to the three questions above.
They come from a small set of server-side rules (see 6), so they stay consistent and can be tested.

### 2. A *Streams* tab beside the conversation and *Waiting streams*

**Status: open.**
The Quality Assurance tab's volume (records a day) and samples cover part of it, but nothing lists streams one by one.
In the final UI it fits as a third tab under *Learning history* on the Learning Status tab, beside the conversation and the waiting streams; the Quality Assurance tab could link to it from a day in the volume table.

One row per stream the learner has handled, newest first:

| Stream | Received | Outcome | Records in | Events out | Score | Translation | Note |
|---|---|---|---|---|---|---|---|
| 2134 | 09:27 | **Produced nothing** | 30 | 0 | — | NETFILTER supervised learned | parser: unmatched content, line 1 |
| 2118 | 09:18 | Learned and served | 30 | 29 | 1.0 | NETFILTER supervised learned (new) | 1 record dropped: line 25 |
| 2117 | 12:05 (10 Nov) | Waiting | — | — | — | — | session 14 parked |

- **Outcome** is one of: *Learned and served*, *Served*, *Served with losses*, *Produced nothing*, *Waiting*, *Deferred*, *Given up*.
- **Records in / Events out** makes dropped records visible at a glance.
  *Records in* needs the input count that `docs/learning-flow-parser-drift-not-detected.md` proposes to add to `ServedStream`.
  Until that exists, show *Events out* together with the number of parser errors.
- The **Stream** and **Translation** cells are links: to the data browser (raw, events, error) and to the fragment pipeline.
- The data comes from `shapeshifter_output` (already one row per served stream, and since the UI merge with a `record_count`), `shapeshifter_deferral` and `shapeshifter_attempt`.
  The output row should store this run's own score and counts, not the binding's score; see the bug write-up.

### 3. Make the run's decision say why

**Status: largely done.**
The UI merge added stop reports (`StopReports`, stored in `shapeshifter_attempt.report`): every way a run can stop has an error line, details and suggestions, and the Learning Status tab shows them under *Where it stands*, with buttons for suggestions it can act on (change the limits, the learning steps or the model).
The progress strip (`FeedProgress`) shows each step's tries and where the run went back, and the conversation shows each answer and how it was judged.
Check that the reports cover the cases below, which are what made GATEWAY-AUDIT hard to read; any that don't are what is left of this item.

**Checked, 19 November 2026 (Track 1 task 6):**

- *Every candidate failing the same way*: covered. The details count each way the answers fell short and give the closest score, and the first suggestion says "Every answer fell short the same way, so more tries may not help" (`StopReports.spent`).
- *The model's refused reply*: was covered only when the **last** answer was refused. Fixed: the latest refused reply is now quoted even when the model tried again after it (`TestStopReports.aRefusedReplyBeforeTheLastAnswerIsStillQuoted`).
- *A truncated sample*: **not covered**. Nothing tells the stop report that the sample was cut. Left for Track 1 task 9, which changes how the sample is cut (N2 option F: one setting in bytes, the 6,000-character prompt cap removed), so the note is written once, against the new cut.
- *Marking the stopped step and each candidate's outcome in the progress strip*: not checked here; it is client work.

`No record boundary after 5 candidates` says *that* it stopped, not *why*.
The reasons are already stored, as the feedback carried into each re-ask.
Show them:

- In the stop report's details, the last candidate's main shortfall.
- Where every candidate failed **the same way**, say so, because that suggests no answer can pass:
  *"All 5 candidates were rejected for wholeness (e.g. `transfer` holds 41% of the document's text and `authentication` 20%; 90% is needed). The records may not share one element name."*
- In the progress strip, mark the step where the run stopped, and the outcome of each candidate on it (✗ wholeness, ✗ wholeness, …).
- When the model's reply is **refused because it isn't in the asked-for form**, show that reply prominently.
  A model that answers *"no single element name fits, because …"* is often explaining exactly what is wrong.
  In GATEWAY-AUDIT's session, that explanation was the best diagnosis available, and it was visible only by opening turn 6 (in the final UI, opening question 6 of the conversation).
- Say when the sample a step judged was **truncated** (`sampleSizeLimit`), and by how much.
  "`batch` occurs once" meant "once in the first 8,192 characters", which no one reading it could have known.

### 4. Error-stream messages that stand on their own

**Status: open.** Server only; the UI merge didn't change these messages.

The Error stream is what most operators look at first, so its `[Shapeshifter AI]` lines should give the whole picture:

| Today | Proposed |
|---|---|
| `Served by binding f123a0c8-…` | `Served by "NETFILTER supervised learned" (learned from this stream, score 1.0): 29 of 30 records became events; 1 dropped (line 25, see below).` |
| `Bound to fragment NETFILTER supervised learned, which produced nothing for this stream` | `"NETFILTER supervised learned" read none of this stream's 30 records (parser: unmatched content from line 1). The format may have changed. Deferred for relearning.` (once the drift bug is fixed) |
| (nothing, for a stream that waits) | `Waiting: learning run 14 for this pipeline is paused for a person (stopped at "split"). See the pipeline's Learning Status tab.` |

Each message should name the translation, give counts, and say what happens next.

### 5. Correct the waiting reason

**Status: done and checked, 19 November 2026 (Track 1 task 6).**
`Stage.waitingFor(...)` words the reason by what the claim holder is doing, and "Another node is learning…" is gone.
All states are covered by `TestStage` (paused for a person, waiting for the model, in progress on a node, awaiting review, and a claim released before it was read).
The paused and awaiting-review reasons sent people to "the document's Feeds tab", which no longer exists; they now name the pipeline's Learning Status tab.
The waiting streams list on the Learning Status tab has a *Tries* column.
Check all three states (waiting for the model, in progress, parked); a warning when a stream keeps being retried against a paused run is still open.

As written: `Stage.recording(...)` (`Stage.java`, around line 1170) gives the reason `"Another node is learning for this pipeline; this stream waits for what it learns"` whenever `attempts.opened(...)` finds the claim taken.
That is also true when the claim is held by a **parked** attempt.
In that case nothing is learning, and the stream will wait until a person acts.
The reason should depend on the claim holder's status:

- *learning*: "Session N is learning on node X; this stream waits for it."
- *parked*: "Session N is parked for a person (stopped at step S: reason). This stream waits until it is answered, retried or given up."

The waiting streams should also show the retry count (done), and warn when a stream keeps being retried against a parked session (open).

### 6. A "what might be wrong" check, server-side

**Status: open.**
The final UI has two checks of its own, neither of which is this: the readiness banner checks the Learning Flow's settings (learning on, a model, quality checks, no problem in the steps), and the Quality Assurance figures flag a translation scoring below its Learning Flow's flag.
Neither looks at what happened to recent streams.

A small rule set, run when the Learning Status tab loads a pipeline, returning findings with a severity, a sentence and a suggested action.
These findings would feed *Where it stands* (1) and the Quality Assurance figures, and also be available through the API and MCP, so that tools and agents see the same thing.
A suggested action should name one of the Learning Status tab's actions, or a section of the Setup tab, as stop-report suggestions already do.

| Rule | Example finding | Suggested action |
|---|---|---|
| Last N streams produced no events, or dropped more than X% of records | "The last stream produced no events; the parser matched none of 30 lines." | Relearn |
| A session stopped with every candidate on one step failing the same way | "Every split candidate failed wholeness: the records may not share one element name." | Read the session; edit guidance; report |
| A stream waits on a parked session | "Stream 2117 has waited 21 h on parked session 14." | Retry / give up / answer |
| Spend breaker open or token budget spent (`shapeshifter_spend`) | "Learning is paused: the token budget for this window is spent." | Raise the budget, or wait until a time |
| The model can't be reached, or its secret is missing | "The model call failed: no secret named learning-flow." | Create the secret |
| No processor filter, or a disabled one, for the learner's pipeline | "Nothing feeds this pipeline: no enabled processor filter." | Open processors |
| Output event-logging version older than installed | "Events are written as event-logging 3.0.0; 4.1.0 is installed." | Note to the AI |
| Events dated far from the stream's receipt time | "Events are dated 2023 for a stream received in 2026." | Note to the AI; learn again |

The last two are content checks rather than status checks, but both were found in the same session, and both are cheap to compute.

### 7. Get to a pipeline's learning from where people already are

**Status: largely done.**
The Learning Status tab is on the pipeline itself, so there is nothing to get to.
Selecting an AI Translator on the Structure tab shows *What it learned*, with *Open its Learning Status*.
A Learning Flow's Pipelines tab and Monitoring > Smart Pipelines open a pipeline at its Learning Status tab.
What is left:

- Data browser: on a stream processed by a Smart Pipeline, a link from the `[Shapeshifter AI]` error lines (or a stream info row) to that pipeline's Learning Status tab, with the stream selected in the *Streams* tab (2).
- Feed and processor screens: the pipeline's chip and *Where it stands* sentence (1) for any pipeline that has an AI Translator element.

### 8. The same through the API and MCP

The Stroom MCP server can't currently read anything Shapeshifter-specific.
The Supervisor's REST resource (`SupervisorResourceImpl`) already serves the learner list, a pipeline's learners (`pipelineLearners`), the runs and the turns, and `QualityResource` serves the quality report, samples and assessments.
Exposing these through MCP tools, as `getLearnerStatus`, `listLearnerStreams` and `getSession`, would let an agent answer *"what is going on?"* without database access.
Those tools should return the same findings as rule set 6, and the stop report of a stopped run, so that an agent and the Learning Status tab say the same thing.

**Status: open.**

## Notes for implementation

- Follow `stroom-gwt/UI-GUIDE.md`: a new panel (the Streams tab) is a dumb component with a controller, inside the Learning Status tab's *Learning history*.
  Use Stroom widgets, keep styles in the component's own stylesheet, and add workbench stories (default, read-only and the empty/no-data case) to `PipelineAiTranslationStories`, using the fixtures in `ShapeshifterAiFixtures`.
- Build the findings on the server and test them there, with one test per rule.
  The client only renders them, beside what `FeedSituation` already says.
- *Records in* depends on the input count proposed in the drift bug, so do 2 and 4 after, or together with, that fix.
- Items 3 and 5 are largely done; what is left of them is checks and small gaps.
- These are additions within the final UI design, not changes to it.
