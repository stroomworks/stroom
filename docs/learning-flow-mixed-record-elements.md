# Shapeshifter AI cannot learn XML whose records have different element names

## Summary

Shapeshifter AI fails to learn the `GATEWAY-AUDIT` feed in the *Shapeshifter AI Demo* content.
Every attempt ends with:

```
No record boundary after 5 candidates
```

The model isn't at fault.
The split step (*what is one record?*) only accepts an answer of the form *every element named X*, and that answer must account for at least 90% of the document's text.
In this feed each record is a child of `<batch>`, but the records come under nine different element names (`authentication`, `transfer`, `share`, `policyChange`, ...).
No single name accounts for anywhere near 90% of the text, so every answer that names a real record is rejected, and the step runs out of candidates.

Any XML feed that interleaves several record types as siblings under a container has the same problem.
That layout is common in audit exports, SIEM exports and event logs.

## Affected data

`stroom-learning-flow/stroom-learning-flow/src/testFixtures/resources/stroom/shapeshifter/ai/scenario/gateway-audit.xml`
(17,291 bytes; also stream 2117 on the `GATEWAY-AUDIT` feed of the dev instance).

```
export                          (root, 2 children)
  batch id=B-1101               authentication×4, transfer×3, heartbeat×2, sessionEnd×2,
                                share, policyChange, adminAction, batchSummary
  batch id=B-1102               authentication×2, transfer×2, sessionEnd×2, diagnostic,
                                share, policyChange, adminAction, heartbeat, batchSummary
```

The records sit at depth 3 (`export > batch > *`).
The context each record needs (node, site, time zone) is held in attributes on `<batch>`.

The three drift files beside it (`gateway-audit-drift-{1-prefixed,2-namespace,3-flattened}.xml`) have the same problem.
In drift 3 the `<batch>` wrapper is gone, and the mixed records sit directly under `<export>`, at depth 2.

## Steps to reproduce

### On a running Stroom

1. Import the demo content pack (`DemoContentPack`), so that `System / Shapeshifter AI Demo` contains the `Any feed` Shapeshifter AI document and the `Learning model` model document.
   Create the `learning-flow` API key secret that the model document names.
2. Create the feed `GATEWAY-AUDIT`, with stream type `Raw Events` and encoding UTF-8.
3. Create a pipeline `GATEWAY-AUDIT supervised` with the same shape as the other demo pipelines:
   `Source -> Shapeshifter AI (Any feed) -> Schema filter (EVENTS) -> Record output filter -> Record count filter -> XML writer -> Stream appender (Events)`.
4. Upload `gateway-audit.xml` to `GATEWAY-AUDIT` as `Raw Events`.
5. Add a processor filter for the pipeline over `GATEWAY-AUDIT` / `Raw Events`, and enable it.
6. Wait for the stream to be processed and open the pipeline's Learning Status tab (from the `Any feed` Learning Flow's Pipelines tab, or Monitoring > Smart Pipelines).

**Expected:** the learner settles a record boundary, writes targets for each record kind, and learns a translation.

**Actual:** the attempt is abandoned with `No record boundary after 5 candidates`, and the session is parked (`PARKED`).

The session on the dev instance (attempt 14, stream 2117), from `shapeshifter_turn`:

| Turn | Step | Candidate | Model's answer | Outcome |
|---|---|---|---|---|
| 1 | chain | 1 | `XSLTFilter` | PASSED |
| 2 | split | 1 | `batch` | WHOLENESS_SHORT |
| 3 | split | 2 | `authentication` | WHOLENESS_SHORT |
| 4 | split | 3 | `batch` | WHOLENESS_SHORT |
| 5 | split | 4 | `export` | REFUSED (the root) |
| 6 | split | 5 | *"No single element name fits. The records are the mixed children of `batch` (authentication, transfer, heartbeat, share, policyChange and so on), each a different element. The root `export` is not accepted, `batch` occurs once, and `authentication` is only one of several record types. A name alone would be a guess, so I can't give one."* | REFUSED (not an element name) |

The model diagnosed the problem correctly on its fifth candidate.
The split step has no way to accept that answer.

### Without a model (deterministic)

The XML scenario tests can reproduce this with a scripted model, using the same pattern as `TestScenario49XmlFragments`:

1. Build a `Scenarios.xmlScript(...)` whose split replies are, in turn, `authentication`, `transfer`, `record`, `batchSummary` and `heartbeat`.
2. Process `new Input(1, "GATEWAY-AUDIT", "Raw Events", Map.of("Format", "XML"), Scenarios.resource("gateway-audit.xml"))`.
3. The run is abandoned with `No record boundary after 5 candidates`.

No reply that names one record type can pass.
The failure is a property of the check, not of the model.

## Where it happens

