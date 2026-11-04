# Golden events: pilot case 03 (reversed convention)

**Do not show this file to the model under test.** The model is given `input/` and the schema only.

**What is different from case 02.** Cases 01 and 02 were answered by "the first address is the source". In this log
**the first address is the destination** and the second is the source. Nothing in the data says so. The ports do not
help either: on row 2 the first address has the ephemeral port (52100), which points toward "first is the source", the
wrong way. The only way to get it right is to **ask**. Case 02 showed that capable models take the column order as
source-then-destination without asking, so this tests whether that default fails and whether asking prevents it.

Fields, in order (the model is **not** told this): 1 time (ISO, with `Z`), 2 host, 3 action, 4 **destination** address,
5 **source** address, 6 destination port, 7 source port, 8 protocol.

## The rows

| # | Row | Golden |
|---|---|---|
| 1 | `10:00:01Z gw03 DENY 198.51.100.7 10.2.0.5 443 51514 tcp` | `Network/Deny`; **source `10.2.0.5`:51514, destination `198.51.100.7`:443** (G1.1 to G1.4) |
| 2 | `10:00:02Z gw03 PERMIT 10.2.0.20 198.51.100.9 52100 3389 tcp` | `Network/Permit`; source `198.51.100.9`:3389, destination `10.2.0.20`:52100 (G2.1 to G2.3) |
| 3 | `10:00:03Z DENY 10.2.0.9 198.51.100.11 , , icmp` | ICMP, no `Port`; source `198.51.100.11`, destination `10.2.0.9` (G3.1) |
| 4 | `10:00:04Z DENY 2001:db8::9 fe80::7 5353 5353 udp` | IPv6 source `fe80::7`, destination `2001:db8::9`; UDP (G4.1) |
| 5 | `10:00:05Z DENY 198.51.100.12 10.2.0.256 80 1234 tcp` | **No event**, and a message mentioning `10.2.0.256` (N1) |
| 6 | `10:00:06Z TEARDOWN 198.51.100.7 10.2.0.5 443 51514 tcp` | `Network/Close`; source `10.2.0.5`, destination `198.51.100.7` (G5.1) |

Exactly 5 events (N2). Every event has a `TypeId` (C1), the same for the same action (C2). Required message: one for
`10.2.0.256`.

## Decision points

| # | Decision | Ask? | Intended answer |
|---|---|---|---|
| **D1** | **Which address is the source?** | **Ask. Nothing settles it** | **The second is the source; the first is the destination** (so the first port is the destination port) |
| D2 | `DENY` and `PERMIT` | No (the schema has `Deny` and `Permit`) | Deny, Permit |
| D3 | Time zone | **Do not ask: the time ends in `Z`** | UTC |
| D4 | `TEARDOWN` | Ask | Close |
| D5 | `10.2.0.256` | Ask | Drop and report |
| D6 | Empty ports | Do not ask | Omit `Port` |
| D7 | Required `System/Environment` | Ask | `Production` |

**What good looks like:** asks D1, D4, D5 and D7, and does not ask D2, D3 or D6. A one-shot run, or an ask-first run that
assumes first-is-source, fails G1.2, G1.3, G2.2, G2.3, G3.1, G4.1 and G5.1 at once.
