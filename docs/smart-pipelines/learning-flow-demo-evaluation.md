# Shapeshifter AI Demo — evaluation of learned transformations

16 November 2026; four feeds added 17 November 2026

## Summary

Of nine learned transformations, four are Good, four are Fair and one is Poor; GATEWAY-AUDIT has not learned at all. Every learned feed was promoted with a score of 1.0 (WINDOWS-SECURITY 0.966), including NETFILTER, so the score does not tell a good translation from a poor one.

- **Good:** FIREWALL, MAINFRAME, DOOR-ACCESS and APP-EVENTS: simple, regular sources, every record correct against the source and the reference. APP-EVENTS currently has no live output; its 12 events were deleted on 10 November.
- **Fair:** AUDITD, WINDOWS-SECURITY, APACHE-ACCESS and AUDITD-AUSEARCH: the right events with sound parsing, but values mapped to the wrong or a generic element, defaults that guess, and detail buried in free text.
- **Poor:** NETFILTER: every event dated 2023 from a hard-coded year, one line dropped, a "Permit" asserted that the log never states, and most packet detail discarded. The ISO-timestamp drift sample produced no events.
- **Not learned:** GATEWAY-AUDIT parked after five record-boundary candidates, because its records are mixed elements under one parent.

**Added 17 November:** four more feeds, each learned from a scenario file not used before, were all promoted. FILE-ACCESS, API-GATEWAY and DOCVAULT-AUDIT are Good; WINDOWS-FRAGMENTS is Fair, like WINDOWS-SECURITY, the same events in another form. Every record became an event, with no errors, and parsing was right on multi-line CSV, a JSON document, XML fragments and nested XML. Of 55 events, 40 are fully correct and the 15 WINDOWS-FRAGMENTS events partly correct, with no wrong values. The weaknesses are latent: three of the four have a catch-all that would mislabel a kind of record the sample doesn't contain, and FILE-ACCESS, which has none, would instead fail the whole stream on one unknown action.

Parsing is the strong point: event boundaries were right on every feed, and hard tokenisation (Apache escapes, `ausearch -i` values with spaces) came out intact. The weak points are the same across feeds: timestamps given a zone or year the source never states, catch-all branches that produce plausible but wrong events, and parsers that drop unexpected lines silently. Learning cost 3.87 million tokens in all, 1.74 million of them on AUDITD-AUSEARCH attempts lost to the 60-second model timeout that has since been fixed.

## Scope and method

Ten feeds sit in the System / Shapeshifter AI Demo folder. Each has a `<FEED> supervised` pipeline that hands its raw stream to the shared Shapeshifter AI "Any feed", which learns a Data Splitter parser and an XSLT and writes `event-logging:3` events. Nine feeds have a promoted, learned transformation; GATEWAY-AUDIT has not learned (its only attempt is parked).

- **What was assessed:** for each feed, every output event against its raw input, plus the learned TextConverter and XSLT read for robustness.
- **Reference:** where the scenario folder (`stroom-learning-flow/src/testFixtures/resources/stroom/shapeshifter/ai/scenario`) holds a hand-written translation (`*.events.xml`, `*.ds3.xml`, `*.xsl`), the learned output was compared with it field by field, as a guide to intent rather than a strict answer key. AUDITD-AUSEARCH, APACHE-ACCESS and NETFILTER have no reference and were judged against the raw data alone.
- **Who assessed:** the review was split across four reviewers running Claude Fable 5.2, each reading the streams and documents through the Stroom MCP server (read only). Learning history (attempts, scores, tokens) came from the `shapeshifter_attempt` and `shapeshifter_turn` tables.
- **The four feeds added on 17 November** (API-GATEWAY, FILE-ACCESS, WINDOWS-FRAGMENTS, DOCVAULT-AUDIT) were reviewed in the same way, by four further reviewers running Claude Fable 5.2, one per feed: every output event read against its raw record, the learned parser and XSLT read for robustness, and the reference compared where there is one. The FILE-ACCESS reviewer also ran the learned parser's regex against edge cases in Python.
- **Ratings:** Good = correct and well mapped with minor gaps; Fair = right events but notable mapping gaps or data buried in free text; Poor = wrong or missing data a consumer would rely on.

