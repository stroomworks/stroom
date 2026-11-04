# AI Translations: shared modules, scoring and synergies

> **Status**: Draft design note for discussion. It proposes options and makes no decision.
> **Date**: 2026-11-04
> **Branch**: `ai_translations`
> **Audience**: Stroomworks engineering

## 0. How this note was produced, and how far to trust it

This note comes from reading the design folders under `docs/reference/design/`, the existing Shapeshifter AI
module (`stroom-shapeshifter/stroom-shapeshifter-ai`, design documents 01 to 10) and the prototype branch
`origin/sw-xslt-dependencies`.

| Source | How it was read | Confidence |
|---|---|---|
| `ai_translations/full-design.md`, `ai-translations-requirements-options.md` | Read in full | High |
| `docs/ai_translations/shapeshifter-ai-assessment.md` | Read in full | High |
| `stroom-shapeshifter-ai` design `06-scoring.md`, `08-data-and-code-map.md`, class listing | Read in part | Medium |
| `query-feeds/`, `claude-suggested-ux/`, `mcp-server/`, `agentic-stroom/`, `development-process/` | Read by delegated agents, which reported findings with file references | Medium. Not independently re-checked. |
| The remaining folders (graphdb, regex-runtime-limits, folder-data-flow, new-ui, log-sending-connectors, and others) | Headings, introductions and targeted searches by a delegated agent | Medium to low |
| `query-feeds-phase-1-plan.md` (pattern library, catalogue contents) and `query-feeds-phase-2-plan.md` (semantic types, claims, assertions, pipeline version on meta) | Read, phase 2 in full (updated 2026-11-04). The rest of query-feeds is still from the delegated agent. | Medium to high. **Per the project owner, nothing in the query-feeds design has been implemented.** |
| `floormap/`, `pathways/`, `stores/` | Titles only | Judged irrelevant, not verified |
| `origin/sw-xslt-dependencies` | Read through `git show` (the branch was not checked out). Key types, the parser's opening section, the store and resource changes and the test names were read. The expression compiler, value resolver and stub function library were not read in full. | Medium. **Nothing was built or run.** |

Where a statement below is a judgement rather than something read in a document, it says so.

---

## 1. Summary

1. **Triage and AI translations share the middle of one loop.** Both gather evidence, propose a change, run it,
   score it, and wait for a person to approve it. They differ only at the ends. Triage groups errors
   deterministically and (in briefs 03 to 05) needs no LLM. Translations need an LLM to generate candidates.
2. **The query-feeds work offers patterns for scoring, not code.** Its cost model scores plans before they run,
   whereas translation scoring judges output after it runs. What transfers is the *shape* of the scoring: evidence
   tiers, pessimistic confidence, structured explain output, and calibration. These address the
   assessment's biggest finding, that a schema-valid but wrong mapping passes every scorer.
3. **The `sw-xslt-dependencies` prototype is the missing static safety check.** It already parses an XSLT with Saxon
   and reports every `stroom:http-call`, `stroom:fetch-json`, dictionary read, map read and import, with line
   numbers and honest "could not tell" results. That is most of what the unbuilt egress check (A147) needs.
   It has one important limit (§5.4): as built it records and reports but never refuses.
4. **Recommended direction.** Extract a small scoring core from the scorers that exist, build triage (briefs 03 to 05)
   as the second real consumer of the shared seams, close the assessment's safety gates in parallel, and run the
   model-comparison evaluation early. A full platform split up front is not advised, because only one consumer
   exists today.

5. **A probe found that standard XSLT functions can read and write local files, and open outbound connections,
   in a setup that mirrors Stroom's.** The Stroom-specific functions (`http-call`, `fetch-json`, `send-email`,
   `ask-ai`) are not the only egress. §6.5 sets out one egress policy, administered in one UI, that covers every
   sink, and §6.5.2 gives the evidence and its limits.
6. **Two project-owner decisions now govern the design.**
   - **Human review is the default.** "No human intervention" is not a contractual requirement. Log data is
     untrusted, so a person approves every AI-generated or AI-changed translation before it goes live. Unattended
     operation is an opt-in for a feed, where the case for it has been made and recorded. It is in scope for 1.0 as
     a customer trial (§6.7, §7, §8 row 1).
   - **The design is provider-neutral, and must work with a customer-hosted model.** At least one customer will run
     their model on premises. Nothing may assume a frontier model, a large context window or a particular vendor
     (§6.6).
7. **Scope for version 1.0 (project-owner decisions).**
   - **The project defines what is delivered**, and version 1.0 only has to deliver enough to be useful. It is
     partly a research project, not a finished product.
   - **Pipelines use the existing Data Splitter and XSLT.** The Shapeshifter Engine (the non-XSLT transformation
     engine) is a prototype and is **not** part of AI translations at this stage. I checked: **it is not in this
     branch.** `stroom-shapeshifter-ai` is the Intelligence side and runs Stroom's own Data Splitter, JSON parser,
     XML fragment parser, `SplitFilter` and XSLT filter, as real pipelines (§6.7).
   - **The on-premises model is unknown and will stay unknown for 1.0.** Assume it is **less capable than current
     frontier models**. We have no access to it, so evaluation uses Claude Haiku, Sonnet and Opus online, with Opus
     as the reference (Option 5).
   - **"Acceptable" means it helps the user reach their end.** With a person in the loop, a translation that helps
     that person in some way is good enough.
   - **Automatic mode is in scope for 1.0, as something for the customer to test and report back on**, not as a
     polished deliverable. It stays opt-in per feed. Because it is in scope, the safety gates (Option 3) are
     needed for 1.0 (§6.7).

   §6.7 turns these into scope.

---

## 2. Where scoring stands today

### 2.1 What exists

`stroom-shapeshifter-ai` has six scorers, each producing a score in [0, 1] plus diagnostics:

| Scorer | Judges | Class |
|---|---|---|
| Compile | Did the step run at all | `CompileScorer` |
| Input coverage | Share of input characters consumed by the split | `InputCoverageScorer` |
| Yield | Output records per unit of input against an expected ratio | `YieldScorer` |
| Schema conformance | Share of records valid against the schema group | `SchemaConformanceScorer` |
| Extraction quality | Anti-degeneracy: typed elements, real `EventDetail` branch, required fields | `ExtractionQualityScorer` |
| Business rules | Share of records satisfying named XPath assertions | `BusinessRulesScorer` |

`Scorecard` applies a document's settings (weight, threshold, gate, parameters). `Verdict` carries the result. The
promotion gate multiplies each step's weighted total, and `promotionFloor` defaults to 0.9. A per-record "bar" is the
set of gates that every served record must clear.

### 2.2 What the assessment says is wrong

From `shapeshifter-ai-assessment.md`:

- Scorers measure **structure, not meaning**. Only business rules is semantic, and a person writes those rules.
- `ERROR_LOAD` and `EVENT_CLASSIFICATION` are named but unbuilt.
- **A new document judges nothing.** With no scorers every candidate passes. The blank state is the unsafe state.
- The corpus is capped at 100 entries per binding, so "no regression" covers only recent records.
- Format drift under a live binding is not scored (ISSUES 2).
- No resource limits on candidate runs, no spend breaker acting on its counter, and no prompt-injection handling.

### 2.3 The worked example that defeats every scorer

A firewall translation that swaps source and destination addresses:

```xml
<!-- Input -->
Nov  4 10:00:01 fw01 DROP src=10.0.0.5 dst=203.0.113.9 dport=443

<!-- Correct -->
<Source><Device><IPAddress>10.0.0.5</IPAddress></Device></Source>
<Destination><Device><IPAddress>203.0.113.9</IPAddress></Device></Destination>

<!-- Wrong, but plausible -->
<Source><Device><IPAddress>203.0.113.9</IPAddress></Device></Source>
<Destination><Device><IPAddress>10.0.0.5</IPAddress></Device></Destination>
```

Both outputs compile, validate against the schema, populate every required field and yield one event per line.
Every scorer above passes both. Only a known-answer test (a golden event) or a person can tell them apart. Any
scoring redesign has to start from this case.

### 2.4 What a golden event is

**Definition.** A golden event is a **known-correct answer for a known input**: one or more raw input records,
supplied by a person who knows what they mean, together with the event or events that a correct translation must
produce from them. It is "golden" because the person, not the model and not the scorers, is the authority on it.

The assessment (§7.3) describes it as "a person supplies a few input records and the events they must produce",
the strongest correctness check and "the most work for the user". The options paper calls the same idea a
*known-answer test case*.

A candidate translation is run over each golden input, and the output is compared with the golden event. A
candidate that fails any golden event cannot be promoted automatically. In review mode the failure is shown to the
person.

**It is not** a sample the model was shown, a score, or something the model wrote. A golden event written by the
model proves only that the model agrees with itself.

**A naming caution.** The test code in `stroom-shapeshifter-ai` has a record called `Golden`
(`ExtractionCorpus.Golden`: a Data Splitter configuration, an input, and the expected `records:2` output, taken
from `stroom-pipeline/src/test/resources/TestDS3`). That is a **developer's regression case for the extraction
stage**, checked in as test data. It is related but not the same thing. I did not find a user-facing way to supply
a golden event for a feed in the module. The nearest existing mechanism is the business-rules scorer (named XPath
assertions) and the per-binding corpus (§2.2). Whether either can hold a golden event unchanged was not checked.

#### Example 1: a full golden event (the §2.3 firewall)

```text
Input record:
  Nov  4 10:00:01 fw01 DROP src=10.0.0.5 dst=203.0.113.9 dport=443

Golden event (must be produced):
  <Event>
    <EventTime><TimeCreated>2026-11-04T10:00:01.000Z</TimeCreated></EventTime>
    <EventSource><Device><HostName>fw01</HostName></Device></EventSource>
    <EventDetail>
      <TypeId>DROP</TypeId>
      <Network>
        <Source><Device><IPAddress>10.0.0.5</IPAddress></Device></Source>
        <Destination><Device><IPAddress>203.0.113.9</IPAddress></Device><Port>443</Port></Destination>
      </Network>
    </EventDetail>
  </Event>
```

This catches the swapped-address mapping that every structural scorer passes. The cost is that an exact match is
brittle: any harmless difference (element order, an extra `Description`) also fails. So an exact match is best kept
for a few events.

#### Example 2: a partial golden event (only what matters)

The same input, but the person asserts only the fields they care about, as XPath assertions:

```text
Input record:
  Nov  4 10:00:01 fw01 DROP src=10.0.0.5 dst=203.0.113.9 dport=443

Must hold:
  //Source/Device/IPAddress       = 10.0.0.5
  //Destination/Device/IPAddress  = 203.0.113.9
  //Destination/Port              = 443
  //EventTime/TimeCreated         = 2026-11-04T10:00:01.000Z
```

Cheaper to write and tolerant of harmless differences. It is the same mechanism as the business-rules scorer
(§2.1), applied to a known input. This is the form I would expect most users to supply.

#### Example 3: an edge case that must go right

Golden events earn most where the normal case hides a trap:

```text
Input record:                                      Why it is in the set:
  Nov  4 10:00:02 fw01 DROP src=2001:db8::5 ...    IPv6 must land in the same element as IPv4
  Nov  4 10:00:03 fw01 DROP src=10.0.0.5 dst=-     "-" means "no value" and must not become an address
  Dec 31 23:59:59 fw01 DROP src=...                No year in the log line: must roll over correctly
  Nov  4 10:00:04 fw01 DROP src=10.0.0.5, dst=...  A stray comma: must not shift the fields
```

For a Data Splitter configuration the same idea is a small input and the expected `records` output, for example a
CSV row whose quoted field contains a comma:

```text
Input:     2026-11-04,alice,"Smith, Alice",login
Expected:  4 fields, the third being `Smith, Alice` (not two fields)
```

#### Example 4: a negative golden event (must NOT produce)

```text
Input:     # fw01 firewall log, started 2026-11-04        (a header or comment line)
Expected:  no event, and no error

Input:     Nov  4 10:00:05 fw01 garbled~~~~
Expected:  no event, and one error that names the line
```

This catches the silent record loss that the scorers rate poorly, since a translation that quietly drops records
has a good-looking yield. It also checks that bad input is reported, not guessed at.

#### How many, and who writes them

- **Three to ten is useful.** One per distinct event shape, plus the edge cases above.
- **A person writes or confirms them.** The model may *propose* a golden event for the person to correct, but it only
  counts once a person has confirmed it.
- **The cheapest route may be review.** When a person reviews a candidate's output and marks an event "correct", that
  record and event can become a golden event. This is my suggestion, not something in any design document, and
  nothing in the module does it today.
- **Keep some out of the model's sight.** If the golden events are in the model's prompt, it can copy the
  expected output and pass them without understanding the format. Holding at least some back from the model keeps
  the check honest. This is my recommendation, not a finding.
- **Sensitive data.** A golden event contains real or realistic data. It needs the same classification as any
  sample (D5) if it is ever sent to a model, and for an on-premises model that is a customer decision (§6.6).

#### What a golden event proves

It proves the candidate is right **for those inputs**, and nothing about other inputs. That is why §3.1 calls it
Verified *for those events* and why automatic mode needs one per feed but is not made safe by it (§6.7).

#### How the system asks for golden events (project-owner direction)

**The system proposes and asks. It does not expect the user to know their golden events in advance.** Users cannot
be assumed to arrive with known-correct answers, so the system asks for one at the moment it needs it, shows a
concrete question, and offers options wherever the options can be enumerated. Everything in this subsection is a
design proposal. None of it exists in the module.

**When it asks.** Only when an answer would change the outcome:

| Trigger | Example |
|---|---|
| **Swap-risk ambiguity** (§6.3 ambiguity rule) | Two IPv4 fields and two output elements that accept IPv4 |
| **Candidates disagree** | Two plausible mappings (or two candidate transforms) produce different events for the same record |
| **A schema-constrained value has several readings** | `DROP` could map to more than one allowed value of an enumerated element |
| **An ambiguous format** | `04/11/26` is 4 November or 11 April; a timestamp with no time zone |
| **An unusual record** | A shape seen once among the sample (the Good–Turing unseen mass in §3.4 is high), or a missing-value marker (`-`, `N/A`) |
| **A required schema field has no source in the log** | `System/Environment` is mandatory in event-logging v4.1.0 and no log supplies it (see below) |
| **Automatic mode is requested** | No golden event covers the feed (§6.7), so it asks for one before enabling it |

In review mode, a question is **optional**: the person can skip it, and the review panel says what that leaves
unchecked. Before automatic mode, it is required.

**How options are enumerated.** Options are generated **deterministically wherever possible**, not by the model,
because a weaker model must not be the thing that decides what the user is shown, and because model-written options
are open to prompt injection from the log data (D1 to D3):

| Source of options | Example question |
|---|---|
| The candidate mappings from the type and catalogue evidence (§6.3, §6.4) | "Which address is the source? **(a)** `10.0.0.5` is the source, `203.0.113.9` the destination. **(b)** The other way round. **(c)** Neither / other." Each choice is shown as the actual event it would produce. |
| Allowed values in the schema | "`DROP` should be recorded as: **(a)** `Deny` **(b)** `Block` **(c)** `Drop` **(d)** other." The list comes from the schema's enumeration. |
| Plausible parses of an ambiguous format | "`04/11/26` is: **(a)** 4 November 2026 **(b)** 11 April 2026." |
| What other feeds on the same schema do (§6.4.4 condition 4) | "Other feeds with this schema record this as `Authenticate/Action = Logon`. Use that?" |
| Where options cannot be listed | A free-text answer, or "paste the expected event". The model may suggest a value. It is marked as a suggestion until a person accepts it. |

**Required fields the log cannot supply (found by the pilot).** The schema makes some elements mandatory that no
log contains. In event-logging v4.1.0 an `EventSource` needs a `System` (whose `Name` and `Environment` are both
required), a `Generator` and a `Device`, and an event needs a `TypeId`. (An earlier version of this note said
`Environment` was one of a group of alternatives. It is not: the schema has it at `minOccurs` 1.) Of six ask-first runs in case 02, **only the two
Opus runs asked for `Environment`**. The other four filled it with the placeholder "Unknown", and one first submitted a
stylesheet with no `System` at all, which was schema-invalid. This is a question the system can find **without a
model**, so it should not be left to the model to notice. A prototype that reads the schema and the input finds the
required values, the direction question and the rest on all three pilot cases
(`pilot/results/question-generator-evaluation.md`):

1. Read the schema and list, for the target event shape, every element with `minOccurs` of 1 or more, and every
   "one of these must be present" group, that has no source field in the input.
2. Ask **once per feed**, not once per translation, with options: a constant, a feed property (the feed name or an
   environment label), or a value from another feed with the same schema (§6.4.4 condition 4).
3. Store the answer as **feed configuration**, not in the XSLT, so it is visible, reviewable and reusable.
4. Never accept a placeholder silently. If a value is filled with "Unknown" the review panel says so.

**Choosing the record to ask about.** Pick the record where the candidates **differ most**, not a random one, so one
answer settles as much as possible. If three candidate mappings agree on 98 records and disagree on two, ask about
one of the two. That keeps the number of questions small.

