# Question generator prototype: does it find the questions the pilot found?

`pilot/tools/question-generator-prototype.py` reads a parsed-records file and the event-logging XSD and prints the
questions a system could ask **without a model**, and the decisions it can settle from the schema or the data. It is
a throwaway prototype in Python (standard library only). It is not Stroom code, and it does not write a translation.

**What it checks, from the schema and the data**

| Rule | Source of the rule |
|---|---|
| Required schema values with no source column (`System/Name`, `System/Environment`, `Generator`, `TypeId`) | Required elements (`minOccurs` of 1 or more) of the Network event shape, read from the XSD |
| Which address is the source | Two same-typed address columns, and a schema shape with two network locations (the §6.3 ambiguity rule). Name evidence (`src`, `dst`) settles it. Otherwise it asks, and shows the event each option would produce |
| Time zone | A timestamp with no zone asks. A `Z` or an offset settles it |
| Date order | A value with day above 12 (or month above 12) settles it. Otherwise it asks |
| Action values | An exact match to the schema's network actions settles it. A small synonym table (`BLOCK` to `Deny`, `ALLOW` to `Permit`) is a confirmation. No match asks, with the schema's actions as the options |
| Protocol | A value outside the schema's list asks (`Other` or drop) |
| Invalid values | A value that looks like an address and is not valid asks (drop, omit or keep) |
| `-` placeholders | Asks once, for all the columns that use it |
| Rows with the wrong number of fields | Asks (drop and report, or emit what there is) |
| Empty values in optional fields | Settled: omitted |

## Result against the decision points written by hand

| Case | Questions I wrote by hand | Questions generated | Match |
|---|---|---|---|
| 02 (positional, day-first dates) | direction, time zone, `RESET`, invalid address, truncated row, required values (6) | the same six | **exact** |
| 03 (reversed) | direction, `TEARDOWN`, invalid address, required values (4) | the same four | **exact** |
| 01 (named fields) | `-`, `GRE`, required values, invalid address, comment and garbled lines, and the offset timestamp (6) | five: no offset question | **5 of 6** |

**What it correctly left unasked:** the action names that match or have a synonym (`DENY`, `PERMIT`, `BLOCK`, `ALLOW`,
`ACCEPT`, `DROP`), date order where one value proves it, time zone where every time has a `Z`, empty ports, and
direction where the columns are named `src` and `dst`.

**The one difference** is case 01's offset timestamp (`11:00:05+01:00`). The generator says the offset settles it
(convert to UTC, as the schema needs). I had listed it as a decision to ask. The generator is right and my hand-written
list over-asked.

**Every failure in the pilot is on a generated list.** The one-shot failures were `RESET` mapped to `Deny` or skipped,
an invalid-address record kept, the required `Environment` filled with "Unknown", and source and destination swapped in
case 03. Each corresponds to a question the generator would have asked. The other failure type, dropped records not
reported, is not a question and is covered by the required-messages check.

## What this does not show

- **It was tuned on these three cases.** I wrote the rules after reading them, so a good match is expected. There is no
  held-out test. The honest next step is to run it on new logs it has not seen.
- **The type detection is crude.** A few regular expressions decide whether a column is an address, a port, a time or an
  enumeration. A real system needs the pattern library of §6.3 and a catalogue of other feeds (§6.4), not this.
- **The synonym table is a decision.** It makes `BLOCK` mean `Deny` without asking. Who owns that table is an open
  question (question 10 in the design note).
- **One target shape only (Network).** The schema reading is generic, but the rules assume network events.
- **It asks a question and does not make one good.** How the question is shown, and what the person does with the
  answer, is the review panel (`../../review-panel-design.md`).

## A bug worth recording

The first version of the schema walker reported fields such as `System/Tag` and `AccessZone` as required. It had
descended into an optional `Door` element nested inside `EventSource`, and treated that element's required children as
the parent's. Reading the schema this way also showed that `System/Environment` is required outright, and my earlier
notes had said it was one of a group of alternatives. The notes are corrected.
