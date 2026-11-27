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

package stroom.learningflow.learning;

import stroom.learningflow.scenario.Scenarios;
import stroom.learningflow.scoring.OutputRecords;
import stroom.learningflow.shared.RecordBoundary;

import net.sf.saxon.s9api.XdmNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// Tests record boundaries for XML whose records have different element names.
///
/// In the `GATEWAY-AUDIT` feed every record is a child of `<batch>`, but the records come under nine different
/// element names. A boundary of "every element named X" would have to hold 90% of the document to pass the
/// wholeness check, yet `transfer` holds only 41% and `authentication` 20%, so every answer naming a real
/// record would be rejected. A boundary of "the children of element X" lets the split step describe such
/// records.
class TestMixedRecordElements {

    private static final String GATEWAY = Scenarios.resource("gateway-audit.xml");
    private static final String PREFIXED = Scenarios.resource("gateway-audit-drift-1-prefixed.xml");
    private static final String NAMESPACED = Scenarios.resource("gateway-audit-drift-2-namespace.xml");
    private static final String FLATTENED = Scenarios.resource("gateway-audit-drift-3-flattened.xml");

    private static final double WHOLENESS = 0.9;

    @Test
    void theChildrenOfTheContainerAreTheRecords() {
        final OutputRecords document = OutputRecords.parse(GATEWAY).orElseThrow();

        final List<XdmNode> records = document.recordsBy(RecordBoundary.ofChildrenOf("batch"));

        assertThat(records).describedAs("every child of every batch, not only the first batch's")
                .hasSizeGreaterThan(20);
        assertThat(records).extracting(node -> node.getNodeName().getLocalName()).contains(
                "authentication", "transfer", "share", "policyChange", "adminAction", "sessionEnd",
                "heartbeat", "batchSummary");
    }

    @Test
    void theMixedChildrenPassTheWholenessCheckThatNoSingleNameCould() {
        final OutputRecords document = OutputRecords.parse(GATEWAY).orElseThrow();

        assertThat(TargetChecks.recordChildren(document, "batch", WHOLENESS))
                .describedAs("which is the whole of why this feed could not be learned")
                .isEmpty();
        assertThat(TargetChecks.recordElement(document, "transfer", WHOLENESS))
                .describedAs("while the best single name holds nowhere near enough")
                .isPresent();
    }

    @Test
    void aContainerThatOccursOnceIsNotRefused() {
        // The split is judged on a sample, and in this feed the first </batch> is at byte 11,318 while the
        // default sample is 8,192 bytes. So within a sample `batch` may occur only once, and a container that
        // repeats in the stream must not be refused for that.
        final String oneBatch = GATEWAY.substring(0, GATEWAY.indexOf("</batch>") + "</batch>".length())
                                + "</export>";

        assertThat(TargetChecks.recordChildren(OutputRecords.parse(oneBatch).orElseThrow(), "batch", WHOLENESS))
                .isEmpty();
    }

    @Test
    void theRecordsOfTheFlattenedDriftAreTheRootsOwnChildren() {
        // The flattened variant drops the <batch> wrapper, so the mixed records sit directly under <export>
        final OutputRecords document = OutputRecords.parse(FLATTENED).orElseThrow();

        assertThat(TargetChecks.recordChildren(document, RecordBoundary.ROOT, WHOLENESS)).isEmpty();
        assertThat(document.recordsBy(RecordBoundary.ofChildrenOf(RecordBoundary.ROOT)))
                .hasSizeGreaterThan(10);
    }

    @Test
    void aPrefixOrANewNamespaceDoesNotChangeWhatARecordIs() {
        // The boundary is a local name, so a variant with a namespace prefix and a variant with a changed
        // namespace still have the same boundary as the stream it was learned on
        for (final String drifted : List.of(PREFIXED, NAMESPACED)) {
            final OutputRecords document = OutputRecords.parse(drifted).orElseThrow();
            assertThat(document.recordsBy(RecordBoundary.ofChildrenOf("batch")))
                    .describedAs(drifted.substring(0, 60))
                    .hasSizeGreaterThan(10);
        }
    }

    @Test
    void howDeepARecordSitsIsReadFromTheDocument() {
        // This is the depth the fragment's SplitFilter is set to split at: 2 for export > batch > *, and 1 for
        // the flattened variant
        assertThat(OutputRecords.parse(GATEWAY).orElseThrow()
                .depthOf(RecordBoundary.ofChildrenOf("batch"))).hasValue(2);
        assertThat(OutputRecords.parse(FLATTENED).orElseThrow()
                .depthOf(RecordBoundary.ofChildrenOf(RecordBoundary.ROOT))).hasValue(1);
    }

    @Test
    void aContainerWithOneChildIsRefusedWithSomethingToTry() {
        // The refusal suggests what to try instead, rather than just saying no
        final String wrapped = "<export><batch><only><a>1</a></only></batch></export>";

        assertThat(TargetChecks.recordChildren(OutputRecords.parse(wrapped).orElseThrow(), "batch", WHOLENESS))
                .hasValueSatisfying(shortfall -> assertThat(shortfall.getMessage())
                        .contains("holds one element child")
                        .contains("name the element whose children repeat"));
    }

    @Test
    void aNameThatHoldsNoChildrenHereSaysSo() {
        assertThat(TargetChecks.recordChildren(OutputRecords.parse(GATEWAY).orElseThrow(), "nosuch", WHOLENESS))
                .hasValueSatisfying(shortfall -> assertThat(shortfall.getMessage())
                        .contains("No element named nosuch"));
    }
}
