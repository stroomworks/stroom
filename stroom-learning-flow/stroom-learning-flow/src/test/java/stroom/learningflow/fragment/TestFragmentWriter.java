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

package stroom.learningflow.fragment;

import stroom.docref.DocRef;
import stroom.learningflow.extraction.DataSplitterStep;
import stroom.learningflow.extraction.JsonStep;
import stroom.learningflow.extraction.XmlFragmentStep;
import stroom.learningflow.learning.LearnedStep;
import stroom.learningflow.learning.StepResult;
import stroom.learningflow.scoring.Verdict;
import stroom.learningflow.shared.RecordBoundary;
import stroom.learningflow.transformation.StandInXsltStep;
import stroom.pipeline.shared.PipelineDoc;
import stroom.pipeline.shared.TextConverterDoc;
import stroom.pipeline.shared.TextConverterDoc.TextConverterType;
import stroom.pipeline.shared.XsltDoc;
import stroom.pipeline.shared.data.PipelineData;
import stroom.pipeline.shared.data.PipelineElement;
import stroom.pipeline.shared.data.PipelineLink;
import stroom.pipeline.shared.data.PipelineProperty;
import stroom.util.shared.DocPath;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/// Tests how `FragmentWriter` turns a learned chain into real Stroom content: a configuration document for each
/// step, and a pipeline fragment that links `Source` through the chain's elements and references those
/// documents from the elements' properties.
///
/// The writer only needs each step's element and configuration text, so the steps here carry the text without
/// having been run.
class TestFragmentWriter {

    private static final DocPath FOLDER = DocPath.fromParts("Learning Flow", "SYSLOG");
    private static final Verdict UNSCORED = new Verdict(List.of(), 1.0);
    private static final String DS3 = "<dataSplitter xmlns=\"data-splitter:3\" version=\"3.0\"/>";
    private static final String XSLT =
            "<xsl:stylesheet xmlns:xsl=\"http://www.w3.org/1999/XSL/Transform\" version=\"2.0\"/>";

    @Test
    void aBoundaryPutsASplitFilterInFrontOfTheTransform() {
        // Stroom's usual pipeline for markup is parser, then SplitFilter, then transform. The filter cuts the
        // parsed stream into one document per record, so the stylesheet sees one record at a time, as a person
        // writing one would expect, memory use is bounded by the record rather than the stream, and an error is
        // isolated to the record that raised it.
        final ContentStores stores = new ContentStores();
        final FragmentWriter writer = stores.writer();
        final List<LearnedStep> chain = List.of(
                new LearnedStep(new JsonStep(), null, new StepResult("<map/>", List.of()), UNSCORED),
                new LearnedStep(new StandInXsltStep(), XSLT, new StepResult("<Events/>", List.of()), UNSCORED));

        final DocRef fragment = writer.write(FOLDER, "json-v1", chain,
                RecordBoundary.ofArray("events").atDepth(2));

        final PipelineData data = stores.pipelines.readDocument(fragment).getPipelineData();
        assertThat(data.getElements().getAdd())
                .extracting(PipelineElement::getId, PipelineElement::getType)
                .containsExactly(
                        tuple("Source", "Source"),
                        tuple("jsonParser", "JSONParser"),
                        tuple("splitFilter", "SplitFilter"),
                        tuple("xsltFilter", "XSLTFilter"));
        assertThat(data.getLinks().getAdd())
                .extracting(PipelineLink::getFrom, PipelineLink::getTo)
                .describedAs("after the parser and in front of the transform")
                .containsExactly(
                        tuple("Source", "jsonParser"),
                        tuple("jsonParser", "splitFilter"),
                        tuple("splitFilter", "xsltFilter"));
        assertThat(data.getProperties().getAdd())
                .filteredOn(property -> "splitFilter".equals(property.getElement()))
                .extracting(property -> property.getName(), property -> property.getValue().getInteger())
                .describedAs("split where the records are, one at a time")
                .containsExactly(tuple("splitDepth", 2), tuple("splitCount", 1));
    }

