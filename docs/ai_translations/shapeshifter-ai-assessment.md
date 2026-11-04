# Shapeshifter AI: assessment of the design

Date: 2026-11-03
Branch: `ai_translations` (one commit ahead of `origin/master`)
Subject: `stroom-shapeshifter/stroom-shapeshifter-ai/design/` (documents 1 to 10), `ISSUES.md`, and parts of
the module source.

## 0. Scope and confidence

This is a review, not a test. It rests on reading all ten design documents and `ISSUES.md`, and on reading
`PipelineStepRunner`, `ModelAdvisor`, `XsltStep`, `Templates` and `QuestionText`, and searching the rest of the
module for timeouts, limits and the handling of `ignoreErrors`. Nothing was built or run. Where a statement is
about code, it says which. Where the design documents and the code might differ, the code was not checked
beyond the files named.

The design's own status (document 10) says it is an MVP prototype, built on fixture data with a scripted
model. The live-model suite (`TestLiveScenarios`) has not been run since the flow last changed (ISSUES 13).
Everything below should be read with that in mind: the design has not yet met real data or a real model in
anger.

## 1. Summary

| Question | Short answer |
|---|---|
| Is the approach sound? | Yes. Configuring once and running at normal speed, with every candidate run and scored, is the right shape |
| Will it work? | Likely for regular feeds; unproven for hard ones, and unproven on a real model |
| Is it easy to set up? | The factory is; the Shapeshifter AI document is not |
| Is it simpler than hand-editing XSLT? | Not for one feed. Possibly for many, but only if most of the settings are hidden behind a safe default |
| Will configuration need tweaking? | Yes, per data source |
| Does it converge? | Mostly. Several gaps are listed in section 5 |
| Is it safe? | Not yet. The central control (A147) is unbuilt, there are no resource limits on candidate runs, and correctness is checked structurally rather than semantically |

The two concerns raised in review are the ones I would weigh most heavily: **user complexity** (section 3)
and **how to establish that it is safe** (sections 6 and 7).

## 2. Will it work?

### 2.1 What is strong

- **The model configures and never translates.** What it writes is ordinary Stroom content. Once accepted,
  every stream runs through it at normal speed with no model involved. This avoids the usual cost, latency,
  non-determinism and availability problems of an LLM in the processing path.
- **Candidates are run, not trusted.** Each answer runs as a real pipeline over the feed's own data and is
  judged by Stroom's validators. The model's opinion of its own work counts for nothing.
- **The questions are small and closed.** Chain, Split, Target, Configure and Admit each have a strict reply
  grammar. This suits how models fail: a closed question with compiler feedback goes much better than a single
  request for a large stylesheet.
- **Learning from a prefix and judging on the whole stream** guards against overfitting to the sample.
- **Deferral rather than error.** A stream that cannot be translated yet waits. Nothing is errored to make it
  wait, and learning never runs on a processing thread.
- **No-regression on a corpus,** with a rule that a translation can never become an `Unknown`.
- **Auditable.** Every session and turn is recorded, every output names the snapshot that made it, and
  as-processed reprocessing reproduces an old result exactly.

### 2.2 What limits confidence

1. **No real-model evidence.** All scenarios use a scripted model. There is no recorded success rate, cost per
   feed, or number of rounds per feed on real data.
2. **The scorers measure structure, not meaning.** There are six scorers: compile, input coverage, yield,
   schema conformance, extraction quality and business rules. Only business rules is semantic, and it relies on
   XPath assertions a person writes. An event that is schema-valid, well-formed and non-degenerate, but maps
   the wrong source field, or records a failure as a success, passes all of them. The `TARGET` step helps a
   little, but the same model proposes the target it is later checked against.
3. **Two scorer types are named but not implemented** (`ERROR_LOAD`, `EVENT_CLASSIFICATION`). The latter is
   the one most relevant to semantic correctness.
4. **The hard cases are the motivating cases.** Document 1 motivates the work with feeds whose stylesheets run
   to thousands of lines. Those have conditional logic, reference-data lookups and context-dependent meaning.
   They are the least likely to be learned well from an 8 KiB sample, and they are not what the fixtures
   exercise.

My expectation: reliable on simple, regular, single-line-record feeds; mixed on multi-line, nested or
conditional ones; and untested on the long tail.

