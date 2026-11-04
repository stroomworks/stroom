# Pilot: rough comparison of Claude models using Claude Code

**Status:** a draft for the project owner. Cases 01 (6 runs), 02 (12 runs) and 03 (6 runs) were run on 4 November 2026: see `results/`. The checking tools were run, and
verified against a hand-written reference translation (see "What was verified" below).

**Purpose.** A rough, free-of-API-credit look at questions from Option 5 of
`../shared-modules-design-note.md`, using Claude Code on a Team seat instead of the API:

1. Can a smaller model (Haiku) produce a usable translation, and how does it compare with Sonnet and Opus?
2. Does asking the user about ambiguous decisions, with options (§2.4 of the note), catch the cases that a
   one-shot answer gets wrong or guesses?
3. Do the golden events catch errors that schema validation and "it ran" miss?

**It is a rough pilot.** One case, one run per cell, only coarse total-token figures per run (no dollar cost), and the models are driven through
a subscription, not a harness. It can shake down the case, the golden events and the checks. It cannot give the
measurements Option 5 asks for. Before building anything on scripted or automated use of a Team seat, check
Anthropic's terms. This pilot is a person using Claude Code interactively.

## What is here

```text
pilot/
  README.md                         this file
  tools/
    run-checks.sh                   runs a candidate: compile and run, schema-validate, golden events, required messages
    Validate.java                   schema validation (JAXP), used by run-checks.sh
    run-datasplitter-check.sh       runs a Data Splitter configuration over an input with no Stroom node (case 04). Needs ./gradlew
    print-classpath.init.gradle     a Gradle init script the script above uses. It changes no build file
    question-generator-prototype.py the question generator prototype (see results/question-generator-evaluation.md)
  case-01-firewall/                 named fields (src=, dst=); the first pilot, run on 4 November 2026
    input/raw.log                   the raw firewall log (synthetic)
    input/parsed-records.xml        the same log as Data Splitter would hand it to the XSLT (records:2)
    golden/golden-events.md         the golden events and decision points. DO NOT SHOW TO THE MODEL
    golden/check-golden.xslt        the executable golden events (22 checks)
    golden/required-messages.txt    messages a candidate must raise for the records it drops
    answers-do-not-show-to-model/   a hand-written reference XSLT and its output. DO NOT SHOW TO THE MODEL
  case-02-connections/              positional fields, no names: run on 4 November 2026 (12 runs)
    (same layout; 18 golden checks)
  case-03-reversed/                 positional fields where the FIRST address is the DESTINATION (6 runs)
  case-04-splitter/                 the Data Splitter half: raw CSV with a comment, a quoted comma and a quoted line break (built, not yet run on a model)
  results/                          what each run produced and how it scored
```

**The task given to the model:** write an XSLT that turns `parsed-records.xml` into event-logging v4.1.0 events.
For case 02 the prompt below changes in one place: say that each `<record>` is one row of a CSV file with **no
header**, that the `<data>` elements have **no names** (the order is the CSV column order), and that `input/raw.csv`
is the original. Do not say what the columns are. That is part of the task.
This tests the XSLT half of a translation only. The Data Splitter half (raw line to `parsed-records.xml`) is not in
this case. It can be a second case, run in Stroom's stepper, since a Data Splitter configuration needs Stroom or
the Shapeshifter Tier 1 runner to test.

## Setting up each run (important)

**Run each model in a clean directory outside this repository.** If the model can see the repository, it can read
the golden events, this note and the reference XSLT, and the result means nothing.

```bash
RUN=$(mktemp -d)                                  # a fresh directory, one per run
cp -r case-01-firewall/input "$RUN/input"
cp "<path>/event_logging_v4_1_0...data.xsd" "$RUN/event-logging-v4_1_0.xsd"
cd "$RUN"
claude                                            # then choose the model with /model, or start with --model
```

- Use a **new session for every run**, so nothing carries over.
- Models to compare: **Haiku**, **Sonnet** and **Opus** (Opus is the reference). Use `/model` to confirm which is
  active and write it down. The tiers available to a Team seat may differ from the API model IDs in §7.1.
- Record the **effort or thinking setting** if Claude Code shows one, as it changes results.
- Do not give the model any hints beyond the prompt below. Do not correct it, except as the "ask" arm allows.

The event-logging XSD is `event_logging_v4_1_0.XMLSchema....data.xsd`. It is vendored in the repository under
`content-cache/` (the path is found automatically by `tools/run-checks.sh`).

## The two arms

**Arm A: one-shot.** Give this prompt and let it work. Do not answer questions beyond "please just decide".

```text
You are writing a Stroom XSLT translation.

Input: input/parsed-records.xml. It is the output of Stroom's Data Splitter (namespace records:2): one <record>
per line of a firewall log, each a list of <data name="..." value="..."/> fields. input/raw.log is the original
log, for reference.

Output: an XSLT 2.0 stylesheet, saved as candidate.xslt, that turns each valid record into an event-logging
v4.1.0 <Event> inside an <Events Version="4.1.0"> root, namespace event-logging:3. The schema is
event-logging-v4_1_0.xsd.

Check your work by running the stylesheet over the input yourself (Saxon, xsltproc or similar if available) and
validating the result against the schema if you can. Say what you could not check.
```

