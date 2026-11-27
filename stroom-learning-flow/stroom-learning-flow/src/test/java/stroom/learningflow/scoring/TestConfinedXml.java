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

package stroom.learningflow.scoring;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.AttributesImpl;

import java.io.StringWriter;
import javax.xml.transform.sax.TransformerHandler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestConfinedXml {

    @Test
    void aDocumentIsReadNamespaceAware() throws Exception {
        final Document document = ConfinedXml.document(
                "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\" version=\"1.2\"/>");

        assertThat(document.getDocumentElement().getLocalName()).isEqualTo("schema");
        assertThat(document.getDocumentElement().getNamespaceURI()).isEqualTo("http://www.w3.org/2001/XMLSchema");
        assertThat(document.getDocumentElement().getAttribute("version")).isEqualTo("1.2");
    }

    @Test
    void aDocumentWithADoctypeIsRefused() {
        assertThatThrownBy(() -> ConfinedXml.document(
                "<!DOCTYPE a [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><a>&e;</a>"))
                .isInstanceOf(SAXException.class);
    }

    @Test
    void textThatIsNotXmlIsRefused() {
        assertThatThrownBy(() -> ConfinedXml.document("not xml"))
                .isInstanceOf(SAXException.class);
    }

    @Test
    void aSerialiserIndentsAndDeclaresWhenAsked() throws Exception {
        final String written = serialise(true, false);

        assertThat(written).startsWith("<?xml");
        assertThat(written).containsPattern("<a>\\R\\s+<b/>\\R</a>");
    }

    @Test
    void aSerialiserWritesBareWhenAsked() throws Exception {
        final String written = serialise(false, true);

        assertThat(written).isEqualTo("<a><b/></a>");
    }

    private static String serialise(final boolean indent, final boolean omitXmlDeclaration) throws Exception {
        final StringWriter writer = new StringWriter();
        final TransformerHandler handler = ConfinedXml.serialiser(writer, indent, omitXmlDeclaration);
        handler.startDocument();
        handler.startElement("", "a", "a", new AttributesImpl());
        handler.startElement("", "b", "b", new AttributesImpl());
        handler.endElement("", "b", "b");
        handler.endElement("", "a", "a");
        handler.endDocument();
        return writer.toString();
    }
}
