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

package stroom.learningflow.stage;

import stroom.learningflow.shared.LearningFlowDoc;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// Tests that the held-out fraction applies to XML and JSON streams, not only to text.
///
/// A promotion is judged on records the model never saw. The held-out fraction is the share of a stream's
/// records withheld from the model and used only to judge promotion, so an XML or JSON stream within the
/// sample size limit must still have its share held back rather than being shown whole.
class TestHeldOutRecords {

    private static final int RECORDS = 10;

    @Test
    void anXmlDocumentHoldsBackItsShareOfRecords() {
        final String shown = LearningPrefix.learningPrefix(xml(), doc(0.2));

        assertThat(shown).describedAs("eight of ten shown").contains("<id>7</id>").doesNotContain("<id>8</id>");
        assertThat(shown).describedAs("still one document").startsWith("<records").endsWith("</records>");
    }

    @Test
    void aJsonDocumentHoldsBackItsShareOfItems() {
        final String shown = LearningPrefix.learningPrefix(json(), doc(0.2));

        assertThat(shown).contains("\"id\": 7").doesNotContain("\"id\": 8");
        assertThat(LearningPrefix.isJsonDocument(shown)).describedAs("still one value the parser can read").isTrue();
    }

    @Test
    void nothingHeldBackShowsTheStreamAsItCame() {
        // Byte for byte, rather than the same document rebuilt with its whitespace changed, because a model
        // shown a reformatted stream is shown something the parser will never see
        assertThat(LearningPrefix.learningPrefix(xml(), doc(0.0))).isEqualTo(xml());
        assertThat(LearningPrefix.learningPrefix(json(), doc(0.0))).isEqualTo(json());
    }

    @Test
    void aDocumentOfOneRecordIsShownWhole() {
        // Holding out the only record there is would leave nothing to learn from
        final String one = "<records xmlns=\"records:2\"><record><id>0</id></record></records>";

        assertThat(LearningPrefix.learningPrefix(one, doc(0.9))).contains("<id>0</id>");
    }

    /// Builds a document with a given held-out fraction.
    ///
    /// @param heldOut The share of records to hold back.
    /// @return A document with that held-out fraction and a sample size limit no test here reaches.
    private static LearningFlowDoc doc(final double heldOut) {
        return LearningFlowDoc.builder()
                .uuid("d")
                .sampleSizeLimit(100_000)
                .heldOutFraction(heldOut)
                .build();
    }

    private static String xml() {
        final StringBuilder data = new StringBuilder("<records xmlns=\"records:2\">\n");
        for (int i = 0; i < RECORDS; i++) {
            data.append("  <record><id>").append(i).append("</id></record>\n");
        }
        return data.append("</records>").toString();
    }

    private static String json() {
        final StringBuilder data = new StringBuilder("{\n  \"events\": [\n");
        for (int i = 0; i < RECORDS; i++) {
            data.append("    {\"id\": ").append(i).append('}').append(i < RECORDS - 1
                    ? ",\n"
                    : "\n");
        }
        return data.append("  ]\n}\n").toString();
    }
}
