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

package stroom.pipeline.xslt;

import stroom.dictionary.shared.DictionaryDoc;
import stroom.docref.DocRef;
import stroom.docstore.api.DependencyRemapper;
import stroom.docstore.api.Store;
import stroom.docstore.api.StoreFactory;
import stroom.pipeline.shared.XsltDoc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Tests the dependency remap function {@link XsltStoreImpl} contributes.
 * <p>
 * The store invokes that function from two places which use it quite differently: on save it wants the
 * dependencies and discards the returned document, while on copy it wants the returned document and writes
 * it back if the remapper reports a change. An XSLT can satisfy the first but not the second - its
 * references are names inside its body, not fields - so the interesting assertion here is the negative
 * one: the copy path must come away believing nothing changed.
 */
class TestXsltStoreImplDependencies {

    private final FakeXsltReferenceLookup lookup = new FakeXsltReferenceLookup();

    @Test
    @DisplayName("records the documents an XSLT imports and reads")
    void recordsDependencies() {
        lookup.with(XsltDoc.TYPE, "Common").with(DictionaryDoc.TYPE, "GeoIP");
        final DependencyRemapper remapper = new DependencyRemapper();

        remapOnRecordingPath(remapper, """
                <xsl:stylesheet version="2.0"
                                xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                                xmlns:stroom="stroom">
                  <xsl:import href="Common"/>
                  <xsl:template match="/">
                    <xsl:value-of select="stroom:dictionary('GeoIP')"/>
                  </xsl:template>
                </xsl:stylesheet>""");

        assertThat(remapper.getDependencies()).containsExactlyInAnyOrder(
                new DocRef(XsltDoc.TYPE, "uuid-Common", "Common"),
                new DocRef(DictionaryDoc.TYPE, "uuid-GeoIP", "GeoIP"));
    }

    @Test
    @DisplayName("recording a dependency does not report the document as changed")
    void recordingDoesNotReportAChange() {
        lookup.with(DictionaryDoc.TYPE, "GeoIP");
        final DependencyRemapper remapper = new DependencyRemapper();

        remapOnRecordingPath(remapper, dictionaryStylesheet());

        // On the save path an empty remapping map makes this true of remap() as well, so this alone does
        // not prove much - the next test is the one that matters.
        assertThat(remapper.isChanged()).isFalse();
    }

    @Test
    @DisplayName("on the copy path, a substitutable reference still reports no change")
    void copyPathNeverReportsAMisleadingChange() {
        final DocRef original = new DocRef(DictionaryDoc.TYPE, "uuid-GeoIP", "GeoIP");
        final DocRef copy = new DocRef(DictionaryDoc.TYPE, "uuid-GeoIP-copy", "GeoIP (copy)");
        lookup.with(DictionaryDoc.TYPE, "GeoIP");

        // A real old-to-new map, exactly as folder copy builds it, containing the very document this
        // stylesheet depends on. Had the function called remap() the flag below would be true.
        final DependencyRemapper remapper = new DependencyRemapper(Map.of(original, copy));
        final XsltDoc doc = xsltDoc(dictionaryStylesheet());

        final XsltDoc returned = newStore().getDependencyRemapFunction().remap(doc, remapper);

        // The point of DependencyRemapper.record(): StoreImpl writes the returned document only when
        // isChanged() is true, and writing it here would produce a new version whose body still names
        // 'GeoIP' - a silent non-remap dressed up as a successful one.
        assertThat(remapper.isChanged()).isFalse();
        assertThat(returned).isSameAs(doc);
        assertThat(returned.getData()).isEqualTo(doc.getData());
    }

    @Test
    @DisplayName("on the copy path, a reference left pointing at the original is reported")
    void copyPathWarnsAboutAReferenceItCouldNotRepoint() {
        final DocRef original = new DocRef(DictionaryDoc.TYPE, "uuid-GeoIP", "GeoIP");
        final DocRef copy = new DocRef(DictionaryDoc.TYPE, "uuid-GeoIP-copy", "GeoIP (copy)");
        lookup.with(DictionaryDoc.TYPE, "GeoIP");

        final DependencyRemapper remapper = new DependencyRemapper(Map.of(original, copy));
        newStore().getDependencyRemapFunction().remap(xsltDoc(dictionaryStylesheet()), remapper);

        // The whole point: silence here would leave the user believing they had two independent sets of
        // documents when editing the original dictionary still changes what the copied XSLT reads.
        assertThat(remapper.getWarnings()).singleElement().asString()
                .contains("XSLT 'Test XSLT'")
                .contains("line 5")
                .contains("still refers to 'GeoIP' in its original location");
    }

    @Test
    @DisplayName("on the copy path, a name the copy made ambiguous is reported as such")
    void copyPathWarnsAboutANameTheCopyMadeAmbiguous() {
        // Copying to a different folder keeps the name, so afterwards two documents answer to 'GeoIP'.
        final DocRef original = new DocRef(DictionaryDoc.TYPE, "uuid-1", "GeoIP");
        final DocRef copy = new DocRef(DictionaryDoc.TYPE, "uuid-2", "GeoIP");
        lookup.with(DictionaryDoc.TYPE, "GeoIP", "uuid-1").with(DictionaryDoc.TYPE, "GeoIP", "uuid-2");

        final DependencyRemapper remapper = new DependencyRemapper(Map.of(original, copy));
        newStore().getDependencyRemapFunction().remap(xsltDoc(dictionaryStylesheet()), remapper);

        // Reported differently from the case above because the consequence is different: an ambiguous name
        // cannot be resolved reliably at all, and it breaks the original stylesheet too.
        assertThat(remapper.getWarnings()).singleElement().asString()
                .contains("now matches 2 documents named 'GeoIP'")
                .contains("The original stylesheet is affected in the same way");

        // Still no change claimed - the warning is the whole of the response.
        assertThat(remapper.isChanged()).isFalse();
    }

