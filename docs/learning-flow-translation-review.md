# Learning Flow: review of the translations it learned

*Written 26 November 2026 · code changes proposed 27 November 2026 · branch `ai_translations_server`*

This report looks at the translations Learning Flow learned for the test data sets, and asks how good they
are: does each event say what the source record says, and where it doesn't, why not. It ends with the
changes to Learning Flow itself that would have caught each class of problem, and
[where in the code each change would be made](#proposed-code-changes).

## How the review was done

- **The data.** Each data set in
  `stroom-learning-flow/stroom-learning-flow/src/testFixtures/resources/stroom/learningflow/scenario/`
  was loaded into the dev instance as its own feed, named `<DATA-SET>-SP`, with its own Smart Pipeline, in
  the Explorer folder **Smart Pipeline**. For data sets with drift variants (gateway-audit, netfilter) and
  for `otel-data/`, only the first file had been loaded when the translations were reviewed. Later on
  26 November the gateway-audit drift files (streams 2233, 2236 and 2238) and netfilter drift-1 and
  drift-3 (streams 2240 and 2242) were also loaded. netfilter was relearned from drift-3 and the new
  translation approved at 14:03, so the netfilter parser and XSLT reviewed below are the earlier ones;
  see [netfilter](#netfilter-permit-or-deny-guessed-from-the-rules-name).
- **The pipelines.** Every pipeline is `Source → Smart Pipeline → Schema Filter (EVENTS) → Record Output
  Filter → Record Count Filter → XML Writer → Stream Appender (Events)`, all learning under the one
  Learning Flow document `Learning Flow`, with the model set in the `LLM Model` document in the same
  folder.
- **The review.** A Claude Fable model read every learned parser and XSLT, compared them with the source
  files and the hand-written reference answers in the same folder (`*.ds3.xml`, `*.xsl`, `*.events.xml`),
  and read a sample of the `Events` output of each feed. The examples quoted below were then re-read from
  the instance to confirm them.
- **Scope.** The review was done in two passes. The first covered the 12 data sets that had learned a
  translation by then. The second covered `netfilter`, `otel-traces` and `windows-security`, which
  finished later and were approved by a person before being reviewed. The sixteenth, `auditd-ausearch`,
  never learned a translation; [Why auditd-ausearch stopped](#why-auditd-ausearch-stopped) explains
  why.
- **Approval.** The Learning Flow document is in review mode, so every learned translation waited as a
  draft until a person approved it. All 15 were approved, including the two rated Poor below, so they
  are all serving.
- **Not checked.** The reviewer did not validate the output against the event-logging schema. Every
  served stream does pass through the pipelines' Schema Filter, so invalid events would show as errors,
  but a schema-valid event can still say the wrong thing, which is what this review is about.

Nothing in Stroom or the repository was changed by the review.

## Summary

| Data set | Verdict | In one line |
|---|---|---|
| records-jsonl | **Poor** | Writes no events at all: every record is declared skipped |
| gateway-audit | **Poor** for one batch | The user is lost from 9 of 10 records in the second batch |
| otel-traces | **Poor** | One event per span, but trace links, durations, status and most attributes are lost |
| netfilter | Acceptable with gaps | Decides Permit or Deny from the rule's name; most packet detail is free text |
| windows-security | Acceptable with gaps | Good for the three sampled event IDs; anything else leaks out as stray text |
| auditd | Acceptable with gaps | One event per log line, so a command and the person who ran it are in different events |
| syslog | Acceptable with gaps | Only two sshd messages are recognised; anything else vanishes |
| events-fragments | Acceptable with gaps | Windows event IDs other than the three in the sample are not handled |
| apache-access | Good | A failed login's claimed user is recorded as if they made the request |
| fixed-width | Good | The parser isn't really fixed-width; unknown actions become `Logon` |
| csv-multiline | Good output | The parser breaks on empty or quoted-comma fields |
| csv-mixed, nested-audit, nested-entries, records-json | Good | Small points only |
| auditd-ausearch | — | Learned nothing: the learner was given up (see below) |

Every Poor result was **promoted**: Learning Flow judged it good enough to serve, and a person then
approved it. That makes them the most important findings for the feature itself, more than for the data
sets.

## The Poor results

### records-jsonl: every record skipped

**What happens.** The feed `RECORDS-JSONL-SP` produces no events. Its error stream says:

```
WARN: Ambiguous rule match for /map/map[1] Matches both "…/map/map" on line 101 and
      "…/map/map[(…string[@key='kind']…) = "login"]" on line 17
…   (the same for all 12 records)
ERROR: Bound to fragment records-jsonl-sp-pipeline learned, which produced nothing for this stream
INFO: 0 event(s), 12 record(s) declared skipped by the translation:
      Record kind is neither login nor logout, so it cannot be translated
```

**Why.** The learned XSLT has a template for login records, one for logout records, and a catch-all:

```xml
<xsl:template match="/map/map[string[@key='kind'] = 'login']"> … </xsl:template>
<xsl:template match="/map/map[string[@key='kind'] = 'logout']"> … </xsl:template>

<!-- Any other kind of record is not understood -->
<xsl:template match="/map/map">
  <Skipped xmlns="learningflow:skips" reason="Record kind is neither login nor logout, …"/>
</xsl:template>
```

In XSLT a pattern with a predicate does *not* automatically outrank one without. All three templates have
the default priority of 0.5, so every login record matches two templates equally. Saxon warns
("Ambiguous rule match") and picks the last one in the stylesheet, which is the catch-all. Every record is
therefore skipped, including records that are plainly logins.

**The fix** is one attribute:

```xml
<xsl:template match="/map/map" priority="-1">
```

**Why Learning Flow let it through.** The records the translation was accepted on show the contradiction
in plain sight: a record with `"kind": "login", "outcome": "success"` is stored with the output
`Skipped … neither login nor logout`. Nothing checks that a skip reason fits the record, nothing counts
how many records were skipped, and the Saxon warning was not treated as a fault. See items 1 and 2 under
[What Learning Flow should change](#what-learning-flow-should-change).

### gateway-audit: user lost when the source format changes

The gateway export has two batches. The second comes from a node with a newer format
(`schemaVersion="3.1"`), which the file's own comment describes: *"the second batch (schemaVersion 3.1)
puts the user on attributes rather than child elements"*.

**Batch 1 (format 3.0): correct.**

```xml
<authentication seq="88410" … outcome="success">
  <user>
    <id>u-10234</id>
    <name>alice.ng</name>
    …
```
becomes
```xml
<User><Id>alice.ng</Id></User>
```

**Batch 2 (format 3.1): the user is lost.**

```xml
<transfer seq="51201" … direction="upload" outcome="success">
  <user id="u-50310" name="d.reyes" displayName="Daniel Reyes" domain="CORP-US"/>
```
becomes
```xml
<User><Id/></User>
…
<Description>… user  (, id , domain ); session S-n-00a1; …</Description>
```

The XSLT reads only the child element `user/name`, never the attribute `user/@name`. This affects 9 of
the 10 records in the batch, including a failed logon for `administrator`. Who did something is the
first thing an audit event must record, so this is a serious gap.

**Other problems in the same translation:**

- *Admin action with neither actor nor target.* Seq 51207 (`revokeRole`) becomes an `Update` whose
  `Before` and `After` are identical and empty:
  ```xml
  <Update>
    <Before><Object><Type>User</Type><Id/><Name/></Object></Before>
    <After><Object><Type>User</Type><Id/><Name/></Object></After>
  ```
- *A session ID written as the user.* On seq 51209 (a logoff) the template's fallback chain reaches
  `sessionId`, giving `<User><Id>S-n-00b7</Id></User>`.
- *The reason for a denial is dropped.* Seq 51204 has
  `<deniedBy rule="R-EXT-014">Folder shares to external domains need Legal approval</deniedBy>`, which
  appears nowhere in the event.
- *A diagnostic message becomes an event.* Seq 51202, a warning from the DLP engine with no user, is
  translated as a `View` of an object of type `diagnostic`. The source file itself lists it as not an
  event.
- *Site in the wrong element.* `Environment` holds `LON` or `NYC`, which belong in `Device/Location/Site`.
- *Description used as a dump.* Every field is also concatenated into `Description`; one is about 2,500
  characters and repeats what the structured elements already say.

**Better.** Read the user from either shape, everywhere it is needed:

```xml
<xsl:function name="local:u" as="xs:string">
  <xsl:param name="u" as="element()?"/>
  <xsl:param name="f" as="xs:string"/>
  <xsl:sequence select="string(($u/*[local-name() = $f], $u/@*[local-name() = $f])
                               [normalize-space(.) != ''][1])"/>
</xsl:function>
```

Leave `User` out when no name resolves rather than falling back to a session ID. Carry `deniedBy`,
`blockedBy` and `error` into `Outcome/Description` and set `Permitted` to false. Write old and new values
into `Update/Before` and `Update/After`. Declare the diagnostic `Skipped`.

**The drift files.** These were loaded after the review, and two of the three fail while serving:

- *Drift-1* (stream 2233, element names prefixed) translates: 22 events.
- *Drift-2* (stream 2236, a namespace on the export) fails with `FATAL: Record export has no readable
  timestamp`, then "produced nothing for this stream".
- *Drift-3* (stream 2238, flattened) gives "0 event(s), 27 record(s) declared skipped … Export-level
  element outside any batch", then "produced nothing". It is a second case of a translation skipping
  every record, this time while serving.

Both failures error the stream; neither opens a relearning.

### otel-traces: right count, little meaning

The source is one OpenTelemetry (OTLP) JSON document of 66 spans, mostly database calls made by Stroom
itself. The translation produces one event per span with correct UTC times, so nothing is lost in count.
But every span is the same kind of event with the same `TypeId` (`OTEL-TRACE-SPAN`), and the information
that makes a trace useful is either free text or gone.

**Trace identity is only in `Description`.** The span

```json
"traceId":"741cdddc99057f22b2eb8cde21e1adc2","spanId":"03ef67add3fdc28a","parentSpanId":"700eebe16ae406e4","kind":3
```

becomes

```xml
<Description>Span Ping; traceId=741cdd…; spanId=03ef67add3fdc28a; parentSpanId=700eebe16ae406e4; kind=3; …;
  durationMs=1; scope=MySQL Connector/J 9.4.0; db.system=mysql; …</Description>
```

The schema has `EventSource/EventId` for the span's ID and `EventChain/Activity/Id` with `Parent` for
exactly this parent and child relationship; neither is used. `kind=3` is left as a number (it means a
client call).

**Other problems:**

- *Durations are rounded to whole milliseconds.* Most database spans take well under a millisecond, so
  they come out as `durationMs=0` or `durationMs=1`, which tells you nothing.
- *Span links are dropped.* 43 of the 66 spans link to another trace; none of the links reach the output.
- *Attributes are a fixed list fitted to database spans.* Only seven attributes are read (`db.system`,
  `db.statement` and so on). `server.address`, `server.port`, the table name and the application's own
  attributes (`filterId`, `lockName`) are lost, and spans that are not database calls print empty slots:
  `db.system=; db.name=; db.operation=;`.
- *Status is never read.* Every span in the sample has `"status":{}`, so the model never saw a failure. A
  failed span would come out exactly like a successful one.
- *The database login is recorded as the user.* `db.user` gives `<User><Id>stroomuser</Id></User>`,
  which is the application's database credential, not a person.
- *One bad span fails the whole document.* There is no split filter, and the time conversion is
  unguarded, so a span with a missing or malformed timestamp would fail all 66 events.

**Better.** Keep one event per span, but use the schema's identity and chain elements, read every
attribute generically into `Data`, compute durations from the full nanosecond values, and set
`Outcome/Success` from `status.code`:

```xml
<EventSource>
  <EventId>03ef67add3fdc28a</EventId>
  …
</EventSource>
…
<EventChain>
  <Activity><Id>03ef67add3fdc28a</Id><Parent><Id>700eebe16ae406e4</Id></Parent></Activity>
</EventChain>
```

```xml
<xsl:for-each select="array[@key='attributes']/map">
  <Data Name="{string[@key='key']}" Value="{map[@key='value']/*[1]}"/>
</xsl:for-each>
```

Two instrumentation libraries report the same SQL statement here, so a person approving this
translation would also want to see that most of the events are driver chatter; see item 14 under
[What Learning Flow should change](#what-learning-flow-should-change).

## The results with gaps

### auditd: one event per line, not per audit event

An auditd event is several lines sharing a serial number. Serial `20002` is one action, frank running
`grep -r secret`:

```
type=SYSCALL msg=audit(1758446116.600:20002): … auid=frank uid=frank … comm="grep" exe="/usr/bin/grep" …
type=EXECVE  msg=audit(1758446116.600:20002): argc=3 a0="grep" a1="-r" a2="secret"
type=CWD     msg=audit(1758446116.600:20002): cwd="/home/frank"
type=PATH    msg=audit(1758446116.600:20002): item=0 name="/usr/bin/grep" …
```

The learned translation turns each line into its own event: 23 events for 8 actions. The event that holds
the command has no user, and the user is in a different event:

```xml
<EventSource>… <Device><HostName>host1</HostName></Device></EventSource>   <!-- no User -->
<EventDetail><TypeId>EXECVE</TypeId>
  <Process><Action>Execute</Action><Command>grep</Command><Arguments>-r secret</Arguments></Process>
```

The working-directory line becomes an event claiming that something was viewed:

```xml
<TypeId>CWD</TypeId>
<View><Object><Type>WorkingDirectory</Type><Name>/home/frank</Name></Object></View>
```

Also: `USER_START` after a failed `USER_LOGIN` is recorded as a second, successful logon at the same
moment; the parser is one regex per record type with a fixed field order, so a line with an extra or
reordered field matches nothing; and `Environment` is the invented literal `Production`.

**Better.** Group lines by serial in the parser (the XSLT only ever sees one record at a time), read
key=value pairs generically rather than by position, and write one `Process/Execute` per serial with the
user from SYSCALL, the command from `exe`, the arguments from EXECVE, and `cwd` as `Data`. The reference
answer `auditd.ds3.xml` / `auditd.xsl` in the scenario folder does this. A generic key=value parser looks
like:

```xml
<regex pattern="^node=(\S+) type=(\S+) msg=audit\((\d+\.\d+):(\d+)\): (.*)$">
  <data name="node" value="$1"/><data name="type" value="$2"/>
  <data name="time" value="$3"/><data name="serial" value="$4"/>
  <group value="$5">
    <regex pattern="(\w+)=(&quot;[^&quot;]*&quot;|\S+)">
      <data name="$1" value="$2"/>
    </regex>
  </group>
</regex>
```

### syslog: only two messages recognised

All 20 sample lines are translated, in both syslog formats. But both regexes require the exact message
text `(Accepted|Failed) <method> for <user> from <ip> port <n>`. A line such as
`Failed password for invalid user admin from …`, `Connection closed`, or anything from a program other
than sshd matches nothing: it becomes no event, only an "unmatched content" error from the parser. For the older format (`Sep 21 09:15:00`, no year
or zone) the year is invented and UTC assumed. `System/Name` is the literal `SYSLOG-SP`.

**Better.** Parse the header and the message separately: header regexes that capture the whole message,
then message regexes inside it, ending with a catch-all so an unrecognised message still reaches the XSLT
and can be declared skipped.

### events-fragments (Windows): no fallback

The three event IDs in the sample (4624 logon, 4634 logoff, 4688 process creation) are translated well,
including converting the hex process ID (`0x1a01` → `6657`). But only those three have templates, and
there is no catch-all, so any other ID, starting with 4625 (failed logon), would fall through to XSLT's
built-in rules and write stray text into the output. No `Outcome` is set, although `Keywords` carries the
audit success flag, and only logon types 2 and 3 are mapped.

**Better.** Add `<xsl:template match="Event" priority="-1">` declaring the record skipped, add 4625, and
derive `Outcome/Success` from `Keywords`.

### netfilter: Permit or Deny guessed from the rule's name

*This describes the translation as it was reviewed. It has since been replaced by a relearning (see
[the drift files](#what-happened-to-the-drift-files) below). The current XSLT tests the prefix
case-insensitively (`DROP|REJECT|LIMIT|SCAN`), so `nft-core-drop` now gives `Deny`, and copies the packet
detail verbatim, so the `FRAG=:185` and bare `OUT` faults are gone. `Permit` is still the default for
everything else, `Environment` is still the literal `Production`, and the year and zone are still
invented.*

All 30 firewall log lines are translated into `Network` events with the right host, addresses and ports
for both IPv4 and IPv6. The problem is how the translation decides whether traffic was blocked:

```xml
<xsl:variable name="denied" select="matches($prefix, 'DROP|SCAN|LIMIT')"/>
…
<xsl:choose>
  <xsl:when test="$denied"><Deny>…</Deny></xsl:when>
  <xsl:otherwise><Permit>…</Permit></xsl:otherwise>
</xsl:choose>
```

The prefix (`FW-IN-DROP`, `FW-IN-ICMP` …) is a label an administrator chose for a logging rule, not a
verdict. So `FW-IN-ICMP`, `FW-IN-FRAG`, `FW-LAN-REDIRECT`, `FW-IN-VPN` and others are all recorded as
**permitted** traffic with nothing in the source to say so. The test is case-sensitive too, so a prefix
such as `nft-core-drop` would also come out as `Permit`. This is a security conclusion invented from a
naming habit.

**Other problems:**

- *Most of the packet is free text.* Everything except the addresses and ports goes into one
  `Outcome/Description` string, e.g. `kerneltime=1393970.694408 IN=eth0 OUT MAC=00:16:3e:… LEN=60 TOS=0x00
  …`. The schema has `TransportProtocol`, `ICMPType` and `MACAddress`, which are unused, so an ICMP or ESP
  event ends up as two bare IP addresses with nothing structured to say what it was. (`TransportProtocol`
  is an enumeration of `TCP`, `UDP`, `ICMP`, `IGMP` and `Other`, so ESP, AH, GRE or ICMPv6 would be
  `Other`, with the real protocol in `Data`.)
- *Small parsing faults.* `FRAG:185` becomes `FRAG=:185`, and an empty `OUT=` (which means the packet was
  for this host) becomes a bare `OUT`, indistinguishable from flags such as `SYN`.
- *Invented year and time zone.* `Oct 19 09:00:03` becomes `2026-10-19T09:00:03.000Z`. The drift files
  show the same hosts stamping `+01:00`, so these times are probably an hour out.
- *Invented literals.* `<Name>Linux Netfilter Firewall</Name>` and `<Environment>Production</Environment>`.

**The parser fails on realistic variants.** Running the reviewed parser's regular expression over the
drift files gives these counts of lines it does not match:

| File | Lines | Not matched | Why |
|---|---|---|---|
| `netfilter.log` (loaded) | 30 | 0 | |
| `netfilter-drift-1-iso-timestamps.log` | 30 | **30** | ISO timestamps and no kernel uptime field |
| `netfilter-drift-2-mac-decode.log` | 30 | 0 | Parsed, but the decoded MAC fields go into free text |
| `netfilter-drift-3-new-traffic.log` | 40 | **4** | Firewall `fw03`'s prefix has no colon after it |

A line that matches nothing produces no record, and the Data Splitter logs an "unmatched content" error
for it.

#### What happened to the drift files

Drift-1 and drift-3 were loaded after the review (streams 2240 and 2242):

- **Drift-1** produced no records. Serving held the stream back (deferred it) and opened a relearning:
  "The parser read no records from this stream: the translation no longer reads this feed." It is still
  held back, because the relearned parser still requires the kernel uptime field.
- **Drift-3** produced 31 events from 40 lines, not 36. Each of the four `fw03` lines took the line after
  it with it, and line 18 (`PROTO=TCP INCOMPLETE [8 bytes] ]`) was lost as well. That is below the
  Standard preset's 90% coverage gate, so the stream was held back. A relearning followed (corpus stream
  2245), and the relearned parser, with the colon after the prefix optional and the packet detail kept as
  one field, read all 40 lines (stream 2248).
- **The parser's errors were not visible to a person.** The outer error streams of these runs (2241,
  2243, 2246 and 2247) each hold only `INFO: Served by "netfilter-sp-pipeline learned"`. The fragment
  counts the parser's errors, but does not pass them on.

So no stream here shows a partial loss getting through. The gap remains in principle: a stream that loses
fewer lines than the coverage threshold allows is served, with nothing asking for a relearning (see
[C1](#c1-act-on-partial-parser-drops-when-serving-item-5)).

**Better.** Use `Deny` only when the prefix says so, case-insensitively (`drop|reject|deny`), and do not
assert `Permit` without evidence. Put the protocol, ICMP type and MAC address in their elements. The
schema requires a MAC address in upper case, so the translation must upper-case it:

```xml
<Deny>
  <Source>
    <Device><IPAddress>198.51.100.77</IPAddress><MACAddress>00:00:5E:00:01:0A</MACAddress></Device>
    <TransportProtocol>UDP</TransportProtocol><Port>41000</Port>
  </Source>
  <Destination>
    <Device><IPAddress>203.0.113.2</IPAddress></Device>
    <TransportProtocol>UDP</TransportProtocol><Port>161</Port>
  </Destination>
  <Rule>FW-IN-DROP</Rule>
  <Data Name="InInterface" Value="eth0"/>
</Deny>
```

In the parser, accept either timestamp form, make the uptime field optional, take the prefix as
everything before `IN=`, and end with a catch-all regex so an unrecognised line still reaches the XSLT.

### windows-security: good for what it saw, nothing for what it didn't

This is the best of the later three, and better than the hand-written reference `windows-security.xsl`
in several ways: it writes `DOMAIN\user`, maps eleven logon types (the reference maps two), and converts
the timestamp to UTC properly. But the sample held exactly three event IDs, five of each, and the
translation handles only those.

**No fallback for other event IDs.** There are templates for 4624 (logon), 4634 (logoff) and 4688
(process creation) and nothing else. A 4625 (failed logon), 4672 or 4720 would fall through to XSLT's
built-in rules, which write the event's text as loose characters inside `<Events>`: no event, and
probably an invalid stream.

**No `Outcome` anywhere.** Once failed logons are added, successful and failed ones could not be told
apart. The reference at least writes `<Outcome><Success>true</Success></Outcome>` for 4624.

**Identifiers in `Description` that have proper homes:**

```
Logon ID 0x3e7a0; logon process User32; authentication package Negotiate; logon type 2; record 1000
```

The record ID belongs in `EventSource/EventId`, and the logon ID in `EventSource/SessionId`, which is what
links a logon to the processes started in it and to the logoff. The new process ID belongs in
`Process/ProcessId`.

**The machine account is the user on logons.** For a 4624 the event's user is `WS01$` (the computer
account that recorded the logon) while the person is `CORP\alice` in `Authenticate/User`. For a 4634 the
person is the event's user. Searching for events by alice would find her logoffs but miss her logons.

**Better.** Add a catch-all template with a low priority, add 4625 with `Success` false and a reason,
add `Outcome` to 4624, put the record and logon IDs in `EventId` and `SessionId`, and use the target user
as the event's user with the machine account kept as `Data`. Guard client fields against Windows' `-`
placeholder.

### The results rated Good

- **apache-access.** All 35 lines translate, with the time zone handled (`+0100` → UTC) and awkward lines
  surviving. But a 401 with a claimed user (`198.51.100.23 - admin […] "GET /admin/ HTTP/1.1" 401`)
  records `admin` in `EventSource/User`, as if admin made the request. Escape sequences stay in values
  (`\"FancyBot\"`), and a TLS handshake sent to the HTTP port ends up in `URL`.
- **fixed-width.** All 30 records translate and `DENIED` gives `Success=false`. But the parser splits on
  whitespace rather than column positions (`(\S{1,8})\s*…`), so a blank column shifts every later field.
  It should use `^(\d{14})(.{8})(.{8})(.{8})(.{8})(.*)$` and trim. Any action other than LOGOFF becomes
  `Logon`.
- **csv-multiline.** All 20 records translate, with multi-line comments kept and `""` unescaped. But the
  fields are `[^,\n]+`, so an empty field, a quoted value containing a comma, or CRLF line endings would
  not match.
- **nested-entries.** Correct for the sample, but `if (did = 'logout') then 'Logoff' else 'Logon'` turns
  any other action into a logon. Its sister data set, nested-audit, correctly declares unknown actions
  skipped.
- **csv-mixed, nested-audit, records-json.** Sound. Minor placement points only (for example a session
  length written as an `Outcome/Description`, which isn't an outcome).

## Why auditd-ausearch stopped

`auditd-ausearch.log` is auditd output formatted by the `ausearch` tool: records separated by `----`
lines, with human-readable timestamps. Its pipeline learned nothing. The learner was **given up**, and
its one stream was written as an error:

```
ERROR: Given up: Candidate failed the gate Schema conformance (0.6533333333333333 of 1.0)
```

The run's turns (from the `learning_flow_turn` table, attempt 17) show that the model did produce a
working translation for the part of the stream it was shown:

| Turn | Question | Outcome |
|---|---|---|
| 1 | Choose the elements | Passed: Data Splitter, then XSLT |
| 2 | Write the parser | The configuration did not compile |
| 3 | Write the parser | It compiled but failed to run |
| 4 | Write the parser | Passed (input coverage 1.0) |
| 5 | Write the XSLT | Schema conformance short (0.59) |
| 6 | Write the XSLT | Passed |

The run spent 324,575 tokens, the most of any data set.

**What went wrong.** A candidate is learned on a sample of the stream and then judged on the whole
stream, including records the model never saw. On the whole stream only 65% of records produced events
that validated against the schema, so the conformance gate (which requires 100%) failed.

The file is 11,016 bytes. The Learning Flow document's default sample size limit is 8,192 bytes, and a
further 20% of records are held back for judging, so the model saw only the first part of the file.
Several record types appear only later in it: `SOCKADDR`, `ANOM_ABEND`, `ADD_GROUP`, `ADD_USER`,
`CONFIG_CHANGE` and `USER_LOGOUT`. An XSLT written without seeing them would plausibly produce invalid
events for them. This is the likely cause, but it could not be confirmed: the per-record feedback is not
kept on the attempt (its `evidence` column is empty).

**Why it gave up rather than trying again.** This is a gap in Learning Flow rather than bad luck. In
`Learning.learnAndBind` (`stroom-learning-flow/.../stage/Learning.java`, around line 560), a candidate
that passes on the sample but fails a gate on the whole stream gives the learner up at once:

```java
final Judged judged = Judging.asLearned(Judging.rerun(learned.chain(), input.data(), boundary(learned)),
        scorecard);
if (!judged.clearsGates()) {
    final StageRun givenUp = givenUp(doc, learner, "A gate failed", …);
    wake(learner);
    return givenUp;
}
```

Two things make this the wrong outcome here:

- A few lines earlier, the same method says that in the background worker "a session that cannot
  finish parks for a person rather than giving up … nothing is made permanent just because the budget or
  the candidates ran out". A whole-stream gate failure doesn't follow that rule.
- Learning Flow already has a mechanism for exactly this case: `settle()` runs the stream in full and,
  if records fall short, shows those records to the model for another round. But `settle()` only starts
  after a candidate has been **promoted**. A first candidate that fails on records it never saw gets no
  second round.

**To recover this pipeline**, raise *Largest sample of a stream* (Setup, Advanced options) above the
file's size, for example to 16,384 bytes, then use **Learn again** on the pipeline's Learning Status tab
and reprocess the errored stream from the pipeline's processor filter. Without a larger sample it will
probably fail the same way.

## What Learning Flow should change

Most of the problems above are not one-offs. They are classes of error that a check during learning could
catch. In rough order of value:

1. **Refuse a translation that skips most of its records, or whose skip reasons contradict the records.**
   records-jsonl was promoted with 12 of 12 records skipped, and with a stored record of kind `login`
   paired with the reason "neither login nor logout". A ceiling on the share of records skipped, plus a
   check that a skip reason doesn't name a value the record actually has, would have caught it.
2. **Treat XSLT warnings as faults.** "Ambiguous rule match" was logged 12 times and ignored. Saxon
   recovers silently by picking the last template, which is rarely what the author meant. The question
   text could also require `priority="-1"` on catch-all templates.
3. **Refuse empty identity elements.** `<User><Id/></User>` and `<Object><Id/><Name/></Object>` are valid
   against the schema but say nothing. A check for empty `Id`, `Name`, `HostName` and `IPAddress` would
   have exposed the gateway's second batch at once.
4. **Sample every structural variant.** The gateway's second batch has a different shape (attributes
   instead of child elements). The learning sample should include at least one record of each distinct
   element-and-attribute signature, not just one of each record name.
5. **Make parser drops visible.** A Data Splitter line that matches no regex never reaches the XSLT, so the
   skip mechanism can't report it (auditd, syslog, apache-access, csv-multiline). It is logged as a parser
   error, but a stream that loses fewer lines than the coverage threshold allows is still served. Either require a
   catch-all regex that passes the raw line on, or compare the number of input lines with the number of
   parsed records.
6. **Test robustness, not just the sample.** The auditd, syslog and fixed-width parsers encode the exact
   field order and message text of the sample. Perturbing held-out records (reordering a key=value pair,
   blanking a column, quoting a field) and scoring the result would favour parsers that generalise.
7. **Ask whether records belong together.** auditd lines sharing a serial are one event. A question about
   a shared correlation key before the record boundary is fixed would lead to one event per action.
8. **Never let a fallback assert a specific action.** nested-entries and fixed-width map anything
   unrecognised to `Logon`. The rule should be that an `xsl:otherwise` declares the record skipped.
9. **Don't invent context.** `Environment` was variously `Production`, `Unspecified`, `Unknown`,
   `Security` (a Windows log channel) and `LON` (a site). Time zones and the syslog year were assumed
   where the data has none. These should come from configuration (the pipeline's time zone property
   already exists for the zone) or be left out.
10. **Reward structured placement over `Description`.** If a check scores "the source value appears
    somewhere in the output", copying everything into `Description` passes it. Weighting values in proper
    elements above values found only in `Description` would discourage the dump.
11. **Don't give up when a candidate fails only on records the model never saw.** auditd-ausearch was
    given up after one candidate passed the sample and failed the whole stream. That candidate should go
    into the same rounds `settle()` runs after a promotion, with the records that fell short shown to the
    model, or at least park for a person in the worker. The per-record feedback should also be kept on
    the attempt so that a person can see which records failed.
12. **Never assert a verdict without a field that states it.** netfilter's Permit or Deny, from a
    keyword in a rule's name, is the same failure as defaulting to `Logon`, but with a security
    conclusion attached. A fallback should record the event neutrally or declare it skipped.
13. **Never let a record fall through to XSLT's built-in rules.** The Windows translation has no
    catch-all, so an unhandled event ID would leak loose text into the output. Learning Flow could check
    that every input record matched an explicit template, or add a standard low-priority catch-all
    itself.
14. **Tell the approver what the sample did not contain.** No sample here held a failed outcome (every
    otel status is empty, there are no failed Windows logons, netfilter has no explicit verdicts), so no
    translation has a failure path, and approval gave no warning. A short summary at approval time
    ("no unsuccessful outcomes in the sample"; "43 of 66 events come from one library") would be cheap
    and honest.
15. **Use the schema's identity and linkage elements.** None of the later three uses `EventSource/EventId`,
    `SessionId` or `EventChain`, although each source has natural values for them (Windows record and
    logon IDs, span and parent span IDs). A hint in the question text would improve all three.

## Proposed code changes

This section takes the list above and says where in the code each change belongs, what the code does
now, and what it should do instead. Nothing here has been implemented yet. A second reviewer checked every
reference and claim in it against the code on 27 November 2026, and the section was corrected from that
review.

Paths are relative to `stroom-learning-flow/stroom-learning-flow/src/main/java/stroom/learningflow/`
unless they begin with a module name. Line numbers were checked again against commit `9e18a95df3`
(which reworks the Supervisor's REST interface and the Learning Flow screens), plus the uncommitted
`ErrorReceiverProxy` fix in the working tree.

### Before starting: corrections to the list above

- **A parser does not drop lines without an error.** A line that no Data Splitter expression matches is
  logged as an "unmatched content" error
  (`stroom-pipeline/src/main/java/stroom/pipeline/xml/converter/ds3/DS3Parser.java:537`), unless the
  configuration sets `ignoreErrors`. Serving counts those errors into its coverage
  (`fragment/ServedStream.java:84`), but they do not appear on the stream's error stream, so a person
  does not see them (netfilter streams 2241 to 2247). A stream from which the parser reads no records at all is held back
  (deferred) and a relearning is opened (`Judging.inputShortfall`, `stage/Judging.java:146`). The gap
  is a partial loss that stays within the coverage threshold; see
  [C1](#c1-act-on-partial-parser-drops-when-serving-item-5).
- **Item 1 goes against a deliberate decision.** The class comment of `DeclaredSkips`
  (`scoring/DeclaredSkips.java:49`) says "there is deliberately no limit on how much of a stream may be
  skipped", and relies on a person reading the count instead. records-jsonl shows that the count does
  not reach the person: what the approver sees is one number per check
  ([D2](#d2-show-the-approver-what-the-numbers-dont-say-items-1-and-14)).
  [A1](#a1-refuse-a-translation-that-skips-every-record-item-1) keeps the decision for partial skips and
  refuses only the case where nothing at all is translated.
- **Related bugs.** `docs/learning-flow-possible-bugs.md` records the same faults from the serving side.
  Bug 17 (a translation that fails outright on a changed format is not treated as drift) is the serving
  half of [A1](#a1-refuse-a-translation-that-skips-every-record-item-1) and the gateway-audit drift-2 and
  drift-3 results. Bug 18 (a first candidate that fails only on unseen records gives the learner up) is
  [B1](#b1-dont-give-up-when-a-candidate-fails-only-on-unseen-records-item-11). Their fixes should be
  designed once, together.
- **Not proposed:** item 6 (scoring robustness by perturbing held-out records) and the second half of
  item 1 (checking that a skip reason does not contradict its record). Both are larger than what they
  would catch here. Item 2 is narrowed to the one warning that mattered; treating every Saxon warning as a
  fault would refuse correct stylesheets.

### Summary

| Change | Items | Lines (main + tests) | Would have caught | Main risk |
|---|---|---|---|---|
| [A1](#a1-refuse-a-translation-that-skips-every-record-item-1) Refuse a translation that skips every record | 1 | 80 + 80 | records-jsonl; gateway-audit drift-3 while serving | The candidate flag set in the wrong place |
| [A2](#a2-refuse-templates-whose-priorities-tie-item-2) Refuse templates whose priorities tie | 2 | 140 + 120 | records-jsonl | Ties the text scan cannot see |
| [A3](#a3-score-elements-written-with-no-value-item-3) Score elements written with no value | 3 | 280 + 200 | gateway-audit batch 2 | Misreading a schema's types; placeholders |
| [B1](#b1-dont-give-up-when-a-candidate-fails-only-on-unseen-records-item-11) Park rather than give up when a candidate fails only on unseen records | 11 | Step 1: 25 + 120; step 2: 150 + 150 | auditd-ausearch giving up | More sessions waiting for a person; cost of extra rounds |
| [B2](#b2-keep-the-evidence-of-every-attempt-item-11) Keep the evidence of parked and given-up attempts | 11 | 70 + 60 | (diagnosis of auditd-ausearch) | Reading rows stored before the change |
| [B3](#b3-sample-every-kind-of-record-item-4) Sample every kind of record | 4 | 230 + 150 | gateway-audit, once sampling works at record depth | The sample changing between questions |
| [C1](#c1-act-on-partial-parser-drops-when-serving-item-5) Act on partial parser drops when serving | 5 | 70 + 100 | None observed: a partial loss within the gate | Relearning noisy feeds over and over |
| [D1](#d1-question-text) Question text | 7, 8, 9, 10, 12, 13, 15 | 25 + 20 | Unmeasured: events-fragments, windows-security, nested-entries, netfilter and others, if the model follows it | No measured effect; regressions elsewhere |
| [D2](#d2-show-the-approver-what-the-numbers-dont-say-items-1-and-14) Show the approver what the numbers don't say | 1, 14 | 260 + 150 | records-jsonl (by a person), otel-traces, netfilter, windows-security | Notices nobody reads |
| **All** | | **about 1,300 + 1,150** | | |

**How the line counts were estimated.** Each count is new or changed lines, including the javadoc and
comments the project requires, from the size of the code each change touches and of similar code
already in the module (for example `CompletenessScorer` is 122 lines and `LiteralsScorer` 162). They are
estimates to compare the proposals with, not commitments: expect them to be out by as much as half
either way. Tests are usually as large as the code, because each change needs a regression test that
fails before it, and the session tests need fixture streams and recorded model turns.

**How each proposal is set out.** *In short* says what the change does and why, in plain terms. The
*Example* uses the data from this review. *Now* and *Change* give the code as it is and what it should
become, with references. *Test* names the regression test. *Size* breaks down the line count, and
*Risks* says what could go wrong.

Only A1 and A2 refuse anything that the Poor results did. The fall-through and fallback problems (items
8, 12 and 13) are covered only by new question text in D1. Nothing would refuse a stylesheet with no
catch-all, or an `xsl:otherwise` that asserts `Logon`.

### A. Scoring

#### A1. Refuse a translation that skips every record (item 1)

**In short.** A translation that turns every record into "skipped" is not a translation. Today it passes
every gate. After this change, the model is told so while it is still learning, and is asked again.

**Example.** The records-jsonl sample holds twelve JSON records like this one:

```json
{"time": "2026-09-21T09:20:00.000Z", "host": "api01", "user": "alice",
 "client": {"ip": "10.0.2.10", "port": 40000}, "kind": "login", "outcome": "success"}
```

The learned stylesheet's catch-all wins every tie (see A2), so every record comes out as:

```xml
<Skipped xmlns="learningflow:skips" reason="Record kind is neither login nor logout, so it cannot be translated"/>
```

Today the completeness check reads "12 in, 0 events, 12 skipped", finds nothing missing and scores
1.0. After A1 it scores 0.0, and the model is told:

```
ERROR: Every one of the 12 records was declared skipped, so the translation produces no events.
       The reasons given were: "Record kind is neither login nor logout, so it cannot be translated".
       Translate the records, or skip only those that are deliberately not events.
```

Placed beside a sample whose records say `"kind": "login"`, that reason contradicts itself, which
gives the model what it needs to find the fault.

While serving, gateway-audit drift-3 gave "0 event(s), 27 record(s) declared skipped". Under A1 the
stream is still served as it is today, but the learner is marked to learn again with the reason "every
record of stream 2238 was declared skipped, although the translation made events when it was learned".

**Now.** For records-jsonl, 12 records went in, no events came out and 12 skips were declared. Every
check that judged it passed or did not apply:

- `CompletenessScorer.score` (`scoring/CompletenessScorer.java:72`) tests `out + skipped >= in`, which
  holds, so it scores 1.0 and adds only an `INFO` notice.
- `SchemaConformanceScorer.score` (`scoring/SchemaConformanceScorer.java:118`) returns
  `Optional.empty()` when the output holds no events, so conformance does not apply at all.
- `YieldScorer` would score the shortfall, but yield is off in the Standard preset and advice only under
  Strict (`stroom-core-shared/src/main/java/stroom/learningflow/shared/CheckPreset.java:92`).
- The `INFO` notice does not reach the approver; see
  [D2](#d2-show-the-approver-what-the-numbers-dont-say-items-1-and-14).

**Change.** Score 0.0 when `out == 0 && skipped == in`, with an `ERROR` diagnostic that says every
record was declared skipped, so the translation makes no events, and quotes the first two or three
distinct reasons from the existing `Records.skipReasons(String)` (`scoring/Records.java:64`). Quoting
the reasons matters: "neither login nor logout" next to a record whose kind is `login` is what shows the
model its mistake.

**Serving.** gateway-audit drift-3 (stream 2238) shows the same fault while serving: 27 of 27 records
skipped, and no relearning. Holding a served stream back for it would be wrong, because a stream of only
heartbeats is legitimate. But a binding that made events when it was learned and now skips every record
of a stream is a sign of drift, so mark the learner for relearning
(`LearnerStates.markForRelearning`), as C1 proposes for parser errors, without deferring the stream.

**Where.** The candidate rule belongs to judging a *candidate*, not every stream. `CompletenessScorer` also judges
served streams when they are stepped, reprocessed or used for a relearning (`Judging.judge`, called
from `stage/Stage.java:398, 411, 550, 714` and `stage/Learning.java:285`). On those paths a stream that
holds only heartbeats is legitimate and must not score 0.0. The scorer cannot tell the uses apart today.
Two ways to do it:

1. **Preferred.** Add a flag to `Attempted` (`scoring/Attempted.java`) saying the step is a candidate
   being learned. Set it where the learning conversation and `Learning.learnAndBind` judge candidates
   (`Judging.rerun`, `stage/Judging.java:96`, and the conversation's own judging in
   `learning/Conversation.java`), and apply the new rule only when it is set. The model is then told
   during the conversation, and can fix the stylesheet before a person sees it.
2. Check `out == 0` in `learnAndBind` (`stage/Learning.java:562`), beside `judged.clearsGates()`. This is
   simpler, but the model never hears about it, so it would give up or park instead of being asked
   again.

Update the class comments of `DeclaredSkips` (line 49) and `CompletenessScorer` to state the one
exception to "no limit".

**Test.** Add to `TestCompletenessScorer`
(`stroom-learning-flow/stroom-learning-flow/src/test/java/stroom/learningflow/scoring/`) a candidate
step shaped like records-jsonl: 12 records in, 12 `<Skipped>` markers out. It must score 0.0 and quote
the reason. Add the same step without the candidate flag, which must still score 1.0. The first fails
today.

**Size.** About 80 lines of main code and 80 of tests:

- `CompletenessScorer`: about 30 lines (the new case and its message).
- The candidate flag on `Attempted`: about 10 lines, plus a line at each of the four places in main code
  that build one (`Learning`, `Stage`, `TargetChecks`, `PipelineFragmentRunner`). The test classes that
  build `Attempted` (about 30 calls) change only if the flag is added as a new constructor argument
  rather than a new factory method.
- Marking the learner when a served stream is all skips: about 30 lines in `Stage` beside the existing
  input check.
- Comments in `DeclaredSkips` and `CompletenessScorer`: about 10 lines.

**Risks.**

- *The candidate flag set in the wrong places.* If a candidate path is missed, the rule silently does
  nothing there. If a serving path sets it, a stream of heartbeats is refused. The tests must cover both
  sides, as the A1 test does.
- *A sample that really is all skips.* A stream that, on the day it is learned, holds only header lines
  or heartbeats can no longer be learned from. That is arguably right, since there is nothing to learn
  from, but the learner will wait for a better stream rather than learn, and a person may need to be
  told why.
- *Relearning churn while serving.* A feed that sometimes sends a stream of only heartbeats would be
  relearned each time, at a cost in tokens. The condition "made events when it was learned" limits this,
  but a feed whose quiet streams are common needs the rolling record (`LearnerStates.rolling`) to require
  several such streams in a row.
- *It changes a documented decision.* "No limit on skips" was deliberate. This keeps it for every case
  but one; the class comments must say so, or the next reader will remove the exception as a mistake.

#### A2. Refuse templates whose priorities tie (item 2)

**In short.** When two templates match the same record and neither has a priority, XSLT does not say
which one runs; Saxon picks the last one in the file and logs a warning nobody reads. That is how
records-jsonl skipped every record. After this change, a stylesheet with such a tie fails its compile
check, and the model is told how to fix it.

**Example.** records-jsonl's stylesheet has these three templates:

```xml
<xsl:template match="/map/map[string[@key='kind'] = 'login']">  …  <!-- priority 0.5 -->
<xsl:template match="/map/map[string[@key='kind'] = 'logout']"> …  <!-- priority 0.5 -->
<xsl:template match="/map/map">                                  …  <!-- priority 0.5: a tie -->
```

A login record matches the first and the third. Both have the default priority 0.5, so Saxon takes the
third, the catch-all. After A2 the compile check fails with:

```
ERROR: The templates matching '/map/map' and '/map/map[string[@key='kind'] = 'login']' have the same
       default priority, so a record that matches both is given to whichever comes last. Give the
       catch-all priority="-1".
```

The fix is one attribute: `<xsl:template match="/map/map" priority="-1">`. Templates that do not tie
are left alone: `Event` and `Event[EventID = 4624]` have default priorities 0 and 0.5, so the
predicate already wins.

**Now.** Saxon's "Ambiguous rule match" warning is captured, but nothing acts on it:

- `CompileScorer.score` (`scoring/CompileScorer.java:39`) scores 1.0 whenever there is output and
  attaches every diagnostic, warnings included.
- `Verdict.feedback` (`scoring/Verdict.java:93`) only includes the diagnostics of checks that **fell
  short**, and `Verdict.notices` (line 79) only `INFO`. So a warning on a passing compile check is never
  shown to the model.
- `BusinessRulesScorer.score` (`scoring/BusinessRulesScorer.java:60`) counts only the stylesheet's own
  `xsl:message`s (`ErrorType.CODE`, and only when `includeTransformMessages` is on, line 91), not Saxon's
  warnings.

**Change.** Fail the compile check of an XSLT step when templates tie. A compile failure is fed back to
the model, and the compile check runs on every judgement, including relearning and improvement.

1. `StylesheetScan` (`transformation/StylesheetScan.java`) records only each instruction's local name
   (`record Instruction`, line 236). Add a `record Template(String match, String mode, String priority)`,
   collected in `Handler.startElement` (line 119) for each `xsl:template` with a `match`. Expose it as
   `templates()`.
2. Add `LearnedStylesheetCheck.ambiguities(String stylesheet)`. It finds pairs of templates in the same
   mode, both with no `priority`, where one match is a path of more than one step and the other is the
   same path followed by a predicate. Both then have a default priority of 0.5 (XSLT 3.0 §6.5), and
   Saxon gives a record that matches both to whichever comes last. A single name such as `Event` has a
   default priority of 0, so it already loses to `Event[...]` and must not be reported. The message
   should say: "The templates matching '/map/map' and '/map/map[...]' have the same default priority, so
   a record that matches both is given to whichever comes last. Give the catch-all `priority="-1"`."
3. Call it from `CompileScorer.score` for an XSLT step (`XsltStep.ELEMENT_TYPE`, as `LiteralsScorer`
   tests), and score 0.0 when it finds any.
4. As a backstop, also score 0.0 when any diagnostic's message begins `Ambiguous rule match`. The scan
   in step 2 cannot see every tie: two different predicates that both match; the same path written
   differently (`map/map`, `/*/map`); union patterns; `mode="#all"` or a list of modes; or an explicit
   `priority="0.5"`. The backstop depends on Saxon's wording, which is why it is not the main check.

Do **not** put this in `LearnedStylesheetCheck.refusals` (line 76). That is the safety check behind
`PermittedScorer`, which `scoring/PermittedScorer.java:37` describes as "a safety check rather than a
quality one". A tie refused there would be reported as "Uses only what it may", and would give the
learner up with "The translation uses something a learned translation may not"
(`learning/StopReports.java:339`).

**Test.** Add to `TestLearnedStylesheetCheck`
(`stroom-learning-flow/stroom-learning-flow/src/test/java/stroom/learningflow/transformation/`):
records-jsonl's three templates (a tie), the same with `priority="-1"` on the catch-all (none), and
`Event` beside `Event[...]` (none). Add a `CompileScorer` test with the records-jsonl stylesheet's
output and warning. Both fail today.

**Size.** About 140 lines of main code and 120 of tests:

- `StylesheetScan`: about 35 lines (the `Template` record and collecting it).
- `LearnedStylesheetCheck.ambiguities`: about 70 lines (grouping by mode, comparing paths, the message).
- `CompileScorer`: about 25 lines (calling it for XSLT steps, and the backstop).
- `Templates`: 3 or 4 lines of question text (counted under D1 if done there).

**Risks.**

- *Misses.* The scan compares pattern text. Ties written differently (`map/map` against `/map/map`,
  unions, `mode="#all"`) are not seen by it. The backstop on Saxon's own warning covers those, but only
  for records the sample actually contains.
- *The backstop depends on Saxon's wording.* If a Saxon upgrade changes "Ambiguous rule match", the
  backstop stops working without any test failing, unless a test runs real Saxon on a tied stylesheet.
  The A2 test should do so.
- *A stricter compile check costs turns.* A compile failure sends the model round again. A tie is always
  a fault, so this is the right outcome, but sessions that pass today with a harmless tie (one where both
  templates write the same thing) will now take an extra turn.
- *The check must stay out of the safety check.* If it is later moved into `refusals` for tidiness, a tie
  would give the learner up as unsafe. The comment in `CompileScorer` should say why it is not there.

#### A3. Score elements written with no value (item 3)

**In short.** An element such as `<Id/>` is valid against most schemas but says nothing. In gateway-audit
it meant that the user who did something was missing from 9 of 10 events, and no check noticed. After
this change, any element that the target schema says should hold a value, written with no value, counts
against the event, whatever the target schema is.

**Example.** gateway-audit's second batch gives the user in attributes:

```xml
<transfer seq="51201" … direction="upload" outcome="success">
  <user id="u-50310" name="d.reyes" displayName="Daniel Reyes" domain="CORP-US"/>
```

The learned stylesheet reads only the child element `user/name`, so it writes:

```xml
<EventSource> … <User><Id/></User> … </EventSource>
```

After A3 the event fails the new check, and the model is told:

```
WARNING: Record 3: EventSource/User/Id is written with no value. Leave an element out where the record
         does not give its value, rather than writing it empty or with a placeholder.
WARNING: 9 of 10 records have elements written with no value.
```

These pass, because the schema does not expect a value in them, or the value is in an attribute:

```xml
<Data Name="lockName" Value=""/>          <!-- has attributes -->
<EventSource> … </EventSource>            <!-- holds child elements, not a value -->
```

With another target schema the same rule applies to its own elements. For example, for a schema
declaring `<xs:element name="Host" type="xs:string"/>`, an empty `<Host/>` is flagged.

**Now.** `<User><Id/></User>` and `<Object><Id/><Name/></Object>` are valid against event-logging,
because `Id`, `Name` and `HostName` are plain `xs:string`, and an empty string is a valid `xs:string`.
No check looks at whether an element that should hold a value has one.

**Why not in the schema.** The schema is the natural place for this rule: a restricted type, such as
`xs:string` with the pattern `.*\S.*`, used for the identifying elements, would refuse them. But
event-logging is a separate, versioned project used by every producer, and tightening it would break
existing hand-written translations, so it needs a new schema version. That is worth proposing to the
event-logging project. Learning Flow also targets any schema, and other schemas may be just as loose, so
it needs its own check either way.

**The rule.** An element whose schema type holds a value (a simple type, or a complex type with simple
content), written with no text and no attributes, says nothing. It should have been left out. The rule
names no elements, so it works for any target schema, and for event-logging it catches every case
above. The attribute exclusion keeps `<Data Name="x" Value=""/>` from being flagged. Elements whose type
is meant to be empty, or holds only child elements, are never flagged.

**Change.**

1. **Read value types from the schema.** Add `ContentModels.holdsValue(String elementName)`
   (`scoring/ContentModels.java`). `ContentModels` already reads the target XSD for the conformance
   hints. It indexes element declarations by local name (`index`, line 235), and follows named and inline
   types, `ref`, `group` and `extension`. Today it records named simple types only when they are
   enumerations (line 262), so it also needs to record every named simple type.
   - `holdsValue` returns `TRUE` when every declaration of the name has a value type: a built-in `xs:`
     type, a named or inline simple type, or a complex type with `simpleContent`.
   - It returns `FALSE` when every declaration is complex with element-only or empty content.
   - It returns unknown (an `Optional.empty()`) where the declarations disagree, or a type cannot be
     resolved, and the scorer does not flag the element. This follows the class's rule of leaving out
     what it cannot follow rather than guessing.
   - Declarations are keyed by local name, not by parent, so an element named the same with different
     kinds of type in different places is not judged. `parentOf` (line 430) returns a type name as often
     as an element name, so it cannot reliably be used to tell them apart.
2. **Share the loaded schema.** `SchemaConformanceScorer` loads and caches `ContentModels` for a target
   (`contentModels`, line 95; `key` and `load`, lines 264 to 276). With no target it uses every schema in
   the `EVENTS` group (`constraint`, line 199). Move the cache, `key`, `load` and `constraint` into a
   small shared class, injected into both scorers, so that the new scorer judges against the same
   schemas as conformance, with the same rule for no target.
3. **The scorer.** A new `EmptyValueScorer` in `scoring/`, following `BusinessRulesScorer`. Read the
   events with `OutputRecords.ofTransformed(step)` (`scoring/OutputRecords.java:71`). Walk each event's
   elements, and flag any element with no child elements, no attributes and only whitespace text, for
   which `holdsValue` is true. Return the failing record indexes in the `Score`, so that
   `Verdict.failingRecords` sees them. The diagnostic names the element and its path
   (`EventSource/User/Id`), and says to leave an element out rather than writing it empty or with a
   placeholder.
4. **The question text.** Add to `Template.TRANSFORMATION_RULES` (`learning/Templates.java:256`): leave
   out an optional element whose value the record does not give, rather than writing it empty or with a
   placeholder such as `unknown`. This matters because the check gives the model a reason to fill the
   element with something, and a placeholder is worse than an empty element, since it looks like data.
   gateway-audit's session ID written as the user is that fault already.

A new check touches these places:

- A value in the `Check` enum
  (`stroom-core-shared/src/main/java/stroom/learningflow/shared/Check.java`), constructed with
  `judgesRecords` true. That flag is what puts it in the bar a served record is held to
  (`Judging.recordBar`, `stage/Judging.java:87`). Optionally add it to `MEANING`
  (`scoring/Scorecard.java:48`) as well, which also applies it to the single event judged in the target
  question.
- A matching `<NAME>_SHORT` value in `StepOutcome`, because `Check.shortfall()` (`Check.java:114`) looks
  it up by name.
- A case in `CheckPreset.setting` (`CheckPreset.java:80`): advice in every preset, as `LiteralsScorer`
  is outside Strict. An empty `xs:string` is valid and a schema may mean something by it, so the check
  should prove itself on real feeds before it can refuse anything.
- Its wording in `FlowWords` (line 122) and in the client's `PlainWords` (lines 113 and 149).
- The scorer in the scorer list in `element/StageFactory.java:234`, and in the test fixture
  `src/testFixtures/java/stroom/learningflow/scenario/Scenarios.java:172`. Scorers are listed there, not
  bound with Guice.

Existing documents still load without the new check (`CheckSettings.complete`).

**Not covered.** A placeholder (`unknown`, `-`, `N/A`) or a value copied from the wrong field passes
both the schema and this check. Catching those would be a separate check.

**Tests.**

- `TestContentModels` (`stroom-learning-flow/stroom-learning-flow/src/test/java/stroom/learningflow/scoring/`),
  for `holdsValue`: against event-logging 4.1.0, `Id`, `Name` and
  `HostName` hold values, and `User`, `Device` and `Object` do not. A name declared with different kinds
  of type in different places is unknown.
- A new `TestEmptyValueScorer`: gateway-audit's batch 2 output fails, naming `User/Id`; the same events
  with `User` left out pass; `<Data Name="x" Value=""/>` passes; and an output against a small,
  non-event-logging test schema is judged by the same rule.

**Size.** About 280 lines of main code and 200 of tests, plus about 40 lines moved:

- `ContentModels.holdsValue` and recording every named simple type: about 60 lines.
- The shared cache of loaded schemas: about 60 lines in a new class, with about 40 lines moved out of
  `SchemaConformanceScorer`.
- `EmptyValueScorer`: about 120 lines.
- The new check's entries in `Check`, `StepOutcome`, `CheckPreset`, `FlowWords`, `PlainWords`,
  `StageFactory` and `Scenarios`: about 30 lines in all.
- Question text: 3 lines (counted under D1 if done there).
- Tests: `holdsValue` cases in `TestContentModels` (about 60 lines), `TestEmptyValueScorer` with a
  small test schema (about 140 lines).

**Risks.**

- *Misreading a schema.* `ContentModels` reads XSD itself, and was written for hints, where a mistake
  costs little. Here a mistake either misses empty elements silently or flags elements that are meant to
  be empty. The "unknown means not judged" rule keeps the second rare, at the cost of the first. Testing
  against event-logging 4.1.0 and at least one other schema is essential.
- *Placeholders.* Telling the model that an empty element is wrong invites `<Id>unknown</Id>`. The
  question-text change asks it to leave the element out instead, but nothing checks that it does.
- *It runs on the serving path.* With `judgesRecords` true, the check judges every served event. The
  walk is cheap, with a map lookup per element, but it is new work on every record of every stream. It
  should be measured on a large feed before it is on by default.
- *Moving the schema cache.* Moving it out of `SchemaConformanceScorer` changes a check that works today.
  The conformance tests must pass unchanged.
- *A new check is a visible change.* It appears in every document's checks and in the UI, and its
  name and wording need agreeing.

### B. The learning session

#### B1. Don't give up when a candidate fails only on unseen records (item 11)

**In short.** The model learns from the first part of a stream and is then judged on all of it. If the
rest of the stream holds records it never saw and its translation fails on them, Learning Flow gives up
on the learner for good. After this change it parks for a person instead (step 1) and, later, first
shows the model the records that failed and lets it try again (step 2).

**Example.** auditd-ausearch, as it ran on 26 November and as it would run after each step:

| | Today | After step 1 | After step 2 |
|---|---|---|---|
| Turn 6 | The XSLT passes on the 8 KB sample | Same | Same |
| Judged on the whole stream | Schema conformance 0.65; a gate fails | Same | Same |
| Then | Given up. The stream errors with "Given up: Candidate failed the gate Schema conformance" and the learner never learns again unless a person resets it | Parked, with the reason "The candidate passed on the sample but failed Schema conformance on records the model was not shown.. Raise the sample size, or edit the translation and re-run." The stream waits | The model is asked again, shown up to five of the records that failed and why, each with the schema's complaint about its event. Only if ten rounds do not clear the stream is it parked |

**Now.** `Learning.learnAndBind` (`stage/Learning.java:562`) gives the learner up, and wakes its waiting
streams to error them, as soon as the candidate fails a gate on the whole stream. The abandoned branch a
few lines above (line 548) parks in the worker instead, and so does `relearnUnderClaim` (line 582, parking
at 608). The rounds in `settle()` (line 863) only start after a `Promoted` or `Rebound` decision.

**Change**, in two steps.

**Step 1.** At line 562, mirror line 548: when `recorder.worker()`, park rather than give up, and do not
call `wake(learner)`. Outside the worker, giving up stays as it is. Two details:

- Use a `parked` overload that carries the verdicts (see B2). The existing one (line 929) builds the
  `StageRun` with no verdicts.
- The park reason must say what a person can do. Resuming replays the recorded turns
  (`stage/Learning.java:206`), so **give more** (`stage/SupervisorActions.java:86`) reproduces the same
  candidate, fails the same gate and parks again. **Learn again** (`SupervisorActions.relearn`, line
  171) does not help either: it marks the learner, but a paused run keeps its claim on the pipeline, and
  only give more, edit and re-run, and finish with Unknowns reopen it (see
  `docs/learning-flow-paused-runs.md`). What changes the outcome is a larger sample size together with
  **edit and re-run** (`SupervisorActions.amend`, line 127). A hint left for the learner through the
  Supervisor's guidance is read afresh before every question (`stage/Learning.java:770`), so it reaches
  the model in the questions asked after the edited turn, but not in the answers that are replayed. Say
  so in the reason, e.g. "The candidate passed on the sample but failed Schema conformance on records the
  model was not shown. Raise the sample size, then edit the translation turn and re-run."

**Step 2.** Before parking, give the model further rounds, as `settle()` gives a promoted candidate.
`relearnUnderClaim` cannot be reused as it is, because it assumes an incumbent `Binding`. Pull out of it
the part that builds the opening feedback and calls
`learn(doc, learner, input, scorecard, opening, held, recorder)` (line 747), and call that from line 562,
up to `MOST_ROUNDS` (10) times. Three things differ from the relearning path:

- The `Judged` from `Judging.asLearned` (`stage/Judging.java:79`) has a null `fellShort`. Use
  `judged.failingRecords()` (`stage/Judged.java:181`) instead.
- Those indexes count output events with skip markers removed, not parser records, so they must be
  mapped back to parser records before the records are shown to the model. After any declared skip the
  two are out of step.
- That `Judged` carries none of the per-record evidence that `Judged.shortOf(Shortfalls)` (line 96)
  builds for `fullPass`, so the opening feedback must be built here, capped at `EVIDENCE` (5) records as
  `fullPass` caps it.

**Test.** A learning-session test in `stroom-app/src/test/java/stroom/learningflow/`, beside
`TestScenario53TheCorpusSurvivesARestart`, with a stream whose last records are of a kind not in the
sample, and a recorded advisor whose first candidate fails them. In the worker, the session must park
with the verdicts kept. It gives up today.

**Size.** Step 1: about 25 lines of main code and 120 of tests. Step 2: about 150 lines of main code
and 150 of tests.

- Step 1: the parked branch at line 562, the `parked` overload that carries verdicts (shared with B2),
  and the wording of the reason.
- Step 2: pulling the shared part out of `relearnUnderClaim` (about 60 lines moved, 20 new), mapping
  failing output events back to parser records (about 50 lines), building the opening feedback (about
  40 lines), and the loop (about 20 lines).
- Tests: a session test with a recorded advisor needs a fixture stream and a recording; about 120 lines
  for step 1, and more recorded turns for step 2.

**Risks.**

- *More sessions waiting for a person.* Every learner that would have given up now parks. The streams
  behind it wait rather than error, so a feed whose data never fits keeps its streams held back until
  someone acts. The Learning Status tab must make parked learners obvious.
- *"Give more" does not help.* Resuming replays the recorded turns, so giving a parked session more
  budget reproduces the same failure. The park reason says so, but a person may still try it first.
- *Cost (step 2).* Each round is another conversation with the model; auditd-ausearch spent 324,575
  tokens in one. Ten rounds could spend ten times that on a stream that cannot be learned. The existing
  spending budgets still apply, but `MOST_ROUNDS` may be too many here.
- *Showing the wrong records (step 2).* If the mapping from output events to parser records is wrong,
  after a declared skip for example, the model is shown records that did not fail and is misled. The
  mapping needs its own tests, with skips in them.

#### B2. Keep the evidence of every attempt (item 11)

**In short.** When a learning attempt gives up or parks, the scores that explain why are thrown away, so
nobody can see afterwards what went wrong. After this change they are kept, along with which records
failed (by number, not by content).

**Example.** The attempt record for auditd-ausearch (attempt 17):

```
Today:        evidence = (empty)
After B2:     evidence = Compiles: passed · Uses only what it may: passed · Completeness: 100%, a gate
                         it passed · Schema conformance: 65%, a gate it failed (needs 100%)
              failing records = 21, 22, 30 …   (positions in the stream; illustrative)
```

A person can then open the stream at those records and see which kinds of record failed, without the
attempt itself storing any of the stream's text. (The numbers are illustrative: the real ones were not
kept, which is the point of B2.)

**Now.** `Sessions.carrying` stores an attempt's evidence only `if (bindingOf(run.decision()) != null)`
(`stage/Sessions.java:254`). A parked run returns earlier still, at line 240, before that line is
reached. So a given-up or parked attempt has an empty `evidence` column.

**Change.**

- Store the evidence when `run.verdicts()` is not empty, in the parked branch (line 240) as well as at
  line 254. With B1 step 1, the auditd-ausearch case becomes a park, so the parked branch is the one that
  matters.
- Update the comments that say evidence means a translation was written: `Sessions.java:252`,
  `Attempts.java:439` and `SupervisorAttempt.java:287`. Nothing that reads evidence assumes it. The only
  readers are `SupervisorService.attempt` (`doc/SupervisorService.java:941`) and the client's
  `FeedDetailController` → `Evidence.lines`, and `Evidence.line` already words a failed gate.

**What it will not tell you.** Evidence is one number per check, on purpose: "Only numbers are kept,
because the diagnostics quote the stream" (`Sessions.java:322`). For auditd-ausearch it would store
"Schema conformance 0.65", which the give-up reason already says. Which records failed is not kept
anywhere. To answer that without quoting the stream, also store the failing record indexes
(`judged.failingRecords()`) with the evidence. That is enough for a person to find the records in the
stream themselves.

**Size.** About 70 lines of main code and 60 of tests:

- `Sessions`: about 10 lines (storing evidence for parked and given-up runs).
- Failing record numbers: about 40 lines. Evidence is stored as JSON in one column
  (`AttemptsDao.evidence`, `stroom-learning-flow-impl-db/.../AttemptsDao.java:442`), so the numbers can
  go into the same JSON without a database migration, provided old rows, which hold a bare list, still
  read.
- The client: about 20 lines to show the numbers under "How it was judged".
- Comments in `Sessions`, `Attempts` and `SupervisorAttempt`.

**Risks.**

- *Reading old rows.* If the JSON changes from a list to an object, every attempt stored before the
  change must still read. A test must load a row in the old shape.
- *Record numbers go stale.* They refer to a stream that may later be deleted or reprocessed. They are a
  pointer for a person, not a record of the data, and the UI should say which stream they refer to.
- *Little else.* Nothing that reads evidence assumes a draft exists, so the change is low-risk.

#### B3. Sample every kind of record (item 4)

**In short.** The model is shown the start of a stream. If a different kind of record only appears later,
the model never sees it and the translation gets it wrong. After this change the sample includes at
least one record of each kind in the stream, where it fits within the sample size limit.

**Example.** gateway-audit's file, and what the model is shown:

```
<export>
  <batch schemaVersion="3.0" site="LON">   10,865 bytes; 10 events with <user><name>…</name></user>
  <batch schemaVersion="3.1" site="NYC">    6,508 bytes; 10 events with <user name="…"/>
</export>
```

| | Shown to the model |
|---|---|
| Today | Batch 1, whole (it is already over the 8,192-byte limit, but the first child is always kept). Nothing of batch 2 |
| After B3 | Once the record boundary is known to be the events, events from batch 1 up to the limit, less one or two, plus the first event of batch 2, each inside its own `<batch>` element so the batch's attributes are still there |

The second shape, `<user name="…"/>`, is then in front of the model, and its translation can read both.

**Now.** `LearningPrefix.learningPrefix` (`stage/LearningPrefix.java:65`) shows the model a strict
prefix: the first `1 - heldOutFraction` of the lines (`wholeLines`, line 90), of the JSON array's items
(`wholeItems`, line 168), or of the root's children (`wholeChildren`, line 269), up to the sample size
limit. A kind of record that first appears late in a stream is never shown. The skeletons that could
tell kinds apart (`RecordSkeleton`, `stage/RecordSkeleton.java:40`) are used to choose one event of each
kind for the target questions (`TargetChecks.kindOf` and `representatives`,
`learning/TargetChecks.java:78, 256`), but not to choose the sample. The class comment says they are
used "when a sample is chosen", which is true of the target questions but not of the learning prefix.

**Why the simple change is not enough.** The obvious change is to add to the prefix one record of each
kind found only in the held-out records. On the test data that does not work:

- **gateway-audit.** The root has two children, both `<batch>`, of 10,865 and 6,508 bytes. The events
  are their children. `wholeChildren` cuts at the root's children, so it keeps batch 1, already over the
  8,192-byte limit, and has no room for batch 2, and nothing of an already-represented kind to drop.
  Sampling has to work at the depth of the records, which is known only once the record boundary is
  settled, after the split question.
- **Text.** `TargetChecks.kindOf` uses the text skeleton of a line, which is so fine that nearly every
  line is its own kind (`netfilter.log` has 27 kinds in 30 lines, `apache-access.log` 32 in 35). Adding
  one of each unseen kind would pull in almost every held-out line. Text needs a coarser kind, such as
  the skeleton of the first few tokens.
- **auditd-ausearch.** Its records are blocks of lines between `----` lines. Taking one line out of its
  block would mislead the model. Here the remedy remains a larger sample.

**Change.**

1. For XML and JSON, choose the sample at the record boundary rather than at the root's children: once
   the boundary is settled, take records at that depth, re-close their ancestors as `wholeChildren`
   already re-closes the root, and add the first held-out record of each kind not yet in the sample.
   Where the size limit is reached, drop records of an already-represented kind from the end of the
   prefix to make room. Keep stream order. Before the boundary is settled, sample as now.
2. For single-line text, do the same with a coarser kind, and test it on the fixtures to check that it
   separates real record types without splitting on values.
3. Leave multi-line text as it is.

The corpus written at promotion is built from the same `learningPrefix` result
(`stage/Learning.java:474, 575, 660`, passed to `Corpus.entries`, `stage/Corpus.java:71`), so it stays
consistent with what the model saw, provided the new selection is made in `learningPrefix` itself.

**Test.** In `TestHeldOutRecords` or `TestSampleSizeLimit`
(`stroom-learning-flow/stroom-learning-flow/src/test/java/stroom/learningflow/stage/`), which test
`learningPrefix` today: an XML document of two large batches whose events differ in shape between
batches, with a record boundary at the events. The sample must include an event from each batch. It
fails today.

**Size.** About 230 lines of main code and 150 of tests. This is the largest change.

- `LearningPrefix`: choosing records at the record boundary rather than at the root's children, and
  re-closing their ancestors: about 120 lines.
- Choosing one record of each kind, and making room within the limit: about 60 lines.
- A coarser kind for single-line text: about 40 lines.
- Passing the record boundary to `learningPrefix` where it is known: about 10 lines at its callers.
- Tests: in `TestHeldOutRecords` or `TestSampleSizeLimit`, with gateway-audit and the text fixtures.

**Risks.**

- *The sample is no longer a run of the stream.* Records taken from far apart lose what was between them,
  such as a header line or a batch's opening. The model may draw wrong conclusions about order or
  context. Re-closing the ancestors keeps the batch attributes, but a text header line could be lost.
- *The sample changes between questions.* The record boundary is settled by the split question, so the
  parser question and the transform question would see different samples. Recorded sessions replay
  their turns, so the sample chosen must be recorded with the session, or a resumed session would be
  asked about a different sample than it answered.
- *What "a kind" is, for text.* Too fine, and every line is its own kind (as now); too coarse, and real
  differences are missed. The threshold needs tuning on the fixtures and will still be wrong for some
  feeds.
- *The held-out records change.* A record of a new kind moves from the held-out part into the sample, so
  the judgement on unseen records is made on fewer kinds. A promotion then says less about how the
  translation will do on kinds it has not seen.
- *The corpus must match the sample.* The regression corpus is built from the same `learningPrefix`
  result. If the selection is ever made outside `learningPrefix`, the corpus and the sample will differ.

### C. Serving

#### C1. Act on partial parser drops when serving (item 5)

**In short.** When a feed's format changes slightly, the parser may fail to read a few lines of each
stream. If the loss is under the coverage threshold (10% under the Standard preset), the stream is
served without those lines, the parser's errors are not shown to anyone, and nothing asks for the
translation to be relearned. After this change the learner is marked to learn again, and a configuration
that hides such errors is refused.

**Example.** A firewall feed in which a new device starts sending two lines in each forty that the parser
does not match:

| | Today | After C1 |
|---|---|---|
| Coverage | 38 of 40 records, 95%: above the 90% gate | Same |
| The stream | Served with 38 events; the 2 lines are lost; the error stream shows only "Served by …" | Served the same way |
| Afterwards | Nothing; every later stream loses its 2 lines too | After a few such streams in a row, the learner is marked to learn again: "the parser could not read 2 of 40 lines of the last 3 streams" |

And a candidate parser written with errors switched off:

```xml
<split delimiter="\n" ignoreErrors="true"> … </split>
```

fails to compile, with "A learned parser may not set ignoreErrors: a line it cannot read must be
reported."

**Now.** A line that no Data Splitter expression matches produces no record, and the parser logs an
"unmatched content" error for it (`DS3Parser.java:537`; also lines 150 and 293). The served run counts
those errors into `InputRead.errors` (`fragment/PipelineFragmentRunner.java:400`), and
`ServedStream.coverage` (`fragment/ServedStream.java:84`) is `records / (records + errors)`. If that is at
or above the coverage gate (0.9 in the Standard preset, `CheckPreset.java:85`), `Judging.inputShortfall`
(`stage/Judging.java:166`) lets the stream through. It is served, and nothing asks for a relearning.

No stream in the instance shows this. Netfilter drift-3 lost 9 of 40 lines, fell below the gate and was
relearned ([what happened to the drift files](#what-happened-to-the-drift-files)). But a feed that loses,
say, 2 lines in 40 would be served at 95% indefinitely. The parser's errors are not on the stream's error
stream either, so nobody would see them.

Two other points:

- `ignoreErrors` turns the errors off. The question text forbids it (`learning/Templates.java:184, 219`),
  but `DataSplitterCompiler` (`extraction/DataSplitterCompiler.java`) does not refuse it. A configuration
  that sets it drops lines with no error at all.
- `Template.EXTRACTION_RULES` (`learning/Templates.java:185`), the question text for the parser, already
  says "Every line of the input should be consumed by a match that emits a record".

**Change.**

1. Refuse `ignoreErrors` in `DataSplitterCompiler`, so that a dropped line is always an error. This is
   enforcing a rule the question text already states.
2. Where `Stage` checks the served input (`stage/Stage.java:605`), treat parser errors on a stream that
   met the coverage gate as drift. Don't defer the stream, since its coverage met the gate, but mark the
   learner with `LearnerStates.markForRelearning(learner, docUuid, reason)`
   (`stage/LearnerStates.java`), giving the number of unmatched lines as the reason. A feed whose format
   has changed then gets a new translation, instead of serving 90% of its records indefinitely. A learned
   parser is meant to consume every line, so any parser error on a served stream is a sign of drift.
   To avoid a relearning for every stray line, this could need a few such streams in a row, using the
   rolling record that `LearnerStates.rolling` already keeps.

**Not proposed:** requiring a catch-all regex in the parser that passes unmatched lines on as records. It
looks like the direct fix, but it would hide the drift that serving catches today. With a catch-all,
every drift-1 line becomes a record, so the parser no longer reads "no records". There are no parser
errors, so coverage is 100%. The XSLT's catch-all declares 30 skips, which completeness accepts. The
stream would be served as "0 events, 30 skipped". Learning-time input coverage would also always be 100%,
so it would stop guarding against a parser that matches nothing properly.

**Test.** A `DataSplitterCompiler` test that a configuration with `ignoreErrors` is refused, and a
`Judging` test that a served stream with 4 parser errors in 40 records marks the learner for relearning.

**Size.** About 70 lines of main code and 100 of tests:

- `DataSplitterCompiler`: about 20 lines to refuse `ignoreErrors`.
- `Stage`: about 50 lines to mark the learner after parser errors, using the rolling record.

**Risks.**

- *Relearning noisy feeds.* Some feeds always carry the odd line that is not a record (a truncated line,
  a stray banner). If every parser error counts as drift, they will be relearned over and over, at a cost
  in tokens. Requiring several streams in a row, and perhaps a minimum share of lines, limits this, but
  the right numbers are guesses until tried.
- *Refusing `ignoreErrors` on relearning.* `DataSplitterCompiler` compiles only candidates, so parsers
  serving today are not affected. But a relearning starts from the serving configuration, and if that
  sets `ignoreErrors`, the first candidate will be refused until the model removes it. This costs a turn.
- *The errors are still invisible.* C1 does not put the parser's errors on the stream's error stream.
  Doing that may be wanted too, but would change what operators see on every served stream.

### D. Question text and the approver

#### D1. Question text

**In short.** Several of the faults were the model doing something the question never told it not to:
asserting `Permit` without evidence, inventing `Production`, putting IDs into `Description`. These are
changes to what the model is told, not checks, so they are cheap but their effect is not certain.

**Example.** The additions to the transform's rules, in the style of the existing text:

```
End the stylesheet with a catch-all template, priority="-1", that writes <Skipped … reason="…"/>, so
that no record falls through to XSLT's built-in rules. A catch-all or an xsl:otherwise must never write
a particular action such as Logon, or a verdict such as Permit: write Deny, Permit or Success only where
a field of the record states it. Leave out an optional element whose value the record does not give,
rather than writing it empty or with a placeholder. System/Environment is required: where the record
does not give it, use stroom:meta('Environment'). Put a record's own ID in EventSource/EventId, a
session or logon ID in EventSource/SessionId, and parent and child IDs in EventChain/Activity. Use
Description only for what has no typed element.
```

With that text, netfilter's `xsl:otherwise` would be expected to record the packet without `Permit`, and
windows-security would be expected to put the logon ID in `SessionId`.

These are guidance to the model, not checks. They are cheap, but whether they work can only be measured
by re-running this review. Each change to the built-in text raises `Templates.VERSION`
(`learning/Templates.java:44`, now 11), so that runs asked with the older text can be told apart
(`learning/AskedWith.java:107`). Parked sessions keep the text they were asked with in any case, because
the text is recorded with the attempt.

In `Template.TRANSFORMATION_RULES` (`learning/Templates.java:256`):

- **Items 8, 12 and 13.** End the stylesheet with a catch-all template, `priority="-1"`, that writes the
  `Skipped` marker, so that no record falls through to XSLT's built-in rules. A catch-all or an
  `xsl:otherwise` must never assert a particular action such as `Logon`. Write `Deny`, `Permit` or
  `Success` only where a field of the record states it.
- **Item 9.** `System/Environment` is required by the schema, so it cannot simply be left out. Where
  the record does not give one, take it from the stream (`stroom:meta('Environment')`) rather than
  writing `Production`. `LiteralsScorer`
  already flags any literal year, date or region time zone, and any literal equal to one of this
  stream's Feed, Environment, System, Type, StreamType or TimeZone headers
  (`scoring/LiteralsScorer.java:46`). It does not flag invented values such as `Production` or
  `Unknown`; those could be added to it as advice. The rules already say "never write a year as a
  literal" (line 262).
- **Item 15.** Use `EventSource/EventId` for a record's own ID, `EventSource/SessionId` for a session or
  logon ID, and `EventChain/Activity` for parent and child IDs.
- **Item 10.** Use `Description` for what has no typed element, not for a copy of everything. Making this
  a scorer (a source value found only in `Description` scores lower) should wait until the question text
  alone has been tried.

In `Template.SPLIT_RULES` (`learning/Templates.java:161`), the split question, which settles what a
record is:

- **Item 7.** Ask whether lines share a correlation key, such as auditd's serial, and if so make one
  record of them.

**Size.** About 25 lines of question text and a `VERSION` change; about 20 lines of tests, where tests
compare the rendered questions.

**Risks.**

- *The effect is unmeasured.* A model may ignore the new rules, or follow them in ways that are worse
  (inventing an `EventId`). Only re-running the review on the same data shows whether they help.
- *Longer questions.* Every transform question grows by about 150 words, which costs tokens on every turn
  and may dilute the rules already there.
- *Event-logging only.* Most of these rules name event-logging elements. They must go into the
  event-logging guidance that `TargetSchema.isEventLoggingOrNone` selects, not into the text every
  schema is given.
- *Regressions elsewhere.* Data sets rated Good today may change. Re-running all of them, not only the
  Poor ones, is needed.

#### D2. Show the approver what the numbers don't say (items 1 and 14)

**In short.** A person approving a draft sees one line per check: "Completeness: 100%". Nothing tells them
that every record was skipped, or that the sample held no failures, so the translation has never been
tested on one. After this change a few plain-language notices are stored with the draft and shown beside
those lines.

**Example.** The approver's "How it was judged" panel for records-jsonl:

```
Today                                   After D2
─────────────────────────────────────   ─────────────────────────────────────────────────────
Compiles: passed                        Compiles: passed
Uses only what it may: passed           Uses only what it may: passed
Completeness: 100%, a gate it passed    Completeness: 100%, a gate it passed
                                        ⚠ 12 of 12 records were declared skipped
                                        ⚠ No event records an unsuccessful outcome
```

And for otel-traces: "No event records an unsuccessful outcome" and "66 of 66 events have the TypeId
OTEL-TRACE-SPAN".

**Now.** What the approver sees is the attempt's evidence (`Sessions.evidence`,
`stage/Sessions.java:326`): one line per check, such as "Completeness: 100%, a gate it passed", rendered
by the client's `Evidence` (`stroom-core-client/.../component/Evidence.java:79`). The `INFO` notices from
`Verdict.notices` (`scoring/Verdict.java:79`, called only from `stage/Judged.java:130`) go into the
judgement's diagnostics. A first learning in review mode returns its `Drafted` run with no diagnostics
(`stage/Learning.java:730`), so they are dropped. The notices reach an error stream only once a stream is
served, stepped or reprocessed (`element/Supervision.java:385`). That is where the "0 event(s), 12
record(s) declared skipped" line quoted for records-jsonl came from: after approval.

**Change.** Compute a short list of notices about the sample once, where a draft is written, and store
them with the attempt beside the evidence:

- Where: `Learning.bind`, and the draft branches of `relearnUnderClaim` and `improveUnderClaim`. Not in
  `Judged.of`: it is also called for every dry run, reprocess and relearning judgement, and once per
  corpus record (`stage/Learning.java:461, 637`), so a notice there would be repeated on served streams
  and for single records.
- Stored: a new field passed through `Attempts.evidence` (`stage/Attempts.java`) to `SupervisorAttempt`
  and shown by `FeedDetailController` under "How it was judged". Like the evidence, notices must not
  quote the stream.
- What: the completeness count when any records were skipped ("12 of 12 records declared skipped");
  "No event records an unsuccessful outcome", where no event has `Outcome/Success` false and there is no
  `Deny`; and "N of M events have the same `TypeId`", where one value covers most events. Keep the list
  short: only notices that tell the approver something the numbers don't.

**Test.** A session test in review mode, with a translation that declares some records skipped, in which
the stored attempt carries the skip notice. Today there is nowhere to store it.

**Size.** About 260 lines of main code and 150 of tests:

- Computing the notices: a new class of about 100 lines.
- Storing them: about 50 lines through `Attempts`, `AttemptsDao` (in the same JSON as the evidence, as
  B2 proposes) and `SupervisorAttempt`.
- Computing them where a draft is written: about 20 lines at `Learning.bind` and the two draft branches.
- The client: about 50 lines in `FeedDetailController` and `Evidence`, and about 40 lines of workbench
  stories, which the UI guide requires for any changed screen.

**Risks.**

- *Notices nobody reads.* If they are often wrong or always present, approvers will learn to ignore them,
  which is the problem today. The list must stay short, and each notice must be rare enough to mean
  something.
- *Quoting the stream.* Evidence deliberately stores no stream text. A notice that quotes a value (a
  `TypeId`, a reason) breaks that rule. Values must be left out, or limited to ones the translation itself
  wrote.
- *Target-specific notices.* "No unsuccessful outcome" reads event-logging's `Outcome/Success` and `Deny`.
  For another target schema these notices do not apply, and must be left out rather than always shown.
- *Stored format.* Shares the JSON change and its old-row risk with B2.

## Next steps

- **Fix records-jsonl** by adding `priority="-1"` to its catch-all template. A hand edit to a learned
  XSLT is snapshotted and served like a promotion, so this is a deliberate decision, not a tidy-up.
- **Build the changes in this order**, each with its regression test first:
  1. [A1](#a1-refuse-a-translation-that-skips-every-record-item-1),
     [A2](#a2-refuse-templates-whose-priorities-tie-item-2),
     [B1 step 1](#b1-dont-give-up-when-a-candidate-fails-only-on-unseen-records-item-11) and
     [B2](#b2-keep-the-evidence-of-every-attempt-item-11). All are small. Together they would have refused
     records-jsonl, and parked auditd-ausearch with its evidence instead of giving it up.
  2. [C1](#c1-act-on-partial-parser-drops-when-serving-item-5), which is small. No stream here needed
     it, but it closes a gap that would let a partly drifted feed be served indefinitely.
  3. [D2](#d2-show-the-approver-what-the-numbers-dont-say-items-1-and-14), so that an approver sees skip
     counts and missing failure paths before approving.
  4. [D1](#d1-question-text), then re-run this review to measure it.
  5. [A3](#a3-score-elements-written-with-no-value-item-3), B1 step 2 and
     [B3](#b3-sample-every-kind-of-record-item-4). These are the larger changes; B3 needs sampling to
     move to the record boundary first.
- **Recover auditd-ausearch** with a larger sample size, as described above, and review what it learns.
- **Treat the served netfilter verdicts and otel outcomes as unreliable** until their translations are
  improved: netfilter's Permit is a guess, and otel cannot record a failed span.
- **Re-run this review after changing the checks**, using the same data, to see whether the Poor and
  Acceptable results improve.
