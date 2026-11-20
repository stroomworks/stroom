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

package stroom.shapeshifter.ai.learning;

import stroom.shapeshifter.ai.scenario.Scenarios;
import stroom.shapeshifter.ai.scoring.OutputRecords;
import stroom.shapeshifter.shared.RecordBoundary;

import net.sf.saxon.s9api.XdmNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// Track 1 task 4: **XML whose records have different element names**
/// (`docs/shapeshifter-ai-mixed-record-elements.md`).
///
/// The `GATEWAY-AUDIT` feed could not be learned at all. Every record is a child of `<batch>`, but the
/// records come under nine different names, and the split step could only say *every element named X* —
/// which then had to hold 90% of the document. `transfer` holds 41% and `authentication` 20%, so every
/// answer naming a real record was rejected and the step ran out of candidates. The model diagnosed it
/// correctly on its fifth try and had no way to say so.
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
        // Half of why the feed could not be learned: the split is judged on a sample, and the first
        // </batch> is at byte 11,318 while the default sample is 8,192 — so `batch` "occurs once" there
        // and the old check read a container that repeats in the stream as a container of records.
        final String oneBatch = GATEWAY.substring(0, GATEWAY.indexOf("</batch>") + "</batch>".length())
                                + "</export>";

        assertThat(TargetChecks.recordChildren(OutputRecords.parse(oneBatch).orElseThrow(), "batch", WHOLENESS))
                .isEmpty();
    }

    @Test
    void theRecordsOfTheFlattenedDriftAreTheRootsOwnChildren() {
        // Drift 3 drops the <batch> wrapper, so the mixed records sit directly under <export>
        final OutputRecords document = OutputRecords.parse(FLATTENED).orElseThrow();

        assertThat(TargetChecks.recordChildren(document, RecordBoundary.ROOT, WHOLENESS)).isEmpty();
        assertThat(document.recordsBy(RecordBoundary.ofChildrenOf(RecordBoundary.ROOT)))
                .hasSizeGreaterThan(10);
    }

    @Test
    void aPrefixOrANewNamespaceDoesNotChangeWhatARecordIs() {
        // The boundary is a local name, so drift 1 (a prefix) and drift 2 (a changed namespace) are the
        // same boundary as the stream it was learned on
        for (final String drifted : List.of(PREFIXED, NAMESPACED)) {
            final OutputRecords document = OutputRecords.parse(drifted).orElseThrow();
            assertThat(document.recordsBy(RecordBoundary.ofChildrenOf("batch")))
                    .describedAs(drifted.substring(0, 60))
                    .hasSizeGreaterThan(10);
        }
    }

    @Test
    void howDeepARecordSitsIsReadFromTheDocument() {
        // What the fragment's SplitFilter is set to split at (§12 item 25): three for export > batch > *,
        // two for the flattened drift
        assertThat(OutputRecords.parse(GATEWAY).orElseThrow()
                .depthOf(RecordBoundary.ofChildrenOf("batch"))).hasValue(2);
        assertThat(OutputRecords.parse(FLATTENED).orElseThrow()
                .depthOf(RecordBoundary.ofChildrenOf(RecordBoundary.ROOT))).hasValue(1);
    }

    @Test
    void aContainerWithOneChildIsRefusedWithSomethingToTry() {
        // Not "no", but "look one level deeper or one level up"
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
