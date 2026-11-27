# Learning Flow: what to do with a paused run

*Written 26 November 2026 · branch `ai_translations_server`*

A Smart Pipeline's learning run can be **paused** (the code's term is *parked*), waiting for a person.
Two things a person reasonably tries on a paused run do not do what they appear to. This note describes
both, explains why from the code, and proposes fixes. No code has been changed yet; the decisions are
the owner's.

## How a paused run behaves today

- A run pauses when it runs out of its time or token budget, when its learning flow sends it to a person,
  or when it has gone 10 rounds without the stream passing (`Learning.MOST_ROUNDS`). The Learning Status
  tab shows "Paused: *reason*", with the run's report and suggestions beneath.
- **A paused run keeps hold of its pipeline.** It is still one of the "open" statuses
  (`AttemptsDao.OPEN`), so a new stream for the pipeline cannot start another run: it waits for the paused
  one (`Sessions.java`, "Another session holds the learner's claim ..."). A paused run never times out.
- **Only three actions reopen a paused run** for the *Learning Flow Deferred Learning* job
  (`SupervisorActions`): **Give it more…** (`grant`), **Answer it myself** (`amend`) and
  **Finish with Unknowns** (`exhaust`). Each ends by calling `carriedOn`, which reopens the run.
- **A resumed run reads the Learning Flow's current limits.** In `Conversation.java`:
  - time: `allowedMs = policy.getAttemptBudgetMs() + grantedMs`, measured afresh for each pass, so a
    resumed run "has the whole of it again" (the Setup tab's own help text);
  - tokens: `allowed = policy.getTokenBudget() + grantedTokens`, measured over the run's whole life.
- **Give it more… refuses an empty grant**: `SupervisorActions.grant` throws unless tokens or time is
  more than zero.

## Problem 1: raising a limit on the Setup tab does not unpause the run

**What the user sees.** A run paused for time shows two suggestions (`StopReports.outOfTime`):
"Give it more time." and "Raise 'Time for each run' on the Setup tab." The second is a link. It opens
the Learning Flow document on the Setup tab with the focus on *Time for each run*, which works. The user
raises the limit and saves. The run stays paused, and nothing says why or what to do next. The token
suggestion (`StopReports.outOfTokens`) has the same shape.

**Why.** Saving the document changes the limits the run *would* use when resumed, but nothing resumes
it. Raising the limit is the right advice; the missing piece is a way to carry the run on afterwards. The
only way today is **Give it more…** with an invented amount, because an empty grant is refused.

**Proposed solutions.**

| Option | What changes | For | Against |
|---|---|---|---|
| **A. A "Carry on" action** (recommended) | A new action on a paused run that reopens it on the current settings with no grant: `SupervisorActions.resume` calling `carriedOn`, a `SupervisorResource` endpoint, a `FeedAction.CARRY_ON` offered when the run is paused (`FeedSituation`), and a matching `SuggestionAction`. The suggestions become "Raise 'Time for each run' on the Setup tab, save, then press Carry on." For a run paused for tokens, the server refuses Carry on while the token limit is still at or below what the run has spent, saying so, instead of reopening it only to pause again at once. | Explicit; the person decides when spending resumes. Reuses `carriedOn`. Also useful for a run paused for time with nothing changed, since a resumed pass gets the full time again. | One more button on an already busy paused state. |
| B. Resume on save | When a Learning Flow is saved with a raised time or token limit, reopen its runs that paused for that limit (found with `Attempts.forDocument` and the report's `StopCause`). | No extra click. | Saving a document starts spending on the model without a separate confirmation; it is also surprising for runs whose owners did not expect it. Needs care to touch only runs paused for the limit that was raised. |
| C. Reword only | No behaviour change. The suggestions say that the Setup limit applies to later runs, and that **Give it more…** is what resumes this one. | Smallest change. | Leaves the person inventing a grant amount to resume a run that the new limit already covers. |

## Problem 2: "Learn again" on a paused pipeline does nothing

**What the user sees.** "More… → Learn again" is offered on a paused pipeline (`FeedSituation`, offered
whenever the pipeline is live). After confirming, the status stays "Paused".

**Why.** `SupervisorActions.relearn` only marks the pipeline's learner for relearning
(`states.markForRelearning`). The mark takes effect when the next stream starts a run, and that cannot
happen while the paused run holds the pipeline. "Stop learning…" has the same limitation: `giveUp` marks
the learner given up, but the paused run stays open until something resumes it, when
`Learning.whyNotToCarryOn` finally closes it.

**Proposed solutions.**

| Option | What changes | For | Against |
|---|---|---|---|
| **A. Learn again discards the paused run** (recommended) | On a paused pipeline, Learn again closes the paused run as `ABANDONED` (its record and turns kept, with "Discarded by *user*: learn again" as the reason), then marks the learner as today. The confirmation text says the paused run is discarded. Stop learning does the same for its reason. | Does what the person asked. The next stream starts afresh. | Loses the paused run's progress, which the confirmation must make plain. |
| B. Hide them while paused | `FeedSituation` stops offering Learn again and Stop learning while a run is paused. | No behaviour change on the server. | The person still cannot start afresh without first resuming or finishing the paused run. |

## Recommendation

Problem 1, option A, and problem 2, option A. Together they leave a paused run with clear ways forward:
carry on as it is, carry on with more, answer for it, finish with Unknowns, or throw it away and start
again.

## Tests needed for any of these

- Server: a run paused for time is reopened by Carry on with no grant, and a run paused for tokens is
  refused while the limit is at or below its spend (scenario tests alongside
  `TestScenario61AParkedSessionGivenMoreToSpendCarriesOn` and
  `TestScenario62AParkedSessionSentToTheExhaustionStepCarriesOn`).
- Server: Learn again on a paused pipeline closes the run as abandoned, and the next stream opens a new
  run.
- Client: `TestFeedSituation` for which actions a paused run offers, and the suggestion wording.
- Workbench stories for the paused state with the new action, run with the leak check
  (`stroom-gwt/stroom-gwt-workbench/WRITING-STORIES.md`).