All output passed schema validation, so "valid" is taken as given; the question here is whether each translation is faithful and well mapped.

## Results at a glance

Record counts are for the stream each transformation learned from; "fully / partly / wrong" is the reviewers' judgement of every output event against the raw source.

| Feed | Source format | Reference | Records in → out | Fully / partly / wrong | Errors | Learning score | Tokens | Rating |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| FIREWALL | sshd syslog, RFC 3164 + 5424 | `syslog.events.xml` | 20 → 20 | 20 / 0 / 0 | 0 | 1.0 | 28,071 | Good |
| MAINFRAME | Fixed-width sign-on log | `fixed-width.events.xml` | 30 → 30 | 30 / 0 / 0 | 0 | 1.0 | 23,256 | Good |
| DOOR-ACCESS | CSV with header | `csv-logon.events.xml` | 6 → 6 | 6 / 0 / 0 | 0 | 1.0 | 23,577 | Good |
| APP-EVENTS | JSON lines | `records.events.xml` | 12 → 12 (deleted) | 12 / 0 / 0 | 0 | 1.0 | 64,242 | Good |
| WINDOWS-SECURITY | Windows event XML | `windows-security.events.xml` | 15 → 15 | 5 / 10 / 0 | 0 | 0.966 | 44,909 | Fair |
| AUDITD | Raw auditd log | `auditd.events.xml` | 8 → 8 | 3 / 5 / 0 | 0 | 1.0 | 485,528 | Fair |
| APACHE-ACCESS | Apache combined access log | None | 35 → 35 | 23 / 12 / 0 ¹ | 0 | 1.0 | 301,213 | Fair |
| AUDITD-AUSEARCH | `ausearch -i` output | None | 27 → 27 | 5 / 16 / 6 ² | 0 | 1.0 | 2,275,643 | Fair |
| NETFILTER | netfilter kernel log | None | 30 → 29; drift 30 → 0 | 0 / 29 / 1 | 1; drift 20 | 1.0 | 551,437 | Poor |
| GATEWAY-AUDIT | Nested XML, mixed record types | None | Not processed | — | — | Parked | 69,301 | Not learned |
| FILE-ACCESS ³ | CSV, quoted multi-line fields, no header | `csv-multiline.events.xml` | 20 → 20 | 20 / 0 / 0 | 0 | 1.0 | 234,839 | Good |
| API-GATEWAY ³ | JSON document with an `events` array | `records.events.xml` | 12 → 12 | 12 / 0 / 0 | 0 | 0.996 | 104,381 | Good |
| DOCVAULT-AUDIT ³ | Nested XML entries | None | 8 → 8 | 8 / 0 / 0 | 0 | 0.985 | 121,931 | Good |
| WINDOWS-FRAGMENTS ³ | Windows event XML fragments, no root | `windows-security.events.xml` (same 15 events) | 15 → 15 | 0 / 15 / 0 ⁴ | 0 | 1.0 | 46,555 | Fair |

¹ Before counting one fault on all 35: the client IP is recorded as the `Device`. ² Before counting one fault on all 27: an invented UTC zone on every timestamp. ³ Added 17 November. ⁴ Strictly counted: every event shares the same gaps, and none has a wrong value; 5 / 10 / 0 if the `Description` list of remaining fields is accepted.

## Per-feed findings

Feeds run from best to worst. Severity tags are the reviewers': High = wrong or missing data a consumer relies on; Medium = a mapping or robustness gap; Low = cosmetic.

### FIREWALL — Good

All 20 sshd lines, in two syslog dialects, became `Authenticate/Logon` events matching `syslog.events.xml` on time, host, client, user and outcome (15 true, 5 false).

