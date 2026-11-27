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

package stroom.learningflow.scenario;

import stroom.learningflow.pack.DemoDocument;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.stage.Deferrals.Deferral;
import stroom.learningflow.stage.Input;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// Tests the deferral row recorded for a stream of a learner that has learned nothing yet.
///
/// Other tests cover the rows for records that fell short of the bar: a served stream that stops at its first
/// short record and waits, recording the snapshot it failed against, is tested in
/// `TestScenario54TheTaskDecision`, and the session's full pass naming every record that fell short, with its
/// gate, in `TestScenario57TheSessionRunsTheHeldStreamInFull`.
class TestScenario52TheRecordsThatFellShort {

    private static final String DOC = "doc-1";
    private static final String FEED = "DOOR-ACCESS";
    private static final String PIPELINE = "pipeline-1";

    private static final String MIXED = Scenarios.resource("csv-mixed.csv");

    /// A stream with no binding to serve it, whose attempt is opened and left for the worker, waits on a
    /// deferral row that names no snapshot it failed against and no failing records.
    @Test
    void aStreamOfALearnerNothingIsLearnedForIsRecordedToo() {
        final Scenarios scenarios = new Scenarios();
        final LearningFlowDoc doc = DemoDocument.configure(Scenarios.document())
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
