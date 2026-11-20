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

package stroom.shapeshifter.ai.learning;

import stroom.docref.DocRef;
import stroom.shapeshifter.ai.learning.Question.Chain;
import stroom.shapeshifter.ai.learning.Question.Configuration;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.xmlschema.shared.XmlSchemaDoc;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// Task 7's acceptance criterion: changing the one setting changes, together, the namespace, the
/// `xsi:schemaLocation` and the version in every prompt, and the wrapper the target checks put a
/// proposed event in. The conformance check is held to the same setting by
/// `TestConformanceAgainstTheTargetSchema`.
class TestTheTargetSchemaDrivesTheWords {

    private static final Sample SAMPLE = new Sample("a,b,c\n", Map.of("Feed", "DOOR-ACCESS"));

    /// The transformation rules as one passage, as they were written before the structural paragraph was
    /// split out of them. Kept here, not read from the templates, so that the two halves are held to a
    /// text this test owns rather than to themselves.
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
            line, a column banner, a heartbeat, a comment - write <Skipped xmlns="shapeshifter:skips" \
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
        // Task 7 splits the structural paragraph out of the transformation rules so that another schema
        // can have its own (decision D2 option G). The acceptance criterion is that a document targeting
        // event-logging, or targeting nothing, is asked exactly what it was asked before — so this holds
        // the two halves to reassembling into the one text, character for character.
        final String rules = BEFORE_THE_SPLIT
                .replace("${targetNamespace}", TargetSchema.EVENT_LOGGING)
                .replace("${targetRoot}", "Events")
                .replace("${targetSchemaLocation}", TargetSchema.EVENT_LOGGING + " file://event-logging-v3.0.0.xsd")
                .replace("${targetVersion}", "3.0.0");

        assertThat(words(null).render(transform())).contains(rules);
    }

    @Test
    void aDocumentThatNamesNoTargetIsAskedExactlyAsItWasBefore() {
        // The acceptance criterion that matters most: a document written before the setting existed must
        // get the same prompt it got before, word for word.
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
        // transform's output. The wrapper has to be the schema the document actually writes, or the
        // conformance check refuses every target a non-event-logging document proposes.
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
        // A hand-written XSD need not declare a version, and need not declare a global element. Neither
        // may leave a hole in the prompt where the model is told what to write.
        final TargetSchema bare = new TargetSchema(
                new DocRef(XmlSchemaDoc.TYPE, "schema-2", "bare"), "bare", "urn:bare:1",
                "file://bare.xsd", null, null, "<xs:schema/>");

        assertThat(words(bare).render(transform()))
                .contains("<Events xmlns=\"urn:bare:1\"")
                .contains("xsi:schemaLocation=\"urn:bare:1 file://bare.xsd\"")
                .contains("Version=\"3.0.0\"");
        assertThat(TargetChecks.asDocument("<Event/>", bare)).startsWith("<Events xmlns=\"urn:bare:1\"");
    }

    private static QuestionText words(final TargetSchema target) {
        return QuestionText.of(ShapeshifterAiDoc.builder().uuid("d").name("door").build(), target);
    }

    private static Configuration transform() {
        return new Configuration("XSLTFilter", "XSLT", SAMPLE,
                "<records xmlns=\"records:2\"><record/></records>", null, null, List.of(), false,
                Question.Records.UNKNOWN, List.of(), false, List.of());
    }
}