    @Test
    void markupWithNoParserIsSplitStraightAfterTheSource() {
        // Input that is already XML has no parser to put the filter after, so the filter splits the source's
        // own records, and the transform still sees them one at a time.
        final ContentStores stores = new ContentStores();
        final FragmentWriter writer = stores.writer();
        final List<LearnedStep> chain = List.of(
                new LearnedStep(new StandInXsltStep(), XSLT, new StepResult("<Events/>", List.of()), UNSCORED));

        final DocRef fragment = writer.write(FOLDER, "xml-v1", chain,
                RecordBoundary.ofElement("Event").atDepth(1));

        final PipelineData data = stores.pipelines.readDocument(fragment).getPipelineData();
        assertThat(data.getLinks().getAdd())
                .extracting(PipelineLink::getFrom, PipelineLink::getTo)
                .containsExactly(
                        tuple("Source", "splitFilter"),
                        tuple("splitFilter", "xsltFilter"));
        assertThat(data.getProperties().getAdd())
                .filteredOn(property -> "splitFilter".equals(property.getElement()))
                .extracting(property -> property.getValue().getInteger())
                .containsExactly(1, 1);
    }

    @Test
    void aBoundaryThatDoesNotKnowWhereItSitsIsWrittenWithoutAFilter() {
        // A binding learned by an earlier version has a record boundary with no depth, so cannot say where to
        // split, and a guess is worse than nothing: the usual depth of one splits a JSON document at its single
        // top-level map, which gives one document for the whole stream. Such a fragment is written without a
        // SplitFilter until it is learned again.
        final ContentStores stores = new ContentStores();
        final FragmentWriter writer = stores.writer();
        final List<LearnedStep> chain = List.of(
                new LearnedStep(new JsonStep(), null, new StepResult("<map/>", List.of()), UNSCORED),
                new LearnedStep(new StandInXsltStep(), XSLT, new StepResult("<Events/>", List.of()), UNSCORED));

        final DocRef fragment = writer.write(FOLDER, "json-v0", chain, RecordBoundary.ofArray("events"));

        assertThat(stores.pipelines.readDocument(fragment).getPipelineData().getElements().getAdd())
                .extracting(PipelineElement::getType)
                .containsExactly("Source", "JSONParser", "XSLTFilter");
    }

    @Test
    void rawTextHasNoSplitFilterBecauseTheDataSplitterIsOne() {
        // For raw text the Data Splitter already splits the input into records, so a chain that splits with
        // a configuration has no record boundary and needs no SplitFilter.
        final ContentStores stores = new ContentStores();
        final FragmentWriter writer = stores.writer();
        final List<LearnedStep> chain = List.of(
                new LearnedStep(new DataSplitterStep(null), DS3, new StepResult("<records/>", List.of()),
                        UNSCORED),
                new LearnedStep(new StandInXsltStep(), XSLT, new StepResult("<Events/>", List.of()), UNSCORED));

        final DocRef fragment = writer.write(FOLDER, "csv-v1", chain, null);

        assertThat(stores.pipelines.readDocument(fragment).getPipelineData().getElements().getAdd())
                .extracting(PipelineElement::getType)
                .doesNotContain("SplitFilter");
    }