**Keeping it cheap for the person.**
- Show the **input record and each option's resulting event side by side**. The person compares, and does not write XML.
- Offer **"none of these"**, **"skip for now"** and **"ask me later"**.
- **Batch** related questions and cap how many are asked per session.
- An accepted option becomes a golden event (an input record plus the chosen event, or partial XPath assertions), is
  stored against the feed with who confirmed it and when (D6), and is **not asked again**.
- The system says what an answer settled: "this fixes the source and destination for all 4,812 records in the sample".

**Safeguards.** An option the system generated is only a golden event once a person has chosen it. The question
and every option are built from typed fields, not raw log text (D3). The system never presents its own guess as
the user's answer.

#### Evidence from the pilot (4 November 2026)

Six runs on one case (`pilot/results/results-2026-11-04.md`) support three points in this section. They are one run
per cell on a small case I wrote, so they are indicative only.

- **The golden event is how the user's intent is captured.** All six outputs were schema-valid. The only run that
  failed a golden check was a one-shot run that made a defensible but unintended choice (it kept a record with an
  invalid address). All three ask-first runs asked, and were right. A schema validator and a scorer cannot know that
  intent; the user can say it in one answer.
- **Ask only what the data cannot settle.** Case 02 marks three decisions the system should *not* ask about (the schema
  settles the action names, and one row settles the date order), so the pilot can count unnecessary questions as well as
  missing ones.
- **Direction is the question the model must not be allowed to skip.** In case 03, where the log lists the destination
  first, five of six runs swapped source and destination and only the one that asked got it right. The §6.3
  ambiguity rule (two same-typed fields, two elements that accept the type) is detectable without a model, so the
  system asks, shows the event each option would produce, and keeps the answer as a golden event.
- **A golden event can be a check that records were not silently dropped.** Three of the six runs skipped records with
  no message at all. A golden check that "dropped records are reported" is now part of the scoring
  (`golden/required-messages.txt`), and it is a requirement of the review panel as well as the translation (§6.7).

---

## 3. What query-feeds offers the scoring system

The query-feeds papers (`query-feeds/`) design a planner that scores query plans from observed feed statistics.
The domain differs, but several ideas carry over.

### 3.1 Evidence tiers: Observed, Declared, Verified

`query-planning-brief-v2.md` separates three grades of evidence and states the rule that only *Verified* evidence
may satisfy a correctness guard.

Applied to translation scoring:

| Tier | Meaning in translations | Example |
|---|---|---|
| **Observed** | Measured from a sample or heuristic | "98% of the 8 KiB sample parsed", yield ratio |
| **Declared** | A person asserted it | The XPath rule `EventTime` is present |
| **Verified** | Checked against known answers on every record | Golden events, an assertion run over the whole held stream |

The promotion gate would accept only Verified evidence to *auto*-promote. Observed and Declared evidence could
justify a draft for review but never an unattended promotion. This is the direct answer to §2.3: the
swapped-address translation can reach review, but it cannot reach automatic mode without a golden event covering
source and destination.

**Phase 2 already defines this vocabulary, and the note should adopt it rather than invent a parallel one.**
The phase-2 plan ("Three levels of confidence", "Provenance in the type system") uses exactly Observed, Declared
and Verified, and makes them *types*, not a flag: a sealed `Claim<T>` with `ObservedClaim`, `DeclaredClaim` and
`VerifiedClaim`. `VerifiedClaim` has no public constructor and is never a serialisable DTO. Two consequences for
this note:

- The `EvidenceTier` enum in §6.1 should become that sealed hierarchy (or a shared one), so a gate that needs
  Verified evidence fails to compile when given Observed evidence.
- **A verified claim is scoped to an exact set of streams.** Phase 2 mints a `VerifiedClaim` only for the streams
  a query will read, and a claim for streams 100 to 256 is never offered to a query over 200 to 300. For
  translations, "verified" therefore means *verified over the held stream and the golden events*. It says nothing
  about streams that arrive tomorrow. Continuing verification has to happen per stream as it is written, which is
  what phase 2's assertion checking (2C) is designed to do (§6.4.5).

### 3.2 Narrow, exhaustive assertions

The brief argues that statistics may be sampled but assertions may not. Translated into scoring, business rules
become many small named claims, each checked on every record:

```yaml
assertions:
  - name: source-is-private-range-for-outbound
    xpath: "EventDetail/Authenticate/..."   # illustrative
  - name: outcome-is-one-of-known-values
    xpath: "Outcome/Success or Outcome/Failure"
  - name: user-not-empty
    xpath: "string-length(User/Id) > 0"
```

Each assertion records the set that was verified and how many records it covered. A golden-event file is the
strongest form: a few input records with the exact events they must produce.

### 3.3 Pessimistic confidence

`08-explain-and-cost.md` computes confidence as a product of "did we have to guess?" factors, and a result with
no signal gets confidence 0 plus a note. A candidate scored on few records, with no business rules, would carry
low confidence automatically, so it cannot auto-promote. This also repairs the blank-scorer state without a
special case: no scorers means no evidence, so confidence is 0.

### 3.4 Good–Turing unseen mass

The phase-1 plan estimates coverage as singletons divided by total occurrences, and uses 3/N as an upper bound for
a rare event. For translations:

- It replaces the guessed `minRecordsToLearn` (default 10) and the 8 KiB sample with a measured answer to "is this
  sample enough to learn from?".
- It supports the unbuilt `EVENT_CLASSIFICATION` scorer: if 40% of distinct event shapes appeared exactly once, the
  sample is thin, and the system should ask for more data before learning.

### 3.5 Shape catalogue as drift input

The query-feeds shape catalogue records paths, cardinalities, patterns and first/last seen per feed. It is a ready
source for the drift check the assessment says is missing: a binding whose input shape has moved since learning
can be flagged by comparing the catalogue's current shape with the shape at learning time.

### 3.6 Structured explain output

A score becomes a tree rather than a number:

```text
Score 0.93  (gates: pass)   confidence 0.40   evidence: OBSERVED
├─ compile              1.00  gate  pass   VERIFIED
├─ schema conformance   1.00  gate  pass   VERIFIED   10000/10000 records
├─ yield                0.97  weight 1     OBSERVED   ratio 1.03 vs expected 1.00
├─ extraction quality   0.88  weight 1     OBSERVED   3 records with bare Unknown
└─ business rules       --    not configured          no evidence
Trust this score? NO - no Declared or Verified rule covers source/destination.
```

The last line is the point. It would also be the natural content of the review screen the assessment recommends as
the main interface (recommendation 7).

### 3.7 Calibration

`08-explain-and-cost.md` is candid that its selectivity constants were never tuned against outcomes. The same trap
applies to scorer weights and thresholds. Recording the predicted score beside the realised outcome (later
reprocessing errors, or a human accept or reject) gives a way to tune them.

### 3.8 Bounded jobs and simplification lessons

- The phase-1 plan's learning job is bounded, resumable and disabled by default, with a per-run budget (records and
  wall-clock), round-robin across feeds, and merge-on-clean-finish. That is a model for the learn job and for the
  missing spend breaker.
- The simplification review argues for fixed defaults over knobs, dropping machinery whose decisions evaporate, and
  using real data over a synthetic corpus. This matches assessment recommendation 5 (named profiles such as
  "strict" and "lenient").

### 3.9 What does not transfer

- The selectivity multipliers and join cost formulas.
- The disclosure-tier machinery, which the simplification review itself recommends cutting.
- The query-feeds code. **None of it is implemented** (per the project owner; the phase plans say the same of the
  `stroom-datashape` modules), and the simplification review defers assertions and calibration, so **do not
  take a dependency on unbuilt code**. Everything in §6.3 and §6.4 that uses the catalogue is therefore written
  as an adapter behind an interface, and as a design to be validated, not an integration to be wired. Extract interfaces from the Shapeshifter scorers that exist.

Two cautions on the catalogue, the second of which corrects an earlier version of this note:

- It is per feed and stream type, and it covers **translated `Events` XML only**. The phase-1 plan states that
  `Raw Events` is out of scope, because it "holds whatever arrived, in whatever format, and has no paths in the
  sense the catalog means". It therefore describes what translations *output*, never what a translation has to
  *read*. The input side has to be profiled by something else (§6.3).
- A brand-new feed has no catalogue of its own, but the cold start is less empty than first stated. Two priors
  exist without the feed: the **generated path map** (Declared: every schema location with its declared type and
  occurrences) and the catalogues of *other* feeds (Observed). §6.4 uses both.

---

## 4. Triage and AI translations as one loop

### 4.1 What triage needs

From briefs 03, 04 and 05 (read by an agent, so check against the briefs):

- **Inputs:** structured stored errors (severity, location, element, error type, message), per-stream error counts
  as a pre-filter, template and arguments for errors raised through `logTemplate`, and scope (processor filter,
  feed, time range).
- **Outputs:** a digest of clusters ranked by *distinct streams affected* (not raw error count), a member list and
  representative sample per cluster, and a "step this" jump into the Stepper.
- **State:** cause state (active, dormant, fix claimed, fix verified, expected, regressed) and per-feed data state
  (outstanding, reprocessing, cleared, accepted).
- **Human in the loop:** the system proposes and the person confirms. Silence is never success, because dormancy
  must carry evidence that processing actually happened.

### 4.2 The overlap

| Capability | AI translations needs it for | Triage needs it for |
|---|---|---|
| Bounded, typed context gathering | Sample, config, errors and output given to the model | Pre-filter by counts, scope selection, safe error echo |
| Error structure and fingerprinting | Safe feedback to the model (trusted vs untrusted text) | The cluster key |
| Candidate generation | Create or improve a transform | Propose a fix for a cluster (Option C) |
| Sandbox execution and validation | Run and score candidates | Reprocess to verify a fix |
| Scoring | Promotion gate, per-record bar | Verification threshold, ranking |
| Review state machine | Draft, approve, reject, retract | Cause state, fix claims, mutes |
| Provenance and versioning | Which transform, pattern and profile made this output | `doc_version` and `first_seen_doc_version` per cluster |
| Governor | Circuit breaker, spend breaker, kill switch | Cap on distinct fingerprints, with a truncation flag |

### 4.3 A concrete walk-through of the shared loop

An error cluster on a feed, handled end to end:

```text
1. error-signature   Fingerprint "No map found for '{}'" arg USER_TO_DEPT  -> cluster C-17, 212 streams
2. context           Build typed context: template (trusted), arg USER_TO_DEPT (type: map-name, passes
                     grammar), 3 sample records (only if data-to-model gate allows), XSLT at the element
3. advisor           Model proposes a diff to the XSLT. Tool-less. Output is text only.
4. static check      Parse the proposed XSLT: no new HTTP kind, no new NOT_FOUND, no removed schema validation
5. sandbox           Run proposal over the 3 samples plus golden events. Compile, schema, assertions.
6. scoring           ScoreResult with evidence tiers and confidence. Trust: yes/no with reasons.
7. proposal-review   Show diff and score tree. A person approves. One-time token bound to the diff hash.
8. provenance        Record model, prompt hash, diff hash, approver.
9. triage state      Cause C-17 -> fix claimed. Reprocess the 212 streams. If C-17 reappears, regressed.
```

Steps 1, 7, 8 and 9 are triage. Steps 2 to 6 are the AI translations loop. Only step 3 needs a model, and
briefs 03 to 05 need none of it.

---

## 5. The XSLT dependency parser prototype (`origin/sw-xslt-dependencies`)

### 5.1 What it is

The branch is one change set of about 7,300 added lines across 49 files. It is the prototype of the
folder-data-flow XSLT parser. It records what an XSLT *refers to* so that those references appear in
`doc_dependency` like every other document type's dependencies.

Main pieces (all paths under `stroom-pipeline/src/main/java/stroom/pipeline/xslt/` unless noted):

| Piece | Role |
|---|---|
| `XsltReferenceParser` / `XsltReferenceParserImpl` | Walks a stylesheet and returns `XsltReferences` |
| `XsltExpressionCompiler`, `XPathExpr`, `StubFunctionLibrary` | Compile the XPath inside attributes with Saxon, using stub Stroom functions so calls are recognised |
| `XsltValueResolver` | Folds variables and `concat()` to literals where possible, with bounded depth and cycle detection |
| `XsltReference`, `XsltReferences` | Result records: one finding, and the whole result |
| `XsltReferenceKind`, `XsltReferenceReason`, `XsltReferenceDirection` (in `stroom-core-shared`) | The vocabulary |
| `XsltReferenceLookup(Impl)` | Resolves a name or UUID to a document |
| `XsltStoreImpl` (changed) | Records dependencies on save, and warns when a copy could not repoint a reference |
| `XsltResourceImpl.checkReferences` (changed) | A REST check run as the *calling user*, for a References panel in the editor |
| `XsltConfig` (changed) | `referenceParseTimeout` (5 s) and `maxReferenceVariableDepth` (10) |
| `V07_14_00_005__populate_doc_dependency_xslt` | A migration that populates dependencies for existing XSLTs |
| `XsltReferencesPresenter` and `XsltPresenter` (client) | The References grid |
| `TestXsltReferenceParser` (about 2,000 lines), `XsltReferenceCorpusReport` | Tests, and a diagnostic that runs the parser over a real corpus |

Note the client code in this branch uses a presenter, whereas `AGENTS.md` says the new GWT guidance has no
presenters. That is a point for whoever merges it, not for this note.

### 5.2 The vocabulary

`XsltReferenceKind` is the useful part for safety:

| Kind | Meaning | Document target? |
|---|---|---|
| `IMPORT` | `xsl:import` or `xsl:include` | Yes (another XSLT) |
| `DICTIONARY` | `stroom:dictionary('NAME')` | Yes (Dictionary) |
| `REF_MAP_READ` | `stroom:lookup(...)`, `stroom:bitmap-lookup(...)` map name | No |
| `REF_MAP_WRITE` | A literal `<map>` element in the output | No |
| `HTTP` | `stroom:http-call` (direction `OUT`) or `stroom:fetch-json` (direction `IN`) | No (external) |
| `UNANALYSED` | An expression that could not be analysed | No |

`XsltReferenceReason` says why a reference is unresolved, and most are *not faults*:
`DATA_DRIVEN`, `PARAMETER`, `NON_LITERAL_BINDING`, `IMPORTED`, `AMBIGUOUS`, `NOT_FOUND`, `UNPARSEABLE`.

A finding is either resolved (`reason == null`) or unresolved, and the record's constructor refuses an
inconsistent one (a target *and* a reason, or candidates without `AMBIGUOUS`).

### 5.3 Worked examples

These follow the behaviour described by the branch's javadoc and test names. They were not run.

**Example 1: a lookup and an outbound call.**

```xml
<xsl:stylesheet version="2.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                xmlns:stroom="stroom">
  <xsl:template match="record">
    <Event>
      <Dept><xsl:value-of select="stroom:lookup('USER_TO_DEPT', data[@name='user']/@value)"/></Dept>
      <xsl:value-of select="stroom:http-call('https://collector.example.org/in', .)"/>
    </Event>
  </xsl:template>
</xsl:stylesheet>
```

Expected findings, in document order:

| kind | rawValue | reason | direction | line |
|---|---|---|---|---|
| `REF_MAP_READ` | `USER_TO_DEPT` | resolved | | 5 |
| `HTTP` | `https://collector.example.org/in` | resolved | `OUT` | 7 |

For an AI translations check this is exactly the signal wanted: *a proposed XSLT that adds an `HTTP` finding the
original did not have* is flagged before saving.

**Example 2: a computed endpoint.**

```xml
<xsl:variable name="host" select="/config/host"/>
<xsl:value-of select="stroom:http-call(concat('https://', $host, '/in'), .)"/>
```

The endpoint depends on the input, so the finding is `HTTP` with an unresolved reason `DATA_DRIVEN`. The parser
neither invents a destination nor drops the call. This is the property that makes it usable as a check: an
unresolved `HTTP` finding is still an `HTTP` finding.

**Example 3: an ambiguous dictionary.** Two Dictionary documents are both named `BLOCKLIST`.

`documentTargets()` returns *both* as edges. The javadoc explains why: an edge means "may use", so listing every
candidate keeps the graph complete, where choosing one would invent a fact and choosing none would lose a live
edge.

**Example 4: a name that exists nowhere.** `<xsl:import href="common-headers"/>` with no such document yields
`IMPORT`, reason `NOT_FOUND`. The javadoc calls this "the reason that turns a silent runtime failure into a
visible one". It is also useful for AI output: a model that hallucinates an import of a library that does not
exist is caught before the pipeline runs.

**Example 5: no false positives.** The tests include `literalInConditionIgnored`, `otherNamespaceIgnored`,
`xsltElementNamedMapIgnored` and `doubledBracesIgnored`. For instance `<xsl:template mode="m{stroom:dictionary('X')}"/>`
must *not* report dictionary `X`, because `mode` is not an attribute value template. The branch's commit
"Only scan attributes that really are attribute value templates" is about exactly this. For a safety check,
a low false-positive rate is what stops reviewers learning to ignore it.

### 5.4 What it cannot do yet, as a safety check

These are the points to settle before relying on it for A147.

1. **It never refuses.** By design `parse` "never throws", and a malformed or partly-read stylesheet still saves.
   The store records dependencies as "a rebuildable index, not part of the document". For AI translations the
   same parser output needs a *separate caller* that applies policy: reject, require confirmation naming the
   destination, or route to a second approver.
