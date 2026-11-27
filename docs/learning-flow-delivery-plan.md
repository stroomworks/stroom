# Learning Flow: delivery plan for the raised tasks and the UI rework

*Written 17 November 2026 · updated 18 November 2026 for the final UI, 19 November 2026 for groups 7 and 9 to 13 and a review of the dependencies, and 19 November 2026 for the rename to **Smart Pipelines** and **Learning Flow** (stage R) · branch `ai_translations_server`*

## Summary

Two bodies of work were in flight:

- **Correctness fixes** to the learning engine, raised as tasks in `docs/learning-flow-*.md`.
- **The UI rework**, done separately on `ai_translations_ui_rework`.

The UI rework has now been merged (`7e06bb589c`), and its screens are the final UI design, apart from minor bug fixes.
They are described in [ui-design.md](smart-pipelines/ui-design.md).
That changes the plan in three ways:

- The UI was built on **today's server**, with editable learning flows, every Supervisor action and per-document question texts.
  It was not built on the simplified server, so the simplifications that would remove what the UI shows (options B, D and E in [simplification-options.md](smart-pipelines/simplification-options.md)) are no longer on the table.
  Decisions SIM-2, SIM-4 and SIM-5 are settled by the UI; see [stage 0b](#stage-0b-settle-the-open-decisions).
- Most of the old Track 2's UI phases are done.
  What is left of Track 2 is server work: the simplifications the UI doesn't depend on, the safety items, and the additions the UI would show (approval evidence, who approved, spend-breaker state).
- Some Track 1 work was done on the UI branch (stop reports, the waiting reason, the default element list), and the UI added a Quality Assurance tab with assessments, which bears on the gates and reference corpora.

Decision N1 is settled as option C: learned stylesheets may use only what is on an allow-list.
The list starts hard-coded ([Track 2 step 2](#step-2-a-hard-coded-smart-pipelines-list)).
~~Towards the end of the project, Track 2 pulls in the `ai_translations_egress` branch, which adds an egress policy and an Egress Policy screen to lock down Stroom's risky XSLT features for all translations, and the list moves onto that screen ([Track 2 step 6](#step-6-the-egress-policy-and-the-list-on-its-screen)).~~ **Changed 23 November 2026:** XSLT egress is now managed by a separate project, whose code this project will receive in due course. This project does nothing more on securing XSLT: Track 2 step 6 is out of scope, and the hard-coded Smart Pipelines list and static stylesheet check built in step 2 stay as they are.

The plan is still two tracks that run side by side, after a short round of decisions:

| Stage | What | Depends on |
|---|---|---|
| 0a | Audit the code for hidden assumptions (done) | Nothing |
| 0b | Settle the open decisions, using what the audit found (done, 19 November 2026; SIM-2, SIM-4 and SIM-5 were settled by the final UI) | Stage 0a |
| Track 1 | Correctness fixes (mostly server) | Tasks 1, 4, 5, 6, 8, 9 and 10 can start now (every decision they need is made), as can the parts of task 7 that don't touch checks. The new checks in tasks 2 and 3 wait for task 10's design (task 2's also for the declared-skips design, task 3's for Track 2 step 2's list); the rest of each task can start now. Task 7's check-related parts follow tasks 3 and 10 |
| Track 2 | Server simplification, the Smart Pipelines list, the other safety items and the server additions the UI would show; the UI itself is done. (The egress policy, step 6, moved to a separate project on 23 November 2026.) | Stages 0a and 0b; steps 1, 1b, 2 and 3 can start now (step 1's factory removal waits for Track 1 task 5's trial) |
| R | The rename: `shapeshifter` out of the code, packages, modules, tables, doc type, element type, REST paths, config and document filenames | Track 2 step 5, on a quiet tree; and it **must** land before the first release (see [Stage R](#stage-r-the-rename)) |
| Track 3 | Security fixes from the [security review](learning-flow-security-review.md) of 23 November 2026, in three groups; tasks 1–7 before the first release. The XSLT sandbox (gchq/stroom #6079) was brought in on 24 November and covers most of task 2 | Tasks 1, 3, 7, 8, 9, 12, 14 and 16 can start now; the others need a decision from [Decisions Track 3 needs](#decisions-track-3-needs) first |
| Later | Calibration and larger step sizes | Track 1 |

## The names

**Decided, 19 November 2026.** The two product names are settled:

| Was | Is | What it means |
|---|---|---|
| Shapeshifter AI | **Learning Flow** | The document type: the settings a learning run obeys, and the checks a candidate must pass. |
| AI Translations | **Smart Pipelines** | The feature, and a pipeline that holds the AI element. Monitoring > Smart Pipelines lists them. |

The UI rework already renamed the document type's display name and the monitoring screen, so the screens and most prose say the new names today.
The code does not: it says `shapeshifter` throughout, in around 640 files — count with
`grep -ril shapeshifter . --exclude-dir=.git --exclude-dir=build --exclude-dir=node_modules | wc -l` rather than trusting a number here, since it drifts.
`ai_translations` is not in the code at all: its handful of uses are in `docs/`, and are branch names and references to the egress branch's `docs/ai_translations/` directory.
**Stage R** below does the rest, as one mechanical change.

*The word "learning flow" keeps its second meaning* (decided 19 November 2026): the ordered list of questions asked during a run is still a learning flow, and `LearningFlow` still names it.
So the document type and the question order inside it share a word.
Where a sentence could be read either way, write "the Learning Flow document" or "the order of questions".

**How this document is written until stage R runs.**
Prose, screen names and permission names use the new names.
Things that are still literally called the old name — file paths (`docs/smart-pipelines/`, `stroom-learning-flow/`), branch names (`ai_translations_server`, `ai_translations_egress`), class names (`ShapeshifterAiDoc`), table names (`shapeshifter_attempt`) and the links to the other design documents — are left alone, so that they still match the code and the links still resolve.
Stage R changes them and re-points the links in one go.

## Stage 0a: audit the code for hidden assumptions

### Why

It turned out to be hard to change the target schema.
The setting suggested the schema was configurable, but `event-logging:3` was assumed in the prompts, the target checks and several scorers.
That is a hidden assumption, not untidy structure, and making the code simpler or more modular would not have exposed it: the same value would just sit inside tidier modules.

The simplification is also the most expensive time to find more of these.
Its estimates (about 5,000–6,000 server lines removed) and its "what we lose" sections assume we know what each part does.
A hidden assumption found halfway through removing the flow language costs much more than one found beforehand.

A first grep of the `stroom-learning-flow` production code (about 30,000 lines) shows where to look:

| Assumption | Production files that mention it |
|---|---|
| `event-logging` | 7 |
| `EventDetail` | 8 |
| Parser names (`DSParser`, `JSONParser`, `XMLFragmentParser`) | 11 |
| `UTC` / `ZoneOffset` | 5 |

Not every mention is a problem; sorting them out is the work.

### How

**1. Sweep for hard-coded assumptions.**
List every value that the design treats as variable: target schema, parser types, time zone, record boundary, model, promotion mode, node count, feed type.
For each, check whether every part of the code honours it.
Sort each finding as *configurable in practice*, *hard-coded but harmless*, or *hard-coded and contradicts a setting*.

**2. "What would it take to…?" walkthroughs.**
Trace each change we know is coming through the code before committing to it:

| # | Change | Plan item it informs |
|---|---|---|
| W1 | A target schema other than event-logging | Track 1 task 7; decisions D1 and D2 |
| W2 | Prompts in code, and removing the flow language | Simplification options E and B |
| W3 | Removing provisional bindings | Simplification option C |
| W4 | Judging input coverage while serving | Track 1 task 2 |
| W5 | A new parser type, or a record boundary that isn't one element name | Track 1 task 4 |
| W6 | A different model provider, or a weaker model | Unknown-models proposal |
| W7 | Several nodes learning and serving at once | Track 1 tasks 5 and 6 |
| W8 | A feed with no timestamps, or local times | Track 1 task 3 |

Each walkthrough records the files that would change, the hard-coded values met on the way, the tests that would catch a regression, and an effort estimate.

**3. Start from what is already written.**
`stroom-learning-flow/stroom-learning-flow/ISSUES.md`, the design rulings A1–A147 (`stroom-learning-flow/stroom-learning-flow/design/09-rulings.md`) and the boundary findings in [server-side-inventory.html §12](smart-pipelines/server-side-inventory.html) already record some of these.
The audit cites them rather than rediscovering them.

### Output

`docs/learning-flow-code-assumption-audit.md`: one entry per finding, each with:

- evidence (`file:line`)
- the assumption, and what breaks because of it
- severity: *blocks a planned change*, *changes an estimate*, or *minor*
- the plan item or decision it affects
- whether it is already known (with the `ISSUES.md` entry or ruling)
- a test that would catch it

Findings that contradict [simplification-options.md](smart-pipelines/simplification-options.md) or this plan go into the stage 0b decisions, and this plan is updated to match.

The audit does not block Track 1 tasks 1–4, which are already well understood.
It should finish before Track 2's server removals start.

### Result

**Done, 17 November 2026: [learning-flow-code-assumption-audit.md](learning-flow-code-assumption-audit.md).**
It has 66 findings: 8 block a planned change as written, 41 change an estimate and 17 are minor.
Its new decisions (N1–N9) are added to stage 0b below, and Tracks 1 and 2 have been updated to match.
The main effects on this plan:

- Track 1 task 3 can't ship as written: its fix uses a function that ruling A147 bans (decision N1).
- Track 1 task 2 is M to L, not a contained change.
- Promotion and learning see only the first 64 KiB of a stream, which affects tasks 2 and 4 (decision N2, since decided).
- Option B's proposed recipe drops steps the demo depends on, and option C doesn't behave as described.
- Several nodes learning at once has races the plan didn't cover: new task 8.

## Stage 0b: settle the open decisions

Several documents end in a "Decisions needed" table.
Some of the answers change where code goes, so they should be settled before either track gets far.
This should take days, not weeks.

The decisions come from four documents, and several of them ask the same question in different words.
This section gathers them, groups the ones that have to be answered together, and sets out the options for each.

### Where the decisions come from

| Label | Source | Decisions in stage 0b |
|---|---|---|
| SIM-*n* | [simplification-options.md §6](smart-pipelines/simplification-options.md#6-decisions-needed) | SIM-1 to SIM-5 and SIM-7 to SIM-9 (options A, B, C, D, E, G and H); SIM-2, SIM-4 and SIM-5 are settled by the final UI |
| D*n* | [learning-flow-single-target-schema.md](learning-flow-single-target-schema.md) | D1 and D2 |
| UM-*n* | [ai-translation-approach-for-unknown-models.html §8](smart-pipelines/ai-translation-approach-for-unknown-models.html) | UM-2, UM-3, UM-6 and UM-9 |
| N*n* | [learning-flow-code-assumption-audit.md](learning-flow-code-assumption-audit.md#new-decisions-for-stage-0b) | N1 to N9 |

Three decisions in those documents are not part of stage 0b:

- **SIM-6** (who decides which feeds' data may go to which model, and who may switch a feed to automatic) is a question of ownership, not design.
  It needs an owner from security or the data owners before any real data is sent to a model, but it doesn't change where code goes.
- **SIM-10** (structural work i–iv) is for the server-side engineers to decide after the others, on whatever code remains.
- **UM-1, UM-4, UM-5, UM-7 and UM-8** (calibration, step sizes, model review, reference corpora) belong to the [later](#later-calibration-and-larger-step-sizes) work.
  UM-5 (step sizes) does constrain N3, and is covered there.

### Summary

| Group | Decisions | Recommendation in one line | What it unblocks |
|---|---|---|---|
| 1. XSLT functions | N1 | **Decided: option C.** An allow-list covering standard XSLT as well as `stroom:` functions, hard-coded at first, moving to the Egress Policy screen towards the end of the project. *(23 November 2026: the move, and run-time enforcement, are now the separate XSLT egress project's; the list stays in code here.)* | Track 1 task 3; Track 2 step 2 |
| 2. What is judged | N2 | **Decided.** Option B: coverage judged on the whole held stream. Option E: large single documents not learned, with a stop report that says why and what to change. Option F: one sample-size setting in bytes that the prompt honours | Track 1 tasks 2, 4 and 9 |
| 3. Promotion | SIM-1, UM-6, SIM-3, N4 | **Decided.** SIM-1 and UM-6: option B, review by default, one promotion path, automatic behind a permission. SIM-3: option E, no provisional bindings. N4: option F, a person must act behind a too-small draft; the record minimum applies to first learning only, not to relearns | Track 2 steps 1 and 1a |
| 4. Gates | UM-2, UM-3 | **Decided.** One list of checks on the Learning Flow, each Off, Advice or Gate, chosen by the user; compile always a gate; a candidate passes when every gate passes (no weights or pass mark); serving uses the same gates; steps don't name checks; the score shown is the average of the checks that ran. Defaults: the objective checks as gates (literals and time plausibility as advice), the heuristic checks off. QA assessments: deleted, since no requirement for them can be found | Track 1 tasks 1–3 and 10; the *Quality checks* section (Track 2 step 4); the removal (Track 2 step 1b) |
| 5. Learning recipe | SIM-2, N3 | **Decided.** SIM-2: editable flows stay (settled by the UI). N3: option D, `ESCALATING` is the default setup, subject to a `TestLiveScenarios` run. Flows are defined in JSON with Stroom's normal Jackson conversion, and the flow DSL is removed (a requirement) | Track 2 step 1c |
| 6. Prompts | SIM-5, N9, D2 | **SIM-5 settled by the UI: per-document question texts stay**, with *Instructions to the AI* beside them. **N9 decided: option D extended**, record the templates and texts each run used and rebuild the prompt for someone with *View Data*; **D2 decided: option G**, a schema summary generated from the XSD, since every target schema must be usable | Track 1 task 7; N9 in Track 2 steps 1 and 4 |
| 7. Where settings live | D1, N6 | **Decided.** D1: option A, a *Target schema* field on the Learning Flow under *AI model and instructions*, naming one schema version, not a group; one Learning Flow per target schema. N6: option F with a per-pipeline property: the stream's header, otherwise a `timeZone` property on the AI Translator element, otherwise UTC; the stage puts the resolved zone in the stream's metadata, read with `stroom:meta`, so a change needs no relearning | Track 1 tasks 3 and 7; a field on the Setup tab; `meta` on Track 2 step 2's list |
| 8. Supervisor | SIM-4 | **Settled by the UI: every action stays**, on the Learning Status tab. Improve moved to the worker, 20 November 2026 | Track 2 step 1 (Improve only) — done |
| 9. Pipeline creation | SIM-7, SIM-8, SIM-9 | **Decided.** SIM-7: option B, drop the Pipeline Factory; Content Templates make each feed's Smart Pipeline. SIM-8: option D, AI on existing feeds added by hand. SIM-9: option H, remove the `claimTasksOnWorker` requirement after a test-cluster trial with the master queue | Track 1 task 5; Track 2 step 1 (option G) |
| 10. Cost | N7 | **Decided: option B.** Estimate tokens from characters when usage is missing, and charge failed calls | The spend breaker (Track 2 step 3) |
| 11. Several nodes | N8 | **Decided: option A.** Multi-node learning is sound for the first release | Track 1 task 8 |
| 12. Database changes | N5 | **Decided: option A.** Edit the unreleased migration in place, and reset dev and demo databases (what the UI merge did) | Every step that changes tables |
| 13. Order of work | UM-9 | **Decided: option A.** Gates first, as Track 1 already orders them | Track 1 |

### Suggested order for settling them

Every group is decided: groups 1 to 7 and 9 to 13 on 18 and 19 November 2026, and group 8 (SIM-4) by the final UI.

~~One design item is still open~~ — **settled, 20 November 2026**: **how a translation declares a skipped record** (group 4, UM-2) is [its own design document](learning-flow-declared-skips-design.md).
A translation writes a marker in its output, in Shapeshifter's own namespace, which the module counts and the writer drops; there is no cap on the skip rate, and the count is shown instead.
That keeps `xsl:message` off the Smart Pipelines list, where [step 2](#step-2-as-built-20-november-2026) put it.

### 1. N1: which Stroom XSLT extension functions a learned stylesheet may use

**Decided, 18 November 2026: option C, in two stages.**
A learned stylesheet may use only what is on an allow-list, which covers standard XSLT and XPath as well as `stroom:` functions.

1. **First, a hard-coded list** in the Learning Flow module, applied by the static stylesheet check (when a candidate is judged and promoted) and by the scenario test harness ([Track 2 step 2](#step-2-a-hard-coded-smart-pipelines-list)).
2. **Towards the end of the project**, the list moves onto the Egress Policy screen from the `ai_translations_egress` branch, where an administrator maintains it, and is also enforced at run time ([Track 2 step 6](#step-6-the-egress-policy-and-the-list-on-its-screen)).

The analysis behind the decision is kept below.

**The question.**
On a node, learned stylesheets run in the real `XSLTFilter`, which allows every `stroom:` extension function.
The scenario test harness allows none.
Ruling A147 says every candidate that calls any extension function must be refused, and Track 2 step 2 builds a static check to do that.
But Track 1 task 3 (missing year) wants the model to use `stroom:format-date`, which takes a missing year from the stream's received time.
Both can't ship as written (audit finding S8).

**The question is wider than extension functions.**
Saxon-HE in `XSLTFilter` has no runtime URI resolver, unparsed-text resolver, collection finder or output resolver, so standard XSLT and XPath reach the node's host with no `stroom:` function involved.
A147 doesn't cover these, because they aren't extension functions.
On the dev instance on 11 November, a pipeline XSLT listed `/etc` with `uri-collection('file:///etc/…')` and wrote a file with `xsl:result-document href="file:///tmp/…"`.

| Group | What | Why it is dangerous |
|---|---|---|
| Standard: host files | `unparsed-text()`, `unparsed-text-lines()`, `document()`, `doc()`, `collection()`, `uri-collection()` with `file:` URIs; `xsl:result-document` | Reads and lists files the `stroom` user can read (listing confirmed); **writes files** (confirmed) |
| Standard: network | The same functions with `http(s):` URIs; `json-doc()` (to check on Saxon-HE 9.9) | Outbound requests from the node, including to cloud metadata addresses |
| Standard: environment | `environment-variable()`, `available-environment-variables()` | Secrets passed in the environment |
| Standard: indirect calls | `function-lookup()`, function references such as `stroom:http-call#5` | Calls any function without its name appearing as a call, defeating a check of named calls |
| `stroom:` outbound | `http-call` (a POST to any URL), `fetch-json`, `send-email`, `ask-ai`, `host-name` and `host-address` (DNS lookups) | Data leaves the node; model spend; a DNS channel |
| `stroom:` reads beyond the record | `lookup`, `bitmap-lookup`, `dictionary`, `manifest-for-id`, `meta-stream-for-id`, `parent-for-id`; less so `feed-attribute`, `classification`, `current-user` | Other feeds' reference data, dictionaries and stream metadata, with the processing user's access |
| `stroom:` side effects | `log` (an ERROR or FATAL drops records from the output), `add-meta`, `put` and `get` | Quiet record loss that can still score well; state carried between records |
| `stroom:` not repeatable | `current-time`, `current-unix-time`, `random`, `random-integer` | Judging and serving give different results |
| `stroom:` pure, or the current stream only | The date parse, format and conversion functions (including `format-date`, subject to AS6), `hash`, the `hex-to-*` functions, `numeric-ip`, `ip-in-cidr`, `cidr-to-numeric-ip-range`, `encode-url`, `decode-url`, `parse-uri`, `json-to-xml`, `shannon-entropy`; `meta`, `meta-keys`, `meta-attribute`, `manifest`, `meta-stream`, `feed-name`, `pipeline-name`, `source`, `source-id`, `stream-id`, `parent-id`, `part-no`, `record-no`, `line-from`, `line-to`, `col-from`, `col-to` | Candidates for the allow-list |

The `ai_translations_egress` branch closes the first two standard groups for all of Stroom at run time, and guards the outbound `stroom:` functions other than the DNS lookups.
The rest is for this decision's list.
Until that branch is pulled in (Track 2 step 6), the static check is the only guard, so the list must refuse every group above except the last.

**Options.**

- **A. Allow none (A147 as written).**
  - For: the simplest rule, and the safest; the model can't reach anything outside the record it is translating.
  - Against: task 3's fix can't use `stroom:format-date`.
    The model would have to rebuild dates in plain XSLT, and it has no way to learn the stream's received year unless that is passed to the stylesheet some other way.
- **B. Allow all (what a node does today).**
  - For: no work.
  - Against: a model-written stylesheet could call functions with side effects or wider access, such as `stroom:http-call` or `stroom:lookup`.
    On an automatic feed nobody reads the stylesheet before it runs.
- **C. An allow-list of pure functions and the current stream's own metadata (chosen).**
  Functions that only compute a value from their arguments and the stream's own metadata are allowed: the last group in the table above, starting with `stroom:format-date`.
  Everything else is refused, standard functions and instructions as well as `stroom:` ones, and so are `function-lookup()` and function references.
  The list is in code at first (Track 2 step 2).
  Towards the end of the project it moves onto the Egress Policy screen from the `ai_translations_egress` branch, editable only with *Manage Egress Policy*, and audited, with the outbound functions and the routes the egress policy denies outright never allowed (Track 2 step 6).
  The static check and the test harness apply the same list, so a stylesheet that passes the tests also passes on a node; from step 6 the serving pipeline applies it too, so one the static check misses is still refused when it runs.
  - For: fixes the missing-year fault properly, keeps A147's intent, and closes the standard-XSLT routes A147 never covered. From step 6, sites can review the list, and add a function they have checked, without a release.
  - Against: the list has to be maintained, and each function on it checked for side effects. Until step 6, changing it needs a release, and there is no run-time enforcement.

**Example.**

```xml
<!-- Allowed under option C: pure, and takes the year from the stream -->
<xsl:value-of select="stroom:format-date('Oct 19 09:06:02', 'MMM dd HH:mm:ss')"/>

<!-- Refused under options A and C -->
<xsl:value-of select="stroom:http-call('https://example.com/lookup', ...)"/>

<!-- Refused under option C, though neither is an extension function call -->
<xsl:value-of select="unparsed-text('file:///etc/passwd')"/>
<xsl:value-of select="function-lookup(QName($stroom-ns, 'http-call'), 5)(...)"/>
```

**Ruling A147** should change from "any candidate that calls an extension function is refused" to "a learned stylesheet may use only what is on the Smart Pipelines list, checked when it is judged and promoted" now, adding "and enforced when it runs" at step 6.

**Affects:** Track 1 task 3; Track 2 step 2 (the hard-coded list and the static stylesheet check) and step 6 (the egress branch, the list on its screen, run-time enforcement); ruling A147, which must be amended to match.

### 2. N2: whole stream or the first 64 KiB, and how large documents are sampled

**Decided, 18 November 2026**, one part at a time:

| Part | Decision |
|---|---|
| What is judged (options A–C) | **Option B**: judge input coverage on the whole held stream, in the full pass; the model is still shown a sample from the prefix. |
| Sampling a large single document (options D–E) | **Option E, leave it**: a single XML or JSON document larger than the prefix is not learned. Learning must say so plainly, and say what would have to change (see below). |
| The sample size (options F–G) | **Option F**: one setting, in bytes, that the prompt honours. Option G waits for calibration. |

Each part has its own letters, so "N2 option B" means only "judge coverage on the whole held stream".

**The question.**
The design says a candidate is judged "over the whole held stream".
In fact the stream-level checks (compile, input coverage, yield, the floor, "no lower than the incumbent") see only the first 64 KiB of any larger stream (audit finding S2).
Only the worker's full pass and serving read the whole stream, and they run only the record checks.
Two related problems sit beside it:

- A single XML or JSON document of 64 KiB or more is learned from a prefix that doesn't parse, so no record boundary is found (W5-4).
- The sample-size setting is overridden by two fixed caps (6,000 characters in the prompt, 64 KiB in the reader), and is in characters although the editor says bytes (S4).

**Options for what is judged.**

- **A. Keep judging the 64 KiB prefix, and document it.**
  - For: no work, and judging stays fast.
  - Against: drift past the first 64 KiB is never seen at promotion, which defeats the point of Track 1 task 2.
- **B. Judge coverage on the whole held stream; keep the prefix for what the model is shown (chosen).**
  The full pass already reads the whole stream, so it can count records in against events out.
  - For: closes the gap for the check that matters most (completeness), at little extra cost.
  - Against: the other stream-level checks still see only the prefix.
- **C. Judge everything on the whole stream.**
  - For: matches the design exactly.
  - Against: slow for large streams, and the judged run is also capped at 10,000 records, so that cap would need its own decision.

**Options for sampling a large single document.**

- **D. Cut at the record boundary (was recommended).** Keep whole records up to the size limit, and close the document so it parses.
- **E. Leave it**, and accept that large exports can't be learned **(chosen)**.

The demo's GATEWAY-AUDIT stream is about 17 KB, inside the prefix, so this choice doesn't affect it: its failure is the mixed record elements (Track 1 task 4).
Real exports of 64 KiB or more, as one XML or JSON document, are what can't be learned.

**What option E still needs: a clear refusal.**
Today such a stream fails quietly or misleadingly: the prefix doesn't parse, so it is treated as lines and the split step finds no record boundary (W5-4).
Instead:

- **Detect it before asking the model anything**: the stream is larger than the prefix (`Input.complete()` is false), it starts as one XML or JSON document, and the prefix doesn't parse as one.
  No model call is made, and so no tokens are spent.
- **Stop the run with a stop report** (`StopReports`), shown under *Where it stands* on the pipeline's Learning Status tab, and the same words on the stream's Error stream:
  - **Error:** "This stream is one XML document of 1.4 MB. Learning reads only the first 64 KiB of a stream, so a single document larger than that can't be learned."
  - **Details:** the stream's size, the limit, and that the limit applies to each stream as a whole, not to each record.
  - **Suggestions:** what would have to change to attempt learning it: have the source send smaller exports, each a whole document under 64 KiB (for example one export per batch or per time period); or, if that can't change, translate this feed with a hand-written pipeline instead.
    The limit is a constant in the code (`Input.PREFIX_BYTES`), not a setting, so the suggestions don't point at the Setup tab.
- **Don't retry the same stream**: learning again from it would end the same way. Later streams of the feed that are small enough are learned as usual.
- **Test**: a scenario with a single XML document and a single JSON document just over 64 KiB, asserting the stop report's error and suggestions, and that no model call is made.

**Options for the sample size.**

**Option F, decided 18 November 2026.** Calibration will be a late phase at best (see [Later](#later-calibration-and-larger-step-sizes)), so option G isn't available now; it can be reconsidered once calibration exists.

- **F. One setting, in bytes, that the prompt honours (chosen).**
  The Learning Flow keeps *Largest sample of a stream (bytes)* (Setup tab, *Learning steps* > *Advanced options*), and the work makes it mean what it says:
  - the fixed 6,000-character cap in the prompt (`QuestionText.INPUT_SHOWN`) goes, so the setting is the real limit;
  - it is counted in bytes, as the screen says, not characters;
  - it stays within the 64 KiB prefix (`Input.PREFIX_BYTES`), or the prefix grows with it.

  The default (8,192, `ShapeshifterAiDoc.DEFAULT_SAMPLE_SIZE_LIMIT`) has to be chosen without calibration. It is a cautious value for an unknown or weak self-hosted model; keep it unless the demo feeds show it is too small.
  Optionally, where the model document's *Max context window tokens* is set, the sample is also capped at a share of it, at about 4 characters a token (the estimate N7 uses). That is arithmetic, not calibration. The field defaults to 0 (unknown), and then the setting alone applies; the module doesn't read the field today.
- **G. Remove the setting** and size the sample from the model's context window, once calibration exists. **Deferred** with calibration.

The other two parts of N2 don't depend on calibration.

**Example (illustrative numbers).**
A 200 KiB syslog stream whose lines change format at 150 KiB.
Today the candidate is judged on the first 64 KiB, coverage is 1.0, and it is promoted.
Under option B (what is judged) the full pass counts 2,400 records in and 1,800 events out, and promotion is refused.

**Affects:** Track 1 task 2 (input coverage on the whole stream), task 4 (the refusal of large documents), and task 9's sample-size fix (S4); simplification option i inherits whole-stream coverage.

### 3. Promotion: SIM-1, UM-6, SIM-3 and N4

These four decide what happens between "a candidate clears the gate" and "it serves", so they should be answered together.

Option letters are unique across the group: SIM-1 and UM-6 are A–C, SIM-3 is D–E, and N4 is F–G.

#### SIM-1 and UM-6: the default promotion mode

**Decided, 18 November 2026: option B, review by default.**
Every result that clears the gate is written as a draft, through one promotion path for both modes.
A new Learning Flow starts with *New translations* set to *Wait for someone to approve them*; automatic is a deliberate choice behind a permission.
UM-6's stricter option C (automatic only once the model is calibrated) waits for calibration, which is a late phase at best.

What this means for the work:

- **One promotion path**: simplification option A (remove the in-place promotion path), in Track 2 step 1. Automatic mode approves the same draft at once, through the same code.
- **The default**: the promotion mode's default on `ShapeshifterAiDoc` changes to review, and the demo's `Any feed` document changes with it (or is documented as a deliberate automatic example).
- **The permission** (decided 19 November 2026): a new application permission, provisionally *Approve Smart Pipelines Automatically*; the name is settled when Track 2 step 1 is built. Approving a draft needs EDIT on its pipeline, so the pipeline's owner decides; switching a Learning Flow to *Use them as soon as they pass* approves every future draft for every pipeline it serves, with nobody looking, so it is a wider act than editing the Learning Flow. Existing permissions don't fit: OWNER on the Learning Flow usually belongs to whoever created it, so it rarely stops anyone with EDIT; ADMINISTRATOR is too coarse; *Manage Content Templates* is a different act. The precedents are *Manage Content Templates* and the egress branch's *Manage Egress Policy*.
  - It is needed only to switch **to** automatic. Switching back to review, the safer direction, needs only EDIT.
  - The server checks it whenever a Learning Flow is saved, **including on import**, so an imported Learning Flow can't arrive already automatic (as Content Templates check their run-as user on save and import).
  - Someone without it can still edit the rest of an automatic Learning Flow, leaving the mode as it is.
  - On the Setup tab the choice is shown read-only to people without it, with a line naming the permission. This is a small change within the final UI design (AS18).
  - By default only administrators have it.
- **"Per feed" is per Learning Flow** in the final UI: the mode is a setting on the Learning Flow, and one Learning Flow can serve many feeds (AS4). A feed that needs a different mode needs its own Learning Flow. A per-pipeline override would be a change to the design, and isn't planned.

**The question.**
Today the default is automatic, and the two modes write learned content in different ways: automatic relearning rewrites the parser and XSLT in place, while review writes a numbered draft that approval points the learner at.
SIM-1 asks whether review should be the default, with one promotion path for both modes.
UM-6 asks the same question from the model's side: whether a person should approve until the model is calibrated and the feed has reference output.
The demo's `Any feed` document is `AUTOMATIC` today.
The final UI offers both modes on the Setup tab (*Approving new translations* > *New translations*), so changing the default doesn't change the screens.

**Options.**

- **A. Keep automatic as the default, with two paths (today).**
  - For: no work, and feeds go live with no one involved.
  - Against: two code paths to test; a model nobody has calibrated changes production content unwatched.
    NETFILTER was promoted automatically with a score of 1.0 although it lost a line and hard-coded a year.
- **B. Review by default, one path, automatic per feed behind a permission (chosen).**
  Every result that clears the gate is written as a draft.
  Under review a person approves it; under automatic the system approves it at once, through the same code.
  - For: one path; a feed can move between modes without its content being handled differently; switching to automatic becomes a deliberate, permitted act.
  - Against: slower onboarding until feeds are switched to automatic; one short-lived draft per automatic promotion.
- **C. Option B, plus automatic allowed only once the model is calibrated and the feed has references (UM-6).**
  - For: the strongest guard against an unknown model.
  - Against: depends on calibration, which comes [later](#later-calibration-and-larger-step-sizes).
    Until then, no feed could be automatic.

Option B now, tightened to option C when calibration exists, satisfies both documents; option B is what was decided.

**Example.**
The firewall feed changes its timestamp format, and a relearn clears the gate.
On a feed left on review, the draft waits on the pipeline's Learning Status tab ("A new translation is ready for you to check"), showing "412/412 events valid, no regressions" once approval evidence is added (Track 2 step 4), and a person approves it.
On a feed switched to automatic, the system approves the same draft at once.
Either way the learner ends with one set of documents and a new snapshot.

#### SIM-3: provisional bindings

**Decided, 18 November 2026: option E, remove them.**
A result learned from fewer than `minRecordsToLearn` records is a draft, even on an automatic Learning Flow, and waits for a person (N4 option F).
What this means for the work (Track 2 steps 1 and 1a):

- The server removes provisional bindings, the serve-time confirmation, the Accept action and its endpoint. Old `PROVISIONAL` attempt rows are mapped before the enum value goes (N5: the unreleased migration is edited in place).
- The UI loses the *On trial* chip, *Accept now* on the Learning Status tab, and "trusted or on trial" on the Quality Assurance tab, removed before the server removes the endpoint (AS13). This is a minor change to the final UI (AS18).
- One of task 8's lost-update writers goes with them.

**The question.**
In automatic mode, a first result learned from fewer than `minRecordsToLearn` (default 10) records is provisional: it serves at once and is confirmed later.
Should provisional bindings go, so that a result learned from too few records always waits for a person?

**Options.**

- **D. Keep them.**
  - For: small feeds on automatic go live with no one involved.
  - Against: the audit found they don't behave as described.
    Confirmation needs one stream with enough records, not enough records in total, so a feed that sends 4 records a file stays provisional for ever and never gets a rolling score.
    The serve-time confirmation is also one of the lost-update writers in task 8.
- **E. Remove them (chosen).** A result from too few records is a draft, even on an automatic feed.
  - For: "when in doubt, it's a draft" fits SIM-1; removes the Accept action, its endpoint and a race.
  - Against: small automatic feeds wait for a person.
    About 20 production files change, and old `PROVISIONAL` attempt rows must be mapped before the enum value is removed (see N5).
    The final UI shows provisional bindings: the *On trial* chip, *Accept now* on the Learning Status tab, and "trusted or on trial" on the Quality Assurance tab.
    Removing them takes those out, which is a minor change to the UI design rather than a redesign.

#### N4: what happens behind a too-small draft

**Decided, 18 November 2026: option F, a person must act.**
A draft learned from too few records waits for a person; later streams of the feed defer with "Awaiting review" until it is approved or rejected.
No new code is needed for it.
It may be revisited later, if quiet feeds on automatic Learning Flows wait too long (see [Later](#later-calibration-and-larger-step-sizes)).

**Decided, 18 November 2026: the record minimum applies to first learning only.**
With one promotion path (SIM-1 option B), a relearn writes a draft, which an automatic Learning Flow approves at once, however few records the stream that prompted it had.

- **Why:** the minimum exists because a first result has nothing else to be judged on. A relearn does: it must pass the regression corpus (records accepted from earlier streams, up to the regression cap) and score no worse than the translation already in use. So a relearn from 3 records is still judged on far more than 3.
- **The existing gate stays:** a relearn a person asks for (*Learn again*, or a learner marked to learn again) still starts only on a stream with at least the minimum (`Stage.relearnOnMark`).
- **For feeds that need every change watched:** use a Learning Flow set to review, the default since SIM-1.
- **Work:** no new code beyond the one promotion path. Write the rule into the module's design notes (rulings), and add a scenario test showing that a relearn prompted by a stream with fewer than `minRecordsToLearn` records is approved at once on an automatic Learning Flow, and waits as a draft on a review one.
- **Rejected alternative:** holding relearns to the minimum too. It needs a new condition at automatic approval, a choice of what to count (the stream alone, or with the corpus), and a reason on the draft for the Learning Status tab; and on a low-volume feed it would make every relearn wait for a person.

**The question.**
If SIM-3 option E is chosen, a draft blocks its learner: every later stream defers with "Awaiting review", and no new session starts behind a draft.
The simplification document says the feed "waits until a later session has learned from more data", but the code doesn't do that (W3-1).
And relearns rebind in place with no record minimum at all, so "never approved automatically on too few records" would cover first learning only (W3-2).

**Options.**

- **F. A person must act (today's behaviour for drafts; chosen for v1).** The draft waits; later streams defer until it is approved or rejected.
  - For: no new code; the rule is simple to explain.
  - Against: a quiet feed on automatic can wait a long time for someone to notice.
- **G. A later session may replace a too-small draft.** When a later stream brings enough records, a new session learns from it and its result replaces the draft.
  - For: small feeds can still finish on their own.
  - Against: new code and new states; the Learning Status tab has to show a draft being replaced.

Separately, decide whether relearns are held to the same minimum.
If they are, a relearn from a small stream also becomes a draft; if not, the rule only applies to first learning, and that should be written down. (Decided above: first learning only.)

**Example.**
A new low-volume feed sends a first file of 4 lines.

| | First file | Next file, 12 lines |
|---|---|---|
| Today | Binds provisionally and serves | Promoted to a full binding, because this one stream has 10 or more records |
| SIM-3 E with N4 F | Becomes a draft | Defers with "Awaiting review" until a person acts |
| SIM-3 E with N4 G | Becomes a draft | A new session learns from 12 records; its result replaces the draft and, on an automatic feed, is approved |

### 4. Gates: UM-2 and UM-3

Option letters are unique across the group: UM-2 is A–C, UM-3 is D–F, and the QA assessments are G–I.
UM-2 and UM-3 are decided, including what the monitoring score is made of. The QA assessments are to be deleted: no requirement for them can be found.

#### How checks work (decided, 18 November 2026)

UM-2 and UM-3 are decided together, as one simple model of checks that the user controls.
In one sentence for a user: *each check is Off, Advice or Gate; a candidate goes live when every gate passes, and a served record that fails a gate holds its stream back; learning steps route on any check that applies to them.*

- **One list of checks, on the Learning Flow.** Every check has a row under the Setup tab's *Quality checks*, heuristic or not: the existing scorers, the checks that today exist only on learning steps (wholeness, preservation, fidelity), and the new ones (completeness, no data-like literals, time plausibility, the regression corpus). Each row has a state and, where one means something, a threshold and its own parameters. Nothing is removed for being heuristic.
- **Each check is Off, Advice or Gate:**
  - **Off**: not run, not shown.
  - **Advice**: run; shown beside a draft on the Learning Status tab and fed back to the model; never decides promotion.
  - **Gate**: refuses a candidate that falls below the check's threshold.
- **A candidate passes when every gate passes.** There are no weights and no weighted total, so no pass mark: a non-gated check can't change the result through the total, and several near misses no longer add up to a failure on their own.
- **Serving uses the same gates.** The serving record bar is every check set to Gate that judges records one by one, not a fixed set in the code. A served record that fails one holds its stream back and starts a relearn; the *Quality checks* section says so where a check is made a gate.
- **Steps don't name checks.** A check applies to a learning step whenever it has something to measure there (the scorecard already scores "every setting whose scorer has a signal on the step"): a split step is judged on coverage, yield and wholeness, a transform step on schema, preservation and the rest. Steps keep their rules (`on yield-short goto split`), which fire on whichever checks applied. A check set to Advice still routes; routing isn't promotion.
- **A step rule that uses a check set to Off is warned about** on the step's card ("This rule uses Yield, which is off"), because it can never fire.
- **The score people see is the average of the checks that ran**, gates and advice alike: "It scored 87%" on the Learning Status tab, the rolling score behind *Below par*, and "scores lately" on the Quality Assurance tab. (This settles what the monitoring score is made of.)
- **Compile is always a gate** and can't be changed: every other check depends on the candidate building and running.
- **Everything else is the user's choice.** Turning off a check the rest of the system relies on is the user's decision and the user's risk, on review and automatic Learning Flows alike. No permission is needed beyond what changing the Learning Flow's other settings needs (EDIT).
- **The defaults turn on only the non-heuristic checks**: a new Learning Flow has the objective checks as gates and the heuristic checks off (UM-2 and UM-3 below give the table). The presets (*Lenient*, *Standard*, *Strict*) are redefined over the three states; *Standard* is the defaults.
- **One concept in the code.** Every check is the same kind of thing, judged and reported the same way; compile stays special, and nothing is forced into the structure where it doesn't fit.

**What goes:**

- `FlowStep.checks`, and *what judges it* on a step's card.
- Weights, the weighted total and the pass mark (`promotionFloor`): the *Weight* field on each check and the *Pass mark* field under *Approving new translations*.
- The fixed serving record bar (`RECORD_SCORERS`) and the fixed wholeness threshold (`TargetChecks.SPLIT_WHOLENESS`, 90%, which becomes wholeness's default threshold).
- The split between checks that wrap scorers and checks "that have nothing to weight or threshold" (the two kinds in the `Check` enum).

**What it costs:**

- A check can no longer be limited to some of the steps it applies to. No case is known that needs it.
- Several checks each just short no longer fail a candidate unless one of them is a gate. That is the intent, but it is a change of behaviour.
- Medium to large effort, leaving less code than it starts with: the scorecard, `Conversation`'s per-step check sets, `FlowStep` and its serialisation, the store's flow validation, the step card, the check editor and *Approving new translations*, and scenario tests that rely on example flows' check lists or on pass-mark arithmetic. It is Track 1 task 10 (server) and part of Track 2 step 4 (the *Quality checks* change); the client stops using the weight, the pass mark and step checks before the server removes them (AS13).

#### UM-2: the gates

**Decided, 18 November 2026: option B as the defaults, with literals as advice first** (option C for literals only), within the model of checks above.

| Check | Default | Today | Built by |
|---|---|---|---|
| Compiles | Gate, always | Exists (`CompileScorer`) | – |
| Schema-valid | Gate | Exists (`SchemaConformanceScorer`) | – |
| Completeness: records in = events out + declared skips | Gate | Partly: input coverage counts characters, on the first 64 KiB only; nothing counts records in against events out | Track 1 task 2, on the whole held stream (N2 option B) |
| Preservation | Gate | Exists in the target checks (`TargetChecks`), but broken for JSON feeds | Track 1 task 1 |
| Regression corpus: records accepted from earlier streams still come out right | Gate | Exists (the per-learner regression set), outside the scorecard | Brought into the one structure of checks |
| No data-like literals (a year, a zone, an environment name written into the XSLT) | **Advice first**, then Gate | Doesn't exist | Track 1 task 3 |
| Time plausibility: each event's time falls in a window around the stream's received time | **Advice**, with the window settable per Learning Flow | Doesn't exist | Track 1 task 3, fix 3 |
| Input coverage (characters consumed) | Gate | Exists (`InputCoverageScorer`) | Kept; completeness covers what it misses |
| Wholeness: the records hold enough of the document's text (split steps) | Gate, threshold 90% | Exists only on learning steps, with the 90% fixed in the code | Brought into the list (Track 1 task 10) |
| Fidelity: the transform reproduces the target events agreed in the target step | Gate | Exists only on learning steps | Brought into the list (Track 1 task 10) |

What goes with the decision:

- **Declared skips must be designed before completeness ships.** A feed with header lines or heartbeats legitimately makes fewer events than records, so a translation needs an agreed way to say which records it skipped and why (for example an `xsl:message` in an agreed form, or a marker in the output that the writer drops). Without it, completeness fails any feed with lines that aren't events, and users would turn it off. This is part of Track 1 task 2, and is **still open**: none of the stage 0b decisions settles it. Design it first in task 2, and record the choice in the module's rulings. The mechanism must be on the Smart Pipelines list (Track 2 step 2; for example `xsl:message`, or an output marker that survives the Schema-valid gate and is dropped by the writer), and reported the same way by the stand-in runner and the real pipeline, so design it before step 2's list is final.
- **Literals start as advice.** The check is itself a heuristic ("looks like data"), so it runs on the demo feeds as advice first. Its default becomes Gate once it has run on every demo feed without a false failure, or with the false failures understood and fixed. A user may make it a gate sooner.
- **Time plausibility is advice by default, with its window settable per Learning Flow** (decided 18 November 2026). It judges whether an event's time is plausible, not whether it is right, so it fails correct output on back-loaded or historical data, on reprocessed streams and on devices with wrong clocks; as a gate it would also hold back every stream of a back-load while serving. As advice it still shows beside a draft and goes back to the model, which is where NETFILTER's invented year is caught on a review Learning Flow (the default since SIM-1). A user may make it a gate.
  - **The window** is set on the Learning Flow, with the check's other settings under *Quality checks*: how far before the stream's received time an event may be (default one year) and how far after (default one day).
  - **Its reference time** is the stream's received time, which must be the same value `format-date` uses (`Meta.getCreateMs()`), so that the two can't disagree. Regression corpus records keep the received time of the stream they came from, so that re-judging them doesn't fail them against the current stream (audit W8).
  - **Its time field** is `EventTime/TimeCreated`, which is event-logging's. For another target schema (D1, D2) the field has to be named, or the check doesn't apply.
  - It doesn't catch time-zone errors, which are hours, not years; the prompt fix (never assume UTC) and N6 cover those.
- **"Reference regression" is the regression corpus for v1.** The corpus is built from the system's own accepted output, so it means "no worse than what was accepted before", not "matches approved output". Checks against hand-approved reference output come with UM-8 (reference corpora), in the later work.

A correction to how the question was first put: *Error load* and *Event classification* are defined as scorer types but nothing implements them, so neither gates today.

**The question, as it was asked.**
Which checks decide promotion (gates), and which only inform the model and the reviewer (advice)?
Today every configured scorer can decide, including the heuristic ones (yield, extraction quality, business rules).
The demo evaluation found that the heuristic scorers give both false confidence and false failures.

**Options for UM-2 (the gates).**

- **A. Keep today's set.**
- **B. Objective gates only (chosen as the defaults, with literals as in C):** compiles, schema-valid, completeness, preservation (once task 1 fixes it), no data-like literals, and reference regression.
  Time plausibility (task 3 fix 3) is added as a check (decided: Advice by default, window settable per Learning Flow).
- **C. Option B, but with completeness and literals as warnings at first** (taken for literals only; completeness is a gate from the start), until they have run on the demo feeds without false failures.

#### UM-3: the heuristic scorers

**Decided, 18 November 2026: option E as the default, with the user free to choose** (see [How checks work](#how-checks-work-decided-18-november-2026)).
The heuristic checks stay available. By default they are **off**; a user may make any of them Advice or Gate.

| Check | Default | Why it is heuristic |
|---|---|---|
| Yield (output records per input record, against an expected ratio) | Off | The right ratio is a guess per feed |
| Extraction quality (typed fields rather than `Data`, real `EventDetail` branches rather than `Unknown`, required fields present) | Off | Rewards looking full, not being right |
| Business rules (the Learning Flow's own XPath assertions) | Off until a site writes rules | Only as good as the rules; a precise site-written rule is as objective as schema validation, and a site may make it a gate |

A heuristic made a gate acts while serving too (it joins the record bar), so a false failure holds streams back and starts relearns, at a cost in tokens. That is the user's choice, and the *Quality checks* section says so.
Option F (remove them) was rejected: it would break learning steps that route on their shortfalls, which matter now that editable flows stay (SIM-2), and lose their feedback to the model, for little saving.

**Options for UM-3 (the heuristic scorers), as they were put.**

- **D. Keep them as gates.**
- **E. Show them as advice (recommended; taken as the user's choice, off by default).** They appear beside a draft on the Learning Status tab and in the feedback to the model, but never refuse a candidate.
- **F. Remove them (rejected).** Some faults they catch would then rely on review alone.

**Example (with the decided defaults).**
NETFILTER today: 30 lines in, 29 events out, an XSLT containing `select="2023"`, promoted at 1.0.
With the decided gates:

```text
✓ Compiles
✓ Schema-valid (29 / 29)
✕ Completeness: 30 in, 29 out, 0 skips declared
✓ Preservation
✓ Regression corpus: no earlier records for this feed
⚠ Literals (advice): 2023, Production
```

It can't be approved as it stands, because completeness, a gate, fails. Its score, the average of the checks that ran, is shown beside it but doesn't decide.
The literals advice shows beside the draft and goes back to the model, but doesn't refuse it on its own.
The heuristic checks are off by default, so "Outcome not in record" from business rules doesn't appear unless the site turns that check on.

#### The QA person's assessments

**Decided, 18 November 2026: delete the feature** (replacing an earlier decision the same day to keep it as option G, people only).
No requirement for it can be found: the assessments and the roles of *QA person* and *Data Provider* arrived with the UI rework branch (`c40284265b`), and no earlier design document, design note or plan mentions them.
So none of the options below is taken; the feature is removed (Track 2 step 1b).

What goes:

- **Client:** the *Assessments* section of the Quality Assurance tab and its *Record an assessment…* and *Mark resolved* buttons; the assessment dialog (`AssessmentEditor`, its controller, template and `assessment-editor.css`); "Flagged by QA" and its link on the Learning Status tab (`FeedDetail`, `FeedDetailController`, `FeedSituation`); open issues in `QualityFigures`; the assessment stories in `PipelineQualityStories` and `PipelineAiTranslationStories`.
- **Shared:** `QualityAssessment`, `AssessmentRequest`, `AssessmentVerdict`, and the assessment and resolve calls on `QualityResource`.
- **Server:** `Assessments`, `InMemoryAssessments`, `AssessmentsDao` and their module bindings; the assessment parts of `QualityService` and `QualityResourceImpl`; their tests (`TestAssessmentsDao`, and the assessment cases in `TestQualityService` and `TestSupervisorResourceImpl`).
- **Database:** the `shapeshifter_assessment` table, taken out of the unreleased `V08_00_00_001` migration (N5) and out of `jooq-codegen.xml`, with the jOOQ code regenerated.
- **Words:** the *QA person* and *Data Provider* roles, in the UI's text, code comments and the migration's comments.

If a requirement for checking live output, or for people's judgements becoming reference output (UM-8), turns up later, it should start from that requirement rather than from this code.

**The rest of the Quality Assurance tab stays** (decided 18 November 2026): the quality figures, the daily volume, what its records became, the samples and the learning runs' scores, with `QualityResource`'s report and samples calls, the volumes code and `shapeshifter_output.record_count`. It is accepted as it stands (19 November 2026), with no separate written requirement; it may evolve with use. Changes made by other decisions still apply: the pass mark goes (UM-2), "trusted or on trial" goes with provisional bindings (SIM-3), and the score shown is the average of the checks that ran.

**The options, as they were put.** The final UI adds a Quality Assurance tab on each Smart Pipeline, where a QA person records an assessment of its translated output: *looks right*, or *has an issue for the Data Provider to fix*.
Should assessments feed in?

- **G. People only (as built).** Assessments are shown on the Quality Assurance tab and, while open, as "Flagged by QA" on the Learning Status tab.
- **H. An open issue holds back promotion** of the next draft for that pipeline until it is resolved, or is shown beside the gates as advice.
- **I. Assessed output becomes reference output** for the reference-regression gate, which is UM-8's "approved output becomes references" (later work).

**Affects:** Track 2 step 1b (the removal).

### 5. Learning recipe: SIM-2 and N3

Option letters are unique across the group: SIM-2 is A–B, and N3 is C–E.
Both are decided, and the group also records the requirement to define flows in JSON rather than the flow DSL.

**Settled by the final UI: option A, editable flows stay.**
The Setup tab's *Learning steps* section is built around editing the flow: a route map of the steps, a card per step with its rules and question text, *Add step*, and *Load default setup* to start from one of the example flows.
Fixing the recipe in code would remove that section, which is more than a minor change to the design.
So simplification option B (remove the flow language) is dropped, and with it Track 2's largest removal and the audit's W2 findings about it.
The options are kept below as the record of why the question was asked.

#### N3: the default setup

**Decided, 18 November 2026: option D, `ESCALATING` (*Quick, then careful*) is the default setup for a new Learning Flow**, subject to a `TestLiveScenarios` run.

- **Why:** under the checks model (group 4) a check applies only to steps it can measure. `DIRECT` has no split step and no target step, so wholeness, preservation and fidelity, three of the default gates, would never apply, and XML and JSON inputs would be judged as one record each. `ESCALATING` gives every default gate something to measure, and it is what the demo content already runs "for formats nobody has seen yet".
- **Before switching:** run `TestLiveScenarios`, which compares `DIRECT`, `TARGET_FIRST` and `ESCALATING`, and record the results. If they show `TARGET_FIRST` clearly better, choose that instead and record why.
- **Where the default is set today**, all to change together: `ShapeshifterAiDoc.DEFAULT_FLOW`, the fallback in `LearningFlow` ("a document naming no steps is read as `DIRECT`'s"), the fallback in `ShapeshifterAiSerialiser`, and the Setup tab's starting flow (`ApproachSectionController`). Changing the fallback changes how a stored document with no steps is read; nothing is released, so only dev and demo documents and the scenario tests that rely on the old default are affected.
- **Work:** S, plus the `TestLiveScenarios` run. Part of Track 2 step 1c, with the move to JSON below, since both change how flows are defined.

#### Flows defined in JSON, not the flow DSL

**A requirement (18 November 2026): stop using the flow DSL, and define flows as JSON, using Stroom's normal Jackson conversion between JSON and objects.**

**Where the DSL is today.** A Learning Flow is already stored as JSON: `LearningFlow`, `FlowStep` and `Transition` are Jackson-annotated, and the document serialiser reads and writes them with `JsonUtil`. The DSL is a second, text form of the same objects, which a person never edits (the Setup tab edits steps on cards, never as text):

- `FlowStep.parse(String)`, a hand-written parser of lines such as `CONFIGURE parser on yield-short goto split`, with its own error messages;
- the written form, `FlowStep.toString()`, `Transition.toString()` ("the transition as it is written") and `LearningFlow.toString()`;
- the built-in default setups, `FlowExample`, which are written as DSL lines and parsed when the class loads;
- over 100 `FlowStep.parse` calls in tests, across `stroom-core-shared`, `stroom-learning-flow` and `stroom-app` (none in `stroom-core-client`), plus 18 in `FlowExample`;
- examples written in the DSL in the design notes, the rulings, `FlowWords`' and `FlowProblem`'s wording, and this plan.

**The change.**

- **The default setups become JSON resources** (for example `escalating.json`, `target-first.json`, `direct.json` beside `FlowExample`), read with the same Jackson mapper as a stored document, so a built-in setup and a saved Learning Flow are the same format.
- **Tests build flows from JSON** (fixtures, or the same resources), or from the objects' builders, not from DSL lines.
- **`FlowStep.parse`, the written forms and the DSL's grammar are deleted.** Where a person sees a step or a rule (the Setup tab, stop reports, `FlowProblem` messages, the progress strip), the wording comes from `FlowWords`, the plain-language form the UI already uses, never from the DSL.
- **The JSON is the objects as they are**, with no custom serialisers: one model, one format. Tidy the model where the DSL shaped it, for example the reserved `goTo` names (`end`, `park`, `give-up`), which the JSON can name more plainly. Make the step-model change together with Track 1 task 10, which removes `FlowStep.checks`, so the step model changes once.
- **Documents:** the stored form is already JSON, so no document migration is needed: nothing is released (AS1), so dev and demo documents are reset or re-saved after task 10 and any tidying of the model, as N5 does for the database.
- **Design notes and rulings** that show flows in the DSL are updated to JSON (the module's `design/05-learning-flow.md` especially); this plan's DSL examples are kept as the record of the question as it was asked.

**Work:** M, mostly mechanical: the three default setups, the parser and written forms removed, over 100 test call sites, and the design notes. It is Track 2 step 1c; only its step-model change is done with Track 1 task 10, and the N3 default change and the test call sites follow separately.

**N3, as it was put.**
What was left of N3 after SIM-2 was a smaller question: **which default setup a new Learning Flow starts with**.
The client starts a new document from `DIRECT` (*Straight to it*); the audit's points about `DIRECT` (no SPLIT, so no record boundary for XML and JSON; no TARGET, so no target, preservation or fidelity checks) argued for making `ESCALATING` (*Quick, then careful*) or `TARGET_FIRST` (*Agree the events first*) the default, not for removing flows.

**The question, as it was asked.**
The order of questions is data on each document, written in a small flow language that a person can edit (then on the Learn tab, now on the Setup tab).
SIM-2 asked whether the recipe could be fixed in code.
N3 asked which recipe, because the audit found the one proposed (`DIRECT`) drops steps the demo depends on, and because the unknown-models proposal assumes flows stay editable so that step size can be a property of the document (W2-6).

A flow looked like this in the DSL, which is being replaced by JSON (`ESCALATING`, abridged):

```text
CHAIN
SPLIT
CONFIGURE parser on yield-short goto split
first: CONFIGURE transform candidates 2
       on passed goto end on spent goto target
TARGET kinds 3
again: CONFIGURE parser
CONFIGURE transform on preservation-short
       goto again on spent goto admit
ADMIT
```

**Options for SIM-2.**

- **A. Keep the editable flow language (chosen, with the final UI).**
  - For: strategies can be tried without a code change, and the unknown-models step sizes fit as more flows.
  - Against: every site can run a different, untested strategy, unattended on automatic feeds; the server must parse, validate and run arbitrary flows; the client keeps the flow editor.
    The first risk is reduced by the default setups and the store refusing a flow with problems, but not removed; review by default (SIM-1 option B) is the main guard.
- **B. Fix the recipe in code (was recommended), with a few plain settings** on the document: the model, retries per step, budgets and scorers.
  - For: every document runs a tested recipe; the flow language, its shared types and most of the flow editor go.
  - Against: experimenting needs a release.
    The audit puts the effort at L, not M: the server saving is about 1,500 lines (only about 250 of `Conversation`'s 1,288 lines interpret flows), about 35 scenario tests are bound to the example flows, the allowed-elements field has to come back to the document (reversing A95), and the progress strip needs the recipe from somewhere.
    The final UI would also lose its *Learning steps* section.

**Options for N3 (which recipe), as asked.** With flows kept, read "recipe" as "default setup for a new Learning Flow".

- **C. `DIRECT`** (chain, parser, transform, admit), as the simplification document proposed.
  - Against: with no SPLIT step, XML and JSON inputs get no record boundary and the transform isn't run one record at a time.
    With no TARGET step, the target, preservation and fidelity checks never run.
    This is a behaviour change, not a simplification.
- **D. Close to `ESCALATING`, keeping SPLIT and TARGET (chosen: `ESCALATING`).**
  The demo already runs `ESCALATING` "for formats nobody has seen yet".
  - For: keeps today's behaviour for XML and JSON.
  - Against: keeps most of `Conversation`, so the saving is smaller.
- **E. `TARGET_FIRST`.** 27 of the scenario tests use it, so it would move fewest tests, but it hasn't been compared with the others on the live scenarios.

Before choosing, run `TestLiveScenarios` (which compares `DIRECT`, `TARGET_FIRST` and `ESCALATING`) against the candidates, and record the results.

**How step sizes fit.**
The unknown-models proposal adds coarse steps (parser and XSLT in one reply) and a whole-pipeline agent loop.
With flows kept, these become new kinds of step and new default setups, offered under *Load default setup*, rather than a code setting.

**Example: what DIRECT loses.**
An `OTEL-TRACES` JSON stream holds many spans under `resourceSpans`.
With SPLIT, the boundary `resourceSpans` is learned, and the transform is shown and run one span at a time.
With `DIRECT`, there is no boundary, so the whole stream goes through the transform as one record, and the target and preservation checks never run.

### 6. Prompts: SIM-5, N9 and D2

Option letters are unique across the group: SIM-5 is A–B, N9 is C–E, and D2 is F–H. [learning-flow-single-target-schema.md](learning-flow-single-target-schema.md) uses the same letters for D2; the audit, written earlier, calls them A–C.

#### SIM-5: prompts in code

**Settled by the final UI: per-document question texts stay, and the notes field is added beside them.**
Each step's card on the Setup tab shows the question text it is asked with, which can be edited or loaded from a built-in, and the *AI model and instructions* section has a free-text *Instructions to the AI* field.
A pipeline's Learning Status tab also keeps a per-feed *Note to the AI* (guidance).
So the UI has both option A's overrides and option B's notes field (SIM-5's options).
Simplification option E (prompts only in code) is dropped.
That makes N9 more important, because past turns may have been asked in a document's own words as well as an older built-in.

**The question, as it was asked.**
Each document can override the built-in text of every question it sends to the model.
Should prompts live only in code?

**Options.**

- **A. Keep per-document overrides (chosen, with the final UI, together with B's notes field).**
  - For: a site can tune wording without a release; D2 option F depends on it.
  - Against: each site can quietly run an untested prompt, and on an automatic feed nobody sees the effect before it goes live.
- **B. Code only, plus a free-text "notes for the model" field per document (was recommended).**
  - For: prompts are reviewed and tested like code; support can tell what the model was asked; the notes field gives sites a safer way to add context.
  - Against: a site can't change the wording, only add to it.

**Example.**
A site edits the transform prompt to "output only the fields you're sure of".
Its events now pass validation but lose detail, and nobody else's tests show it.
Under option B, the site would write "the `src` field is the client address, not the server's" in the notes field instead, which adds context without replacing the rules.

#### N9: recording which prompt the model saw

**Decided, 18 November 2026: option D, extended.** Record what the prompt was built from, not the prompt itself, and rebuild the prompt as submitted for a person allowed to see the data.

- **Recorded on each learning run** (`shapeshifter_attempt`, in the unreleased migration, N5), and again when a paused run resumes:
  - the text of every question template the run used, the built-in ones and the Learning Flow's own, with the built-in version (`Templates.VERSION`). Recording the text rather than only the version means the prompt can be rebuilt after an upgrade without the old templates in the code;
  - the Learning Flow's *Instructions to the AI* and the pipeline's *Notes to the AI*, as the run had them;
  - the build of Stroom that ran it, so that a rebuild after an upgrade can say the checks' wording may have changed.

  None of this is stream text. The sample is already identified by the run (the stream and the part of it learned from), so it isn't copied.
- **Rebuilt when viewed.** On the Learning Status tab, a person with the *View Data* permission who opens a question in the conversation sees the prompt as it was submitted: the recorded templates and texts, filled with the sample read from the stream, and the feedback re-derived by re-walking the run (A45: a re-walk over the same sample re-derives the same questions). Anyone else sees the summary, as today, and a sentence saying why (as the Quality Assurance tab's samples do).
- **Limits, said where they apply:** a prompt can't be rebuilt once its stream has been deleted (retention), and after an upgrade the re-derived feedback may differ from what was sent if the checks' wording changed; the conversation says so rather than showing a guess.
- **Resuming uses the recorded templates**, so a paused run that resumes after an upgrade or an edit shows the model its own history in the words it was first asked, not new ones.
- **Why not option E** (store the rendered text): it would copy stream text into `shapeshifter_turn`, where it could be read without the data permission and would outlive the stream under retention, and it repeats the sample in every question. The module already keeps pointers rather than copies (`shapeshifter_corpus` holds a stream id and segment, not the record).

**Work:** M. Server: record the template set and texts per run, a service that rebuilds a turn's prompt behind the *View Data* check, and resume rendering from the recorded set. Client: the conversation's *what was asked* shows the rebuilt prompt, or the summary and why. In Track 2 step 1 (server) and step 4 (client).

**The question, as it was asked.**
SIM-5's support benefit ("what exactly did the model see?") doesn't hold today.
Turns store only a question summary, such as "Configuration: XSLTFilter XSLT", and every earlier question is re-rendered from the current templates on each call (W2-5).
After an upgrade, a past turn can't be reproduced, and a resumed attempt's history is shown to the model in new words.

**Options.**

- **C. Record nothing more.** Accept that past turns can't be explained after an upgrade.
- **D. Record the prompt version per turn (chosen, extended as above).** One column holding `Templates.VERSION`. Cheap, and enough to find the wording in source control.
  With per-document question texts kept (SIM-5), it only identifies the built-in wording; a turn asked in a document's own words would also need the document's version, or option E.
- **E. Record the rendered text per turn.** Exact, but much more storage, and it includes sample data.

#### D2: how the model is told about a schema other than event-logging

**A requirement (18 November 2026): every target schema must be usable**, not only event-logging.

**Decided, 18 November 2026: option G, generate a structural summary from the XSD**, since it is the option that gives any schema a usable prompt without work at each site.

- The prompt gets a summary generated from the target schema's XSD: the root element, the record element, the required children in order and the allowed choices, capped in length. The hand-written event-logging guidance stays as the built-in text when the target is event-logging.
- A site can add anything the summary misses through *Instructions to the AI*, or a step's own question text (SIM-5 keeps both).
- The wording is tested against at least one schema other than event-logging, with a second XSD test fixture (already in Track 1 task 7).

What "every target schema must be usable" means beyond the prompt, and so for Track 1 task 7:

- **Where the target schema is set** is decided (D1 option A): one schema version, on the Learning Flow.
- **Checks that read event-logging's own fields** must work for other schemas or say they don't apply: time plausibility reads `EventTime/TimeCreated`, so its time field is named per Learning Flow (a field beside its window, defaulting to event-logging's); extraction quality reads `EventDetail` and `Unknown`, so it doesn't apply to other schemas unless its fields are named. A check that doesn't apply to the Learning Flow's schema is shown as such under *Quality checks*, not silently passed.
- **The other event-logging assumptions** the audit found (W1-1 to W1-7: the Java-literal wording, ADMIT for other schemas, `Data` counted as untyped, "a record is a child of the root" in five classes), and preservation's records:2 assumption (W5-6, Track 1 task 1), are part of the same work.
- **Effort:** task 7 is L rather than M.

**The question, as it was asked.**
The prompts describe event-logging in hand-written text.
If the target schema can be something else (D1), how does the model learn that schema's structure?

**Options.**

- **F. Require a template override** in which someone writes the rules by hand.
  Possible again, since the final UI keeps overrides (SIM-5), but it puts the burden on every site that uses another schema.
- **G. Generate a structural summary from the XSD (chosen):** root element, record element, required children in order, and allowed choices, capped in length.
  - For: the schema is really set in one place, and any schema gets a usable prompt.
  - Against: more work; the wording must be tested against real schemas.
- **H. Generic rules only**, and let the model learn the structure from validation errors.
  - Against: costs more attempts and budget, and is likely to stall on schemas with deep required structure.

Whichever is chosen, the hand-written event-logging guidance stays as the built-in text when the target is event-logging.

**Example (illustrative).**
For a schema `acme-audit:1`, option G would add something like this to the prompt:

```text
Target schema: acme-audit:1 (namespace urn:acme:audit:1), root element <AuditLog>.
Write one <Entry> per input record.
Each <Entry> has, in this order: <When> (required), <Who> (required), <What> (required), <Outcome> (optional).
<What> contains exactly one of: <Login>, <Logout>, <FileAccess>.
```

**Affects:** Track 1 task 7 (M, or L with option G).

### 7. Where settings live: D1 and N6

Each option, with mockups of the Setup tab, the feed's Settings tab and the Learning Status tab, is described in [learning-flow-group-7-settings-options.html](learning-flow-group-7-settings-options.html).
That page also describes a per-pipeline setting for each, as a property of the AI Translator element: N6 option G and D1 option H.

Option letters are unique across the group: D1 is A–B (and H on the options page), and N6 is C–F (and G on the options page).

#### D1: the target-schema setting

**Decided, 19 November 2026: option A, a *Target schema* field on the Learning Flow, under *AI model and instructions*.**
Everything that depends on the schema already lives in the Learning Flow: the business rules, the required fields, the prompts, and the Target and Admit steps.
So the target belongs there too, and each target schema gets its own Learning Flow.
The field names **one schema version, not a schema group**: `EVENTS` holds seven event-logging versions, from 3.0.0 to 4.1.0.

What this means for the work (Track 1 task 7):

- The field picks one XML schema (its namespace and version), and the prompts, the target checks, the XSD summary (D2 option G) and the Schema-valid check all use that one schema. The Schema-valid check validates against it, not against every schema in a group, and its `schemaGroup` setting is removed.
- On the Setup tab it is a picker of XML schemas, not a group text box, with the route map's summary for the section naming it. Workbench stories: default, read-only, dialog OK and Cancel.
- A per-pipeline target (D1 option H on the options page) is not adopted.
- **The default for a new Learning Flow is the latest event-logging version** (decided 19 November 2026): the newest event-logging XML schema installed when the Learning Flow is created. It is saved on the document, so installing a newer version later doesn't change existing Learning Flows; moving one to it is a deliberate edit, followed by learning again.
- The built-in guidance written by hand for event-logging (`Templates.java`, today `event-logging-v3.0.0.xsd` and `Version="3.0.0"`, with its `EventDetail` branches) is checked against the latest version and updated, with the namespace, location and version filled in from the chosen schema (D2) rather than written into the text.
- Where no event-logging schema is installed, the field starts empty and the readiness banner says so, like a missing model.

**The question.**
Today the target schema is the conformance scorer's `schemaGroup` parameter, while the prompts and target checks assume `event-logging:3`.
Where should the one setting live?

**Options.**

- **A. A top-level field on the Learning Flow (recommended).**
  - For: the schema describes what the document produces; it is still defined if the conformance scorer is removed.
  - Against: a document migration and a new field on the Setup tab, most naturally under *AI model and instructions* or *Quality checks*, with its route-map summary and workbench stories.
    This is a small addition within the final UI design.
- **B. Keep it on the conformance scorer**, and have the prompts and checks read it from there.
  - For: no new field.
  - Against: a document without that scorer has no target, so it needs a fallback, which hard-codes event-logging again.

#### N6: where a feed's time zone lives

**Decided, 19 November 2026: option F, with a per-pipeline property in place of the feed's or document's zone.**
The time zone is set per pipeline, not on the feed document: a text property, `timeZone`, on the AI Translator element, which in practice is one per feed.
The zone of timestamps that carry no offset comes from, in order:

1. the stream's `TimeZone` header, if it has one;
2. otherwise the pipeline's `timeZone` property;
3. otherwise UTC (decided 19 November 2026, replacing "the AI states the zone it assumed", and in preference to the server's time zone, which can differ between nodes).

The stage puts the resolved zone into the stream's metadata as `ResolvedTimeZone`, UTC included, so the learned stylesheet always reads a zone with `stroom:meta` and passes it to `stroom:format-date`; changing the zone needs no relearning.
The model is never asked to choose or assume a zone.

**The header** is `TimeZone`, a Stroom header like `Feed`, `System` and `Environment`, kept as a stream attribute.
HTTP has no standard header for a sender's time zone (`Date` is always GMT; vendor headers such as `Time-Zone` or `Prefer: outlook.timezone` don't agree), and the header describes the data's zone, which may not be the zone of the proxy or forwarder that posted it.
Its value is an IANA zone ID such as `Europe/London`.

What this means for the work (Track 1 task 3, fix 4):

- `meta` must be on Track 2 step 2's list (it was a candidate in N1), with a test that a stylesheet reading the zone with `stroom:meta` passes the static check and the harness.
- Every judged run outside a processing task (the worker, for each attempt; Supervisor actions that judge) sets both `MetaHolder` and `MetaDataHolder` with `ResolvedTimeZone` (already part of task 3), so the resolved zone and the stream's time are there while learning as well as while serving.
- The `timeZone` property is checked as a zone ID when the pipeline is saved, since it is a plain text property.
- The Learning Status tab shows the zone in use and where it came from (header, pipeline, or UTC because neither was set).
- The header is documented for senders and proxies, and an invalid value is reported, not silently treated as UTC.
- The stage writes the resolved zone under the metadata key `ResolvedTimeZone` (decided 19 November 2026), never overwriting the sender's own `TimeZone` attribute, and the stylesheet reads `stroom:meta('ResolvedTimeZone')`.
- **Known limitation, accepted:** pipelines made by Content Templates inherit the template's `timeZone`, so a feed's own zone is set on its pipeline by hand. Content Templates can gain per-pipeline property values later if needed; most installations take data from systems in the same zone, so the template's value will usually be right.

**The question.**
Nothing in the Learning Flow module knows a time zone, and the prompt tells the model to write times "with a trailing Z" (S9).
Receipt headers reach the stage but are never read.
Where should the time zone of timestamps that carry no offset come from?

**Options.**

- **C. A field on the Learning Flow.**
  - For: one place, beside the target schema.
  - Against: one document can serve many feeds (the demo's `Any feed` document does), and they may be in different zones.
- **D. A property of the feed.**
  - For: the zone belongs to where the data comes from.
  - Against: a change to the feed document, outside the Learning Flow module.
- **E. A receipt header, such as `TimeZone: Europe/London`.**
  - For: the sender knows best, and headers already reach the stage.
  - Against: senders often don't set it.
- **F. A combination:** a header if present, otherwise the feed's or document's default, otherwise ask the model to state its assumption rather than silently write UTC.

Whichever is chosen, the zone is shown to the model and passed to `stroom:format-date` as its third argument, which needs N1.
The audit suggests deciding it alongside D1, so that the Setup tab is changed once.

**Example.**
A London firewall logs `Oct 19 09:06:02`, during British Summer Time (UTC+1).
The right `TimeCreated` is `2026-10-19T08:06:02.000Z`.
With no zone, today's prompt makes the model write `2026-10-19T09:06:02.000Z`, an hour out, and no check can tell.

### 8. SIM-4: which Supervisor actions are in v1

**Settled by the final UI: option A, every action stays.**
The Supervisor as a screen is gone.
Monitoring > Smart Pipelines and a Learning Flow's Pipelines tab only list pipelines; every action is on the pipeline's Learning Status tab, offered only where it applies, by the pipeline's permissions (see [ui-design.md](smart-pipelines/ui-design.md#a-pipelines-learning-status-tab)).
Simplification option D (a smaller Supervisor) is dropped as a UI change.

What is still open is server-side only:

| Action | On screen as | What is left to decide |
|---|---|---|
| Watch | The list, *Where it stands*, *Learning history* | Nothing |
| Approve, reject | *Approve and use it*, *Reject…* | Approval evidence and who approved (Track 2 step 4) |
| Retract | *Remove translation…* | Nothing |
| Relearn, give up | *Learn again*, *Stop learning…* | Nothing |
| Grant | *Give it more…* | Nothing |
| Guidance | *Note to the AI* (per feed), beside the Learning Flow's *Instructions to the AI* | Nothing |
| Improve | *Improve…* | It calls the model inside a web request, against the design's own rule. Move it to the worker (Track 2 step 1); the button stays. |
| Amend a turn | *Answer it myself*, or change an answer and run again, in the conversation | Nothing; the UI keeps it |
| Exhaust | *Finish with Unknowns* | Nothing; it stays with editable flows (SIM-2) |
| Accept | *Accept now* | Goes, with provisional bindings (SIM-3 option E; Track 2 step 1a) |

**Affects:** Track 2 step 1 (Improve to the worker); supervisor-visibility items in Track 1 task 6.

### 9. Pipeline creation: SIM-7, SIM-8 and SIM-9

Option letters are unique across the group: SIM-7 is A–C, SIM-8 is D–F, and SIM-9 is G–H.

#### SIM-7: drop the Pipeline Factory

**Decided, 19 November 2026: option B, drop the factory.**
Content Templates of type `INHERIT_PIPELINE` make each feed's Smart Pipeline, and the Pipeline Factory, its two tables and its migration are removed in Track 2 step 1 (simplification option G).
The template pipeline must leave `learnedFolder` and `learnedName` unset (see the example below).
This relies on SIM-9 option H, since on a default install a Content Template can't make a Smart Pipeline while the `claimTasksOnWorker` requirement stays.

**The question.**
Two features each turn a new feed into a pipeline that inherits from a template: Content Templates (core Stroom, released in 7.9) and the Pipeline Factory (new on this branch).
A feed that matches both gets two pipelines and two filters, so every stream is translated twice, with two learners and twice the model spend.

**Options.**

- **A. Keep both.** Double processing remains, and someone has to make sure they never match the same feed.
- **B. Drop the factory; Content Templates of type `INHERIT_PIPELINE` make each feed's Smart Pipeline (recommended).**
  - For: about 6,000 lines and two tables go, and three security problems the factory has go with it (any run-as user, unclean substitutions, no separation between accounts).
  - Against: no automatic coverage of existing feeds; no record of what was made, with retries; fewer filter settings.
- **C. Keep the factory and stop using Content Templates for AI.** Keeps the security problems, and the factory is unreleased while Content Templates already ship.

**Example.**
Account `1234` sends its first data for `ACME-FIREWALL-EVENTS`.
The Content Template creates the feed in `Feeds/1234`, a pipeline `Feeds/1234/ACME-FIREWALL-EVENTS` inheriting from the AI template, and a filter for that feed.
Its learned content lands in `Feeds/1234/ACME-FIREWALL-EVENTS learned`, which the account's groups can already see.
`learnedFolder` and `learnedName` must be left unset on the template pipeline, or every feed would write to the same place.

#### SIM-8: AI on existing feeds

**Decided, 19 November 2026: option D, by hand.**
A person adds AI to an existing feed, which is itself the decision that its data may go to the model (AS11), by creating a pipeline that inherits from the AI template, with a filter and a minimum creation time, and then disabling the old translation filter.
Option F went with the factory (SIM-7); option E, a scan trigger on Content Templates, can be reconsidered later if sites ask for it.

**The question.**
Must existing feeds get Smart Pipelines automatically?

**Options.**

- **D. By hand, per feed, once its data is approved for the model (recommended).**
  A person creates a pipeline inheriting from the AI template, with a filter and a minimum creation time, then disables the old translation filter.
- **E. Automatically, with a scan trigger added to Content Templates later**, off by default, with a minimum creation time for AI templates.
- **F. Automatically, through the factory.** Not available if SIM-7 option B is chosen.
  It also attaches a second pipeline beside any hand-built one, so every stream is translated twice, and unless a minimum creation time is set it sends the feed's history to the model.

#### SIM-9: drop the `claimTasksOnWorker` requirement

**Decided, 19 November 2026: option H, remove it after a test-cluster trial with the master queue.**
The trial is part of Track 1 task 5; if it shows woken tasks are missed (AS5), the decision reopens, and with it SIM-7 option B. **No cluster is available (20 November 2026), so the trial becomes an inspection** — which can show that the code intends the right thing but cannot show that two nodes do it.

**The question.**
The Learning Flow module refuses to create a filter over a Smart Pipeline unless the experimental `stroom.processor.claimTasksOnWorker` setting is on, which needs the whole cluster stopped to change.
The stated reason, that the master queue wouldn't see woken tasks, doesn't hold: waking is a database update the master's next fill reads, and `TestDeferredTask` already covers it in both modes.

**Options.**

- **G. Keep the requirement.**
  Every site needs an experimental mode to use Smart Pipelines, and a Content Template that makes a Smart Pipeline fails at receipt on a default install (so SIM-7 option B wouldn't work).
- **H. Remove it, after a test-cluster trial with the master queue (recommended).**
  A wake then takes up to about 10 seconds plus the queue's fill cycle; worker claiming stays available as a site's choice.

**Affects:** Track 1 task 5 is SIM-9; Track 2 step 1 (option G) needs SIM-9.

### 10. N7: counting spend when usage is unknown

**Decided, 19 November 2026: option B.**
When a provider reports no usage, tokens are estimated at about 4 characters a token from the prompt and reply, and failed calls are charged, so the spend breaker (Track 2 step 3) can trip.
Option C, a separate limit on the number of calls, is not adopted now.

**The question.**
When a provider doesn't report token usage, which some OpenAI-compatible local servers don't, the module counts zero tokens, so the token budget never trips and spend reads zero (S10).
Failed calls also count as zero (W6-3).
The planned spend breaker would never open.

**Options.**

- **A. Refuse models that don't report usage.** Simple and strict, but rules out some self-hosted models.
- **B. Estimate tokens from characters when usage is missing, and charge failed calls (recommended).** An estimate of about 4 characters a token is enough for a breaker.
- **C. Add a second limit on the number of calls**, which needs no usage at all, alongside A or B.

**Example (illustrative).**
A site points the module at a local server that omits `usage`, with a token budget of 200,000.
A feed that keeps failing runs 300 sessions overnight; the budget and the spend counter both read zero.
Under option B the budget trips part-way through the first session that exceeds it.

### 11. N8: several nodes learning at once

**Decided, 19 November 2026: option A, sound for the first release.**
Track 1 task 8 is in scope for the first release and can start now.

**The question.**
The audit found races when several nodes learn and serve the same learner (W7-1 to W7-4).
For example, a node with a stale cache can open a new attempt after a promotion and append a second binding for the same learner; nothing in the table prevents it.
Must this be sound for the first release?

**Options.**

- **A. Yes (recommended if any multi-node installation will run it).** Track 1 task 8: a backstop expiry, a guard against a second live binding, a version-checked `replace`, waking first-learning deferrals, and a two-cache test harness. Effort M.
- **B. No: the first release supports one node only**, stated in the documentation, with task 8 later.
  Cheaper now, but it rules out clustered installations until task 8 is done.

### 12. N5: editing the unreleased migration

**Decided, 19 November 2026: option A, edit `V08_00_00_001` in place until the first release, and reset dev and demo databases.**
This confirms what the UI merge already did. The rule is recorded in a comment at the top of the migration, and `generateJooq` is run after each change.

**The question.**
The Learning Flow module has one database migration, `V08_00_00_001`, which hasn't been released.
Several steps (removing provisional bindings, the children-of boundary, the prompt-version column) change tables.
Edit that migration, or add new ones?

**Options.**

- **A. Edit it in place until the first release, and reset dev and demo databases (recommended).**
  - For: the release ships one clean migration, not one that creates columns and the next that drops them.
  - Against: every database that has already applied it fails Flyway's checksum and has to be reset.
- **B. Add migrations (`V08_00_00_002`, …).**
  - For: existing dev and demo databases keep working.
  - Against: the release carries the branch's history; the migration that removes provisional bindings must also map old rows.

Whichever is chosen, run `generateJooq` after each change.

The UI merge had already in effect chosen option A: it edited `V08_00_00_001` in place to add `shapeshifter_attempt.report`, `shapeshifter_output.record_count` and the `shapeshifter_assessment` table.

### 13. UM-9: the order of the raised tasks

**Decided, 19 November 2026: option A, gates first.**
Track 1's order, adjusted for the audit, stands.

**The question.**
In what order should the raised tasks be done?

**Options.**

- **A. Gates first (recommended):** preservation, parser drift fix 1 and missing year fixes 1 and 3, then mixed record elements and the single target schema.
  Supervisor visibility items 3 and 5 are small and can ship at any time (and are largely done; see Track 1 task 6).
- **B. What users see first:** supervisor visibility and the single target schema, then the gates.
  Easier to demonstrate, but the later gates would change what the Learning Status tab shows.

Track 1 already follows option A, adjusted for the audit.
Confirming it is all that is needed.

## Track 1: correctness fixes (mostly server)

These change the checks and the serving code.
The final UI shows their results (stop reports, chips, quality figures) but needs no change for them, except where a task says so.
Re-find each task's audit findings in the merged code before starting (AS2): the UI merge changed `Stage`, `Conversation`, `Supervision`, `StageFactory`, `AttemptsDao`, `OutputsDao`, `FlowStep` and `StepDetails`.
The order is the one the unknown-models proposal recommends, adjusted for what the [code assumption audit](learning-flow-code-assumption-audit.md) found, and task 10 goes before the new checks in tasks 2 and 3 despite its number.
The audit's finding IDs are given so each task starts from what is already known.

| # | Task | Fixes to do | Needs | Effort | Audit findings |
|---|---|---|---|---|---|
| 1 | [Preservation check matches record markup](learning-flow-preservation-matches-record-markup.md) | The proposed fix in that document. It currently stops JSON feeds such as `OTEL-TRACES` from learning at all, and preservation is one of the promotion gates. | Nothing | As in that document | W5-6 (preservation assumes records:2 `data/@value`) |
| 2 | [Parser drift not detected](learning-flow-parser-drift-not-detected.md) | Fix 1, re-scoped: measure input coverage with a filter in the real pipeline run (the one refactor i needs), report it through `ServedStream`, make the stand-in runner agree, check it in the worker's full pass and `settle` as well as in `Stage.stream`, and stop the unbounded relearn after `Kept`. Build completeness as a check (default Gate; UM-2): records in = events out + declared skips, with the way a translation declares a skip designed first. **Started 20 November 2026**; see [task 2 as built](#task-2-as-built-so-far-20-november-2026) | N2 (decided: option B); UM-2 (decided); task 10's structure of checks (for completeness); the declared-skips design: **decided and built, 20 November 2026** (A148) | M to L | W4-1 to W4-7, S2, W5-8 |
| 3 | [Missing year hard-coded](learning-flow-missing-year-hard-coded.md) | Fix 1: replace the prompt's "trailing Z" sentence and explain partial dates. Fix 3: a time plausibility check, in the one structure of checks, Advice by default, with its window (how far before and after the stream's received time) settable per Learning Flow; regression corpus records keep their own stream's received time. For every judged run outside a processing task (the worker, for each attempt; Supervisor actions that judge), set both `MetaHolder` (for `format-date`) and `MetaDataHolder` with `ResolvedTimeZone` (for `stroom:meta`), replacing the previous attempt's, since the worker runs a batch of attempts in one pipeline scope (AS6); with a `stroom-app` test that judging and serving the same stream give the same date. Fix 4 (N6): the `timeZone` property on the AI Translator element, checked as a zone ID on save; resolve header, then property, then UTC; write `ResolvedTimeZone`; report an invalid header; show the zone and its source on the Learning Status tab (a small UI change, AS18). Add a rollout note for bindings that already hard-code a year. Build the no-data-like-literals check (default Advice until it has run on the demo feeds without false failures, then Gate) as a check in the one structure of checks (UM-2). | N1 (decided: option C): **met, 20 November 2026** — `format-date` and `meta` are on the list and stand in in the harness. N6 (decided) for fix 4; task 10's structure of checks (for the two new checks): **done** | M to L | S8, S9, W8-1 to W8-8 |
| 4 | [Mixed record elements](learning-flow-mixed-record-elements.md) | **Done, 20 November 2026.** Fix 1 as recommended: a "children of element" boundary as a real field (`RecordBoundary.children`), answered `container/*` or `*`, with the one consumer of the boundary handling it explicitly. A container occurring once in the sample is no longer refused — half of why `GATEWAY-AUDIT` could not be learned. N2 option E built too: a single XML or JSON document larger than the prefix is refused with a reason and what to change, but only where the sample looks like markup, so a long text stream is untouched. See *What was built* in the task document. | N5 (decided: option A) for the new column — **needed after all**: a binding's boundary is stored in columns (`boundary_element`, `boundary_array`, `boundary_depth`), so `boundary_children` was added to the unreleased migration on both `shapeshifter_binding` and `shapeshifter_output`, with jOOQ regenerated. Found while reading `LearnerBindingsDao` for task 8; without it a node would have lost a children boundary silently | M, plus S for the refusal | W5-1, W5-2, W5-4, W5-5, W5-8 |
| 5 | [Remove the `claimTasksOnWorker` requirement](smart-pipelines/remove-claim-tasks-on-worker-requirement.md) | **Done, 20 November 2026.** Option H as written: the check, `SupervisedPipelines` and `SupervisedPipelineCheck` are gone, two scenarios run both ways round, and the design documents are corrected. **No cluster was available, so §7.2's trial became an inspection** — the four questions answered by reading the wake, the fill and the reaper, with what it cannot show written beside it in §7.3. AS5 is weakened accordingly. | Nothing | S, plus the inspection | W7-6 |
| 6 | [Supervisor visibility](learning-flow-supervisor-visibility.md) items 3 and 5 | **Largely done on the UI branch.** Item 3: `StopReports` gives every stopped run an error, details and suggestions, shown on the Learning Status tab; check it covers every candidate failing the same way, the model's refused reply, and a truncated sample. Item 5: `Stage.waitingFor(...)` words the waiting reason by what holds the claim; check all three states (waiting for the model, in progress, parked), and correct its text, which still points to "the document's Feeds tab", to the Learning Status tab. The other items are re-scoped against the final UI in that document. | Nothing | S (checks and gaps only) | W7-5 |
| 7 | [Single target schema](learning-flow-single-target-schema.md) | **Started, 20 November 2026 — the setting, the conformance check, the prompts and the generated schema summary.** `ShapeshifterAiDoc.targetSchema` names one `XMLSchema` document (D1 option A); `TargetSchemas` resolves it to a `TargetSchema` — namespace, system id, version and root element all read from the XSD itself, not from the document's name — and the conformance check judges against that version alone, falling back to the group its own settings carry where a document names none, so nothing that exists changes. The `Scorer` SPI now carries the resolved target, since the scorers are shared across documents. **The prompts and the target checks follow it:** `Template.TRANSFORMATION_RULES` carries `${targetNamespace}`, `${targetSchemaLocation}`, `${targetVersion}` and `${targetRoot}` in place of event-logging 3.0.0 in so many words, the chain question names the target's namespace, and `TargetChecks` wraps a proposed event in the target's own root; a document naming no target is asked word for word as it was. `Templates.VERSION` is 10. **A new document starts on the latest event-logging installed** (D1, confirmed by the user on 20 November): eight `stroom-app` scenarios now declare that their own canned answers write 3.0.0, since a test node installs all seven versions and 4.1.0's `VersionSimpleType` allows `"4.1.0"` alone. **The structural summary is generated from the XSD** (D2 option G): `Template.TARGET_STRUCTURE` holds event-logging's hand-written guidance as its built-in text, and `TargetStructure` writes the passage for any other schema by walking its XSD from the root with `ContentModels`, capped at 12 lines or 2,000 characters; an override of the template wins over both. A test holds the split to reassembling into the pre-split text character for character for event-logging, which is the "same prompts, same scores" criterion. A second XSD fixture, `sightings.xsd`, and a check against Stroom's Data Splitter schema for the cap. **23 November 2026:** the Setup tab picker is built, with its stories; untyped `Data` and `Unknown` are measured only for event-logging (W1-4); the questions name the target's own record element (W1-1); and, by the user's decision on W1-3, a target that isn't event-logging has no fallback: a spent transform stops for a person, and *Finish with Unknowns* is refused. **Done, 23 November 2026:** `schemaGroup` removed (no migration, AS1); time plausibility's time field named per Learning Flow, with the check's window editable in its dialog; *Finish with Unknowns* hidden in the client for another schema; `TargetChecks`' refusal in the target's words; W1-5 stated as a limit, by the user's decision, with a Setup tab warning; W1-6 held by a test, the demo document keeping no target so it works on any install. **Task 7 is complete.** **Not re-run after this slice:** the `stroom-app` scenarios and the workbench stories. Original scope: the Learning Flow's *Target schema* field, naming one schema version (D1 option A), defaulting to the latest installed event-logging version, with the hand-written event-logging guidance updated to match; the prompt's schema summary generated from the XSD (D2 option G); the Java-literal wording, ADMIT for other schemas, `Data` counted as untyped, "a record is a child of the root" in five classes; time plausibility's time field named per Learning Flow; checks that read event-logging's fields shown as not applying to other schemas unless their fields are named; and a second XSD test fixture. | D1 (decided: option A); D2 (decided: option G); for the check-related parts, task 10 and the *Quality checks* client change (checks shown as not applying; removing `schemaGroup`, which the client uses, AS13) and task 3 (time plausibility's time field); a Setup tab change for the *Target schema* picker | L | S1, W1-1 to W1-7 |
| 8 | Sound multi-node learning (new) | **Done, 20 November 2026.** All five parts; see [task 8 as built](#task-8-as-built-20-november-2026). **W7-7 is not covered** — it is not in the task's list and the audit calls it minor. | N8 (decided: option A) | M | W7-1 to W7-4, W7-7 |
| 9 | Small fixes (new) — **done, 20 November 2026**; see [task 9 as built](#task-9-as-built-20-november-2026) | The worker's encoding (S3); `heldOutFraction` for XML and JSON (S5); one sample-size setting, in bytes, that the prompt honours (S4; N2 option F): remove the 6,000-character prompt cap, count bytes, keep it within the prefix, and optionally cap it at a share of the model's *Max context window tokens* where that is set, and say in a stop report when the sample a step judged was cut, and by how much (supervisor visibility item 3, left from task 6); the JSON description (W5-7), the default element list (S6) being done on the UI branch (`FlowStep.LEARNABLE_ELEMENTS` offers `XMLFragmentParser`; confirm only); a token budget of 0 (S11); `asDocument`'s `"<Events"` test (W1-2); the stale comment (W3-4); the corpus stream type (S13, verify first); correct the comments that still give redaction (rulings A17 and A38, superseded by A146) as the reason stream text isn't stored, in `Question`, `Inputs`, `Stage`, `ModelAdvisor` and `Sample`, and `Question`'s claim that `stroom-ai` audits the raw exchange, which no code does. | Nothing | S each | As listed |
| 10 | One model of checks (new; UM-2 and UM-3) | Designed in [learning-flow-checks-model-design.md](learning-flow-checks-model-design.md), whose decisions D1–D6 were settled on 19 November 2026; among them, the comparison with the incumbent's score goes (ruling A15 amended), and the regression corpus gate takes its place. The server side of [How checks work](#how-checks-work-decided-18-november-2026): one list of checks on the Learning Flow, each Off, Advice or Gate, with compile fixed as a gate; wholeness, preservation, fidelity and the regression corpus brought into the list (wholeness's 90% as its default threshold); a candidate passes when every gate passes, with weights, the weighted total and `promotionFloor` removed; the serving record bar built from the gates that judge records; `FlowStep.checks` removed, a check applying to every step it can measure; the displayed and rolling score as the average of the checks that ran; presets redefined; no document migration (AS1): dev and demo documents are reset or re-saved, as N5 does for the database. Tasks 2 and 3 build their new checks into this structure, so it goes first or alongside them; task 1 is independent. Share only the step-model change with Track 2 step 1c (`FlowStep.checks` removed, the JSON model tidied), so the step model changes once; step 1c's `ESCALATING` switch with its `TestLiveScenarios` run, and its conversion of the test call sites, are separable and must not hold up this task. The sequence (AS13): agree the design; add the new check list and states to the shared model and the server; change the client (Track 2 step 4's *Quality checks* change) to use them and stop using `FlowStep.checks`, the weights and the pass mark; then remove those. So this task is in practice server and client work together. | UM-2, UM-3 (decided); the *Quality checks* client change in Track 2 step 4, before the removals land | M to L, with the step-model part of step 1c and the *Quality checks* part of step 4 | – |

Every one of these is now done.
The new checks in tasks 2 and 3 (completeness; no data-like literals and time plausibility) are built into task 10's one structure of checks, so they wait for task 10's server design, and land after or with it.
The rest of each task can start now: task 2's coverage measure, `ServedStream`, runner agreement and the bound on relearning after `Kept`; task 3's prompt wording, the stream metadata and the rollout note.
Task 2's completeness check needed the declared-skips design (settled 20 November 2026; see UM-2 and A148), and task 3's fix that uses `format-date` needs `format-date` and `meta` on Track 2 step 2's list.
Task 7's schema field, prompt summary and W1 fixes can start now; its check-related parts (time plausibility's time field, checks shown as not applying, removing `schemaGroup`) follow tasks 3 and 10.
Task 1 doesn't wait for task 10: it fixes the inside of the existing preservation check, which task 10 then brings into the list.
Task 3 no longer has to wait for task 2: the serving record checks can already defer a stream (W8-6).

Each fix comes with a regression test that fails before the fix and passes after it.
Most can use the scenario fixtures (`stroom-learning-flow/stroom-learning-flow/src/testFixtures/resources/stroom/shapeshifter/ai/scenario`).
Task 3 needs a `stroom-app` test, because only the real pipeline runs Stroom's own `format-date` and `meta` with the stream's metadata; the harness has stand-ins.
Tasks 2 and 4 need one as well, because the stand-in runner and the real pipeline can disagree.
No existing test uses the `netfilter*` or `gateway-audit*` fixtures.
A `stroom-app` test is slower to write and run than a scenario test; the effort sizes in the table don't include it for tasks 2, 3 and 4.

When Track 1 is done, rerun the [demo evaluation](smart-pipelines/learning-flow-demo-evaluation.md) and compare it with the 16–17 November results.
At least NETFILTER (Poor), GATEWAY-AUDIT (not learned) and the NETFILTER drift sample (no events) should change.

### Task 8 as built (20 November 2026)

| Finding | What it was, and what it is now |
|---|---|
| **W7-1**: a lost cache event is never backstopped under traffic | The binding and learner-state caches expired only `afterAccess`, which never expires a **busy** learner — every stream touches it — so a node that missed the clearing event served an old binding, or "no binding", for as long as the feed kept arriving. That is precisely when it matters. They now also expire `afterWrite` |
| **W7-2**: a stale node can append a second live binding | After which the first by id wins and the second is orphaned: serving nothing, visible to nobody, with a regression corpus of its own. `append` of a binding that would serve a learner already served returns the one that is there, and the **database** says the same — `shapeshifter_binding_live` is unique over the learner while the binding is not a draft, so two nodes racing lose the race in the database rather than between two lines of Java |
| **W7-3**: `replace` is last-writer-wins despite its comment | Taking the row lock was not enough: the writer then wrote **every** field back from a copy taken before the other, so a node promoting a provisional binding from its cache reverted a rebind another node had made. `Binding` now carries the version of the row it was read at, `replace` checks it, and a refused write returns false and clears the loser's cache. A binding built rather than read carries no version and is written as before, so a promotion whose row was pruned is still not lost |
| **W7-4**: first-learning deferrals are not swept awake | `stampsWaiting` left out rows with no stamp — a stream waiting for a *first* learning fell short of nothing — so a deferral written just after the promotion's wake waited the full five-minute retry for a translation that already existed. They are included now |
| The two-cache harness | `TestTwoNodesOneLearner`: two `CachedLearnerBindings` over one store, each with its own cache and a bus that reaches nobody else, which is the lost event |

**A trade-off taken deliberately.** Including the unstamped rows means the sweep wakes a learner whose
first-learning rows are still waiting, every pass, until those streams are served — a deferral goes when
its stream is served, not when it is woken. Waking a task that is already `CREATED` is an update of no
rows, and the precise alternative (compare the row's wait against the binding's promotion time) cannot be
tested in the scenario harness, whose in-memory store uses wall-clock time while the stage runs on a fixed
clock. Three scenario tests record the second wake so that it is documented rather than a surprise.

**W7-7 is not covered**: `bind` spans several transactions, so a crash between the append and the close
makes the next worker treat the learner as relearning. It is not in the task's list, and the audit calls
it minor.

### Task 9 as built (20 November 2026)

| Item | What it was, and what it is now |
|---|---|
| **S4**: one sample size limit, in bytes, that the prompt honours | The sample was cut twice — to the Learning Flow's limit, then again by a fixed **6,000 characters** inside the prompt. With the default limit of 8,192 the model never saw the last quarter of its own sample while the parser's split was judged on all of it, and raising the limit above 6,000 did nothing at all. `QuestionText` now cuts to the document's own limit, and the limit is counted in **bytes**, as its label has always said and as a model is billed (`Input.bytes`, `Input.withinBytes`) |
| **S4**, the rest: say when a sample was cut | A `Sample` now knows how much it was cut from, and a stop report says *"The AI was shown 8,192 bytes of the 2,000,000 read from this stream"* — on a step that failed or ran out, not on one a rule stopped deliberately. This is supervisor visibility item 3, left over from task 6 |
| **S5**: the held-out share applies to XML and JSON | A markup or JSON document within the limit was shown **whole**, so A14 ("judged on records the model never saw") did not hold for those feeds, though the editor said it did. Held out by record now, the root closed again — which `wholeChildren` and `wholeItems` already did for the size limit |
| **S3**: the worker's encoding | The worker decoded every stream as UTF-8 whatever the feed said, while serving honoured the feed's encoding — so a windows-1252 or UTF-16 feed was learned from wrongly decoded text and then served correctly decoded. `StreamInputs` reads the feed's encoding; one it cannot name is a reason to go on in UTF-8, not to abandon the stream |
| **S11**: a token budget of 0 | Meant "unlimited" in the editor and "a budget of nothing" on the server, so a document written by import, REST or MCP with `tokenBudget: 0` parked at its first answer |
| **S13**: the corpus stream type | Added to the **stored** types so it can be picked — and so *excluded* from a processor filter written over a feed alone. Deliberately **not** added to the hard-coded set, which is also what a sender may declare on receipt: nobody outside should be able to post a stream claiming to be a translation's corpus, since the corpus is what the next translation is held to |
| **W1-2**: `asDocument` | Decided whether a target was already a document by whether the text held the characters `<Events` anywhere, so a prefixed root was wrapped twice and an event merely mentioning them was not wrapped at all. Read from the root element now |
| **W5-7**: the JSON description | The chain question told the model `JSONParser` produces `records:2`. It produces the `http://www.w3.org/2013/XSL/json` vocabulary — the same mistake that cost seven attempts in the live run of 22 September |
| **S6**: the default element list | Confirmed: `LEARNABLE_ELEMENTS` and the installed runners agree. `TestLearnableElements` now holds them together, in both directions |
| **W3-4**: the stale comment about provisional bindings | Verified gone already; the code moved since the audit |
| The redaction comments | `Question`, `Inputs`, `Stage`, `ModelAdvisor` and `Sample` gave A17's unbuilt redaction as the reason stream text is not stored. A146 settled that samples are **never** redacted, so the reason is simply that a second copy of real data would live under the attempt's retention rather than the stream's. `Question`'s claim that `stroom-ai` audits the raw exchange is gone: no code does |

**Not done**: capping the sample at a share of the model's *Max context window tokens* where that is set.
The plan calls it optional, and it is a guess about tokenisation that the byte limit now makes
unnecessary for the fault S4 was about.

### Task 2 as built, so far (20 November 2026)

Task 2's first item was the design the rest of it waited on: [how a translation declares a skipped
record](learning-flow-declared-skips-design.md), decided on 20 November 2026 as a marker in the output
with no cap on the skip rate, and recorded as ruling A148.

| Part | State |
|---|---|
| The declared-skips design | **Decided and built.** `<Skipped xmlns="shapeshifter:skips" reason="..."/>`: counted apart from the events (`DeclaredSkips`, `Records`, `OutputRecords`), ignored by the Schema-valid check, dropped by the record gate before the writer, and taught to the model (`Templates.VERSION` 7 → 8) |
| Completeness as a check | **Done.** `Check.COMPLETENESS`, `StepOutcome.COMPLETENESS_SHORT`, `CompletenessScorer`: `records in = events out + declared skips`, a **gate in every preset** (UM-2: option C — warnings first — was taken for literals only). It does not apply to a parser, whose records are the input's shape; input coverage judges that step |
| The skip count shown | **Done.** A check that passes can now say something (`Verdict.notices`), so the count reaches the error stream through `Judged.of` and `Supervision`; the served stream carries `ShapeshifterAiSkipped`; the Learning Status tab's row reads *"Scores 98% · 96 of 1,204 skipped"*; and the Quality Assurance tab says it beside the volume. Kept over the same rolling memory as the score (`shapeshifter_learner_state.rolling_skips`) and per stream (`shapeshifter_output.skip_count`), both added to the unreleased migration with jOOQ regenerated |
| Fix 1: what the parser made of the input, measured in the real pipeline run | **Done.** A filter on the parser's fork counts the records it cut (`RecordTap`), its errors are counted before they are de-duplicated (W4-2), and the stream's content bytes are counted as it is read; all three come out through `ServedStream.input` (`InputRead`). The stand-in agrees, held by `TestFragmentRunnersAgree` (W4-7). `Stage.stream` **and** the worker's full pass and `settle` judge it (W4-4) |
| The measure itself | **Records, not characters** (decision of 20 November 2026, W4-3). Learning measures coverage as a share of characters (A36), which cannot be counted over a streamed input without estimating it; the records the parser cut can be counted exactly. The two come out close — on the scenario with six unmatched alarm lines, characters give 0.695 and records 0.700 |
| What defers a stream | A stream the parser read **nothing** of defers whatever the checks say — that is not a quality threshold but the translation not working at all, and the compile gate is fixed for the same reason. Everything short of that goes through the Learning Flow's own checks: records lost through `COMPLETENESS`, a parser reporting records it could not read through `COVERAGE`, and **only as gates** |
| The output row | **Fixed (W4-6).** `outputs.emitted` and the `ShapeshifterAiScore` attribute carry this run's score; `Supervision` writes the attributes **after** the stream is served, so a deferred stream writes none |
| The relearn loop | **Bounded (W4-5).** A stream may open three sessions against the same translation; after that it waits for a person rather than opening a paid session on every five-minute retry |

**What it turned up.** Joining the per-record outputs back into one document (`RecordJoin`) passed on only
the *first* record's namespace declarations, so an element a later record declared its own namespace for
was written with no binding and read back in whatever namespace surrounded it. The skip marker was the
first thing to use a namespace that varies record to record, which is why it had never shown. Fixed, with
`TestRecordJoin` holding it.

**The hole this accepts**, held to in `TestCompletenessScorer.aSkipForEveryRecordStillPasses`: a
translation may declare every record skipped and completeness still passes. That is the decision, not an
oversight, and showing the count is the whole of the mitigation — which is why it was built rather than
left for Track 2 step 4.

**What fix 1 turned up, and is worth a decision.** The demo document has input coverage **off**
(`DemoDocument.checks`, "as the scorers this list replaced had them"). Had the zero-records rule been left
to that check, the dev instance's own fault would still not have been caught on the document it ran. It is
not left to it — a stream the parser reads nothing of defers regardless — but a demo document that cannot
see a feed it reads only half of is worth a second look when the demo evaluation is re-run.

### Task 3 as built, so far (20 November 2026)

| Part | State |
|---|---|
| Fix 1: the prompt explains partial dates | **Done.** The "trailing Z" sentence is replaced; `Templates.VERSION` is 7 |
| Fix 3: the time plausibility check | **Done.** `Check.TIMES`, `TimesParameters` (a year before, a day after, settable), `TimesScorer`. Advice by default, gate under *Strict* |
| AS6: the stream's time when judging | **Done.** `StreamHeld`/`PipelineStreamHeld`, called by `DeferredWorker` for every attempt |
| The `stroom-app` test that judging and serving agree | **Done.** `TestJudgingTakesTheYearFromTheStream`, which also holds `StroomStandIns` to the real `format-date` |
| Fix 4 (N6): the zone resolves and reaches the stylesheet | **Done.** `ResolvedZone`, the `timeZone` property, `PipelineZones`, `ZonedMetaData`; `ResolvedTimeZone` is in the metadata on both paths, and an unreadable zone is reported |
| Fix 4 (N6): the property checked **when the pipeline is saved** | **Not done — see below** |
| The no-data-like-literals check | **Done.** `Check.LITERALS`, `LiteralsScorer`: a year, a date, a zone, or this stream's own feed, environment or system written into the translation. Advice first (option C), gate under *Strict* |
| The rollout note for bindings that hard-code a year | **Done**, in [the task document](learning-flow-missing-year-hard-coded.md). No migration: an existing binding keeps serving, the times check reports against it as advice, and relearning is the fix |
| The zone and its source on the Learning Status tab (AS18) | Not done — a client change, with Track 2 step 4's other additions |

#### A decision N6 needs: there is no hook for checking a pipeline property on save

N6 says the `timeZone` property is "checked as a zone ID when the pipeline is saved, since it is a plain
text property". **There is nowhere to do that.** `PipelineStoreImpl.writeDocument` has no element-level
validation, and no element in the codebase validates in its setter — they assign and nothing more.

What is built instead: a bad zone saves, and is reported as a warning against the element the first time
the pipeline runs, naming what was wrong and saying that times are being read as UTC. Not silent, but
later than N6 asked for.

The ways out, for the product owner and whoever owns the pipeline editor:

- **Accept run-time reporting**, and amend N6. Cheapest, and consistent with how every other pipeline
  property behaves today.
- **Add a validation SPI to `stroom-pipeline`**, so that any element can declare a check that runs on
  save. The right answer for Stroom as a whole, well beyond Track 1 task 3, and it would want its own
  design.
- **Special-case this property** in the pipeline store. Quick, and the sort of thing that is still there
  in five years.

Nothing in the plan depends on which is chosen; the zone works either way.

## Track 2: server simplification, safety items and what the UI would show

The UI rework is done: the Pipelines and Setup tabs, the Learning Status and Quality Assurance tabs, What it learned, and Monitoring > Smart Pipelines are built and merged ([ui-design.md](smart-pipelines/ui-design.md)).
The old Track 2's UI phases are therefore finished or dropped:

| Old step | Status |
|---|---|
| UI phase 1 (words, wiring, hiding Accept, Improve, Exhaust, Answer, Guidance and flow editing) | Dropped. The final UI keeps them all (SIM-2, SIM-4, SIM-5). |
| UI phase 2 (Feeds tab) | Done, as a Learning Flow's Pipelines tab and each pipeline's Learning Status tab. |
| UI phase 3 (quality presets; delete the Learn tab and flow editor) | Presets done (*Lenient*, *Standard*, *Strict*). The Learn and Score tabs are deleted; the flow editor is rebuilt on the Setup tab, not removed. |
| UI phase 4 (Monitoring overview) | Done as Monitoring > Smart Pipelines, without system-wide spending or approval filters (see step 4). |
| UI phase 5 (setup wizard) | Dropped. The readiness banner and default setups cover it. |

The ordering rule still holds: **the client stops using a feature before the server removes it** (AS13).
Since the final UI uses the flow language, every Supervisor action and per-document question texts, those are not removed.
What is left:

| Step | Side | What | Source |
|---|---|---|---|
| 1 | Server | **Done, 20 November 2026.** Review by default and the permission, one promotion path (A), no provisional bindings (C), the unused parts (F), the Pipeline Factory (G), Improve on the worker (A46), and N9 option D extended's recording and resume; see [Step 1 as built](#step-1-as-built-20-november-2026). **Moved to step 4:** N9's rebuild service, which has no reader until step 4's client shows the rebuilt prompt. | [simplification-options.md §3](smart-pipelines/simplification-options.md#3-the-options) |
| 1a | UI | **Done, 20 November 2026**, with step 1's option C. The *On trial* chip, *Accept now*, the *Trust* figure and the "on trial" wording are gone, and so is the endpoint behind them. | [ui-design.md](smart-pipelines/ui-design.md) |
| 1b | Both | **Done, 20 November 2026.** The QA assessments are gone: the client's section, dialog and *Flagged by QA* banner, the shared types, the three endpoints, the server's assessment code and the `shapeshifter_assessment` table (edited out of the unreleased migration, N5; jOOQ regenerated). The Quality Assurance tab keeps its volume, cardinality, samples and run scores. | [Group 4: the QA person's assessments](#the-qa-persons-assessments) |
| 1c | Both | **Done but for the default, 20 November 2026.** The three default setups are JSON resources read with Jackson, with a `flowExamples` endpoint and `BuiltInFlow` DTO, because `FlowExample` is GWT-compiled and the client calls `steps()`: the presets could not be read beside it as the plan assumed, and the user chose server JSON plus REST over builders in shared code. The client fetches them through `FlowExamplesCache`, so the Setup tab's preset naming and *Start from* are asynchronous and the store fills a new document's steps. `FlowStep.parse`, `FlowStep.format`, `Transition.format` and the enums' word parsers are deleted, with all 126 test call sites converted to the new `FlowStep.builder(QuestionKind)`; `design/05-learning-flow.md`, `07-ui.md` and ruling A83's wording are in JSON. **Left:** the `ESCALATING` default (N3), which needs a `TestLiveScenarios` run against a live model. The model tidying the requirement also asks for (the reserved `goTo` names) stays with Track 1 task 10, so the step model changes once. | [Group 5](#5-learning-recipe-sim-2-and-n3) |
| 2 | Server | **Done, 20 November 2026.** The Smart Pipelines list, hard-coded (decision N1, option C): the functions and instructions a learned stylesheet may use, as a list in code, applied by the static stylesheet check and the scenario test harness. See [Step 2](#step-2-a-hard-coded-smart-pipelines-list). | [N1](#1-n1-which-stroom-xslt-extension-functions-a-learned-stylesheet-may-use) |
| 3 | Server | **Done, 20 November 2026.** The spend breaker and rate limit (A24, the policy over A44's counter): see [Step 3 as built](#step-3-as-built-20-november-2026). | [simplification-options.md §5](smart-pipelines/simplification-options.md#5-what-must-exist-before-any-feed-runs-automatically) |
| 4 | Both | **Done, 23 November 2026:** approval evidence and who approved, the spend-breaker state (on the lists' rows and the Learning Status tab rather than the readiness banner), the time zone, and *what was asked* with N9's rebuild service (first walk only); see the work-in-progress note. Additions the final UI doesn't yet show: approval evidence beside a draft (the gate strip, warnings such as literals, and advice) and who approved, on the Learning Status tab; the spend-breaker state, on the readiness banner and Monitoring > Smart Pipelines. These are additions within the design. Also the conversation's *what was asked*, which shows the rebuilt prompt to someone with *View Data* (N9) — **and the server service that rebuilds it**, moved here from step 1 on 20 November 2026 because it has no reader until this client lands. What a run was asked with is already recorded against its attempt, so the service has what it needs. **Also the *Quality checks* change from UM-2 and UM-3**: each check Off, Advice or Gate (compile fixed as a gate), every check listed (wholeness, preservation, fidelity and the new ones included), the *Weight* field and the *Pass mark* field removed, *what judges it* removed from a step's card, a note where a check made a gate also acts while serving, a warning on a step's card when a rule uses a check that is off, presets redefined over the three states, and the readiness check ("no quality checks") updated. A change within the existing section, with its workbench stories updated. **Step 4 has two parts with different timing:** the *Quality checks* change goes with Track 1 task 10 (it must land before task 10's server removals); the rest (approval evidence, who approved, the spend-breaker state, *what was asked*) needs Track 1 tasks 2, 3 and 10 (the gate strip and literals), and Track 2 steps 1 (N9) and 3 (the breaker). | [ui-design.md](smart-pipelines/ui-design.md#what-changed-from-the-proposals) |
| 5 | Server | **Scoped 23 November 2026 (the user's decision): iv and iii before the rename; ii, and the rest of i, after the first release.** iv is **done**: the in-memory stores are in `testFixtures`. i is **largely done already**: on a node, XSLT candidates run through the real pipeline (`PipelineStepRunner`), the parser runners drive Stroom's own Data Splitter, JSON and XML factories, and the full pass and serving use the real pipeline; what is left is running parser candidates through the pipeline too. iii is **done**: `Stage` (3,115 lines) is serving alone (993), with `Learning`, `Sessions`, `Promotion`, `SupervisorActions`, `Judging` and `LearningPrefix` beside it, and the REST class's logic is `SupervisorService` behind a thin resource. **Step 5 is complete as scoped**; ii and the rest of i are post-release. Original: structural work i–iv: judge with the same code that serves, fewer storage interfaces, break up the central class, test code out of the product. | [simplification-options.md §3](smart-pipelines/simplification-options.md#options-that-only-change-the-codes-organisation) |
| 6 | Both | **Out of scope, 23 November 2026: XSLT egress is managed by a separate project**, whose code will arrive in due course. Nothing more is done on securing XSLT here. *Was:* **Towards the end of the project: pull in the `ai_translations_egress` branch**, move the Smart Pipelines list onto its Egress Policy screen, and enforce the list at run time. See [Step 6](#step-6-the-egress-policy-and-the-list-on-its-screen). | `ai_translations_egress`: `docs/ai_translations/egress-ui-plan.md` |

Step 2 is needed for review mode too, and must be done before any Content Template makes a Smart Pipeline.
It doesn't depend on step 1, so if Track 1 has capacity it can be pulled forward; Track 1 task 3 needs it for `format-date`.
~~Step 6 replaces step 2's list in code with one an administrator maintains, and adds the run-time enforcement step 2 lacks.~~ The list stays in code, behind `AiXsltAllowList`, so the separate project's code can supply it when it arrives without the callers changing.

### Step 1 as built (20 November 2026)

Five of the seven parts are built; Improve on the worker and N9 option D extended are left.

**Review by default, and the permission (SIM-1 option B).**
`ShapeshifterAiDoc.DEFAULT_PROMOTION_MODE` is `REVIEW`, and the new application permission
`APPROVE_SMART_PIPELINES_AUTOMATICALLY` (*Approve Smart Pipelines Automatically*) is checked in
`ShapeshifterAiStoreImpl.writeDocument`, so it holds on import as well as on save.
Only the move **to** automatic is checked: switching back needs EDIT alone, and someone without the
permission can still edit the rest of a Learning Flow that is already automatic.

**One promotion path (option A).**
Whatever the mode, a result that clears the gates is written as a draft; a Learning Flow set to use new
translations as soon as they pass approves it at once, through the same private `promoteDraft` that a
person's *Approve* takes.

Keeping A58 — one fragment per learner — cost more than re-pointing the binding would have, and is the
one place where what was built goes beyond the option's wording.
Approval now **copies the draft into the fragment that serves** and deletes the draft's documents
(`FragmentWriter.copyInto`, and a new `ContentCreator.delete` so the explorer node goes with the
document), rather than pointing the binding at the draft's fragment as review mode used to.
The serving pipeline keeps its own `DocRef`, so every output ever recorded against the binding still
names the pipeline that serves it.

**No provisional bindings (option C, SIM-3 option E), with step 1a.**
The provisional state is gone from the shared model, the server, the database and the client, as is
`Stage.accept` and its endpoint.
A first result learned from too few records for a held-out judgement (A14) is now a draft even on an
automatic Learning Flow; the record minimum is a first learning's bar only, so the relearning paths
don't ask it (N4).
Scenario 14 is rewritten; scenario 28 is retired.

**The unfinished and unused parts (option F).**
`regressionRetentionDays` and the `record_spans` column are removed, with the code that wrote and read
them; scenario 38 is retired, since the regression corpus keeps each record's own text (A56), which is
what a relearning is put to the model with.
`ERROR_LOAD` and `EVENT_CLASSIFICATION` had already gone with task 10; `shapeshifter_spend` and the
Admit step are kept, as the option says.

**The Pipeline Factory (option G).**
`stroom-pipeline-factory` and everything that wired it in are removed: the document type, the editor,
the job, the resource, the config, the two tables and their migration, the GWT inherits, the icon and
the stories.
Nothing in `stroom-learning-flow` depended on it, and it was never released, so its migration went
rather than being undone.
Content Templates of type `INHERIT_PIPELINE` make each feed's pipeline from here on; the template
pipeline's `learnedFolder` and `learnedName` must be left unset, or every feed that inherits from it
writes its learned content to the same place.

Anyone who made a `PipelineFactoryDoc` on a dev instance will find the document type gone when that
instance next starts.

**Improve on the worker (A46).**
`Stage.improve` validates, records the guidance and opens an attempt parked at its first question — the
same mechanism a processing task uses — and the Deferred Learning job puts the questions.
A model conversation is minutes long; holding an HTTP thread for it survives neither a node restart nor
a browser giving up.
Two things the old comment named as missing are now built: the attempt remembers which binding it is
improving (`improving_binding_uuid`), and `Stage.improvementInput` puts the binding's accepted records
back together so the worker has a sample without a stream.
The button now says the improvement was asked for; what it comes to appears on the Learning Status tab,
which is what `ui-design.md` means by moving it "without changing the screen".

**N9 option D extended, recording and resume.**
Each run records the words it was asked with — every template with the text that run used, the
*Instructions to the AI*, the built-in version and the build — and a parked run is carried on in those
words rather than in whatever the Learning Flow says by the time the worker reaches it.
A session is a conversation, and showing the model its own history in new words told it a conversation
it never had.

The guidance is deliberately **not** recorded there, though the decision's wording mentions it beside
the instructions: the supervisor's notes are read afresh before every question, precisely so a hint
given while an attempt was parked reaches the turn that resumes it, and they already have their own
rows with the time they were given.
A copy frozen when the run opened would be wrong for every turn after the first.

N9's third server piece, the service that rebuilds a turn's prompt behind the *View Data* check, is
moved to step 4: it has no reader until that step's client shows the rebuilt prompt, and an unused
service is what simplification option F has just finished deleting.

### Step 2: a hard-coded Smart Pipelines list

**Why.**
A learned stylesheet runs in the real `XSLTFilter`, and nothing limits what Saxon can reach from it (decision N1): standard XSLT can read and list host files, make outbound requests and write files, and several `stroom:` functions call out, read other feeds' data, drop records or give different results each run.
N1 is decided as option C: an allow-list.
To start with, the list is in code, so that it can ship without the egress branch.

**The work.**

1. **The list, in code**, in `stroom-learning-flow`, with a reason for each entry: the pure date, string and conversion functions and the current stream's own metadata (including `meta`, which N6 needs to read the resolved time zone), as in [N1](#1-n1-which-stroom-xslt-extension-functions-a-learned-stylesheet-may-use), plus the standard XPath and XSLT functions and instructions a translation needs.
   Anything not on it is refused.
   Keep it behind a small interface (for example `AiXsltAllowList`), so that step 6 can swap the source without touching the callers.
2. **The static stylesheet check**, when a candidate is judged and again when it is promoted: parse the stylesheet and refuse any function or instruction not on the list, any `function-lookup()` or function reference (such as `stroom:http-call#5`), and any computed URI or destination.
   The refusal is fed back to the model as the reason, like any other check, and shows as a failed check in the conversation on the Learning Status tab.
3. **The scenario test harness** stops refusing every extension function and applies the same list, so that a stylesheet that passes the tests passes on a node.
   The stand-in `XsltStep` runs Saxon with no Stroom function library, so it needs implementations of the allowed `stroom:` functions (at least `format-date` and `meta`), held to the real ones by a `stroom-app` test.
4. **Tests**: one per refused group in N1's table (at least `unparsed-text`, `xsl:result-document`, `environment-variable`, `function-lookup`, a function reference, `stroom:http-call`, `stroom:lookup`, `stroom:log`, `stroom:random`), and one that a stylesheet calling `stroom:format-date`, with the zone read by `stroom:meta`, passes judging and the harness alike (Track 1 task 3 depends on it).
   The test that judging and serving the same stream give the same date belongs to Track 1 task 3, which sets the stream's metadata when judging (AS6).

**What it doesn't do.**
There is no run-time enforcement: a learned stylesheet is only as safe as the static check is thorough.
So the check must be an allow-list over every function and instruction, never a list of banned names.
Changing the list needs a release.

**Effort.** M to L, with the harness's implementations of the allowed `stroom:` functions.

**Affects:** ruling A147; Track 1 task 3 (it needs `format-date` and `meta` on the list).

### Step 2 as built (20 November 2026)

All four parts are built and the module's tests, the client's and checkstyle pass.

| Part | Where |
|---|---|
| The list, behind an interface | `AiXsltAllowList`, `HardCodedXsltAllowList` |
| The static check | `LearnedStylesheetCheck`, `StylesheetScan` |
| As a check of the one model | `Check.PERMITTED`, `PermittedScorer`, outcome `permitted-short` |
| Again at promotion | `Stage.bind`, `FragmentCodes.stylesheets`, `StopReports.refusedByTheList` |
| The harness applying the same list | `XsltStep`, `StroomStandIns`, `StreamFacts` |

**Decisions taken while building it**, none of which the plan had settled:

- **The check is a gate a Learning Flow cannot turn down**, like compile (`CheckSettings.problems` refuses Advice or Off). It is a safety check, not a quality one: on an automatic Learning Flow nobody reads the stylesheet before it runs.
- **It is not counted as a *quality* gate** in the readiness banner, for the same reason — a translation can be safe and still be worthless, and a document with every quality check off must not look half-protected. The route map's total does count it: *Standard · 8 gates*, of which 6 are quality gates.
- **The check refuses what it cannot read**: a stylesheet that does not parse, an undeclared prefix, an unclosed brace in an attribute value template. An allow-list that passed what it could not understand would not be one.
- **The harness refuses rather than guessing.** A run that does not know the stream fails a partial date rather than taking the year from the clock, which is assumption AS6 from the other side.

**What it does not do**, beyond the step's own "what it doesn't do":

- **25 of the 32 allowed `stroom:` functions have no harness stand-in** (`StroomStandIns.missing`), so a stylesheet using one passes the static check and fails in the scenario suite. Nothing uses them; a test pins the list so it shrinks on purpose.
- **The `stroom-app` test holding the stand-ins to the real functions is not written, and cannot be yet.** Until Track 1 task 3 makes the worker set `MetaHolder` and `MetaDataHolder` for each attempt, the real `format-date` takes a missing year from the clock (AS6), so there is nothing stable to compare a stand-in against. The plan already puts that test in task 3; this is why.
- **`xsl:message` is refused**, which the declared-skips design may have to reverse: it is a candidate for how a translation declares a skipped record (Track 1 task 2's completeness check). The reason is recorded in `HardCodedXsltAllowList` where it will be found.

### Step 3 as built (20 November 2026)

An attempt has budgets of its own — so many tries, so long, so many tokens — and they bound **one**
session. Nothing bounded a feed that keeps opening them: a learner that cannot be taught, a format that
changes every hour, a model that charges and fails. A44's counter was written by every model call and read
by nothing.

| Part | Where |
|---|---|
| The policy over the counter | `SpendBreaker`, checked in `Stage.recording` before an attempt is opened |
| The cluster's total, which the counter could not give | `Spend.spentAll`, `SpendDao`, `InMemorySpend` |
| The cluster's ceilings | `shapeshifter.spendTokensPerHour` (10,000,000) and `spendCallsPerHour` (1,000) |
| A Learning Flow's own ceilings | `ShapeshifterAiDoc.spendTokensPerHour`, `spendCallsPerHour`; null or zero leaves it to the cluster's |

**Four ceilings, not two.** A per-document ceiling cannot bound a cluster — a hundred documents each
inside their own are a hundred times it — and a cluster-wide ceiling alone would let one runaway feed
starve every other. Tokens and questions are both counted, because a model may be priced or throttled by
either.

**What it does when it trips**: no new session opens, and the streams that would have opened one wait,
with the reason naming which ceiling and saying it clears when the hour rolls. Nothing errors, nothing is
lost, and learning starts again by itself. A session already running is left to finish — it is inside its
own budgets, and killing it would waste what has already been paid for. That is what A24's "error mode"
becomes, now that A57 has taken the name.

**The default worth arguing with.** 10,000,000 tokens an hour across the whole cluster is roughly
eighteen whole learnings, taking the development instance's 550,000 tokens for one feed as the unit. It is
a backstop against a runaway rather than a budget, and it is the number most likely to want changing for a
given model's prices. Zero turns a ceiling off, and the configuration says in as many words what that
leaves.

**Not done, and it belongs to step 4**: the breaker's state on the readiness banner and on Monitoring >
Smart Pipelines, and fields for a Learning Flow's own ceilings on the Setup tab. Until then a document's
ceilings are set through the document rather than through a screen, and what a person sees is the waiting
stream's reason on the Learning Status tab. The cluster's ceilings — which are the safety item §5 asks for
— are configuration, and need no screen.

### Step 6: the egress policy, and the list on its screen

**Changed 23 November 2026:** XSLT egress is now managed by a separate project, whose code this project will receive in due course. This project does nothing more on securing XSLT: Track 2 step 6 is out of scope, and the hard-coded Smart Pipelines list and static stylesheet check built in step 2 stay as they are. What follows is kept as the record of what was planned, and of what the egress branch held, for whoever integrates the separate project's code.

~~Planned for towards the end of the project.~~

**Why.**
The `ai_translations_egress` branch adds an egress policy and an Egress Policy screen that lock down Stroom's risky XSLT features for all translations, not only AI ones.
Its screen is where the Smart Pipelines list belongs once sites need to maintain it, and its Saxon confinement gives the run-time enforcement step 2 lacks.

**What the branch holds** (`origin/ai_translations_egress`, three commits of 4–5 November 2026 on top of `579e4969b8`; its own plan and status are in `docs/ai_translations/egress-ui-plan.md` §12):

- **An egress policy engine** (`stroom-egress-api`, `-impl`, `-impl-db`): one registry of permitted destinations, by kind (HTTP, EMAIL, AI_MODEL, FILE, OBJECT_STORE, MESSAGE_BUS, SEARCH_BACKEND), matched exactly or by prefix on a normalised destination; one guard that every sink calls; report-only or enforce per kind, and a kill switch; denied and reported attempts aggregated. Rules are in two new tables, `egress_rule` and `egress_setting`.
- **Guard calls at every sink**: `http-call`, `fetch-json`, `send-email`, `ask-ai`, the HTTP and file appenders, `HttpPostFilter`, model calls, S3, Kafka, Elasticsearch, Solr and the content-store icon servlet. Redirects forced off. A build-failing test (`TestEgressSinks`) that finds any I/O class with no guard and no reasoned allow-list entry.
- **Saxon confinement** (`SaxonConfinement` in `stroom-util`): reflexive external functions off; `unparsed-text()` (and with it `unparsed-text-lines()`, `json-doc()`), `collection()` and `xsl:result-document` denied outright, in every mode; `doc()` and `document()` governed by the HTTP and FILE rules. Applied in `XsltPoolImpl`, the `xpath()` query function, the extraction filter, and the Learning Flow module's `XsltStep` and `OutputRecords`, where everything is refused.
- **The Egress Policy screen**, a content tab on the Monitoring menu for holders of a new application permission, *Manage Egress Policy*: the mode and kill switch of each kind, the permitted destinations (with a rule dialog and a Test button), and the blocked and reported attempts. Workbench stories in `EgressPolicyScreenStories`.
- Tests: about 94 unit tests in the engine, `TestSaxonConfinement`, `TestXsltPoolConfinement` on the real pool, and `TestEgressRealNode` running real pipelines.
- Design notes in `docs/ai_translations/` (note the underscore): the egress plan, the shared-modules design note, the review-panel design, the AI-authored flag design note, an assessment of the Learning Flow module, and about 14,000 lines of pilot evaluation material.

**What it doesn't cover, which the Smart Pipelines list does** (its plan says so: assumption A10, and §6.5.11 of the shared-modules note):

| Gap | Examples |
|---|---|
| Functions that read beyond the record | `lookup`, `bitmap-lookup`, `dictionary`, `manifest-for-id`, `meta-stream-for-id`, `parent-for-id` |
| Side effects inside the pipeline | `log` (an ERROR or FATAL drops records), `add-meta`, `put` and `get` |
| Results that differ between judging and serving | `current-time`, `current-unix-time`, `random`, `random-integer` |
| Network use that isn't a sink | `host-name`, `host-address` (DNS lookups) |
| Standard functions with no egress hook | `environment-variable()`, `available-environment-variables()` |
| Calls that a check of named calls misses | `function-lookup()`, function references such as `stroom:http-call#5` |
| Report-only mode | Every kind starts in report-only, where `doc()` over HTTP or FILE is reported but allowed. A learned stylesheet would be confined only once an administrator enforces HTTP and FILE. |

**The work.**

1. **Merge the branch** into `ai_translations_server`, by a person (`AGENTS.md` doesn't allow an agent to run `git merge`).
   How much of it to take (the whole branch, with its design notes and pilot material in `docs/ai_translations/`, or only the egress commit `3eecde7f1f`) is **to be decided when this step starts**.
   A trial merge (`git merge-tree`, 18 November 2026) found three conflicts, which will have grown by then:
   - `AiServiceImpl`: this branch's model timeout fix and the egress branch's guard and redirect changes both change how the client is built. Keep both.
   - `stroom.json` and `stroom.yaml`: regenerate with `./gradlew :stroom-app:resolve` rather than merging by hand.

   `XsltPoolImpl`, `XsltStep` and `OutputRecords` merged without conflict, but the module has changed since the branch was cut, and Tracks 1 and 2 will change it further; check that the confinement still sits where learning and judging build their Saxon configurations.
2. **Finish what the branch left undone**:
   - The data access uses jOOQ's plain-SQL form because no database was available. Generate the jOOQ classes (`./gradlew :stroom-egress:stroom-egress-impl-db:generateJooq`) and use them, as every other module does.
   - The migration is `V07_15_00_001__egress_policy.sql`. Rename it to match the 8.0 numbering before it ships (N5 applies while it is unreleased).
   - Run the stories with the leak check, look at the screen in a browser, and add the read-only story `UI-GUIDE.md` expects (or record why it has none).
   - Run `TestEgressDaoImpl` and `TestDbEgressRegistry` against MySQL.
   - Settle the sinks the branch left as `UNGUARDED, TO DO` (Solr's ZooKeeper hosts, SQS, the receive path's S3 events, the account email sender).
3. **Move the Smart Pipelines list onto the Egress Policy screen.**
   A fourth part of the screen, *AI-written content*, seeded with step 2's list and its reasons.
   - Some things can never be added, whatever an administrator chooses: the routes the branch denies outright, `function-lookup()`, function references, and the outbound functions (`http-call`, `fetch-json`, `send-email`, `ask-ai`). The screen shows them greyed with the reason.
   - Changing it needs *Manage Egress Policy*, asks first, and is audited, as the rest of the screen is.
   - It is held in the egress tables, reaches every node within the branch's bound, and replaces the source behind step 2's interface, so the static check and the harness don't change.
4. **Enforce the list at run time** for learned stylesheets: the fragment pipeline that serves a learned translation runs with its XSLT confined in enforce mode whatever the kinds' global modes, and its `stroom:` calls checked against the list.
   That closes the report-only gap above, and anything the static check misses.
   Later, once the AI-authored flag (`docs/ai_translations/ai-provenance-design-note.md` on the branch) exists, "learned stylesheet" can become "any content marked AI-authored and not yet accepted by a person".
5. **Tests**: a run-time regression test per refused route and function, using the real pool as `TestEgressRealNode` does; a test that a stylesheet calling `stroom:format-date` passes judging, the harness and serving alike; a test that changing the list on one node reaches the others; and workbench stories for the new part of the screen (default, read-only, an entry added with OK, Cancel, and the greyed never-allowed rows).

**Effort.** L: S to M to merge and regenerate (more, the later it is done); M to finish the branch's open items; M for the list's screen part and run-time enforcement.

**Affects:** ruling A147's run-time half; [ui-design.md](smart-pipelines/ui-design.md), which gains a related screen outside the Learning Flow screens.

Dropping options B, D and E shrinks the server saving: the simplification document's estimate of about 5,000–6,000 server lines removed loses about 1,500 lines for B alone, plus D's endpoints and E's template overrides.
The server keeps parsing, validating and running editable flows, so the risks SIM-2 option A lists stay, and review by default (SIM-1) matters more.

The audit changes the scope of the remaining steps:

| Option or item | Change | Audit findings |
|---|---|---|
| B: no flow language | Dropped. The audit's W2 findings about removing it no longer apply; W2-6 (step sizes as flows) now fits as it stands. | W2-1 to W2-4, W2-6, W2-7 |
| C: no provisional bindings (SIM-3 option E) | Later streams defer behind a too-small draft until a person acts (N4 option F); relearns are not held to the minimum (first learning only). Map old `PROVISIONAL` attempt rows before removing the enum value. Removing C also removes one of the lost-update writers in task 8. | W3-1 to W3-3, W3-5, W7-3 |
| E: prompts in code | Dropped. Record each run's templates and texts instead (N9 option D, extended), so a past prompt can be rebuilt after an upgrade or an edit | W2-5 |
| Spend breaker | Count spend when the provider reports no usage, and charge failed calls (N7 option B), or the breaker never trips. | S10, W6-3 |
| Static stylesheet check (step 2) | Apply the N1 list, the same in the check and the test harness. | S8 |
| Database changes | Follow decision N5 (option A: edit `V08_00_00_001` in place) for every step that changes tables, including the egress branch's migration. | W3-5 |

## Where the tracks meet

| Overlap | How to handle it |
|---|---|
| Supervisor visibility items 1, 2, 7 and 8 (status line, Streams tab, getting there, API and MCP) | Items 1 and 7 are largely met by the final UI (*Where it stands* on the Learning Status tab; the tab itself, What it learned, and Monitoring > Smart Pipelines). Items 2 and 8 remain, re-scoped in [supervisor-visibility](learning-flow-supervisor-visibility.md) against the Learning Status and Quality Assurance tabs. |
| Single target schema (D1 option A) | Add the *Target schema* field to the Setup tab at the same time as Track 1 task 7, so the tab is changed once. |
| Missing year, fix 1 (prompt wording) and per-document question texts | Put the new wording in the built-in templates. Documents that have their own question text for the step keep their old wording, so the rollout note for task 3 should say how to reload the built-in (*Load built-in* on the step's card). |
| Structural refactor i and parser drift fix 1 | Refactor i rewrites the code that fix 1 touches. Build fix 1's coverage measure as the filter refactor i needs (W4-7), so the refactor keeps it and its tests. |
| Supervisor visibility item 6 (a "what might be wrong" check) | Build it on the Track 1 gates once they exist; its findings feed *Where it stands* on the Learning Status tab, and the Quality Assurance tab's figures. |
| QA assessments and the gates | None: the QA assessments are deleted (stage 0b group 4; Track 2 step 1b). |
| One model of checks (Track 1 task 10), flows in JSON (Track 2 step 1c) and the *Quality checks* change (Track 2 step 4) | One piece of work across both tracks: agree the design; add the new check list and states to the shared model and the server; change the client to use them and stop using `FlowStep.checks`, the weights and the pass mark; then remove those (AS13). Step 1c's default switch and test call sites are separable and follow. Tasks 2 and 3 build their checks into the new structure. |
| Missing year (Track 1 task 3) and the hard-coded list (Track 2 step 2) | **Settled: step 2 went first, 20 November 2026.** `stroom:format-date` is on the list and the harness stands in for it. Task 3 still owns setting the stream's metadata when judging (AS6), and the `stroom-app` test that holds the stand-in to the real function, which cannot be written until it does. |
| The rename (stage R) and every open branch | The rename touches about 639 files, so it conflicts with anything open at the time. Merge the outstanding branches first, then rename. See [Stage R](#stage-r-the-rename). The separate XSLT egress project's code may touch `XsltStep` and `OutputRecords`; whichever lands second, the rename or that code, pays the reconciling. |
| Egress branch (Track 2 step 6) and the module's Saxon sites | *Now the separate XSLT egress project's (23 November 2026).* When its code arrives, re-check that it confines the module's Saxon sites (`XsltStep`, `OutputRecords`), which have changed since the egress branch was cut. |

## Stage R: the rename

**Done, 23 November 2026**, as one change on a tree the user had just committed; see the work-in-progress note for the rules, the wire changes and what was regenerated. Markdown written before it keeps the old identifiers as a record.

**Decided, 19 November 2026.** The code takes the names in [The names](#the-names): `shapeshifter` becomes `learningflow`, and `ai_translations` becomes `smart pipelines` where it is the feature's name.
This is one mechanical change, done in one sitting on a quiet tree, not spread across the other tasks.

### When

Two constraints fix the window:

- ~~**Not before Track 2 step 6**, by preference rather than necessity.~~ *Moot from 23 November 2026: XSLT egress is a separate project's, so there is no egress merge for the rename to wait on. Rename on a quiet tree, after Track 2 step 5. If the separate project's code arrives before the rename, rename it with everything else; if after, it is written against the old names and is reconciled when it lands.* The `ai_translations_egress` branch was cut with the old names and touches the module's files (`XsltStep`, `OutputRecords`) and `docs/ai_translations/`. Step 6's trial merge of 18 November found **no conflict** in those two files, so renaming first would not make the merge impossible — but it would make every later rebase of that branch noisier, and git would be matching a moved package against hunks written under the old one. Merge the branch, then rename. The same goes for any other branch outstanding when the rename runs.

  *(Corrected 19 November 2026: this bullet used to say the egress branch "already conflicts" in those two files, which contradicts [step 6](#step-6-the-egress-policy-and-the-list-on-its-screen), where the trial merge records the opposite. The three real conflicts were `AiServiceImpl`, `stroom.json` and `stroom.yaml`.)*
- **Before the first release.** AS1 holds only until then: nothing is released, so the tables can be renamed by editing the unreleased `V08_00_00_001` migration in place (N5 option A), and the document type's id can change without converting stored documents. After a release, the same rename needs a mapping migration, a document-type conversion for every installation, and a deprecation period for the REST paths and the config key.

So: **after Track 2 step 5, before release** (changed 23 November 2026 from "after Track 2 step 6", which is now the separate XSLT egress project's).

A third, weaker preference: after Track 1 task 10 step 3, which renames the `Scorer*` classes to `Check*` and mechanically edits the test files that build a `ScorerSetting` (`new ScorerSetting(` appears in about 50 of them). Renaming after it means those classes and files are touched once, not twice.

**The cost of waiting** is that everything written between now and then is written under the old names. That is accepted: the rename is mechanical and repeatable, and the merge cost is not.

### Decisions to settle when the stage starts

**Settled 23 November 2026 (the user's decisions):** R1 as recommended (`stroom.learningflow`, modules `stroom-learning-flow:stroom-learning-flow` and `stroom-learning-flow:stroom-learning-flow-impl-db`); **R2 against the recommendation: the pipeline element's display name becomes *Smart Pipeline*** (not *AI Translator*), on `ShapeshifterAiParser`'s element and the label on `PipelineAiTab`; R2b as recommended (the element type id changes too); R3 as recommended (documents moved and every link re-pointed). The rename runs on a clean tree, after the user has committed the work of 19–23 November, so that it is one commit of its own.

| # | Question | Recommendation |
|---|---|---|
| R1 | What are the Java package and Gradle module names? | Package `stroom.learningflow` (Stroom concatenates multi-word packages: `pipelinefactory`, `contentstore`). Modules `stroom-learning-flow:stroom-learning-flow` and `stroom-learning-flow:stroom-learning-flow-impl-db`. |
| R2 | Does the pipeline element keep the display name *AI Translator* (`ShapeshifterAiParser`, and the label on `PipelineAiTab`)? | Keep it: it says what the element does and sits beside the other translator elements. It is the only user-visible wording the rename would change that the UI rework did not already settle. (The pipeline's tab is *Learning Status*, and is not in question.) |
| R2b | Does the **element type id** change — `ShapeshifterAiElements.PARSER`, the string `"ShapeshifterAi"`, and the element property `shapeshifterAi`? | Yes, with the same AS1 justification as the document type: it is embedded in every stored pipeline, the demo pack's pipelines and the content templates, and nothing is released, so there is nothing to convert. It is a *different* string from the document type's id, so it is easy to rename one and forget the other. |
| R3 | Do the document directories move (`docs/smart-pipelines/` and `docs/learning-flow-*.md`)? | Yes: `docs/smart-pipelines/` and `docs/learning-flow-*.md`, with every link re-pointed in the same change. The egress branch's `docs/ai_translations/` (note the underscore) is renamed when step 6 merges it, not before. |

### The work

Do it in this order; each step leaves the build green.

1. **Documents.** Rename `docs/learning-flow-*.md` to `docs/learning-flow-*.md` and `docs/smart-pipelines/` to `docs/smart-pipelines/`, including this plan. Re-point every link, in `docs/` and in the javadoc that cites the design notes. Rename the module's design notes under the module's `design/` directory and update `README.md`, `ISSUES.md` and the rulings A1–A147 where they name the module.
2. **Java, client and shared.** `stroom.shapeshifter.*` to `stroom.learningflow.*`; `ShapeshifterAi*` classes to `LearningFlow*`; `Scorer*` is already becoming `Check*` in task 10 step 3, so run that first and rename once. Leave `LearningFlow` (the order of questions) alone — it already has the right name, and after the rename it sits in the package named after the document type, which is correct.
   **A package rename alone will not do**, and an IDE will not catch these: the GWT module descriptor `stroom-core-client/src/main/resources/stroom/shapeshifter/ShapeshifterAi.gwt.xml` and the three `<inherits name="stroom.shapeshifter.ShapeshifterAi"/>` that name it (`App.gwt.xml`, `DashboardApp.gwt.xml`, `StroomWorkbench.gwt.xml`), or `gwtCompile` fails; the UiBinder namespaces `xmlns:s="urn:import:stroom.shapeshifter.client.component"` in the components' `.ui.xml`; the `identity="shapeshifterAi…"` attributes in those same templates, and the `#shapeshifterAi…` selectors the stories use to find them; `ext.moduleName` in both `build.gradle` files; and the star-import allow-list in `config/checkstyle/checkstyle.xml`, which names `stroom.shapeshifter.ai.impl.db.jooq.tables.Shapeshifter*` and will fail checkstyle after the jOOQ regeneration.
3. **Gradle.** Rename the module directories and the `settings.gradle` entries (R1), and the `build.gradle` dependencies that name them.
4. **Database.** Rename the `shapeshifter_*` tables (12 today, 11 once Track 2 step 1b has removed `shapeshifter_assessment`) by editing `V08_00_00_001__learning_flow.sql` in place (N5 option A), renaming the file with it; update `jooq-codegen.xml`, and in `stroom-learning-flow-impl-db/build.gradle` both `ext.jooqMigrationLocation` and `ext.jooqFlywayTable` (`shapeshifter_ai_schema_history`); regenerate with `./gradlew :stroom-learning-flow:stroom-learning-flow-impl-db:generateJooq`, then fix the checkstyle allow-list from step 2. Any dev or demo database is rebuilt, not migrated.
5. **The wire — two separate strings, easily confused.** The **document type** id (`DocumentTypeRegistry.SHAPESHIFTER_AI_DOCUMENT_TYPE` and the `"ShapeshifterAi"` string the explorer, search, import-export and the workbench fixtures use), and the **pipeline element type** id (`ShapeshifterAiElements.PARSER`, also `"ShapeshifterAi"`, with its element property `shapeshifterAi`), which is embedded in stored pipelines rather than in documents (R2b). Then the REST paths (`/shapeshifterAiSupervisor/v1` and the rest) and the config key. Regenerate `expected.yaml` with `GenerateExpectedYaml`, and the proxy's with `GenerateProxyExpectedYaml` if it is affected. The document type uses the generic `SvgImage.AI`, so no `GenerateSvgImages` run is needed.
6. **Workbench.** Fixtures, stories and story ids (`App/Editors/ShapeshifterAiEditor` becomes `App/Editors/LearningFlowEditor`), and the `"type": "ShapeshifterAi"` strings in the fake REST replies.
7. **The demo pack.** The `generateDemoContentPack` task hard-codes its output name and the pack's folder name (`stroom-learning-flow/build.gradle`, `Smart_Pipelines_Demo`), so change those as well as regenerating: `smart-pipelines-demo-v1.0.zip` becomes `smart-pipelines-demo-v1.0.zip`. The pack embeds both type ids from step 5, so it cannot be left as it is.
8. **A changelog entry**: `./log_change.sh Refactor 0 "Rename Shapeshifter AI to Learning Flow, and AI Translations to Smart Pipelines."` — `Refactor`, not `Bug`, and the imperative mood the entry template asks for.

### What is not renamed

- **Git branches** (`ai_translations_server`, `ai_translations_ui_rework`, `ai_translations_egress`). An agent may not change git state (`AGENTS.md`); a person renames them, or lets them die with their merges.
- **`LearningFlow`, the order of questions**, and the phrase "learning flow" for it (see [The names](#the-names)).
- **Third-party and generated directories**, which hold no occurrences.

### Verifying it

- `grep -ril shapeshifter . --exclude-dir=.git --exclude-dir=build --exclude-dir=node_modules` returns only the **expected residue**, and nothing else: the third-party `zxcvbn.js`; any `unreleased_changes/` entry written before the rename; the *Was* column of [The names](#the-names) and this section; `docs/` and `docs-obs/` material kept as history, including the `.html` siblings of the renamed documents; and `docs/ai_translations/` if the egress branch's directory has not been renamed with it. Check the list, don't expect it to be empty.
- `./gradlew clean build shadowJar buildDistribution` and `./buildDockerImages.sh`.
- `./gradlew checkstyleMain checkstyleTest` and `gwtCompile`.
- The workbench stories, with the leak check, under their new ids.
- The demo pack's import tests, which read the document type from the pack.

### Effort

M, and almost all of it mechanical, but it touches about 639 files and must land as one commit.
The risk is not difficulty but collision: any branch open at the time will conflict in every file it touches, which is why the timing above matters more than the work itself.

## Later: calibration and larger step sizes

The [unknown-models proposal](smart-pipelines/ai-translation-approach-for-unknown-models.html) adds calibration of each model against the scenario suite, and coarser step sizes (parser and XSLT in one reply, then a whole-pipeline agent loop).
Both rely on the gates Track 1 builds, so they come after it.
Calibration is a late phase at best (decided 18 November 2026); nothing earlier in the plan depends on it.
Until then, the sample size is a setting on each Learning Flow (N2 option F), and automatic mode is guarded by review by default and a permission (SIM-1 option B), not by calibration (UM-6 option C).
When calibration comes, reconsider N2 option G (sizing the sample from the model) and UM-6 option C.

**N4 may be revisited.** Behind a draft learned from too few records, a person must act (N4 option F).
If quiet feeds on automatic Learning Flows are found to wait too long for someone to notice, reconsider option G: a later stream with enough records starts a new learning run whose result replaces the draft.
That needs new states, and the Learning Status tab would have to show a draft being replaced.
Coarse steps come first; the whole-pipeline size only when an installation with a strong model needs it.

Before calibrating, the live test harness must build its model client the same way the node does (W6-6), or calibration measures a different client.
Weaker and self-hosted models also need the transcript kept within the model's context window, truncated replies detected, and more tolerant reply parsing (W6-1, W6-2, W6-4, W6-5).

## Assumptions

This plan, and the options and recommendations in stage 0b, rest on the assumptions below.
Each one says what would change if it turned out to be wrong.
Where an assumption has been checked, the evidence is given; the rest should be confirmed when the decision it affects is made.

### About the state of the code

| # | Assumption | Basis | If it is wrong |
|---|---|---|---|
| AS1 | Nothing in the Learning Flow module or the Pipeline Factory has been released, so no installation has data or configuration to migrate. | `ISSUES.md` #18 (one unreleased migration, `V08_00_00_001`); the factory is new on this branch. | N5 option A (edit the migration in place) and SIM-7's "delete the factory's migration rather than undo it" are no longer possible. Every table change needs a new migration that maps existing rows. |
| AS2 | The audit's findings and line references describe the code as it was on 17 November 2026. | The audit was done that day on `ai_translations_server`. | The UI merge (`7e06bb589c`) has since moved or removed lines it cites, and deleted the UI classes it cites. Each Track 1 task should re-find its audit findings in the merged code before starting. |
| AS3 | The demo evaluation of 16–17 November reflects how the system behaves today. | [learning-flow-demo-evaluation.md](smart-pipelines/learning-flow-demo-evaluation.md). | The NETFILTER and GATEWAY-AUDIT examples used throughout stage 0b, and the "should change" list after Track 1, would need re-running before they are used as a baseline. |
| AS4 | One Learning Flow can serve many pipelines, and so many feeds. | Ruling A114: a learner is the pipeline's UUID and the element's id, and the document is not part of the key. The demo's `Any feed` document is used this way. | N6's per-pipeline `timeZone` matters less, though it is still right; D1's one Learning Flow per target schema is unaffected. |
| AS5 | Deferring and waking work with the master task queue, so `claimTasksOnWorker` isn't needed for correctness. | Simplification option H, reviewed independently; `TestDeferredTask` runs in both modes. **Weakened, 20 November 2026**: no test cluster is available, so task 5's trial becomes an inspection of the claim and deferral paths. The assumption now rests on reading the code and on a single-node test, not on watching two nodes. | SIM-9 option H is wrong, and SIM-7 option B (Content Templates make Smart Pipelines) only works on clusters switched to worker claiming. **The trial that was there to catch this is no longer being run**, so this is the assumption most likely to be found wrong in production rather than in test. |
| AS6 | `stroom:format-date` is pure **provided the stream's metadata is set**: it then computes its result only from its arguments and the stream's metadata. | Checked 19 November 2026: `FormatDate.getBaseTime()` takes the reference time from `MetaHolder` (the stream's create time), but **falls back to `Instant.now()`** when there is no meta, and caches the value on the function call instance. A new `FormatDate` is made each time a stylesheet is borrowed from the pool (`XsltFilter.startProcessing`), so the cached time can't pass between pipeline runs. But `MetaHolder` is pipeline-scoped and the worker runs a batch of attempts in one pipeline scope (`DeferredLearning.exec`), so it must set `MetaHolder` and `MetaDataHolder` (which `stroom:meta` reads) for each attempt, replacing the previous attempt's; a stale meta would give the previous stream's year silently. Nothing in the Learning Flow module sets either today. Track 1 task 3 does this, and tests that judging and serving agree. | Without the metadata, judging and serving give different years, so `format-date` would be unfit for the N1 allow-list, and Track 1 task 3 would need another way to give the model the stream's year. |

### About the product

| # | Assumption | Basis | If it is wrong |
|---|---|---|---|
| AS7 | Autonomous (automatic) mode must stay available, even if it isn't the default. | The premise of [simplification-options.md §3](smart-pipelines/simplification-options.md#3-the-options). | Much more can be removed: the serving-path promotion, the spend breaker's urgency, provisional bindings outright, and N4 becomes moot. |
| AS8 | Some installations will be clusters, and will run learning on more than one node. | Stroom is commonly deployed as a cluster. Not confirmed for the first Learning Flow customers. | N8 option A was chosen on this assumption; if no first installation runs learning on more than one node, Track 1 task 8 could move after the first release. |
| AS9 | Some installations will use models whose provider doesn't report token usage, such as OpenAI-compatible local servers. | Audit finding S10; the unknown-models proposal. | N7 matters less, but failed calls still need charging (W6-3). |
| AS10 | Calibrating each model is a late phase at best, after Track 1, so for most of the project no model is calibrated. | [Later: calibration and larger step sizes](#later-calibration-and-larger-step-sizes); the decision of 18 November 2026. | UM-6 option C (automatic only once calibrated) could be adopted straight away instead of SIM-1 option B, and N2's sample size could be taken from the model (N2 option G) rather than a setting. |
| AS11 | Whoever points a feed at a model has decided its data may go to that model; no one inspects each feed's data first. Setting up a Content Template that makes Smart Pipelines is that decision for every feed it matches, so a pipeline it makes learns at once. (Corrected 19 November 2026: this row used to say a person must approve each feed, which overstated the ruling.) | Ruling A146: "a feed is pointed at a model only where its data may go to that model". | If a site does need each feed approved, Smart Pipelines made by Content Templates would have to start with learning off until a person switches it on. |

### About the plan

| # | Assumption | Basis | If it is wrong |
|---|---|---|---|
| AS12 | The UI merged from `ai_translations_ui_rework` is the final design, apart from minor bug fixes. It is built on today's server (editable flows, every Supervisor action, per-document question texts), not on the simplified one, and Track 1 changes what it shows but not its screens. | The decision of 18 November 2026; [ui-design.md](smart-pipelines/ui-design.md). | If the design changes, SIM-2, SIM-4 and SIM-5 reopen, and with them simplification options B, D and E. |
| AS13 | The client stops using a feature before the server removes it. | The UI delivery plan's ordering rule. | Track 2's step order no longer holds, and server removals could break the client. With the UI final, this matters for provisional bindings (step 1a), the QA assessments (step 1b), the checks model (Track 1 task 10 with step 4), and `schemaGroup` (Track 1 task 7). |
| AS14 | The people who own each decision (the product owner, security and the data owners, server-side engineers) are available to settle stage 0b in days, not weeks. | Stated in stage 0b; met, since every decision was settled by 19 November 2026. | All of Track 2's removals wait longer. No Track 1 task is held up by a stage 0b decision any longer. |
| AS15 | Effort sizes (S, M, L) are relative estimates for one engineer who knows the module. | The audit's walkthroughs. | They are not durations, and shouldn't be added up into a schedule without that conversion. |
| AS18 | Removing provisional bindings, adding approval evidence, who approved and the spend-breaker state, adding a target-schema field, and showing the time zone in use and its source on the Learning Status tab (N6) count as minor changes to the final UI, not redesigns. (The *Quality checks* change from UM-2 and UM-3, three states per check and step-card warnings, is a change within the existing section, agreed with those decisions.) | Each adds to or removes from an existing section or tab, without new screens. | If any of them is treated as a design change, it needs agreeing before Track 2 step 1a or 4, or Track 1 task 7. |
| AS19 | A separate project locks down XSLT for all of Stroom, and may give the Smart Pipelines list a screen of its own. *(Was: the `ai_translations_egress` branch, pulled in by Track 2 step 6.)* | The decision of 18 November 2026, changed on 23 November 2026: XSLT egress is now managed by a separate project. | The list would stay in code, or need its own screen, and the standard-XSLT routes would need confining by the Learning Flow module alone, for learned stylesheets only. |
| AS20 | Learned stylesheets can be confined in enforce mode whatever the egress kinds' global modes, and their `stroom:` calls checked at run time, without changing how other translations run. | Not yet checked: `XsltPoolImpl` pools one Saxon `Processor` per compiled stylesheet, and the branch installs one guard for all of them. | Run-time enforcement for learned stylesheets would wait until HTTP and FILE are enforced for everything. |
| AS21 | Until the separate XSLT egress project's code arrives, a static check with an allow-list over every function and instruction is enough protection for learned stylesheets. | The check refuses anything not on the list, including indirect calls and computed URIs. | A route the check misses would run unchecked on a node until step 6. If that is not acceptable for automatic feeds, decide with SIM-1 whether automatic mode waits for step 6. |

### About the stage 0b write-up

| # | Assumption | If it is wrong |
|---|---|---|
| AS16 | The recommendations are those of the source documents, except where stage 0b says otherwise. The options not in any source document are: N6 option F (header with a fallback) and its per-pipeline property (option G on the group 7 options page), N7 option C (a limit on calls), N8 option B (one node only), and the "about 4 characters a token" estimate in N7. | These additions need the same review as the rest before they are relied on. |
| AS17 | The examples marked "illustrative" (N2, N7 and the `acme-audit:1` schema in D2) show the shape of the problem, not measured results. | They shouldn't be quoted as evidence. |

## Ready to start now

- **Stage 0b is done**: every decision is settled, the last being group 7 (D1 and N6) on 19 November 2026. The one design item that remained open — how a translation declares a skipped record — was settled on 20 November 2026.
- **First, check the baseline**: the Learning Flow module's tests and checkstyle pass on `ai_translations_server` after the UI merge, and each task's audit findings are re-found in the merged code before it starts (AS2).
- **Track 1 is complete** (task 7 finished 23 November 2026). Rerun the demo evaluation, as the end of Track 1 says.
- **The design for task 10**: the check model and the step model it shares with Track 2 step 1c, and the client changes that must land before the server removals. The new checks in tasks 2 and 3 follow it.
- ~~**The declared-skips design**~~ — **done, 20 November 2026** ([the design](learning-flow-declared-skips-design.md)). Track 1 task 2's completeness check is unblocked, and the Smart Pipelines list needs no change.
- ~~**Track 2 step 2, the hard-coded Smart Pipelines list**~~ — **done, 20 November 2026** ([Step 2 as built](#step-2-as-built-20-november-2026)). With `format-date` and `meta` on the list and standing in in the harness, **Track 1 task 3 is unblocked**.
- **Track 2 steps 1, 1b and 3**, which need no further decisions; step 1's removal of the Pipeline Factory waits for Track 1 task 5's trial.
- **Not yet: stage R, the rename.** It is decided, and its work is written out, but it waits for the outstanding branches to merge and must land before the first release ([Stage R](#stage-r-the-rename)). Until it runs, write new code under the old names rather than renaming as you go, so that the rename stays one mechanical change.
- **Track 3, the security fixes** (added 23 November 2026): tasks 1, 3 and 7 need nothing and are first; tasks 2, 4, 5, 6 and 10 need decisions S-D1 to S-D5 settled. See [Track 3](#track-3-security-fixes).
- **Bringing the module's design notes into line** with the final UI (`design/07-ui.md` and `05-learning-flow.md`), which the code's javadoc cites.

## To review when the XSLT egress project's code arrives

*Added 23 November 2026.* XSLT egress is now managed by a separate project (see [Track 2 step 6](#step-6-the-egress-policy-and-the-list-on-its-screen)). Until its code arrives, this project keeps the Smart Pipelines list — the functions and instructions a learned stylesheet may use — hard-coded in `HardCodedXsltAllowList`, behind the `AiXsltAllowList` interface, with the static stylesheet check and the test harness applying it (Track 2 step 2, decision N1).

**It arrived on 24 November 2026** as gchq/stroom PR #6081 (issue #6079, *Add XSLT sandbox* and *Extend
XSLT sandbox*). It was brought onto this branch as unstaged changes, not by a merge. Its two prerequisites
on master came with it: #6000 (pool invalidation) and #6036 (NUL in `XMLWriter`). See the work-in-progress
notes, *The XSLT sandbox brought in*. What it provides:

- `SaxonSecurity` and `SaxonPolicy` (`stroom-util`, `stroom.util.xml`). Every Saxon processor, configuration
  and factory in Stroom must be made through `SaxonSecurity`, and a guard test
  (`TestSaxonSecurity.testSaxonOnlyCreatedThroughSaxonSecurity`) fails the build on any that is not. A
  secured configuration:
  - refuses `doc()`, `document()`, `unparsed-text()`, `collection()` and `uri-collection()` beyond the
    policy;
  - removes `transform()`, `load-xquery-module()` and `saxon:doc()`;
  - refuses `xsl:result-document` and limits `system-property()`;
  - hides environment variables;
  - stops the XML that Saxon parses itself (`parse-xml()`, `doc()`) from declaring a DOCTYPE or loading
    external entities.
- `XsltConfig.secureMode` (default true) and `allowedUriPrefixes` for the pipeline's pool
  (`XsltSecuritySettings`, `XsltPoolImpl`).
- `AddressCheckingDnsResolver` for the HTTP clients that XSLT's `http-call` and `fetch-json` use.
  `send-email` gets a recipient allow-list and a per-run cap.

The review below is done in part:

- **The module's Saxon sites:** done. `XsltStep`, `OutputRecords`, `RecordJoin`, `RecordSplit` and
  `JsonStep` now use `SaxonSecurity` with `SaxonPolicy.LOCKED`. `XsltStep.confine()` no longer turns
  external functions back on. The registered `stroom:` stand-ins work without them. **But these are the
  stand-in runners and serialisers.** On a node, candidates and promoted fragments run in the real pool
  (`PipelineStepRunner`, `PipelineFragmentRunner`), confined by `secureMode`, with the full `stroom:`
  function library (re-review, 24 November).
- **The pool that promoted fragments run in:** it is secured only while `secureMode` is true. That setting
  is Stroom-wide, so an administrator who turns it off also unconfines learned stylesheets. Decide whether
  learned fragments should be secured whatever the setting (AS20).
- **The other items below are still open:** the list, the static check, the stand-ins, and AS19 to AS21.
  Track 3 task 1 (the sound check) stands. The sandbox confines what a stylesheet can reach at run time,
  but it does not enforce the Smart Pipelines list.

Items to review (written before the code arrived):

- **The list itself**: whether it stays in code, moves to wherever the new project keeps its policy, or is replaced by it. The `AiXsltAllowList` interface is there so its source can change without its callers changing.
- **The static check**: whether it is still needed once the new project enforces at run time, or stays as an early refusal that tells the model why a candidate was turned down.
- **The harness's stand-ins** for the allowed `stroom:` functions (`StroomStandIns`), which must agree with whatever the new project allows.
- **The module's Saxon sites** (`XsltStep`, `OutputRecords`): that the new project confines them, as the egress branch did when it was cut.
- **Assumptions AS19 to AS21**, and decision N1's wording, against what the new project actually provides.

## Track 3: security fixes

*Added 23 November 2026, from the [security review](learning-flow-security-review.md).* That review
checked the module against the OWASP Top 10, the OWASP Top 10 for LLM Applications and the OWASP Top 10 for
Agentic Applications. Each task names the findings it fixes, by the review's numbers. Re-find each finding
in the code before starting: the review's line numbers are from 23 November.

Findings 1 and 2 are flaws in this project's own check and allow-list, so they are fixed here rather than
left to the XSLT egress project. What is built should be reviewed with that project's code when it arrives
(see *To review when the XSLT egress project's code arrives*, above).

**24 November 2026:** the XSLT sandbox (gchq/stroom #6079) has been brought in, and the module's Saxon sites
use it. That covers most of finding 2 (task 2), but not finding 1 (task 1), which is about the static
check. The security review is being run again against the code with the sandbox in, and the tasks below
will be revised from its results.

**The re-review (24 November)** is in the [security review](learning-flow-security-review.md), section
*Re-review, 24 November 2026*. Two of its four Fable agents were stopped by a safety classifier when they
began writing attack harnesses, so some results are reproduced by one reviewer, not verified.

What it changes in the tasks below:

| Task | Change |
|---|---|
| 1 | **Larger, and still first.** The check has three more bypasses that need no comment (N1): a shadowed function name, `merge-source/@for-each-source`, and `use-when`. The comment-stripping stopgap is therefore not enough. Add: key declared functions by namespace URI; scan every attribute that holds XPath, or refuse unknown ones; take `merge-source` off the list. **Add run-time enforcement:** compile learned stylesheets with a function library that holds only the allowed `stroom:` functions. In the pool, a bypass reaches `http-call`, `send-email`, `lookup` and the rest; the sandbox does not stop Java-registered functions. Turn the scratchpad harnesses into regression tests over `LearnedStylesheetCheck` and the pool |
| 2 | **Finding 2 is fixed in secure mode.** What is left: refuse to learn or promote while `XsltConfig.secureMode` is off, or compile learned stylesheets `LOCKED` whatever it is (N2); a regression test for the `parse-xml` payloads through `XsltStep` and the pool; `Records.java` and `RecordSkeleton.java` onto `ConfinedXml`; give learned stylesheets an opaque base URI so diagnostics don't carry the working directory (N9) |
| 3 | Unchanged, and more pressing. A plain `stroom:http-call` in a candidate is called once during judging, before the check |
| 5 | Add N7: check a fragment's element types in `Promotion.promote` and `freshened`, against an allowed list, not only "no writer". Wire up or delete `FragmentCheckImpl`, which nothing calls |
| 7 | Add N3: cap, fence and label the failing records quoted in relearn feedback |
| 10 | Add N4: on an AUTOMATIC flow, require `APPROVE_SMART_PIPELINES_AUTOMATICALLY` to change its instructions, checks, thresholds or model, or drop the flow to REVIEW on such a save |
| 12 | Add N5: record each corpus entry's source feed, and write re-accepted entries back to it |
| 17 | Add the rest of N9, and the `amend` reset that strands a draft (finding 21's addition) |
| Not this project's | N6 (the guard test's gaps; the JVM default `TransformerFactory`) and N8 (private networks allowed for `http-call`; the model client not using the address check) are Stroom-wide. Raise them with the sandbox's owners |

Decision S-D1 is settled as B, as far as it goes. Whether learned stylesheets should stay secured
when `secureMode` is off is a new decision (**S-D8**, below).

The tasks are in three groups, in the review's suggested order. The groups are a proposal: each task can
be taken or deferred on its own. Eight tasks (2, 4, 5, 6, 10, 11, 15 and 17) need one of seven decisions
first; these are listed under
[Decisions Track 3 needs](#decisions-track-3-needs), each with a recommendation.

### Before the first release

| # | Task | Fixes to do | Needs | Effort | Findings |
|---|---|---|---|---|---|
| 1 | A sound stylesheet check | Stop scanning XPath with regular expressions. Compile each expression (`select`, `test`, AVTs, text-value templates) with Saxon and walk the expression tree for real function calls, so comments and literals cannot hide one. Until then, as a stopgap, strip nested `(: :)` comments before blanking literals. The check stays one function, used by the `PERMITTED` scorer and the promotion re-check. | Nothing | M | 1 |
| 2 | `parse-xml` and the allow-list's breadth | **In large part done by the XSLT sandbox (24 November)**: the module's Saxon sites and the pool, while `secureMode` is on, now refuse a DOCTYPE and external entities in `parse-xml`. Still to do: a regression test that reproduces finding 2 through `XsltStep` and through the pool; settle the `secureMode`-off case (see *To review when the XSLT egress project's code arrives*); review `error`, `trace`, `serialize`, `merge-source`, `try`/`catch` and `json-to-xml` on the list. | Decision S-D1, now probably settled (see below) | S | 2, 16 (allow-list breadth) |
| 3 | Check before running, and on every promotion route | Run the static check before a candidate is run (`Conversation.java:872-875, 913, 922`), and refuse it without running it. Move the promotion-time re-check from `bind()` into `Promotion.promote` and `freshened`, so that approval, relearn and improve all pass it. Correct the comment at `Learning.java:690-693`. | Task 1 (the same function, made sound) | S | 3, 19 (the route part), 7 (the imported-document gap) |
| 4 | Model credentials and USE | On saving a Learning Flow, require USE on its model and its target schema when they change. On saving a model document, require VIEW on the named credential. In the learning job, resolve the credential as a real user rather than as the processing user. | Decision S-D2 | M | 4, 8 |
| 5 | Promotion touches only its own documents | Record the UUIDs that `FragmentWriter` created against the snapshot. Let `copyInto` overwrite, read or delete only those, and refuse otherwise with a stop report. Refuse approval when the draft's versions differ from its snapshot, and require the draft to be judged again (`freshened` already compares versions, `Promotion.java:262`). On saving a pipeline, check that the saver may create in `learnedFolder`, or confine learned content beneath the pipeline's own folder. | Decision S-D3 (for `learnedFolder`) | M | 17, 19 (the approval part), 27 |
| 6 | Relearning that one sender cannot steer | Apply `minRecordsToLearn` to a relearn opened by a shortfall. Treat corpus entries that cannot be read as a gate failure, and show "n of m corpus records readable". Limit what one promotion may add to the corpus, and keep a pinned set from the first reviewed promotion that eviction cannot remove. Make Regression a forced gate on automatic flows (`CheckSettings`, beside `PERMITTED`). On an automatic flow, require review when a relearn would replace a binding that has been scoring well. Cap automatic relearns per learner per day. Correct the *Records before a translation is trusted* help text (`SetupTab.ui.xml:152-153`). | Decision S-D4 (the numbers) | M to L | 18 |
| 7 | Stream text as data in the prompt | Fence the sample with a per-prompt random delimiter (or escape fences inside it), and treat failing-record text and improve samples the same way. Say in the system text that the sample is data, not instructions. Send guidance as a delimited block of lower authority than the document's instructions, with a length cap. Flag predicates on literal values that select a skip or empty output (`StylesheetScan.literals()` is already collected), and show conditional skips in the evidence. Raise `Templates.VERSION`. | Nothing | M | 12, 28 (guidance, corpus replay), 16 (guidance size) |

### Next

| # | Task | Fixes to do | Needs | Effort | Findings |
|---|---|---|---|---|---|
| 8 | Fencing, and stopping a run | Carry the claim's `VERSION` as a fencing token in `heartbeat`, `turn`, `parked` and `closed` (`WHERE version = ? AND node = ?`), and abort the walk when a heartbeat updates no row. Re-verify the claim in the same transaction as the binding change, before `copyInto`, and stop ignoring `replace`'s result. Heartbeat on a timer for the whole walk, including `fullPass`, the whole-stream re-run and the regression loop. Re-read learner state and learning mode at each heartbeat and before `writeDraft` and `promote`. Never clear a give-up from a run that began before it. Add an explicit cancel for a running attempt, and release the claim of a parked attempt that is given up. | Nothing | M | 20, 21 |
| 9 | Bounded candidate runs | Run each candidate, and the prompt rebuild's re-runs, under a bounded executor with a wall-clock timeout and interruption. Cap output bytes. Catch `Error` around the transform so that an `OutOfMemoryError` stops one candidate, not the pass. Cache the prompt rebuild per attempt, and rate-limit it. | Nothing | M | 5, 16 (prompt rebuild) |
| 10 | Copy, import and the demo pack | Override `copyDocument` to reset the copy to REVIEW unless the caller holds `APPROVE_SMART_PIPELINES_AUTOMATICALLY`. Override `importDocument` to run the same validation and permission check as `writeDocument`, and correct the javadoc at `LearningFlowStoreImpl.java:132`. Ship the demo pack in `REVIEW` with a blank `baseUrl` and a spend ceiling, and regenerate the zip. Warn on import when a model document's endpoint changes. Test import and copy. | Decision S-D5 (the demo pack's defaults) | S to M | 6, 7, 26 |
| 11 | Spend that one learner cannot exhaust | Cap grants, and use saturating arithmetic. Check the breaker on resume and on reopen, and make the check and the open atomic. Count calls as attempted, in a `finally`. Estimate tokens when usage is missing (`SimpleTokenCountEstimator`). Set a completion limit and a token-budget default. Turn on per-document and per-learner ceilings by default, with a share reserved so that one learner cannot use the whole allowance. Cap promotions per learner per period, whatever translation is in use, and park for a person on the second automatic replacement in a window. | Decision S-D4 (the numbers) | M | 10, 23 |
| 12 | Who may see what | Filter `learners`, `backlog` and `find` rows by pipeline VIEW. Gate stored answers and evidence on View Data, cap their length, and prune turns with the stream. Delete guidance on the learner as well as the id, and fail when no row is deleted. Check permission before `learnerOf` answers. Store an HTTP error's status and type, not its body, in stop reports. | Nothing | M | 9, 11, 14, 15, 16 (enumeration) |

### Then

| # | Task | Fixes to do | Needs | Effort | Findings |
|---|---|---|---|---|---|
| 13 | What the reviewer sees | Link the draft itself from the Learning Status tab, not the serving fragment. Show the stylesheet diff against the incumbent, before-and-after output for corpus records, conditional skips, literal predicates and the standing guidance. Beside the Approve button, list the gates passed, the record count and what was not checked. Say how many feeds and waiting streams the approval affects. Make Literals a gate in the Standard preset. Take held-out records from a different stream. Rename the headline figures (for example, "passes its checks"). Client work, with its workbench stories. | Tasks 5 and 7 (what to show) | L | 22 |
| 14 | Provenance and audit | Store `modelId`, the base-URL host and the model document's version on the attempt and the binding, and show them in review. Flag or refuse a resume when they differ. Raise an audit event per automatic promotion, naming the flow, its last editor, the model, the attempt and the fragment versions. Keep an attempt while its binding is in use (add PROMOTED to `KEPT`). Give each `@AutoLogged` Supervisor and quality method its operation type, and log `prompt` and `samples` as data views. | Nothing | M | 25, 16 (auditing) |
| 15 | Which feeds may go to which model | Per model document, an allow-list of feeds or classifications, or a flag that the model runs on the premises. Refuse learning when a feed's classification exceeds it. Optional masking patterns applied to the sample and to check feedback. Stop attributing guidance to its author in the prompt. | Decision S-D6 | M | 24, 16 (data sent to the provider) |
| 16 | Bounded result sets | Page in SQL, as `waitingOn` already does: `DeferralsDao.forDocument`, `OutputsDao.boundBy`, `CorpusIndexDao.rows` and `trim`, `SnapshotsDao.forBinding`. Push the pipeline filter into `LearningPipelinesDao`'s derived tables. | Nothing | M | 13 |
| 17 | The low items | Collation: `utf8mb4_bin` on the identity columns, by editing the unreleased migration (N5 option A) and regenerating jOOQ. Indexes for retention pruning and `OutputsDao.asProcessed`, and a unique key on the corpus. Remove a deleted Learning Flow's spend, output and snapshot rows. Make the pipeline's time zone setting beat the sender's `TimeZone` header, or make the order a setting. Length limits on guidance, amend answers and reasons, numeric ranges in `writeDocument`, and a 400 for a null body. Offer roll-back to the previous snapshot on retract, and hold the learner for review after a retraction. Gate the `__stub__` model behind a development setting. Add the document or learner to `AttemptsDao`'s mutator `WHERE` clauses. Label the model's reply more plainly in stop reports. Decide on a separate approve permission. | Decision S-D7 (separation of duties) | M in total, S each | 16, 28 |

Dependency verification for the whole build (`gradle/verification-metadata.xml`, finding 28) is
Stroom-wide, not this module's, and is left to the Stroom team.

Each fix comes with a regression test that fails before it and passes after it (AGENTS.md):

- the two Critical exploits as unit tests over `LearnedStylesheetCheck` and `XsltStep`;
- tasks 5, 6, 8 and 10 as scenario tests;
- task 3's refusal-before-run as a `Conversation` test;
- task 2 and task 9 also need a `stroom-app` test, because only the real pipeline runs Stroom's own
  XSLTFilter and pool.

After task 3, re-run the review's two Critical reproductions against a promoted fragment on the dev
instance. That is the path the review could not run.

### Decisions Track 3 needs

| Id | Question | Options | Recommendation |
|---|---|---|---|
| S-D1 | `parse-xml` (task 2) | A: take `parse-xml` and `parse-xml-fragment` off the list. B: keep them, and harden every Saxon configuration. | **B is now built**: the XSLT sandbox arrived on 24 November and hardens every configuration. Keep them on the list, unless the re-review finds a route that the sandbox misses, or the `secureMode`-off case is left open. (Was: A now, B with the egress project.) |
| S-D2 | Whose identity resolves a model's credential in the learning job (task 4) | A: the user who last saved the Learning Flow's model setting. B: the model document's owner. C: a run-as user set on the Learning Flow, like a processor filter's. | **C**, because it matches how processor filters already run, and it is visible and changeable. A is simplest but silently changes when someone else saves. |
| S-D3 | Where learned content may be created (task 5) | A: beneath the learner pipeline's own folder only. B: anywhere the pipeline's saver may create. | **B**, with the check on save, because `learnedFolder` exists for a reason. A is simpler if nobody uses the property. |
| S-D4 | The numbers for tasks 6 and 11 | The relearn minimum, how much one promotion may add to the corpus, the pinned set's size, automatic relearns and promotions per learner per period, and the default ceilings. | Start from the review's suggestions: `minRecordsToLearn` for a relearn, 20% of the cap, the first reviewed promotion's entries, and 3 automatic replacements a day. Set them as Learning Flow settings with these defaults. |
| S-D5 | The demo pack (task 10) | A: `REVIEW`, a blank `baseUrl`, a spend ceiling. B: keep `AUTOMATIC` for the demo, with a ceiling and a README warning. | **A**: a demo that is copied should start safe. Automatic is a setting the demo can show being turned on. |
| S-D6 | Data to the model endpoint (task 15) | A: a per-model allow-list of feeds. B: a per-model maximum classification, checked against the feed's. C: only an "on the premises" flag and a warning. | **B**, if Stroom's feed classification is reliably set on the target deployments; otherwise A. |
| S-D7 | Separation of duties (task 17) | A: a distinct approve permission. B: also require a second person where the approver answered a turn or gave guidance in that run. C: leave approval with pipeline EDIT. | **A** now, and B as a setting later. |
| S-D8 | Learned stylesheets when `XsltConfig.secureMode` is off (task 2, N2) | A: refuse to learn or promote while it is off. B: compile learned stylesheets with `SaxonPolicy.LOCKED` and a restricted function library whatever the setting, in a pool of their own. C: allow it, with a warning on the Learning Flow screen. | **B** if task 1's restricted function library already needs learned stylesheets compiled apart, which it probably does; otherwise **A**. |
