# Golden events: pilot case 01 (firewall)

**Do not show this file to the model under test.** The model is given `input/` and the schema only.

These are the known-correct answers (see §2.4 of the design note). They are executable: `tools/check-golden.xslt`
checks each line of the table below against a candidate's output. Each event is found by its **event time** (as a
UTC `xs:dateTime`), not by position.

## The input records and what each must produce

| # | Input (parsed record) | Golden event | Why it is in the set |
|---|---|---|---|
| 0 | `# fw01 firewall log started 2026-11-04` | **No event** (N1) | A comment or header line |
| 1 | `10:00:01Z DROP src=10.0.0.5 dst=203.0.113.9 TCP 51514 -> 443` | `Network/Deny`; **Source** `10.0.0.5`:51514, **Destination** `203.0.113.9`:443, TCP, logging device `fw01` (G1.1 to G1.7) | **The swapped-address case.** A swap is still schema-valid and passes every structural check (§2.3) |
| 2 | `10:00:02Z ACCEPT src=2001:db8::5 dst=2001:db8::9 UDP 5353 -> 53` | `Network/Permit`; IPv6 addresses in the right places; UDP; ports 5353 and 53 (G2.1 to G2.4) | IPv6, and an action that is not `DROP` |
| 3 | `10:00:03Z DROP src=10.0.0.7 dst=- TCP 40000 -> 22` | Event kept, source `10.0.0.7`, **no Destination address**, and `-` is not output as an address (G3.1, G3.2) | `-` means "no value" |
| 4 | `10:00:04Z DROP src=10.0.0.8 dst=203.0.113.20 ICMP, sport=- dport=-` | `TransportProtocol` ICMP and **no `Port` element** (G4.1) | ICMP has no ports |
| 5 | `11:00:05+01:00 DROP ... proto=GRE` | Event time **10:00:05 UTC**; `TransportProtocol` = `Other` (G5.1, G5.2) | An offset timestamp, and a protocol outside the schema's list (TCP, UDP, ICMP, IGMP, Other) |
| 6 | `10:00:06Z DROP src=10.0.0.999 ...` | **No event** with `10.0.0.999` in it, and the problem reported (N2) | An invalid address. The schema does not stop an address string like this, and it must not be recorded as one |
| 7 | `10:00:07Z fw01 garbled~~~~` | **No event** (N3) | An unparseable line must not become an event |

**Whole-output checks.** Exactly **5 events** (N4). Every event has a non-empty `TypeId` (C1), and the same action
always gets the same `TypeId` (C2).

**Deliberately not fixed by the goldens** (so a good alternative is not marked wrong): the value of `TypeId`, the
`Generator` and `System/Name`, element order, and whitespace. They are checked for presence and consistency only.

## The full event for record 1 (the one an exact comparison would use)

```xml
<Event xmlns="event-logging:3">
  <EventTime><TimeCreated>2026-11-04T10:00:01.000Z</TimeCreated></EventTime>
  <EventSource>
    <System><Name>Firewall</Name><Environment>Production</Environment></System>
    <Generator>Firewall</Generator>
    <Device><HostName>fw01</HostName></Device>
  </EventSource>
  <EventDetail>
    <TypeId>NET_DENY</TypeId>
    <Network>
      <Deny>
        <Source>
          <Device><IPAddress>10.0.0.5</IPAddress></Device>
          <TransportProtocol>TCP</TransportProtocol><Port>51514</Port>
        </Source>
        <Destination>
          <Device><IPAddress>203.0.113.9</IPAddress></Device>
          <TransportProtocol>TCP</TransportProtocol><Port>443</Port>
        </Destination>
      </Deny>
    </Network>
  </EventDetail>
</Event>
```

This is schema-valid (checked). An exact comparison against it is brittle, so the pilot uses the assertions above.

## Decision points: what the system should ask about, and the intended answers

For the **"ask" arm** (see the README). A person (you) plays the user and answers from the "intended answer"
column. Options are the ones the system could enumerate **without a model**: from the schema, the data, or the
candidate mappings. Count how many of these the model asks about unprompted.

| # | Decision | Where the options come from | Options | Intended answer |
|---|---|---|---|---|
| D1 | What does `DROP` become? And `ACCEPT`? | The schema's network actions: `Bind`, `Connect`, `Open`, `Close`, `Send`, `Receive`, `Listen`, `Permit`, `Deny`, `Data` | (a) Deny (b) Close (c) other. For ACCEPT: (a) Permit (b) Connect | `DROP` is **Deny**, `ACCEPT` is **Permit** |
| D2 | Which address is the source? | Two IPv4 fields and two elements that accept IPv4 (§6.3 ambiguity rule) | (a) `src` is the source (b) `dst` is the source | (a) |
| D3 | `dst=-`: what should be output? | The data (a missing-value marker) | (a) omit the Destination (b) output `-` (c) drop the record | (a) |
| D4 | How is `11:00:05+01:00` recorded? | The data (an offset timestamp) | (a) convert to UTC `10:00:05Z` (b) keep the offset | (a) |
| D5 | `proto=GRE`: what is recorded? | The schema's list: TCP, UDP, ICMP, IGMP, Other | (a) `Other` (b) drop the record | (a) |
| D6 | `System/Environment` is required and is not in the log | The schema (`Environment` is required: `minOccurs` 1 in `SystemComplexType`) | (a) a constant such as `Production` (b) a feed property (c) other | (a) `Production` |
| D7 | `10.0.0.999` is not a valid address | The data, and a check against the address pattern | (a) drop the record and report it (b) output it anyway (c) output the event without the address | (a) |
| D8 | A comment line or an unparseable line | The data (no `action` field) | (a) no event, and report the unparseable one (b) emit an event with what there is | (a) |

D2 is the one that matters most. A model that quietly assumes `src` is the source, without asking, is right here,
but would be wrong on a feed where the naming is the other way round, so the question is about the **behaviour**
of asking, not just the answer.
