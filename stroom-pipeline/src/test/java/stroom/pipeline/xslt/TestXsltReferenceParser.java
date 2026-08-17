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
import stroom.pipeline.shared.XsltDoc;
import stroom.pipeline.shared.XsltReferenceCertainty;
import stroom.pipeline.shared.XsltReferenceDirection;
import stroom.pipeline.shared.XsltReferenceKind;
import stroom.pipeline.shared.XsltReferenceReason;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Tests for {@link XsltReferenceParser}.
 * <p>
 * Organised around the two things that can go wrong, which are not equally bad. A <b>false negative</b> -
 * failing to find a reference - costs a missing dependency edge. A <b>false positive</b> - reporting a
 * reference that is not there, or resolving one that cannot be known - puts a lie in the configuration and
 * trains users to distrust the whole feature. So the "finds it" cases are here, and so is a whole nested
 * class of cases asserting the parser stays quiet.
 */
class TestXsltReferenceParser {

    private final FakeXsltReferenceLookup lookup = new FakeXsltReferenceLookup();

    // ------------------------------------------------------------------------
    // Imports - xsl:import and xsl:include
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("xsl:import and xsl:include")
    class Imports {

        @ParameterizedTest
        @ValueSource(strings = {"import", "include"})
        @DisplayName("resolves by name, as CustomURIResolver does")
        void byName(final String element) {
            lookup.with(XsltDoc.TYPE, "Common");

            final XsltReferences result = parse("""
                    <xsl:stylesheet version="2.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
                      <xsl:%s href="Common"/>
                    </xsl:stylesheet>""".formatted(element));

            assertThat(result.references())
                    .extracting(XsltReference::kind, XsltReference::rawValue, XsltReference::target)
                    .containsExactly(tuple(
                            XsltReferenceKind.IMPORT,
                            "Common",
                            new DocRef(XsltDoc.TYPE, "uuid-Common", "Common")));
        }

        @Test
        @DisplayName("resolves a doc-ref string by UUID when no name matches")
        void byDocRefString() {
            lookup.with(XsltDoc.TYPE, "Renamed", "the-uuid");

            final XsltReferences result = parse("""
                    <xsl:stylesheet version="2.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
                      <xsl:import href="uuid='the-uuid', name='Original Name'"/>
                    </xsl:stylesheet>""");

            assertThat(result.references())
                    .extracting(XsltReference::target)
                    .containsExactly(new DocRef(XsltDoc.TYPE, "the-uuid", "Renamed"));
        }

        @Test
        @DisplayName("treats a bare href as a UUID when no name matches, as parseDocRef does")
        void bareUuid() {
            lookup.with(XsltDoc.TYPE, "Some Name", "bare-uuid");

            final XsltReferences result = parse("""
                    <xsl:stylesheet version="2.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
                      <xsl:import href="bare-uuid"/>
                    </xsl:stylesheet>""");

            assertThat(result.references())
                    .extracting(XsltReference::target)
                    .containsExactly(new DocRef(XsltDoc.TYPE, "bare-uuid", "Some Name"));
        }

        @Test
        @DisplayName("NOT_FOUND where the target does not exist - the silent runtime failure made visible")
        void notFound() {
            final XsltReferences result = parse("""
                    <xsl:stylesheet version="2.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
                      <xsl:import href="Missing"/>
                    </xsl:stylesheet>""");

            assertThat(result.references())
                    .extracting(XsltReference::reason, XsltReference::rawValue, XsltReference::target)
                    .containsExactly(tuple(XsltReferenceReason.NOT_FOUND, "Missing", null));
            assertThat(result.withReason(XsltReferenceReason.NOT_FOUND)).hasSize(1);
        }

        @Test
        @DisplayName("AMBIGUOUS carries every candidate, since the runtime throws rather than choosing")
        void ambiguous() {
            lookup.with(XsltDoc.TYPE, "Shared", "uuid-a")
                    .with(XsltDoc.TYPE, "Shared", "uuid-b");

            final XsltReferences result = parse("""
                    <xsl:stylesheet version="2.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
                      <xsl:import href="Shared"/>
                    </xsl:stylesheet>""");

            assertThat(result.references()).singleElement().satisfies(reference -> {
                assertThat(reference.reason()).isEqualTo(XsltReferenceReason.AMBIGUOUS);
                assertThat(reference.target()).isNull();
                assertThat(reference.candidates()).extracting(DocRef::getUuid)
                        .containsExactly("uuid-a", "uuid-b");
            });
        }

        @Test
        @DisplayName("name matching is case sensitive, as the store's collation is")
        void caseSensitive() {
            lookup.with(XsltDoc.TYPE, "Common");

            final XsltReferences result = parse("""
                    <xsl:stylesheet version="2.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
                      <xsl:import href="COMMON"/>
                    </xsl:stylesheet>""");

            assertThat(result.references())
                    .extracting(XsltReference::reason)
                    .containsExactly(XsltReferenceReason.NOT_FOUND);
        }
    }

    // ------------------------------------------------------------------------
    // Dictionaries
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("stroom:dictionary")
    class Dictionaries {

        @Test
        @DisplayName("resolves by name")
        void byName() {
            lookup.with(DictionaryDoc.TYPE, "GeoIP");

            final XsltReferences result = parse(template("""
                    <xsl:value-of select="stroom:dictionary('GeoIP')"/>"""));

            assertThat(result.references())
                    .extracting(XsltReference::kind, XsltReference::target)
                    .containsExactly(tuple(
                            XsltReferenceKind.DICTIONARY,
                            new DocRef(DictionaryDoc.TYPE, "uuid-GeoIP", "GeoIP")));
        }

        @Test
        @DisplayName("resolves by UUID first, as Dictionary.java does")
        void byUuidBeforeName() {
            // A dictionary whose *name* is the UUID of another. The runtime tries findByUuid first, so the
            // UUID match must win.
            lookup.with(DictionaryDoc.TYPE, "decoy", "the-uuid")
                    .with(DictionaryDoc.TYPE, "the-uuid", "other-uuid");

            final XsltReferences result = parse(template("""
                    <xsl:value-of select="stroom:dictionary('the-uuid')"/>"""));

            assertThat(result.references())
                    .extracting(XsltReference::target)
                    .containsExactly(new DocRef(DictionaryDoc.TYPE, "the-uuid", "decoy"));
        }

        @Test
        @DisplayName("AMBIGUOUS names every candidate rather than picking one as the runtime does")
        void ambiguousNamesCandidates() {
            lookup.with(DictionaryDoc.TYPE, "Users", "uuid-1")
                    .with(DictionaryDoc.TYPE, "Users", "uuid-2");

            final XsltReferences result = parse(template("""
                    <xsl:value-of select="stroom:dictionary('Users')"/>"""));

            assertThat(result.references()).singleElement().satisfies(reference -> {
                assertThat(reference.reason()).isEqualTo(XsltReferenceReason.AMBIGUOUS);
                assertThat(reference.candidates()).hasSize(2);
            });
        }

        @Test
        @DisplayName("an ambiguous name still contributes an edge per candidate")
        void ambiguousContributesEveryEdge() {
            lookup.with(DictionaryDoc.TYPE, "Users", "uuid-1")
                    .with(DictionaryDoc.TYPE, "Users", "uuid-2");

            final XsltReferences result = parse(template("""
                    <xsl:value-of select="stroom:dictionary('Users')"/>"""));

            // Choosing one would invent a fact; recording none would lose a live edge. Both, so that
            // "what uses this dictionary" and broken-dependency detection keep working.
            assertThat(result.documentTargets()).extracting(DocRef::getUuid)
                    .containsExactly("uuid-1", "uuid-2");
        }

        @Test
        @DisplayName("identified by namespace URI, so an unconventional prefix works")
        void unconventionalPrefix() {
            lookup.with(DictionaryDoc.TYPE, "GeoIP");

            final XsltReferences result = parse("""
                    <xsl:stylesheet version="2.0"
                                    xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                                    xmlns:s="stroom">
                      <xsl:template match="/">
                        <xsl:value-of select="s:dictionary('GeoIP')"/>
                      </xsl:template>
                    </xsl:stylesheet>""");

            assertThat(result.references())
                    .extracting(XsltReference::kind, XsltReference::rawValue)
                    .containsExactly(tuple(XsltReferenceKind.DICTIONARY, "GeoIP"));
        }

        @Test
        @DisplayName("an empty name is not a reference, matching the runtime's own guard")
        void emptyNameIgnored() {
            final XsltReferences result = parse(template("""
                    <xsl:value-of select="stroom:dictionary('')"/>"""));

            assertThat(result.references()).isEmpty();
        }
    }

    // ------------------------------------------------------------------------
    // Lookups - map names only, never a store
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("stroom:lookup and stroom:bitmap-lookup")
    class Lookups {