    @Test
    void writesTheDocumentsAndAFragmentThatReferencesThem() {
        final ContentStores stores = new ContentStores();
        final FragmentWriter writer = stores.writer();
        final List<LearnedStep> chain = List.of(
                new LearnedStep(new DataSplitterStep(null), DS3, new StepResult("<records/>", List.of()), UNSCORED),
                new LearnedStep(new StandInXsltStep(), XSLT, new StepResult("<Events/>", List.of()), UNSCORED));

        final DocRef fragment = writer.write(FOLDER, "csv-logon-v1", chain, null);

        assertThat(fragment.getType()).isEqualTo(PipelineDoc.TYPE);
        assertThat(fragment.getName()).isEqualTo("csv-logon-v1");
        final PipelineData data = stores.pipelines.readDocument(fragment).getPipelineData();
        assertThat(data.getElements().getAdd())
                .extracting(PipelineElement::getId, PipelineElement::getType)
                .containsExactly(
                        tuple("Source", "Source"),
                        tuple("dsParser", "DSParser"),
                        tuple("xsltFilter", "XSLTFilter"));
        assertThat(data.getLinks().getAdd())
                .extracting(PipelineLink::getFrom, PipelineLink::getTo)
                .describedAs("a chain from Source with no destination: the fragment of A20")
                .containsExactly(
                        tuple("Source", "dsParser"),
                        tuple("dsParser", "xsltFilter"));

        final List<PipelineProperty> properties = data.getProperties().getAdd();
        assertThat(properties).extracting(PipelineProperty::getElement, PipelineProperty::getName)
                .containsExactly(
                        tuple("dsParser", "textConverter"),
                        tuple("xsltFilter", "xslt"));

        final DocRef textConverterRef = properties.get(0).getValue().getEntity();
        assertThat(textConverterRef.getType()).isEqualTo(TextConverterDoc.TYPE);
        final TextConverterDoc textConverter = stores.textConverters.readDocument(textConverterRef);
        assertThat(textConverter.getName()).isEqualTo("csv-logon-v1-dsParser");
        assertThat(textConverter.getConverterType()).isEqualTo(TextConverterType.DATA_SPLITTER);
        assertThat(textConverter.getData()).isEqualTo(DS3);

        final DocRef xsltRef = properties.get(1).getValue().getEntity();
        assertThat(xsltRef.getType()).isEqualTo(XsltDoc.TYPE);
        assertThat(stores.xslts.readDocument(xsltRef).getData()).isEqualTo(XSLT);
    }

    /// Checks that a text converter is written with the converter type its step requires, not the type most
    /// steps use.
    ///
    /// `TextConverterDoc` records its converter type, and the element that reads it refuses one of the wrong
    /// type: `XMLFragmentParser` throws "The assigned text converter is not an XML fragment". If every text
    /// converter were written as a Data Splitter, a learned `XMLFragmentParser` chain would pass every scorer,
    /// because the stand-in runner reads only the text, and then fail on every real stream. Tests that use
    /// the stand-in runners cannot see this, so it is checked here.
    @Test
    void aTextConverterIsWrittenAsTheKindItsStepSaysItIs() {
        final ContentStores stores = new ContentStores();
        final FragmentWriter writer = stores.writer();
        final List<LearnedStep> chain = List.of(
                new LearnedStep(new XmlFragmentStep(), "<!-- fragment -->",
                        new StepResult("<records/>", List.of()), UNSCORED),
                new LearnedStep(new StandInXsltStep(), XSLT, new StepResult("<Events/>", List.of()), UNSCORED));

        final DocRef fragment = writer.write(FOLDER, "xml-fragments-v1", chain, null);

        final PipelineData data = stores.pipelines.readDocument(fragment).getPipelineData();
        final DocRef textConverterRef = data.getProperties().getAdd().get(0).getValue().getEntity();
        assertThat(stores.textConverters.readDocument(textConverterRef).getConverterType())
                .describedAs("the kind XMLFragmentParser will accept, and it accepts only one")
                .isEqualTo(TextConverterType.XML_FRAGMENT);
    }