    @Test
    @DisplayName("a reference to something outside the copy is not warned about")
    void referencesOutsideTheCopyAreNotWarnedAbout() {
        lookup.with(DictionaryDoc.TYPE, "GeoIP");
        final DocRef unrelated = new DocRef(DictionaryDoc.TYPE, "uuid-Other", "Other");

        final DependencyRemapper remapper = new DependencyRemapper(
                Map.of(unrelated, new DocRef(DictionaryDoc.TYPE, "uuid-Other-copy", "Other (copy)")));
        newStore().getDependencyRemapFunction().remap(xsltDoc(dictionaryStylesheet()), remapper);

        // An XSLT naming a document that was not part of the copy is behaving correctly. Warning here would
        // fire on nearly every copy and teach the user to dismiss the dialog unread.
        assertThat(remapper.getWarnings()).isEmpty();
    }

    @Test
    @DisplayName("the recording path never warns")
    void recordingPathNeverWarns() {
        lookup.with(XsltDoc.TYPE, "Common").with(DictionaryDoc.TYPE, "GeoIP");
        final DependencyRemapper remapper = new DependencyRemapper();

        remapOnRecordingPath(remapper, """
                <xsl:stylesheet version="2.0"
                                xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                                xmlns:stroom="stroom">
                  <xsl:import href="Common"/>
                  <xsl:template match="/">
                    <xsl:value-of select="stroom:dictionary('GeoIP')"/>
                  </xsl:template>
                </xsl:stylesheet>""");

        // Saving is not copying. With no remappings in force nothing was expected to be repointed, so
        // there is nothing to report, and a warning shown on every save would be noise.
        assertThat(remapper.getWarnings()).isEmpty();
    }

    @Test
    @DisplayName("a remapping of a document to itself is not a change to report")
    void selfRemappingIsNotReported() {
        final DocRef original = new DocRef(DictionaryDoc.TYPE, "uuid-GeoIP", "GeoIP");
        lookup.with(DictionaryDoc.TYPE, "GeoIP");

        final DependencyRemapper remapper = new DependencyRemapper(Map.of(original, original));
        newStore().getDependencyRemapFunction().remap(xsltDoc(dictionaryStylesheet()), remapper);

        // wouldRemap() asks whether a substitution would change anything, not merely whether the map
        // mentions the document, so that this degenerate entry cannot produce a warning about nothing.
        assertThat(remapper.getWarnings()).isEmpty();
    }

    @Test
    @DisplayName("a malformed stylesheet neither throws nor blocks the save")
    void malformedStylesheetIsHarmless() {
        final DependencyRemapper remapper = new DependencyRemapper();

        assertThatCode(() -> remapOnRecordingPath(remapper, "<xsl:stylesheet><unclosed>"))
                .doesNotThrowAnyException();
        assertThat(remapper.getDependencies()).isEmpty();
    }

    @Test
    @DisplayName("a document with no body yet records nothing")
    void nullBodyRecordsNothing() {
        final DependencyRemapper remapper = new DependencyRemapper();

        assertThatCode(() -> remapOnRecordingPath(remapper, null)).doesNotThrowAnyException();
        assertThat(remapper.getDependencies()).isEmpty();
    }

    @Test
    @DisplayName("an ambiguous name contributes an edge to every candidate")
    void ambiguousNameContributesEveryEdge() {
        lookup.with(DictionaryDoc.TYPE, "GeoIP", "uuid-1").with(DictionaryDoc.TYPE, "GeoIP", "uuid-2");
        final DependencyRemapper remapper = new DependencyRemapper();

        remapOnRecordingPath(remapper, dictionaryStylesheet());

        // doc_dependency means "may use", so every candidate is recorded. Choosing one would invent a
        // fact and recording none would lose an edge the runtime will follow.
        assertThat(remapper.getDependencies()).extracting(DocRef::getUuid)
                .containsExactlyInAnyOrder("uuid-1", "uuid-2");
    }

    private void remapOnRecordingPath(final DependencyRemapper remapper, final String data) {
        newStore().getDependencyRemapFunction().remap(xsltDoc(data), remapper);
    }

    private static String dictionaryStylesheet() {
        return """
                <xsl:stylesheet version="2.0"
                                xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                                xmlns:stroom="stroom">
                  <xsl:template match="/">
                    <xsl:value-of select="stroom:dictionary('GeoIP')"/>
                  </xsl:template>
                </xsl:stylesheet>""";
    }

    private static XsltDoc xsltDoc(final String data) {
        return XsltDoc.builder()
                .uuid("xslt-uuid")
                .name("Test XSLT")
                .data(data)
                .build();
    }

    /**
     * The store only needs to exist far enough to hand over its remap function, so the persistence side is
     * mocked out entirely.
     */
    private XsltStoreImpl newStore() {
        final StoreFactory storeFactory = Mockito.mock(StoreFactory.class);
        Mockito.when(storeFactory.createStore(Mockito.any(), Mockito.any(), Mockito.any(),
                        Mockito.any(), Mockito.any()))
                .thenReturn(Mockito.mock(Store.class));

        return new XsltStoreImpl(
                storeFactory,
                Mockito.mock(XsltSerialiser.class),
                new XsltReferenceParserImpl(lookup));
    }
}