| Location | What it does |
|---|---|
| `Conversation.split(...)`, XML branch (`stroom-learning-flow/.../learning/Conversation.java`) | Asks the model for one element name. Accepts the reply only if it matches `[A-Za-z_][\w.-]*`, occurs in the document, and passes `TargetChecks.recordElement`. |
| `TargetChecks.recordElement(...)` | Rejects the root, and a single occurrence that holds repeated structured children. Then requires the occurrences of the named element to hold at least `SPLIT_WHOLENESS` (0.9) of the document's non-whitespace text. |
| `RecordBoundary` (`stroom-core-shared/.../shapeshifter/shared/RecordBoundary.java`) | Can only express *the element with this local name* (`ofElement`) or *the items of this JSON array* (`ofArray`). There is no way to express *every child element of X*. |
| `Templates` `SPLIT_XML` | Tells the model to *Name the element that is one record ... Reply with the element's name alone*. The prompt leaves no room for a mixed-name answer. |

No record element name gets near 90%.
Over the whole file, `transfer` holds 41% of the document's non-whitespace text and `authentication` 20%.

`batch` also fails, though over the whole file it would pass (2 occurrences, 100%).
The split is judged on a **sample**, and the document's default `sampleSizeLimit` is 8,192 characters.
The first `</batch>` is at byte 11,318, so the sample holds part of one batch only.
As the model's reply says, `batch` "occurs once" there.
`recordElement` therefore rejects it as a container of repeated records.
`export` is rejected as the root.

On this sample, no reply of any kind can pass the split step.

The `candidates` count comes from the step's `candidates` setting, or else from the document's `maxAttempts`.
The flow running here uses 5.

## Possible fixes

### 1. Add a "children of element" record boundary (recommended)

Let a boundary say *every child element of `<batch>` is one record*, whatever its name.

- **`RecordBoundary`**: add a third variant, for example `ofChildrenOf(String parent)`, with a matching getter and JSON property.
  The existing `depth` already covers what the split filter needs: one level below the parent (3 for `gateway-audit.xml`, 2 for drift 3).
  `equals` and `hashCode` must tell this variant apart from `ofElement(parent)`.
  Bindings already in storage must still deserialise.
- **`OutputRecords.recordsBy`**: return the element children of every occurrence of the parent.
- **`TargetChecks`**: add a `recordChildren(document, parent)` check.
  It should require that the parent is not the root, unless the records are the root's own children.
  It should also require at least one child, and that the children together hold at least `SPLIT_WHOLENESS` of the text.
