# Shapeshifter AI preservation check counts the record's markup as source data

## Summary

After the model has written its target events, Shapeshifter AI re-runs the parser and checks that every value the targets take from the source record is still in the parser's output.
This is the **preservation** check (`TargetChecks.preservation`).

The check works out which values a target "takes from the record" by searching the record's **serialised XML**, tags and attributes included.
It then looks for those values only among the **text values** of the parser's output.
So any word in a target that happens to match part of the record's markup, such as a JSON key, is treated as source data that the parser has lost, even though no parser could ever produce it as a value.

The result is a `preservation-short` outcome for a parser that is working correctly.
In the default "Any feed" flow, the step that runs this check (`again`) has no transition for that outcome, so the attempt ends and parks with the misleading reason **"JSONParser failed on its input"**.

JSON feeds are the most exposed, because the JSON parser turns every key into a `key="…"` attribute in the record's markup.
Feeds parsed with the XML fragment parser are likely to be affected in the same way (see [Scope](#scope)).

## Observed on the dev instance

Feed `OTEL-TRACES`, pipeline `OTEL-TRACES supervised` (`53a2fd3f-0b51-476f-93e3-1aedddaa3378`), document `Any feed` (`bbc4cf72-ad52-463c-b6e8-e5cfd37f77a0`).
Input stream 2152 is `otel-data/002959554.json` from the scenario folder: one OpenTelemetry (OTLP) trace export with 3 `resourceSpans` blocks and 6 spans.

```
shapeshifter_attempt   id=147, input_meta_id=2152, status=PARKED, score=NULL,
                       tokens_spent=228,662, decision='JSONParser failed on its input'
shapeshifter_deferral  meta_id=2152, attempts=2,
                       reason='Another node is learning for this pipeline; this stream waits for what it learns'
```

The turns of attempt 147:

| Turn | Step | Question | Outcome |
|---|---|---|---|
| 1 | chain | Chain: choose from [DSParser, JSONParser, XMLFragmentParser, XSLTFilter] | passed (`JSONParser -> XSLTFilter`) |
| 2 | split | Split: what one record is, for JSONParser | passed (`resourceSpans`) |
| 3 | `first` | Configure: XSLTFilter XSLT | conformance-short (score 0) |
| 4 | `first` | Configure: XSLTFilter XSLT, after 10 shortfall(s) | conformance-short (score 0) |
| 5 | target | Target: what record 1 of 2 becomes, after 9 shortfall(s) | conformance-short |
| 6 | target | Target: what record 1 of 2 becomes, after 2 shortfall(s) | conformance-short |
| 7 | target | Target: what record 1 of 2 becomes, after 2 shortfall(s) | conformance-short |
| 8 | target | Target: what record 1 of 2 becomes, after 4 shortfall(s) | **passed** |
| 9 | target | Target: what record 2 of 2 becomes | **passed** |
| — | `again` | *(nothing asked: the JSON parser takes no configuration)* | **preservation-short** → parked |

Turns 3 to 7 are ordinary learning: the model's events were invalid, it was told why, and it fixed them (see [Context](#context-the-conformance-shortfalls-before-it)).
The fault is the last row.
Both targets were accepted, and the attempt then stopped on a check that no parser could pass.

The stored decision gives only the one-line reason, not the check's messages (see [Related problems](#related-problems)).
The missing value was worked out by re-running the check's logic on the stream and the two accepted targets, as described below.

## How the check goes wrong, step by step

### 1. What a record looks like

The split chosen was `resourceSpans`, so each item of that array is one record.
The JSON parser turns the JSON into the XSL/json vocabulary (`http://www.w3.org/2013/XSL/json`), where every JSON key becomes a `key` attribute.
The first record, shortened:

```xml
<map>
  <map key="resource">
    <array key="attributes">
      <map><string key="key">host.name</string><map key="value"><string key="stringValue">pc</string></map></map>
      <map><string key="key">process.pid</string><map key="value"><string key="intValue">1607316</string></map></map>
      …
    </array>
  </map>
  <array key="scopeSpans">
    <map>
      <map key="scope"><string key="name">MySQL Connector/J</string><string key="version">9.4.0</string></map>
      <array key="spans">
        <map>
          <string key="traceId">a479ab28a8df36da2f56a6da99f7998c</string>
          <string key="spanId">6ebf47df23e168b8</string>
          <string key="parentSpanId"></string>
          <number key="flags">257</number>
          <string key="name">Get variable '@@session.transaction_read_only'</string>
          …
        </map>
      </array>
    </map>
  </array>
</map>
```

The **text values** of this record are things like `pc`, `1607316`, `a479ab28…`, `6ebf47df23e168b8` and `Get variable '@@session.transaction_read_only'`.
The **markup** contains element names (`map`, `array`, `string`, `number`) and key names (`traceId`, `spanId`, `parentSpanId`, `startTimeUnixNano`, …).

The target step stores this record as `XdmNode.toString()`, which is the whole serialised XML, markup and all (`TargetChecks.rootRecords` and `arrayItems`, `TargetChecks.java:101` and `:109`).

### 2. The accepted target

Turn 8's target event for this record (abridged):

```xml
<Event xmlns="event-logging:3">
  <EventTime><TimeCreated>2026-11-16T10:47:45.335Z</TimeCreated></EventTime>
  <EventSource>
    <System><Name>unknown_service:java</Name><Environment>Linux 6.12.108-1-MANJARO</Environment></System>
    <Generator>MySQL Connector/J</Generator>
    <Device><HostName>pc</HostName></Device>
    <User><Id>stroomuser</Id></User>
  </EventSource>
  <EventDetail>
    <TypeId>SELECT</TypeId>
    <Process>
      <Action>Execute</Action>
      <Type>Application</Type>
      <Command>SELECT (...)</Command>
      <ProcessId>1607316</ProcessId>
      <ThreadId>23857</ThreadId>
      <Data Name="TraceId" Value="a479ab28a8df36da2f56a6da99f7998c"/>
      <Data Name="SpanId" Value="6ebf47df23e168b8"/>
      <Data Name="LinkedSpanId" Value="9ee398590a5d7479"/>
      …
    </Process>
  </EventDetail>
</Event>
```

This is a reasonable event.
`SpanId` is a label the model chose for a `Data` element; the value it labels, `6ebf47df23e168b8`, comes from the record.

### 3. Which values the check says the target needs

`TargetChecks.sourceValues` (`TargetChecks.java:353`) takes every text and attribute value in the target event, at least 2 characters long, and keeps those for which `target.record().contains(value)` is true:

```java
if (value.length() >= VALUE_LENGTH_COUNTED && target.record().contains(value)) {
    values.add(value);
}
```

Because `target.record()` is the serialised XML, `contains` searches the markup as well as the data:

| Target value | Found in the record? | Why | Counted as needed? |
|---|---|---|---|
| `pc` | yes | text value | yes (correct) |
| `6ebf47df23e168b8` | yes | text value | yes (correct) |
| `MySQL Connector/J` | yes | text value | yes (correct) |
| `Execute` | no | — | no (correct: a constant) |
| `TraceId` | no | the key is `traceId`, and `contains` is case-sensitive | no |
| `LinkedSpanId` | no | — | no |
| **`SpanId`** | **yes** | **inside the key `parentSpanId`, i.e. `<string key="parentSpanId">`** | **yes (wrong)** |

The method's own Javadoc says the values are those that occur "in the record's **text**", so this is a mismatch between the intent and the code.

### 4. Where the check looks for them

`TargetChecks.dataValues` (`TargetChecks.java:368`) collects what the parser's output carries.
It reads only text nodes and the `value` attributes of `data` elements:

```java
records.evaluate(record, "descendant::*[local-name() = 'data']/@value | descendant::text()")
```

`SpanId` is never a text value in the parser's output; it exists only inside an attribute name.
So the check reports it missing, and would do so for any JSON parser output of this record:

```
Record kind 1: the records carry no value for [SpanId], which the target event needs from the record "<map>…</map>". Extract every field the event uses.
```

Re-running steps 3 and 4 on stream 2152 against both accepted targets gives exactly one missing value for each, and it is the same one:

```
kind 1 needs 18 values, missing: ['SpanId']
kind 2 needs 16 values, missing: ['SpanId']
```

Every other value the targets take from the records is in the parser's output.
Without this false positive, preservation passes.

### 5. Why the attempt parks with a misleading reason

The default "Any feed" flow is:

```
CHAIN → SPLIT → CONFIGURE parser (on yield-short goto split)
      → first: CONFIGURE transform, 2 candidates (on passed goto end, else goto target)
      → TARGET (3 kinds)
      → again: CONFIGURE parser
      → CONFIGURE transform (on preservation-short goto again, else goto admit)
      → ADMIT
```

At `again`, the JSON parser is run again without asking the model anything (`Conversation.configure`, whose branches for an element that takes no configuration from the model end at `Conversation.java:742` and `:755`).
With targets in hand, `judge` applies the preservation check to a parser (`Conversation.java:892`):

```java
if (runner.parser() && checks.contains(Check.PRESERVATION)) {
    final List<StoredError> lacking = TargetChecks.preservation(result.output(), walk.targets);
    if (!lacking.isEmpty()) {
        return new Judged(StepOutcome.PRESERVATION_SHORT, lacking, null);
    }
}
```

The outcome goes to `runOnly(step, judged, runner.elementType() + " failed on its input")`.
`again` names no transitions, so `runOnly` (`Conversation.java:425`) abandons the attempt with that reason, and the attempt parks.
The model is never asked anything after this point, so it has no chance to work around the shortfall.

## Scope

- **JSON input (JSONParser)**: affected, as shown above.
  A target value counts as needed if it is a substring of any key name in the record, or of the vocabulary's element names (`map`, `array`, `string`, `number`, `boolean`, `null`) or the attribute name `key`.
  With `VALUE_LENGTH_COUNTED = 2`, short labels are especially likely to match.
  For example, `Id` is inside `traceId`, and `Name` would match a key such as `hostName`.
- **XML input (XMLFragmentParser)**: very likely affected, not verified.
  The record is again serialised XML, so a target label that matches an element or attribute name in the source (for example `Data Name="EventID"` against `<EventID>` in a Windows event) would count as needed.
- **Text input (DSParser)**: not affected.
  The record is the raw line, which has no markup.

The bug only bites when a run reaches a step that checks preservation at the parser after targets exist.
In the default flow that means `first` must fail, which is why the feeds learned so far (see `docs/smart-pipelines/learning-flow-demo-evaluation.md`) have not hit it.

## Steps to reproduce

### On a running Stroom

1. Set up the Shapeshifter AI demo content (System / Shapeshifter AI Demo), with a working `Learning model`.
2. Create a feed `OTEL-TRACES` and a pipeline `OTEL-TRACES supervised` built like the other `<FEED> supervised` pipelines (Shapeshifter AI → "Any feed", schema group `EVENTS`), with an enabled processor filter on the feed's `Raw Events`.
3. Upload `stroom-learning-flow/stroom-learning-flow/src/testFixtures/resources/stroom/shapeshifter/ai/scenario/otel-data/002959554.json`.
4. Wait for the attempt to finish and look at `shapeshifter_attempt` and `shapeshifter_turn` for the pipeline.

**Expected:** after the targets pass, the parser passes preservation, because it carries every value the targets take from the records.
Learning carries on to the transform.

**Actual:** the attempt parks with `JSONParser failed on its input`.

This depends on the model's first two XSLT candidates failing and on the model choosing a label that matches a key, so a live run will not reproduce it every time.
The unit test below reproduces it deterministically.

### In a unit test

Add to `stroom-learning-flow/stroom-learning-flow/src/test/java/stroom/shapeshifter/ai/scenario/TestTargetChecks.java`:

```java
@Test
void preservationIgnoresAKeyNameThatMatchesATargetLabel() {
    // One OTLP span, as the JSON parser writes it. "SpanId" occurs only inside the key "parentSpanId".
    final String parsed = """
            <map xmlns="http://www.w3.org/2013/XSL/json">
              <array key="spans">
                <map>
                  <string key="spanId">6ebf47df23e168b8</string>
                  <string key="parentSpanId"></string>
                  <string key="name">Ping</string>
                </map>
              </array>
            </map>""";
    final String record = TargetChecks.arrayItems(OutputRecords.parse(parsed).orElseThrow(), "spans").get(0);
    final String event = """
            <Event xmlns="event-logging:3">
              <EventDetail>
                <TypeId>Ping</TypeId>
                <Process>
                  <Data Name="SpanId" Value="6ebf47df23e168b8"/>
                </Process>
              </EventDetail>
            </Event>""";

    final List<StoredError> lacking = TargetChecks.preservation(parsed, List.of(Target.of(record, event)));

    assertThat(lacking).isEmpty();
}
```

With the current code, this should fail with `the records carry no value for [SpanId]`.
The test is a sketch and has not been run.
Check that `OutputRecords.parse` yields records for a document shaped like this; if it doesn't, wrap the map the way the JSON parser's real output is wrapped.

A second test should check that the fix still catches a real loss.
Use the same target, but a parser output with the `spanId` value removed; `lacking` must name `6ebf47df23e168b8`.

## Proposed fix

Compare the target's values with the record's **text values**, not its serialised XML, so that `sourceValues` does what its Javadoc says.

In `TargetChecks.sourceValues`, when the record is markup, collect its values the same way `dataValues` does, and match each target value against those:

```java
static Set<String> sourceValues(final Target target) {
    final Set<String> recordValues = recordValues(target.record());
    final Set<String> values = new TreeSet<>();
    OutputRecords.parse(asDocument(target.event().orElseThrow())).ifPresent(document -> {
        for (final XdmNode event : document.records()) {
            document.evaluate(event, "descendant::text() | descendant::*/@*").forEach(item -> {
                final String value = item.getStringValue().strip();
                if (value.length() >= VALUE_LENGTH_COUNTED
                    && recordValues.stream().anyMatch(recordValue -> recordValue.contains(value))) {
                    values.add(value);
                }
            });
        }
    });
    return values;
}

/// The values a record carries: its text and attribute values where it is markup, else the record itself.
private static Set<String> recordValues(final String record) {
    return OutputRecords.parse(record)
            .map(parsed -> { /* text nodes, plus attribute values other than the JSON vocabulary's key */ })
            .orElse(Set.of(record));
}
```

Points to settle when making the change:

- **Text records stay as they are.** If the record does not parse as XML, the record text itself is the only value, which keeps DSParser behaviour unchanged.
- **XML records' attribute values.** For the XML fragment parser, source attribute values (for example `SystemTime="…"` in a Windows event) are real data and should count as record values.
  For the JSON vocabulary, the `key` attribute is a name, not data, and should be excluded.
  `dataValues` must then also look at the same attribute values, or a genuine attribute value will be demanded and never found.
  (`dataValues` matches `local-name() = 'data'` in lower case, so Windows' `<Data Value="…">` is not read either; check this while you are there.)
- **Matching within a value.** A target value that is part of a longer record value (for example `stroom` inside a connection string) should still count, as now, so keep `contains` against each value, not equality.

## Related problems

These came up while diagnosing the attempt.
They are separate from the bug above, and some may deserve their own tasks.

1. **The parked reason hides the cause.**
   "JSONParser failed on its input" reads as a parse error, but the parser ran cleanly and the real outcome was `preservation-short`.
   The run-only branch of `configure` could name the outcome, for example `"JSONParser fell short at step 'again': preservation-short"`.
2. **The check's messages are not stored.**
   `shapeshifter_attempt.decision` keeps only the reason, and no turn is written for a run-only step, so the pipeline's Learning Status tab cannot show which values were missing.
   Here they had to be reconstructed by hand.
   Storing the abandon feedback with the decision would make this kind of park easy to diagnose.
3. **`again` has no way back to the model.**
   For a run-only parser such as the JSON parser, there is nothing to reconfigure, so a preservation shortfall at `again` always ends the attempt.
   The flow could send `preservation-short` back to `target`, with the shortfall as feedback, so the model can drop or rename the value it cannot have.
4. **The record grain loses spans.**
   With the split at `resourceSpans`, each record is a resource block, not a span.
   The block with four spans became one event, with three spans squashed into `<Data Name="ChildSpan" Value="…"/>` strings (turn 9).
   The larger OTLP files in `otel-data/` have up to 15 blocks and 717 spans per file, so most spans would be buried.
   The better record is an item of `spans`, but then the `resource` and `scope` attributes are outside the record.
   This is the same kind of limit as `docs/learning-flow-mixed-record-elements.md`: the split can name only one array or element.

## Context: the conformance shortfalls before it

These are expected learning behaviour, not part of the bug, but they explain why the run reached `again` at all.

- **Turn 3** put `<TimeCompleted>` in `EventTime`, and **turn 4** put `<Data Name="EndTime">` there.
  In event-logging v3, `EventTime` may only hold `TimeCreated` and `TimeSource`, so every event failed validation (score 0), both candidates of `first` were used, and the flow went to `target`.
- **Turns 5 to 7** used values the schema does not allow: `Process/Action` was `EXECUTE` (the schema allows `Execute`, `Startup`, `Shutdown` and so on) and `Process/Type` was `SELECT` (only `OS`, `Service` or `Application` are allowed).
  The model fixed these in response to the feedback, and turns 8 and 9 passed.

## Acceptance criteria

- `TargetChecks.sourceValues` counts a target value as needed only if it occurs in a text or data value of the source record, never only in an element or attribute name.
- The new regression test in `TestTargetChecks` fails before the fix and passes after it.
- A test with a genuinely dropped value still reports `preservation-short`, naming that value.
- Existing scenario tests that rely on preservation still pass, in particular `TestScenario44Auditd` (dropped EXECVE arguments) and `TestScenario47FixedWidth` (four-column parser).
- Re-learning stream 2152 on `OTEL-TRACES` no longer parks at `again` because of a key name.
- A CHANGELOG entry is added with `./log_change.sh`.
