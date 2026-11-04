# Review panel: what the person sees and does

**Status:** a design draft for the project owner. Nothing here is built. It specifies **function, not layout**: before
any GWT work the project's `stroom-gwt/UI-GUIDE.md` has to be read and followed (a component and a controller, Stroom's
own widgets, BEM styles, workbench stories with read-only and dialog OK and Cancel). I have not read it.

**Why this document exists.** The project owner has decided that human review is the default for AI translations
(`shared-modules-design-note.md`, §1 point 6 and §6.7), so the review experience is a deliverable in its own right. The
pilot (`pilot/results/`) gave evidence about what it must do, and this turns that evidence into a specification.

## 1. What the pilot says the panel must do

| Finding (pilot) | Consequence for the panel |
|---|---|
| Every output was schema-valid and still five of six case-3 runs had source and destination swapped | The panel must **show the result of each decision**, not a validity tick. A person compares events, and a validity badge proves little |
| Only one of three ask-first runs asked about direction when the log had no hint | **The system chooses the mandatory questions**, deterministically, not the model (`pilot/results/question-generator-evaluation.md`) |
| One-shot runs made reasonable but unintended choices (`RESET`, an invalid address) | The person's answer is the **intent**. Capture it once and keep it |
| Four of six asked-first runs did not ask for the required `System/Environment` and used "Unknown" | **Required values the log cannot supply** are asked once per feed, and a placeholder is always flagged |
| Three of six one-shot runs skipped bad records with no message | **Dropped records are listed with the reason**, and a silent drop is a visible defect |
| Every run stated its assumptions in its report | **Assumptions are surfaced and shown first**, not left in a paragraph |
| Models over-asked about things the schema settles | Questions are **limited to what the data and schema cannot settle**, and the panel says what it settled and why |

## 2. Principles

1. **Ask only what the data cannot settle.** Show what was settled and why, so the person can overrule it.
2. **Mandatory questions are the system's, and the model cannot skip them.** In review mode a person may skip one. The
   panel then says what that leaves unchecked.
3. **Show the consequence, not the choice.** Every option is rendered as the event it would produce, for a real record.
4. **A person's answer is kept as evidence.** It becomes a golden event (the pilot design, `shared-modules-design-note.md`
   §2.4), a feed setting, or a constraint on the model, with who and when (D6).
5. **Say what was not checked.** Never imply a check that did not happen (§6.7, "honesty is the requirement").
6. **Cheap by default.** One click per answer. No XML to write. Related questions are batched.
7. **Nothing the model produced is trusted text.** Questions and options are built from typed fields, not raw log
   text (D3). Anything shown from the log is marked as data.

## 3. The flow

```text
  1. Intake         what the system found: the sample, the detected shape, the target schema
  2. Questions      the mandatory and the optional decisions, each as a card (section 4)
  3. Candidate      the proposed translation, run over the sample, with its checks (section 5)
  4. Decision       approve, ask for a change, or reject
```

Steps 2 and 3 can repeat: an answer changes the candidate, and the candidate can raise a new question.

## 4. The question card

One card per decision. This is the direction question from pilot case 3 (the log lists the destination first):

```text
+---------------------------------------------------------------------------------------+
|  MANDATORY   Which address is the source?                  evidence: none in the data   |
|                                                                                       |
|  Columns 4 and 5 both hold addresses, and the schema has a Source and a Destination.    |
|  Port numbers point one way on row 1 and the other way on row 2: weak evidence.         |
|                                                                                       |
|  Row 1:  2026-11-04T10:00:01Z gw03 DENY 198.51.100.7 10.2.0.5 443 51514 tcp             |
|                                                                                       |
|   ( ) Column 4 is the source        ( ) Column 5 is the source                          |
|       Source       198.51.100.7:443       Source       10.2.0.5:51514                   |
|       Destination  10.2.0.5:51514         Destination  198.51.100.7:443                 |
|                                                                                       |
|  ( ) None of these / I will say     [ Skip for now ]    [ Ask me later ]                |
|                                                                                       |
|  If you skip: the translation will assume column 4, marked "assumed, not confirmed",    |
|  and automatic mode will not be available for this feed.                                |
+---------------------------------------------------------------------------------------+
```

**Card fields**

| Field | Meaning |
|---|---|
| Kind | `direction`, `required value`, `action`, `invalid value`, `time zone`, `date order`, `protocol`, `placeholder marker`, `row shape` |
| Mandatory or optional | Mandatory questions are those where a wrong guess is schema-valid and wrong (direction) or where the schema needs a value the log lacks |
| Why it is asked | One line, in plain words, from the rule that raised it |
| Evidence | What the data or schema says, and the tier (Observed, Declared or Verified, §3.1 of the note) |
| Sample record | A real row, chosen where the options differ most (not a random one) |
| Options | Enumerated from the schema, the data or other feeds with the same schema. Each is shown **as the resulting event** |
| Escape hatches | *None of these*, *Skip for now*, *Ask me later* |
| Consequence of skipping | What the translation will assume, and what that rules out |

**What goes on a card, by kind** (all found without a model, `question-generator-prototype.py`):

| Kind | Options come from |
|---|---|
| direction | The two orderings of two same-typed address columns |
| required value | A constant, a feed property, a column, or what other feeds with this schema use |
| action | The schema's allowed actions (`Close`, `Deny`, `Permit`, and so on), and *drop the record* |
| invalid value | Drop and report, keep without it, keep with the raw value |
| time zone | UTC, a named zone, the feed's zone |
| protocol | The schema's list, and *Other* |

