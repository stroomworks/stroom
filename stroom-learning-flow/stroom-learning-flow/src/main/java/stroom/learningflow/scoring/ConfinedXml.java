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

package stroom.learningflow.scoring;

import stroom.util.xml.SAXParserFactoryFactory;
import stroom.util.xml.SaxonPolicy;
import stroom.util.xml.SaxonSecurity;

import org.w3c.dom.Document;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

import java.io.IOException;
import java.io.StringReader;
import java.io.Writer;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.sax.SAXSource;
import javax.xml.transform.sax.TransformerHandler;
import javax.xml.transform.stream.StreamResult;

/// Hardened XML parsing and serialising for XML that a model wrote or a model's stylesheet produced. A
/// DOCTYPE is refused outright, so no entity (external or expanding) can be declared, and nothing in the
/// text can make the parser fetch anything. Saxon does not apply its own parse options to a
/// `StreamSource`, which is why a configured reader is supplied to it in a [SAXSource] instead.
public final class ConfinedXml {

    private ConfinedXml() {
    }

    public static XMLReader reader() {
        try {
            final XMLReader reader = SAXParserFactoryFactory.newInstance().newSAXParser().getXMLReader();
            reader.setFeature(SAXParserFactoryFactory.FEATURE_DISALLOW_DOCTYPE, true);
            reader.setFeature(SAXParserFactoryFactory.FEATURE_EXTERNAL_GENERAL_ENTITIES, false);
            reader.setFeature(SAXParserFactoryFactory.FEATURE_EXTERNAL_PARAMETER_ENTITIES, false);
            reader.setFeature(SAXParserFactoryFactory.FEATURE_LOAD_EXTERNAL_DTD, false);
            return reader;
        } catch (final SAXException | ParserConfigurationException e) {
            throw new IllegalStateException("Unable to set up a confined XML parser", e);
        }
    }

    public static SAXSource source(final String xml) {
        return new SAXSource(reader(), new InputSource(new StringReader(xml)));
    }

    /// Parses an XSD or other XML held as text into a namespace-aware DOM, with secure processing on and
    /// any DOCTYPE refused, so that nothing in the text can make the parse fetch anything.
    ///
    /// @param xml The text.
    /// @return The document.
    /// @throws ParserConfigurationException Where the parser cannot be configured this way.
    /// @throws SAXException                 Where the text does not parse.
    /// @throws IOException                  Where the text cannot be read.
    public static Document document(final String xml)
            throws ParserConfigurationException, SAXException, IOException {
        final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature(SAXParserFactoryFactory.FEATURE_DISALLOW_DOCTYPE, true);
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
    }

    /// Creates a Saxon serialiser that writes the SAX events it is given to the writer as XML. It is locked
    /// down in the same way as every other use of Saxon in the module.
    ///
    /// @param writer             Where the XML goes.
    /// @param indent             Whether the XML is indented.
    /// @param omitXmlDeclaration Whether the XML declaration is left out.
    /// @return The serialiser, with its result set.
    public static TransformerHandler serialiser(final Writer writer,
                                                final boolean indent,
                                                final boolean omitXmlDeclaration) {
        final TransformerHandler handler = SaxonSecurity.newTransformerFactory(SaxonPolicy.LOCKED)
                .newTransformerHandler();
        handler.getTransformer().setOutputProperty(OutputKeys.INDENT, yesNo(indent));
        handler.getTransformer().setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, yesNo(omitXmlDeclaration));
        handler.setResult(new StreamResult(writer));
        return handler;
    }

    private static String yesNo(final boolean value) {
        return value
                ? "yes"
                : "no";
    }
}