        @ParameterizedTest
        @ValueSource(strings = {"lookup", "bitmap-lookup"})
        @DisplayName("records a map name with no document target")
        void mapNameOnly(final String function) {
            final XsltReferences result = parse(template("""
                    <xsl:value-of select="stroom:%s('geo_ip', @ip)"/>""".formatted(function)));

            assertThat(result.references())
                    .extracting(XsltReference::kind, XsltReference::rawValue, XsltReference::target)
                    .containsExactly(tuple(XsltReferenceKind.REF_MAP_READ, "geo_ip", null));
        }

        @Test
        @DisplayName("a lookup naming a map is resolved even though it names no document")
        void mapNameIsResolved() {
            final XsltReferences result = parse(template("""
                    <xsl:value-of select="stroom:lookup('geo_ip', @ip)"/>"""));

            // Resolved means "the value is known", not "a document was found". A lookup cannot name a
            // document: which store it reaches depends on the pipeline's configured references.
            assertThat(result.references()).singleElement().satisfies(reference -> {
                assertThat(reference.isResolved()).isTrue();
                assertThat(reference.target()).isNull();
                assertThat(reference.candidates()).isEmpty();
            });
        }

        @Test
        @DisplayName("a chained lookup contributes each component, not the joined string")
        void chainedMapNames() {
            final XsltReferences result = parse(template("""
                    <xsl:value-of select="stroom:lookup('MAP1/MAP2/MAP3', @id)"/>"""));

            // Only the components are real map names. A store can never be called 'MAP1/MAP2' - Plan B
            // names are constrained to [a-z_0-9] - so recording the joined string would offer a name
            // nothing can ever match.
            //
            // Not a hypothetical case, so please do not simplify it away. Running the parser over a real
            // deployment's content found
            //     stroom:lookup('USER_ID_TO_STAFF_NO_MAP/STAFF_NO_TO_USER_DETAILS_MAP', ...)
            // and the second of those two maps was written by another translation in the same export.
            // Without this split that read matches no writer, and the writer looks like an orphan.
            assertThat(result.resolvedValues(XsltReferenceKind.REF_MAP_READ))
                    .containsExactly("MAP1", "MAP2", "MAP3");
        }

        @Test
        @DisplayName("case is preserved, because normalising is the consumer's business")
        void casePreserved() {
            final XsltReferences result = parse(template("""
                    <xsl:value-of select="stroom:lookup('GeoIP_Map', @ip)"/>"""));

            // Plan B lower-cases map names on both read and write; reference data does not. The parser
            // cannot know which is meant, so it records what was written.
            assertThat(result.resolvedValues(XsltReferenceKind.REF_MAP_READ)).containsExactly("GeoIP_Map");
        }
    }

    // ------------------------------------------------------------------------
    // Map output - the write side, for both schemas
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("<map> output")
    class MapOutput {

        @Test
        @DisplayName("finds a map name in reference-data:2 output")
        void referenceDataSchema() {
            final XsltReferences result = parse(template("""
                    <referenceData xmlns="reference-data:2">
                      <reference>
                        <map>geo_ip</map>
                        <key>1.2.3.4</key>
                        <value>somewhere</value>
                      </reference>
                    </referenceData>"""));

            assertThat(result.resolvedValues(XsltReferenceKind.REF_MAP_WRITE)).containsExactly("geo_ip");
        }

        @Test
        @DisplayName("finds a map name in plan-b output, whose shape is entirely different")
        void planBSchema() {
            // The plan-b schema nests <map> in <state>, <temporal-state>, <session> and friends rather
            // than in <reference>. A parser written only for reference-data:2 would find nothing here -
            // and this is the write side the Plan B migration has to recover.
            //
            // Shaped after a real Plan B translation rather than invented: the default namespace is
            // declared on the stylesheet element, the root carries xsi:schemaLocation and version, and
            // the session has a timeout. Real content uses both plan-b:1 and plan-b:2, which makes no
            // difference here - <map> is matched on local name, in any non-XSLT namespace.
            final XsltReferences result = parse("""
                    <xsl:stylesheet xpath-default-namespace="event-logging:3"
                                    xmlns="plan-b:1"
                                    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                                    xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                                    version="2.0">
                      <xsl:template match="Events">
                        <plan-b xsi:schemaLocation="plan-b:1 file://plan-b-v1.0.xsd" version="1.0">
                          <xsl:apply-templates />
                        </plan-b>
                      </xsl:template>
                      <xsl:template match="Event">
                        <temporal-state>
                          <map>user_state</map>
                          <key>
                            <xsl:value-of select="EventSource/User/Id" />
                          </key>
                          <time>
                            <xsl:value-of select="EventTime/TimeCreated" />
                          </time>
                          <value>active</value>
                        </temporal-state>
                        <session>
                          <map>user_sessions</map>
                          <key>
                            <xsl:value-of select="EventSource/User/Id" />
                          </key>
                          <time>
                            <xsl:value-of select="EventTime/TimeCreated" />
                          </time>
                          <timeout>15m</timeout>
                        </session>
                      </xsl:template>
                    </xsl:stylesheet>""");

            assertThat(result.resolvedValues(XsltReferenceKind.REF_MAP_WRITE))
                    .containsExactly("user_state", "user_sessions");
        }

        @Test
        @DisplayName("matches the element name case-insensitively, as both filters do")
        void elementNameCaseInsensitive() {
            final XsltReferences result = parse(template("""
                    <referenceData xmlns="reference-data:2">
                      <reference><Map>geo_ip</Map></reference>
                    </referenceData>"""));

            assertThat(result.resolvedValues(XsltReferenceKind.REF_MAP_WRITE)).containsExactly("geo_ip");
        }

        @Test
        @DisplayName("resolves a map name produced by xsl:value-of of a literal variable")
        void fromVariable() {
            final XsltReferences result = parse("""
                    <xsl:stylesheet version="2.0"
                                    xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                                    xmlns:stroom="stroom">
                      <xsl:variable name="MAP" select="'geo_ip'"/>
                      <xsl:template match="/">
                        <reference xmlns="reference-data:2">
                          <map><xsl:value-of select="$MAP"/></map>
                        </reference>
                      </xsl:template>
                    </xsl:stylesheet>""");

            assertThat(result.references())
                    .filteredOn(reference -> reference.kind() == XsltReferenceKind.REF_MAP_WRITE)
                    .extracting(XsltReference::rawValue, XsltReference::certainty)
                    .containsExactly(tuple("geo_ip", XsltReferenceCertainty.INFERRED));
        }

        @Test
        @DisplayName("a data-driven map name is reported unresolved, so the panel is known to be partial")
        void dataDriven() {
            final XsltReferences result = parse(template("""
                    <reference xmlns="reference-data:2">
                      <map><xsl:value-of select="@mapName"/></map>
                    </reference>"""));

            assertThat(result.references())
                    .filteredOn(reference -> reference.kind() == XsltReferenceKind.REF_MAP_WRITE)
                    .extracting(XsltReference::reason)
                    .containsExactly(XsltReferenceReason.DATA_DRIVEN);
        }
    }

    // ------------------------------------------------------------------------
    // External endpoints
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("external endpoints")
    class Endpoints {

        @Test
        @DisplayName("http-call is outbound, because it POSTs a body")
        void httpCallIsOutbound() {
            final XsltReferences result = parse(template("""
                    <xsl:value-of select="stroom:http-call('https://example.com/api', '', '', '')"/>"""));

            assertThat(result.references())
                    .extracting(XsltReference::kind, XsltReference::rawValue, XsltReference::direction)
                    .containsExactly(tuple(
                            XsltReferenceKind.HTTP,
                            "https://example.com/api",
                            XsltReferenceDirection.OUT));
        }

        @Test
        @DisplayName("fetch-json is inbound, because it GETs")
        void fetchJsonIsInbound() {
            final XsltReferences result = parse(template("""
                    <xsl:copy-of select="stroom:fetch-json('https://example.com/feed.json')"/>"""));

            assertThat(result.references())
                    .extracting(XsltReference::direction)
                    .containsExactly(XsltReferenceDirection.IN);
        }
    }

    // ------------------------------------------------------------------------
    // Value resolution
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("value resolution")
    class ValueResolution {

        @Test
        @DisplayName("folds concat of literals")
        void concatOfLiterals() {
            final XsltReferences result = parse(template("""
                    <xsl:value-of select="stroom:lookup(concat('geo_', 'prod'), @ip)"/>"""));

            // STATIC, which is worth explaining because it looks wrong. Saxon evaluates a concat of pure
            // literals while compiling, so the parser is handed 'geo_prod' already folded and never sees
            // the concat. Certainty records whether the parser had to reason, not whether the string can
            // be found in the file - see XsltReferenceCertainty.
            assertThat(result.references())
                    .extracting(XsltReference::rawValue, XsltReference::certainty)
                    .containsExactly(tuple("geo_prod", XsltReferenceCertainty.STATIC));
        }