## 5. The candidate view

After the questions, the person reviews what was produced.

```text
+---------------------------------------------------------------------------------------+
|  CANDIDATE 3 of 3    produced by <model document>    ASSUMED, NOT CONFIRMED:  1         |
|                                                                                       |
|  Checked                                                                               |
|    [pass] runs over the 6 sample rows, 5 events, 1 dropped                              |
|    [pass] valid against event-logging 4.1.0                                             |
|    [pass] 4 of 4 of your answers applied (source = column 5, RESET = Close, ...)        |
|    [pass] your 1 golden event reproduces                                                |
|  Not checked                                                                           |
|    - rows beyond the 6 in the sample                                                    |
|    - what a downstream consumer does with the events                                    |
|                                                                                       |
|  Dropped records (reported to the error stream)                                         |
|    row 5  10:00:05Z  invalid address 10.2.0.256 (as you chose)                          |
|                                                                                       |
|  Values filled in that the log did not supply                                           |
|    System/Name = "Gateway"   System/Environment = "Production"   (you gave both)        |
|                                                                                       |
|  What this translation can reach outside Stroom:  nothing                               |
|                                                                                       |
|  Row by row   [ input ]  ->  [ event ]   changed since the last version are marked      |
|                                                                                       |
|        [ Approve ]     [ Ask for a change ]     [ Reject ]                              |
+---------------------------------------------------------------------------------------+
```

**Rules for the candidate view**

| Rule | Reason |
|---|---|
| **Assumed, not confirmed** items are shown at the top, not buried | Every pilot run stated its assumptions, but in a paragraph at the end |
| *Checked* and *Not checked* are always both present | A run that says only what passed implies more than it did |
| Every dropped record is listed with its reason and its source row | Three of six one-shot runs skipped records in silence |
| Filled-in values that did not come from the log are listed, and "Unknown" is flagged | Four of six runs invented `Environment` |
| Sinks (§6.5.3 of the note) are listed: functions, elements, standard XSLT routes. "Nothing" is stated when there are none | A reviewer cannot see a destination hidden in an XSLT |
| Each row shows input and event side by side, with changes since the last version marked | The person compares, and does not read XSLT |
| No score is shown without what it covers | A score of 0.95 hid the §2.3 swap |

## 6. What each answer produces

| Answer | Stored as | Used for |
|---|---|---|
| Direction | A golden event (the sample row and the chosen event) and a constraint passed to the model | Checks every future candidate for this feed |
| Required value | Feed configuration (a constant or a feed property), not text in the XSLT | Visible, reviewable, reusable across feeds with the same schema |
| Action mapping | A rule, and a golden event for one row | The same, and the choice appears in the candidate view |
| Invalid value | A rule ("drop and report"), and a golden *negative* event | The required-message check |
| Skip | An *assumed, not confirmed* marker | Shown on the candidate, blocks automatic mode |

Each is recorded with the person, the time and the model document and version that proposed it (D6). A confirmed
answer is **not asked again** for the same feed. A change to it is a new, audited decision.

## 7. Review mode and automatic mode

| | Review mode (the default) | Automatic mode (opt-in per feed, a customer trial, §6.7) |
|---|---|---|
| Mandatory questions | Asked. May be skipped, with the consequence shown | **Must be answered** before it can be enabled |
| Golden events | Optional | **Required**: at least one covering the feed |
| Assumed, not confirmed items | Shown | **Block** promotion |
| Who decides | The person approves each version | A named person enables the mode, per feed, with the decision recorded. A promotion is reversible in one action |

## 8. Edge cases

| Situation | What the panel does |
|---|---|
| The person does not know the answer | Offers to show more rows where the options differ, or to mark it *unknown* and assume nothing. It never guesses silently |
| The sample is too small to show the difference between options | Says so, and asks for more rows or a golden event from the feed owner |
| Two answers conflict | Shows both, with the rows each was based on, and asks which holds |
| The model cannot satisfy an answer | Reports that, and does not substitute its own choice |
| The question count is large | Orders by consequence, batches related questions, asks at most a set number per round, and says how many remain |
| A feed with the same schema already has an answer (§6.4.4 condition 4) | Offers it as an option, marked as coming from another feed, and requires confirmation |

## 9. Measures for the trial

So the customer can report whether it works (§6.7): questions asked, answered, skipped and re-asked; how often a
candidate was approved, changed or rejected; how often a confirmed answer was later changed; golden events held per
feed; dropped records reported against silent ones found later; and how long a review took.

## 10. Open questions

1. How many questions will a person tolerate per round? The pilot had 4 to 9 per run, and the limit should be tested
   with real users.
2. Where does it live: the Stroom UI, an MCP client, or both? The same card can be text, and the pilot used text.
3. Which questions are mandatory is a decision, and the list in section 4 is a draft. Who owns it?
4. Does a feed owner or a pipeline author answer? The card needs to say who may, and the audit needs to record it.
5. How are answers shared between feeds with the same schema, and with whose permission (question 14 in the note)?
6. The synonym table (`BLOCK` to `Deny`) decides questions silently. Who owns it, and is every use shown?
7. Layout and UI components: after reading `stroom-gwt/UI-GUIDE.md`.
