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
import stroom.learningflow.extraction.NodeFixture;
import stroom.learningflow.shared.LearningFlow;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.shared.Template;
import stroom.xmlschema.shared.XmlSchemaDoc;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// Tests `TargetStructure`, which describes a target schema that is not event-logging to the model from its
/// own XSD.
///
/// The hand-written guidance only covers event-logging, so generating a summary from the XSD is what makes any
/// other target schema usable.
class TestTargetStructure {

    private static final TargetSchema SIGHTINGS = schema("urn:sightings:2", "file://sightings-v2.4.xsd",
            "2.4", "Sightings", read("sightings.xsd"));

    @Test
    void aSchemaDescribesItselfFromItsRootOutwards() {
        final String passage = TargetStructure.of(SIGHTINGS);

        assertThat(passage)
                .describedAs("where a record goes, so the first candidate does not have to guess it")
                .contains("holding one record's worth under <Sightings>");
        assertThat(passage)
                .describedAs("the root, then what it holds, then what those hold")
                .contains("Sightings contains, in order: Sighting+")
                .contains("When")
                .contains("Observer?")
                .contains("Grid");
        assertThat(passage)
                .describedAs("a choice is offered as one, not as two required children")
                .contains("(Bird")
                .contains("Mammal");
        assertThat(passage)
                .describedAs("the values a restricted type allows, so a candidate does not invent one")
                .contains("woodland");
        assertThat(passage)
                .describedAs("the notation is explained once, as it is in the conformance feedback")
                .contains("? optional")
                .contains("Where the schema allows an element this does not describe");
    }

    @Test
    void aRealSchemaIsDescribedWithinItsBudget() {
        // Stroom's own Data Splitter schema is a real schema, bigger than the fixture, and not event-logging.
        // A large schema described in full would crowd out the sample and the feedback, which matter more to
        // the answer.
        //
        // One fixture is used throughout, because each has its own in-memory store, so a reference taken from
        // one does not resolve in another.
        final NodeFixture node = new NodeFixture();
        final TargetSchema dataSplitter = node.targetSchemas()
                .resolve(node.schemaRef("data-splitter:3"))
                .orElseThrow();

        final String passage = TargetStructure.of(dataSplitter);

        assertThat(passage).contains("holding one record's worth under <dataSplitter>");
        assertThat(passage.lines().filter(line -> line.startsWith("- ")).count())
                .isLessThanOrEqualTo(TargetStructure.LINES);
        assertThat(passage.length())
                .describedAs("capped, so that the schema cannot crowd out the data")
                .isLessThan(TargetStructure.BUDGET * 2);
    }

    @Test
    void aSchemaThatSaysNothingUsefulSaysSoRatherThanNothing() {
        // A hand-written XSD with no root element, and one whose root holds no elements. Neither may leave
        // the model with an unfinished sentence where its instructions should be.
        assertThat(TargetStructure.of(schema("urn:bare:1", "file://bare.xsd", "1.0", null, "<xs:schema/>")))
                .contains("declares no root element")
                .contains("file://bare.xsd");
        assertThat(TargetStructure.of(schema("urn:bare:1", "file://bare.xsd", "1.0", "Thing", "<xs:schema/>")))
                .isEqualTo("holding one record's worth of urn:bare:1 under <Thing>.");
    }

    @Test
    void theGeneratedSummaryIsWhatTheQuestionCarries() {
        // This is the purpose of the class: a document targeting another schema is told that schema's
        // structure, just as a document targeting event-logging is told event-logging's.
        final LearningFlowDoc doc = LearningFlowDoc.builder().uuid("d").name("sightings").build();

        final String text = QuestionText.of(doc, SIGHTINGS).render(new Question.Configuration(
                "XSLTFilter", "XSLT", new Sample("a,b,c\n", Map.of()),
                "<records xmlns=\"records:2\"><record/></records>", null, null, List.of(), false,
                Question.Records.UNKNOWN, List.of(), false, List.of()));

        assertThat(text)
                .contains("Sightings contains, in order: Sighting+")
                .describedAs("and none of event-logging's own guidance, which is about event-logging")
                .doesNotContain("EventDetail")
                .doesNotContain("Authenticate");
    }

    @Test
    void aFlowThatSaysWhatItsSchemaHoldsIsBelievedOverBoth() {
        // A template override is how a Learning Flow says what the built-in text cannot, so it replaces a
        // generated summary just as it replaces event-logging's hand-written one.
        final LearningFlowDoc doc = LearningFlowDoc.builder().uuid("d").name("sightings")
                .flow(new LearningFlow(List.of(), Map.of(Template.TARGET_STRUCTURE,
                        "holding one <Sighting> per record, and read the ringing scheme's note first."), null))
                .build();

        final String text = QuestionText.of(doc, SIGHTINGS).render(new Question.Configuration(
                "XSLTFilter", "XSLT", new Sample("a,b,c\n", Map.of()),
                "<records xmlns=\"records:2\"><record/></records>", null, null, List.of(), false,
                Question.Records.UNKNOWN, List.of(), false, List.of()));

        assertThat(text)
                .contains("read the ringing scheme's note first")
                .doesNotContain("Sightings contains, in order");
    }

    private static TargetSchema schema(final String namespace,
                                       final String systemId,
                                       final String version,
                                       final String root,
                                       final String text) {
        return new TargetSchema(new DocRef(XmlSchemaDoc.TYPE, "schema-" + namespace, namespace),
                namespace, namespace, systemId, version, root, text);
    }

    private static String read(final String file) {
        try (InputStream in = TestTargetStructure.class.getResourceAsStream(file)) {
            if (in == null) {
                throw new IllegalStateException("The fixture " + file + " is not on the classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