        @Test
        @DisplayName("folds concat involving a variable, which Saxon cannot pre-evaluate")
        void concatWithVariableIsInferred() {
            final XsltReferences result = parse("""
                    <xsl:stylesheet version="2.0"
                                    xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                                    xmlns:stroom="stroom">
                      <xsl:variable name="ENV" select="'prod'"/>
                      <xsl:template match="/">
                        <xsl:value-of select="stroom:lookup(concat('geo_', $ENV), @ip)"/>
                      </xsl:template>
                    </xsl:stylesheet>""");

            // Here the parser does the work, because Saxon cannot: it reads the declaration Saxon was
            // told nothing about. So this one is INFERRED.
            assertThat(result.references())
                    .extracting(XsltReference::rawValue, XsltReference::certainty)
                    .containsExactly(tuple("geo_prod", XsltReferenceCertainty.INFERRED));
        }

        @Test
        @DisplayName("a literal at the point of use is static")
        void literalIsStatic() {
            final XsltReferences result = parse(template("""
                    <xsl:value-of select="stroom:lookup('geo_ip', @ip)"/>"""));

            assertThat(result.references())
                    .extracting(XsltReference::certainty)
                    .containsExactly(XsltReferenceCertainty.STATIC);
        }

        @Test
        @DisplayName("resolves a global literal variable")
        void globalVariable() {
            final XsltReferences result = parse("""
                    <xsl:stylesheet version="2.0"
                                    xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                                    xmlns:stroom="stroom">
                      <xsl:variable name="MAP" select="'geo_ip'"/>
                      <xsl:template match="/">
                        <xsl:value-of select="stroom:lookup($MAP, @ip)"/>
                      </xsl:template>
                    </xsl:stylesheet>""");

            assertThat(result.resolvedValues(XsltReferenceKind.REF_MAP_READ)).containsExactly("geo_ip");
        }

        @Test
        @DisplayName("resolves a global variable declared BELOW the template that uses it")
        void globalDeclaredBelowIsInScope() {
            // A global's scope is the whole stylesheet irrespective of document order. Applying the
            // preceding-sibling rule uniformly would wrongly refuse this.
            final XsltReferences result = parse("""
                    <xsl:stylesheet version="2.0"
                                    xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                                    xmlns:stroom="stroom">
                      <xsl:template match="/">
                        <xsl:value-of select="stroom:lookup($MAP, @ip)"/>
                      </xsl:template>
                      <xsl:variable name="MAP" select="'geo_ip'"/>
                    </xsl:stylesheet>""");

            assertThat(result.resolvedValues(XsltReferenceKind.REF_MAP_READ)).containsExactly("geo_ip");
        }

        @Test
        @DisplayName("resolves a local literal variable")
        void localVariable() {
            final XsltReferences result = parse(template("""
                    <xsl:variable name="MAP" select="'local_map'"/>
                    <xsl:value-of select="stroom:lookup($MAP, @ip)"/>"""));

            assertThat(result.resolvedValues(XsltReferenceKind.REF_MAP_READ)).containsExactly("local_map");
        }

        @Test
        @DisplayName("a local shadowing a global resolves to the LOCAL value")
        void localShadowsGlobal() {
            final XsltReferences result = parse("""
                    <xsl:stylesheet version="2.0"
                                    xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                                    xmlns:stroom="stroom">
                      <xsl:variable name="MAP" select="'global_map'"/>
                      <xsl:template match="/">
                        <xsl:variable name="MAP" select="'local_map'"/>
                        <xsl:value-of select="stroom:lookup($MAP, @ip)"/>
                      </xsl:template>
                    </xsl:stylesheet>""");

            assertThat(result.resolvedValues(XsltReferenceKind.REF_MAP_READ))
                    .containsExactly("local_map")
                    .doesNotContain("global_map");
        }

        @Test
        @DisplayName("a local declared LATER in the same template is not in scope")
        void localDeclaredLaterIsNotInScope() {
            // The declaration follows the reference, so it is not in scope and the global applies. An
            // ancestor-only walk would wrongly find the local.
            final XsltReferences result = parse("""
                    <xsl:stylesheet version="2.0"
                                    xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                                    xmlns:stroom="stroom">
                      <xsl:variable name="MAP" select="'global_map'"/>
                      <xsl:template match="/">
                        <xsl:value-of select="stroom:lookup($MAP, @ip)"/>
                        <xsl:variable name="MAP" select="'later_local'"/>
                      </xsl:template>
                    </xsl:stylesheet>""");

            assertThat(result.resolvedValues(XsltReferenceKind.REF_MAP_READ))
                    .containsExactly("global_map")
                    .doesNotContain("later_local");
        }

        @Test
        @DisplayName("folds a variable built from another variable, recursively")
        void recursiveFolding() {
            final XsltReferences result = parse("""
                    <xsl:stylesheet version="2.0"
                                    xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                                    xmlns:stroom="stroom">
                      <xsl:variable name="ENV" select="'prod'"/>
                      <xsl:variable name="BASE" select="concat('geo_', $ENV)"/>
                      <xsl:template match="/">
                        <xsl:value-of select="stroom:lookup($BASE, @ip)"/>
                      </xsl:template>
                    </xsl:stylesheet>""");

            assertThat(result.resolvedValues(XsltReferenceKind.REF_MAP_READ)).containsExactly("geo_prod");
        }

        @Test
        @DisplayName("resolves a variable bound by its text content rather than @select")
        void variableWithTextContent() {
            final XsltReferences result = parse("""
                    <xsl:stylesheet version="2.0"
                                    xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                                    xmlns:stroom="stroom">
                      <xsl:variable name="MAP">geo_ip</xsl:variable>
                      <xsl:template match="/">
                        <xsl:value-of select="stroom:lookup($MAP, @ip)"/>
                      </xsl:template>
                    </xsl:stylesheet>""");

            assertThat(result.resolvedValues(XsltReferenceKind.REF_MAP_READ)).containsExactly("geo_ip");
        }

        @Test
        @DisplayName("a conditional yields every reachable literal, not none")
        void conditionalYieldsAllBranches() {
            final XsltReferences result = parse(template("""
                    <xsl:value-of select="stroom:lookup(if (@live) then 'map_a' else 'map_b', @ip)"/>"""));

            assertThat(result.resolvedValues(XsltReferenceKind.REF_MAP_READ))
                    .containsExactly("map_a", "map_b");
        }

        @Test
        @DisplayName("a conditionally bound variable yields every literal outcome")
        void conditionalVariableBinding() {
            final XsltReferences result = parse("""
                    <xsl:stylesheet version="2.0"
                                    xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                                    xmlns:stroom="stroom">
                      <xsl:template match="/">
                        <xsl:variable name="MAP">
                          <xsl:choose>
                            <xsl:when test="@live">map_a</xsl:when>
                            <xsl:otherwise>map_b</xsl:otherwise>
                          </xsl:choose>
                        </xsl:variable>
                        <xsl:value-of select="stroom:lookup($MAP, @ip)"/>
                      </xsl:template>
                    </xsl:stylesheet>""");

            assertThat(result.resolvedValues(XsltReferenceKind.REF_MAP_READ))
                    .containsExactly("map_a", "map_b");
        }

        @Test
        @DisplayName("a partly literal conditional reports the literal AND the reason for the rest")
        void partiallyResolvedConditional() {
            final XsltReferences result = parse(template("""
                    <xsl:value-of select="stroom:lookup(if (@live) then 'map_a' else @other, @ip)"/>"""));

            // Reporting only the literal would overstate what is known; reporting only the reason would
            // discard a real reference. Both.
            assertThat(result.references())
                    .extracting(XsltReference::rawValue, XsltReference::reason)
                    .containsExactly(
                            tuple("map_a", null),
                            tuple("stroom:lookup(if (@live) then 'map_a' else @other, @ip)",
                                    XsltReferenceReason.DATA_DRIVEN));
        }