2. **It does not follow imports.** An imported stylesheet is reported as a dependency edge, and a variable
   declared there is `IMPORTED`. An `http-call` inside an imported file is only visible by walking the dependency
   graph transitively. The policy caller must do that walk.
3. **It reports the *literal* function set it knows.** The function names are literals in the parser
   (`dictionary`, `lookup`, `bitmap-lookup`, `http-call`, `fetch-json`). `ask-ai` and other network-capable
   `stroom:` functions are not in the list I read. Whether they are covered elsewhere in the branch was not
   checked. The assessment (§6.2) names `http-call`, `fetch-json` and `ask-ai` as the functions A147 must block,
   so the list needs to be reviewed against it.
4. **It does not look for standard XSLT escape hatches.** `document()`, `unparsed-text()`, `xsl:result-document`
   and `xsl:include` of an external URI are not in the kinds above. Only `xsl:import` and `xsl:include` are
   handled, and as document references. Whether other constructs are caught elsewhere was not checked. The
   assessment says `XsltStep` (the Tier 1 stand-in) already refuses `document()` and friends, so a shared policy
   layer would want one definition used by both.
5. **Permissions are deliberately the caller's.** `checkReferences` resolves with the *calling user's* rights and
   says so at length: wrapping it to be more complete "would turn the panel into a way of discovering documents".
   Accepted consequence: the report can differ from what the runtime does. Any policy check run by AI tooling
   must choose deliberately whether it resolves as the user, as the processing user, or as a system identity, and
   record why.
6. **Findings are "may use", not "will use".** A call inside a never-matched template is still reported.
   For a safety gate that is the right bias. For a scorer it would over-count.
7. **Bounded work.** The 5 s timeout returns a *partial* result flagged as incomplete, and the branch measured
   about 1.1 ms mean per stylesheet warm over 51 real stylesheets, with a first-parse cost of about 86 ms. A
   caller must treat `hasParseFailure()` as "unknown", never as "clean". The branch's guidance is not to show
   parse failures to a human author mid-edit, but a *policy* check on AI output must not ignore them.

**Update after checking the code (see §6.5).** Items 3 and 4 above were open questions. They are now partly
answered. The parser's function list is too narrow for an egress check: `send-email` and `ask-ai` exist, and the
probe in §6.5.2 shows that standard XSLT functions (`doc`, `document`, `unparsed-text`, `collection`,
`xsl:result-document`) reach the file system and the network without going through any Stroom function. The
parser would need a wider set of finding kinds, and, more importantly, **a static check cannot be the control.**
Runtime enforcement is.

### 5.5 How it plugs into the shared modules

| Shared module | Use of the parser |
|---|---|
| **sandbox / static check** | `parse(proposedXslt)` and diff against `parse(currentXslt)`. New `HTTP`, new `NOT_FOUND`, new `UNANALYSED` blocks or escalates. |
| **proposal-review** | The review panel lists every finding, so the reviewer sees "this change adds an outbound call to X" rather than hunting for it in 400 lines. |
| **scoring-core** | A `Scorer` that is a *gate*: zero tolerance for a new `HTTP` finding. Evidence tier **Verified**, because it is exact over the whole document. |
| **context** | Resolves which maps, dictionaries and imports the translation depends on, so the model is told what exists and cannot invent a name. |
| **triage (error-signature)** | `NOT_FOUND` on a lookup or dictionary predicts a "No map found" cluster before processing. |
| **folder-data-flow** | The same edges feed the structure view and the "which feeds lack a translation" gap view. |

A small interface that makes this reusable:

```java
/// Static findings about a candidate artefact, independent of how it was produced.
interface ArtefactInspector<A, F> {
    /// Inspects the artefact. Never throws; an incomplete read is reported in the result.
    Inspection<F> inspect(A artefact);
}

record Inspection<F>(List<F> findings, boolean complete) {}
```

`XsltReferenceParser` would be one implementation. A regex-lint inspector (from `regex-runtime-limits`) and a
Data Splitter inspector would be others.

---

## 6. Proposed module split

Principle: **interface-only modules with adapters**, so a consumer does not take a dependency on a particular
engine, and so the shared code does not tie delivery to Stroom core's review cycle (options paper §5).

| Module | Contents | First consumers |
|---|---|---|
| `ai-scoring-core` | `Scorer`, `ScoreItem`, `ScoringProfile` (immutable, versioned), `ScoreResult`, `ExplainNode`, `Assertion`, `CoverageEstimator` | Shapeshifter scorers, triage verification |
| `ai-inspection` | `ArtefactInspector`, policy rules (egress, removed validation, unbounded regex) | XSLT parser adapter, regex lint |
| `ai-context` | Typed, capped context builder; trusted-versus-untrusted error classes (D3) | Option C, Option E, Shapeshifter questions |
| `error-signature` | Fingerprint, normaliser version, roll-up | Triage owns it; translations consume it |
| `ai-sandbox` | Run an artefact over approved or synthetic samples with time and memory limits | Learning, fix verification |
| `ai-review` | Proposal state machine, one-time approval token bound to the diff hash, expiring mutes | Review queue, triage cause and data state |
| `ai-governor` | Retry, rate and spend limits, circuit breaker, kill switch, provenance record | All |
| `ai-advisor` | The tool-less `stroom-ai` wrapper | Option C, E, Shapeshifter |
| `egress-policy` (a Stroom core feature, not AI-specific) | One registry of permitted destinations, one guard called by every sink, one admin UI (§6.5) | Every option. It is the robust form of D4. |
| `ai-field-types` | Field type classifier, field profiles, type-agreement scorer (§6.3), catalogue and schema-map priors (§6.4) | Shapeshifter scoring, typed echo for the model, triage error arguments |

### 6.1 Core scoring shapes

```java
record ScoreItem(
        String id,
        double value,            // 0..1
        double weight,
        double threshold,
        boolean gate,
        boolean passed,
        double confidence,       // 0..1; 0 means "no evidence", never auto-accept
        EvidenceTier tier,       // OBSERVED, DECLARED, VERIFIED
        List<String> notes) {}

enum EvidenceTier { OBSERVED, DECLARED, VERIFIED }

record ScoringProfile(String id, int version, List<ScorerSetting> scorers,
                      double promotionFloor) {}

record ScoreResult(double total, boolean gatesPassed, double confidence,
                   boolean autoPromotable, ExplainNode explanation) {}
```

Aggregation rules, stated once and tested once:

- A failed gate wins, whatever the total.
- Total confidence is the *minimum* across items that carry weight, not the average.
- `autoPromotable` requires at least one **Verified** item that covers the fields the profile marks as critical.
- No items at all gives confidence 0 and `autoPromotable = false`. This removes the blank-state problem.

These map onto existing classes: `Scorer` and `Score` already exist, `Scorecard` becomes the aggregator, and
`Verdict` is the nearest thing to `ScoreResult`. The change is additive: tier, confidence and explain tree are new
fields.

### 6.2 Why not split everything now

`development-process/ai-assisted-development-process.md` advises against abstracting on the first instance. Today
the second consumer is concrete for scoring (triage verification), and for the context and inspection pieces
(Options C and E). For the rest, the shapes would be guesses. The order in §7 reflects this.

---

### 6.3 Field type classification (`ai-field-types`)

This section adds a ninth module, drawn from the "type" machinery in the query-feeds design: the phase-1 pattern
library, the phase-2 semantic types, and `query-optimiser/07-domain-types.md`. It was first written before phase 2
had been read and has since been revised against it. **Nothing in the query-feeds design is implemented**, so
every dependency below is on a design, not on code.

#### 6.3.1 What the query-feeds design provides

| Piece | Source | What it gives |
|---|---|---|
| Pattern library | Phase-1 plan, "Pattern library" | IPv4, IPv6, MAC, hostname, common hashes, port, common timestamp formats. Counts matches and multiple matches per path. **No value is ever retained.** |
| Overlaps recorded | Phase-1 plan, "Worked example: pattern counting" | `192.168.4.22` is both an IPv4 address and a syntactically valid hostname. The library records both so a later step can weigh them with the path name and schema type, rather than inheriting a guess. |
| Schema as ground truth | Phase-1 plan, "The schema as ground truth" | The event-logging schema's simple types (`IPAddressSimpleType`, `DeviceMACAddressSimpleType`, `PortSimpleType`, `DateTimeSimpleType`, the enumerations) give the expected type of each output element. The plan sets a precision target of at least 95% before inference may set defaults. |
| Domain types | `query-optimiser/07-domain-types.md` | A `class.attribute` label such as `Host.ipaddress`. Today it is used only to reject incompatible joins, and it is opt-in. |
| Name evidence | Phase-1 plan | The path name is a second signal. The plan notes that "port pattern, 94% of records, once each" is very probably `src_port`. |
| Semantic types | Phase-2 plan, 2A | A `namespace:name` identity (`event-logging:ip-address`, `acme:case-ref`), never a bare string, so two sites' "identifier" cannot collide. A single `areEquivalent(a, b)` service, which at first answers identity only, so no code compares types by string. Storage type (text, number) is kept separate from semantic type (what it means). |
| Stacked evidence | Phase-2 plan, "Assignment to catalog entries" | A location's type records *every* source: schema constraint (Declared), path name (Declared), value inspection (Observed), administrator confirmation (Declared). Three sources agreeing is strong. One weak or conflicting source is "a suggestion for an administrator, never a decision". |
| Inference gate | Phase-2 plan, same section | At 95% or better precision on schema-typed paths, inferred types may pre-fill defaults. Below that they are suggestions only. Phase 1 measures it. It has not been measured, because nothing is built. |
| Pattern assertion | Phase-2 plan, 2C, assertion kinds | `Pattern(path, type)`: every value matches the type's pattern, "one pattern test per value, **shared with catalog inference**". The pattern library already has two consumers in the design. |
| Generated path map | Phase-2 plan, "The shipped vocabulary" | Every schema location with its declared type and occurrences, all Declared, produced by promoting the phase-1 schema walker to main code. Available with no data at all. |

#### 6.3.2 What the idea is, and its limits

The idea: if an input field's values match a type, that is a strong clue about which output element they belong
in. Used in three places.

1. **Candidate pruning.** Narrow what the model has to choose between, and give a scorer a cheap contradiction
   check.
2. **A scorer.** Compare the type of the source field with the type the target element expects.
3. **A safe summary for the model.** Send "field 3: 100% IPv4, 40 distinct values" instead of raw samples (D5), and
   use the same classifier as the narrow-grammar check that lets a value be echoed safely (D3).

Limits, stated plainly:

- **It cannot choose between two fields of the same type.** In the §2.3 firewall example, `src` and `dst` are both
  IPv4. Both the correct and the swapped output pass every type check. Type narrows candidates and nothing more.
  Telling them apart needs name evidence (`src`, `dst`), position, or a golden event.
- **Schema conformance already enforces constrained types.** An IP in an IP element that does not match the
  pattern already fails. The new coverage is in *unconstrained* elements (the plan notes
  `EmailAddressSimpleType` and `TagSimpleType` are unconstrained restrictions of `xs:string`) and in plain
  strings, where nothing currently checks that the value is the right kind of thing.
- **The evidence is asymmetric.** A clear mismatch is strong negative evidence. A match is weak positive evidence
  and is only ever **Observed** (§3.1), never Verified.
- **Many fields have no distinctive type.** Usernames, free text, process names and most enumerations match
  nothing. An enumeration is only identifiable by a low distinct-value count, not by a pattern.

#### 6.3.3 A worked example

Input (one syslog-style firewall line, as in §2.3):

```text
Nov  4 10:00:01 fw01 DROP src=10.0.0.5 dst=203.0.113.9 dport=443
```

What a classifier returns for each field. Every type that matches is returned, not just one, because the
overlaps are the point:

| Input field | Value | Types that match | Notes |
|---|---|---|---|
| timestamp | `Nov  4 10:00:01` | `timestamp.syslog` | Distinctive. Year is absent, which is itself worth telling the model. |
| host | `fw01` | `hostname` | Weak. Any word is a hostname. |
| action | `DROP` | none | Enumeration candidate only if its distinct count is low across the sample. |
| src | `10.0.0.5` | `ipv4`, `hostname` | Overlap. IPv4 is the stronger reading. |
| dst | `203.0.113.9` | `ipv4`, `hostname` | Same type as `src`. **Ambiguous target.** |
| dport | `443` | `port`, `integer` | Overlap. |

Candidate pruning then gives, for the two address fields: both can go to `Source/Device/IPAddress` or
`Destination/Device/IPAddress`, and neither type check can choose.

**The ambiguity rule.** If two input fields share a type, and two or more output elements accept that type, and
nothing else (a name or position hint) separates them, mark the mapping *swap-risk*. A swap-risk mapping cannot
be auto-promoted. In review mode the screen says so and asks for one golden event:

```text
Cannot tell which address is the source. Two input fields (src, dst) are both IPv4 and two
output elements accept IPv4. The field names suggest src -> Source, dst -> Destination, but names
are Observed evidence only. Supply one example event, or confirm.
```

This turns the §2.3 failure from a silent pass into a targeted question.

#### 6.3.4 The interface

```java
/// Classifies a single value against the known types, returning every type that matches.
interface FieldTypeClassifier {

    /// Returns every type the value matches, strongest first. Never throws and never retains the value.
    /// An empty result means "no known type", which is not the same as "invalid".
    TypeMatches classify(CharSequence value);
}

record TypeMatches(List<FieldType> types) {}

/// A semantic type. The identity is the phase-2 qualified name, `namespace:name`, never a bare string, and
/// two types are compared only through the equivalence service, never by string equality.
record FieldType(SemanticTypeId id, int specificity, @Nullable String schemaSimpleType) {}

/// Illustrative: the phase-2 design's own identity type, constructed only through validation.
record SemanticTypeId(String namespace, String name) {}

/// Summarises a field across a sample. Holds counts only, never values, so it can be sent to a model
/// and stored without carrying the data it describes.
record FieldProfile(
        String fieldName,
        long valueCount,
        Map<String, Long> matchCountsByType,   // multiple matches are counted for each type
        long distinctEstimate,                 // a sketch, not a set of values
        boolean looksEnumerated) {}
```

`FieldProfile` deliberately matches what the phase-1 accumulator already records (match counts and multiple-match
counts per path), so the two should share the pattern library rather than each keeping their own.

The scorer that uses it:

```java
/// Scores whether each output value's type agrees with the type its element expects.
final class TypeAgreementScorer implements Scorer {
    // Per output leaf with a traceable source field:
    //   contradiction  : source type incompatible with the element's expected type
    //   agreement      : source type compatible
    //   unknown        : either side has no type, which scores nothing and is not a failure
    // Result: share of known comparisons that agree, plus swap-risk findings from §6.3.3.
    // Evidence tier: OBSERVED. A contradiction above the configured rate fails a gate.
}
```

**Lineage is the weak point.** To compare a source field with an output element, the scorer must know which input
field an output value came from. The only cheap way is to match the output value back to an input value, which
is ambiguous when the same value appears twice in a record (an address that is both source and destination, a
repeated `0`). Where the match is ambiguous the comparison is `unknown`, not a guess. A stronger approach is for
the fragment runner to record real provenance. §6.3.8 reports what it can and cannot do today.

#### 6.3.5 One compiled classifier

Compiling every type check into one structure is feasible. Four cautions decide how.

1. **Overlaps need all matches, not the first.** A plain alternation returns one winner and hides the overlap that
   §6.3.1 treats as evidence. In `java.util.regex` every match can be recovered in one pass by giving each type
   an optional lookahead with its own capture group, then reading which groups are set. Illustration only, and
   **not run**:

   ```java
   // Each type is a zero-width lookahead that captures the whole value when it matches, with an empty
   // alternative so a non-match does not fail the whole pattern. Read which named groups are non-null.
   final Pattern classifier = Pattern.compile(
           "^(?:(?=(?<ipv4>(?:\\d{1,3}\\.){3}\\d{1,3})\\z)|)"
           + "(?:(?=(?<mac>(?:[0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2})\\z)|)"
           + "(?:(?=(?<port>\\d{1,5})\\z)|)"
           + "(?:(?=(?<hostname>[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?"
           + "(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*)\\z)|)");
   ```

2. **Combined is not automatically faster.** Java's engine backtracks, and one large pattern can be slower than
   several small anchored ones. The larger gains usually come from:
   - **dispatching cheaply first**: look at length and first character, so a 17-character value starting with a
     hex digit is tried against MAC and not against timestamps;
   - **hand-written scanners** for the regular formats (IPv4, MAC, hex hashes, integers), keeping regex for the
     irregular ones such as timestamps;
   - **caching per distinct value**, since feeds repeat values heavily.

   Treat all three as hypotheses to measure, not facts.
3. **The values are attacker-controlled.** The patterns are ours, but a crafted value can still trigger
   catastrophic backtracking in a badly written one. `regex-runtime-limits` covers this for Stroom's existing
   regex use: a Saxon backtracking limit, static lint rules and a scaling benchmark. The classifier should be held
   to the same rules. A linear-time engine is the safest choice. RE2/J has no lookahead and so cannot use the
   trick in item 1, and whether it offers a set-matching API was not checked. A hand-rolled automaton for the
   small fixed set is the other route.
