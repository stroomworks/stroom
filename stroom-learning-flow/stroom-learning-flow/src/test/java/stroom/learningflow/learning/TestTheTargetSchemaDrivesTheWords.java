/*
 * Copyright 2026 Crown Copyright
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

package stroom.learningflow.learning;

import stroom.docref.DocRef;
import stroom.learningflow.learning.Question.Chain;
import stroom.learningflow.learning.Question.Configuration;
import stroom.learningflow.learning.Question.TargetFor;
import stroom.learningflow.shared.BusinessRulesParameters;
import stroom.learningflow.shared.Check;
import stroom.learningflow.shared.CheckSetting;
import stroom.learningflow.shared.CheckState;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.shared.XPathAssertion;
import stroom.xmlschema.shared.XmlSchemaDoc;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// Tests that the document's target schema setting drives the wording of the questions.
///
/// Changing the one setting changes, together, the namespace, the `xsi:schemaLocation` and the version in every
/// prompt, and the wrapper the target checks put a proposed event in. `TestConformanceAgainstTheTargetSchema`
/// checks that the conformance check follows the same setting.
class TestTheTargetSchemaDrivesTheWords {

    private static final Sample SAMPLE = new Sample("a,b,c\n", Map.of("Feed", "DOOR-ACCESS"));

    /// The event-logging transformation rules as one passage, with the target placeholders still in it. The
    /// built-in text is made from two templates, the rules and the target structure paragraph. This copy is
    /// owned by the test rather than read from the templates, so the two halves are checked against a fixed
    /// text rather than against themselves.
    private static final String BEFORE_THE_SPLIT = """
            The stylesheet reads records:2 (use xpath-default-namespace="records:2") and writes \
            ${targetNamespace} events: <${targetRoot} xmlns="${targetNamespace}" \
            xsi:schemaLocation="${targetSchemaLocation}" Version="${targetVersion}"> holding one <Event> per \
            record. Every Event needs EventTime/TimeCreated, EventSource and EventDetail, in that order. \
            EventSource needs System (Name, Environment), Generator and one of Device, Client, Server or Door, \
            and should name the User where the input has one. EventDetail needs TypeId and then exactly one \
            branch describing what happened, from this list and no other: Authenticate, Authorise, Search, Copy, \
            Move, Create, View, Import, Export, Update, Delete, Process, Print, Install, Uninstall, Network, \
            AntiMalware, Alert, Send, Receive. Authenticate holds Action first (Logon, Logoff, ...), then User \
            with its Id; other branches hold their own typed elements — a Description is a child of the branch's \
            element, never the branch. Element order is enforced everywhere. Write every datetime as an ISO 8601 \
            instant in UTC with milliseconds, e.g. 2026-11-11T09:18:19.000Z. Where the input's timestamp leaves \
            out the year, the date or the time zone, do not guess and never write a year as a literal: parse it \
            with stroom:format-date(value, pattern), or stroom:format-date(value, pattern, timeZone) where you \
            know the zone it was written in, declaring xmlns:stroom="stroom" on the stylesheet. That takes \
            whatever the timestamp leaves out from the time this stream was received, so the same translation is \
            right for a stream received next year. Never read a local time as if it were UTC. Every field the \
            input carries should land in a typed element. Do not put fields into Data elements or use the \
            Unknown branch: output that validates but says nothing scores as nothing. Every record must be \
            accounted for: produce an Event for each one. Where a record is deliberately not an event - a header \
            line, a column banner, a heartbeat, a comment - write <Skipped xmlns="learningflow:skips" \
            reason="..."/> in its place, saying briefly why. The marker is counted, shown to the reviewer and \
            removed before anything is written, so it is never a way to make an awkward record go away: a record \
            you cannot read is a record to ask about, not to skip.""";

    /// A schema that is not event-logging, to prove nothing is hard-coded.
    private static final TargetSchema SIGHTINGS = new TargetSchema(
            new DocRef(XmlSchemaDoc.TYPE, "schema-1", "sightings v2.4"),
            "sightings v2.4",
            "urn:sightings:2",
            "file://sightings-v2.4.xsd",
            "2.4",
            "Sightings",
            "<xs:schema/>");

    @Test
    void theTransformRulesNameTheSchemaTheDocumentMustWrite() {
        final String text = words(SIGHTINGS).render(transform());

        assertThat(text)
                .contains("writes urn:sightings:2 events")
                .contains("<Sightings xmlns=\"urn:sightings:2\"")
                .contains("xsi:schemaLocation=\"urn:sightings:2 file://sightings-v2.4.xsd\"")
                .contains("Version=\"2.4\"");
        assertThat(text)
                .describedAs("no literal event-logging is left in the part that names the target")
                .doesNotContain("file://event-logging-v3.0.0.xsd")
                .doesNotContain("Version=\"3.0.0\"");
    }

    @Test
    void theChainQuestionNamesItTooSoTheChainIsChosenForTheRightOutput() {
        assertThat(words(SIGHTINGS).render(new Chain(SAMPLE, List.of("DSParser", "XSLTFilter"), List.of())))
                .contains("transforms XML records into urn:sightings:2 events");
    }

    @Test
    void theEventLoggingRulesAreWordForWordWhatTheyWereBeforeTheyWereSplit() {
        // The structural paragraph is a separate template so that another schema can have its own. A
        // document targeting event-logging, or naming no target, must still be asked exactly the same text, so
        // this checks the two halves reassemble into the one passage, character for character.
        final String rules = BEFORE_THE_SPLIT
                .replace("${targetNamespace}", TargetSchema.EVENT_LOGGING)
                .replace("${targetRoot}", "Events")
                .replace("${targetSchemaLocation}", TargetSchema.EVENT_LOGGING + " file://event-logging-v3.0.0.xsd")
                .replace("${targetVersion}", "3.0.0");

        assertThat(words(null).render(transform())).contains(rules);
    }

    @Test
    void aDocumentThatNamesNoTargetIsAskedExactlyAsItWasBefore() {
        // Most importantly, a document saved before the target schema setting existed must get the same
        // event-logging prompt, word for word.
        final String text = words(null).render(transform());

        assertThat(text)
                .contains("writes event-logging:3 events")
                .contains("<Events xmlns=\"event-logging:3\"")
                .contains("xsi:schemaLocation=\"event-logging:3 file://event-logging-v3.0.0.xsd\"")
                .contains("Version=\"3.0.0\"");
        assertThat(words(null).render(new Chain(SAMPLE, List.of("XSLTFilter"), List.of())))
                .contains("transforms XML records into event-logging:3 events");
    }

    @Test
    void aProposedEventIsWrappedInTheTargetsOwnRoot() {
        // The target checks judge one proposed event by wrapping it as a document and scoring it as a
        // transform's output. The wrapper has to be the root of the schema the document actually writes, or the
        // conformance check would refuse every target a non-event-logging document proposes.
        assertThat(TargetChecks.asDocument("<Sighting><What>owl</What></Sighting>", SIGHTINGS))
                .startsWith("<Sightings xmlns=\"urn:sightings:2\"")
                .contains("xsi:schemaLocation=\"urn:sightings:2 file://sightings-v2.4.xsd\"")
                .contains("Version=\"2.4\"")
                .endsWith("</Sightings>");
    }

    @Test
    void anEventThatIsAlreadyAWholeDocumentIsLeftAsItIs() {
        final String document = "<Sightings xmlns=\"urn:sightings:2\"><Sighting/></Sightings>";

        assertThat(TargetChecks.asDocument(document, SIGHTINGS)).isEqualTo(document);
        assertThat(TargetChecks.asDocument("<?xml version=\"1.0\"?>\n" + document, SIGHTINGS))
                .describedAs("its declaration is dropped, as it is for event-logging")
                .isEqualTo(document);
    }

    @Test
    void withoutATargetTheWrapperIsEventLoggingAsItAlwaysWas() {
        assertThat(TargetChecks.asDocument("<Event/>", null))
                .isEqualTo(TargetChecks.asDocument("<Event/>"))
                .startsWith("<Events xmlns=\"event-logging:3\"")
                .contains("xsi:schemaLocation=\"event-logging:3 file://event-logging-v3.0.0.xsd\"")
                .contains("Version=\"3.0.0\"");
    }

    @Test
    void aSchemaThatDeclaresNoVersionOrRootFallsBackRatherThanWritingNothing() {
        // A hand-written XSD need not declare a version or a global element. Neither may leave a gap in the
        // prompt where the model is told what to write, so event-logging's values are used instead.
        final TargetSchema bare = new TargetSchema(
                new DocRef(XmlSchemaDoc.TYPE, "schema-2", "bare"), "bare", "urn:bare:1",
                "file://bare.xsd", null, null, "<xs:schema/>");

        assertThat(words(bare).render(transform()))
                .contains("<Events xmlns=\"urn:bare:1\"")
                .contains("xsi:schemaLocation=\"urn:bare:1 file://bare.xsd\"")
                .contains("Version=\"3.0.0\"");
        assertThat(TargetChecks.asDocument("<Event/>", bare)).startsWith("<Events xmlns=\"urn:bare:1\"");
    }

    @Test
    void theQuestionsNameTheElementOneRecordBecomesInTheTargetsOwnWords() {
        // The target question and the rules must not say <Event> whatever the schema. The sightings schema's
        // root holds only Sighting, so that is what one record becomes
        final TargetSchema sightings = new TargetSchema(SIGHTINGS.docRef(), SIGHTINGS.name(),
                SIGHTINGS.namespaceURI(), SIGHTINGS.systemId(), SIGHTINGS.version(), SIGHTINGS.rootElement(),
                read("sightings.xsd"));

        assertThat(words(sightings).render(targetFor()))
                .contains("Write the single <Sighting> this record should become")
                .contains("produce one <Sighting> for each one")
                .doesNotContain("<Event>")
                .doesNotContain("an Event");
    }

    @Test
    void eventLoggingsRuleAgainstDataAndUnknownIsNotGivenForAnotherSchema() {
        // The rule against Data and Unknown is specific to event-logging; in another schema a Data element
        // may mean something
        assertThat(words(SIGHTINGS).render(transform()))
                .doesNotContain("Data elements")
                .doesNotContain("Unknown branch")
                .contains("should land in a typed element. Every record must be accounted for");
        assertThat(words(null).render(transform()))
                .contains("Do not put fields into Data elements or use the Unknown branch");
    }

    @Test
    void aRootThatHoldsSeveralKindsOrIsNotDescribedIsNamedAsAPlace() {
        // SIGHTINGS has an empty XSD here, so it is not clear which element one record becomes, and the
        // question names a place instead
        assertThat(words(SIGHTINGS).render(targetFor()))
                .contains("Write the single element under <Sightings> this record should become");
        assertThat(words(SIGHTINGS).render(transform()))
                .contains("produce one element under <Sightings> for each one");
    }

    @Test
    void theBusinessRulesAreSaidToBeOverTheTargetsRecord() {
        final LearningFlowDoc doc = LearningFlowDoc.builder().uuid("d").name("door")
                .checks(List.of(new CheckSetting(Check.RULES, CheckState.ADVICE, 0.5,
                        new BusinessRulesParameters(List.of(new XPathAssertion("named", "Species != ''")),
                                true))))
                .build();
        final TargetSchema sightings = new TargetSchema(SIGHTINGS.docRef(), SIGHTINGS.name(),
                SIGHTINGS.namespaceURI(), SIGHTINGS.systemId(), SIGHTINGS.version(), SIGHTINGS.rootElement(),
                read("sightings.xsd"));

        assertThat(QuestionText.of(doc, sightings).system()).contains("each an XPath over the Sighting:");
        assertThat(QuestionText.of(doc, SIGHTINGS).system()).contains("each an XPath over the record:");
        assertThat(QuestionText.of(doc, (TargetSchema) null).system()).contains("each an XPath over the Event:");
    }

    @Test
    void aMalformedTargetIsRefusedInTheTargetsOwnWords() {
        final TargetSchema sightings = new TargetSchema(SIGHTINGS.docRef(), SIGHTINGS.name(),
                SIGHTINGS.namespaceURI(), SIGHTINGS.systemId(), SIGHTINGS.version(), SIGHTINGS.rootElement(),
                read("sightings.xsd"));

        assertThat(TargetChecks.refusal("<Sighting>", sightings).orElseThrow().getMessage())
                .endsWith("Reply with the Sighting element alone, without an XML declaration or a Sightings "
                          + "wrapper");
        assertThat(TargetChecks.refusal("<Event>", null).orElseThrow().getMessage())
                .describedAs("event-logging's words as they were")
                .endsWith("Reply with the Event element alone, without an XML declaration or an Events wrapper");
    }

    private static TargetFor targetFor() {
        return new TargetFor(SAMPLE, "a,b,c", 1, 1, List.of());
    }

    private static String read(final String file) {
        try (InputStream in = TestTheTargetSchemaDrivesTheWords.class.getResourceAsStream(file)) {
            if (in == null) {
                throw new IllegalStateException("The fixture " + file + " is not on the classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static QuestionText words(final TargetSchema target) {
        return QuestionText.of(LearningFlowDoc.builder().uuid("d").name("door").build(), target);
    }

    private static Configuration transform() {
        return new Configuration("XSLTFilter", "XSLT", SAMPLE,
                "<records xmlns=\"records:2\"><record/></records>", null, null, List.of(), false,
                Question.Records.UNKNOWN, List.of(), false, List.of());
    }
}
