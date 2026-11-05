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

package stroom.util.xml.saxon;

import net.sf.saxon.TransformerFactoryImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestSaxonConfinement {

    private static final String INPUT = "<in/>";

    private static String stylesheet(final String body) {
        return """
                <xsl:stylesheet version="2.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                                xmlns:xs="http://www.w3.org/2001/XMLSchema">
                  <xsl:template match="/">
                    <out>%s</out>
                  </xsl:template>
                </xsl:stylesheet>
                """.formatted(body);
    }

    private static String run(final String body,
                              final SaxonAccessGuard guard,
                              final boolean strict) throws TransformerException {
        final TransformerFactoryImpl factory = new TransformerFactoryImpl();
        SaxonConfinement.confine(factory.getConfiguration(), guard, strict);
        final Transformer transformer = factory.newTransformer(new StreamSource(new StringReader(stylesheet(body))));
        final StringWriter out = new StringWriter();
        transformer.transform(new StreamSource(new StringReader(INPUT)), new StreamResult(out));
        return out.toString();
    }

    private static final class Recording implements SaxonAccessGuard {

        private final List<SaxonRoute> routes = new ArrayList<>();
        private final boolean permitDocument;

        private Recording(final boolean permitDocument) {
            this.permitDocument = permitDocument;
        }

        @Override
        public boolean permit(final SaxonRoute route, final String uri) {
            routes.add(route);
            return route == SaxonRoute.DOCUMENT && permitDocument;
        }
    }

    @Test
    void testUnparsedTextIsRefusedAndAsked(@TempDir final Path dir) throws IOException {
        final Path file = Files.writeString(dir.resolve("a.txt"), "secret");
        final Recording guard = new Recording(true);
        assertThatThrownBy(() -> run("<xsl:value-of select=\"unparsed-text('" + file.toUri() + "')\"/>", guard, false))
                .isInstanceOf(TransformerException.class);
        assertThat(guard.routes).contains(SaxonRoute.UNPARSED_TEXT);
    }

    @Test
    void testRelatedUnparsedTextFunctionsAreRefusedByTheSameHook(@TempDir final Path dir) throws IOException {
        final Path file = Files.writeString(dir.resolve("a.json"), "{\"a\":1}");
        for (final String call : List.of(
                "unparsed-text-lines('" + file.toUri() + "')",
                "json-doc('" + file.toUri() + "')")) {
            final Recording guard = new Recording(true);
            assertThatThrownBy(() -> run("<xsl:value-of select=\"" + call + "\"/>", guard, false))
                    .isInstanceOf(TransformerException.class);
            assertThat(guard.routes).contains(SaxonRoute.UNPARSED_TEXT);
        }
    }

    @Test
    void testUnparsedTextAvailableReturnsFalse(@TempDir final Path dir)
            throws IOException, TransformerException {
        final Path file = Files.writeString(dir.resolve("a.txt"), "secret");
        final String output = run(
                "<xsl:value-of select=\"unparsed-text-available('" + file.toUri() + "')\"/>",
                new Recording(true), false);
        assertThat(output).contains("false");
    }

    @Test
    void testCollectionIsRefused(@TempDir final Path dir) {
        final Recording guard = new Recording(true);
        assertThatThrownBy(() -> run(
                "<xsl:value-of select=\"count(collection('" + dir.toUri() + "?select=*.xml'))\"/>", guard, false))
                .isInstanceOf(TransformerException.class);
        assertThat(guard.routes).contains(SaxonRoute.COLLECTION);
    }

    // Switching off reflexive external functions, which must stay off, also makes Saxon reject xsl:result-document
    // when the stylesheet is compiled. The attempt therefore fails with a compile error in the pipeline's error
    // stream and never reaches the output hook, which remains as a second line of defence.
    @Test
    void testResultDocumentFailsToCompileAndWritesNothing(@TempDir final Path dir) {
        final Recording guard = new Recording(true);
        assertThatThrownBy(() -> run(
                "<xsl:result-document href=\"" + dir.resolve("o.xml").toUri() + "\"><x/></xsl:result-document>",
                guard, false))
                .isInstanceOf(TransformerException.class);
        assertThat(dir.resolve("o.xml")).doesNotExist();
    }

    @Test
    void testDocumentIsAskedAndPermittedWhenTheGuardAgrees(@TempDir final Path dir)
            throws IOException, TransformerException {
        final Path file = Files.writeString(dir.resolve("d.xml"), "<d>hello</d>");
        final Recording guard = new Recording(true);
        final String output = run("<xsl:value-of select=\"document('" + file.toUri() + "')/d\"/>", guard, false);
        assertThat(output).contains("hello");
        assertThat(guard.routes).containsExactly(SaxonRoute.DOCUMENT);
    }

    @Test
    void testDocumentRefusedRecoversByDefaultAndFailsWhenStrict(@TempDir final Path dir)
            throws IOException, TransformerException {
        final Path file = Files.writeString(dir.resolve("d.xml"), "<d>hello</d>");
        final String call = "<xsl:value-of select=\"document('" + file.toUri() + "')/d\"/>";
        assertThat(run(call, new Recording(false), false)).doesNotContain("hello");
        assertThatThrownBy(() -> run(call, new Recording(false), true)).isInstanceOf(TransformerException.class);
    }

    @Test
    void testDocAvailableOfARefusedDocumentIsFalse(@TempDir final Path dir)
            throws IOException, TransformerException {
        final Path file = Files.writeString(dir.resolve("d.xml"), "<d/>");
        final Recording guard = new Recording(false);
        assertThat(run("<xsl:value-of select=\"doc-available('" + file.toUri() + "')\"/>", guard, false))
                .contains("false");
        assertThat(guard.routes).contains(SaxonRoute.DOCUMENT);
    }

    @Test
    void testRefuseAllRefusesEvenDocument(@TempDir final Path dir) throws IOException {
        final Path file = Files.writeString(dir.resolve("d.xml"), "<d>hello</d>");
        assertThatThrownBy(() -> run(
                "<xsl:value-of select=\"document('" + file.toUri() + "')/d\"/>",
                SaxonConfinement.refuseAll(), true))
                .isInstanceOf(TransformerException.class);
    }

    @Test
    void testDefaultGuardAllowsDocumentButNotTheDeniedRoutes(@TempDir final Path dir) throws IOException {
        final Path file = Files.writeString(dir.resolve("d.xml"), "<d>hello</d>");
        final TransformerFactoryImpl factory = new TransformerFactoryImpl();
        SaxonConfinement.install(null);
        SaxonConfinement.confine(factory.getConfiguration());
        final StringWriter out = new StringWriter();
        try {
            factory.newTransformer(new StreamSource(new StringReader(stylesheet(
                            "<xsl:value-of select=\"document('" + file.toUri() + "')/d\"/>"))))
                    .transform(new StreamSource(new StringReader(INPUT)), new StreamResult(out));
        } catch (final TransformerException e) {
            throw new AssertionError(e);
        }
        assertThat(out.toString()).contains("hello");
        assertThatThrownBy(() -> {
            final TransformerFactoryImpl other = new TransformerFactoryImpl();
            SaxonConfinement.confine(other.getConfiguration());
            other.newTransformer(new StreamSource(new StringReader(stylesheet(
                            "<xsl:value-of select=\"unparsed-text('" + file.toUri() + "')\"/>"))))
                    .transform(new StreamSource(new StringReader(INPUT)), new StreamResult(new StringWriter()));
        }).isInstanceOf(TransformerException.class);
    }
}