4. **Where it runs decides whether speed matters.**

   | Place | Volume | Verdict |
   |---|---|---|
   | Learning time, over the sample | Small (tens to thousands of values) | Speed hardly matters. Use it freely. |
   | Judging a candidate over the held stream | One stream | Fine, with per-value caching. |
   | Serving, per record | Every record | Conflicts with "speed first" (`full-design.md`). Sample it, or use it only for drift detection. Phase 2 has a ready method for deciding: its 2C gate measures one check as a share of *parse cost* (under 5% proceed; 5 to 15% proceed with the cost shown; over 15% stop), using the phase-1B harness. Adopt the same measure and thresholds here rather than inventing new ones. |

**Measuring it.** The phase-1 plan already vendors the event-logging schema and walks it for each path's declared
type. The same walk gives labelled data for a benchmark: precision and recall of the classifier per type, plus
throughput of the combined classifier against separate patterns and against hand-written scanners, on real
feeds. No figures exist yet.

#### 6.3.6 How it connects to the rest of the note

| Module | Use of the classifier |
|---|---|
| `ai-scoring-core` | `TypeAgreementScorer` is an ordinary scorer, tier OBSERVED, with an optional gate on contradictions. |
| `ai-context` | Sends `FieldProfile` instead of raw samples where policy allows, and uses `classify` as the type-check for echoed values (D3). |
| `ai-sandbox` | Reports the swap-risk findings alongside compile and schema results. |
| `ai-review` | Shows type findings and the swap-risk question in the review panel. |
| `error-signature` (triage) | The same classifier can type the arguments of an error (a lookup key, an element name, a date) for the typed-argument check that Option C step 2 needs. |
| Phase-1 accumulator | Should share the pattern library rather than keep a second copy. |

#### 6.3.7 Risks

| Risk | Notes |
|---|---|
| Over-trusting a match | A type match is Observed, and a hostname pattern matches almost anything. Keep specificity in `FieldType`, and weight by it. |
| Type profiles leak structure | The phase-1 plan notes that Tier 2 statistics can reveal names by inference ("port pattern, 94% of records, once each" is probably `src_port`). A profile sent to a model is a Tier 2 disclosure, so it needs the same data-to-model gate (D5) as a sample. |
| Two pattern libraries drift apart | One in the accumulator, one here. Share one. |
| Inference not yet proven | The 95% precision target is a target, not a result. |
| Lineage guesses | See §6.3.4. Ambiguous matches must score as unknown. |

#### 6.3.8 Can the fragment runner record provenance? (checked in the code)

Question 12, answered by reading `PipelineFragmentRunner`, `FragmentRunner`, `RecordTap`, `RecordJudge`,
`ExtractionResult` and `NestedElementData`. Nothing was run.

| Level of provenance | Does it exist today? | Evidence |
|---|---|---|
| **Per record: which parsed record produced which output record** | **Yes** | `FragmentRunner.takeRecords()` returns, per record in record order, a `FragmentRecord` that holds what each element of the fragment was given and wrote (`NestedElementData`). `RecordTap` keeps the parser's record "as a document of its own", and `RecordJudge.judge(index, record, parsed)` is handed both the output record and the record as the parser cut it. |
| **Per record: the span of raw input a record came from** | **Partly** | `ExtractionResult.recordRanges` gives a one-based span per record for the Data Splitter step run on its own (`DataSplitterRunner`). `PipelineFragmentRunner` does not: its own header says a headless run "has no span to ask for", so it takes each element's input from the previous element's output instead. |
| **Per field: which input field produced which output value** | **No** | Nothing in the runner, the tap or the judge records it. The XSLT is run as an opaque transform, and Saxon does not track where a value came from. |

**What the per-record pairing gives the type scorer (§6.3).** Matching an output value back to an input value need
no longer search the whole raw line. It can search the **parsed record**, which Data Splitter has already broken
into named fields. The ambiguity is then limited to the same value appearing twice **within one parsed record**,
for example an address that is both source and destination. That is a real improvement, but it does not remove the
case in §2.3.

**A way to get field-level provenance without changing Saxon or the engine (a proposal, not tested).** Run the
candidate a second time, in the sandbox, over a copy of the parsed record in which **each field's value is
replaced with a distinct sentinel of the same type**. Where each sentinel appears in the output is the provenance.

```text
Parsed record:   src=10.0.0.5      dst=203.0.113.9
Sentinel copy:   src=10.99.0.1     dst=10.99.0.2        (distinct, both valid IPv4)
Output:          Source/IPAddress = 10.99.0.1     Destination/IPAddress = 10.99.0.2
Conclusion:      src -> Source,  dst -> Destination        (exact, even when the real values were equal)
```

This answers the "same value twice" case directly, and it needs the fragment runner to run the XSLT stage on an
edited record, which its per-record structure suggests is possible. Limits, stated plainly:

- **The sentinel must be valid for its type.** A transform that tests or reformats a value (an address check, a
  regular expression, a date parse) may take a different branch on a sentinel that does not look like the real
  thing. `ai-field-types` (§6.3) is what can generate distinct, type-valid sentinels for IPv4, IPv6, ports,
  timestamps and so on.
- **Values that are looked up or mapped will not survive.** A value that is translated through reference data or an
  enumeration (`DROP` to `Deny`) comes out as something else, so the sentinel is not found. It then reports "not a
  direct copy", which is itself useful, but it is not provenance.
- **Values that are combined or split** (a concatenation, a substring) show a partial match, which has to be reported as such.
- **Cost and safety:** one extra run per record sampled, under the same limits as any candidate run.
- **A field used in two places** shows both, which is correct.

Status: the record-level facts above are read from the code. The sentinel method is my suggestion and has not been
prototyped. Whether to build it is for the project to decide. For 1.0 it is optional: without it, the type scorer
falls back to matching values within the parsed record and reports `unknown` where ambiguous.

### 6.4 The catalogue as a prior for where data should go

The question: if we know the type and cardinality of what has been put into each field of the existing feeds, can
a model infer where new data should go, and is that a use of the query-feeds catalogue? **Yes, with four
conditions** (§6.4.4). It is a good use, and arguably a better one than the learning-time hint it first looks like.

#### 6.4.1 What the catalogue holds

Per feed and stream type, per path (and per name under a `Data[@Name=...]` path), the phase-1 design records:

- occurrences, number of records containing it, and **the maximum per record** (the evidence for cardinality);
- an inferred storage type;
- **pattern evidence**: how many values matched each pattern (IPv4, MAC, port, timestamp, and so on), with
  multiple matches counted, never retaining a value;
- an **estimated distinct-value count** (a sketch, so a low count identifies an enumeration);
- first and last seen, which is the drift signal.

Everything it records is **Observed**. It is bounded (5,000 entries per feed and stream type, with overflow
counted) and cheap, and it is explicitly not a copy of the data.

#### 6.4.2 Two uses

**Use A: a prior for a new feed (learning time).** The new feed's *input* fields are profiled by the classifier
in §6.3 (`FieldProfile`). The catalogue supplies the profile of every *output* path, drawn from existing feeds,
plus the Declared profile from the schema's generated path map. A deterministic step ranks which output paths each
input field resembles. The model is then given the ranked candidates with their evidence, instead of being asked
to guess from raw samples.

An illustrative comparison. All figures are invented, to show the shape of the evidence:

| Input field (new feed `FW_EVENTS`) | Input profile | Candidate output path | What existing feeds show for that path | Compatible? |
|---|---|---|---|---|
| `src` | IPv4 100%, once per record, about 1,000 distinct | `EventSource/Device/IPAddress` | IPv4 99%, at most once, high distinct | Yes |
| `dport` | port 100%, once per record, about 30 distinct | `Data[@Name='dst_port']` | port 100%, at most once, low distinct | Yes |
| `dport` | same | `EventSource/Device/IPAddress` | IPv4 99% | **No, type contradiction** |
| `action` | no pattern, once per record, 4 distinct | `EventDetail/Authenticate/Action` | an enumeration of 14 values | Weak. Low distinct count agrees, but the values must be mapped to the enumeration, not copied. |
| `src` and `dst` | both IPv4 | `Source/...` and `Destination/...` | both look the same | **Ambiguous.** The prior cannot separate them. §6.3.3 still applies. |

**Use B: drift and regression (relearning and serving).** A candidate or live binding's output is profiled and
compared with the catalogue of *this feed*. For example, the previous translation filled `EventSource/Device/IPAddress`
in 99% of records and a relearned candidate fills it in 41%. That is a regression that a structural scorer misses,
because the output is still schema-valid.

This answers two gaps the assessment lists:

- the **corpus is capped at 100 entries per binding**, oldest dropped first, so a rare record type learned early
  can fall out and later regress unnoticed. The catalogue is an aggregate over the whole feed, not a sample of it;
- **format drift under a binding is not scored** (ISSUES 2). The catalogue's first-seen and last-seen figures are a
  ready drift signal.

Use B needs no model, is deterministic, and is the stronger of the two. If only one is built, build this one.

#### 6.4.3 How the pieces fit, with the interface

```java
/// What existing translations have put into each output path. An adapter over the shape catalogue, so
/// nothing here depends on the catalogue being built.
interface OutputPathPriors {

    /// Paths whose observed profile is compatible with the input profile, strongest first, each carrying
    /// its evidence. Never returns values. Declared (schema map) and Observed (other feeds) evidence are
    /// returned separately and are not blended.
    List<PathCandidate> candidatesFor(FieldProfile input, PriorScope scope);

    /// The profile this feed's own translation produced, for regression and drift checks.
    Optional<PathProfile> profileOf(FeedRef feed, String path);
}

record PathCandidate(String path, Claim<PathProfile> declared, Claim<PathProfile> observed,
                     double compatibility, List<String> reasons) {}
```

`compatibility` is a product of factors in [0, 1], each *pessimistic* when evidence is missing: type overlap,
cardinality (an input field seen twice in one record cannot go to a path whose Declared maximum is one), and
distinct-count bucket (an enumeration-like input does not resemble a near-unique identifier). The product follows
the confidence rule from §3.3, so a path with no evidence scores low and cannot be auto-selected.

`PriorScope` says which existing feeds count. It is the hard part (§6.4.4, condition 4).

The model's role is then narrow and checkable: choose among the ranked candidates, and write the transformation.
The same priors feed the `TypeAgreementScorer` afterwards, so the model's choice is *checked* against the evidence
and not merely trusted. Asking a model to infer placement from raw statistics alone would be unverifiable, since
the statistics are only Observed.

#### 6.4.4 Conditions and risks

1. **The catalogue profiles output, not input.** `Raw Events` is out of scope for it. The input profile must come
   from `ai-field-types` over the learning sample. Two profiles computed in two places are only comparable if they
   use **one shared pattern library and one sketch method**, which is the same reason §6.3.7 gave. Phase 2 already
   shares the library between inference and `Pattern` assertions, so ours would be the third consumer.
2. **Priors inherit the mistakes of the feeds they come from.** If an existing translation maps a field wrongly,
   the catalogue faithfully records the wrong thing, and a new feed learns it. Weight by evidence: prefer feeds
   whose translations are human-reviewed, and **exclude feeds whose translations are unreviewed AI output** from
   the prior (the triage agent reported that brief 10 sets the same rule for exemplar pools; not re-checked).
   Where the catalogue holds a Verified claim for a path (phase 2, 2C), that outweighs an Observed profile.