        @Test
        @DisplayName("a variable reference cycle is reported unresolved rather than hanging")
        void cycleTerminates() {
            final String xslt = """
                    <xsl:stylesheet version="2.0"
                                    xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                                    xmlns:stroom="stroom">
                      <xsl:variable name="A" select="concat($B, 'x')"/>
                      <xsl:variable name="B" select="concat($A, 'y')"/>
                      <xsl:template match="/">
                        <xsl:value-of select="stroom:lookup($A, @ip)"/>
                      </xsl:template>
                    </xsl:stylesheet>""";

            assertThatCode(() -> {
                final XsltReferences result = parse(xslt);
                assertThat(result.references())
                        .extracting(XsltReference::reason)
                        .containsExactly(XsltReferenceReason.NON_LITERAL_BINDING);
            }).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("finds a lookup inside an attribute value template")
        void insideAttributeValueTemplate() {
            final XsltReferences result = parse(template("""
                    <record location="{stroom:lookup('geo_ip', @ip)}"/>"""));

            assertThat(result.resolvedValues(XsltReferenceKind.REF_MAP_READ)).containsExactly("geo_ip");
        }

        @Test
        @DisplayName("finds a lookup nested inside another function call")
        void nestedInsideAnotherCall() {
            final XsltReferences result = parse(template("""
                    <xsl:value-of select="upper-case(stroom:lookup('geo_ip', stroom:meta('id')))"/>"""));

            assertThat(result.resolvedValues(XsltReferenceKind.REF_MAP_READ)).containsExactly("geo_ip");
        }

        @Test
        @DisplayName("finds a lookup in an expression using XSLT-only functions")
        void alongsideXsltOnlyFunctions() {
            // Found by running the parser over real content, where every expression inside an
            // xsl:for-each-group came back unanalysable. current-grouping-key() and current-group() are
            // defined by XSLT, not XPath, so a standalone XPath compiler rejects them - and rejecting the
            // expression loses whatever the parser was looking for inside it.
            final XsltReferences result = parse("""
                    <xsl:stylesheet version="3.0"
                                    xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                                    xmlns:stroom="stroom">
                      <xsl:template match="/">
                        <xsl:for-each-group select="record" group-by="@type">
                          <xsl:value-of select="stroom:lookup('found_anyway', current-grouping-key())"/>
                          <xsl:if test="count(current-group()) gt 1">
                            <xsl:value-of select="stroom:dictionary('AlsoFound')"/>
                          </xsl:if>
                        </xsl:for-each-group>
                      </xsl:template>
                    </xsl:stylesheet>""");

            assertThat(result.references())
                    .extracting(XsltReference::reason)
                    .doesNotContain(XsltReferenceReason.UNPARSEABLE);
            assertThat(result.resolvedValues(XsltReferenceKind.REF_MAP_READ))
                    .containsExactly("found_anyway");
            assertThat(result.references())
                    .extracting(XsltReference::kind, XsltReference::rawValue)
                    .contains(tuple(XsltReferenceKind.DICTIONARY, "AlsoFound"));
        }

        @Test
        @DisplayName("key() and document() are stubbed too, being XSLT-only")
        void otherXsltOnlyFunctions() {
            final XsltReferences result = parse(template("""
                    <xsl:value-of select="stroom:lookup('m1', key('k', @id))"/>
                    <xsl:value-of select="stroom:lookup('m2', document('other.xml')/root)"/>"""));

            assertThat(result.resolvedValues(XsltReferenceKind.REF_MAP_READ)).containsExactly("m1", "m2");
            assertThat(result.unresolved()).isEmpty();
        }

        @Test
        @DisplayName("concat is NOT stubbed, so folding still works")
        void concatIsNotStubbed() {
            // The XSLT-only list is an allow list precisely so that functions Saxon does implement keep
            // their real behaviour. Stubbing concat would silently disable XP-12's folding.
            final XsltReferences result = parse(template("""
                    <xsl:value-of select="stroom:lookup(concat('geo_', 'prod'), @ip)"/>"""));

            assertThat(result.resolvedValues(XsltReferenceKind.REF_MAP_READ)).containsExactly("geo_prod");
        }

        @Test
        @DisplayName("finds a lookup inside a template match pattern's predicate")
        void insidePattern() {
            final XsltReferences result = parse("""
                    <xsl:stylesheet version="2.0"
                                    xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                                    xmlns:stroom="stroom">
                      <xsl:template match="record[stroom:lookup('geo_ip', @ip)]"/>
                    </xsl:stylesheet>""");

            assertThat(result.resolvedValues(XsltReferenceKind.REF_MAP_READ)).containsExactly("geo_ip");
        }
    }

    // ------------------------------------------------------------------------
    // One case per reason code
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("reason codes")
    class Reasons {

        @Test
        @DisplayName("DATA_DRIVEN where the name comes from the input")
        void dataDriven() {
            assertReason("stroom:lookup(@mapName, @ip)", XsltReferenceReason.DATA_DRIVEN);
        }

        @Test
        @DisplayName("DATA_DRIVEN where the name comes from a path expression")
        void dataDrivenPath() {
            assertReason("stroom:lookup(//config/map, @ip)", XsltReferenceReason.DATA_DRIVEN);
        }

        @Test
        @DisplayName("PARAMETER where the binding is a template param, even with a literal default")
        void parameterWithLiteralDefault() {
            // Proving no caller overrides it needs call-graph analysis across apply-templates with modes,
            // and a default says nothing about the value that will be used.
            final XsltReferences result = parse("""
                    <xsl:stylesheet version="2.0"
                                    xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                                    xmlns:stroom="stroom">
                      <xsl:template match="/">
                        <xsl:param name="MAP" select="'never_trust_this'"/>
                        <xsl:value-of select="stroom:lookup($MAP, @ip)"/>
                      </xsl:template>
                    </xsl:stylesheet>""");

            assertThat(result.references())
                    .extracting(XsltReference::reason)
                    .containsExactly(XsltReferenceReason.PARAMETER);
            assertThat(result.resolvedValues(XsltReferenceKind.REF_MAP_READ)).isEmpty();
        }

        @Test
        @DisplayName("PARAMETER where the binding is a global param")
        void globalParameter() {
            final XsltReferences result = parse("""
                    <xsl:stylesheet version="2.0"
                                    xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                                    xmlns:stroom="stroom">
                      <xsl:param name="MAP" select="'supplied_outside'"/>
                      <xsl:template match="/">
                        <xsl:value-of select="stroom:lookup($MAP, @ip)"/>
                      </xsl:template>
                    </xsl:stylesheet>""");

            assertThat(result.references())
                    .extracting(XsltReference::reason)
                    .containsExactly(XsltReferenceReason.PARAMETER);
        }

        @Test
        @DisplayName("NON_LITERAL_BINDING where a variable will not fold")
        void nonLiteralBinding() {
            final XsltReferences result = parse("""
                    <xsl:stylesheet version="2.0"
                                    xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                                    xmlns:stroom="stroom">
                      <xsl:variable name="MAP" select="current-dateTime()"/>
                      <xsl:template match="/">
                        <xsl:value-of select="stroom:lookup($MAP, @ip)"/>
                      </xsl:template>
                    </xsl:stylesheet>""");

            assertThat(result.references())
                    .extracting(XsltReference::reason)
                    .containsExactly(XsltReferenceReason.NON_LITERAL_BINDING);
        }

        @Test
        @DisplayName("IMPORTED where nothing in this document declares the variable")
        void imported() {
            // The parser reads one document. A variable it cannot see is presumably declared in an
            // imported stylesheet, which is a different answer from "not foldable".
            assertReason("stroom:lookup($DECLARED_ELSEWHERE, @ip)", XsltReferenceReason.IMPORTED);
        }

        @Test
        @DisplayName("UNPARSEABLE where an expression is not valid XPath, without losing the document")
        void unparseableIsContained() {
            final XsltReferences result = parse(template("""
                    <xsl:value-of select="this is not ( valid xpath"/>
                    <xsl:value-of select="stroom:lookup('still_found', @ip)"/>"""));

            assertThat(result.hasParseFailure()).isFalse();
            assertThat(result.references())
                    .extracting(XsltReference::reason)
                    .containsExactly(XsltReferenceReason.UNPARSEABLE, null);
            assertThat(result.resolvedValues(XsltReferenceKind.REF_MAP_READ)).containsExactly("still_found");
        }

        private void assertReason(final String expression, final XsltReferenceReason expected) {
            final XsltReferences result = parse(template(
                    "<xsl:value-of select=\"" + expression + "\"/>"));
            assertThat(result.references())
                    .extracting(XsltReference::reason)
                    .containsExactly(expected);
        }
    }

    // ------------------------------------------------------------------------
    // Staying quiet - the property that matters most
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("no false positives")
    class NoFalsePositives {

        @Test
        @DisplayName("a literal in a condition is not a reference")
        void literalInConditionIgnored() {
            // The 'user' here is a comparison value, not a map name. Collecting every literal in the
            // expression tree rather than the arguments of the calls would wrongly report it.
            final XsltReferences result = parse(template("""
                    <xsl:if test="@type = 'user'">
                      <xsl:value-of select="@name"/>
                    </xsl:if>"""));

            assertThat(result.references()).isEmpty();
        }

        @Test
        @DisplayName("a same-named function in another namespace is not a Stroom function")
        void otherNamespaceIgnored() {
            final XsltReferences result = parse("""
                    <xsl:stylesheet version="2.0"
                                    xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                                    xmlns:other="not-stroom">
                      <xsl:template match="/">
                        <xsl:value-of select="other:lookup('not_a_map', @ip)"/>
                      </xsl:template>
                    </xsl:stylesheet>""");

            // No map read is claimed, which is the property that matters. The expression does not compile -
            // no such function exists, here or at runtime - so it is reported as unanalysable rather than
            // attributed to a kind of reference it might not be.
            assertThat(result.resolvedValues(XsltReferenceKind.REF_MAP_READ)).isEmpty();
            assertThat(result.documentTargets()).isEmpty();
            assertThat(result.references())
                    .extracting(XsltReference::kind, XsltReference::reason)
                    .containsExactly(tuple(XsltReferenceKind.UNANALYSED, XsltReferenceReason.UNPARSEABLE));
        }

