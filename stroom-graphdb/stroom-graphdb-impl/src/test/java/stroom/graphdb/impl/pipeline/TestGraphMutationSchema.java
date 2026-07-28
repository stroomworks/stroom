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

package stroom.graphdb.impl.pipeline;

import org.junit.jupiter.api.Test;
import org.xml.sax.SAXException;

import java.io.StringReader;
import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task P2.1: proves the {@code graph-mutation:1} XSD accepts the frozen v1 example (design doc &sect;5.2 /
 * implementation plan Task P2.1) and rejects structurally-invalid documents - a required attribute missing from
 * {@code edge}, and a {@code validFrom} missing from {@code node}. Purely schema-level: no {@link
 * stroom.graphdb.impl.pipeline.GraphFilter} involved (Task P2.2 exercises the SAX-parsing side against the same
 * vocabulary).
 */
class TestGraphMutationSchema {

    private static final String VALID_XML = """
            <graph xmlns="graph-mutation:1" version="1.0">
                <node id="d-42" validFrom="2026-01-01T00:00:00.000Z">
                    <label>Device</label>
                    <property name="serial">ABC123</property>
                </node>
                <edge type="CONNECTED_TO" validFrom="2026-01-01T00:00:00.000Z">
                    <src>d-42</src>
                    <dst>account-a</dst>
                    <property name="channel">wifi</property>
                </edge>
                <node-delete id="d-42" validFrom="2026-02-01T00:00:00.000Z"/>
                <edge-delete type="CONNECTED_TO" validFrom="2026-02-01T00:00:00.000Z">
                    <src>d-42</src>
                    <dst>account-a</dst>
                </edge-delete>
            </graph>
            """;

    @Test
    void validExample_validatesCleanly() throws Exception {
        validator().validate(new StreamSource(new StringReader(VALID_XML)));
    }

    @Test
    void edge_missingRequiredTypeAttribute_isRejected() {
        final String xml = """
                <graph xmlns="graph-mutation:1" version="1.0">
                    <edge validFrom="2026-01-01T00:00:00.000Z">
                        <src>d-42</src>
                        <dst>account-a</dst>
                    </edge>
                </graph>
                """;
        assertThatThrownBy(() -> validator().validate(new StreamSource(new StringReader(xml))))
                .isInstanceOf(SAXException.class);
    }

    @Test
    void edge_missingSrcOrDst_isRejected() {
        final String xml = """
                <graph xmlns="graph-mutation:1" version="1.0">
                    <edge type="CONNECTED_TO" validFrom="2026-01-01T00:00:00.000Z">
                        <dst>account-a</dst>
                    </edge>
                </graph>
                """;
        assertThatThrownBy(() -> validator().validate(new StreamSource(new StringReader(xml))))
                .isInstanceOf(SAXException.class);
    }

    @Test
    void node_missingValidFrom_isRejected() {
        final String xml = """
                <graph xmlns="graph-mutation:1" version="1.0">
                    <node id="d-42">
                        <label>Device</label>
                    </node>
                </graph>
                """;
        assertThatThrownBy(() -> validator().validate(new StreamSource(new StringReader(xml))))
                .isInstanceOf(SAXException.class);
    }

    @Test
    void unknownVersion_isRejected() {
        final String xml = """
                <graph xmlns="graph-mutation:1" version="99.0">
                </graph>
                """;
        assertThatThrownBy(() -> validator().validate(new StreamSource(new StringReader(xml))))
                .isInstanceOf(SAXException.class);
    }

    /**
     * Validates against the <b>shipped</b> schema resource, not a test copy of it, so these cases cannot pass
     * while the artefact an administrator actually registers says something different.
     */
    private static Validator validator() throws SAXException {
        final SchemaFactory schemaFactory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        final Schema schema = schemaFactory.newSchema(GraphMutationSchema.url());
        return schema.newValidator();
    }

    /**
     * The schema text must be retrievable, because that is how it reaches an {@code XMLSchema} document.
     */
    @Test
    void text_returnsTheSchemaSource() {
        assertThat(GraphMutationSchema.text())
                .contains("targetNamespace=\"" + GraphMutationSchema.NAMESPACE_URI + "\"")
                .contains("<xs:element name=\"graph\">");
    }
}