## 3. User complexity

### 3.1 The concern

The worry is that Shapeshifter AI asks the user to understand and tune so many things that it is harder to use
than writing the XSLT by hand. I think that is a fair concern, and in its current form I think it is partly
right.

### 3.2 What the user has to deal with

Counted from the design documents (by my count; the exact number depends on how settings are grouped):

**To get a feed translated at all**

- A Pipeline Factory document: enabled, stream filter, key, template, folder pattern, name pattern, element
  property patterns, run-as user, and about nine filter settings.
- A template pipeline containing the Shapeshifter AI element, a writer and an appender.
- A `stroom-ai` model document and a stored secret.
- The processor setting `claimTasksOnWorker`, which must be on. While it is off the processor refuses to
  create a filter over a pipeline that may defer. Nothing about this is discoverable from the screens.
- A Shapeshifter AI document.

**The Shapeshifter AI document alone** has roughly twenty author-facing settings:

| Group | Settings |
|---|---|
| Model and text | model, `instructions`, allowed elements, template overrides (about ten templates) |
| Flow | the learning flow itself: a graph of steps, each with kind, role, guard, candidates, kinds, elements, checks and one transition per outcome |
| Sampling | `sampleSizeLimit`, `heldOutFraction`, `maxAttempts` |
| Scoring | up to six scorers, each with weight, threshold, gate and its own parameters; `promotionFloor`, `minRecordsToLearn`, `regressionCap`, `relearnThreshold`, and a `regressionRetentionDays` that does nothing |
| Cost and time | `tokenBudget`, `attemptBudgetMs`, `maxDeferralAgeMs` |
| Behaviour | `promotionMode`, learning mode |

**Node configuration:** `deferredLearningBatchSize`, `sessionLease`, `attemptRetention`, two caches,
`deferredTaskRetryDelay`.

**At run time** the Supervisor offers roughly a dozen actions: learn again, improve, give up, accept, retract,
approve, reject, grant, exhaust, answer instead, guidance, and withdraw guidance. Each has consequences the
user must understand: for example, *reject* makes the learner give up, whereas *retract* does not.

### 3.3 Why this is more than it needs to be

- **The learning flow is exposed as an editable graph with its own grammar.** It is powerful, and it is
  appropriate for the people building Shapeshifter AI. It is a poor thing to ask a feed owner to reason about.
  Three example flows exist because no single one works for every feed, which is a sign that the choice
  belongs to the system, not the user.
- **Several settings are really implementation detail** that a user cannot reasonably choose well:
  `heldOutFraction`, `sampleSizeLimit`, `regressionCap`, scorer weights, and the lease.
- **Some settings interact.** A yield `expectedRatio` that is wrong makes the `yield-short` outcome fire,
  which in the escalating flow sends the session back to Split. The user cannot see this from the setting.
- **A new document judges nothing** (ISSUES 22). The blank state is the unsafe state, and it is silent.
- **There are dead and half-built controls:** `regressionRetentionDays`, two unbuilt scorer types, and the
  `ignoreErrors` rule that is stated but not enforced.

### 3.4 Is it more work than editing XSLT by hand?

It depends on how many feeds there are and on how much of the above the user must touch.

- **One feed, one expert:** hand-written XSLT is probably quicker and certainly more predictable. The user
  already knows XSLT and Data Splitter, and the result is exactly what they wrote.
- **Many feeds, similar needs:** the document is set up once and shared by every pipeline made from the
  template. The per-feed cost then falls to approving or correcting what is learned. This is where the
  design could pay off.
- **Many feeds, varied needs:** the user is likely to end up tuning scorers and leaving guidance per feed,
  and debugging why the model's output was accepted or rejected. That is expert work in a less familiar
  vocabulary, and it may exceed the cost of writing the XSLT.

There is also a hidden cost: **reviewing generated XSLT is not free.** Someone has to trust, or check, a
stylesheet they did not write. If they cannot check it, they are relying on the scorers (section 2.2, point 2).
If they can, they are doing a large part of the work they hoped to avoid.

So the honest position is that the design only wins if most of this surface is hidden from most users. That is
a product decision that has not yet been made.

### 3.5 Recommendations on complexity