- **Went well:** both RFC 3164 and RFC 5424 handled, dialect detected by timestamp shape; no year hard-coded; the raw line kept so priority and PID are recoverable; `Generator` = `sshd` and descriptive TypeIds (`SSH_LOGON_FAILURE`) beat the reference.
- **[Medium]** The year of year-less BSD lines comes from `current-date()`: reprocessing these September lines in 2027 would date them in the future.
- **[Medium]** `.000Z` appended to zone-less local time.
- **[Medium]** One fully anchored regex per dialect, including the exact sshd sentence: `Failed password for invalid user …` or `Accepted publickey …` would be dropped silently.

### MAINFRAME — Good

All 30 fixed-width lines are correct and match `fixed-width.events.xml` on time, user, action, success and reason.

- **Went well:** `20260921091500` → `2026-09-21T09:15:00.000Z` with no hard-coded date; padding trimmed; multi-word reasons (`BAD PASSWORD`) intact; `DENIED` → `Success=false`; case-tolerant matching.
- **[Medium]** The parser splits on whitespace (`(\S+)\s+…`) rather than by column, so a value filling its 8-character column or a blank column would shift every field into a valid but wrong event. The reference cuts by position.
- **[Medium]** UTC assumed for a zone-less source (as the reference does).
- **[Low]** Unknown actions default to `Logon`; terminal ids such as `TSO01` go in `Device/HostName`.

### DOOR-ACCESS — Good

All 6 rows are correct; the header and five trailing blank lines produced no stray events.

- **Went well:** a header-driven parser (`$heading$1`) that survives reordered columns, unlike the reference; `where` → `Device/Location/Site`, a better fit than the reference's `Device/Name`; no outcome invented.
- **[Medium]** Only `logon` occurs in the sample, so the `logoff` branch and the fallback (which copies the raw word into `Authenticate/Action`) were never exercised.
- **[Medium]** The comma split is not quote-aware: `"Smith, J"` would shift columns.

### APP-EVENTS — Good, but no live output

The 12 JSON records translate correctly, judged from the deleted Events stream 2103 and a desk check of the XSLT.

- **[High, operational]** The feed has no live output. Events 2103 and Error 2102 were deleted on 10 November and nothing has replaced them.
- **Went well:** better than the reference on retention: `Session s-0703 lasted 240 seconds` kept on logouts; nested `client.ip`/`port` read correctly; logouts get no invented outcome.
- **[Medium]** Record selection `if (/map/map) then /map/map else /map` depends on how the pipeline wraps each record; a root-level record would select the nested `client` map instead.
- **[Medium]** Any `kind` other than `logout` is reported as `Logon`.

### WINDOWS-SECURITY — Fair

All 15 events are right on time, host, user and action, and nothing in `EventData` is lost, but a fifth of the output is untyped `Data`.

- **Went well:** the right actor per event type (`TargetUserName` for 4624/4634, `SubjectUserName` for 4688); `Client` gets `WorkstationName` as well as IP and port, which the reference omits; sub-second fractions handled more robustly than the reference.
- **[High]** `LogonType` left as raw `2` in `Data` instead of `<LogonType>Interactive</LogonType>` (5 logons).
- **[Medium]** No `Outcome/Success` on 4624 logons, and the catch-all branch turns any other event id — a 4625 failed logon included — into a plain logon.
- **[Medium]** `NewProcessId` in `Data`, while a `Data Name="ProcessId"` carries the creator's PID, which misleads. 75 of 370 output elements are `Data`; this is why the score was 0.966.
- **[Medium]** `System/Name` is the hostname and `Environment` is the log channel.

### AUDITD — Fair

23 lines grouped into the 8 correct events with correct times, users, client IPs and PIDs; the 5 process events have mangled arguments.

- **Went well:** exact grouping by `msg=audit(time:serial)`; the failed login (record 6) correctly `false` despite a `res=success` USER_START in the same event; adds a `Process/Outcome` from `success=yes`, which the reference lacks.
- **[High]** `Process/Arguments` is the raw fragment `a0="grep" a1="-r" a2="secret"` instead of `-r secret`, on all 5 process events.
- **[Medium]** One rigid regex per line type in exact field order: a real SYSCALL line with `suid fsuid … subj` would be lost, and a second PATH line would make the XSLT's `concat()` throw.
- **[Medium]** Most fields live only in a 300–480 character `Description`; `RunAs` repeats the user or names sshd's root.