**Arm B: ask first.** The same prompt plus this paragraph, and **you answer from the "intended answer" column of
`golden-events.md`** (the file stays with you, not with the model):

```text
Before you write the stylesheet, list every decision where the log or the schema is ambiguous and the answer would
change the output. For each, ask me as a numbered multiple-choice question that shows the options (including
"other"), and wait for my answers. Do not ask about anything you can settle from the schema or the data. Group
related questions and ask no more than eight.
```

Answer every question the model asks from the intended-answer column. If it asks about something not in the list,
answer sensibly and note it. **Do not volunteer an answer it did not ask for.**

## Scoring a run

Save the model's stylesheet as `candidate.xslt`, then, from this repository:

```bash
pilot/tools/run-checks.sh /path/to/candidate.xslt /path/to/results-dir [case-01-firewall | case-02-connections]
```

It prints four results, and the messages the stylesheet raised:

1. **Compile and run** over the parsed records. Failing here is a failure to produce a working translation.
2. **Schema validity** of the output against the event-logging XSD.
3. **Golden events**: 22 checks for case 01, 18 for case 02. Report the number passed and **which failed**.
4. **Required messages**: a dropped record must be reported by a message, not skipped silently. The script lists any
   expected message that is missing.

Then fill in one row of the table below per run. The three columns that matter most for the design are marked.

| Run | Model | Arm | Ran | Schema-valid | Golden (of 22 or 18) | **Failures that were schema-valid** | **Required messages missing** | **Questions asked** | **Asked about the swap (D2)?** | **Asked unnecessarily** | Iterations | Notes |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | | A | | | | | | n/a | n/a | n/a | | |
| 2 | | B | | | | | | | | | | |

- **Failures that were schema-valid** is the key column for the scoring design. A translation that is valid but
  fails a golden event is the §2.3 problem, found on a real model.
- **Questions asked**, **asked about the swap** and **asked unnecessarily** are the key columns for the "system asks"
  design (§2.4). Case 02 marks which decisions the system should *not* ask about.
- **Required messages missing** is the reporting check: a silent drop fails it.
- **Iterations** is how many times the model changed its stylesheet after running it itself.

Also write down, per run: whether the model **reported** the dropped records (N2, N3) in a message or stayed silent,
and whether it **claimed to have checked** something it had not. The design note says honest labelling is a
requirement (§6.7).

Six runs (three models, two arms) is a sensible first pass. Repeat a cell if the result is surprising: model
output varies, and one run proves little.

## What was verified before this was written

- **The reference XSLT** (`answers-do-not-show-to-model/reference.xslt`) runs under Saxon-HE 9.9.1-8 over the
  parsed records, produces 5 events, and reports the invalid-address record.
- **Its output is valid** against `event_logging_v4_1_0` (JAXP schema validation), after two schema-driven
  corrections: `System` must contain `Name` and `Environment` (both required by the schema, with `Description` and `Classification` optional), and `System` must
  precede `Generator` in `EventSource`.
- **All 22 golden checks pass** on the reference output.
- **The checks have teeth.** A copy of the reference output with the first event's addresses swapped is still
  **schema-valid** but fails G1.3 and G1.4. A copy that outputs `-` as an address fails G3.2.

**Case 02 (verified the same way):** its reference runs, produces 6 valid events and reports the two dropped rows, and
passes all 18 golden checks. A copy with row 2 swapped by the port heuristic is schema-valid but fails G2.3 and G2.4.
A copy that reads the first date as month-first fails G1.1 to G1.5.

## Limits and cautions

- **The reference was written by me, with knowledge of the golden events**, so passing proves the goldens are
  achievable, not that they are the only right answers. If a model's answer is reasonable but fails a golden
  event, look at the golden event before blaming the model. `TypeId`, `Generator` and `System/Name` are deliberately
  not fixed.
- **The case is small and synthetic.** It has one log format, eight decision points and five valid events.
- **Both arms see the same input.** If the model has seen similar firewall logs in its training, the result flatters it.
- **Data protection.** The input is synthetic. Do not substitute real logs.
- **No claim about the on-premises model.** Haiku is a proxy for a weaker model, not a prediction (§7.1).

## Case 04: the Data Splitter half

Case 04 is checked with a different script, because the thing under test is a Data Splitter configuration, not an
XSLT:

```bash
pilot/tools/run-datasplitter-check.sh candidate.ds3.xml \
    pilot/case-04-splitter/input/raw.csv \
    pilot/case-04-splitter/answers-do-not-show-to-model/expected-records.xml
```

It prints `OUTCOME`, `RECORDS`, `COVERAGE`, `ERRORS` and `MATCH` (yes or no against the expected records), and exits
0 only if the configuration compiled, raised no errors and matched. It runs `DataSplitterCheck`, a small class
added to the Shapeshifter module's test fixtures (with `TestDataSplitterCheck`), over the module's own
`DataSplitterCompiler` and `DataSplitterRunner`, so it is the same engine the learning loop uses. The first run is slow
because Gradle compiles. The prompt for this case asks for one `<record>` per row, one unnamed `<data value=...>` per
field in column order. See `case-04-splitter/golden/golden-events.md` for the decision points.
