# Golden events: pilot case 02 (connection log, positional fields)

**Do not show this file to the model under test.** The model is given `input/` and the schema only.

**What is different from case 01.** The records have **no field names and no header**: each `<data>` has only a
`value`, in a fixed order. Which address is the source is **not stated anywhere**, and the ports point the wrong way
on one record. This is the swap-risk case from §6.3 of the design note, made real. Case 01 named its fields `src` and
`dst`, so nobody had to ask.

The fields, in order (the model is **not** told this): 1 time (`dd/MM/yyyy HH:mm:ss`, no zone), 2 host, 3 action,
4 first address, 5 second address, 6 first port, 7 second port, 8 protocol.

## The records and what each must produce

| # | Row | Golden | Why it is in the set |
|---|---|---|---|
| 1 | `04/11/2026 10:00:01,fw02,BLOCK,10.1.0.5,198.51.100.7,51514,443,tcp` | 4 Nov 10:00:01 UTC; `Network/Deny`; source `10.1.0.5`:51514, destination `198.51.100.7`:443; TCP; `fw02` (G1.1 to G1.5) | The ordinary case. The first port is ephemeral, the second is a service port, so it looks like (first = source) |
| 2 | `13/11/2026 09:30:00,fw02,ALLOW,198.51.100.9,10.1.0.20,3389,52100,tcp` | 13 Nov 09:30:00 UTC; `Network/Permit`; **source `198.51.100.9`:3389, destination `10.1.0.20`:52100** (G2.1 to G2.4) | **The trap.** The first port is a well-known service (3389) and the second is ephemeral, so a port-based guess puts the source on the second address. The intended answer is **the first address is the source**. The `13` also proves the date is day-first |
| 3 | `04/11/2026 10:00:03,fw02,BLOCK,10.1.0.7,10.1.0.8,40000,40001,udp` | Source `10.1.0.7`:40000, destination `10.1.0.8`:40001; UDP (G3.1) | Both ports ephemeral and both addresses private: **no hint at all** |
| 4 | `04/11/2026 10:00:04,fw02,BLOCK,10.1.0.9,198.51.100.11,,,icmp` | ICMP with **no `Port` element** (G4.1) | Empty ports (not a dash) |
| 5 | `04/11/2026 10:00:05,fw02,BLOCK,fe80::1,ff02::fb,5353,5353,udp` | IPv6 source `fe80::1`, destination `ff02::fb`; UDP (G5.1) | IPv6 link-local and multicast |
| 6 | `04/11/2026 10:00:06,fw02,BLOCK,10.1.0.256,...` | **No event** with `10.1.0.256`; and a **message** reporting it (N1, R) | An invalid address |
| 7 | `04/11/2026 10:00:07,fw02,BLOCK` | **No event**; and a **message** (N2, R) | A truncated row (3 fields, not 8) |
| 8 | `04/11/2026 10:00:08,fw02,RESET,10.1.0.5,198.51.100.7,51514,443,tcp` | `Network/Close`, source and destination kept (G6.1) | `RESET` is neither `Permit` nor `Deny`. The schema's other network actions are Bind, Connect, Open, Close, Send, Receive, Listen, Data |

**Whole output.** Exactly 6 events (N3). Every event has a non-empty `TypeId` (C1), and the same action the same
`TypeId` (C2). `TypeId`, `Generator`, `System/Name`, `Environment` and element order are not fixed.

**Required messages** (`golden/required-messages.txt`, checked by `tools/run-checks.sh`): the stylesheet must raise
at least one message mentioning `10.1.0.256`, and one for the truncated row. A silent skip fails.

## Decision points: what the system should ask, and what it should not

For the ask-first arm. You play the user and answer from the "intended answer" column. Count questions asked **and
questions asked unnecessarily** (those marked "do not ask").

| # | Decision | Ask? | Options the system can list without a model | Intended answer |
|---|---|---|---|---|
| D1 | `BLOCK` and `ALLOW` | Do not ask: the schema has `Deny` and `Permit` | n/a | `BLOCK` is Deny, `ALLOW` is Permit |
| **D2** | **Which address is the source?** | **Ask.** Nothing in the data settles it, and the ports on row 2 point the other way | (a) the first address is the source (b) the second is (c) decide by port number | **(a) the first** |
| D3 | Date order: `04/11/2026` | **Do not ask.** Row 2 has `13/11/2026`, which cannot be month-first | n/a | Day first |
| D4 | Time zone: the log has none | **Ask** | (a) UTC (b) a named zone (c) the feed's configured zone | (a) UTC |
| D5 | `RESET` | **Ask.** It is not in the log's `BLOCK`/`ALLOW` pair | The schema's network actions: Bind, Connect, Open, Close, Send, Receive, Listen, Permit, Deny, Data; or drop the record | **Close** |
| D6 | Empty ports on the ICMP row | Do not ask: an empty value is absent | n/a | Omit `Port` |
| D7 | `10.1.0.256` | **Ask** (as in case 01) | (a) drop and report (b) keep with the value in a `Data` element (c) keep without the address | (a) drop and report |
| D8 | A 3-field row | **Ask** | (a) drop and report (b) emit what there is | (a) drop and report |
| D9 | `System/Environment` is required and not in the log | **Ask** | (a) a constant such as `Production` (b) a feed property | (a) `Production` |

**What good looks like:** it asks D2, D4, D5, D7, D8 and D9 (six questions), and does **not** ask D1, D3 or D6. A model
that asks D3 has not noticed that row 2 settles it. A model that does not ask D2 and gets it right has guessed. A model that
does not ask D2 and decides by port number fails G2.3 and G2.4, and its output is still schema-valid.