### APACHE-ACCESS — Fair

The parser is excellent — all 35 awkward lines split correctly and nothing is dropped — but the mapping puts the client in the wrong place.

- **Went well:** `+0100` converted to UTC across midnight; the space in a path, `"GET /"`, `"-"`, `\"` quotes and the raw TLS handshake all parse; each kind of `-` handled per field; attack strings kept verbatim; `PUT` 201 → `Create`, `DELETE` → `Delete`.
- **[High]** The client IP is written to `EventSource/Device/IPAddress` on all 35 records and there is no `Client`. `Device` is the logging server, so every who-and-where query is wrong.
- **[Medium]** The action comes from method and status alone: the 401 run with `admin` and `root` reads as failed `View`s by ordinary users, not failed authentication; the same `POST /api/v1/orders` is `Create` on 201 but `Update` on 500.
- **[Medium]** `CONNECT`, `OPTIONS *`, `SSTP_DUPLEX_POST` and the TLS handshake are all `View`; `\xc3\xa9` and `\"` are left encoded in URL and user agent.

### AUDITD-AUSEARCH — Fair

27 events with correct boundaries, reversed record order handled, and spaced arguments intact, but six events are the wrong kind.

- **Went well:** EXECVE arguments with spaces and `=` survive (`-czf /tmp/backup 2025.tgz --exclude=*.log /home/grace/My Documents`); the `/etc/shadow` EACCES open is a clean failed `View`; auid versus uid right for `useradd` and `auditctl`; the mm/dd dates read correctly across midnight.
- **[High]** A UTC zone invented on all 27 timestamps; the XSLT's own comment says "UTC is assumed".
- **[High]** Everything outside auth and file opens becomes `Process/Execute`: ADD_USER/ADD_GROUP lose `acct=henry` entirely; the SIGSEGV crash is a successful execute; SERVICE_STOP/START are both "systemd executed"; the `connect` has no `Network` element and `saddr={ … }` is split into four fields.
- **[Medium]** sudo session open has User and RunAs inverted (`User=root`, `RunAs=dave`); `(invalid user)`, `?` and `unknown(100999)` used as user ids; CONFIG_CHANGE hidden under TypeId `SYSCALL`; one ssh login becomes six `Logon` events.

### NETFILTER — Poor

Addresses are extracted accurately, but no event is fully correct.

- **Went well:** the quoted inner packet of ICMP errors kept separate (`inner_*`), so inner ports never leak; `MAC=` split into destination and source; all IPs (v4 and v6) and ports faithful; a generic key=value tokeniser.
- **[High]** `<xsl:variable name="year" select="2023"/>`, commented "the feed's logs are from 2023", an invented fact: every event is three years out, and probably an hour, since the drift file shows `+01:00`.
- **[High]** Line 25 (`FRAG:185`) was dropped by the parser and the translation was still promoted at 1.0.
- **[High]** The outcome is guessed from the rule name (`contains($prefix, 'DROP')`), so `FW-SCAN-XMAS`, `FW-SCAN-NULL` and `FW-IN-FRAG` read as permitted traffic. Protocol, interfaces, TCP flags, ICMP type/code and UID/GID are parsed but never emitted. `Environment` = `Production` is invented.
- **[High]** The ISO-timestamp drift sample (stream 2134) produced no events: 20 parser errors, no relearn, visible only in the Error stream.

### GATEWAY-AUDIT — Not learned

The only attempt parked on 10 November after five record-boundary candidates (`batch`, `authentication`, `export`…), 69,301 tokens. The records are the children of `batch`, under nine different element names: `authentication`, `transfer`, `sessionEnd`, `heartbeat`, `share`, `policyChange`, `adminAction`, `batchSummary` and `diagnostic` (29 records in two batches; `diagnostic` appears only in the second). The learner can only split on one element name, which must hold at least 90% of the text, and the most common, `transfer`, holds about 41%. The sample the model saw held one batch only, so even a list of names taken from it would have missed `diagnostic`. Some of the nine (`heartbeat`, `diagnostic`, `batchSummary`) look like housekeeping rather than events, which the target step would need to recognise. The raw stream has been deferred 99 times since.

