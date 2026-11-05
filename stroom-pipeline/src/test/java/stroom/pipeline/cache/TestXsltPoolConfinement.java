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

package stroom.pipeline.cache;

import stroom.cache.impl.CacheManagerImpl;
import stroom.pipeline.filter.XsltConfig;
import stroom.pipeline.shared.XsltDoc;
import stroom.pipeline.xsltfunctions.StroomXsltFunctionLibrary;
import stroom.security.mock.MockSecurityContext;
import stroom.util.xml.saxon.SaxonAccessGuard;
import stroom.util.xml.saxon.SaxonConfinement;
import stroom.util.xml.saxon.SaxonRoute;

import net.sf.saxon.s9api.Processor;
import net.sf.saxon.s9api.SaxonApiException;
import net.sf.saxon.s9api.XsltTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.xml.transform.stream.StreamSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Shows that the XSLT pool, which compiles every pipeline stylesheet, applies the egress hooks, so the finding in the
/// design note, that Saxon's standard routes bypass Stroom's own function checks, is closed where stylesheets are
/// actually compiled and not only in a standalone probe.
class TestXsltPoolConfinement {

    private final List<SaxonRoute> asked = new ArrayList<>();
    private XsltPoolImpl pool;

    @BeforeEach
    void setUp() {
        final SaxonAccessGuard guard = (route, uri) -> {
            asked.add(route);
            return route == SaxonRoute.DOCUMENT;
        };
        SaxonConfinement.install(guard);
        pool = new XsltPoolImpl(
                new CacheManagerImpl(),
                XsltConfig::new,
                Mockito.mock(DocumentPermissionCache.class),
                new MockSecurityContext(),
                (href, base) -> null,
                () -> Mockito.mock(StroomXsltFunctionLibrary.class));
    }

    @AfterEach
    void tearDown() {
        SaxonConfinement.install(null);
    }

    private String run(final String body) throws SaxonApiException {
        final String stylesheet = """
                <xsl:stylesheet version="2.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
                  <xsl:template match="/"><out>%s</out></xsl:template>
                </xsl:stylesheet>
                """.formatted(body);
        final XsltDoc doc = XsltDoc.builder().uuid("test").name("test").data(stylesheet).build();
        final XsltTransformer transformer = pool.createValue(doc).getXsltExecutable().load();
        final StringWriter out = new StringWriter();
        transformer.setSource(new StreamSource(new StringReader("<in/>")));
        transformer.setDestination(new Processor(false).newSerializer(out));
        transformer.transform();
        return out.toString();
    }

    @Test
    void testUnparsedTextIsDeniedInThePool(@TempDir final Path dir) throws IOException {
        final Path file = Files.writeString(dir.resolve("secret.txt"), "secret");
        assertThatThrownBy(() -> run("<xsl:value-of select=\"unparsed-text('" + file.toUri() + "')\"/>"))
                .isInstanceOf(SaxonApiException.class);
        assertThat(asked).contains(SaxonRoute.UNPARSED_TEXT);
    }

    @Test
    void testCollectionIsDeniedInThePool(@TempDir final Path dir) {
        assertThatThrownBy(() -> run(
                "<xsl:value-of select=\"count(collection('" + dir.toUri() + "?select=*.xml'))\"/>"))
                .isInstanceOf(SaxonApiException.class);
        assertThat(asked).contains(SaxonRoute.COLLECTION);
    }

    @Test
    void testDocumentIsAskedAndFollowsTheGuardInThePool(@TempDir final Path dir)
            throws IOException, SaxonApiException {
        final Path file = Files.writeString(dir.resolve("d.xml"), "<d>hello</d>");
        assertThat(run("<xsl:value-of select=\"document('" + file.toUri() + "')/d\"/>")).contains("hello");
        assertThat(asked).containsExactly(SaxonRoute.DOCUMENT);
    }
}
