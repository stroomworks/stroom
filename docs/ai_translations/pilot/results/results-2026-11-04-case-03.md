# Pilot results: case 03 (reversed convention), 4 November 2026

Six runs (Haiku, Sonnet, Opus; one-shot and ask-first; one rep each) against `case-03-reversed`, where **the first
address in the log is the destination**. Each stylesheet, output, messages and golden result is in `case-03/`. Scored
with `tools/run-checks.sh case-03-reversed`.

**Read this first.** One run per cell, isolation by instruction only, and in arm B I played the user and answered from
the key. The ask-first prompt also tells the model "do not ask about anything you can settle from the schema or the
data", which may push a model toward settling a question from a misleading hint (see below). Model names are Claude
Code aliases. Indicative only.

## Scores

| Model | Arm | Schema-valid | Golden (of 14) | Required message missing (of 1) | What failed |
|---|---|---|---|---|---|
| Haiku | one-shot | yes | 7 | 0 | direction: source and destination swapped on every row |
| Sonnet | one-shot | yes | 7 | 1 | the same, and the dropped record was silent |
| Opus | one-shot | yes | 7 | 0 | the same |
| Haiku | ask-first | yes | 7 | 0 | the same: **did not ask about direction** |
| Sonnet | ask-first | yes | **14** | 0 | nothing: **asked, and was told** |
| Opus | ask-first | yes | 7 | 0 | the same: **did not ask about direction** |

**Five of six runs swapped source and destination on every event, and all six outputs were schema-valid.** A reference
copy using the usual first-is-source convention fails the same 7 checks and is also schema-valid.

## What happened

- **Only one run asked, and it was the only one that got it right.** Sonnet ask-first asked "Are column 4 the source IP,
  5 the destination IP, 6 the source port and 7 the destination port?" It had listed that as its own assumption and
  still put it to me as a question. I answered that the first address is the destination and the first port is the
  destination port, and its stylesheet came out right.
- **Haiku and Opus ask-first did not ask.** Each stated the column order as a settled fact. Haiku wrote that the order
  "is supported by the data (row 2: 10.2.0.20 to 198.51.100.9, port 52100 to 3389)". That reading is wrong here:
  the first address has the ephemeral port, which is exactly the hint I built to mislead. Both then asked about
  everything else and got the rest of the translation right.
- **The ask-first arm is not enough by itself.** Two of three ask-first runs failed on the one question that mattered.
  Asking worked when it happened. Relying on the model to decide whether to ask did not.
- **Stating the assumption was not enough either, but it helped.** Every one-shot report listed "source IP, then
  destination IP" as an assumption. Sonnet one-shot even said row 1 "looks swapped (source port 443, destination port
  51514)" and followed the column order literally. A reviewer reading those reports could have caught it.
  Haiku one-shot said it had "confirmed against raw.csv", on the same misleading port evidence.
- **Everything the model should settle, it did.** The timestamp ends in `Z`, so there was no time-zone question. Haiku
  and Opus did not ask one. Sonnet raised the timestamp as a confirmation.
- **Over-asking again:** all three ask-first runs asked how `DENY` and `PERMIT` map (the schema settles it), and two
  asked about empty ports.
- **Required `System/Environment`:** asked by Sonnet and Opus ask-first, filled with "Unknown" by Haiku. In one-shot, Haiku
  used "Unknown" and Sonnet and Opus used "Production", all invented and all stated in their reports.
- **Reporting dropped records:** four runs reported the dropped record. Sonnet one-shot dropped it silently.

## What this changes

1. **The swap risk in §6.3 is real, and a golden event is the right defence.** Case 02 suggested capable models
   default correctly. Case 03 shows the default simply follows the convention, and when the convention is inverted the
   output is wrong and still schema-valid. The only check that catches it is a known answer for one record.
2. **The system should ask about direction itself, deterministically.** Two same-typed fields plus two accepting
   elements is exactly the §6.3 ambiguity rule, and it can be detected without a model. Leaving the decision to the
   model to ask gave 1 question in 3. The system should always ask, and show the candidate event for each option.
3. **Do not tell the model to settle things "from the data" without limits.** That instruction invites the model to
   resolve a real ambiguity from a misleading hint. The system should decide which questions are mandatory and
   which are the model's to skip.
4. **A stated assumption should be surfaced, not buried.** Every run said what it assumed. The review panel should lift
   direction, units and similar assumptions to the top, and ask for a golden event to confirm them.
5. **Next:** a Data Splitter case (see `case-plan-data-splitter.md`), repeats, and the API comparison for cost.
