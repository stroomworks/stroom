# Shapeshifter AI: open issues

What is known to be unfinished or undecided, with where the detail lives. Rulings are cited by number
(design 01 §13, design 04 §10, design 05 §9). Close an entry by striking it through with the date and
the commit or ruling that closed it, rather than deleting it.

## Needs a ruling

1. ~~**Whether deferred tasks count against a filter's task budget.**~~ *Ruled 2026-10-07: every filter counts `DEFERRED` with
   `CREATED` (A109), made safe by one filter per feed and type (A104); built 2026-10-08 in design 06 phase K.* Task creation bounds itself on
   `countTasksForFilter(filter, CREATED)`, so `DEFERRED` tasks are outside the budget: the deferred
   population is bounded by what arrives, and a wake or a sweep can return a backlog to `CREATED` far
   past the limit, after which creation pauses until it drains. Options: count `CREATED` and `DEFERRED`
   together, which brings back the stall design 04 §5.2 avoided; or a separate per-filter cap on deferred
   tasks past which creation pauses. Recommended: leave it until the real-scale trial shows whether the
   deferred population matters. *Design 04 §5.2 and §9.*

2. **A format change under a binding goes unnoticed.** What a parser drops while a stream is served — lines
   its splitter does not match — is reported on the error stream but judged by nothing, since the record
   bar judges records and these never became records. A feed that changes its format under a binding is
   not sent back to be learned until a person marks its learner (A100). *Design 04 §10, A99's note.*

3. **Improving a serving binding is still synchronous.** The Supervisor's *improve* is the one place the
   model is asked outside the worker (A102): it learns against the binding's accepted corpus, which the
   worker cannot read back as a stream. Moving it to the worker needs the attempt to carry its own
   sample — a change to the attempt row. *Design 04 §10, A102.*

17. ~~**Whether an attempt's time budget is per carrying or per attempt.**~~ *Ruled 2026-10-08: per run, said
    plainly (design 06 A145); the grant dialog is built in phase L slice 4.* Tokens are counted over the
    attempt's life: each carrying starts from what was already spent. Time is counted from when the
    carrying began (`Recorder.startedMs`), so a session parked for running out of time gets a full
    `attemptBudgetMs` again on its next carrying. Any grant reopens it, even one of tokens alone, and
    the person granting is not told the clock restarts. A time grant only matters to a single carrying
    longer than the whole budget. Options: keep time per carrying, which bounds how long one run holds a
    worker, and say so in the Supervisor and the grant dialog; or count it per attempt, which needs the
    attempt's start or time spent kept on the attempt row. Recommended: per carrying, said plainly.
    *Design 04, A5 and A77; item 5.*

## Small fixes

4. ~~**The lease can lapse during a long model call.**~~ *Closed 2026-10-06: `HeartbeatingAdvisor` pushes
   the claim out every third of the lease while a question is with the model, design 04 §4.* A session's claim is pushed out before every
   question but not during one, so a single call longer than the lease (ten minutes by default) lets
   another node take the attempt and ask the same question. A heartbeat on a timer during the call fixes
   it. Worth doing before anything runs unattended. *Design 04 §4.*

5. ~~**Grant asks for tokens only.**~~ *Closed 2026-10-08 in design 06 phase L slice 4: a dialog of tokens
   and minutes for each run (A145).* The resource takes tokens and milliseconds; the Supervisor's button
   prompts for tokens and always sends no time. Fix: a two-field grant dialog, tokens and minutes,
   after the built-in text popup. What its time field means waits on item 17. *Design 04, phase 6
   first slice (A77).*

6. ~~**A session sent to the exhaustion step shows no mark** on its row in the Supervisor beyond its state,
   though the attempt carries the flag.~~ *Closed 2026-10-08 in design 06 phase L slice 3: the status says
   it was sent until the worker takes it there.* *Design 04, phase 6 third slice (A65).*

7. **An unreadable step edit is lost on the Flow tab.** An edit the form cannot read as a step — an id
   that is not a word, a limit below one — is reported beside the form, and lost if another step is
   clicked before it is put right. *Design 05 §7, slice 3's audit.*

8. **A kind's own checks are not named.** Where no check is ticked, the step's panel says it is judged by
   its kind's own checks but cannot say which: that table lives in the engine. Moving it into the shared
   model would let the engine and the panel read one table. *Design 05 §10.*

9. ~~**A multi-part step's pass is highlighted early.**~~ *Closed 2026-10-08 in design 06 phase L slice 3:
   a unit's pass followed by the step's next unit takes no edge.* On the Supervisor's path over the flow,
   a step of several units — a Configure over the whole chain, a Target over several kinds — records a
   turn per unit, and the first unit's pass lit the step's pass edge. *Design 05 §7, slice 2's audit.*

10. **A marked learner relearns on its first stream whose prefix is big enough.** A task holds only its
    stream's prefix, so the mark path judges that. Noted rather than a fault. *Design 04 §10, A100.*