    @Test
    void aRepeatedElementTypeGetsDistinctIds() {
        final ContentStores stores = new ContentStores();
        final FragmentWriter writer = stores.writer();
        final List<LearnedStep> chain = List.of(
                new LearnedStep(new StandInXsltStep(), XSLT, new StepResult("<a/>", List.of()), UNSCORED),
                new LearnedStep(new StandInXsltStep(), XSLT, new StepResult("<b/>", List.of()), UNSCORED));

        final DocRef fragment = writer.write(FOLDER, "two-pass", chain, null);
        final PipelineData data = stores.pipelines.readDocument(fragment).getPipelineData();

        assertThat(data.getElements().getAdd()).extracting(PipelineElement::getId)
                .containsExactly("Source", "xsltFilter", "xsltFilter2");
        assertThat(data.getLinks().getAdd()).extracting(PipelineLink::getFrom, PipelineLink::getTo)
                .containsExactly(tuple("Source", "xsltFilter"), tuple("xsltFilter", "xsltFilter2"));
        assertThat(data.getProperties().getAdd()).extracting(PipelineProperty::getElement)
                .containsExactly("xsltFilter", "xsltFilter2");
        assertThat(stores.xslts.list()).hasSize(2);
    }

    @Test
    void everyWriteCreatesNewDocuments() {
        // Writing a fragment always creates new documents; it never changes documents that may already have run.
        final ContentStores stores = new ContentStores();
        final FragmentWriter writer = stores.writer();
        final List<LearnedStep> chain = List.of(
                new LearnedStep(new StandInXsltStep(), XSLT, new StepResult("<Events/>", List.of()), UNSCORED));

        final DocRef first = writer.write(FOLDER, "v1", chain, null);
        final DocRef second = writer.write(FOLDER, "v2", chain, null);

        assertThat(second.getUuid()).isNotEqualTo(first.getUuid());
        assertThat(stores.pipelines.list()).hasSize(2);
        assertThat(stores.xslts.list()).hasSize(2);
    }

    /// A fragment whose name is already taken in the folder gets the first free number appended. The documents
    /// it references are named after the numbered name, so a fragment and its documents still sort together and
    /// can be told apart from earlier ones.
    @Test
    void aSecondFragmentOfTheSameNameTakesTheFirstFreeNumber() {
        final ContentStores stores = new ContentStores();
        final FragmentWriter writer = stores.writer();
        final List<LearnedStep> chain = List.of(
                new LearnedStep(new StandInXsltStep(), XSLT, new StepResult("<Events/>", List.of()), UNSCORED));

        final DocRef first = writer.write(FOLDER, "DOOR-ACCESS Raw Events", chain, null);
        final DocRef second = writer.write(FOLDER, "DOOR-ACCESS Raw Events", chain, null);
        final DocRef third = writer.write(FOLDER, "DOOR-ACCESS Raw Events", chain, null);

        assertThat(List.of(first, second, third)).extracting(DocRef::getName)
                .containsExactly("DOOR-ACCESS Raw Events", "DOOR-ACCESS Raw Events 2", "DOOR-ACCESS Raw Events 3");
        assertThat(stores.xslts.list()).extracting(DocRef::getName)
                .describedAs("each fragment's stylesheet named for its own fragment")
                .containsExactlyInAnyOrder("DOOR-ACCESS Raw Events-xsltFilter",
                        "DOOR-ACCESS Raw Events 2-xsltFilter",
                        "DOOR-ACCESS Raw Events 3-xsltFilter");
    }

    /// The number used is the first free one, not the one after the highest, so a gap left when a person
    /// deleted a fragment is filled.
    @Test
    void theFirstFreeNumberFillsAGap() {
        final ContentCreator creator = new ContentCreator() {
            @Override
            public DocRef create(final DocPath folder, final String type, final String name) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void delete(final DocRef docRef) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void ensure(final DocPath folder) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Set<String> names(final DocPath folder) {
                return Set.of("Door", "Door 3", "Door-xsltFilter");
            }
        };

        assertThat(creator.unused(FOLDER, "Door")).isEqualTo("Door 2");
        assertThat(creator.unused(FOLDER, "Turnstile")).describedAs("a free name is itself").isEqualTo("Turnstile");
    }
}
