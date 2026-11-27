# Shapeshifter AI: the UI design

*Written 18 November 2026 · describes the UI merged into `ai_translations_server` from `ai_translations_ui_rework` (`c40284265b`, merged in `7e06bb589c`)*

## Status

This is the agreed UI design for AI Translations.
The screens merged from `ai_translations_ui_rework` are taken as final, apart from minor bug fixes.
Where another document in `docs/` describes a different UI, this one wins.

It replaces both earlier UI proposals:

| Document | Status |
|---|---|
| [Shapeshifter AI UI Rework.html](Shapeshifter%20AI%20UI%20Rework.html) (the full rework) | Superseded. The built UI follows its main idea (editable flows, set up as plain-language steps) but puts the screens in different places. See [What changed from the proposals](#what-changed-from-the-proposals). |
| [simplified-ui-rework.html](simplified-ui-rework.html) (the simplified rework) | Superseded and not to be built. It assumed a fixed recipe and a smaller Supervisor; the built UI keeps editable flows and every action. |

The design notes in the module (`stroom-learning-flow/stroom-learning-flow/design/07-ui.md` and `05-learning-flow.md`) still describe the old screens, and the code's javadoc cites them as "design 07" and "design 05".
They should be brought into line with this document.

## The words the screens use

The screens use these words, and the documents in `docs/` should too.

| Word on screen | What it is | Older words in the documents |
|---|---|---|
| **Learning Flow** | The document type (`ShapeshifterAiDoc`). Its display name in `DocumentTypeRegistry`. | Shapeshifter AI document |
| **AI Translator** | The pipeline element (`ShapeshifterAiParser`) that learns and serves a translation. | Shapeshifter AI element, the AI element |
| **Smart Pipeline** | A pipeline that holds an AI Translator element, directly or through a parent it inherits from. One per feed, as Content Templates or the Pipeline Factory make them. | Supervised pipeline, `<FEED> supervised`, learner |
| **Learning run** | One attempt to learn a translation (`shapeshifter_attempt`). | Session, attempt |
| **Learning steps** | The learning flow's steps, edited on the Setup tab. | Flow, approach, recipe |
| **Default setup** | One of the example flows, loaded as a starting point: *Straight to it* (`DIRECT`), *Agree the events first* (`TARGET_FIRST`), *Quick, then careful* (`ESCALATING`). | Starting point, example flow |
| **Translating** / **On trial** / **Below par** | A translation in use; on trial is a provisional binding (to be removed: SIM-3); below par is under the Learning Flow's flag. | Serving, provisional, below threshold |
| **Needs you** / **Paused** / **Approve** | A run waiting for a person: parked, or a draft under review. | Waiting for a person, parked, awaiting review |
| **Stopped** / **Off** | The learner gave up; or learning is switched off in the Learning Flow. | Given up, learning off |
| **Quality checks** | The scorers, set from a preset: *Lenient*, *Standard* or *Strict*. | Scorers, scoring |
| ~~**Assessment**~~ | **To be deleted** (no requirement found; delivery plan, stage 0b group 4). A judgement of a Smart Pipeline's output: *looks right*, or *has an issue*. | (new) |

## Who uses it

| Role | What they do | Where |
|---|---|---|
| **Admin** | Sets up a Learning Flow, watches every Smart Pipeline, steps in when one needs a person, and judges a pipeline's translated output. | The Learning Flow's tabs; Monitoring > Smart Pipelines; a pipeline's Learning Status and Quality Assurance tabs |
| ~~**QA person**~~ | **Role to be deleted** with the assessments. | – |
| ~~**Data Provider**~~ | **Role to be deleted** with the assessments. | – |

These are roles, not permissions.
Access follows Stroom's document permissions:

- A Learning Flow's tabs: the document's VIEW and EDIT permissions.
- A pipeline's Learning Status and Quality Assurance tabs: the **pipeline's** permissions, not the Learning Flow's. A person who may view the pipeline reads; one who may edit it acts. The server gives a viewer the settings of the Learning Flow it learns under (`SupervisorResource.settings`), so they don't need access to the Learning Flow itself.
- Samples of records on the Quality Assurance tab: only for a person who may also view the data.
- Recording and resolving assessments: to be deleted with the assessments.
- Monitoring > Smart Pipelines: the View Data application permission, then each row by the pipeline's permissions.

The QA person and Data Provider roles, and the assessments they used, are to be deleted: no requirement for them can be found (delivery plan, stage 0b group 4).

## Where things are

```text
Learning Flow (document)
├── Pipelines        its Smart Pipelines, where each stands; readiness banner above
├── Setup            everything the document holds, on one page, with a route map
├── Documentation
└── Permissions

Pipeline (a Smart Pipeline)
├── Data
├── Structure        an AI Translator selected unfolds to "What it learned"
├── Learning Status  where its learning stands, and every action on it
├── Quality Assurance  figures, volume, samples (assessments to be deleted)
├── Processors, Active Tasks, Documentation, Permissions

Monitoring menu
└── Smart Pipelines  every Smart Pipeline of every Learning Flow
```

The idea behind the layout: a Learning Flow says **how** feeds are learned; each Smart Pipeline is **where** a feed's learning happens.
So the work on one feed (watching it, approving, granting, answering, judging its output) is done on its pipeline, by the pipeline's permissions.
The Learning Flow and the Monitoring screen list pipelines and open them; neither acts on one.

### A Learning Flow's Pipelines tab

The tab the document opens on (`FeedsTabController`, labelled *Pipelines*).

- **Readiness banner** (`Readiness`, `ReadinessBanner`), across the top, shown only when something stops the document learning anything: learning is off, no model is chosen, there are no quality checks (so every answer would pass), or the learning steps have a problem.
  Each check has a fix button (*Switch on*, *Choose a model*, *Use the Standard checks*, *Show the approach*) that opens the Setup tab at the right section and, where it can, makes the change there for the person to check and save.
  It is worked out from the document as the Setup tab has it, so a change shows before it is saved.
- **The list of its Smart Pipelines** (`FeedList`): one row per pipeline, with an *Open* button, the pipeline's name, a chip saying where it stands, and a line of detail (what it scores, what waits, or why it stopped).
  A quick filter on names; a filter dialog (`FeedFilterEditor`) by where they stand (*All*, *Needs you*, *Learning*, *Translating*, *Stopped or off*) and whether deleted pipelines show; auto refresh every ten seconds while the tab is on screen.
- *Open* opens the pipeline at its Learning Status tab.

### A Learning Flow's Setup tab

Everything the document holds, on one page (`SetupTab`), in the order a translation meets it.
A **route map** down the left (`RouteMap`) is how the page is found around: each section is a stop, with a line saying what it is set to (for example "Standard · 4 checks") and what is wrong in it, if anything.
Under *Learning steps* the map shows each step, with rules that go back drawn as arcs and rules that skip ahead dashed, then the fallback and the translation learned.

| Section | What it holds | Under *Advanced options* |
|---|---|---|
| **AI model and instructions** | Learning on or off; the model; *Instructions to the AI*, free text sent with every question. | – |
| **Learning steps** | The learning flow's steps. *Load default setup* replaces them with one of the default setups. The step chosen on the map is edited on its card (`StepCard`): its rules as sentences, its tools (move, copy, delete), and the question text it is asked with, which can be edited or loaded from a built-in. *Add step* under the chosen stop. *Limits*: time for each run, tokens for a whole run. | Tries per step; largest sample of a stream |
| **Quality checks** | *Start from*: Lenient, Standard or Strict. | The checks themselves, where they differ from a preset; lines kept back to test against |
| **Approving new translations** | Pass mark (to be removed: UM-2 and UM-3); *New translations*: use them as soon as they pass, or wait for someone to approve them. | Records before a translation is trusted; if the transform can't be finished (pause for a person, or serve what it can with the rest as Unknown events), with the fallback's question text |
| **Monitoring translations in use** | The level below which a translation in use is flagged. | Records kept to check against later; when a waiting stream is overdue |

*Advanced options* open by themselves wherever something differs from a new Learning Flow's defaults (`AdvancedSettings`), so nothing changed is ever out of sight.
The flow is never shown or edited as text, and the store refuses to save a flow with problems; a problem with a step is shown on its card and against the step on the map.

There is no separate scoring screen, flow chart or step dialog: the old Learn and Score tabs are folded into this page.

### A pipeline's Structure tab: What it learned

Selecting an AI Translator element on a pipeline's Structure tab shows, under the element's properties, a fold-out **What it learned** (`LearnedTranslation`).
Unfolded, it reads the element's learned translation and lists its parts in the order a record passes through them (a parser and its text converter, a filter and its XSLT, and so on), each opening the document it uses.
It also shows *Translating with* (the learned fragment pipeline) and a link, *Open its Learning Status*.
It is read only when unfolded, so a person who never looks costs nothing.

### A pipeline's Learning Status tab

Shown where the pipeline, or a parent it inherits from, has an AI Translator element (`PipelineAiTabController`, labelled *Learning Status*).
This is the one place a pipeline's learning is acted on.

- **Which element**, where the pipeline has more than one AI Translator.
- **Where it stands** (`FeedSituation`): a chip, one sentence, a sentence of detail, and the actions that fit, the main ones as buttons and the rest under *More*.
  Where a run stopped with a report (`StopReport`), the sentence is the report's error, with its **Details** and **Suggestions**.
  A suggestion the screen can act on (change the limits, the learning steps or the model) opens the Learning Flow's Setup tab at that place.
- **Translating with**: the learned translation, as a link.
- ~~**Flagged by QA**~~: **to be deleted** with the assessments.
- **Note to the AI**: a note for this feed, sent with its next learning run (guidance).
- **Learning history**, folded, and opened by itself when a run needs someone:
  - which learning run is shown;
  - **Progress** (`FeedProgress`): each step of the flow in order, done, current or to come, with the tries each took, and where the run went back to an earlier step, why;
  - the **conversation** (`ConversationList`): a row per question, with its step, who answered and how it was judged; each opens to its score, what was asked and the whole answer, with a button to answer it instead, or change the answer and run again from there;
    - *What was asked* will show the prompt as it was submitted to someone with the *View Data* permission, rebuilt from what the run recorded and the stream's own data; anyone else sees the question's summary and why (delivery plan, N9). It can't be rebuilt once the stream is deleted, and the conversation says so.
  - on a second tab, shown only where streams wait, the **waiting streams** (`WaitingStreamList`): raised, feed and type, stream id, waiting since, tries, and why.

Refreshed every ten seconds while it is on screen.
A person who can only view the pipeline sees everything and is offered no action.

The actions (`FeedAction`), offered only where they apply:

| Button | What it does | Offered when |
|---|---|---|
| Approve and use it | Approves a draft | A draft awaits review |
| Reject… | Rejects a draft; asks why | A draft awaits review |
| Give it more… | Grants a paused run more tokens or minutes | A run is paused |
| Finish with Unknowns | Sends a paused run to the fallback | A run is paused, and isn't already headed for the fallback |
| Answer it myself | Answers the question the run stopped at | A run is paused at a question |
| Accept now | Trusts a translation on trial now | On trial. **To be removed** with provisional bindings (SIM-3) |
| Improve… | Asks for the translation in use to be made better | Translating |
| Learn again | Learns afresh from the next stream | Any pipeline that still exists |
| Stop learning… | The learner gives up; asks why | Not already stopped, and no draft awaits review |
| Remove translation… | Takes the translation back; asks why | Translating |
| Go to Setup | Opens the Learning Flow's Setup tab, where learning is switched on | Learning is off |

Nothing that would change a deleted pipeline's learning is offered.

### A pipeline's Quality Assurance tab

Shown where the pipeline has an AI Translator element (`QualityTabController`, labelled *Quality Assurance*), for judging one pipeline's output without reading every record.
It stays, without the assessments (decided 18 November 2026), and is accepted as it stands, with no separate written requirement; it may evolve with use (19 November 2026).
Read when shown, not on a timer: quality changes over hours.

- **Quality now** (`QualityFigures`): what the translation scores lately and over how many records, against the Learning Flow's flag; what it scored when it went live (against the pass mark, until that is removed); whether it is trusted or (until provisional bindings are removed) still on trial; how many streams wait. Each figure says what it rests on, and is coloured where it needs attention.
- **Volume**: records translated day by day, as a bar chart (`VolumeChart`) and, under *Day by day*, a table.
- **What its records became**: the kinds of output its records turned into (`RecordKind`).
- **Samples**: records the translation was learned and checked on, beside what they became. Only for a person who may view the data; anyone else is told why there are none.
- ~~**Assessments**~~: **to be deleted** (no requirement found; delivery plan, stage 0b group 4, and Track 2 step 1b), with *Record an assessment…*, *Mark resolved* and the assessment dialog (`AssessmentEditor`).
- **Learning runs**: each run's score, newest first.

It is served by a new resource, `QualityResource` (`/shapeshifterAiQuality/v1`), backed by `QualityService`, the `shapeshifter_assessment` table (to be deleted), `shapeshifter_output.record_count` and the daily volumes.

### Monitoring > Smart Pipelines

A screen of its own in the Monitoring menu (`SupervisorController`, `ShapeshifterAiSupervisorPlugin`), beside Jobs and the other monitoring screens.
It is the same list as a Learning Flow's Pipelines tab, across every Learning Flow the user may read, with an extra column naming each pipeline's Learning Flow.
*Open* goes to the pipeline's Learning Status tab.
Nothing is done to a pipeline's learning here.
It replaces the Supervisor; its tab type is still `ShapeshifterAiSupervisor`, so a tab left open reopens.

### Related: Monitoring > Egress Policy (towards the end of the project)

Not one of the AI Translations screens, but AI Translations will use it.
The `ai_translations_egress` branch, to be pulled in towards the end of the project by the [delivery plan's Track 2 step 6](../learning-flow-delivery-plan.md#step-6-the-egress-policy-and-the-list-on-its-screen), adds an Egress Policy screen for holders of *Manage Egress Policy*: which destinations Stroom may send data to or read from, the mode and kill switch of each kind, and what was blocked.
The plan adds a part to it for **AI-written content**: the list of functions and instructions a learned stylesheet may use (decision N1).
Until then the list is hard-coded, and nobody can change it without a release.
The AI Translations screens don't change for it; a stylesheet refused by the list shows as a failed check in the conversation on the Learning Status tab, like any other check.

### While stepping

The stage decision pane (`StageDecisionPane`) still shows what the AI Translator decided for a stream while stepping a pipeline.

## What was removed

The UI branch deleted these screens and classes. Documents that name them describe the old UI.

| Removed | Replaced by |
|---|---|
| The Learn tab (`LearnTab`), flow chart (`FlowChart`), flow-step dialog (`FlowStepEditor`) | The Setup tab's *Learning steps*: route map and step cards |
| The Score tab (`ScoreTab`) | The Setup tab's *Quality checks*, with presets |
| The Supervisor's learner list (`LearnerList`, `LearnerFilterEditor`) | `FeedList` and `FeedFilterEditor`, on a Learning Flow's Pipelines tab and on Monitoring > Smart Pipelines |
| The session list (`SessionList`) | The run chooser under *Learning history* |
| The turn list and transcript (`TurnList`, `TurnTranscript`) | `ConversationList` |
| The process header (`ProcessHeader`) | The chips and detail lines in the list, and the readiness banner |
| The Supervisor's acting screen | A pipeline's Learning Status tab |

## What the design settles for the server

Taking this UI as final settles, or narrows, several open questions in the [delivery plan](../learning-flow-delivery-plan.md) and [simplification-options.md](simplification-options.md):

| Question | What the UI settles |
|---|---|
| SIM-2 (fixed recipe or editable flows) | **Editable flows stay.** The Setup tab is built around editing steps, with default setups as starting points. Simplification option B (remove the flow language) is off the table. N3 became "which default setup a new Learning Flow starts from", decided as *Quick, then careful* (`ESCALATING`). Flows are defined in JSON rather than the flow DSL; the Setup tab never showed the DSL, so the screens don't change. |
| SIM-4 (which Supervisor actions in v1) | **Every action stays** on the Learning Status tab, including Improve, Exhaust (*Finish with Unknowns*), Answer, Accept and guidance (*Note to the AI*). Simplification option D (a smaller Supervisor) is off the table as a UI change. Improve can still move from a web request to the worker without changing the screen. |
| SIM-5 (prompts in code) | **Per-document question texts stay**, edited on each step's card, and the free-text *Instructions to the AI* is added beside them. Simplification option E is off the table; N9 (record the prompt version per turn) matters more, not less. |
| SIM-3 (provisional bindings) | **Decided: removed.** The *On trial* chip, *Accept now* and the "on trial" figure on the Quality Assurance tab go (delivery plan Track 2 step 1a), a minor change to this design. A result from too few records becomes a draft, and the feed waits for a person (N4). |
| SIM-1 (default promotion mode) | **Decided: review by default.** The Setup tab already offers both modes. New Learning Flows default to *Wait for someone to approve them*, and choosing *Use them as soon as they pass* will need a permission beyond EDIT: a small change to the Setup tab. The mode is per Learning Flow, not per feed. |
| UM-2 and UM-3 (which checks are gates) | **Decided; it changes *Quality checks* and the step card.** One list of every check (wholeness, preservation and fidelity included), each **Off, Advice or Gate**, chosen by the user; compile is always a gate. A candidate passes when every gate passes, so the *Weight* field and the *Pass mark* field go. Steps don't name checks, so *what judges it* goes from the step card; a step whose rule uses a check that is off is warned about on its card. Where a check is made a gate, the section says it also acts while serving. The score shown everywhere is the average of the checks that ran. Defaults: the objective checks (schema-valid, completeness, preservation, the regression corpus, input coverage, wholeness, fidelity) as gates; no-data-like-literals as advice at first; time plausibility as advice, with its window settable per Learning Flow; the heuristic checks (yield, extraction quality, business rules) off. The presets are redefined over the three states. See the delivery plan, stage 0b group 4. |
| D1 (where the target schema lives) | **Decided: a *Target schema* field under *AI model and instructions*** on the Setup tab, naming one schema version, with the route map's summary naming it. One Learning Flow per target schema. |
| N6 (where a feed's time zone comes from) | **Decided: per pipeline.** A `timeZone` text property on the AI Translator element (edited on the pipeline's Structure tab), used when a stream has no `TimeZone` header; otherwise UTC. The Learning Status tab shows the zone in use and where it came from. No Setup tab change. |
| Supervisor visibility items 3 and 5 | Done: stop reports with details and suggestions, and the waiting reason by what holds the claim. |

## What changed from the proposals

The built UI is closest to the full rework, with these differences:

| Full rework proposed | Built |
|---|---|
| The Supervisor moves into the document as its first tab, where feeds are watched and acted on | The document's first tab (*Pipelines*) only lists its Smart Pipelines. Acting moves to each pipeline's Learning Status tab, by the pipeline's permissions. |
| An Approach tab and a Settings tab | One Setup tab, with a route map and five sections |
| Feeds tab with a detail pane beside the list | List only; the detail is the pipeline's Learning Status tab |
| A Monitoring overview with system-wide spending and approval filters | Monitoring > Smart Pipelines: the list across Learning Flows, without spending figures |
| A setup wizard (phase 5) | None; the readiness banner and the Setup tab's defaults do its job |
| No QA screens | A Quality Assurance tab per pipeline. Its assessments and the QA person and Data Provider roles came with it, and are to be deleted |
| The document type called "Shapeshifter AI" | Called "Learning Flow"; the element called "AI Translator" |

Not built from either proposal: approval evidence beside a draft (the gate strip), who approved a translation, and the spend-breaker state.
Those are server additions the UI would show; see the delivery plan's Track 2.

## Code and stories

- Client: `stroom-core-client/src/main/java/stroom/shapeshifter/client/component/`, plus `PipelineEditor` and `PipelineStructure` in `stroom.pipeline.client.component` for the pipeline's tabs.
- Shared: `QualityResource`, `QualityReport`, `QualitySample`, `QualityAssessment`, `AssessmentRequest`, `AssessmentVerdict`, `RecordKind`, `VolumeDay`; new `SupervisorResource` calls `pipelineLearners` and `settings`.
- Server: `stroom.shapeshifter.ai.quality` (`QualityService`, `Assessments`, `Volumes`), `LearnerAccess`, `StopReports`.
- Workbench stories: `ShapeshifterAiEditorStories`, `SupervisorStories`, `PipelineAiTranslationStories`, `PipelineQualityStories`, `ShapeshifterAiStopReportStories`.

## Open questions

1. ~~Who are the QA person and the Data Provider in the permission model, and does recording an assessment need its own permission rather than VIEW on the pipeline?~~ Moot: the roles and assessments are to be deleted.
2. ~~Should resolving an issue need EDIT on the pipeline (as built), or a separate permission?~~ Moot: the assessments are to be deleted.
3. ~~Should assessments feed the server, for example as reference output (UM-8) or as a signal beside the gates (UM-2, UM-3), or stay a record for people only?~~ **Decided, 18 November 2026: the assessments are deleted** (delivery plan, stage 0b group 4). No requirement for them, or for the QA person and Data Provider roles, can be found: they arrived with the UI rework branch. A future requirement for checking live output should start from that requirement.
4. Does Monitoring > Smart Pipelines need the system-wide spending and approval filters the earlier proposals planned, once the spend breaker exists?
