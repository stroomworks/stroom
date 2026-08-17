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
import stroom.docstore.api.DocumentResourceHelper;
import stroom.pipeline.shared.CheckXsltReferencesRequest;
import stroom.pipeline.shared.XsltDoc;
import stroom.pipeline.shared.XsltReferenceCheckResult;
import stroom.pipeline.shared.XsltReferenceInfo;
import stroom.pipeline.shared.XsltReferenceKind;
import stroom.pipeline.shared.XsltReferenceReason;
import stroom.util.shared.EntityServiceException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Tests the <i>check references</i> action.
 * <p>
 * This is the only route by which an unresolved reference reaches a user before the derived table exists, so
 * what matters here is that it reports the right things about the right stylesheet: the caller's unsaved
 * copy where one is supplied, and never without a read permission check.
 */
class TestXsltResourceImplCheckReferences {

    private static final DocRef XSLT_DOC_REF = new DocRef(XsltDoc.TYPE, "xslt-uuid", "My XSLT");

    private final FakeXsltReferenceLookup lookup = new FakeXsltReferenceLookup();

    @Test
    @DisplayName("reports resolved and unresolved references from the supplied body")
    void reportsReferences() {
        lookup.with(DictionaryDoc.TYPE, "GeoIP");

        final XsltReferenceCheckResult result = checkReferences(stored("<xsl:stylesheet/>"), """
                <xsl:stylesheet version="2.0"
                                xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                                xmlns:stroom="stroom">
                  <xsl:import href="Missing"/>
                  <xsl:template match="/">
                    <xsl:value-of select="stroom:dictionary('GeoIP')"/>
                    <xsl:value-of select="stroom:lookup('geo_ip', @ip)"/>
                  </xsl:template>
                </xsl:stylesheet>""");

        assertThat(result.getReferences())
                .extracting(XsltReferenceInfo::getKind,
                        XsltReferenceInfo::getRawValue,
                        XsltReferenceInfo::getReason)
                .containsExactly(
                        tuple(XsltReferenceKind.IMPORT, "Missing", XsltReferenceReason.NOT_FOUND),
                        tuple(XsltReferenceKind.DICTIONARY, "GeoIP", null),
                        tuple(XsltReferenceKind.REF_MAP_READ, "geo_ip", null));
        assertThat(result.hasParseFailure()).isFalse();
    }

    @Test
    @DisplayName("only NOT_FOUND and AMBIGUOUS count as problems")
    void problemsAreTheActionableOnes() {
        final XsltReferenceCheckResult result = checkReferences(stored(null), """
                <xsl:stylesheet version="2.0"
                                xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                                xmlns:stroom="stroom">
                  <xsl:import href="Missing"/>
                  <xsl:template match="/">
                    <xsl:value-of select="stroom:lookup(@mapName, @ip)"/>
                  </xsl:template>
                </xsl:stylesheet>""");

        // A data-driven map name is normal and not a fault; a missing import is the thing worth acting on.
        // Reporting both as problems would train users to ignore the panel.
        assertThat(result.getReferences()).hasSize(2);
        assertThat(result.getProblems())
                .extracting(XsltReferenceInfo::getRawValue)
                .containsExactly("Missing");
    }

    @Test
    @DisplayName("an ambiguous name reports its candidates so the collision can be named")
    void ambiguousReportsCandidates() {
        lookup.with(DictionaryDoc.TYPE, "GeoIP", "uuid-1").with(DictionaryDoc.TYPE, "GeoIP", "uuid-2");

        final XsltReferenceCheckResult result = checkReferences(stored(null), """
                <xsl:stylesheet version="2.0"
                                xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                                xmlns:stroom="stroom">
                  <xsl:template match="/">
                    <xsl:value-of select="stroom:dictionary('GeoIP')"/>
                  </xsl:template>
                </xsl:stylesheet>""");

        assertThat(result.getProblems()).singleElement().satisfies(problem -> {
            assertThat(problem.getReason()).isEqualTo(XsltReferenceReason.AMBIGUOUS);
            assertThat(problem.getTarget()).isNull();
            assertThat(problem.getCandidates()).extracting(DocRef::getUuid)
                    .containsExactly("uuid-1", "uuid-2");
        });
    }

