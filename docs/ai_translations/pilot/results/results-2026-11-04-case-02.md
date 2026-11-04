# Pilot results: case 02 (connection log, positional fields), 4 November 2026

Twelve runs of Claude Code sub-agents against `case-02-connections`: Haiku, Sonnet and Opus, each on arm A (one-shot)
and arm B (ask first), two repeats per cell. Each stylesheet, its output, its messages and its golden result are in
`case-02/`. Scored with `tools/run-checks.sh case-02-connections`.

**Read this first.** Two runs per cell on one small case. Isolation was by instruction only. In arm B **I played the
user and answered from the answer key**, so a perfect arm B score mostly shows that the model asked the right questions
and that the key is right. It does not show that the model is better. The model names are Claude Code aliases and I
could not see the versions. Treat everything below as indicative.

**A correction to the goldens, made after the first scoring.** My first checks required `Port` directly under
`Source`/`Destination` and the host in `Device/HostName`. The schema also allows `Device/Port` and `Device/Name`, and two
runs used those. That was my over-specification, not their error. The checks now accept either placement
(`Source//Port`, and `HostName` or `Name`). Case 01 was re-scored with the same relaxation and **none of its scores
changed**. The scores below are the relaxed ones.

## Scores

| Run | Model | Arm | Schema-valid | Golden (of 18) | Required messages missing (of 2) | What failed |
|---|---|---|---|---|---|---|
| 1 | Haiku | A, rep 1 | yes | 16 | 1 | RESET skipped (G6.1); wrong event count (N3) |
| 2 | Haiku | A, rep 2 | yes | **18** | 0 | nothing |
| 3 | Sonnet | A, rep 1 | yes | 16 | 2 | kept the invalid-address record (N1, N3); silent drops |
| 4 | Sonnet | A, rep 2 | yes | 16 | 2 | kept the invalid-address record (N1, N3); silent drops |
| 5 | Opus | A, rep 1 | yes | 16 | 0 | RESET mapped to `Deny` (G6.1); inconsistent `TypeId` (C2) |
| 6 | Opus | A, rep 2 | yes | 16 | 0 | RESET mapped to `Deny` (G6.1); inconsistent `TypeId` (C2) |
| 7 | Haiku | B, rep 1 | yes | **18** | 0 | nothing (two self-fixes after schema failures) |
| 8 | Haiku | B, rep 2 | yes | **18** | 0 | first submission was schema-**invalid** (see below) |
| 9 | Sonnet | B, rep 1 | yes | **18** | 0 | nothing |
| 10 | Sonnet | B, rep 2 | yes | **18** | 0 | nothing |
| 11 | Opus | B, rep 1 | yes | **18** | 0 | nothing |
| 12 | Opus | B, rep 2 | yes | **18** | 0 | nothing |

(The run numbers are for this table only. The folders are named `c2-<model>-<arm>-<rep>`.)

**One-shot: 1 run in 6 was perfect. Ask-first: 6 in 6 were perfect.** Every one-shot failure was a decision the model
was never asked about and made reasonably by itself:
- **RESET:** Opus mapped it to `Deny` (twice), Haiku skipped the row (once). The intended answer is `Close`.
- **The invalid address `10.1.0.256`:** Sonnet kept the record without the address (twice, with the bad value in a
  `Data` element). The intended answer is to drop it and report it. Opus and Haiku dropped it and reported it.
- **Silent drops:** Sonnet's one-shot runs raised no messages at all.

**The strongest model did not do better one-shot.** Opus failed the same two checks in both reps. Haiku's rep 2 was the
only perfect one-shot run. Different models made different reasonable choices. What separates them is that nobody asked.

## The swap trap did not catch anyone

**No model, in either arm, fell for the port-based guess.** All twelve put the first address as the source on row 2
(source port 3389, destination port 52100), even one-shot, even Haiku. They took the column order as source then
destination. The trap that case 02 was built to spring did not discriminate between these models.

Whether the models *asked* about it (arm B):

| Run | Asked which address is the source? |
|---|---|
| Opus rep 1 | **Yes, explicitly**: noticed port 3389 and asked whether row 2 is a reply that should be swapped |
| Haiku rep 2 | Yes, as a confirmation ("confirm that field 4 is the source") |
| Sonnet rep 1 | Partly: asked how to map the ports, and flagged that row 2 looks like a server reply |
| Haiku rep 1, Sonnet rep 2, Opus rep 2 | No: stated the column order as an assumption (Opus rep 2 invited correction) |