- **`Conversation.split`, XML branch**: accept a reply such as `batch/*` (or `*` for the root's children), and build the boundary from it.
  Keep the current single-name reply working.
- **`SPLIT_XML` prompt**: explain both forms.
  Suggested wording: *where every record is the same element, name it; where records of several kinds sit side by side under one container, reply `container/*`*.
  Raise `Templates.VERSION`.
- **The configuration question** (`QuestionText.split(...)` for XML): describe the new boundary to the transform, along the lines of *each record is one child of `<batch>`*.
- **Record kinds**: the target step groups records by kind.
  With this boundary, the element name is the obvious kind key.
  Check that `TargetChecks.count` / `kindOf` keep `authentication` and `transfer` as separate kinds.
- **Stage**: wherever the stage counts records or judges yield by the binding's boundary, handle the new variant, and give the fragment's `SplitFilter` the recorded depth.

The approach fits with what is already there.
`SplitFilter` splits by depth, not by name, and *replicates the parent element structure for all split out instances* (its class Javadoc).
That should mean each record arrives inside its `<batch>`, with the batch's `node`, `site` and `timeZone` attributes, which the policy-change records need to place their local times.
Confirm this with a test.

**Tests to add:**

- A scenario test that learns `gateway-audit.xml` with the split reply `batch/*`, and checks that the boundary, the depth and the number of kinds come out as expected.
- The same test over the three drift files.
  Drift 3 needs `*` (the root's children); drifts 1 and 2 test prefixed and changed namespaces against a local-name boundary.
- `TestTargetChecks` cases for the new check: root, empty parent, short wholeness, and a pass.
- `RecordBoundary` serialisation, in both directions, for the new variant.
- A regression test showing that the single-name boundary still works (the existing scenarios 46 and 49 cover most of this).

### 2. Accept a set of element names

Let the reply list several names, such as `authentication|transfer|share|...`, and judge wholeness over their union.

This needs less new vocabulary, but it is brittle.
The model has to list every kind, and a new kind in a later stream falls outside the boundary.
The `diagnostic` record appears only in batch B-1102, so a sample drawn from B-1101 alone would miss it.
Drift is where this feature has to cope, so this approach is not recommended.

### 3. Accept an XPath boundary

Let the reply be an XPath such as `/export/batch/*`.
This is the most general option, but the stage would need a depth for `SplitFilter`, and an arbitrary XPath doesn't map to one.
It also gives the model far more ways to answer wrongly.
Option 1 covers the realistic layouts with a much smaller surface.

### 4. Workarounds that need no code change (not recommended)

- **More candidates** (`maxAttempts`, or the step's `candidates`): every possible single-name answer fails the same way, so this only wastes model calls.
- **Steering the model to answer `batch`** (document `instructions`, or a `SPLIT_XML` override): the model already tried this twice, and it was rejected, because the sample holds only one `batch` (see *Where it happens*).
  A larger `sampleSizeLimit` (over 17,291) would let `batch` through.
  But each batch would then become one "record", so the target step would be shown a whole batch and asked for one event.
  The yield and record-count checks would then fail further on.
  The stage would also lose its per-record memory bound, because a batch would be the unit.

### Also: the sample size makes the container check misfire

Whatever fix is chosen, the record-boundary checks judge a truncated sample as if it were the whole stream.
A wrapper that repeats in the stream (`batch`) but appears once in the sample is rejected as a container.
Under option 1, the parent of a `batch/*` boundary may likewise appear only once in a sample, which is fine, and the check should be written to allow it.
The rejection message could also say that the sample was truncated, so that neither the model nor a person reads "occurs once" as a fact about the stream.

## Notes

- The `GATEWAY-AUDIT supervised` pipeline on the dev instance has the description *"The supervised stage where APP-EVENTS's parser and translation would be."*, which looks copied from the APP-EVENTS pipeline.
- `gateway-audit*.xml` and the `netfilter*.log` files are not yet in `DemoContentPack.FEEDS`.
  The pack only sets up DOOR-ACCESS, APP-EVENTS, FIREWALL and MAINFRAME.
- The session's turns above were read from the dev instance's database (`shapeshifter_turn`, attempt 14).
  The stored questions are summaries, so the exact sample the model was shown was not available.
  That the sample held one `batch` comes from the default `sampleSizeLimit`, the file's byte offsets and the model's own reply.
- Stream 2117 has been retried 34 times while it waits on the parked session, and its waiting reason misleadingly says *"Another node is learning for this pipeline"* (this wording has since been replaced: `Stage.why` now words the reason by what holds the claim).
  See `docs/learning-flow-supervisor-visibility.md`, item 5.


## What was built (20 November 2026)

**Fix 1, as recommended.** A record boundary can now be *the children of an element*.

| Part | Where |
|---|---|
| The boundary, as a **real field** rather than `batch/*` stuffed into the element field | `RecordBoundary.children`, `ofChildrenOf`, `isChildren`; `equals` tells it from `ofElement(parent)` |
| The records it names | `OutputRecords.recordsBy` — the element children of **every** occurrence of the container, so a stream of batches is a stream of records |
| The check | `TargetChecks.recordChildren`: at least two children, and they hold the document between them |
| The reply grammar | `container/*`, or `*` for the root's own children (`Conversation.childrenSplit`) |
| The prompt | `SPLIT_XML` explains both forms; `Templates.VERSION` 8 → 9 |
| What the transform is told | `QuestionText.split` describes it, including that the context it needs is on the enclosing container's attributes |
| Carried through learning | `Boundary.ofChildrenOf`, `Boundary.shared()` |

**The container occurring once is no longer refused.** That was half of why this feed could not be
learned: the split is judged on a sample, the first `</batch>` is at byte 11,318 and the default sample
is 8,192, so `batch` "occurs once" there and the old check read a container that repeats in the stream as
a container of records. `recordChildren` allows it; what it refuses instead is a container with a *single*
child, and it says to look one level deeper or one level up rather than simply refusing.

**Every consumer of the boundary handles it explicitly.** In code there is exactly one —
`OutputRecords.recordsBy` — plus `RecordBoundary.toString` for what a person reads.

**And it is persisted.** A binding's boundary is stored in columns, not in JSON, so `boundary_children`
was added to the unreleased migration on both `shapeshifter_binding` and `shapeshifter_output` (N5
option A), with jOOQ regenerated and both DAOs reading and writing it. Missing that would have lost a
children boundary silently on a node: written as all-nulls and read back as no boundary at all, so the
fragment would split in the wrong place and the stage would count the root's children as records.
`TestLearnerBindingsDao.aBoundaryOfAContainersChildrenSurvivesTheRoundTrip` holds it.

### Also built: the sample-size refusal (N2 option E)

A stream that is **one XML or JSON document larger than the prefix** read as text is now refused, with a
stop report that says why — *a part of a document is not a document* — that a larger limit is not the
answer, and that the feed should be sent as a stream of records or split before it reaches the stage.
Before, the split step passed with no boundary at all and everything after it was judged on nothing.

It fires only where the sample **looks like** markup. A chain whose first element is a transform reads
anything down the same branch, so a CSV stream of a million lines reaches it too — and its prefix is a
perfectly good sample. Refusing that would refuse the feeds this feature is for; a test holds it.

### Tests

`TestMixedRecordElements` (8, over the real fixtures and all three drift files),
`TestScenario65MixedRecordElements` (3, end to end through the conversation),
`TestOneDocumentLargerThanThePrefix` (2), and `gateway-audit.xsl` as a fixture.

**Not done:** the dev-instance re-run of `GATEWAY-AUDIT` (stream 2117), which needs the MCP server.
