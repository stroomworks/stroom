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

import stroom.docref.DocRef;
import stroom.learningflow.extraction.ExtractionCorpus.Golden;
import stroom.learningflow.fragment.FragmentRunner;
import stroom.learningflow.fragment.RecordJudge;
import stroom.learningflow.fragment.ServedStream;
import stroom.learningflow.fragment.StandInFragmentRunner;
import stroom.learningflow.learning.FlowExamples;
import stroom.learningflow.learning.LearnedStep;
import stroom.learningflow.learning.StepResult;
import stroom.learningflow.scoring.Attempted;
import stroom.learningflow.scoring.Verdict;
import stroom.learningflow.shared.Binding;
import stroom.learningflow.shared.FlowExample;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.shared.LearningMode;
import stroom.learningflow.shared.RecordBoundary;
import stroom.learningflow.stage.Decision.Bound;
import stroom.learningflow.stage.Decision.Promoted;
import stroom.learningflow.stage.Decision.Sentinel;
import stroom.learningflow.stage.Input;
import stroom.learningflow.stage.Stage;
import stroom.learningflow.stage.StageRun;
import stroom.learningflow.transformation.StandInXsltStep;
import stroom.util.shared.DocPath;

import org.junit.jupiter.api.Test;
import org.xml.sax.ContentHandler;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// Tests reprocessing a stream "as processed", which runs it through the same fragment, under the same record
/// boundary, that produced its earlier output.
///
/// This is what an audit needs, and the snapshot of the fragment makes it possible however much the fragment
/// has changed since. Reprocessing with the current binding is just an ordinary processor filter on the
/// pipeline and is not tested here. The two differ only after a binding has been rebound, so these tests
/// rebind before reprocessing.
class TestReprocessingAsProcessed {

    private static final String FEED = "DOOR-ACCESS";
    private static final String XSLT = Scenarios.resource("csv-logon.xsl");
    private static final String DEGENERATE = Scenarios.resource("csv-degenerate.xsl");
    private static final Golden CSV = Scenarios.corpus("001_csv_with_header");
    private static final String RECORDS_XSLT = Scenarios.resource("records.xsl");
    private static final Verdict UNSCORED = new Verdict(List.of(), 1.0);

    private static LearningFlowDoc doc() {
        return Scenarios.document()
                .uuid("doc-1")
                .name("door-access")
                .learningMode(LearningMode.AUTOMATIC)
                .allowedElements(List.of("DSParser", "XSLTFilter"))
                .minRecordsToLearn(1)
                .build();
    }

    private static Input stream(final long id, final String data) {
        return new Input(id, FEED, "Raw Events", Map.of("Format", "CSV"), data, "pipeline-1");
    }

    /// A fragment runner that records the boundary each run is given, and otherwise delegates to the runner it
    /// wraps.
    private static final class Watching implements FragmentRunner {

        private final List<RecordBoundary> boundaries = new ArrayList<>();
        private final FragmentRunner runner;

        private Watching(final FragmentRunner runner) {
            this.runner = runner;
        }

        /// Serves through the wrapped runner without recording anything; only calls to `run` are recorded.
        @Override
        public ServedStream serve(final DocRef fragment,
                                  final InputStream input,
                                  final RecordBoundary boundary,
                                  final RecordJudge judge,
                                  final ContentHandler downstream) {
            return runner.serve(fragment, input, boundary, judge, downstream);
        }

        @Override
        public List<Attempted> run(final DocRef fragment, final String input, final RecordBoundary boundary) {
            boundaries.add(boundary);
            return runner.run(fragment, input, boundary);
        }

        @Override
        public List<Attempted> run(final DocRef fragment,
                                   final String input,
                                   final RecordBoundary boundary,
                                   final Map<String, String> code) {
            boundaries.add(boundary);
            return runner.run(fragment, input, boundary, code);
        }
    }