### Feeds added on 17 November

Each has a `<FEED> supervised` pipeline on the shared "Any feed", like the others. Sources: `csv-multiline.csv` (FILE-ACCESS, stream 2154), `records.json` (API-GATEWAY, stream 2153), `nested-audit.xml` (DOCVAULT-AUDIT, stream 2155) and `events-fragments.xml` (WINDOWS-FRAGMENTS, stream 2157). In all four, the first XSLT candidate fell short on schema conformance. WINDOWS-FRAGMENTS passed with its second candidate; the other three used both, and writing target events then led to a passing translation. The learned parser and XSLTs are kept in `docs/smart-pipelines/learned/`.

#### FILE-ACCESS — Good

All 20 events reproduce their records exactly, including the six notes that span two lines and the doubled-quote notes; 14 are `View` and 6 `Delete`, as in the source.

- **Went well:** multi-line notes kept whole with the line break (`Removed after review, superseded⏎by the v2 draft`); outer quotes stripped and `""` turned back into `"`; commas inside quoted notes survive; records start on a date pattern, so a continuation line can't be taken for a new record; `User` and `Description` omitted when empty; no catch-all on the action. The reviewer ran the parser's regex in Python against empty notes, notes starting or ending with an escaped quote, CRLF endings and a missing final newline, and all held.
- **[Medium]** The note is filed as `File/Description` (`<File><Name>old draft.docx</Name><Description>Superseded</Description></File>`), but "Routine access" or "Printed for the site visit and returned" is a remark on the action, not a property of the file. The reference's `Outcome/Description` is the better fit.
- **[Medium]** An unknown action ends with `<xsl:message terminate="yes">`, so one `EDIT` or `RENAME` row would lose every event in the stream. Safer than guessing, but brittle.
- **[Medium]** Only the last column may be quoted. In Python, these give no match at all: a quoted file name with a comma (`"a, b.pdf"`), an unquoted note containing a quote, whitespace after the closing quote, a seventh column after a quoted note, a five-column row. A quoted value in columns 1–5 that does match keeps its quotes (`"bob"` as the user).
- **[Medium]** The regex has no `^` anchor, so a header or malformed line may be skipped silently (seen in Python; not confirmed in Data Splitter).
- **[Low]** An unterminated quote (`…,f.pdf,"oops`) is accepted without warning; an extra unquoted column is absorbed into the note; `System/Name`, `Generator` (`FILE-ACCESS`) and `Environment` (`Unspecified`) are invented; `TypeId` is `FILE_VIEW` where the source says `VIEW`; host and file name are not guarded against being empty.
- **Against the reference:** identical in fidelity. Learned uses `File` where the reference uses `Document` (either is defensible). The reference's catch-all makes everything that isn't `DELETE` a `View`; the learned version is better on correctness and worse on availability.

#### API-GATEWAY — Good

All 12 events are faithful, and every value in a record reaches a structured element: 9 logons and 3 logoffs, 7 successful and 2 failed (erin and dave).