        @Test
        @DisplayName("a valid expression in another namespace yields nothing at all")
        void otherNamespaceValidExpressionIsSilent() {
            // The previous case reports something only because the expression is broken. A perfectly good
            // expression that simply is not a Stroom call must produce no finding whatsoever.
            final XsltReferences result = parse(template("""
                    <xsl:value-of select="substring(@name, 1, 4)"/>"""));

            assertThat(result.references()).isEmpty();
        }

        @Test
        @DisplayName("an xsl:variable named 'map' is not map output")
        void xsltElementNamedMapIgnored() {
            final XsltReferences result = parse(template("""
                    <xsl:variable name="map">not_output</xsl:variable>"""));

            assertThat(result.references()).isEmpty();
        }

        @Test
        @DisplayName("a lookup in a template that can never match is still recorded")
        void reachabilityNotAnalysed() {
            // Presence in the syntax tree is what is recorded. Deciding whether a template ever matches
            // is a different and much harder question, and a reference in dead code is still a reference
            // in the configuration.
            final XsltReferences result = parse("""
                    <xsl:stylesheet version="2.0"
                                    xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                                    xmlns:stroom="stroom">
                      <xsl:template match="never-appears-in-any-input">
                        <xsl:value-of select="stroom:lookup('still_recorded', @ip)"/>
                      </xsl:template>
                    </xsl:stylesheet>""");

            assertThat(result.resolvedValues(XsltReferenceKind.REF_MAP_READ))
                    .containsExactly("still_recorded");
        }

        @Test
        @DisplayName("doubled braces are literal text, not an embedded expression")
        void doubledBracesIgnored() {
            final XsltReferences result = parse(template("""
                    <record note="{{stroom:lookup('not_an_expression', @ip)}}"/>"""));

            assertThat(result.references()).isEmpty();
        }

        @Test
        @DisplayName("an unknown Stroom function contributes nothing of its own")
        void unknownStroomFunctionIgnored() {
            // It still has to compile, or the lookup nested in it would be lost - see the next case.
            final XsltReferences result = parse(template("""
                    <xsl:value-of select="stroom:some-future-function('a', 'b')"/>"""));

            assertThat(result.references()).isEmpty();
        }

        @Test
        @DisplayName("a lookup nested inside an unknown Stroom function is still found")
        void lookupInsideUnknownFunctionStillFound() {
            final XsltReferences result = parse(template("""
                    <xsl:value-of select="stroom:some-future-function(stroom:lookup('found_anyway', @ip))"/>"""));

            assertThat(result.resolvedValues(XsltReferenceKind.REF_MAP_READ))
                    .containsExactly("found_anyway");
        }
    }

    // ------------------------------------------------------------------------
    // Real translations - whole stylesheets taken from working configurations
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("real translations")
    class RealTranslations {

        @Test
        @DisplayName("an ordinary translation with no references at all is completely silent")
        void realWorldTranslationWithNoReferences() {
            // A representative Stroom translation: records:2 in, event-logging out, five variables bound
            // from the input, no lookups, no dictionary, no imports, no map output. The right answer is
            // nothing at all - and the parse must succeed, because an empty result only means "no
            // references" if the document was actually read. Were it reported as a parse failure the
            // silence would be meaningless.
            //
            // The interesting part is the variables. Every @select here holds a string literal inside a
            // predicate - data[@name='id'] - and none of them is a reference. A parser that collected
            // literals from the expression tree, rather than only the arguments of Stroom calls, would
            // report five map names that do not exist.
            final XsltReferences result = parse("""
                    <?xml version="1.1" encoding="UTF-8" ?>
                    <xsl:stylesheet
                        xpath-default-namespace="records:2"
                        xmlns:stroom="stroom"
                        xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                        version="2.0">

                      <xsl:template match="records">
                        <Events>
                          <xsl:apply-templates />
                        </Events>
                      </xsl:template>
                      <xsl:template match="record">
                        <xsl:variable name="id" select="data[@name='id']/@value" />
                        <xsl:variable name="guid" select="data[@name='guid']/@value" />
                        <xsl:variable name="from_ip" select="data[@name='from_ip']/@value" />
                        <xsl:variable name="to_ip" select="data[@name='to_ip']/@value" />
                        <xsl:variable name="application" select="data[@name='application']/@value" />

                        <Event>
                          <Id><xsl:value-of select="$id" /></Id>
                          <Guid><xsl:value-of select="$guid" /></Guid>
                          <FromIp><xsl:value-of select="$from_ip" /></FromIp>
                          <ToIp><xsl:value-of select="$to_ip" /></ToIp>
                          <Application><xsl:value-of select="$application" /></Application>
                        </Event>
                      </xsl:template>
                    </xsl:stylesheet>""");

            assertThat(result.hasParseFailure()).isFalse();
            assertThat(result.references()).isEmpty();
        }

        @Test
        @DisplayName("an ordinary event-to-records translation is silent, URL-shaped attributes included")
        void realWorldTranslationWithDefaultNamespaceAndComments() {
            // The other direction - event-logging in, records:2 out - and it covers ground the previous
            // fixture does not:
            //
            //  - a default namespace (xmlns="records:2"), so every literal result element is namespaced,
            //    and the parser must not treat a namespaced element as anything special;
            //  - XML comments between and inside elements, which the walk must step over;
            //  - xsi:schemaLocation holding "file://records-v2.0.xsd". That looks like an endpoint, and a
            //    parser that scanned attributes for URLs would report it. Endpoints come only from the
            //    arguments of stroom:http-call and stroom:fetch-json, so this must yield nothing;
            //  - xsl:attribute, whose @name is an attribute value template and whose @select is XPath -
            //    two attributes on one element needing different treatment.
            final XsltReferences result = parse("""
                    <?xml version="1.0" encoding="UTF-8" ?>
                    <xsl:stylesheet
                        xmlns="records:2"
                        xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                        version="2.0">

                      <!-- Match on the top level Events element -->
                      <xsl:template match="/Events">
                        <!-- Create the wrapper element for all the events/records -->
                        <records
                            xsi:schemaLocation="records:2 file://records-v2.0.xsd"
                            version="2.0">
                          <!-- Apply any templates to this element or its children -->
                          <xsl:apply-templates />
                        </records>
                      </xsl:template>

                      <!-- Match on any Event element at this level -->
                      <xsl:template match="Event">
                        <!-- Create a record element and populate its data items -->
                        <record>
                          <data name="StreamId">
                            <!-- Added to the event by the IdEnrichmentFiler -->
                            <xsl:attribute name="value" select="@StreamId" />
                          </data>
                          <data name="EventId">
                            <!-- Added to the event by the IdEnrichmentFiler -->
                            <xsl:attribute name="value" select="@EventId" />
                          </data>
                          <data name="Id">
                            <xsl:attribute name="value" select="./Id" />
                          </data>
                          <data name="Guid">
                            <xsl:attribute name="value" select="./Guid" />
                          </data>
                          <data name="FromIp">
                            <xsl:attribute name="value" select="./FromIp" />
                          </data>
                          <data name="ToIp">
                            <xsl:attribute name="value" select="./ToIp" />
                          </data>
                          <data name="Application">
                            <xsl:attribute name="value" select="./Application" />
                          </data>
                        </record>
                      </xsl:template>
                    </xsl:stylesheet>""");

            assertThat(result.hasParseFailure()).isFalse();
            assertThat(result.references()).isEmpty();
        }

        @Test
        @DisplayName("stroom:put is a Stroom call whose literal first argument is not a reference")
        void realWorldTranslationUsingStroomPut() {
            // A Bluecoat translation, and the most searching false-positive case in the suite because it
            // does call a Stroom function. stroom:put stores a value in a task-scoped map for a later
            // stroom:get; its first argument is an arbitrary key, not a document and not a reference data
            // map. Only dictionary, lookup, bitmap-lookup, http-call and fetch-json name anything.
            //
            // So a parser that took "argument 0 of any stroom: function" as a reference - an easy and
            // plausible shortcut - would report '_bc_software' and '_bc_version' as map names that no
            // store will ever match. The right answer is silence, while stroom:put must still compile, or
            // anything nested inside it would be lost.
            //
            // Also covered: XSLT 3.0, both xpath-default-namespace and a default output namespace at once,
            // xsl:choose at template level (whose @test predicates hold literals), xsl:call-template/@name,
            // and a concat of input paths with literal separators 'T' and '.000Z'.
            final XsltReferences result = parse("""
                    <?xml version="1.0" encoding="UTF-8" ?>
                    <xsl:stylesheet
                        xpath-default-namespace="records:2"
                        xmlns="event-logging:3"
                        xmlns:stroom="stroom"
                        xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                        xmlns:xs="http://www.w3.org/2001/XMLSchema"
                        version="3.0">

                      <!-- Bluecoat Proxy logs in W3C Extended Log File Format (ELF) -->

                      <!-- Ingest the record key value pair elements -->
                      <xsl:template match="records">
                        <Events xsi:schemaLocation="event-logging:3 file://event-logging-v3.2.4.xsd"
                                Version="3.2.4">
                          <xsl:apply-templates />
                        </Events>
                      </xsl:template>

                      <!-- Main record template for single event -->
                      <xsl:template match="record">
                        <xsl:choose>

                          <!-- Store the Software and Version information of the Bluecoat log file for use
                          in the Event Source elements which are processed later -->
                          <xsl:when test="data[@name='_bc_software']">
                            <xsl:value-of select="stroom:put('_bc_software', data[@name='_bc_software']/@value)" />
                          </xsl:when>
                          <xsl:when test="data[@name='_bc_version']">
                            <xsl:value-of select="stroom:put('_bc_version', data[@name='_bc_version']/@value)" />
                          </xsl:when>

                          <!-- Process the event logs -->
                          <xsl:otherwise>
                            <Event>
                              <xsl:call-template name="event_time" />
                            </Event>
                          </xsl:otherwise>
                        </xsl:choose>
                      </xsl:template>

                      <!-- Time -->
                      <xsl:template name="event_time">
                        <EventTime>
                          <TimeCreated>
                            <xsl:value-of
                                select="concat(data[@name = 'date']/@value,'T',data[@name='time']/@value,'.000Z')" />
                          </TimeCreated>
                        </EventTime>
                      </xsl:template>
                    </xsl:stylesheet>""");

            assertThat(result.hasParseFailure()).isFalse();
            assertThat(result.references()).isEmpty();
        }