So three of six asked or flagged it, and all twelve were right. The honest reading is that for models like these,
"first address then second" is the default convention, and the swap risk (§6.3) is real only where the convention
does not hold. A case where it does not hold (the first address is the *destination*) would test it. The answer key
here said "first", so a model that assumed the opposite convention would fail.

## Ask-first: what was asked, against the decision points

| Decision | Should it be asked? | Asked by (of 6) |
|---|---|---|
| D1 `BLOCK`/`ALLOW` mapping | No (the schema settles it) | **6**: all asked, usually as a confirmation |
| D2 which address is the source | Yes | 3 (one explicit, one as a confirmation, one partly) |
| D3 date order | No (row 2 settles it) | 0, apart from one that bundled the format into its time-zone question |
| D4 time zone | Yes | 6 |
| D5 `RESET` | Yes | 6 |
| D6 empty ports | No | 3 (Haiku rep 2, Sonnet rep 1 and 2) |
| D7 `10.1.0.256` | Yes | 6 |
| D8 the 3-field row | Yes | 6 |
| D9 required `System/Environment` | Yes | **2** (both Opus). Haiku and Sonnet used the placeholder "Unknown" |

- **They asked what the data could not settle** (D4, D5, D7, D8: all six every time), **and they did not ask about the date
  order**, which row 2 settles. That is the behaviour §2.4 wants.
- **They over-asked on D1**, which the schema settles. Most framed it as a confirmation, so the cost was small, but all six did it.
- **Only Opus asked for the required `Environment`.** The four others filled it with "Unknown". Haiku rep 2's first
  stylesheet left out `System` and `Generator` entirely and was **schema-invalid**, because the answer I picked from
  its own options said "no `Generator`". It asked me for values only after validating, and then fixed it. Haiku rep 1
  and Sonnet rep 1 and 2 caught the missing `System` by validating and added "Unknown" themselves.
- **Extra questions** (not in the answer key): the future-dated row 2 (four runs asked), `Outcome`, `EventId`,
  `Description` and the raw action. These cost little, but they are why question counts ran from 6 to 9.

## Other observations

- **Reporting dropped records** was the clearest one-shot weakness. In arm B every run reported them, because the answer
  said to. One-shot, Opus reported both reps, Haiku one rep fully, and Sonnet neither.
- **Every model invented constants** (`System/Name`, `Generator`, `TypeId`) and said so in its report. I saw no report
  claim a check that was not done.
- **Self-checking worked.** Every run wrote its own schema validator and fixed its own schema errors. That is why all
  twelve final outputs are schema-valid. The golden events caught the rest.
- **Rework:** one-shot stylesheets needed 0 or 1 changes after running. Sonnet rep 2 (ask-first) needed three small ones.
- **Rough cost:** total tokens per sub-agent were about 56k to 89k (Haiku 71k to 89k, Sonnet 56k to 61k, Opus 59k to 67k),
  over 25 to 160 seconds. Haiku used the most. These are totals, with no input/output split, and no dollar figure.
- **Isolation:** three runs (Opus rep 1 and 2 one-shot, Sonnet rep 2 ask-first) wrote a helper validator outside their
  working directory (one deleted it afterwards, as it reported). Nobody reported reading outside the directory, but I
  cannot show that none did.

## What this changes

1. **The ask-first design (§2.4) is supported again, with a better-defined benefit.** It resolves the decisions a
   one-shot model gets "wrong" only because the user never said what they wanted: `RESET`, invalid records, reporting.
2. **The swap-risk story needs a harder case.** This one showed that capable models default to "first is source". The
   value of asking about it is unproven here.
3. **Required fields with no source value are the most-missed question.** Four of six asked-first runs did not ask
   for `Environment`. The system should know the schema's required fields and ask for them every time (§6.3, §6.4).
4. **Drop reporting should be built in**, not left to the prompt: it is a required-messages check now.
5. **Next:** a case where the convention is inverted; a Data Splitter case; and the API comparison for repeats and cost.