- **Went well:** time, host, user, client IP and port copied verbatim; logoffs carry no invented outcome; session ID and duration kept as named `Data` (`SessionId` `s-0703`, `SessionSeconds` `240`), which the reference drops; optional elements guarded with `xsl:if`, where the reference emits `Client` and `User` unconditionally.
- **[Medium]** Any `kind` other than `logout` becomes a `Logon` (`<xsl:otherwise>Logon</xsl:otherwise>`), so `token_refresh` or `password_change` would be reported as authentication.
- **[Medium]** The raw `kind` never reaches the event: `TypeId` is a made-up constant (`API-GATEWAY-LOGON`), so a mislabelled kind can't be recovered. The reference passes it through (`<TypeId>login</TypeId>`).
- **[Medium]** The outcome is a binary catch-all: anything other than `success` (`ok`, `partial`, `locked`) becomes `false`, and the original value is dropped.
- **[Medium]** `TimeCreated`, `Device/HostName` and `Authenticate/User/Id` are emitted unconditionally; a record without `time`, `host` or `user` gives empty elements.
- **[Medium]** Records are selected by an exact path (`map/map/array[@key='events']/map`), so a change of wrapping would silently give no events. The reference matches the shape instead (`//map[string/@key = 'time']`).
- **[Low]** `System/Name` and `Generator` are the feed name and `Environment` is `Not specified`; the document's top-level `"source": "api-gateway"` is never read; a port sent as a string, or an empty `client` object, would be dropped or give an empty `Client`.
- **[Note]** The parser was re-run against the targets and passed preservation: its labels (`SessionId`, `SessionSeconds`) don't occur in any JSON key, so `docs/learning-flow-preservation-matches-record-markup.md` didn't trigger.

#### DOCVAULT-AUDIT — Good

All 8 events are fully correct: logons, logoffs and document opens, with every per-record value in a structured element. There is no reference for this input; it was judged against the raw data.