        @Test
        @DisplayName("the full Bluecoat translation: entities, AVTs, meta and get, still no references")
        void realWorldBluecoatWithEventSource() {
            // The same translation carried through to its event source template, which adds several
            // things nothing else in the suite covers:
            //
            //  - an XML entity inside an XPath string literal: translate(..., '&quot;', ''). After
            //    expansion the expression contains a quote character as data. This is the concrete reason
            //    a text-scanning implementation cannot work: the parser sees the expression only after the
            //    XML parser has expanded entities, whereas a scanner would see the raw bytes;
            //  - stroom:meta and stroom:get, two more Stroom functions that name no document. Their
            //    literal arguments - 'MyMeta', 'System', 'Environment', '_bc_software' - must not be
            //    mistaken for map names;
            //  - an attribute value template holding a path expression, Value="{data[...]/@value}", whose
            //    predicate contains yet another literal;
            //  - a variable bound by element content containing xsl:if and xsl:value-of ($gen), and
            //    another built from three consecutive xsl:value-of elements ($user). Neither is passed to
            //    a reference-bearing function, so neither should be resolved at all - the parser does that
            //    work only where it could produce a reference;
            //  - xsl:attribute/@select reading a variable, and a leading space inside an XPath attribute.
            final XsltReferences result = parse("""
                    <?xml version="1.0" encoding="UTF-8" ?>
                    <xsl:stylesheet
                        xpath-default-namespace="records:2"
                        xmlns="event-logging:3"
                        xmlns:stroom="stroom"
                        xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                        xmlns:xs="http://www.w3.org/2001/XMLSchema"
                        version="3.0">

                      <!-- Bluecoat Proxy logs in W3C Extended Log File Format (ELF) -->

                      <!-- Ingest the record key value pair elements -->
                      <xsl:template match="records">
                        <Events xsi:schemaLocation="event-logging:3 file://event-logging-v3.2.4.xsd" Version="3.2.4">
                          <xsl:apply-templates />
                        </Events>
                      </xsl:template>

                      <!-- Main record template for single event -->
                      <xsl:template match="record">
                        <xsl:choose>

                          <!-- Store the Software and Version information of the Bluecoat log file for use in
                          the Event Source elements which are processed later -->
                          <xsl:when test="data[@name='_bc_software']">
                            <xsl:value-of select="stroom:put('_bc_software', data[@name='_bc_software']/@value)" />
                          </xsl:when>
                          <xsl:when test="data[@name='_bc_version']">
                            <xsl:value-of select="stroom:put('_bc_version', data[@name='_bc_version']/@value)" />
                          </xsl:when>

                          <!-- Process the event logs -->
                          <xsl:otherwise>
                            <Event>
                              <xsl:call-template name="event_time" />
                              <xsl:call-template name="event_source" />
                            </Event>
                          </xsl:otherwise>
                        </xsl:choose>
                      </xsl:template>

                      <!-- Time -->
                      <xsl:template name="event_time">
                        <EventTime>
                          <TimeCreated>
                            <xsl:value-of
                                select="concat(data[@name = 'date']/@value,'T',data[@name='time']/@value,'.000Z')" />
                          </TimeCreated>
                        </EventTime>
                      </xsl:template>

                      <!-- Template for event source-->
                      <xsl:template name="event_source">

                        <!--
                        We extract some situational awareness information that the posting script includes
                        when posting the event data
                        -->
                        <xsl:variable name="_mymeta" select="translate(stroom:meta('MyMeta'),'&quot;', '')" />

                        <!-- Form the EventSource node -->
                        <EventSource>
                          <System>
                            <Name>
                              <xsl:value-of select="stroom:meta('System')" />
                            </Name>
                            <Environment>
                              <xsl:value-of select="stroom:meta('Environment')" />
                            </Environment>
                          </System>
                          <Generator>
                            <xsl:variable name="gen">
                              <xsl:if test="stroom:get('_bc_software')">
                                <xsl:value-of select="concat(' Software: ', stroom:get('_bc_software'))" />
                              </xsl:if>
                              <xsl:if test="stroom:get('_bc_version')">
                                <xsl:value-of select="concat(' Version: ', stroom:get('_bc_version'))" />
                              </xsl:if>
                            </xsl:variable>
                            <xsl:value-of select="concat('Bluecoat', $gen)" />
                          </Generator>
                          <xsl:if test="data[@name='s-computername'] or data[@name='s-ip']">
                            <Device>
                              <xsl:if test="data[@name='s-computername']">
                                <Name>
                                  <xsl:value-of select="data[@name='s-computername']/@value" />
                                </Name>
                              </xsl:if>
                              <xsl:if test="data[@name='s-ip']">
                                <IPAddress>
                                  <xsl:value-of select=" data[@name='s-ip']/@value" />
                                </IPAddress>
                              </xsl:if>
                              <xsl:if test="data[@name='s-sitename']">
                                <Data Name="ServiceType" Value="{data[@name='s-sitename']/@value}" />
                              </xsl:if>
                            </Device>
                          </xsl:if>

                          <!-- -->
                          <Client>
                            <xsl:if test="data[@name='c-ip']/@value != '-'">
                              <IPAddress>
                                <xsl:value-of select="data[@name='c-ip']/@value" />
                              </IPAddress>
                            </xsl:if>

                            <!-- Remote Port Number -->
                            <xsl:if test="data[@name='c-port']/@value !='-'">
                              <Port>
                                <xsl:value-of select="data[@name='c-port']/@value" />
                              </Port>
                            </xsl:if>
                          </Client>

                          <!-- -->
                          <Server>
                            <HostName>
                              <xsl:value-of select="data[@name='cs-host']/@value" />
                            </HostName>
                          </Server>

                          <!-- -->
                          <xsl:variable name="user">
                            <xsl:value-of select="data[@name='cs-user']/@value" />
                            <xsl:value-of select="data[@name='cs-username']/@value" />
                            <xsl:value-of select="data[@name='cs-userdn']/@value" />
                          </xsl:variable>
                          <xsl:if test="$user !='-'">
                            <User>
                              <Id>
                                <xsl:value-of select="$user" />
                              </Id>
                            </User>
                          </xsl:if>
                          <Data Name="MyMeta">
                            <xsl:attribute name="Value" select="$_mymeta" />
                          </Data>
                        </EventSource>
                      </xsl:template>
                    </xsl:stylesheet>""");

            assertThat(result.hasParseFailure()).isFalse();
            assertThat(result.references()).isEmpty();
        }

