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

package stroom.graphdb.impl;

import stroom.graphdb.impl.pipeline.GraphMutationSchema;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the event-logging translation {@code docs/graphdb/04-event-logging-xslt.md} teaches: the stylesheet
 * in {@code docs/graphdb/examples/}, its sample corpus, and the {@code graph-mutation:1} document it is
 * documented as producing.
 *
 * <p><b>Why this exists.</b> That translation is the only worked example of getting real
 * {@code event-logging:3} data into a graph, and it is what anyone starting out copies. It was carried entirely
 * in a documentation file and a directory of examples that nothing ran, which is the state in which a
 * stylesheet rots invisibly - it stays plausible while it stops working, and the first person to find out is a
 * user whose feed silently produced nothing. The corpus and expected output were already committed; only the
 * assertion was missing.</p>
 *
 * <p>Three things are checked, and the distinction between the first two matters: the transform reproduces the
 * <b>documented output</b> (so the example still says what it claims), and its output satisfies the
 * <b>shipped XSD</b> (so what it produces would survive a {@code SchemaFilter} in a real pipeline). Either
 * could break without the other. The third checks that the snippets <em>printed in the prose</em> are still
 * lines of the stylesheet, which is the drift a reader would meet first.</p>
 *
 * <h2>Why the comparison is structural</h2>
 *
 * <p>It compares parsed documents, not text, because a textual diff fails on differences that are not
 * differences. Saxon emits the root element's two namespace declarations in the opposite order to the committed
 * file - the same document, serialised by a different writer. Namespace <em>declarations</em> are therefore
 * excluded from the comparison while namespace <em>URIs</em> are compared on every element and attribute, which
 * is the distinction that matters: a node moving out of {@code graph-mutation:1} is a real defect, and the order
 * two {@code xmlns} attributes were written in is not. Do not "tighten" this back into a string comparison; it
 * will pass until the next Saxon upgrade.</p>
 */
class TestEventLoggingXslt {

    /** The documented translation and its corpus, all committed under {@code docs/graphdb/examples/}. */
    private static final String EXAMPLES_DIRECTORY = "docs/graphdb/examples";
    private static final String STYLESHEET = "event-logging-to-graph.xslt";
    private static final String INPUT = "sample-events.xml";
    private static final String EXPECTED_OUTPUT = "expected-output.xml";

    /** The document whose prose prints excerpts of the stylesheet. */
    private static final String DOCUMENT = "04-event-logging-xslt.md";

    /** A fenced {@code xslt} block's body. */
    private static final Pattern XSLT_BLOCK = Pattern.compile("```xslt\\n(.*?)```", Pattern.DOTALL);

