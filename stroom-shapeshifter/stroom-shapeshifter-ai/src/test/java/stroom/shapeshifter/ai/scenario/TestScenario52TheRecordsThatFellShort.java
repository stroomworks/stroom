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

package stroom.shapeshifter.ai.scenario;

import stroom.shapeshifter.ai.pack.DemoDocument;
import stroom.shapeshifter.ai.stage.Deferrals.Deferral;
import stroom.shapeshifter.ai.stage.Input;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// Design 02 §5, scenario 52: **the records that fell short.**
///
/// Design 04 §3 moves the bar from the stream to the record (A55). Phase 3 recorded, in shadow, the rows
/// phase 4 would defer on; phase 4 made them real, and what this scenario held of a served stream's
/// shortfalls is now held where it happens: the stream that stops at its first short record and waits,
/// stamped with the snapshot it failed against, in scenario 54; and the session's full pass naming
/// every record that fell short, with its gate, in scenario 57 (A99 retired the inline walk the shadow
/// rows were written from). What remains here is the row a stream of a learner that has learned nothing
/// waits on.
class TestScenario52TheRecordsThatFellShort {

    private static final String DOC = "doc-1";
    private static final String FEED = "DOOR-ACCESS";
    private static final String PIPELINE = "pipeline-1";

    private static final String MIXED = Scenarios.resource("csv-mixed.csv");

    /// A stream the document has no binding for, whose attempt is opened and parked for a worker (scenario
    /// 30), waits: a row with nothing it failed against.
    @Test
    void aStreamOfALearnerNothingIsLearnedForIsRecordedToo() {
        final Scenarios scenarios = new Scenarios();
        final ShapeshifterAiDoc doc = DemoDocument.configure(Scenarios.document())
                .uuid(DOC)
                .name("door-access")
                .build();

        scenarios.stage(document -> Script.of(), scenarios.learnerBindings).decide(doc, Scenarios.LEARNER, stream(3L,
                MIXED));

        final List<Deferral> rows = scenarios.deferrals.forDocument(DOC);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().inputId()).isEqualTo(3L);
        assertThat(rows.getFirst().failedAgainst()).isNull();
        assertThat(rows.getFirst().failingRecords()).isZero();
        assertThat(rows.getFirst().reason()).isNotBlank();
    }

    private static Input stream(final long id, final String data) {
        return new Input(id, FEED, "Raw Events", Map.of(), data, PIPELINE);
    }
}