        @Test
        @DisplayName("an Apache HTTPD translation: four real lookups, and no document edges at all")
        void realWorldApacheWithLookups() {
            // The first real translation here that actually looks anything up, so it exercises the half
            // the other fixtures could not: finding references rather than correctly finding none.
            //
            // Four stroom:lookup calls, each with a literal map name, interleaved with three Stroom
            // functions that name nothing - stroom:format-date and stroom:feed-attribute - and with the
            // map name always argument 0 while the key, the effective date and the trace flag follow.
            //
            // Whitespace is left exactly as written, including the double spaces inside expressions such
            // as data[@name  =  'vserver'] and stroom:lookup('FQDN_TO_IP',$vServer, ...). Indentation has
            // been regularised and some long attribute values wrapped, to stay inside the line length
            // limit; every expression is otherwise character for character as supplied, and XPath treats
            // the inserted newlines as the whitespace they are.
            final XsltReferences result = parse("""
                    <?xml version="1.0" encoding="UTF-8" ?>
                    <xsl:stylesheet xpath-default-namespace="records:2"
                                    xmlns="event-logging:3"
                                    xmlns:stroom="stroom"
                                    xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                                    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                                    xmlns:xs="http://www.w3.org/2001/XMLSchema"
                                    version="3.0">

                      <!-- Ingest the records tree -->
                      <xsl:template match="records">
                        <Events xsi:schemaLocation="event-logging:3 file://event-logging-v3.2.3.xsd" Version="3.2.3">
                          <xsl:apply-templates />
                        </Events>
                      </xsl:template>

                      <!-- Only generate events if we have an url on input -->
                      <xsl:template match="record[data[@name = 'url']]">
                        <Event>
                          <xsl:apply-templates select="." mode="eventTime" />
                          <xsl:apply-templates select="." mode="eventSource" />
                          <xsl:apply-templates select="." mode="eventDetail" />
                        </Event>
                      </xsl:template>

                      <xsl:template match="node()"  mode="eventTime">
                        <EventTime>
                          <TimeCreated>
                            <xsl:value-of
                                select="stroom:format-date(data[@name = 'time']/@value, 'dd/MMM/yyyy:HH:mm:ss XX')" />
                          </TimeCreated>
                        </EventTime>
                      </xsl:template>

                      <xsl:template match="node()"  mode="eventSource">
                        <!-- Set some variables to enable lookup functionality  -->
                        <xsl:variable name="formattedDate"
                            select="stroom:format-date(data[@name =  'time']/@value, 'dd/MMM/yyyy:HH:mm:ss XX')" />
                        <!--  For Version 2.0 of Apache  audit we  have the virtual  server,  so this  will
                        be our server -->
                        <xsl:variable name="vServer" select="data[@name  =  'vserver']/@value"  />
                        <xsl:variable name="vServerPort" select="data[@name =  'vserverport']/@value"  />
                        <EventSource>
                          <System>
                            <Name>
                              <xsl:value-of select="stroom:feed-attribute('System')"  />
                            </Name>
                            <Environment>
                              <xsl:value-of select="stroom:feed-attribute('Environment')"  />
                            </Environment>
                          </System>
                          <Generator>Apache  HTTPD</Generator>
                          <Device>
                            <HostName>
                              <xsl:value-of select="stroom:feed-attribute('MyHost')"  />
                            </HostName>
                            <IPAddress>
                              <xsl:value-of select="stroom:feed-attribute('MyIPAddress')"  />
                            </IPAddress>
                          </Device>
                          <Client>
                            <xsl:variable name="chost"
                                select="stroom:lookup('IP_TO_FQDN', data[@name = 'clientip']/@value,
                                                      $formattedDate, true())" />
                            <xsl:if  test="$chost">
                              <HostName>
                                <xsl:value-of  select="$chost" />
                              </HostName>
                            </xsl:if>
                            <IPAddress>
                              <xsl:value-of select="data[@name =  'clientip']/@value"  />
                            </IPAddress>
                            <xsl:if test="data[@name =  'clientport']/@value !='-'">
                              <Port>
                                <xsl:value-of select="data[@name =  'clientport']/@value"  />
                              </Port>
                            </xsl:if>
                            <xsl:variable name="cloc"
                                select="stroom:lookup('FQDN_TO_LOC', $chost,  $formattedDate, true())"  />
                            <xsl:if  test="$chost != '' and $cloc">
                              <xsl:copy-of select="$cloc"  />
                            </xsl:if>
                          </Client>
                          <Server>
                            <HostName>
                              <xsl:value-of  select="$vServer" />
                            </HostName>
                            <!--  See if we  can get  the  service  IPAddress -->
                            <xsl:variable name="sipaddr"
                                select="stroom:lookup('FQDN_TO_IP',$vServer, $formattedDate,  true())"  />
                            <xsl:if  test="$sipaddr">
                              <IPAddress>
                                <xsl:value-of  select="$sipaddr" />
                              </IPAddress>
                            </xsl:if>
                            <!--  Server Port Number   -->
                            <xsl:if test="$vServerPort !='-'">
                              <Port>
                                <xsl:value-of  select="$vServerPort" />
                              </Port>
                            </xsl:if>
                            <!--  See if we  can get the Server location -->
                            <xsl:variable name="sloc"
                                select="stroom:lookup('FQDN_TO_LOC', $vServer, $formattedDate, true())"  />
                            <xsl:if  test="$sloc">
                              <xsl:copy-of select="$sloc"  />
                            </xsl:if>
                          </Server>
                          <User>
                            <Id>
                              <xsl:value-of select="data[@name='user']/@value" />
                            </Id>
                          </User>
                        </EventSource>
                      </xsl:template>

                      <xsl:template match="node()"  mode="eventDetail">
                        <EventDetail>
                          <TypeId>SendToWebService</TypeId>
                          <Description>Send/Access data to Web Service</Description>
                          <Classification>
                            <Text>UNCLASSIFIED</Text>
                          </Classification>
                          <Send>
                            <Source>
                              <Device>
                                <IPAddress>
                                  <xsl:value-of select="data[@name = 'clientip']/@value"/>
                                </IPAddress>
                                <Port>
                                  <xsl:value-of select="data[@name = 'vserverport']/@value"/>
                                </Port>
                              </Device>
                            </Source>
                            <Destination>
                              <Device>
                                <HostName>
                                  <xsl:value-of select="data[@name = 'vserver']/@value"/>
                                </HostName>
                                <Port>
                                  <xsl:value-of select="data[@name = 'vserverport']/@value"/>
                                </Port>
                              </Device>
                            </Destination>
                            <Payload>
                              <Resource>
                                <URL>
                                  <xsl:value-of select="data[@name = 'url']/@value"/>
                                </URL>
                                <Referrer>
                                  <xsl:value-of select="data[@name = 'referer']/@value"/>
                                </Referrer>
                                <HTTPMethod>
                                  <xsl:value-of select="data[@name = 'url']/data[@name = 'httpMethod']/@value"/>
                                </HTTPMethod>
                                <HTTPVersion>
                                  <xsl:value-of select="data[@name = 'url']/data[@name = 'version']/@value"/>
                                </HTTPVersion>
                                <UserAgent>
                                  <xsl:value-of select="data[@name = 'userAgent']/@value"/>
                                </UserAgent>
                                <InboundSize>
                                  <xsl:value-of select="data[@name = 'bytesIn']/@value"/>
                                </InboundSize>
                                <OutboundSize>
                                  <xsl:value-of select="data[@name = 'bytesOut']/@value"/>
                                </OutboundSize>
                                <OutboundContentSize>
                                  <xsl:value-of select="data[@name = 'bytesOutContent']/@value"/>
                                </OutboundContentSize>
                                <RequestTime>
                                  <xsl:value-of select="data[@name = 'timeM']/@value"/>
                                </RequestTime>
                                <ConnectionStatus>
                                  <xsl:value-of select="data[@name = 'constatus']/@value"/>
                                </ConnectionStatus>
                                <InitialResponseCode>
                                  <xsl:value-of select="data[@name = 'responseB']/@value"/>
                                </InitialResponseCode>
                                <ResponseCode>
                                  <xsl:value-of select="data[@name = 'response']/@value"/>
                                </ResponseCode>
                                <Data Name="Protocol">
                                  <xsl:attribute
                                      select="data[@name = 'url']/data[@name = 'protocol']/@value"
                                      name="Value"/>
                                </Data>
                              </Resource>
                            </Payload>
                            <!-- Normally our translation at this point would contain an <Outcome>
                            attribute. Since all our sample data includes only successful outcomes we have
                            ommitted the <Outcome> attribute in the translation to minimise complexity-->
                          </Send>
                        </EventDetail>
                      </xsl:template>
                    </xsl:stylesheet>""");

            assertThat(result.hasParseFailure()).isFalse();

            // Document order, and FQDN_TO_LOC twice because there are two call sites. The parser reports
            // call sites, not a distinct set - de-duplication belongs to whatever consumes the findings,
            // and doc_dependency does it with a unique key.
            assertThat(result.resolvedValues(XsltReferenceKind.REF_MAP_READ))
                    .containsExactly("IP_TO_FQDN", "FQDN_TO_LOC", "FQDN_TO_IP", "FQDN_TO_LOC");

            // Every map name is a literal at the point of use, so nothing is left undetermined.
            assertThat(result.unresolved()).isEmpty();

            // The heart of it: four map reads and not one document edge. A lookup does not identify what
            // it looks up - that takes the pipeline's configured references as well - so a parser that
            // resolved these to Plan B stores or reference loaders would be inventing configuration.
            assertThat(result.documentTargets()).isEmpty();

            // stroom:format-date and stroom:feed-attribute name nothing, so the four lookups are the
            // whole of it.
            assertThat(result.references()).hasSize(4);
        }