    /** An XML comment, possibly spanning lines - dropped before comparing, on both sides. */
    private static final Pattern COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);

    /**
     * A floor on the snippets found, for the reason {@code TestDocumentationQueries} documents: a check that
     * walks a file and verifies what it finds passes perfectly when a changed fence label means it finds
     * nothing.
     */
    private static final int MINIMUM_SNIPPETS = 6;

    @Test
    void documentedStylesheetProducesDocumentedOutput() throws Exception {
        final Document actual = parse(transform().getBytes(StandardCharsets.UTF_8));
        final Document expected = parse(DocumentationSources.read(example(EXPECTED_OUTPUT))
                .getBytes(StandardCharsets.UTF_8));

        final List<String> differences = new ArrayList<>();
        compare(actual.getDocumentElement(), expected.getDocumentElement(), "/graph", differences);

        assertThat(differences)
                .describedAs("the documented stylesheet's output against the documented expected output. "
                            + "Either the translation in " + DOCUMENT + " has changed behaviour, or "
                            + EXPECTED_OUTPUT + " is stale - decide which before editing either")
                .isEmpty();
    }

    /**
     * The transform's <b>own</b> output is validated, not the committed expected output, so this tracks what the
     * stylesheet produces today rather than what someone once recorded. Against the <b>shipped</b> schema, for
     * the reason {@code TestGraphMutationSchema} gives: an example that validates only against a test copy of
     * the vocabulary is no evidence about the artefact an administrator registers.
     */
    @Test
    void transformOutputValidatesAgainstTheShippedSchema() throws Exception {
        final SchemaFactory schemaFactory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        final Schema schema = schemaFactory.newSchema(GraphMutationSchema.url());

        schema.newValidator().validate(new StreamSource(
                new ByteArrayInputStream(transform().getBytes(StandardCharsets.UTF_8))));
    }

    /**
     * Every line of XSLT printed in the prose must still be a line of the stylesheet, in the order shown.
     *
     * <p>This is the drift a reader meets first: the document explains a template, the stylesheet has since been
     * corrected, and the explanation now teaches something that is not there.</p>
     *
     * <p><b>An ordered subsequence, not a substring</b>, because the document abbreviates in two ways that are
     * both legitimate and both defeat a substring check. It <b>elides</b> with {@code …}
     * ({@code <xsl:template match="…" mode="edges"> … </xsl:template>}), and it <b>omits intervening lines</b> -
     * one block prints a {@code for-each-group} without the {@code xsl:sort} that follows it in the file, because
     * the sort is not what that passage is about. Subsequence matching tolerates exactly those two and still
     * fails on what matters: a documented line that no longer exists, or one whose order has changed.</p>
     *
     * <p>Comments are dropped from both sides, and whitespace collapsed per line. A documentation excerpt is
     * legitimately re-indented to fit its prose, and re-worded in a comment, without the code changing - and
     * the stylesheet wraps the one comment these blocks share across two lines where the document keeps it on
     * one.</p>
     */
    @Test
    void everyStylesheetSnippetInTheDocumentationIsInTheStylesheet() {
        final List<String> stylesheet = significantLines(DocumentationSources.read(example(STYLESHEET)));
        final List<String> missing = new ArrayList<>();
        int checked = 0;

        final Matcher block = XSLT_BLOCK.matcher(
                DocumentationSources.read(DocumentationSources.documentationFile(DOCUMENT)));
        while (block.find()) {
            checked++;
            int position = 0;
            for (final String line : significantLines(block.group(1))) {
                final int found = stylesheet.subList(position, stylesheet.size()).indexOf(line);
                if (found < 0) {
                    missing.add(position == 0
                            ? line
                            : line + "   (or it appears before, not after, '" + stylesheet.get(position - 1)
                              + "')");
                    break;
                }
                position += found + 1;
            }
        }

        assertThat(missing)
                .describedAs(DOCUMENT + " prints XSLT that is no longer in " + STYLESHEET
                            + ", or no longer in that order - the prose and the example have drifted apart")
                .isEmpty();
        assertThat(checked)
                .describedAs("XSLT snippets found - a floor, so a changed fence label cannot make this a no-op")
                .isGreaterThanOrEqualTo(MINIMUM_SNIPPETS);
    }

    // ------------------------------------------------------------------------------------------------------

    /**
     * Runs the documented stylesheet over the documented corpus.
     *
     * <p>Saxon is on this module's classpath already (the stylesheet needs it - it is XSLT 2.0, using
     * {@code xsl:for-each-group} and {@code xsl:function}), and is picked up as the {@link TransformerFactory}
     * implementation. The stylesheet deliberately uses no {@code stroom:} extension functions, so no pipeline
     * context is needed to run it.</p>
     *
     * @return the serialised {@code graph-mutation:1} document; never null.
     */
    private static String transform() throws TransformerException {
        final Transformer transformer = TransformerFactory.newInstance()
                .newTransformer(new StreamSource(example(STYLESHEET).toFile()));
        final StringWriter output = new StringWriter();
        transformer.transform(new StreamSource(example(INPUT).toFile()), new StreamResult(output));
        return output.toString();
    }

    private static Path example(final String name) {
        return DocumentationSources.repositoryRoot().resolve(EXAMPLES_DIRECTORY).resolve(name);
    }

    /**
     * Parses namespace-aware, with ignorable whitespace dropped, so indentation is not compared as content.
     */
    private static Document parse(final byte[] xml) throws ParserConfigurationException, SAXException, IOException {
        final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setIgnoringElementContentWhitespace(true);
        final Document document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
        document.normalizeDocument();
        return document;
    }

    /**
     * Compares two elements and their descendants, appending one entry to {@code differences} per disagreement.
     *
     * <p>Collects rather than fails fast so a broken translation reports what changed across the whole document
     * instead of only the first element - the difference between a diagnosis and a hint.</p>
     *
     * @param actual        the transform's element; never null.
     * @param expected      the documented element; never null.
     * @param path          an XPath-ish trail to {@code actual}, for the failure message; never null.
     * @param differences   accumulator; never null.
     */
    private static void compare(final Element actual, final Element expected, final String path,
                                final List<String> differences) {
        if (!nameOf(actual).equals(nameOf(expected))) {
            differences.add(path + ": is <" + nameOf(actual) + "> but should be <" + nameOf(expected) + ">");
            return;
        }

        final List<String> actualAttributes = attributesOf(actual);
        final List<String> expectedAttributes = attributesOf(expected);
        if (!actualAttributes.equals(expectedAttributes)) {
            differences.add(path + ": attributes are " + actualAttributes + " but should be "
                            + expectedAttributes);
        }

        final List<Element> actualChildren = childElementsOf(actual);
        final List<Element> expectedChildren = childElementsOf(expected);
        if (actualChildren.size() != expectedChildren.size()) {
            differences.add(path + ": has " + actualChildren.size() + " child elements but should have "
                            + expectedChildren.size());
        }
        if (actualChildren.isEmpty() && expectedChildren.isEmpty()) {
            final String actualText = textOf(actual);
            final String expectedText = textOf(expected);
            if (!actualText.equals(expectedText)) {
                differences.add(path + ": is '" + actualText + "' but should be '" + expectedText + "'");
            }
            return;
        }

        for (int i = 0; i < Math.min(actualChildren.size(), expectedChildren.size()); i++) {
            final Element child = actualChildren.get(i);
            compare(child, expectedChildren.get(i), path + "/" + nameOf(child) + "[" + (i + 1) + "]",
                    differences);
        }
    }

    /** An element's qualified identity: namespace URI plus local name, never the prefix. */
    private static String nameOf(final Element element) {
        final String namespace = element.getNamespaceURI();
        return namespace == null ? element.getLocalName() : "{" + namespace + "}" + element.getLocalName();
    }

    /**
     * An element's attributes as sorted {@code name=value} entries.
     *
     * <p><b>Namespace declarations are excluded</b> - see this class's Javadoc. Sorted, because attribute order
     * is not part of an XML document's meaning either.</p>
     */
    private static List<String> attributesOf(final Element element) {
        final NamedNodeMap attributes = element.getAttributes();
        final List<String> entries = new ArrayList<>(attributes.getLength());
        for (int i = 0; i < attributes.getLength(); i++) {
            final Attr attribute = (Attr) attributes.item(i);
            if (!XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(attribute.getNamespaceURI())) {
                final String namespace = attribute.getNamespaceURI();
                entries.add((namespace == null ? "" : "{" + namespace + "}")
                            + attribute.getLocalName() + "=" + attribute.getValue());
            }
        }
        return entries.stream().sorted().toList();
    }

    private static List<Element> childElementsOf(final Element element) {
        final NodeList children = element.getChildNodes();
        final List<Element> elements = new ArrayList<>();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i).getNodeType() == Node.ELEMENT_NODE) {
                elements.add((Element) children.item(i));
            }
        }
        return elements;
    }

    private static String textOf(final Element element) {
        return collapseWhitespace(element.getTextContent());
    }

    /**
     * XSLT reduced to the lines worth comparing: comments removed, {@code …} treated as a line break (it marks
     * omitted content, so what sits either side of it are separate lines), whitespace collapsed within each
     * line, and blank lines dropped.
     *
     * @param xslt never null.
     * @return never null; may be empty for a snippet that is all comment.
     */
    private static List<String> significantLines(final String xslt) {
        return COMMENT.matcher(xslt).replaceAll("")
                .replace("…", "\n")
                .lines()
                .map(TestEventLoggingXslt::collapseWhitespace)
                .filter(line -> !line.isEmpty())
                .toList();
    }

    private static String collapseWhitespace(final String text) {
        return text.replaceAll("\\s+", " ").strip();
    }
}