1. **Ship one opinionated default and hide the rest.** A user should see: the model, an on/off switch, the
   promotion mode (defaulting to review), a token budget, and a short list of feeds. Everything else belongs
   behind an "Advanced" section that is closed by default.
2. **Make the blank state safe.** Start every document from the demo scorer set, or refuse to learn with an
   empty set. Warn loudly on the Score tab.
3. **Remove or hide what does not work.** Drop `regressionRetentionDays` and the two unbuilt scorers (ISSUES
   20, 24), and enforce or remove the `ignoreErrors` rule (ISSUES 23).
4. **Choose the flow automatically,** for instance starting with Direct and escalating on failure, rather than
   asking the user. Keep the flow editor for administrators.
5. **Replace scorer tuning with named profiles** such as "strict" and "lenient", with the numbers visible but
   not required.
6. **Cut the setup steps.** A single "Set up Shapeshifter AI for these feeds" action should create the model
   reference, template, factory and document, and check `claimTasksOnWorker`. The demo pack already shows what
   a complete set looks like.
7. **Make the review screen the main interface.** Most users should only ever see "here is what was learned,
   here are sample inputs and outputs side by side, approve or reject".
8. **Measure it.** Time a person doing a real feed by hand against time to a trusted result through the system,
   including review, on several feeds. If the system is not clearly faster for the median feed, the
   complexity is not justified.

## 4. Setup effort and tuning

Expect tuning, at least at first:

- the yield `expectedRatio` for multi-line, header-heavy or fan-out formats;
- thresholds and the promotion floor (default 0.9);
- `requiredFields` and business-rule assertions, which are the only semantic check;
- `minRecordsToLearn` (default 10), which decides whether a binding is provisional;
- token and time budgets, and `maxAttempts`;
- the choice of flow;
- `sessionLease`, which must exceed the slowest model call;
- guidance for particular feeds.

## 5. Convergence and stability

**What helps**

- each transition fires at most once per session, so flows terminate;
- sessions are limited to ten rounds and each step to a fixed number of candidates;
- one claim per learner, with a heartbeat and a lease;
- bindings may not regress on the corpus, and a translation may not become an `Unknown`;
- `relearnThreshold` only flags, so a person decides.

**Gaps**

1. **Nothing bounds a feed that keeps failing across sessions.** A67 says the spend limit and deferral age do.
   The spend counter is written but never read, and the deferral age only flags. A feed can cycle through learn,
   park and learn again, spending tokens each time.
2. **The corpus is capped at 100 entries per binding, oldest dropped first.** After the cap, "no regression"
   covers only recent records, so a rare record type learned early can fall out and later regress without
   anything noticing.
3. **Format drift under a binding is not scored** (ISSUES 2). Lines the parser drops while serving are
   reported but not judged.
4. **One failing record defers the whole stream** and marks the learner for relearning. On noisy real data
   this may cause frequent deferrals, while streams carrying the new record type wait.
5. **A mediocre binding can stick.** A candidate need only clear the floor and do no worse than the incumbent.
   A provisional binding is never retracted by the gate (A101).
6. **Hand edits and relearning interact.** A hand-edited XSLT is snapshotted and the next relearn rewrites from
   it (A72). Whether this behaves well at scale is untested, and "apply to all made" is undecided (A122).
7. **Deferred tasks count against the filter's task budget** (A109). The design flags this for the trial.

## 6. Safety: where the design stands

### 6.1 What "safe" has to mean here

It helps to separate the properties, because the design is stronger on some than others.

| Property | Question |
|---|---|
| **Containment** | Can anything the model writes reach outside the data, such as the network, files or other documents? |
| **Integrity of events** | Do the events say what the source data says? |
| **Availability and cost** | Can a feed, or an attacker, exhaust threads, memory or tokens? |
| **Confidentiality** | Where does the data go, and who can see what is learned? |
| **Control** | Can a person see, stop and undo everything it does? |

### 6.2 Containment

- **Built:** the model has no tools and takes no actions. Its output is parsed against a strict grammar and run
  as configuration. Tier 1's `XsltStep` refuses `document()`, `unparsed-text()`, `xsl:include`, `xsl:import`,
  external entities and extension functions.
