/*
 * Copyright 2016-2026 Crown Copyright
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package stroom.learningflow.scenario;

import stroom.learningflow.learning.Target;
import stroom.learningflow.learning.TargetChecks;
import stroom.learningflow.scoring.OutputRecords;
import stroom.learningflow.scoring.Scorecard;
import stroom.learningflow.shared.Check;
import stroom.learningflow.shared.CheckSetting;
import stroom.learningflow.shared.CheckState;
import stroom.learningflow.shared.ExtractionQualityParameters;
import stroom.learningflow.shared.RecordBoundary;
import stroom.learningflow.shared.YieldBasis;
import stroom.learningflow.shared.YieldParameters;
import stroom.util.shared.StoredError;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// Tests `TargetChecks`, which judges the target events the model proposes and the record boundaries it
/// names.
///
/// A proposed target is judged as one event: it must be one well-formed event, and only the scorers of
/// meaning weigh it, since a stream's coverage and yield say nothing about one event on its own.
class TestTargetChecks {

    private static final String RECORD = "2020-06-17T08:00:00.000Z,jim,warehouse,logon";
    private static final String EVENT = firstEvent(Scenarios.resource("csv-logon.events.xml"));
    private static final String JSON = "http://www.w3.org/2013/XSL/json";

    /// Builds the scorecard of a strict document, with an expected yield of one event per five lines, which
    /// no single-line record could meet if yield were applied to a target.
    private static Scorecard scorecard() {
        return Scorecard.of(List.of(
                CheckSetting.of(Check.COMPILE, CheckState.GATE),
                new CheckSetting(Check.COVERAGE, CheckState.GATE, 0.9, null),
                new CheckSetting(Check.YIELD, CheckState.GATE, 0.5, new YieldParameters(0.2, YieldBasis.LINES)),
                new CheckSetting(Check.CONFORMANCE, CheckState.GATE, 1.0, null),
                new CheckSetting(Check.QUALITY, CheckState.GATE, 0.7,
                        new ExtractionQualityParameters(false, List.of("EventSource/User/Id")))),
                new Scenarios().scorers());
    }

    @Test
    void aValidEventIsAcceptedWhateverTheStreamScorersWouldSay() {
        assertThat(TargetChecks.judge(scorecard(), RECORD, TargetChecks.asDocument(EVENT))).isEmpty();
    }

    @Test
    void anXmlDeclarationBeforeTheEventIsSetAside() {
        final String declared = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" + EVENT;
        assertThat(TargetChecks.asDocument(declared)).doesNotContain("<?xml");
        assertThat(TargetChecks.judge(scorecard(), RECORD, TargetChecks.asDocument(declared))).isEmpty();
    }

    @Test
    void aTargetThatIsAlreadyADocumentIsNotWrappedAgain() {
        // A target whose root is an Events element with a namespace prefix is already a document. Wrapping it
        // again would make the scorers judge a document inside a document.
        final String prefixed = "<evt:Events xmlns:evt=\"event-logging:3\"><evt:Event/></evt:Events>";

        assertThat(TargetChecks.asDocument(prefixed))
                .describedAs("a root of its own, whatever prefix it carries")
                .isEqualTo(prefixed);
    }

    @Test
    void anEventThatMerelyMentionsTheRootIsStillWrapped() {
        // Conversely, the characters "<Events" appearing in text, an attribute or a comment do not make a
        // root.
        final String mentions = "<Event><Description>wrapped in &lt;Events&gt;</Description></Event>";

        assertThat(TargetChecks.asDocument(mentions))
                .startsWith("<Events ")
                .endsWith("</Events>");
    }

    @Test
    void anEventAloneIsWrapped() {
        assertThat(TargetChecks.asDocument(EVENT)).startsWith("<Events ").endsWith("</Events>");
    }

    @Test
    void anEventThatDoesNotParseIsRefusedNotPassedUnscored() {
        final String broken = EVENT.replace("</EventTime>", "");
        final List<StoredError> refusal = TargetChecks.judge(scorecard(), RECORD, TargetChecks.asDocument(broken));
        assertThat(refusal).hasSize(1);
        assertThat(refusal.get(0).getMessage()).contains("not well-formed");
    }

    @Test
    void twoEventsForOneRecordAreRefused() {
        final List<StoredError> refusal = TargetChecks.judge(scorecard(), RECORD,
                TargetChecks.asDocument(EVENT + "\n" + EVENT));
        assertThat(refusal).hasSize(1);
        assertThat(refusal.get(0).getMessage()).contains("2 events");
    }

    @Test
    void aMeaninglessEventIsRefusedByTheScorersOfMeaning() {
        // An event that is valid but extracts nothing, carrying the record's fields as Data under Unknown.
        final String degenerate = String.join("\n",
                "<Event xmlns=\"event-logging:3\">",
                "  <EventTime><TimeCreated>2020-06-17T08:00:00.000Z</TimeCreated></EventTime>",
                "  <EventSource>",
                "    <System><Name>Door Access</Name><Environment>Test</Environment></System>",
                "    <Generator>CSV</Generator>",
                "    <Device><Name>unknown</Name></Device>",
                "  </EventSource>",
                "  <EventDetail>",
                "    <TypeId>record</TypeId>",
                "    <Unknown>",
                "      <Data Name=\"who\" Value=\"jim\"/><Data Name=\"where\" Value=\"warehouse\"/>",
                "      <Data Name=\"what\" Value=\"logon\"/>",
                "    </Unknown>",
                "  </EventDetail>",
                "</Event>");
        final List<StoredError> refusal = TargetChecks.judge(scorecard(), RECORD,
                TargetChecks.asDocument(degenerate));
        // Only extraction quality refuses it; coverage and yield do not apply to one event on its own.
        assertThat(refusal).isNotEmpty();
        assertThat(refusal).allMatch(error -> "ExtractionQuality".equals(error.getElementId().getId())
                                              || error.getMessage().startsWith("Extraction quality"));
    }

    @Test
    void fidelityReadsNamesByNamespaceNotPrefix() {
        // The same tree written with a namespace prefix on every element, as some transforms write it.
        final String prefixed = ("<evt:Events xmlns:evt=\"event-logging:3\">" + EVENT + "</evt:Events>")
                .replaceAll("<(/?)([A-Z])", "<$1evt:$2")
                .replace("<evt:Events xmlns:evt", "<evt:Events xmlns:evt");
        final List<Target> targets = List.of(Target.of(RECORD, EVENT));
        assertThat(TargetChecks.fidelity(prefixed, targets)).isEmpty();
    }

    @Test
    void fidelityShowsTheNearestEventBesideTheTarget() {
        final String swapped = EVENT.replace("<Name>warehouse</Name>", "<Name>jim</Name>");
        final List<StoredError> shortfalls = TargetChecks.fidelity(TargetChecks.asDocument(swapped),
                List.of(Target.of(RECORD, EVENT)));
        assertThat(shortfalls).hasSize(1);
        assertThat(shortfalls.get(0).getMessage())
                .contains("Produce exactly:")
                .contains("The nearest event produced was:")
                .contains("<Name>jim</Name>");
    }

    @Test
    void preservationDoesNotDemandAJsonKeyThatTheTargetUsesAsAName() {
        // A JSONParser record: every key is a `key` attribute, which is a name, not a value the parser emits.
        final String record = "<map xmlns=\"" + JSON + "\"><string key=\"traceId\">a479ab28</string>"
                              + "<string key=\"name\">Get variable</string></map>";
        final String event = """
                <Event><EventDetail><Unknown>\
                <Data Name="traceId" Value="a479ab28"/><Data Name="name" Value="Get variable"/>\
                </Unknown></EventDetail></Event>""";
        final String parsed = "<array xmlns=\"" + JSON + "\">" + record + "</array>";
        assertThat(TargetChecks.preservation(parsed, List.of(Target.of(record, event)))).isEmpty();
    }

    @Test
    void preservationStillReportsAValueTheParserDropped() {
        final String record = "<map xmlns=\"" + JSON + "\"><string key=\"traceId\">a479ab28</string>"
                              + "<string key=\"name\">Get variable</string></map>";
        final String event = """
                <Event><EventDetail><Unknown>\
                <Data Name="traceId" Value="a479ab28"/><Data Name="name" Value="Get variable"/>\
                </Unknown></EventDetail></Event>""";
        // The parser lost the name's value.
        final String parsed = "<array xmlns=\"" + JSON + "\"><map><string key=\"traceId\">a479ab28</string>"
                              + "</map></array>";
        final List<StoredError> shortfalls = TargetChecks.preservation(parsed, List.of(Target.of(record, event)));
        assertThat(shortfalls).hasSize(1);
        assertThat(missing(shortfalls.get(0))).isEqualTo("[Get variable]");
    }

    @Test
    void preservationCountsAnXmlRecordsAttributeValuesAsData() {
        // An XMLFragmentParser record: its attribute values are data, as Windows events carry them.
        final String record = "<Event><System><TimeCreated SystemTime=\"2026-10-19T08:06:02Z\"/></System>"
                              + "<EventData><Data Name=\"TargetUserName\">alice</Data></EventData></Event>";
        final String event = """
                <Event><EventTime><TimeCreated>2026-10-19T08:06:02Z</TimeCreated></EventTime>\
                <EventDetail><Unknown><Data Name="TargetUserName" Value="alice"/></Unknown></EventDetail>\
                </Event>""";
        final String parsed = "<Events>" + record + "</Events>";
        assertThat(TargetChecks.preservation(parsed, List.of(Target.of(record, event)))).isEmpty();
    }

    @Test
    void preservationOfATextRecordIsUnchanged() {
        // A DSParser record is text, so the record itself is the only source of values and any part of it
        // counts.
        final String parsed = "<records xmlns=\"records:2\"><record>"
                              + "<data name=\"when\" value=\"2020-06-17T08:00:00.000Z\"/>"
                              + "<data name=\"who\" value=\"jim\"/></record></records>";
        final List<StoredError> shortfalls = TargetChecks.preservation(parsed, List.of(Target.of(RECORD, EVENT)));
        assertThat(shortfalls).hasSize(1);
        assertThat(missing(shortfalls.get(0)))
                .contains("warehouse")
                .contains("logon")
                .doesNotContain("jim");
    }

    /// The values a preservation shortfall names as missing, without the record it quotes.
    private static String missing(final StoredError shortfall) {
        final String message = shortfall.getMessage();
        final int start = message.indexOf("no value for ") + "no value for ".length();
        return message.substring(start, message.indexOf(", which the target event needs"));
    }

    private static String firstEvent(final String events) {
        final int start = events.indexOf("<Event>");
        final int end = events.indexOf("</Event>") + "</Event>".length();
        return events.substring(start, end);
    }

    @Test
    void aRecordElementIsCountedByItsOutermostOccurrences() {
        final OutputRecords document = OutputRecords.parse(
                "<log><item id=\"1\"><item id=\"1a\"/></item><item id=\"2\"/></log>").orElseThrow();
        // The inner item is part of the outer record, not a record of its own; the count and the wholeness
        // check agree on two.
        assertThat(TargetChecks.elementsNamed(document, "item")).hasSize(2);
        assertThat(TargetChecks.elementsNamed(document, "item").get(0)).contains("1a");
        assertThat(TargetChecks.recordElement(document, "item", 0.9)).isEmpty();
        assertThat(TargetChecks.recordElement(document, "log", 0.9)).isPresent();
        // One record whose repeated children are fields is a record, not a container of them; one whose
        // repeated child has structure of its own is the container.
        final OutputRecords one = OutputRecords.parse(
                "<records><record><data name=\"a\" value=\"1\"/><data name=\"b\" value=\"2\"/></record></records>")
                .orElseThrow();
        assertThat(TargetChecks.recordElement(one, "record", 0.9)).isEmpty();
        final OutputRecords nested = OutputRecords.parse(
                "<log><entries><entry><who>a</who></entry><entry><who>b</who></entry></entries></log>").orElseThrow();
        assertThat(TargetChecks.recordElement(nested, "entries", 0.9)).isPresent();
        // Only an element's own children count: one Event holding repeated structured Data two levels down is
        // a record.
        final OutputRecords event = OutputRecords.parse("<Events><Event><System><Id>1</Id></System><EventData>"
                + "<Data><Text>a</Text></Data><Data><Text>b</Text></Data></EventData></Event></Events>").orElseThrow();
        assertThat(TargetChecks.recordElement(event, "Event", 0.9)).isEmpty();
    }

    @Test
    void aBoundaryThatIsNotANameNamesNothingRatherThanBreakingTheXPath() {
        // The model's reply is put into an XPath predicate, so a reply that could close the predicate must not
        // reach Saxon. A boundary that is not a valid name matches nothing.
        final OutputRecords document = OutputRecords.parse("<log><item/><item/></log>").orElseThrow();

        assertThat(document.recordsBy(RecordBoundary.ofElement("item"))).hasSize(2);
        assertThat(document.recordsBy(RecordBoundary.ofElement("a']|//*[local-name()='item"))).isEmpty();
        assertThat(document.recordsBy(RecordBoundary.ofArray("a]|//x["))).isEmpty();
        assertThat(document.recordsBy(RecordBoundary.ofArray("events"))).isEmpty();
    }

    @Test
    void theRootsRecordsAreATopLevelArraysItems() {
        // The parser wraps a top-level array in a keyless array under the root map, which no key could name,
        // so `root` reaches its items. A root map of values, as JSON lines produce, gives the values.
        final String ns = "http://www.w3.org/2013/XSL/json";
        final OutputRecords array = OutputRecords.parse("<map xmlns=\"" + ns + "\"><array>"
                + "<map><string key=\"a\">1</string></map><map><string key=\"a\">2</string></map></array></map>")
                .orElseThrow();
        assertThat(TargetChecks.rootRecords(array)).hasSize(2);
        assertThat(TargetChecks.rootRecords(array).get(0)).contains("key=\"a\"").contains(">1<");
        final OutputRecords lines = OutputRecords.parse("<map xmlns=\"" + ns + "\">"
                + "<map><string key=\"a\">1</string></map><map><string key=\"a\">2</string></map></map>")
                .orElseThrow();
        assertThat(TargetChecks.rootRecords(lines)).hasSize(2);
        final OutputRecords keyed = OutputRecords.parse("<map xmlns=\"" + ns + "\"><array key=\"events\">"
                + "<map><string key=\"a\">1</string></map></array></map>").orElseThrow();
        assertThat(TargetChecks.rootRecords(keyed)).describedAs("a keyed array is named by its key").hasSize(1);
        assertThat(TargetChecks.rootRecords(keyed).get(0)).startsWith("<array");
    }

    @Test
    void markupRecordsAreCountedByTheKindTheirRepresentativeWasChosenBy() {
        final String login = "<map xmlns=\"j\"><string key=\"kind\">login</string>"
                             + "<string key=\"user\">a</string></map>";
        final String logout = "<map xmlns=\"j\"><string key=\"kind\">logout</string></map>";
        final List<String> records = List.of(login, login.replace(">a<", ">b<"), logout);
        final List<String> representatives = TargetChecks.representatives(records);
        assertThat(representatives).containsExactly(login, logout);
        // The first line of every record is the same, so a count by it would say three of each kind.
        assertThat(TargetChecks.count(records, login)).isEqualTo(2);
        assertThat(TargetChecks.count(records, logout)).isEqualTo(1);
    }
}
