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


package stroom.shapeshifter.ai.scenario;

import stroom.shapeshifter.ai.learning.Target;
import stroom.shapeshifter.ai.learning.TargetChecks;
import stroom.shapeshifter.ai.scoring.OutputRecords;
import stroom.shapeshifter.ai.scoring.Scorecard;
import stroom.shapeshifter.shared.ExtractionQualityParameters;
import stroom.shapeshifter.shared.RecordBoundary;
import stroom.shapeshifter.shared.SchemaConformanceParameters;
import stroom.shapeshifter.shared.ScorerSetting;
import stroom.shapeshifter.shared.ScorerType;
import stroom.shapeshifter.shared.YieldBasis;
import stroom.shapeshifter.shared.YieldParameters;
import stroom.util.shared.StoredError;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A proposed target is judged as one event: it must be one well-formed event, and only the scorers of
 * meaning weigh it, since a stream's coverage and yield say nothing about one event on its own.
 */
class TestTargetChecks {

    private static final String RECORD = "2020-06-17T08:00:00.000Z,jim,warehouse,logon";
    private static final String EVENT = firstEvent(Scenarios.resource("csv-logon.events.xml"));

    /**
     * The full scorer set of a strict document, with a yield expectation of one event in five lines that
     * no single-line record could meet were it applied.
     */
    private static Scorecard scorecard() {
        return new Scorecard(List.of(
                new ScorerSetting(ScorerType.COMPILE, 0.0, 1.0, true, null),
                new ScorerSetting(ScorerType.INPUT_COVERAGE, 1.0, 0.9, true, null),
                new ScorerSetting(ScorerType.YIELD, 1.0, 0.5, true, new YieldParameters(0.2, YieldBasis.LINES)),
                new ScorerSetting(ScorerType.SCHEMA_CONFORMANCE, 1.0, 1.0, true,
                        new SchemaConformanceParameters("EVENTS")),
                new ScorerSetting(ScorerType.EXTRACTION_QUALITY, 1.0, 0.7, true,
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
        // The degeneracy trap of design 01 §8.3: valid, and the record's fields carried as Data in Unknown.
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
        // Only extraction quality speaks: no coverage or yield shortfall for one event on its own.
        assertThat(refusal).isNotEmpty();
        assertThat(refusal).allMatch(error -> "ExtractionQuality".equals(error.getElementId().getId())
                                              || error.getMessage().startsWith("Extraction quality"));
    }

    @Test
    void fidelityReadsNamesByNamespaceNotPrefix() {
        // The same tree written with a prefix on every element, as one live transform did.
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
        assertThat(TargetChecks.recordElement(document, "item")).isEmpty();
        assertThat(TargetChecks.recordElement(document, "log")).isPresent();
        // One record whose repeated children are fields is a record, not a container of them; one whose
        // repeated child has structure of its own is the container.
        final OutputRecords one = OutputRecords.parse(
                "<records><record><data name=\"a\" value=\"1\"/><data name=\"b\" value=\"2\"/></record></records>")
                .orElseThrow();
        assertThat(TargetChecks.recordElement(one, "record")).isEmpty();
        final OutputRecords nested = OutputRecords.parse(
                "<log><entries><entry><who>a</who></entry><entry><who>b</who></entry></entries></log>").orElseThrow();
        assertThat(TargetChecks.recordElement(nested, "entries")).isPresent();
        // Only an element's own children count: one Event holding repeated structured Data two levels down is
        // a record.
        final OutputRecords event = OutputRecords.parse("<Events><Event><System><Id>1</Id></System><EventData>"
                + "<Data><Text>a</Text></Data><Data><Text>b</Text></Data></EventData></Event></Events>").orElseThrow();
        assertThat(TargetChecks.recordElement(event, "Event")).isEmpty();
    }

    @Test
    void aBoundaryThatIsNotANameNamesNothingRatherThanBreakingTheXPath() {
        // What the model replies goes into a predicate: a reply that could close one must not reach Saxon,
        // and a boundary from elsewhere that is not a name names nothing here.
        final OutputRecords document = OutputRecords.parse("<log><item/><item/></log>").orElseThrow();

        assertThat(document.recordsBy(RecordBoundary.ofElement("item"))).hasSize(2);
        assertThat(document.recordsBy(RecordBoundary.ofElement("a']|//*[local-name()='item"))).isEmpty();
        assertThat(document.recordsBy(RecordBoundary.ofArray("a]|//x["))).isEmpty();
        assertThat(document.recordsBy(RecordBoundary.ofArray("events"))).isEmpty();
    }

    @Test
    void theRootsRecordsAreATopLevelArraysItems() {
        // The parser wraps a top-level array in a keyless array under the root map, which no key could name:
        // root reaches its items. A root map of values, as JSON lines make, gives the values.
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