- **Not built (A147, ISSUES 21):** on a node, a candidate is run by the real `XSLTFilter` with the full
  `stroom:` function library. A stylesheet could call `http-call`, `fetch-json` or `ask-ai` while it is
  judged, and keep calling it once promoted. The design says this must be done before real data, and I agree.
- **Not built (A19, ISSUES 23):** the prompt forbids `ignoreErrors` in Data Splitter, but nothing refuses it.
- **Important caveat:** because Tier 1 stand-ins already refuse these things, the test suite would not reveal
  the gap on a node. `TestStepRunnersAgree` is the right place to catch it.

### 6.3 Resource safety

In the files I read, and in a search of the module, I found no timeout, deadline or interrupt for a candidate
run or a fragment run. The only bound is a cap of 10,000 captured records (`MOST_RECORDS`). A Data Splitter
expression with catastrophic backtracking, or a stylesheet with unbounded recursion, would tie up a worker
thread. I did not audit Stroom's own regex or Saxon limits, which may supply some protection that I have not
seen. The design documents do not discuss this.

### 6.4 Prompt injection

The design set does not mention prompt injection at all. What exists is containment that helps by accident.

**What helps**

- The model cannot act. At worst it writes configuration, and that is scored.
- Guidance can only come from people with Edit permission on the document, is attributed, and is placed in the
  system message.
- The model is used only at learning time, so a successful injection cannot touch later streams directly.
- The model is reached through `stroom-ai`, which guards the base URL against SSRF.
- Every model call is audited, with its token count and outcome, though not the sample.

**What does not**

- **No instruction-versus-data boundary.** In `QuestionText`, the sample is wrapped in a Markdown code fence and
  nothing more. A fence is not a trust boundary because the sample can contain its own fence markers, and the
  system text never says that sample content is untrusted data.
- **Feed and type names** are interpolated into the prompt unescaped (`headers()`), as are stream attributes
  where they are used. Where feeds can be auto-created from a received name, that is attacker-influenced.
- **Earlier replies are replayed** as assistant messages in every later turn.
- **Semantic poisoning is open.** A crafted log line can try to steer the mapping, for example to label
  attacker activity as routine, and every structural scorer would still pass.
- **Combined with A147 being unbuilt,** an injected line that gets the model to emit a network-calling function
  is the most serious practical risk today.

### 6.5 Confidentiality

Samples are sent to the model unredacted (A146). The reasoning is sound: redaction good enough to protect data
would need a model, and a model asked to translate redacted data loses the context it translates by. The
control is therefore policy only: point a feed at a model its data may go to. Nothing in the design enforces
that. There is also no mention of what the model provider does with the data, which is outside the design but
inside the risk.

The corpus is stored as a stream on the source feed, so it carries the feed's classification and retention.
That is a good decision.

### 6.6 Control and cost

- **Good:** the Supervisor shows every session and turn, a person can answer in place of the model, and every
  automatic act has a manual counterpart. Permissions are checked per document (View to read, Edit to act).
  Review mode makes a person approve every binding.
- **Gap:** no spend breaker (section 5, point 1), and no request-rate limit across documents.
- **Gap:** there is no single "stop everything" control. Learning can be switched off per document, which is
  adequate, but there is no node-wide kill switch described.
- **Gap:** automatic mode promotes with no human involved. That is the mode the design treats as the default.

## 7. How to establish that it is safe

The design describes controls. What is missing is a way to show they work. I suggest the following.

### 7.1 Write a threat model first

One short document covering:

- **Assets:** the data in the feeds, the events written downstream, the Stroom node and network, the model
  credentials, and the token budget.
- **Untrusted inputs:** sample content, feed and type names, stream attributes (receipt headers), and model
  output.
- **Trusted inputs:** the document, the template, and guidance from people with Edit permission.
- **Attackers:** a source system that sends crafted data; a user with limited permissions; a compromised or
  mistaken model; a model provider.
- **For each:** what the attacker can do today, what stops them, and what is not yet stopped.

### 7.2 Close the known gaps, in this order

1. **Build A147 and A19** as a single static check, applied when a candidate is judged and again at promotion.
   Add a test on a real node (not only on the stand-in) that a stylesheet calling each blocked construct is
   refused.
2. **Add resource limits** for every candidate run and every fragment run: wall-clock timeout, memory or output
   bound, and cancellation. Test with a pathological regex and a looping stylesheet.