- **Went well:** `when`, `session/host`, `client/@ip`, `actor/login`, `department` and the entry id all mapped; document classification, path and size placed in `File`; times passed through unchanged; optional elements guarded; no parser needed and none invented.
- **[Medium]** Every `did` other than exactly `login` or `logout` becomes `View` with TypeId `DOCVAULT_OPEN`, including `delete`, `share`, `Login` (the match is case-sensitive) or a missing `did`. The raw `did` is never written, so the mislabelling couldn't be detected downstream.
- **[Medium]** `System/Name` and `Generator` are the constant `DocVault`, not read from `auditLog/@application`; `version="2"` never reaches an event. (Whether the root attribute is visible to the XSLT once the stream is split per `entry` wasn't checked.)
- **[Medium]** `TimeCreated` and `Device/HostName` are emitted unconditionally, and an open with no `document` gives an empty `File`.
- **[Low]** `Environment` is the placeholder `Not recorded`; `EntryId` sits under `EventSource` for opens but `Authenticate` for logons; TypeIds are made up rather than the source's `did`; more than one `document` element would make the XSLT fail with a type error.

#### WINDOWS-FRAGMENTS — Fair

The same 15 events as WINDOWS-SECURITY, sent as fragments without a root element. No event has a wrong user, time, host, action, IP, port, command or PID; every event is counted as partly correct because of gaps that all of them share (5 / 10 / 0 if the `Description` list of remaining fields is accepted). The reviewer rated it Good; it is rated Fair here for consistency with the scale (detail buried in free text) and with WINDOWS-SECURITY, the same 15 events, which is Fair with the same remaining gaps. It does fix WINDOWS-SECURITY's one High finding, the undecoded `LogonType`.

- **Went well:** seven-digit fractions cut to milliseconds; the right actor per event type, with the machine account `WS01$` not taken for the user; 4624 `Client` fully structured, including `HostName` from `WorkstationName`, which the reference drops; `LogonType` decoded to the schema's names (`Interactive`, `Network`) from a nine-entry table, where the reference has three and the WINDOWS-SECURITY translation left a raw `2`; nothing in `EventData` lost.
- **[Medium]** Any event ID other than 4688 and 4634 becomes a `Logon`: a 4625 failed logon would read as a logon, without a failure outcome and without its `Client`; a 4672, 4720 or 1102 would too, with an empty `User/Id`. The reviewer notes this becomes High as soon as such data arrives.
- **[Medium]** No `Outcome` on logons, though the reference has `<Success>true</Success>` and the source's `Keywords` (`0x8020000000000000`, audit success) could supply it.
- **[Medium]** Fields the schema could hold are buried in one `Description` string (`TargetDomainName: CORP; TargetLogonId: 0x3e7a0; …`; for 4688, `ProcessId: 0x801; ParentProcessName: …`). `User` has a `Domain` element, and the creator's `ProcessId: 0x801` reads ambiguously beside the structured `<ProcessId>0x1a01</ProcessId>`.
- **[Medium]** `Environment` holds the log channel (`Security`); the schema means the deployment instance.
- **[Medium]** Client and process fields are emitted unconditionally: a missing `IpPort` gives an empty `Port`, and a local logon's `IpAddress` of `-` would be written as an address. The reference guards against `-`.
- **[Low]** `Generator` (`Windows Event Log`) and `Process/Type` (`Application`) are constants; `System` header fields (`Guid`, `Level`, `Task`, `Opcode`, `Keywords`) are dropped; a fraction shorter than three digits would garble the time; events outside the Microsoft namespace would give an empty `Events` without error.
- **Against the reference:** better on client host name, logon types, keeping every field and taking `System/Name` from the data; worse on outcome, the `-` guard and `Environment`. The reference's catch-all makes unknown IDs a `Process`; the learned one makes them a `Logon`, the more misleading of the two for security data.

## Patterns across feeds

The learner is a strong parser and a weak mapper: it splits records reliably, but when the source is silent or the event type is unusual it guesses, and the scorers do not catch the guesses.

| Pattern | Feeds affected | Example |
| --- | --- | --- |
| The promotion score misses semantic faults | All nine; three of the four added (latent) | NETFILTER promoted at 1.0 with a hard-coded year, a dropped line and guessed outcomes; DOCVAULT-AUDIT's catch-all `View` promoted at 0.985 |
| A zone or year the source never states | NETFILTER, AUDITD-AUSEARCH, FIREWALL, MAINFRAME | `2023` constant; `.000Z` appended to zone-less local time; year from `current-date()` |
| Catch-all branches yield plausible but wrong events | WINDOWS-SECURITY, AUDITD, AUDITD-AUSEARCH, APP-EVENTS, MAINFRAME, NETFILTER; added: API-GATEWAY, DOCVAULT-AUDIT, WINDOWS-FRAGMENTS | Unknown Windows ids → `Logon`; ANOM_ABEND → successful `Execute`; no `DROP` in the rule → `Permit` |
| Values left in free text or generic `Data` | AUDITD, AUDITD-AUSEARCH, WINDOWS-SECURITY, WINDOWS-FRAGMENTS | `LogonType` as `Data`; `acct`, `unit`, `saddr` only in `Description` |
| Parsed values thrown away | NETFILTER | Protocol, interfaces, flags and UID parsed but never emitted |
| Unexpected lines dropped silently | NETFILTER, FIREWALL, AUDITD, MAINFRAME, APACHE-ACCESS | `FRAG:185` line lost; fully anchored regexes with no catch-all token rule |
| Element meaning misread | APACHE-ACCESS, AUDITD, AUDITD-AUSEARCH | Client IP as `Device`; `RunAs` set to sshd's root; sudo User and RunAs inverted |
| Source metadata is a placeholder or invented | All nine and all four added | `System/Name` = feed or host name; `Environment` = `Unspecified`, `Production`, `Not recorded` |
| Drift not surfaced | NETFILTER | ISO-timestamp sample: 0 events, no relearn, visible only in the Error stream |
| Required fields emitted unguarded | API-GATEWAY, DOCVAULT-AUDIT, WINDOWS-FRAGMENTS, FILE-ACCESS | A record without `time` or `host` gives an empty `TimeCreated` or `HostName`; a Windows `IpAddress` of `-` written as an address |
| The source's own type value is not kept | API-GATEWAY, DOCVAULT-AUDIT, FILE-ACCESS | `TypeId` = `API-GATEWAY-LOGON` instead of `login`, so a catch-all's mislabelling can't be traced |
| Context outside the record is lost | API-GATEWAY | The document-level `source` is not in any record, so it never reaches an event |

The strengths are consistent too:

- **Event boundaries** were right on every feed, including multi-line auditd events, reversed `ausearch -i` records, CSV with blank lines, and (added feeds) CSV with quoted newlines, a JSON document, XML fragments and nested XML.
- **A loud failure is possible:** FILE-ACCESS stops on an unknown action instead of guessing, which shows the model can avoid catch-alls; it overshoots, though, by failing the whole stream rather than the one record.
- **Hard tokenisation** came out intact: Apache's escaped quotes, raw spaces and TLS bytes, and `ausearch -i` values containing spaces and `=`.
- **Retention sometimes beats the hand-written reference:** APP-EVENTS keeps session details, WINDOWS-SECURITY keeps every `EventData` value, and AUDITD adds a process outcome.
- **No sample-specific values are baked in** (users, hosts, IPs) on any feed except NETFILTER's year and environment.

## Recommendations

The highest-value change is to make promotion fail on the faults found here, because every one of them passed at a score of 1.0.

**Scoring and promotion**

1. Make completeness a gate: lines in must equal records out plus lines deliberately skipped, so a dropped line such as NETFILTER's `FRAG:185` blocks promotion.
2. Flag literal constants in the XSLT that look like data — a year, an `Environment` value, a fixed zone suffix — and reject them unless the feed configuration supplies them.
3. Check that each outcome and action has evidence in the source: an asserted `Permit`, or `Success=true` on a crash, should cost score.
4. Penalise catch-all branches that emit a specific action; prefer `Unknown` or a flagged TypeId so mis-typing is visible.
5. Add a reviewer pass by a stronger model over a sample of output against the raw records; it found every issue in this document.

**Prompt guidance for the translation**

1. Time: never append `Z` or a year that the source does not state. Take the zone and year from feed configuration, or use `stroom:format-date` with a stream-relative year.
2. Element meanings: remote address → `Client`, logging host → `Device`; `RunAs` only when it differs from the user; use typed elements such as `LogonType` and `ProcessId` before generic `Data`.
3. Prefer name/value `Data` over a long `Description` blob, and never leave a parsed field unemitted.
4. Parsers: always end the token rules with a catch-all, use positional groups for fixed-width data, and make CSV splitting quote-aware.
5. Keep the source's own type value in `TypeId` (or a `Data` item), and guard every element against an empty source value, so that an unknown or incomplete record is visible rather than plausible.
6. For an action the translation doesn't recognise, emit a flagged event for that record (or an error for that record alone), never one that ends the whole stream.

**Learning behaviour**

1. Treat a stream that a serving binding turns into zero events, or into errors, as drift: relearn, or at least raise it on the pipeline's Learning Status tab (see `docs/learning-flow-parser-drift-not-detected.md`).
2. Let the record split name a parent whose children are the records, whatever they are called (`batch/*`), so GATEWAY-AUDIT can learn. A list of element names would be brittle: a name missing from the sample, like `diagnostic`, would fall outside the boundary (see `docs/learning-flow-mixed-record-elements.md`, fix 1).

**Operations**

1. Reprocess APP-EVENTS so the feed has live output again.
2. Deploy the model timeout fix (`AiServiceImpl`, `ApacheHttpClientBuilder`): it removes the 60-second cap that cost AUDITD-AUSEARCH 15 failed attempts and 1.74 million tokens.

**Test scenarios**

1. Add reference outputs (`*.events.xml`) for `auditd-ausearch.log`, `apache-access.log` and `netfilter.log`, so these feeds can be scored automatically.
2. Add samples for the branches no sample exercised: a `logoff` row for DOOR-ACCESS, a 4625 failed logon for WINDOWS-SECURITY, full-width and blank columns for MAINFRAME, and a SYSCALL line with all fields plus two PATH records for AUDITD.
3. Add a reference output for `nested-audit.xml`, and records that exercise the catch-alls found on the added feeds: a `kind` other than login and logout in `records.json`, a `did` other than login, logout and open in `nested-audit.xml`, and a 4625 failed logon in `events-fragments.xml`.
4. Upload the remaining NETFILTER drift files (`drift-2-mac-decode`, `drift-3-new-traffic`). Reading the parser, the reviewers expect drift 2 to lose every MAC address silently and drift 3 to lose about 6 of 40 lines.