    private static Script learning(final Scenarios scenarios) {
        return scenarios.script(CSV.configuration(), XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(CSV.configuration()))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(XSLT));
    }

    @Test
    void asProcessedRunsTheFragmentThatProducedTheOutputAndNotWhatIsBoundNow() {
        final Scenarios scenarios = new Scenarios();
        final Stage stage = scenarios.stage(Script.of());

        final LearningFlowDoc doc = doc();
        final StageRun learned = scenarios.process(learning(scenarios), doc, stream(1, CSV.input())).learned();
        assertThat(learned.decision()).describedAs(learned.decision().toString())
                .isInstanceOf(Promoted.class);
        final DocRef first = learned.produced().fragment();

        // The binding is rebound to another fragment, as an improvement does, keeping its UUID. The second
        // fragment is a real one: a run with the current binding would succeed with it and produce different
        // events, which is what lets the test tell the two modes apart.
        final Binding binding = scenarios.learnerBindings.forLearner(Scenarios.LEARNER).getFirst();
        final DocRef second = scenarios.stores.writer().write(
                DocPath.fromParts("Learning Flow", FEED), "improved-v2",
                List.of(new LearnedStep(scenarios.runners().getFirst(), CSV.configuration(),
                                new StepResult("<records/>", List.of()), UNSCORED),
                        new LearnedStep(new StandInXsltStep(), DEGENERATE,
                                new StepResult("<Events/>", List.of()), UNSCORED)),
                binding.getRecordBoundary());
        scenarios.learnerBindings.replace(Scenarios.LEARNER, "doc-1", binding.copy().pipeline(second).build());

        // Reprocessing as processed sends the stream back through the fragment that made its output.
        final StageRun again = stage.reprocess(doc, Scenarios.LEARNER, stream(1, CSV.input()));

        assertThat(again.decision()).describedAs(again.decision().toString()).isInstanceOf(Bound.class);
        assertThat(again.produced().fragment().getUuid())
                .describedAs("the fragment that produced it, not the one bound now")
                .isEqualTo(first.getUuid());
        assertThat(again.output()).isEqualTo(learned.output());
    }

    @Test
    void asProcessedRunsUnderTheBoundaryThatProducedTheOutputAndNotTodaysBindingBoundary() {
        // A rebind keeps the binding's UUID but replaces its record boundary, and the boundary decides where
        // the input is split into records and so what the transform is given. The fragment alone is therefore
        // not enough to rerun a stream as it ran: the record of the output keeps the boundary it was made
        // with, and the reprocessing run must be given that one.
        final Scenarios scenarios = new Scenarios();
        final Watching watching = new Watching(new StandInFragmentRunner(scenarios.stores.pipelines,
                scenarios.stores.stackLoader, scenarios.stores.textConverters, scenarios.stores.xslts,
                scenarios.runners()));
        final Script script = scenarios.jsonScript("events", RECORDS_XSLT)
                .expect(QuestionMatcher.chain()).reply("JSONParser -> XSLTFilter")
                .expect(QuestionMatcher.split().withoutFeedback()).reply("events")
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(RECORDS_XSLT));
        final Stage stage = scenarios.stage(document -> script, scenarios.learnerBindings, watching);
        final LearningFlowDoc doc = doc().copy()
                .allowedElements(List.of("JSONParser", "XSLTFilter"))
                // The split question decides the boundary, and the target-first flow asks it for every input.
                .flow(FlowExamples.flow(FlowExample.TARGET_FIRST))
                .build();
        final Input json = new Input(1, "API-GATEWAY", "Raw Events", Map.of("Format", "JSON"),
                Scenarios.resource("records.json"), "pipeline-1");

        final StageRun learned = scenarios.process(document -> script, scenarios.learnerBindings, watching, doc, json)
                .learned();
        assertThat(learned.decision()).describedAs(learned.decision().toString()).isInstanceOf(Promoted.class);
        assertThat(learned.produced().boundary().splitDepth().orElseThrow())
                .describedAs("the items of the array sit three elements down")
                .isEqualTo(3);

        // The binding is rebound with a boundary that would cut somewhere else entirely.
        final Binding binding = scenarios.learnerBindings.forLearner(Scenarios.LEARNER).getFirst();
        scenarios.learnerBindings.replace(Scenarios.LEARNER, "doc-1", binding.copy()
                .recordBoundary(RecordBoundary.ofArray("events").atDepth(1))
                .build());

        watching.boundaries.clear();
        final StageRun again = stage.reprocess(doc, Scenarios.LEARNER, json);

        assertThat(again.decision()).describedAs(again.decision().toString()).isInstanceOf(Bound.class);
        assertThat(watching.boundaries)
                .describedAs("run under the boundary that produced the output, not the binding's today")
                .extracting(boundary -> boundary.splitDepth().orElseThrow())
                .containsExactly(3);
    }

    @Test
    void asProcessedSaysSoWhenNothingIsRecordedRatherThanServingAsCurrent() {
        final Scenarios scenarios = new Scenarios();
        final Stage stage = scenarios.stage(scenarios.script(DEGENERATE, DEGENERATE));

        final StageRun run = stage.reprocess(doc(), Scenarios.LEARNER, stream(99, CSV.input()));

        assertThat(run.decision()).isInstanceOf(Sentinel.class);
        assertThat(((Sentinel) run.decision()).reason())
                .contains("Nothing is recorded for input 99")
                .contains("as-current");
    }
}