3. **Refuse to learn without scorers,** and ship a default set.
4. **Build the spend breaker and rate limit,** and test that a flapping feed is stopped.
5. **Add the "data, not instructions" framing** to the system text, and escape or quote feed names and
   attributes. This is cheap, though it is a mitigation and not a control.
6. **Default new documents to review mode,** and require a deliberate, recorded decision to use automatic
   mode on a feed.

### 7.3 Give correctness an independent check

Structural scorers cannot establish that events mean the right thing. Options, which can be combined:

- **Golden events:** a person supplies a few input records and the events they must produce. These become
  business rules or corpus entries that a candidate must meet. This is the strongest option and the most
  work for the user.
- **A second, independent check** such as a differently prompted model, or one shown only the event and the
  record. It is advisory only (A23), and it is itself open to injection, so it supports a human decision
  rather than replacing it.
- **Sampling in production:** a person reviews a random sample of served events for each new binding, with the
  record and event side by side.
- **Rules for important fields,** for example that a user and a timestamp must be present and plausible.

### 7.4 Test the safety properties directly

Create a **hostile corpus** and run it as part of the build:

- records that contain instruction-like text ("ignore previous instructions", fake fence markers, fake
  "feedback" blocks);
- records that try to elicit extension functions, entity declarations, `document()` and `xsl:include`;
- records engineered to cause regex backtracking and deep recursion;
- oversized records and streams, and many small streams;
- feed names and attributes containing control characters and instruction text.

For each, the pass condition is stated in advance: refused at the static check, bounded by a limit, or
rejected by a scorer, and never executed or promoted.

Run the same corpus against the live model, because a scripted model cannot show how a real one behaves.

### 7.5 Release gates

I would not run it on real data until the following are true. The list is deliberately short and checkable.

| # | Gate |
|---|---|
| 1 | A147 and A19 built, with node-level tests that refuse each blocked construct |
| 2 | Candidate and fragment runs have enforced time and resource limits, with tests |
| 3 | A document with no scorers cannot learn |
| 4 | A spend breaker acts on the counter, with a test that stops a flapping feed |
| 5 | A threat model exists and has been reviewed by someone who did not write the feature |
| 6 | The hostile corpus passes on a node, with a real model, and the results are kept |
| 7 | The live suite has been run on real-shaped data, with success rate, rounds and cost recorded |
| 8 | Review mode is the default, and automatic mode needs a recorded decision |
| 9 | Data-handling policy written: which models may receive which feeds, and who decides |
| 10 | A usability test against hand-written XSLT (section 3.5, item 8) shows a clear benefit |

### 7.6 Operating safely once live

- Start with review mode, a small number of feeds, and a low token budget.
- Watch `shapeshifter_spend` by hand until the breaker exists.
- Keep as-processed reprocessing available, since it is the audit tool for "what did this stream become, and
  why".
- Treat any new model, new flow or change to the built-in prompt text (`Templates.VERSION`) as a change that
  needs the hostile corpus and a sample of the live suite to be re-run.
- Decide in advance what is done when something wrong is found: retract the binding, give the learner up, or
  switch learning off for the document, and then reprocess the affected streams (the stage reports how many it
  produced, but never reprocesses them itself, A129).

## 8. Overall view

The design is careful, and unusually candid about its own gaps. The architecture is the right one. Its main
weaknesses are the following, in order of importance:

1. The safety controls that matter most (A147, resource limits, the spend breaker) are not built.
2. Correctness is checked structurally, so a plausible but wrong mapping passes.
3. The configuration surface is large enough that it may cost more than it saves for many users, and the safe
   state is not the default state.
4. It has not been shown to work with a real model on real data.

The first and third are fixable with known work. The second needs a decision about what independent check is
acceptable. The fourth needs the live runs.

## 9. Suggested next steps

1. Agree the release gates in section 7.5, or amend them.
2. Build A147 and A19, add the resource limits, and add the node-level tests.
3. Make the blank state safe (scorers, review mode).
4. Write the threat model and the hostile corpus.
5. Run the live suite on realistic, messy feeds and record success rate, rounds and cost.
6. Decide the product shape: the simple default and "Advanced" split in section 3.5, and run the comparison
   against hand-written XSLT.