3. **Disclosure across feeds.** A profile of feed A, handed to a model working on feed B, is information about A.
   The phase-1 plan warns that Tier 2 statistics can reveal names by inference ("port pattern, 94% of records,
   once each" is probably `src_port`), and feeds carry classifications. The data-to-model gate (D5) therefore has
   to apply to catalogue statistics *across* feeds, not only to samples. The phase-1 tiers (ordinals instead of
   names at the lower tiers) are the existing mechanism and should be reused.
4. **"Similar feed" means "same schema" (project-owner direction).** The catalogue itemises data that has already
   been normalised into a schema, so **any catalogue for a feed whose output matches the target schema can be used
   as a prior**, provided we have permission to use it (condition 3 and D5). No vendor, product or attribute
   similarity is needed. `PriorScope` is therefore "feeds whose output is in this schema (and version) that the
   user may read".

   The reason is **consistency of meaning**. All feeds that carry the same schema should carry the same meaning,
   cardinality and typing in it. A new feed that records `EventDetail/Authenticate/Action` differently from every
   other feed is probably a mistake, or at least a decision worth a person's attention. So the catalogue serves two
   purposes here: a prior for where data should go, and **a consistency check across feeds** (the cross-feed form
   of Use B, §6.4.2): "other feeds using this schema record this element once per event as an `Action` from this
   set, and this candidate does something else."

   Caveats, so this is not over-read:
   - **Same schema does not guarantee same meaning.** The event-logging schema is permissive, and feeds of
     different kinds legitimately use different parts of it. A firewall and a login log do not share cardinalities.
     So a deviation is a **finding for a person**, not an error, and the output is "differs from N of M feeds", with
     the evidence.
   - **A schema-wide prior is broad.** Where there are many feeds, weighting by closeness (same event type, same
     `EventDetail` branch) is an optional refinement, not a precondition.
   - **Permission has to be checked per feed**, not assumed from the schema (condition 3).
   - Conditions 2 and 7 still apply: a prior inherits the mistakes of the feeds it comes from, and everything is
     Observed until verified. Consistency across feeds is evidence of agreement, not of correctness.
5. **Untrusted text in the catalogue.** Where a translation builds `Data[@Name='...']` names from the data (for
   example key=value logs), those names came from attackers. A catalogue entry's name is then untrusted text and
   must pass the typed-echo check (D3) before a model sees it. This is the reason phase 1 has ordinals; use them.
6. **Type and cardinality still do not separate same-typed roles.** The `src`/`dst` row above is the standing
   example. The prior narrows, the ambiguity rule asks.
7. **Everything is Observed until verified.** Priors are suggestions with evidence, and the phase-2 rule applies:
   Observed is not enough to take a shortcut that could change an answer.

#### 6.4.5 Continuing verification once a binding is live (phase 2, 2C and 2D)

Phase 2's assertion machinery is the existing design for "keep checking after promotion", and it fits the
Shapeshifter binding closely.

| Phase 2 | Shapeshifter equivalent | Note |
|---|---|---|
| Assertion kinds `MaxOccurs`, `Pattern`, `Enumeration`, `ClosedPaths` | Business-rule assertions and the per-record bar | Same idea, different engine. A translation's declared assertions could become 2C assertions, so they are checked on every record of every stream at translation time, not only during learning. |
| Verification recorded per *output stream as written*, as ranges in a dedicated table, not in `meta_val` (30-day deletion) | `shapeshifter_output` rows, which already name the binding and snapshot per stream | Aligns. Phase 2's reasoning on retention (attributes vanish after 30 days) applies to any verification evidence we record. |
| 2D: a **composite digest** of the pipeline and every document it references, stored on `meta` | The binding's snapshot, which records "its documents' versions" (design document 08) | The same idea. If 2D is built, the snapshot digest could be stored in the same place and the audit trail would then not need a Shapeshifter-only table. |
| 2D's stated gap: reference data used in lookups is **outside** the digest | The same gap applies to a translation that uses reference-data lookups | Two streams with the same digest were produced by the same logic, not necessarily from the same reference data. Record this limit in any claim that depends on it. |
| `AssertionFilter` must sit **immediately before the stream writer** | Where Shapeshifter's `RecordGate` sits (design document 03) | Same placement rule. A check placed earlier can be invalidated by a later element. |
| Gate: stop if one check costs over 15% of parse cost | The serving-cost concern in §6.3.5 | Reuse the measure. |

This also tightens the §3.1 point: a golden-event pass is Verified *for those events*, and live Verified evidence
accumulates only from per-stream checks while the binding serves data.

#### 6.4.6 Where it sits in the module split

It adds a catalogue adapter to `ai-field-types`: `OutputPathPriors` behind an interface, with an in-memory
implementation for tests (as the Shapeshifter module already does for its other seams) and a catalogue-backed one
once the catalogue exists. Because nothing is built, the in-memory implementation is where it starts, fed from the
vendored event-logging schema for the Declared half. That also makes it testable now.

### 6.5 Egress policy

**Requirement (from the project owner):** the egress policy covers everything. One UI gives permission for all
egress. Anything not permitted is blocked.

This section lists every sink I found, reports what was verified and how, and proposes one policy. "Egress" is
used broadly here: anything that moves data out of the Stroom process to somewhere a person has not approved,
including the local file system and the network. Reads from the local file system are included because the same
mechanism that reads a file can be used to send it somewhere else.

#### 6.5.1 What was checked, and how

| Claim | Method | Status |
|---|---|---|
| The Stroom XSLT functions that touch the network or send data | Read the `xsltfunctions` directory and the annotations of `HttpCall`, `FetchJson`, `SendEmail`, `AskAi`. Function bodies were **not** read. | Verified from signatures |
| Pipeline elements that send data out | Listed classes annotated `@ConfigurableElement` | Verified from names. Behaviour not read. |
| `xsl:import` and `xsl:include` of an external file | A probe (§6.5.2) | **Verified blocked** at compile time |
| `doc()`, `document()`, `unparsed-text()`, `collection()` reading local files | The probe | **Verified: they read** |
| `xsl:result-document` writing a file | The probe | **Verified: it wrote** |
| `doc()` and `unparsed-text()` opening an outbound connection | The probe, against a closed port | **Verified: a connection was attempted** (the error was "Connection refused") |
| Java reflexive calls (`java:` namespace) | The probe | **Verified unavailable** in Saxon-HE, with the error "Reflexive calls to Java methods are not available under Saxon-HE" |
| The same behaviour inside a running Stroom | Not done | **Not verified.** See the limits below. |

#### 6.5.2 The probe, and its limits

`Probe2.java` (source in the Appendix) mirrors Stroom's code path as closely as could be done outside a running
node:

- `new Processor(false)`, as in `XsltPoolImpl.createValue`;
- a compile-time `URIResolver` set on the `XsltCompiler`, as in `XsltPoolImpl`. The probe's resolver **refuses
  everything**, which is stricter than Stroom's `CustomURIResolver`;
- execution through `TemplatesImpl`, then `TransformerImpl`, then `newTransformerHandler()`, as in
  `XsltFilter.startDocument`;
- Saxon-HE 9.9.1-8, the version in `gradle/libs.versions.toml`.

Result, with a refusing compile-time resolver:

```text
unparsed-text   => RAN: [secret-line-1]            (read a local file)
doc             => RAN: [hello]                    (read a local XML file)
document        => RAN: [hello]
collection      => RAN: [1]                        (listed a local directory)
result-document => RAN: [ok]   and out.txt now contains "written"   (wrote a local file)
import-external => BLOCKED: resolver refused: file:///.../imp.xsl   (compile time)
```

The point: the compile-time resolver blocked the `xsl:import` of an external file, but **did not stop any of the
runtime functions**. The resolver Stroom installs applies when the stylesheet is compiled. The runtime
transformer does not use it. I searched `stroom-pipeline` and found no runtime `URIResolver`, unparsed-text
resolver, collection finder or output resolver being set, but I did not search the whole repository.

Limits, stated plainly:

1. **This was not run inside Stroom.** A real node might set something I did not find. Treat §6.5.2 as strong
   evidence and write the equivalent test against a real node (§6.5.8).
2. The operating system decides what the JVM may read and write. In a hardened container the reachable files are
   fewer, but the Stroom process's own configuration, keystores and data are exactly what it can reach.
3. This exposure **already exists today** for anyone who can edit an XSLT. The AI work changes who can *cause* it
   (an injected instruction, or a model's mistake), not whether it is possible.
4. Whether the Tier-1 stand-in `XsltStep` refuses these constructs, as the assessment says, is consistent with
   this finding. That stand-in refusing them means the test suite would not reveal the gap on a node, which is
   the assessment's own warning about A147.

#### 6.5.3 The inventory of sinks

Everything below needs an entry in the egress policy. "Kind" is the category the policy uses (§6.5.4).

**Stroom XSLT functions** (from the `xsltfunctions` directory; the registration of `send-email` and `ask-ai` is
in separate modules, `EmailXsltFunctionModule` and `AiXsltFunctionModule`, and I did not check whether either is
conditional):

| Function | Sends or reaches | Kind | Notes |
|---|---|---|---|
| `http-call` (url, headers, mime type, body, client config) | A URL, with a body (POST) | HTTP | The client config argument is JSON from the XSLT. See §6.5.5. |
| `fetch-json` (url, client config) | A URL (GET), response read in | HTTP | Same client config argument. A GET can still carry data in the URL. |
| `send-email` (to, subject, body, cc, bcc) | Email recipients through the site's mail setup | EMAIL | Destination is an address, not a URL. |
| `ask-ai` (model name or UUID, message, instructions) | A configured model provider | AI_MODEL | Destination is a model document. Also sends data to a third party and costs money. |

**Standard XSLT and XPath functions** (verified by the probe, §6.5.2):

| Construct | Reaches | Kind |
|---|---|---|
| `doc()`, `document()` | A file or URL, read | FILE, HTTP |
| `unparsed-text()`, `unparsed-text-available()` | A file or URL, read | FILE, HTTP |
| `collection()` | A directory listing, read | FILE |
| `xsl:result-document` | A file, written | FILE |
| `xsl:import`, `xsl:include` | Another XSLT | Blocked at compile time (not a runtime sink) |

**Pipeline elements** (names from `@ConfigurableElement` classes; behaviour not read):

| Element | Presumed destination | Kind |
|---|---|---|
| `HTTPAppender`, `HttpPostFilter` | A URL | HTTP |
| `FileAppender`, `RollingFileAppender`, `HDFSFileAppender` | A file system path | FILE (including HDFS) |
| `S3Appender` | An object store bucket | OBJECT_STORE |
| `StandardKafkaProducer` | A Kafka cluster and topic | MESSAGE_BUS |
| `SolrIndexingFilter`, `ElasticIndexingFilter` | A search cluster | SEARCH_BACKEND |
| `ShapeshifterAiParser` | A model provider, through `stroom-ai` | AI_MODEL |

**Data-read functions that are not egress but belong near it.** `meta-stream-for-id` takes any stream id and
returns that stream's data, and the lookup functions (`lookup`, `sql-store-lookup`, `plan-b-lookup`) read other
stores. I searched the function classes for explicit permission checks and **found none**. They may delegate to
services that apply the *processing user's* permissions, not the author's, but I did not read those services.
Unresolved; see open question 18.

Not egress, but worth a decision: `put` and `get` hold state across records within a task, and `add-meta` sets
attributes on the output stream. Neither leaves the process. They could still be misused to carry data between
records or to alter stream attributes.

**One sink that is not Saxon at all:** HTTP clients created from the client-config argument are cached
(`CommonHttpClient`, a `configCache` keyed by the config string). A policy decision has to be made on the
*request*, not on the cached client.

#### 6.5.4 One policy, one registry, one guard

**Principle.** Every sink calls one guard before it acts. The guard consults one registry. The registry is
administered in one UI. Nothing leaves the process unless an enabled rule permits it. There is no per-function
list.

**Deliberately simple.** The first design had patterns, directions, scopes, client profiles, limits and expiry.
Each is a place for a gap or a false sense of safety, and none is needed to start. They are listed in §6.5.4.1 as
considered and deferred.

```java
/// The single decision point. Every sink calls this before sending, reading or writing anything outside the
/// Stroom process. Fails closed: an exception, a missing rule and an unknown kind all deny.
interface EgressGuard {

    /// Decides whether this request is permitted, and records the decision.
    EgressDecision check(EgressRequest request);
}

record EgressRequest(
        EgressKind kind,            // HTTP, EMAIL, AI_MODEL, FILE, OBJECT_STORE, MESSAGE_BUS, SEARCH_BACKEND
        String destination,         // already normalised (see below): URL, address, path, bucket, model doc UUID, topic
        EgressSink sink,            // e.g. function http-call, element HTTPAppender
        EgressContext context) {}   // pipeline, feed, stream id: recorded for audit, not used for matching

record EgressDecision(boolean permitted, @Nullable String ruleId, String reason, EgressMode mode) {}
```

**Rule fields** (what an administrator enters):

| Field | Meaning |
|---|---|
| Kind | One of the kinds above |
| Destination | One normalised string (examples below). Never a wildcard or a regular expression. |
| Match | **Exact** or **Prefix**, chosen by the administrator. Default Exact. |
| Comment | Why this is permitted, and who is accountable. Required. |
| Enabled | On or off |

The usual Stroom audit fields (created and updated, by and when) are kept. Nothing else.

Examples:

```text
HTTP          https://siem.example.org:8443/api/events        Exact
HTTP          https://siem.example.org:8443/api/              Prefix   (any path under /api/)
EMAIL         alerts@example.org                              Exact
FILE          /stroom/output                                  Prefix   (any file under this directory)
OBJECT_STORE  s3://audit-archive/stroom/                      Prefix
MESSAGE_BUS   kafka://broker1:9092/events                     Exact
AI_MODEL      <model-document-uuid>                           Exact
```

**What *Prefix* means.** It is never a string prefix. A string prefix would let `https://siem.example.org`
match `https://siem.example.org.evil.example`, and `/stroom/output` match `/stroom/output-old`. For each kind the
destination is parsed first, and the prefix applies **only to the last, hierarchical part, one whole component at a
time**:

| Kind | Always compared exactly | Prefix applies to |
|---|---|---|
| HTTP | Scheme, host, port | Path segments (`/api/` matches `/api/x`, not `/apix`). The query string is never prefix-matched. |
| FILE | (nothing) | Path components of the canonical path (`Path.startsWith` semantics) |
| OBJECT_STORE | Scheme, bucket | Key segments |
| MESSAGE_BUS | Cluster | Not offered. Topic is exact. |
| EMAIL | (not offered) | A domain-wide rule is a *suffix* match, which is the pattern risk. List each address. |
| AI_MODEL, SEARCH_BACKEND | (not offered) | Exact only |

**Normalise first, then compare.** An exact match is only safe if both sides are in the same form. The match is
made on the normalised request, never the raw string:

- HTTP: reject user-info (`https://allowed.example@evil.example/`); fold case; strip a trailing dot; apply default
  ports; convert IDN to its ASCII form; decode and re-encode the path once; resolve `.` and `..` segments.
- FILE: expand the path variables, make the path absolute, resolve `..`, and resolve symbolic links on the nearest
  existing ancestor (§6.5.4.2).
- EMAIL: lower-case the domain; reject angle-bracket and multi-address forms.

If normalisation fails, the request is denied.

**Modes.** A global mode per kind: *report-only* (decisions recorded, nothing blocked, used for rollout), *enforce*
(the default once live). Never *allow-all*. A global **kill switch** denies every egress of a chosen kind at
once.

**Fail closed.** A sink that has no declared kind, or that throws, is denied. To stop a new sink being added and
forgotten:

- each `@XsltFunctionDef` and `@ConfigurableElement` that can reach outside the process **declares its
  `EgressKind`**, using an annotation;
- a unit test enumerates every such class and **fails the build** if one that can do I/O has no declared kind.
  The set in §6.5.3 would be the first population. This is the structural defence against the next `send-email`.

##### 6.5.4.1 Considered and deferred

| Dropped | Why | Revisit when |
|---|---|---|
| **Patterns** (wildcards, regular expressions) | A matcher is a place for a bypass. Exact or whole-component prefix is easy to reason about and test. | A site has so many near-identical destinations that listing them is unmanageable |
| **Expiry and review dates** | Stroom has expiry for API keys and stored credentials, but I found no access or permission rule that expires. An expiring rule is a new behaviour, and an expiry that silently breaks a feed is a hazard. A "last used" column (§6.5.7) shows stale rules instead. | There is a policy requirement to re-approve |
| **Limits** (bytes, calls per minute) | Not needed to start. A permitted generic webhook is still an exfiltration route; §6.5.11 says so. | Evidence of misuse, or a data-volume concern |
| **Client profiles** | No named client profiles exist for the current egress mechanisms. They would be a new concept. See §6.5.5 for the minimum that is kept. | Credentials or TLS settings need central management. The `stroom-credentials` module is the likely home. |
| **Scope** (per feed, pipeline or group) | Every rule applies to everything. This is simpler and the safe default: a rule is a statement about the destination. | An organisation needs a destination to be usable by one team only |
| **Direction** (in, out, both) | All egress permissions are dangerous. A GET can carry data in the URL, and data fetched from an untrusted host is itself a risk, so a direction label could give false reassurance. | Never, probably |
| **An *AI-authored allowed* flag** | It was a form of scope. The AI-specific control is instead a **save-time check**: AI-authored changes may not contain computed destinations or the standard functions in §6.5.6 (§6.5.10). | Open question 20 |
| **DNS pinning, and blocking private and metadata addresses** | With an exact destination list an unlisted host is already blocked, and a listed internal host may legitimately resolve to a private address. This assumes DNS for approved hosts is trusted. | The threat model includes a hostile resolver |

##### 6.5.4.2 How the file kinds build their paths (read from the code)

I read `FileAppender`, `RollingFileAppender`, `SimplePathCreator` and `ExtendedPathCreator`.

- The destination is a **pipeline element property**, `outputPaths`, a comma-separated list from which one is
  **picked at random** for each output. It is set by whoever edits the pipeline. It is **not** set from XSLT.
- The template is expanded by `PathCreator` before use. The variables are: `year`, `month`, `day`, `hour`,
  `minute`, `second`, `millis`, `ms`, `uuid`, `feed`, `pipeline`, `sourceId`, `streamId`, `partNo`, `streamNo`,
  `searchId`, `node`, `fileName`, `fileStem`, `fileExtension`, and **system properties**. `feed`, `pipeline` and
  `node` are passed through `PathSegmentUtil.cleanSegment`. I did not read that method, so I do not know whether it
  strips `..`. The design does not depend on it: **the check is on the fully expanded path**.
- `FileAppender.createOutput` uses `Paths.get(path)` directly, with no normalisation, and then **creates missing
  directories and a lock file**. `RollingFileAppender` does call `toAppPath`, which normalises (relative paths are
  taken relative to the application home), and builds a separate **rolled** file name.
- Because the file name contains time, a UUID or ids, an **exact file path is never usable**. Practically every
  FILE rule is a **Prefix on a directory**. That is containment, not a pattern, but it is the one security-sensitive
  comparison in the design, so it must be done on the canonical path.

Consequences for the guard:

1. Check **after** variable expansion and normalisation, and **before** `Files.createDirectories`, so a denied
   path never creates a directory.
2. Resolve symbolic links on the nearest existing ancestor, then compare path components. Otherwise a link inside
   an allowed directory can point out of it, and `/allowed/../etc` can pass.
3. For a rolling appender, check **both** the active file's directory and the rolled-file destination.
4. Check every entry in `outputPaths`, at save time as well as at run time, so a bad entry is found when it is
   written, not on a one-in-N random pick.
5. The same rule applies to the standard XSLT constructs `unparsed-text`, `doc`, `collection` and
   `xsl:result-document` if they are not denied outright (§6.5.6).

Not verified: `HDFSFileAppender`, `S3Appender` and the path handling of any other element. The same approach
applies but each needs reading.

#### 6.5.5 HTTP specifics

An exact destination list is the main defence. The guard still has to:

- **Normalise before matching** (§6.5.4), which closes the user-info, case, IDN, trailing-dot and default-port
  bypasses.
- **Allow `https` only**, unless a rule names `http` explicitly.
- **Check every redirect hop** against the same rules, or disable redirects. An approved host that redirects to
  an unapproved one must not be followed.
- **Not trust TLS settings in the XSLT.** `http-call` and `fetch-json` accept a JSON client configuration
  whose TLS settings include `keyStorePath`, `trustStorePath`, their passwords, `trustSelfSignedCertificates` and
  `verifyHostname`. There are no named profiles to replace it (§6.5.4.1), so the minimum is: **ignore those
  fields and report that they were supplied**, or reject the call. An approved destination must not be reachable
  with verification switched off, or with a keystore from the server's disk.
- **Strip `Authorization` and `Cookie` headers supplied by the XSLT**, unless a later design adds profiles.
- **Apply the same checks to `doc()`, `document()`, `unparsed-text()` and `collection()` when the target is an
  `http(s)` URL**, since the probe showed they open connections.

Other kinds, briefly. Email: normalise addresses and refuse header injection (a subject containing newlines;
`SendEmail` documents that the subject must be a single line, so check that it is enforced). Files: §6.5.4.2.
AI: match the model document, and apply the data classification gate (D5) before any data is sent.

#### 6.5.6 Standard XSLT constructs need Saxon-level hooks

The probe showed `doc`, `document`, `unparsed-text`, `collection` and `xsl:result-document` bypass Stroom's
functions, so the guard has to be installed where Saxon performs the I/O. This section reports what was checked
about those hooks, and where Saxon is used in the repository.

##### 6.5.6.1 The hooks exist, and each covers different routes (verified)

All four hooks are methods on `net.sf.saxon.Configuration` in 9.9.1-8 (confirmed with `javap` on the jar). A second
probe, `Probe3` (Appendix), mirrors Stroom's path: `new Processor(false)`, then `XsltCompiler`, then
`TemplatesImpl`, `TransformerImpl` and `newTransformerHandler()`, as in `XsltPoolImpl` and `XsltFilter`. It ran each
route with no hooks, with each hook alone, and with all four. Each hook was set on the **configuration**, and a
refusing compile-time resolver stayed in place on the compiler as `XsltPoolImpl` does.

| Hook (on `Configuration`) | Routes it closed | Routes it did **not** close |
|---|---|---|
| `setURIResolver` | `doc()`, `document()`, `doc-available()`, `xsl:source-document`, and `doc()` of an `http:` URL | `unparsed-text*`, `json-doc`, `collection`, `xsl:result-document` |
| `setUnparsedTextURIResolver` | `unparsed-text()`, `unparsed-text-lines()`, `unparsed-text-available()`, `json-doc()` | everything else |
| `setCollectionFinder` | `collection()` and `uri-collection()` given a URI | everything else. `collection()` with no argument already fails: "No default collection has been defined". |
| `setOutputURIResolver` | `xsl:result-document` | everything else. A relative `href` already fails with SXRD0002 because the output has no system id. |

Findings that matter:

1. **No hook covers another's routes.** With only one installed, the other routes still read or wrote. All four are
   needed. This is the main reason the hooks must be installed in one place (§6.5.6.3).
2. **With all four installed, every route in the probe was blocked**, and each hook was handed the full URI, so a
   guard has what it needs to decide.
3. **`xsl:import` and `xsl:include` are a separate mechanism.** They are governed by the resolver set on the
   `XsltCompiler`, which `XsltPoolImpl` already sets (`CustomURIResolver`, which resolves only XSLT documents by name
   or UUID). Confirmed: they were blocked in every run, including with none of the four hooks.
4. **Recovery policy defeats a refusing `doc()` resolver.** `document()` (the XSLT 1.0 form) recovers from a refused
   resolver with a *warning* and an **empty sequence**, so the stylesheet ran to completion. `doc()` failed. Setting
   `Configuration.setRecoveryPolicy(DO_NOT_RECOVER)` makes `document()` fail too. Without it, a "refusal" can
   look like success. (This setting has wider effect than the hooks. It changes how Saxon treats other recoverable
   errors, so it should be applied to untrusted content and tried on existing translations before it is used for
   them.)
5. **`unparsed-text-available()` and `doc-available()` report `false`** when refused, rather than failing. That is
   acceptable, but the hook still sees the attempt, so it is auditable.
6. **Stroom's own extension functions were unaffected** by `FeatureKeys.ALLOW_EXTERNAL_FUNCTIONS = false` (a probe
   function registered through the s9api `registerExtensionFunction` still ran). Reflexive Java calls
   (`java:` namespace) were already unavailable under Saxon-HE. Stroom registers its functions with
   `Configuration.registerExtensionFunction` (`StroomXsltFunctionLibrary.init`), a different entry point on the
   same registry, which I did not run.
7. **Unverified:** the hooks were exercised in a standalone JVM. They have not been run inside a Stroom node. Open
   question 16 still stands. The version-specific method names are now confirmed, which closes the first half of
   open question 17.

##### 6.5.6.2 Where Saxon is used today, and which of those places have hooks

Searched `src/main` across the repository (excluding `.claude/worktrees`). "Hooks" means the four above.

| Where | What it evaluates | Source of the code | Hooks today |
|---|---|---|---|
| `XsltPoolImpl.createValue` (`stroom-pipeline`) | **The XSLT of every translation**, run by `XsltFilter` | A person, or a model | **None.** Only the compile-time resolver. This is the main gap. |
| `XPath.java` (`stroom-query-language`), the `xpath()` query function | An XPath expression typed into a query or dashboard | **Any user who can run a query** | **None.** Probe `Probe5` confirmed that `unparsed-text()`, `doc()` and `collection()` in such an expression read local files. |
| `XPathExtractionOutputFilter` (`stroom-search-extraction`) | XPath expressions in a search-extraction pipeline | A person who can edit the pipeline | **None** |
| `XsltStep` (`stroom-shapeshifter-ai`) | A candidate stylesheet in the learning loop | A model | **All four**, plus `DO_NOT_RECOVER` and `ALLOW_EXTERNAL_FUNCTIONS=false`, set privately in `XsltStep.confine`, with a refusing resolver on the factory and on each transformer |
| `OutputRecords` (`stroom-shapeshifter-ai`) | XPath used by the scorers | Configuration, possibly model-supplied | **None** |
| `RecordJoin`, `RecordSplit`, `JsonStep` (`stroom-shapeshifter-ai`) | Identity transformation to serialise | Fixed code | Not needed: no stylesheet or expression is evaluated |
| `TransformerFactoryFactory` (`stroom-util`), used by `XMLUtil`, `XMLRecordEmitter`, `Config` | Identity transformation to serialise | Fixed code | Not needed. It sets secure processing, which is a different control. |
| `TinyTreeBufferFilter`, `ReferenceDataServiceImpl`, `RefDataValueConverter`, `EventListUtils`, `StateValueProxy`, `SqlStoreValueProxy`, `MyXmlEmitter` | Builds or reads Saxon trees. No stylesheet or XPath is evaluated. | Fixed code | Not needed |

So you were right that hooks are installed in some cases, but **in only one: the Shapeshifter stand-in**
(`XsltStep`). It is the one place that is not production. **No production path has them.** Production translations,
the `xpath()` query function and the extraction filter are all open to the routes in §6.5.6.1. The `xpath()`
function is the widest exposure, because the expression comes from whoever can run a query.

Three smaller observations:

- Only `XsltStep` sets `DO_NOT_RECOVER`, which §6.5.6.1 item 4 shows is needed for `document()` to fail.
- The hooks in `XsltStep` refuse unconditionally. They are not connected to any registry, so they cannot be
  opened by an administrator.
- `XPath.java` already disables DOCTYPE and external entities in its own XML reader (a comment says so). That
  hardens how *data* is parsed. It does not touch what an *expression* can call.

##### 6.5.6.3 Applying them consistently

Four copies of the same four lines would drift. Proposal, none of it implemented (at the project owner's
direction, no source was changed):

1. **One class** in `stroom-util`, for example `SaxonConfinement.confine(Configuration, mode)`. It installs all four
   hooks plus `ALLOW_EXTERNAL_FUNCTIONS=false`, and `DO_NOT_RECOVER` in deny mode. `stroom-util` already depends on
   Saxon. Each hook delegates to the guard of §6.5.4, so one implementation serves every site.
2. **Every creation site calls it.** The creation sites to cover are the five rows above with *None* or private
   hooks: `XsltPoolImpl`, `XPath.java`, `XPathExtractionOutputFilter`, `XsltStep` (replacing its private copy) and
   `OutputRecords`.
3. **A build-failing check that it stays consistent**, the same idea as the sink enumeration in §6.5.4: a test scans
   `src/main` for `new Processor(`, `new Configuration(`, `Configuration.newConfiguration(`,
   `new TransformerFactoryImpl(` and `TransformerFactory.newInstance(`. Each hit must be on a short allow-list
   with a one-line reason (identity transformation, tree building), or it fails. A new Saxon site then cannot be
   added without a decision. The rows marked *Not needed* above are the initial allow-list.
4. **A shared test**, run against every confined configuration, that uses the routes in §6.5.6.1 and expects each to
   be refused, so one test proves all four hooks stay installed.
5. **Two modes**, as for the rest of the policy (§6.5.4): *report-only* for content that exists today, which logs
   each attempt and lets Saxon proceed using the previous resolver, and *deny* for new or untrusted content.
   Suggested start: `XsltPoolImpl` in report-only, because existing translations might use these functions
   (nothing here says whether any do, and the report-only log is how to find out); the `xpath()` function,
   the extraction filter and the Shapeshifter sites in deny, because I know of no legitimate reason for an
   extraction expression to read a file or a URL. That last point is a judgement for the project owner, not a
   finding.
6. **Delegation detail:** in report-only mode each hook should call the resolver that was installed before it
   (captured with the matching `get...` method), so that Saxon's behaviour is unchanged. The output resolver needs
   a `newInstance()` that wraps the delegate's.

Recommended default for translations once the guard exists: **deny `unparsed-text`, `collection` and
`xsl:result-document` outright**, as there is no known legitimate use in event translation, and route `doc`,
`document` and the HTTP functions through the guard. An administrator can open a rule if a site needs one. If a
hook were ever missing, the fallback is a static ban at save time, which sees a function call even when its
argument is computed.

#### 6.5.7 The admin UI

A plan for building this screen and the engine behind it, with requirements, assumptions, risks, modules, sketches and
worked examples, is in `egress-ui-plan.md`.

One UI, one permission. It is a new screen, so before any GWT work the project's `stroom-gwt/UI-GUIDE.md` has to
be read and followed (it is named in `AGENTS.md`, and I have not read it): a component and controller, Stroom's
own widgets, BEM styles, and workbench stories including read-only and dialog OK/Cancel. This note specifies the
function, not the layout.

| Screen | Purpose |
|---|---|
| **Destinations** | Every rule: kind, destination, match (exact or prefix), comment, enabled, and **last used**, so an unused rule is visible and a rule that is relied on is not removed by accident. This replaces expiry (§6.5.4.1). |
| **Add or edit a rule** | The fields in §6.5.4, with a **"would this be allowed?" test box**: enter a kind and a destination, and see the normalised form, which rule matches, or why none does |
| **Blocked and reported** | Every denied or report-only attempt: destination, sink, pipeline, feed, time. One click turns an entry into a **request** for a rule |
| **Requests** | A pending rule proposal with its justification. **The approver cannot be the requester.** |
| **Audit** | Every decision, every rule change, and every use, filterable by pipeline and feed |
| **Global** | Mode per kind (report-only or enforce) and the kill switch |
| **Inventory import** | Runs the static parser (§5) over existing XSLT, and lists every destination it can read (§6.5.9) |

Permission and protection:

- A new application permission, for example **Manage Egress Policy**, held by a small group.
- The permission and the policy store are **protected from AI agents**. An agent acting with a user's authority
  must not be able to edit the policy, and the MCP server's tool list must have no tool that can. The options
  paper already notes `OpenAIModelDoc` as a protected object, and the policy belongs in the same class.
- Every change is audited and recorded with provenance (D6), including who requested it and who approved it.

#### 6.5.8 Tests this needs (nothing here has been run inside Stroom)

| Test | What it proves |
|---|---|
| For each construct in the probe, a real-node test that the guard denies it with no rule, and permits it with one | The §6.5.2 finding is closed, and the hooks in §6.5.6 work in 9.9.1-8 |
| The build-failing enumeration of sinks (§6.5.4) | No new sink is added without a declared kind |
| URL bypass cases: user-info, mixed case, IDN, trailing dot, default port, encoded characters, `..` segments, a look-alike host (`siem.example.org.evil.example`), redirect to a disallowed host | The HTTP guard and the prefix rule (§6.5.4, §6.5.5) |
| Client configuration in the XSLT that sets `verifyHostname` false or names a keystore | It is rejected or ignored, and reported |
| Email header injection in the subject, and a look-alike domain | The email guard |
| Path traversal, symbolic links, relative paths, `/stroom/output-old` against a `/stroom/output` prefix, a path variable that expands outside the prefix, and the check happening before directories are created | The file guard (§6.5.4.2) |
| A computed destination in a stylesheet from an AI author | Rejected at save (§6.5.10) and denied at run time |
| A hostile record that tries to elicit each of the above | The hostile corpus the assessment recommends (§7.4) |
| Fail-closed: the guard throws, the registry is unavailable | Everything denied, nothing silently permitted |

#### 6.5.9 Rolling it out without breaking existing translations

Default-deny breaks any existing translation that already calls out. The XSLT dependency parser (§5) gives the
inventory:

1. Run the parser over the whole estate. Every resolved `HTTP` finding is a candidate rule. Computed destinations
   (`DATA_DRIVEN`) cannot be listed and need a person to look.
2. Extend the parser's kinds (§5.4 update) to include email, AI, and the standard functions in §6.5.3. Its current
   list is literal and too narrow.
3. Seed the registry from the inventory with an owner assigned to each rule.
4. Run in **report-only** for a trial period. The *Blocked and reported* screen shows what would have been
   denied. Add rules for what is legitimate.
5. Switch to **enforce**, one kind at a time, starting with the kinds that have the fewest existing uses.
6. Keep a break-glass procedure, using the kill switch and an audited temporary rule that is removed by hand once the incident is over (rules do not expire, §6.5.4.1).

The parser does not see runtime-computed destinations and does not see sinks in pipeline element properties.
Report-only mode is what catches those.

#### 6.5.10 What the policy gives the AI translations project

| Need | How the egress policy helps |
|---|---|
| D4 (constrain what the output can do), robust form | Enforced at run time in Stroom, not only at save |
| T4, T5 (code execution and egress via appenders) | Every sink, including the pipeline elements, goes through the guard |
| Exfiltration after prompt injection (T2) | An injected instruction to call out is denied, and recorded |
| Stricter for AI-authored content | There is no per-rule flag (§6.5.4.1). Instead the **save check** for AI-authored XSLT **rejects computed destinations outright** and rejects the standard functions in §6.5.6, so nothing relies on a runtime decision alone. |
| Review | The review panel (§5.5) lists each sink in a proposed change with its status: *permitted by rule X*, *not permitted*, or *computed at run time, will be denied unless it matches a rule* |
| Provenance (D6) | Decisions and rule changes are audited |
| Platform defence in depth | The policy is application-level. A **network-level** egress restriction (as the repository's own devcontainer firewall scripts do for development) is a second layer, and an operator should apply it. |

#### 6.5.11 What the egress policy does not cover

An allowlist stops data leaving through a *route nobody approved*. It does not address:

- **Wrong data to an approved place.** A permitted destination can still receive records a translation should not
  send. Limits on size and rate were deferred (§6.5.4.1), so nothing reduces it yet.
- **Silent record loss and wrong mapping.** These need known-answer tests, the catalogue drift check (§6.4) and
  review. The swapped-address example from §2.3 passes the egress policy.
- **Data to a model provider (D5).** The policy can say *which* model; it does not decide *which data* may go.
- **Resource exhaustion.** Candidate runs still need time and memory limits.
- **Cross-feed reads** by the data-read functions (§6.5.3).
- **Prompt injection reaching a model that can act (D1 to D3).**

### 6.6 Provider-neutral by design, including customer-hosted models

At least one customer will run its model on premises, and the customer and models are otherwise unknown. So:

| Principle | What it means for the modules |
|---|---|
| **Assume it is weaker than a frontier model** | For 1.0 nothing will be known about the customer's model, so design for one that is less capable: it may follow long instructions poorly, make more mistakes in XSLT, and have a modest context window. |
| **Assume nothing else about the model** | A model is always an `OpenAIModelDoc` (base URL, API key name, model id, context window). No module may name a vendor or rely on a feature only some models have. |
| **Plan for a small context window** | `ai-context` (§6) already caps and types what goes to the model. The cap has to come from the model document's `maxContextWindowTokens`, with a floor that still works for a modest local model. Type profiles (§6.3) help: "field 3: 100% IPv4" is much smaller than samples. |
| **Do not rely on a model's judgement for safety** | Weaker models follow instructions less reliably. Every control that matters (the egress policy, the save-time check, the scorers, human review) runs outside the model. |
| **Data policy per model, not per product** | The data-to-model gate (D5) decides which classes of data a given model document may see. A model on the customer's network can be allowed richer samples than a hosted one, and that is a decision the customer's administrator makes, recorded against the model document. |
| **The egress policy already fits** | An `AI_MODEL` rule is the model document's identifier (§6.5.4). A model on an internal host needs no special case, which is one reason §6.5.4.1 drops private-address blocking: a listed internal host is allowed to resolve to a private address. |
| **Structured output is a requirement to test, not assume** | Scoring and review depend on the model returning usable XSLT or Data Splitter configuration. The customer's model cannot be tested for 1.0 (§6.7), so the evaluation (Option 5) uses a range of Claude models as a proxy and measures how often each returns it. |
| **Weaker output means more review, not less** | If an on-premises model produces poorer candidates, the response is more review and a higher bar to reach it, never lower scoring thresholds. |

What is still unknown, and needed before the on-premises case can be designed in detail: the model, its context
window, whether it supports tool calls or only plain completion, who operates it, and whether the customer's
network policy lets Stroom reach it from the node that runs the pipeline (§6.5.4 covers the permission, not the
connectivity).

### 6.7 Scope for version 1.0

Decided by the project owner. This section records what follows from it; the consequences are mine and are for review.

**What counts as success.** A translation is acceptable if it **helps the user achieve their end**. In review mode,
if the model helps the person in some way (a first draft, a mapping they correct, an explanation of why a record
failed) that is enough. Version 1.0 is partly research: it has to be useful and honest about what it does not know,
not complete.

| Consequence | Detail |
|---|---|
| **In review mode, scoring informs the reviewer.** | The scorers (§2, §6.1) show the person what the candidate does and does not do, and make failures visible, in particular the swapped-address example of §2.3. |
| **In automatic mode, scoring is the gate, so it has to hold.** | **Automatic mode is in scope for 1.0** (project owner), as a customer trial, not a polished deliverable. With no person between the model and the live pipeline, the promotion gate, the per-record bar and the corpus are all that stand in the way, and the assessment's findings apply in full: the blank-scorer state passes everything, a schema-valid swapped-address mapping passes every scorer (§2.3), there are no run-time limits on candidates and no prompt-injection handling. |
| **Golden events: optional in review mode, required for automatic mode.** | They remain the strongest correctness evidence. Without one, a feed cannot be promoted automatically (§2.3, §3.1). Open question 8 (asking a feed owner for one) therefore still matters, for the feeds that opt in. |
| **No formal sign-off or fixed acceptance suite for 1.0.** | Open question 3 is answered. The evaluation in Option 5 supplies the evidence of how well models do, and gives no pass mark. |
| **Honesty is the requirement.** | The review panel must say what was and was not checked, per the "never optimistic" rule (§3.3), and say plainly when the evidence is only Observed. A helpful but wrong draft is acceptable. One presented as verified is not. |
| **The targets are Data Splitter and XSLT only.** | Candidates are a Data Splitter configuration (parser) and an XSLT. The assessment's learning loop already has steps for both (`DSParser` and `XSLTFilter`, and a stand-in `XsltStep`). I have not checked how complete the Data Splitter step is. |
| **The Shapeshifter Engine is out of scope, and it is not in this branch (verified).** | The "Shapeshifter Engine" of `full-design.md` is a separate, fast, non-XSLT transformation tool with its own node types. I found no module for it. The only Shapeshifter modules are `stroom-shapeshifter-ai` (146 main classes) and `stroom-shapeshifter-ai-impl-db` (42). An earlier version of this note listed the fragment runner and other classes as Engine parts. **That was wrong** (see the next row). |
| **What the branch does contain is built on Stroom's own pipeline elements, and is in scope.** | `stroom-shapeshifter-ai` runs candidates through **real Stroom pipelines**: `DataSplitterCompiler`, `DataSplitterRunner` and `DataSplitterStep` (Data Splitter), `JsonStep` (Stroom's `JSONParser`), `XmlFragmentStep` (`XMLFragmentParser`), `RecordSplit` (Stroom's `SplitFilter`), `XsltStep` (an XSLT stand-in), `PipelineFragmentRunner` and `PipelineStepRunner` (the real elements, pools and function library), and the `ShapeshifterAiParser` pipeline element. None of it needs the Engine. All of it is reusable and relevant to 1.0, and the learning loop, scorers, scorecard and review sit on top. |
| **Questions this reopens.** | Open question 12 (can the fragment runner record real provenance?) is open again, not deferred. `ShapeshifterAiParser` stays in the egress inventory (§6.5.3), since it sends data to a model. Only the "custom format versus XSLT" comparison in Option 5 is dropped, because that format belongs to the Engine. |
| **The 1.0 floor for automatic mode.** | Confirmed by the project owner. Before automatic mode is enabled on any customer feed: the egress policy with the Saxon hooks (§6.5.4, §6.5.6), run-time limits on candidate runs, scorers that cannot pass in a blank state, and a spend limit. The other gates in Option 3 (the full hostile corpus, the save-time check on computed destinations beyond the egress policy) are wanted but are not the floor. |
| **Automatic mode is opt-in, per feed, and off by default.** | Enabled by a named person, per feed, with the decision recorded (§8 row 1). Never a module default, and never available for a feed that has no golden event. Review mode remains the default for everything else. |
| **Automatic mode needs the safety gates in 1.0.** | Option 3 moves from "can run in parallel" to **a precondition for enabling automatic mode on any customer feed**: the egress policy (§6.5) and Saxon hooks (§6.5.6), the save-time check for computed destinations, time and memory limits on candidate runs, a working spend breaker, scorers that cannot pass in a blank state, and a hostile-input corpus. With an unknown, weaker model reading untrusted log data (§6.6), these are what make a trial responsible. They do not need to be polished. They need to be there. |
| **The trial must produce evidence the customer can report.** | The point of the trial is a report on whether it works. So automatic mode records, per feed: each promotion and its scorecard, each rollback or failure, each time a person overrides or reverts an automatic change, records dropped or flagged, and model spend. The existing provenance and audit records (D6, §6.5.7) are the basis. A one-page summary a customer can send back is enough. |
| **A safe way back is required.** | An automatic promotion must be reversible by one action (restore the previous version, as the corpus and versioning already allow), and a kill switch must stop automatic mode for a feed or everywhere. |
| **Experimental labelling.** | The product says plainly that automatic mode is an experiment: what it checks, what it does not, and that review mode is the safe choice. |
| **The model-dependence risk is accepted openly.** | The weaker the model, the less useful the draft. 1.0 does not try to make a weak model strong. It makes sure its output is checked, reviewed (or, in the automatic trial, gated) and honestly labelled (§6.6). |

## 7. Options going forward

### Option 1: Extract `ai-scoring-core` only
Pull the existing scorers into a generic module, then add tiers, confidence and the explain tree.
- **Effort:** small to medium (judgement; no estimate exists).
- **Pros:** directly serves the scoring idea; no new consumer needed; fixes the blank-state hazard.
- **Cons:** on its own, it does not address safety or triage.

### Option 2: Triage first, on the shared seams (recommended core)
Build briefs 03 to 05 (digest, fingerprints, state) as the first non-LLM consumer of `error-signature`,
`ai-review` and `ai-governor`. Then add AI fix suggestion (Option C) using the same scoring and sandbox.
- **Effort:** the briefs give 3 to 5, 10 to 15 and 8 to 12 days for the three steps, plus S–M for the AI part
  (figures from the options paper; not re-estimated).
- **Pros:** a second real consumer proves the split. Delivers the template and typed-argument work that makes safe
  model input possible for every option.
- **Cons:** needs Stroom core changes (error raising, new storage). `ErrorType` is set in only two places in the
  tree today, so most errors are `UNKNOWN` and treated as input.

### Option 3: Safety gates before further extraction
Close the assessment's gates: A147 and A19 as one static check, enforced limits on candidate runs, no learning
without scorers, a working spend breaker, a hostile corpus. The `sw-xslt-dependencies` parser is the starting point
for the static check (§5.5). **A147 is now better described as the egress policy of §6.5**: one registry, one guard,
one admin UI, enforced at run time at every sink. The parser supplies the inventory and the save-time check, not the
control. The probe in §6.5.2 shows the gap is wider than the assessment describes: the standard XSLT functions
reach the file system and network, not just the `stroom:` functions.
- **Pros:** the most defensible; the assessment says it would not run on real data until these exist.
- **Cons:** least visible progress. Can run in parallel with 1 and 2.
- **Now a precondition for automatic mode.** Automatic mode is in scope for 1.0 as a customer trial (§6.7), so the
  gates are needed before it is switched on for any customer feed. They are not needed for review mode alone,
  where a person stands between the model and the live pipeline, although the egress policy is worth having
  regardless (§6.5.2, limit 3).

### Option 4: Full platform split now
Extract all nine modules up front.
- **Not advised.** One consumer exists. Shapes would be guesses, and it would couple delivery to Stroom core review.

### Option 5: Evaluation first
Run the §10 model comparison from the options paper: 10 to 20 existing translations, generate XSLT (and Data
Splitter configuration) with and without the MCP skill, score schema validity and field-by-field agreement.
**The self-hosted model is not available to us** (§6.6), so the comparison uses the **Claude models through the
online API: Haiku, Sonnet and Opus**, with Opus as the reference. The smaller models stand in for the less capable
on-premises model, and the result is a measure of how quickly quality falls as capability falls. Use `graphdb/04-event-logging-xslt.md` (a verified XSLT with byte-identical expected output) and the
`eval-driven-resource-development.md` exercise format as the first cases.
- **Pros:** cheap; measures the model-dependence (C1) the paper cannot otherwise estimate; informs whether
  Shapeshifter's custom format beats XSLT for the models you will face. (Dropped for 1.0: the custom format
  belongs to the Engine, which is out of scope, §6.7. The comparison is XSLT and Data Splitter only.)
- **Cons:** needs access to real translations. **It cannot measure the on-premises model.** Haiku is not an
  open-weight model, so the result is a proxy, not a prediction. Treat Haiku's result as the "weak model" case, and
  say so in the report.
- **Design of the comparison.** Every case is run on each of the three models, with and without the MCP skill, and
  more than once, because model output varies. Record per run: compiles, schema valid, field agreement against the
  case's golden events (§2.4), number of repair iterations needed, tokens and cost, and time. Report the **gap to
  Opus** and where each smaller model first fails, not only an average. Two further results matter most for
  this project: **how often each model returns usable XSLT or Data Splitter at all** (§6.6), and **whether it fails
  safely**, meaning a wrong answer that the scorers and golden events catch, rather than one that passes.
- **Data.** The models are hosted, so use synthetic or already-public sample data, not customer data (D5).
- **What you need to set up to run it** is in §7.1.
- **Add one arm:** run each case with and without the §6.4 priors (Declared schema map only, then plus the
  catalogues of other feeds, simulated from existing translated output). That measures whether priors help,
  before any of the catalogue is built. It also measures the risk in §6.4.4 condition 2, by seeding one prior with
  a deliberately wrong mapping and seeing whether the model repeats it.

### 7.1 What to set up to run Option 5 (Claude API)

Nothing here has been done, and no account was touched. This is a checklist. The model names, context sizes and
prices come from the Claude API reference as cached on 2026-10-06 and should be checked before relying on them.

**1. Access**

| Step | Detail |
|---|---|
| Create an Anthropic Console account and an **organisation** | Use a work account. Check with whoever owns procurement: this spends money, and a company card or invoice terms may be needed. |
| Create a **workspace** just for this evaluation | A separate workspace keeps its spend and keys apart from anything else. |
| **Set a spend limit** on the workspace before the first run | A runaway loop in a harness is the main cost risk. Set a limit well above the estimate below, and stop there. |
| Create an **API key** for that workspace | Store it as an environment variable (`ANTHROPIC_API_KEY`) or in a secrets store. **Do not put it in the repository, in a Stroom document, or in a prompt.** A tool-less harness on a developer machine is enough. |
| Optional: the `ant` command-line tool | `ant auth login` keeps a profile the official SDKs read automatically, so no key needs to be exported. |
| Check **data retention** terms for your organisation | Some models are not available under zero data retention, and the data here must be synthetic or public in any case (D5). |

**2. Models**

| Role | Model ID | Reference input / output per million tokens |
|---|---|---|
| Reference | `claude-opus-5-5` | $4 / $20 |
| Middle | `claude-sonnet-5-5` | $2 / $10 |
| Weakest | `claude-haiku-5-5` | $0.10 / $0.50 (for prompts up to 100K tokens) |

Use these exact strings. Opus 5.5 and Sonnet 5.5 do not allow turning thinking off, and several sampling settings
(temperature and similar) are rejected, so the harness must leave them at their defaults and choose a reasoning
**effort** level instead. Record the effort level per run, since it changes results and cost. Opus 5.5's default is
`medium`.

**3. The harness (to build, not yet written)**

| Piece | Detail |
|---|---|
| A small program that takes a **case**, a **model** and an **arm** (with or without the MCP skill, with or without priors) and returns the generated XSLT or Data Splitter configuration | Java, since the Shapeshifter module is Java: the official `com.anthropic:anthropic-java` SDK. It does not need Stroom running. |
| It runs the existing scorers over the result | The Tier 1 in-memory runners and the scorers need no node (§6.7). |
| It records, per run | Compile, schema validity, agreement with the golden events (§2.4), repair iterations, tokens (input, output, cached) and cost, time, effort level, model ID. |
| It **repeats** each run | Output varies. Five repeats per cell is a sensible start. |
| It enforces its own **per-run and total token ceilings** | A second line of defence behind the workspace spend limit. |

It deliberately does not go through Stroom's `OpenAIModelDoc`. Stroom's model documents use an OpenAI-style
connection, and I did not verify that they can be pointed at Claude. For this evaluation, calling the Claude API
directly is simpler and measures the model, not the connection. How a real deployment connects to a Claude model
is a separate question that this evaluation does not answer.

**4. The cases and answers (this is the larger piece of work)**

| Item | Detail |
|---|---|
| **10 to 20 existing translations** with their raw input samples | Synthetic or public data only. |
| **A golden event set for each** (§2.4) | This is what makes agreement measurable. Three to ten per case, including edge cases and at least one negative case. |
| The **event-logging schema** and the schema map | Both are already in the repository. |
| Starting cases | `graphdb/04-event-logging-xslt.md` (a verified XSLT with byte-identical expected output) and the `eval-driven-resource-development.md` exercise format. |

**5. A rough cost, to be replaced by a measurement**

Assume 20 cases, three models, two arms and five repeats (600 runs), and a generous 100,000 input and 20,000 output
tokens per run including repair iterations:

| Model | Runs | Approximate cost |
|---|---|---|
| Opus 5.5 | 200 | about $160 |
| Sonnet 5.5 | 200 | about $80 |
| Haiku 5.5 | 200 | about $4 |
| **Total** | 600 | **about $250** |

These token counts are my assumption, not a measurement. Run a **pilot** of one case on each model first, read the
real token usage, and recompute. Prompt caching of the shared instructions and schema would reduce the Opus and
Sonnet figures. Add a margin, and set the spend limit above it.

**Rough pilot first, without the API.** `pilot/README.md` has a drafted case (a synthetic firewall log to
event-logging v4.1.0), executable golden events, and a scoring tool, for a rough Haiku, Sonnet and Opus comparison
using Claude Code on a Team seat. It also tests the "system asks" idea (§2.4) with eight enumerated decision
points. It can shake down the case and the checks. It cannot give token, cost or repeatability figures.

**Pilot results (4 November 2026, `pilot/results/results-2026-11-04.md`).** Six runs (Haiku, Sonnet and Opus, each
one-shot and ask-first) on one firewall case. All six outputs were schema-valid, and 5 of 6 passed all 22 golden
checks, so the schema check alone could not tell a good run from a poor one. The single failure was Opus one-shot
keeping a record with an invalid address, a defensible but unintended choice that all three ask-first runs avoided
by asking. No model asked about the swap, because the fields were named `src` and `dst`, so the swap trap was not
actually tested. No model reported dropped records unless told to. One run per cell, an instruction-only isolation
and a case that was easy for every model: indicative only, and no evidence about the on-premises model.

**Pilot case 2 (`pilot/results/results-2026-11-04-case-02.md`):** twelve runs on positional fields, two per cell. All
twelve outputs were schema-valid. One-shot, 1 run in 6 passed every golden check; the other five failed on decisions the
model was never asked about (mapping `RESET`, keeping an invalid-address record, reporting drops). **All six ask-first
runs passed everything**, though I supplied the answers from the key, so that shows the right questions were asked, not
that the model was better. **No model, in either arm, fell for the port-based swap trap**, even one-shot: they all took
the column order as source then destination, so that trap did not discriminate. Only the two Opus runs asked for the
required `System/Environment`. The strongest model did no better one-shot than Haiku.

**Pilot case 3 (`pilot/results/results-2026-11-04-case-03.md`):** the same shape with **the first address as the
destination**. **Five of six runs swapped source and destination on every event, and all six outputs were
schema-valid.** The one run that was right (Sonnet, ask-first) was the only one that **asked** whether the first
address is the source. Two of three ask-first runs did not ask, and treated a misleading port hint as settling it. So
the swap risk (§6.3) is real, a golden event is the defence that works, and **the system should ask about direction
itself, deterministically, rather than leave it to the model** (§2.4). Every run did state its column assumption, so
the information was in the report, but only a reviewer reading it would have caught the error.

**6. Order of work**

1. Create the account, workspace, spend limit and key.
2. Pick the first two or three cases and write their golden events.
3. Build the harness, then run the pilot on all three models.
4. Review the pilot's token use and results, then fix the design of the comparison before the full run.
5. Run the full comparison and write up the gap to Opus, per §7 Option 5.

### Suggested sequence

1. Start **5** immediately, as it informs the rest.
2. Run **3** and **1** in parallel. The parser prototype and the scoring core are independent. **3 must be
   finished before automatic mode is switched on for any customer feed** (§6.7). Review mode does not wait for it.
3. Then **2**, using the extracted pieces.
4. Enable automatic mode as an opt-in trial, for feeds that have a golden event, with the reporting in §6.7.

**Human review is the default throughout** (decided by the project owner). Every option above assumes, unless a feed has opted in to automatic mode, that a person
approves each AI-generated or AI-changed translation, using the review panel (§5.5) and the evidence from the
scoring core. Unattended operation (Option B in the options paper) is not a contractual requirement, but **is in scope for 1.0 as
an opt-in customer trial** (§6.7), with the conditions in §8 row 1. This changes the weight of the work: the **review experience**
(what the person sees, how much they have to read, and how reliably it shows what changed and what it can reach)
becomes a deliverable in its own right, rather than a stop on the way to automation. A draft specification of the panel, built from the pilot findings, is in
`review-panel-design.md`.

Decisions still needed from you:

- Does versioning stay in the Shapeshifter central database, or move to GitRepo with PR promotion (see §8)?
- Which identity should the static check resolve references as (see §5.4, item 5)?

---

## 8. Tensions between the documents

| # | Tension | Why it matters | Suggested resolution |
|---|---|---|---|
| 1 | `full-design.md` **auto-applies** an AI transform and retries. The security analysis stops at tier 1 (a person applies). | Auto-apply fits only tier 2 or above. The project owner has confirmed that unattended operation is not a contractual requirement, that review is needed in many cases for untrusted log data, and that automatic mode **is** in scope for 1.0 as a customer trial. | **Review is the default.** Automatic mode is an opt-in per feed, needs Verified evidence (a golden event) and a recorded decision by a named person, is reversible and killable, and records the evidence the customer will report back. It is never the default of any module. |
| 2 | `full-design.md` feeds the AI sample data and current errors. The security analysis forbids raw input-derived text on the write plane. | The assessment found no prompt-injection handling in the code (it fences the sample in Markdown, and feed names are interpolated unescaped). | Route through `ai-context`: templates not messages, typed arguments, 64-character cap. |
| 3 | **Two versioning systems.** Shapeshifter keeps immutable versions in a central database. The UX briefs and security analysis use GitRepo, commit trailers and PR promotion. | Two provenance stories, and approved transforms outside normal content review. | Decide one. Export approved transforms into Stroom content either way. |
| 4 | Brief 03 treats the "our config or their data" split as free via `ErrorType`. The security analysis says it is set in two places. | Triage ranking would be wrong until it is populated. | Make populating `ErrorType` an explicit prerequisite. |
| 5 | Brief 03 steps a real stream. The security analysis says agent stepping must be sandbox and synthetic only. | Fine for humans, not for an agent. | Shared modules must not expose the Stepper to an agent. |
| 6 | The scoring profile is versioned per record in `full-design.md`. Brief 01 says attribute to versions, not people. | Compatible, but needs reconciling. | Record profile id and version with every score. |
| 7 | Appendix A of `stroom-ux-opportunities.md` says it was written "from recollection". The briefs claim source verification. | Reliability differs. | Prefer the briefs. |
| 8 | The query-feeds catalogue covers translated `Events` only. Shapeshifter must understand `Raw Events`. | The catalogue cannot profile what a translation reads. | Profile the input with `ai-field-types`, over one shared pattern library (§6.4.4). |
| 9 | Two designs each propose a record of "which logic produced this stream": phase 2's composite digest on `meta` (2D), and Shapeshifter's snapshot and `shapeshifter_output`. | Same job, two stores. | Align them. Decide whether the snapshot digest is stored in the 2D place if and when 2D exists. |
| 10 | Phase 2's `VerifiedClaim` is scoped to an exact stream set. Shapeshifter's promotion gate reasons about a held stream and the corpus. | "Verified" must not be read as "verified for all future data". | Adopt the stream-set scoping, and continue verification per stream once serving (§6.4.5). |
| 11 | The assessment (§6.2) says A147 is about `http-call`, `fetch-json` and `ask-ai`. A probe shows `doc`, `document`, `unparsed-text`, `collection` and `xsl:result-document` also reach files and the network, and the pipeline elements (appenders, Kafka, Solr, Elastic, S3) are further sinks. `send-email` is one more function. | A fix aimed at three functions would leave most sinks open. | One egress policy over every sink (§6.5). |

---

## 9. Other documents worth reusing

| Document | What to take | Where it plugs in |
|---|---|---|
| `regex-runtime-limits/stroom-regex-performance-1.md` | Saxon `REGEX_BACKTRACKING_LIMIT`, static lint rules, authoring-time scaling benchmark, three regex dialects | `ai-inspection` regex lint; `ai-sandbox` limits. Addresses the "no timeout on candidate runs" finding. |
| `folder-data-flow/decision-request.md` | Parse-on-save dependency recording; the point that anyone who can edit an XSLT can move data to an arbitrary endpoint | The case for the parser in §5 |
| `graphdb/04-event-logging-xslt.md` and `examples/` | A verified XSLT, expected output, and the namespace gotcha (schema file v4.1.0, target namespace `event-logging:3`) | Gold-standard few-shot and first evaluation case |
| `repair-malformed-xml-json/` | The "repair is a guess" taint marker propagated downstream | A model for marking AI-influenced output, and for input pre-processing |
| `agentic-stroom/eval-driven-resource-development.md` | Declarative exercises: setup, test data, checks | Regression suite for the generator and its prompts |
| `log-sending-connectors/` | The `/datafeed` contract for sample capture, and the warning that the `Feed` header is a security boundary | Feed onboarding; auto-created feeds must not take attacker-chosen names |
| `new-ui/` pipeline UI plans | A canvas, inspector and "open in Stroom" deep links | A possible home for diff-and-approve |
| `query-optimiser/08-explain-and-cost.md` | Advisory, non-executing "explain first" and honest confidence wording | Score presentation (§3.6) |
| `analyst-notebook/` | `OpenAIModelDoc` and LangChain4j already exist; code cells declined for security | Reuse the model config rather than adding another; supports the tier reasoning |
| `palantir_gap_analysis/` | Frames evaluation harnesses (AIP Evals) as a gap | Strategic justification for the evaluation work |

Not relevant, as far as could be judged: `floormap/`, `pathways/`, `stores/`,
`graphdb-linked-from-dashboard/` (a downstream consumer only).

---

## 10. Open questions

**Status at 4 November 2026.** Answered questions are kept in the list with their consequences.

| Status | Questions |
|---|---|
| Answered by the project owner | 1, 2, 3, 4, 8, 13 |
| Answered by reading the code or by a test | 12 (code, §6.3.8), 17 (first half, probe, §6.5.6.1) |
| Deferred | 10 |
| Open, and needs a decision from the project | 5, 6, 7, 14, 19, 20, 21, 22 |
| Open, and needs a measurement or a running node | 9, 11, 15, 16, 17 (second half), 18 |

Where a question has a *Pilot* note, it refers to `pilot/results/results-2026-11-04.md`.

1. *(Answered by the project owner.)* "No human intervention" is **not** a contractual requirement. There may be
   cases where it is the best option, but for untrusted log data human review is required in many. **The project
   defines what is delivered**, and a version 1.0 that is enough to be useful is fine. Consequence: review is the
   default (§1 point 6, §7, §8 row 1). Automatic mode is nonetheless in scope for 1.0, as an opt-in customer trial
   (§6.7).
2. *(Answered by the project owner.)* The customer and models are **not known** and **will not be known, at least
   for 1.0**. At least one customer will run its model **on premises** (on hardware the customer controls, so that
   log data does not leave their network). **Assume that model is less capable than current frontier models.**
   Consequence: the design is provider-neutral and assumes a weaker model (§6.6). Details of the model (size,
   context window, tool support) are no longer a question to resolve before 1.0. They become things the design
   must tolerate not knowing.
3. *(Answered by the project owner.)* An acceptable translation is one that **helps the user achieve their ends**.
   In review mode, a model that helps the person in some way is good enough. This is partly a research project, not a
   finished product. Consequence: in review mode scoring informs the reviewer, there is no fixed acceptance suite for 1.0, and honest
   labelling of what was checked is the requirement. **Automatic mode is in scope for 1.0 as a customer trial** and
   there scoring is the gate, so the safety gates and the evidence for the trial's report matter (§6.7).
4. *(Answered by the project owner, and checked in the code.)* The Shapeshifter Engine is a prototype and is **not**
   part of AI translations at this stage, and **it is not in this branch**. **All pipelines use the existing Data
   Splitter and XSLT.** What the branch does contain, `stroom-shapeshifter-ai`, runs real Stroom pipeline elements
   and is in scope (§6.7). An earlier version of this note wrongly put the fragment runner and related classes
   outside scope. That is corrected.
5. Should the static check live in `stroom-pipeline` beside the parser, or in a separate module that depends on it?
6. *(Largely superseded by §6.5.3.)* Every network-capable `stroom:` function, and the standard XSLT functions
   `document()`, `unparsed-text()`, `collection()` and `xsl:result-document`, are now listed as sinks under the egress
   policy. Still to decide: whether the parser's own function list and reference kinds are extended to match, so the
   inventory and the save-time check use one list.
7. Which identity does the static check resolve references as, and how is the difference from runtime resolution
   shown to a reviewer?
8. *(Answered by the project owner.)* The system should **ask when it needs golden events, preferably with
   suggestions or options**, because users cannot be assumed to know them in advance. Consequence: §2.4 now says
   when the system asks, how options are enumerated (deterministically where possible), how it chooses the record
   to ask about, and how to keep the question cheap. Still open: how many questions a person will tolerate. The review screen is
   specified in `review-panel-design.md` (a draft). *Pilot:* in case 01, every ask-first run asked about the invalid-address
   decision and got it right, and the only golden failure in the whole pilot was the one-shot run that did not ask
   (§2.4). No run asked about the source-or-destination swap, because that case named its fields. Case 3 then made the swap real, and five of six runs got it wrong (§2.4).
9. *(Revised after reading phase 2.)* Phase 2 weighs stacked evidence (schema, path name, value inspection,
   administrator) and gates defaults on 95% precision against the event-logging schema. That is a sound design but
   an unmeasured one, since nothing is built. Should this project run the precision measurement itself, on the
   vendored schema and real translated output, as part of the §10 evaluation?
10. *(Deferred.)* Can name-based role evidence (`src`, `dst`, `client`, `server`) be added next to the type
    patterns, and who owns the list of names and their synonyms? The project owner's view: worth investigating, but
    possibly too much complexity at this stage. For 1.0 the field names are already in what the model sees (as
    Observed evidence), and a golden-event question settles a genuine swap (§2.4). A maintained synonym list is
    not planned. Revisit if the golden-event questions turn out to be asked too often. *Pilot:* in case 01 the field names
    (`src`, `dst`) made the swap a non-question, which is the value of name evidence in one example. Case 02, with
    no names, shows what happens without it.
11. *(Revised.)* Phase 2 already shares the pattern library between catalogue inference and `Pattern`
    assertions. `ai-field-types` would be a third consumer. Which module owns the library, given that the
    accumulator lives in the not-yet-built `stroom-datashape` and has to be plain Java depending only on
    `stroom-util-shared`? A library that small could be extracted first and used by both.
12. *(Answered by reading the code, §6.3.8.)* `PipelineFragmentRunner` records **per-record** provenance (the parsed
    record against the output record), but **not per-field** provenance, and a headless run has no raw-input span.
    The type scorer therefore matches values within the parsed record and reports `unknown` where ambiguous.
    Still open: whether to build the sentinel-substitution method in §6.3.8, which would give exact field-level
    provenance for XSLT without engine changes. It is optional for 1.0.
13. *(Answered by the project owner.)* "Similar feed" means **same schema**. The catalogue itemises data
    normalised into a schema, so any catalogue whose feed matches the target schema can be used as a prior, with
    permission. The goal is consistency: all feeds with the same schema should carry the same meaning, cardinality
    and so on (§6.4.4 condition 4). Still open: permission per feed, and how deviations from the other feeds are
    shown (as a finding for a person, not an error).
14. Do catalogue statistics from feed A count as A's data for the data-to-model gate (D5) when used to help
    translate feed B? If so, which classification governs?
15. Should Use B (drift and regression against this feed's own catalogue, §6.4.2) be built first, since it needs no
    model and no similarity definition, and is the stronger of the two uses?
16. *(Egress, §6.5.)* Does the §6.5.2 finding reproduce inside a running Stroom node? The probe mirrors Stroom's
    code path but is not Stroom. A real-node test should come first, because the answer decides how urgent the
    runtime guard is for the existing estate, independent of AI.
17. *(Partly answered, §6.5.6.1.)* The four hooks exist in 9.9.1-8 and close every probed route when installed
    together. Still open: do they behave the same inside a running node, and should `DO_NOT_RECOVER` be applied to
    existing translations or only to untrusted content?
18. Do the data-read functions (`meta-stream-for-id`, the lookups) apply a permission check, and if so as whom:
    the author or the processing user? No check was found in the function classes. This is a data-exposure
    question rather than egress, but it combines with any egress.
19. *(Revised.)* The rule is a normalised string with *exact* or *prefix* matching, chosen per rule, and the
    prefix applies by whole component to the hierarchical part only (§6.5.4). Is *exact or prefix* enough for
    every kind? Email domains, Kafka topic families and S3 key families may want more. If so, is listing each one
    acceptable, given the aim of having no patterns?
20. Who may hold *Manage Egress Policy*, and is a second approver required for new destinations? The UI in
    §6.5.7 assumes the requester cannot approve their own request.
21. Should AI-authored translations be forbidden from computed destinations outright (§6.5.10)? It is the safest
    setting and removes any reliance on a runtime decision for AI output. With the *AI-authored allowed* flag
    dropped (§6.5.4.1) this save check is the only AI-specific control, so it matters more.
22. Are `send-email` and `ask-ai` always registered, or only when configured (§6.5.3)? That decides whether the
    sink inventory differs between sites.

## 11. References

- `docs/reference/design/ai_translations/full-design.md` and `ai-translations-requirements-options.md`
- `docs/ai_translations/shapeshifter-ai-assessment.md`
- `stroom-shapeshifter/stroom-shapeshifter-ai/design/` (documents 01 to 10) and `ISSUES.md`
- `docs/reference/design/query-feeds/` (notably `query-planning-brief-v2.md`, the phase plans and the simplification review)
- `docs/reference/design/query-optimiser/08-explain-and-cost.md`
- `docs/reference/design/claude-suggested-ux/` (briefs 01, 03, 04, 05, 10, 12, 13, 14)
- `docs/reference/design/mcp-server/stroom-llm-mcp-security.md`
- `docs/reference/design/folder-data-flow/decision-request.md`
- `docs/reference/design/regex-runtime-limits/stroom-regex-performance-1.md`
- `docs/reference/design/query-feeds/query-feeds-phase-1-plan.md` (pattern library, pattern counting, the schema as ground truth)
- `docs/reference/design/query-feeds/query-feeds-phase-2-plan.md` (semantic types, claims, assertions, pipeline version on meta)
- `docs/reference/design/query-optimiser/07-domain-types.md`
- `origin/sw-xslt-dependencies`, commit `727edbb69c` and its ancestors
- Stroom source read for §6.5: `stroom-pipeline/.../xsltfunctions/` (function annotations),
  `.../cache/XsltPoolImpl.java`, `.../filter/XsltFilter.java`, `.../xslt/CustomURIResolver.java`,
  `stroom-util/.../http/HttpTlsConfiguration.java`, `gradle/libs.versions.toml` (Saxon-HE 9.9.1-8)

## Appendix: the probe

Run outside Stroom, against the Saxon-HE 9.9.1-8 jar from the Gradle cache. It is a scratch file and is not part of
the repository. It creates `target.txt`, `t.xml` and `imp.xsl` beside itself first, and writes `out.txt`.

```java
import net.sf.saxon.s9api.*;
import net.sf.saxon.jaxp.*;
import javax.xml.transform.*;
import javax.xml.transform.sax.*;
import javax.xml.transform.stream.*;
import java.io.*;

public class Probe2 {
    public static void main(String[] a) throws Exception {
        final String dir = a[0];
        final String[][] cases = {
            {"unparsed-text", "<xsl:value-of select=\"unparsed-text('file://" + dir + "/target.txt')\"/>"},
            {"doc", "<xsl:value-of select=\"doc('file://" + dir + "/t.xml')/*\"/>"},
            {"document", "<xsl:value-of select=\"document('file://" + dir + "/t.xml')/*\"/>"},
            {"collection", "<xsl:value-of select=\"count(collection('file://" + dir + "/?select=*.xml'))\"/>"},
            {"result-document", "<xsl:result-document href=\"file://" + dir
                    + "/out.txt\" method=\"text\">written</xsl:result-document>ok"},
            {"import-external", null},
        };
        for (String[] c : cases) {
            // ... builds a stylesheet around each case, then:
            // Processor p = new Processor(false);                      // as XsltPoolImpl
            // XsltCompiler comp = p.newXsltCompiler();
            // comp.setURIResolver((href, base) -> { throw new TransformerException("refused: " + href); });
            // XsltExecutable ex = comp.compile(...);
            // TemplatesImpl templates = new TemplatesImpl(ex);          // as XsltFilter
            // TransformerImpl tr = (TransformerImpl) templates.newTransformer();
            // TransformerHandler h = tr.newTransformerHandler();
            // ... send an empty <a/> document through the handler and print the result or the error
        }
    }
}
```

The listing above is abridged to the part that matters (the configuration mirrored from Stroom). The full
program is in the session scratchpad at
`/tmp/claude-0/-workspaces-stroom/414587f2-52ab-486d-98b7-5c76eca94075/scratchpad/saxon/Probe2.java`. That
location is temporary. If the probe is to be kept, it should be turned into a real test (§6.5.8).

### Second probe: the Saxon hooks (§6.5.6.1)

`Probe3.java` is the same configuration as `Probe2`, with the four hooks set on `Processor.getUnderlyingConfiguration()`
before the compiler is created. Each hook recorded the URI it was given, then refused:

```java
final Configuration c = new Processor(false).getUnderlyingConfiguration();
c.setURIResolver((href, base) -> { throw new TransformerException("DENIED uri " + href); });
c.setUnparsedTextURIResolver((uri, enc, cfg) -> { throw new XPathException("DENIED unparsed " + uri); });
c.setCollectionFinder((ctx, uri) -> { throw new XPathException("DENIED collection " + uri); });
c.setOutputURIResolver(/* an OutputURIResolver whose resolve() throws */);
// then, as in Probe2: XsltCompiler with a refusing compile-time resolver, TemplatesImpl, TransformerImpl,
// newTransformerHandler(), and an empty <a/> document through the handler.
```

It ran sixteen routes under six settings (no hooks, each hook alone, all four). `Probe4` checked recovery
(`DO_NOT_RECOVER`), `ALLOW_EXTERNAL_FUNCTIONS=false` with a registered extension function, and the `java:`
namespace. `Probe5` evaluated `unparsed-text()`, `doc()` and `collection()` as plain XPath on a
`new Processor(false)`, the setup used by `xpath()` and the scorers. All are in the same scratchpad folder and are
temporary. If kept, they should become the shared test in §6.5.6.3 item 4.