    @Test
    @DisplayName("checks the supplied body rather than the stored one")
    void prefersTheSuppliedBody() {
        lookup.with(DictionaryDoc.TYPE, "InStore").with(DictionaryDoc.TYPE, "BeingEdited");

        final String stored = """
                <xsl:stylesheet version="2.0"
                                xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                                xmlns:stroom="stroom">
                  <xsl:template match="/">
                    <xsl:value-of select="stroom:dictionary('InStore')"/>
                  </xsl:template>
                </xsl:stylesheet>""";
        final String editing = stored.replace("InStore", "BeingEdited");

        // The question "does this reference exist" is asked while editing, so checking the last saved copy
        // would answer about the wrong stylesheet.
        assertThat(checkReferences(stored(stored), editing).getReferences())
                .extracting(XsltReferenceInfo::getRawValue)
                .containsExactly("BeingEdited");
    }

    @Test
    @DisplayName("falls back to the stored body when none is supplied")
    void fallsBackToTheStoredBody() {
        lookup.with(DictionaryDoc.TYPE, "InStore");

        final String stored = """
                <xsl:stylesheet version="2.0"
                                xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                                xmlns:stroom="stroom">
                  <xsl:template match="/">
                    <xsl:value-of select="stroom:dictionary('InStore')"/>
                  </xsl:template>
                </xsl:stylesheet>""";

        assertThat(checkReferences(stored(stored), null).getReferences())
                .extracting(XsltReferenceInfo::getRawValue)
                .containsExactly("InStore");
    }

    @Test
    @DisplayName("a mid-edit stylesheet reports a parse failure rather than failing the request")
    void midEditReportsAParseFailure() {
        final XsltReferenceCheckResult result =
                checkReferences(stored(null), "<xsl:stylesheet><unclosed>");

        // Reported, but as a parse failure rather than as findings, so a client can decline to make a fuss
        // about a stylesheet somebody is halfway through writing.
        assertThat(result.hasParseFailure()).isTrue();
        assertThat(result.getReferences()).isEmpty();
        assertThat(result.getProblems()).isEmpty();
    }

    @Test
    @DisplayName("a document that cannot be read is rejected rather than checked")
    void unreadableDocumentIsRejected() {
        // read() returning null stands in for a document that is missing, or that this user may not see.
        // Checking the body regardless would leak what a stylesheet refers to.
        assertThatThrownBy(() -> checkReferences(null, "<xsl:stylesheet/>"))
                .isInstanceOf(EntityServiceException.class)
                .hasMessageContaining("not found");
    }

    @Test
    @DisplayName("a request with no document is rejected")
    void requestWithoutADocumentIsRejected() {
        assertThatThrownBy(() -> newResource(stored(null))
                .checkReferences(new CheckXsltReferencesRequest(null, "<xsl:stylesheet/>")))
                .isInstanceOf(EntityServiceException.class)
                .hasMessageContaining("document must be supplied");
    }

    private XsltReferenceCheckResult checkReferences(final XsltDoc storedDoc, final String data) {
        return newResource(storedDoc)
                .checkReferences(new CheckXsltReferencesRequest(XSLT_DOC_REF, data));
    }

    private static XsltDoc stored(final String data) {
        return XsltDoc.builder()
                .uuid(XSLT_DOC_REF.getUuid())
                .name(XSLT_DOC_REF.getName())
                .data(data)
                .build();
    }

    /**
     * @param storedDoc What the store will return for the requested document, or null to stand in for a
     *                  document that cannot be read.
     */
    private XsltResourceImpl newResource(final XsltDoc storedDoc) {
        final DocumentResourceHelper documentResourceHelper = Mockito.mock(DocumentResourceHelper.class);
        Mockito.when(documentResourceHelper.read(Mockito.any(), Mockito.eq(XSLT_DOC_REF)))
                .thenReturn(storedDoc);

        return new XsltResourceImpl(
                () -> Mockito.mock(XsltStore.class),
                () -> documentResourceHelper,
                () -> new XsltReferenceParserImpl(lookup));
    }
}