20. **Two scorer types have no scorer.** `ERROR_LOAD` and `EVENT_CLASSIFICATION` are in `ScorerType`, with
    parameters and a check each, but nothing implements them, and a document naming either is refused
    when its scorecard is built ("No scorer is installed"). Since 2026-11-02 the editor offers neither
    (`ScorerType.isBuilt`), and shows one only where a document already names it. Build them, or take
    them out of the model. *Design 01 §8.4 and §12 item 5.*

22. **A document with no scorers judges nothing.** Only the scorers a document lists are applied, compile
    included, so a new document, which has none, passes every candidate and every record, and nothing says
    so. Fix: warn on the Score tab and refuse to learn with an empty set, or start a new document with the
    demo set. *design/06-scoring.md §6.3, design/10-status-and-future.md §10.4.*

23. **A19's refusal of `ignoreErrors` is not built.** The built-in question text forbids it; nothing refuses
    a Data Splitter configuration that sets it. Fix: refuse it as `compile-failed` in the same static check
    as item 21. *design/09-rulings.md A19, design/10-status-and-future.md §10.4.*

24. **`regressionRetentionDays` is read by nothing,** though the Score tab's help text says it sets how long
    regression records are kept. The corpus follows the source feed's retention (A18). Recommended: remove
    the field. *design/06-scoring.md §6.8, design/10-status-and-future.md §10.4.*

25. ~~**Stale texts.**~~ *Closed 2026-11-02: each rewritten, and `Decision.Retracted` removed.* The Retract button's tooltip says what it produced is asked to be processed again,
    which A129 ended; the Deferred Learning job's description speaks of a deferred execution mode; the
    `attemptRetention` description speaks of retraction asking for reprocessing; `Decision.Retracted` is
    constructed by nothing; class comments say "deferred mode's worker". *design/10-status-and-future.md §10.4 lists each.*

## For the real-scale trial

11. **The backlog pages in memory,** reading every readable document's rows, and its "raised" count is
    the page total rather than the raised rows. *Design 04, phase 6 first slice's audit.* A learner's
    waiting streams are now a page read in the database (design 06 phase L slice 2a), and the backlog list
    went with the client's move to them in 2b; its endpoint stays only for the count in the document's
    header, which still reads in memory. The learning pipelines list that replaces it groups the whole
    attempts table for each page and again for its total; the trial should watch that query once a
    cluster has kept a season of sessions.

21. **A stylesheet the model writes is not checked before it runs.** On a node a candidate runs in the
    real `XSLTFilter` with the whole `stroom:` function library, so a stylesheet could call `http-call`,
    `fetch-json` or `ask-ai` while it is judged, and go on calling it once promoted. A147 rules a static
    check instead of a restricted library at run time: every candidate stylesheet is refused, as
    `compile-failed` with the reason, if it calls any extension function or names `document()`,
    `unparsed-text()`, `collection()`, `xsl:include`, `xsl:import`, `xsl:result-document` or an external
    entity; the same check is made again at promotion. `XsltStep`, the Tier 1 stand-in, already refuses
    all of these by configuring Saxon; the check is to do the same to the text, so that it holds whichever
    runner judges the candidate. A stylesheet a person edits by hand afterwards is theirs, like any other.

## Before a pull request

12. **The full build and the Docker images** — `./gradlew clean build shadowJar buildDistribution` and
    `./buildDockerImages.sh` — have not been run since phase 6 began.

13. **One live-model run** against the real model, since the flow, its exits and the Chain step's
    elements have all changed under the harness. *Design 02 §6.3.*

14. **A changelog entry,** through `./log_change.sh`, which needs a GitHub issue number.

18. ~~**The Shapeshifter AI migrations are numbered for 7.13.**~~ *Closed 2026-10-07: the twenty became one,
    `V08_00_00_001__shapeshifter_ai.sql`, in phase J slice 1.* The branch targets Stroom 8.0, but its
    twenty migrations are `V07_13_00_001` to `V07_13_00_020`. Phase J rewrites them (A111) and renumbers
    them `V08_00_00_*` as it does. The Pipeline Factory's is already `V08_00_00_001`. *Design 06 §7.*

## Walkthroughs

15. **Phase 6's exit:** a person takes a feed from unseen to served from the UI, watching the backlog form
    and drain, with no error stream written at any point. *Design 03, design 04 phase 6.*

16. **Phase H's exit:** a person composes the escalating example on the Flow tab from an empty flow
    without typing the grammar, and reads a parked session's path in the Supervisor. *Design 03, design 05.*

19. **Phase I's exit:** a person makes a Pipeline Factory over `Raw Events` with an ordinary template from
    the explorer, with a stream filter bounded to the test feeds or by a recent `Create Time` (a new
    factory reads every matching stream already held), enables it, sends data for two feeds, and watches the *Pipeline Factory* job make a
    pipeline and filter per feed, the filters process, and the Made tab list both. Then deletes one
    pipeline, sends more data, and sees it not made again until forgotten. *Design 06, phase I slice 5.*