        @Test
        @DisplayName("the reference feed that writes the three maps the Apache translation reads")
        void realWorldReferenceDataFeedWritingThreeMaps() {
            // The write side of the previous fixture, and the pair is the point: this loader produces
            // FQDN_TO_IP, IP_TO_FQDN and FQDN_TO_LOC, and the Apache translation looks up all three. Read
            // and write are recorded by the same parser from opposite ends, which is exactly the join the
            // Plan B migration and the pipeline editor's suggestion panel are built on.
            //
            // It also carries a trap the parser has to not fall into. The header comment lists all three
            // map names in prose - "FQDN_TO_IP - Fully Qualified Domain Name to IP Address" and so on - so
            // an implementation that scanned text would find each name twice and could not tell the
            // documentation from the output. Walking elements makes comments invisible, and the size
            // assertion below is what pins that down.
            final XsltReferences result = parse("""
                    <?xml version="1.1" encoding="UTF-8" ?>
                    <xsl:stylesheet xpath-default-namespace="records:2"
                                    xmlns="reference-data:2"
                                    xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                                    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                                    xmlns:stroom="stroom"
                                    xmlns:evt="event-logging:3"
                                    version="2.0">

                      <!--
                      GEOHOST REFERENCE FEED:

                      CHANGE HISTORY
                      v1.0.0 - 2020-02-09 John Doe

                      This is a reference feed for device Logical and Geographic data.

                      The feed provides for each device
                      * the device FQDN
                      * the device IP Address
                      * the device Country location (using ISO 3166-1 alpha-3 codes)
                      * the device Site location
                      * the device Building location
                      * the device Room location
                      *the device TimeZone location (both standard then daylight time zone offsets from UTC)

                      The reference maps are
                      FQDN_TO_IP - Fully Qualified Domain Name to IP Address
                      IP_TO_FQDN - IP Address to FQDN (HostName)
                      FQDN_TO_LOC - Fully Qualified Domain Name to Location element
                      -->

                      <xsl:template match="records">
                        <referenceData xmlns="reference-data:2"
                                       xsi:schemaLocation="reference-data:2 file://reference-data-v2.0.xsd"
                                       version="2.0.1">
                          <xsl:apply-templates/>
                        </referenceData>
                      </xsl:template>

                      <!-- MAIN TEMPLATE -->
                      <xsl:template match="record">
                        <!-- FQDN_TO_IP map -->
                        <reference>
                          <map>FQDN_TO_IP</map>
                          <key>
                            <xsl:value-of select="lower-case(data[@name='FQDN']/@value)" />
                          </key>
                          <value>
                            <IPAddress>
                              <xsl:value-of select="data[@name='IPAddress']/@value" />
                            </IPAddress>
                          </value>
                        </reference>

                        <!-- IP_TO_FQDN map -->
                        <reference>
                          <map>IP_TO_FQDN</map>
                          <key>
                            <xsl:value-of select="lower-case(data[@name='IPAddress']/@value)" />
                          </key>
                          <value>
                            <HostName>
                              <xsl:value-of select="data[@name='FQDN']/@value" />
                            </HostName>
                          </value>
                        </reference>

                        <!-- FQDN_TO_LOC map -->
                        <reference>
                          <map>FQDN_TO_LOC</map>
                          <key>
                            <xsl:value-of select="lower-case(data[@name='FQDN']/@value)" />
                          </key>
                          <value>
                          <!--
                          Note, when mapping to a XML node set, we make use of the Event namespace - i.e.
                          evt: defined on our stylesheet element. This is done, so that, when the node set
                          is returned, it is within the correct namespace.
                          -->
                            <evt:Location>
                              <evt:Country>
                              <xsl:value-of select="data[@name='Country']/@value" />
                              </evt:Country>
                              <evt:Site>
                              <xsl:value-of select="data[@name='Site']/@value" />
                              </evt:Site>
                              <evt:Building>
                              <xsl:value-of select="data[@name='Building']/@value" />
                              </evt:Building>
                              <evt:Room>
                              <xsl:value-of select="data[@name='Room']/@value" />
                              </evt:Room>
                              <evt:TimeZone>
                              <xsl:value-of select="data[@name='TimeZones']/@value" />
                              </evt:TimeZone>
                            </evt:Location>
                          </value>
                        </reference>
                      </xsl:template>
                    </xsl:stylesheet>""");

            assertThat(result.hasParseFailure()).isFalse();

            // The three maps this feed writes, in document order.
            assertThat(result.resolvedValues(XsltReferenceKind.REF_MAP_WRITE))
                    .containsExactly("FQDN_TO_IP", "IP_TO_FQDN", "FQDN_TO_LOC");

            // Three findings, not six: the same three names appear in the header comment, and comments are
            // not output. This is the assertion that would fail if the parser ever regressed to scanning
            // text rather than walking the tree.
            assertThat(result.references()).hasSize(3);

            // Written as literals in the source, so nothing had to be worked out.
            assertThat(result.references())
                    .extracting(XsltReference::certainty)
                    .containsOnly(XsltReferenceCertainty.STATIC);
            assertThat(result.unresolved()).isEmpty();

            // As with a read, a map write names no document: which store receives the data is settled by
            // the pipeline, not by the translation. That is the defect Part 2 of the specification exists
            // to fix, and until it is fixed there is no edge for the parser to record.
            assertThat(result.documentTargets()).isEmpty();
        }
    }

    // ------------------------------------------------------------------------
    // Robustness - a parse failure must never cost a save
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("robustness")
    class Robustness {

        @Test
        @DisplayName("malformed XML yields a parse failure rather than an exception")
        void malformedXml() {
            final XsltReferences result = parse("<xsl:stylesheet><unclosed>");

            assertThat(result.hasParseFailure()).isTrue();
            assertThat(result.references()).isEmpty();
        }

        @Test
        @DisplayName("a half-edited stylesheet still parses without throwing")
        void midEdit() {
            assertThatCode(() -> parse("<xsl:template match=\"/\"><xsl:value-of select=\"stroom:look"))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a null body is an empty result, not a failure")
        void nullBody() {
            final XsltReferences result = newParser().parse(null);

            assertThat(result.hasParseFailure()).isFalse();
            assertThat(result.references()).isEmpty();
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "   ", "\n\t "})
        @DisplayName("a blank body is an empty result, not a failure")
        void blankBody(final String body) {
            final XsltReferences result = newParser().parse(body);

            assertThat(result.hasParseFailure()).isFalse();
            assertThat(result.references()).isEmpty();
        }

        @Test
        @DisplayName("the same input always yields the same findings in the same order")
        void deterministic() {
            lookup.with(DictionaryDoc.TYPE, "GeoIP").with(XsltDoc.TYPE, "Common");
            final String xslt = """
                    <xsl:stylesheet version="2.0"
                                    xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                                    xmlns:stroom="stroom">
                      <xsl:import href="Common"/>
                      <xsl:template match="/">
                        <xsl:value-of select="stroom:dictionary('GeoIP')"/>
                        <xsl:value-of select="stroom:lookup('map_a', @a)"/>
                        <xsl:value-of select="stroom:lookup('map_b', @b)"/>
                        <reference xmlns="reference-data:2"><map>written</map></reference>
                      </xsl:template>
                    </xsl:stylesheet>""";

            final List<XsltReference> first = parse(xslt).references();
            final List<XsltReference> second = parse(xslt).references();

            assertThat(second).isEqualTo(first);
            assertThat(first)
                    .extracting(XsltReference::rawValue)
                    .containsExactly("Common", "GeoIP", "map_a", "map_b", "written");
        }

        @Test
        @DisplayName("findings carry the line they were found on")
        void reportsLineNumbers() {
            final XsltReferences result = parse("""
                    <xsl:stylesheet version="2.0"
                                    xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                                    xmlns:stroom="stroom">
                      <xsl:template match="/">
                        <xsl:value-of select="stroom:lookup('geo_ip', @ip)"/>
                      </xsl:template>
                    </xsl:stylesheet>""");

            assertThat(result.references()).singleElement()
                    .extracting(XsltReference::lineNumber)
                    .isEqualTo(5);
        }

        @Test
        @DisplayName("exceeding the time budget returns what was found, marked incomplete")
        void timeoutIsPartialNotFatal() {
            // A budget no real parse can meet, to prove the deadline is honoured rather than to measure
            // anything. Findings already collected are still true; the message marks them partial.
            final XsltReferenceParser parser =
                    new XsltReferenceParserImpl(lookup, Duration.ofNanos(1), 10);

            final XsltReferences result = parser.parse(template("""
                    <xsl:value-of select="stroom:lookup('geo_ip', @ip)"/>"""));

            assertThat(result.hasParseFailure()).isTrue();
            assertThat(result.parseFailure()).contains("exceeded");
        }
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    private XsltReferences parse(final String xslt) {
        return newParser().parse(xslt);
    }

    private XsltReferenceParser newParser() {
        return new XsltReferenceParserImpl(
                lookup, XsltReferenceParserImpl.DEFAULT_TIMEOUT, XsltReferenceParserImpl.DEFAULT_MAX_DEPTH);
    }

    /**
     * Wrap a fragment in a stylesheet with a template, for the many cases where the surrounding
     * boilerplate is not what is being tested.
     */
    private static String template(final String body) {
        return """
                <xsl:stylesheet version="2.0"
                                xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                                xmlns:stroom="stroom">
                  <xsl:template match="/">
                %s
                  </xsl:template>
                </xsl:stylesheet>""".formatted(body.indent(4).stripTrailing());
    }
}
