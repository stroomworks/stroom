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

import stroom.learningflow.learning.FlowExamples;
import stroom.learningflow.shared.Check;
import stroom.learningflow.shared.CheckSetting;
import stroom.learningflow.shared.CheckState;
import stroom.learningflow.shared.FlowExample;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.shared.LearningMode;
import stroom.learningflow.shared.RecordBoundary;
import stroom.learningflow.stage.Decision.Promoted;
import stroom.learningflow.stage.Input;
import stroom.learningflow.stage.StageRun;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// Tests learning XML whose records have different element names under one container.
///
/// In the `GATEWAY-AUDIT` fixture every record is a child of `<batch>`, but under nine different names. A
/// split answer of the form "every element named X" would have to hold 90% of the document to pass, and no
/// single name does (`transfer` holds 41% and `authentication` 20%), so every such answer would be rejected.
/// The split can instead be answered `batch/*`, meaning every child of the container is one record,
/// whatever it is called.
class TestScenario65MixedRecordElements {

    private static final String GATEWAY = Scenarios.resource("gateway-audit.xml");
    private static final String FLATTENED = Scenarios.resource("gateway-audit-drift-3-flattened.xml");
    private static final String XSLT = Scenarios.resource("gateway-audit.xsl");

    @Test
    void theMixedChildrenOfAContainerAreLearnedAsRecords() {
        final Scenarios scenarios = new Scenarios();
        final Script script = scenarios.xmlScript("batch/*", XSLT)
                // With only one allowed element, the chain question is not asked.
                .expect(QuestionMatcher.split().withoutFeedback()).reply("batch/*")
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(XSLT));

        final StageRun run = scenarios.process(script, doc(),
                new Input(1, "GATEWAY-AUDIT", "Raw Events", Map.of("Format", "XML"), GATEWAY)).learned();

        assertThat(run.decision()).describedAs(run.decision().toString()).isInstanceOf(Promoted.class);
        script.verifyExhausted();

        final RecordBoundary boundary = ((Promoted) run.decision()).binding().getRecordBoundary();
        assertThat(boundary.isChildren()).isTrue();
        assertThat(boundary.getChildren()).isEqualTo("batch");
        assertThat(boundary.getElement()).describedAs("not the element of that name").isNull();
        assertThat(boundary.splitDepth()).describedAs("export > batch > record").hasValue(2);
    }

    @Test
    void theFlattenedDriftIsTheRootsOwnChildren() {
        // This variant of the feed drops the <batch> wrapper, so the mixed records sit directly under <export>.
        final Scenarios scenarios = new Scenarios();
        final Script script = scenarios.xmlScript("*", XSLT)
                .expect(QuestionMatcher.split().withoutFeedback()).reply("*")
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(flattenedXslt()));

        final StageRun run = scenarios.process(script, doc(),
                new Input(1, "GATEWAY-AUDIT", "Raw Events", Map.of("Format", "XML"), FLATTENED)).learned();

        assertThat(run.decision()).describedAs(run.decision().toString()).isInstanceOf(Promoted.class);
        final RecordBoundary boundary = ((Promoted) run.decision()).binding().getRecordBoundary();
        assertThat(boundary.getChildren()).isEqualTo(RecordBoundary.ROOT);
        assertThat(boundary.splitDepth()).hasValue(1);
    }

    @Test
    void aSingleNameStillSettlesTheBoundaryWhereOneFits() {
        // Guards against breaking feeds that use a single record element name: that kind of boundary is
        // still distinct from a boundary of all the children of an element.
        assertThat(RecordBoundary.ofElement("batch"))
                .describedAs("and an element is never the same boundary as its children")
                .isNotEqualTo(RecordBoundary.ofChildrenOf("batch"));
    }

    /// Adapts the stylesheet to records that sit directly under the root.
    private static String flattenedXslt() {
        return XSLT.replace("select=\"batch/*\"", "select=\"*\"")
                .replace("match=\"batch/*\"", "match=\"export/*\"")
                .replace("../@site", "../@site")
                .replace("../@node", "../@node");
    }

    private static LearningFlowDoc doc() {
        return Scenarios.document()
                .uuid("doc-1")
                .name("gateway-audit")
                .learningMode(LearningMode.AUTOMATIC)
                .flow(FlowExamples.flow(FlowExample.TARGET_FIRST))
                .allowedElements(List.of("XSLTFilter"))
                .minRecordsToLearn(5)
                .heldOutFraction(0.0)
                .sampleSizeLimit(200_000)
                .checks(List.of(
                        new CheckSetting(Check.COMPILE, CheckState.GATE, 1.0, null),
                        CheckSetting.of(Check.COVERAGE, CheckState.OFF),
                        CheckSetting.of(Check.COMPLETENESS, CheckState.GATE),
                        CheckSetting.of(Check.WHOLENESS, CheckState.GATE),
                        CheckSetting.of(Check.CONFORMANCE, CheckState.OFF),
                        CheckSetting.of(Check.QUALITY, CheckState.OFF),
                        CheckSetting.of(Check.RULES, CheckState.OFF),
                        CheckSetting.of(Check.TIMES, CheckState.OFF),
                        CheckSetting.of(Check.LITERALS, CheckState.OFF),
                        CheckSetting.of(Check.PERMITTED, CheckState.GATE),
                        CheckSetting.of(Check.PRESERVATION, CheckState.GATE),
                        CheckSetting.of(Check.FIDELITY, CheckState.GATE),
                        CheckSetting.of(Check.REGRESSION, CheckState.GATE)))
                .build();
    }
}
