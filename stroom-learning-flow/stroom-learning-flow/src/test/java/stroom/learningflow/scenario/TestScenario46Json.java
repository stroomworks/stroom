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

import stroom.learningflow.learning.Exchange;
import stroom.learningflow.learning.FlowExamples;
import stroom.learningflow.learning.InputKind;
import stroom.learningflow.learning.Question.Configuration;
import stroom.learningflow.learning.Question.Split;
import stroom.learningflow.learning.Question.TargetFor;
import stroom.learningflow.shared.BusinessRulesParameters;
import stroom.learningflow.shared.Check;
import stroom.learningflow.shared.CheckSetting;
import stroom.learningflow.shared.CheckState;
import stroom.learningflow.shared.ExtractionQualityParameters;
import stroom.learningflow.shared.FlowExample;
import stroom.learningflow.shared.LearningFlow;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.shared.LearningMode;
import stroom.learningflow.shared.RecordBoundary;
import stroom.learningflow.shared.XPathAssertion;
import stroom.learningflow.shared.YieldBasis;
import stroom.learningflow.shared.YieldParameters;
import stroom.learningflow.stage.Decision.Bound;
import stroom.learningflow.stage.Decision.Parked;
import stroom.learningflow.stage.Decision.Promoted;
import stroom.learningflow.stage.Input;
import stroom.learningflow.stage.StageRun;
import stroom.pipeline.shared.data.PipelineData;
import stroom.pipeline.shared.data.PipelineElement;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/// Tests learning JSON, both as JSON lines and as one document holding an array of records.
///
/// The chain uses a JSONParser, which takes no configuration, so no configuration question is asked for it.
/// The split question asks which array holds the records (`root` for JSON lines), and the transform question
/// shows the parser's real XML output. Both forms are promoted outright, because the record boundary the split
/// names is what the records are counted by. The tests also check that the transform is run one record at a
/// time, which stops it producing one event for a whole document or reading values from outside its record.
class TestScenario46Json {

    private static final String XSLT = Scenarios.resource("records.xsl");
    private static final String LINES = Scenarios.resource("records.jsonl");
    private static final String DOCUMENT = Scenarios.resource("records.json");
    private static final String EVENTS = Scenarios.resource("records.events.xml");

    private static LearningFlowDoc doc() {
        return Scenarios.document()
                .uuid("doc-1")
                .name("api-gateway")
                .learningMode(LearningMode.AUTOMATIC)
                .flow(FlowExamples.flow(FlowExample.TARGET_FIRST))
                .allowedElements(List.of("JSONParser", "XSLTFilter"))
                .minRecordsToLearn(5)
                .checks(List.of(
                        new CheckSetting(Check.COMPILE, CheckState.GATE, 1.0, null),
                        CheckSetting.of(Check.COVERAGE, CheckState.OFF),
                        new CheckSetting(Check.YIELD, CheckState.GATE, 0.5,
                                new YieldParameters(1.0, YieldBasis.RECORDS)),
                        CheckSetting.of(Check.WHOLENESS, CheckState.GATE),
                        new CheckSetting(Check.CONFORMANCE, CheckState.GATE, 1.0, null),
                        new CheckSetting(Check.QUALITY, CheckState.GATE, 0.7,
                                new ExtractionQualityParameters(false, List.of("EventSource/User/Id"))),
                        new CheckSetting(Check.RULES, CheckState.GATE, 1.0,
                                new BusinessRulesParameters(List.of(new XPathAssertion(
                                                "logons name the user",
                                                "not(EventDetail/Authenticate) "
                                                + "or EventDetail/Authenticate/User/Id[normalize-space(.) != '']")),
                                                true)),
                        CheckSetting.of(Check.PRESERVATION, CheckState.GATE),
                        CheckSetting.of(Check.FIDELITY, CheckState.GATE),
                        CheckSetting.of(Check.REGRESSION, CheckState.GATE)))
                .build();
    }

    private static Input stream(final long id, final String data) {
        return new Input(id, "API-GATEWAY", "Raw Events", Map.of("Format", "JSON"), data);
    }

    @Test
    void scenario46JsonLinesAreRecordsOfTheRoot() {
        final Scenarios scenarios = new Scenarios();
        final Script script = scenarios.jsonScript("root", XSLT)
                .expect(QuestionMatcher.chain()).reply("JSONParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("XSLTFilter").withTargets(2)).reply(Scenarios.fenced(XSLT));

        final StageRun run = scenarios.process(script, doc(), stream(1, LINES)).learned();

        assertThat(run.decision()).describedAs(run.decision().toString()).isInstanceOf(Promoted.class);
        script.verifyExhausted();
        assertThat(Scenarios.canonical(run.output())).isEqualTo(Scenarios.canonical(EVENTS));
        // Chain, split, two targets (a login and a logout, told apart by their keys) and the transform. Nothing
        // is asked for the parser, which takes no configuration.
        assertThat(script.asked()).hasSize(5);
        assertThat(script.asked()).noneMatch(question -> question instanceof Configuration c
                                                         && c.elementType().equals("JSONParser"));
        final Split split = (Split) script.asked().get(1);
        assertThat(split.kind()).isEqualTo(InputKind.JSON);
        assertThat(split.documentType()).isNull();
        assertThat(script.asked().stream().filter(TargetFor.class::isInstance)).hasSize(2);
        final Configuration transform = (Configuration) script.asked().get(4);
        assertThat(transform.split().array()).isEqualTo("root");
        assertThat(transform.input()).contains("http://www.w3.org/2013/XSL/json");
    }

    @Test
    void theOneEventTransformOfRunSevenCannotBeWrittenOnceTheTransformSeesOneRecord() {
        // A transform that emits one event from only the first item of the array would score well if the whole
        // document were counted as one record. Because the transform is shown one record and run over every
        // record, "the first item" is always the record it was given, so the same stylesheet writes twelve
        // events. A transform that cannot see beyond its own record cannot make this mistake.
        final String firstItem = XSLT.replace("select=\"//map[string/@key = 'time']\"",
                "select=\"(//map[string/@key = 'time'])[1]\"");
        assertThat(firstItem).describedAs("the stylesheet's loop was narrowed to the first item")
                .isNotEqualTo(XSLT);
        final Scenarios scenarios = new Scenarios();
        final Script script = scenarios.jsonScript("events", XSLT)
                .expect(QuestionMatcher.chain()).reply("JSONParser -> XSLTFilter")
                .expect(QuestionMatcher.split().withoutFeedback()).reply("events")
                .expect(QuestionMatcher.configuration("XSLTFilter").withoutFeedback())
                .reply(Scenarios.fenced(firstItem));

        final StageRun run = scenarios.process(script, doc(), stream(1, DOCUMENT)).learned();

        script.verifyExhausted();
        assertThat(run.decision()).describedAs(run.decision().toString()).isInstanceOf(Promoted.class);
        assertThat(Scenarios.canonical(run.output())).describedAs("twelve events, not one")
                .isEqualTo(Scenarios.canonical(EVENTS));
    }

    @Test
    void whereTheFlowHasNoTargetsYetTheTransformIsShownOneRecordOfEachKind() {
        // The escalating flow asks for a configuration before any target exists. Without an example of each
        // kind, the model would see a login and only a note that other records differ. A stylesheet written
        // from a login alone drops the logouts, so one record of each other kind is shown as well.
        final Scenarios scenarios = new Scenarios();
        final Script script = scenarios.jsonScript("events", XSLT)
                .expect(QuestionMatcher.chain()).reply("JSONParser -> XSLTFilter")
                .expect(QuestionMatcher.split().withoutFeedback()).reply("events")
                .expect(QuestionMatcher.configuration("XSLTFilter").withoutFeedback())
                .reply(Scenarios.fenced(XSLT));

        final StageRun run = scenarios.process(script,
                doc().copy().flow(FlowExamples.flow(FlowExample.ESCALATING)).build(), stream(1, DOCUMENT)).learned();

        script.verifyExhausted();
        assertThat(run.decision()).describedAs(run.decision().toString()).isInstanceOf(Promoted.class);
        final Configuration transform = (Configuration) script.asked().stream()
                .filter(Configuration.class::isInstance).findFirst().orElseThrow();
        assertThat(transform.targets()).describedAs("asked before any target was settled").isEmpty();
        assertThat(transform.input()).describedAs("the record it will be given, a login")
                .contains("key=\"kind\">login<");
        assertThat(transform.otherKinds()).describedAs("and one of the other kind, which it will also be given")
                .hasSize(1);
        assertThat(transform.otherKinds().get(0)).contains("key=\"kind\">logout<");
    }

    @Test
    void whereTheTargetsAlreadyShowEachKindTheQuestionDoesNotShowThemAgain() {
        // Under the target-first flow every kind of record is already shown to the model beside the event it
        // must become, so repeating the records in the question would spend tokens from the budget saying the
        // same thing twice.
        final Scenarios scenarios = new Scenarios();
        final Script script = scenarios.jsonScript("events", XSLT)
                .expect(QuestionMatcher.chain()).reply("JSONParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("XSLTFilter").withTargets(2))
                .reply(Scenarios.fenced(XSLT));

        final StageRun run = scenarios.process(script, doc(), stream(1, DOCUMENT)).learned();

        script.verifyExhausted();
        final Configuration transform = (Configuration) script.asked().stream()
                .filter(Configuration.class::isInstance).findFirst().orElseThrow();
        assertThat(transform.targets()).describedAs("one target per kind, each with its record").hasSize(2);
        assertThat(transform.otherKinds()).isEmpty();
        assertThat(run.decision()).isInstanceOf(Promoted.class);
    }

    @Test
    void aTransformThatWritesNothingForItsRecordIsStillShortOnYield() {
        // This is what the yield scorer is still needed for. A transform run one record at a time cannot emit
        // one event and ignore the other records, but it can still write nothing at all for the record it is
        // given.
        final String nothing = XSLT.replace("select=\"//map[string/@key = 'time']\"",
                "select=\"(//map[string/@key = 'time'])[2]\"");
        final Scenarios scenarios = new Scenarios();
        final Script script = scenarios.jsonScript("events", XSLT)
                .expect(QuestionMatcher.chain()).reply("JSONParser -> XSLTFilter")
                .expect(QuestionMatcher.split().withoutFeedback()).reply("events")
                .expect(QuestionMatcher.configuration("XSLTFilter").withoutFeedback())
                .reply(Scenarios.fenced(nothing))
                .expect(QuestionMatcher.configuration("XSLTFilter").withFeedbackMentioning("Yield scored"))
                .reply(Scenarios.fenced(XSLT));

        final StageRun run = scenarios.process(script, doc(), stream(1, DOCUMENT)).learned();

        script.verifyExhausted();
        assertThat(run.decision()).describedAs(run.decision().toString()).isInstanceOf(Promoted.class);
        assertThat(Scenarios.canonical(run.output())).isEqualTo(Scenarios.canonical(EVENTS));
    }

    @Test
    void aTransformThatReachesOutsideItsRecordIsCaughtBecauseThePipelineWillNotGiveItThat() {
        // A stylesheet that reaches outside its record into the document's envelope for a value. Over the whole
        // document the envelope is there, every event names its user and the candidate looks perfect. But the
        // split filter in the fragment keeps the structure above a record and not the envelope's other
        // contents, so when run as the pipeline will run it every event names nobody, and the document's
        // business rule refuses it. If it were scored over the whole document instead, it would be promoted and
        // then produce events with no user for ever.
        final Scenarios scenarios = new Scenarios();
        final String readsTheEnvelope = XSLT.replace(
                "select=\"string[@key = 'user']\"",
                "select=\"//string[@key = 'source']\"");
        // The script's targets are derived from this same stylesheet. The candidate is still refused the first
        // time it is judged, because the session runs the transform over one record at a time, where the
        // envelope is missing.
        final Script script = scenarios.jsonScript("events", readsTheEnvelope)
                .expect(QuestionMatcher.chain()).reply("JSONParser -> XSLTFilter")
                .expect(QuestionMatcher.split()).reply("events")
                .expect(QuestionMatcher.configuration("XSLTFilter"))
                .reply(Scenarios.fenced(readsTheEnvelope))
                // With the only candidate spent, the flow's exhaustion step asks once more. The same stylesheet
                // fails in the same way, and the session stops.
                .expect(QuestionMatcher.admission("XSLTFilter"))
                .reply(Scenarios.fenced(readsTheEnvelope));

        final StageRun run = scenarios.process(script, doc().copy().maxAttempts(1).build(),
                stream(1, DOCUMENT)).learned();

        script.verifyExhausted();
        // With its candidates spent, the session is parked for a person: nothing is bound and the stream waits.
        assertThat(run.decision()).describedAs(run.decision().toString()).isInstanceOf(Parked.class);
        assertThat(((Parked) run.decision()).reason())
                .describedAs("caught where it is written rather than at the gate: the conversation shows the "
                             + "transform one record and runs it over every record, so the envelope is "
                             + "missing the first time the candidate is judged")
                .isEqualTo("Couldn't translate what's left as marked Unknowns: its 1 answer fell short.");
        assertThat(scenarios.learnerBindings.forLearner(Scenarios.LEARNER)).describedAs("and nothing was bound")
                .isEmpty();
    }

    @Test
    void scenario46ADocumentsRecordsAreTheItemsOfAnArray() {
        final Scenarios scenarios = new Scenarios();
        final Script script = scenarios.jsonScript("events", XSLT)
                .expect(QuestionMatcher.chain()).reply("JSONParser -> XSLTFilter")
                // A key that does not name an array is refused; the array of records is accepted.
                .expect(QuestionMatcher.split().withoutFeedback()).reply("client")
                .expect(QuestionMatcher.split().withFeedbackMentioning("No array with the key \"client\""))
                .reply("events")
                .expect(QuestionMatcher.configuration("XSLTFilter").withTargets(2)).reply(Scenarios.fenced(XSLT));

        final StageRun run = scenarios.process(script, doc(), stream(1, DOCUMENT)).learned();

        script.verifyExhausted();
        // The array the split named is what the stage and the yield scorer count records by. The document has
        // twelve records, not one root map, so twelve events is a yield of one per record and the stream is
        // promoted outright. A one-event transform cannot pass on a single-record count. The binding keeps the
        // array as its record boundary for the streams it will serve.
        assertThat(run.decision()).describedAs(run.decision().toString()).isInstanceOf(Promoted.class);
        assertThat(((Promoted) run.decision()).score()).isEqualTo(1.0);
        assertThat(((Promoted) run.decision()).binding().getRecordBoundary())
                .isEqualTo(RecordBoundary.ofArray("events"));
        assertThat(Scenarios.canonical(run.output())).isEqualTo(Scenarios.canonical(EVENTS));
        final Configuration transform = (Configuration) script.asked().get(script.asked().size() - 1);
        assertThat(transform.targets()).describedAs(transform.targets().toString()).hasSize(2);
        assertThat(transform.split().array()).isEqualTo("events");
        final List<Exchange> turns = run.transcript();
        assertThat(turns.get(1).question()).isInstanceOf(Split.class);

        // The fragment that runs this in a pipeline splits at the array's items, so the stylesheet sees one
        // record at a time and memory use is bounded by the record rather than the stream. The depth is read
        // from the parsed document, not assumed: the items of an array under a key sit one level deeper than
        // the children of a root.
        assertThat(((Promoted) run.decision()).binding().getRecordBoundary().splitDepth()).hasValue(3);
        assertThat(((Promoted) run.decision()).binding().getRecordBoundary().getDepth())
                .describedAs("the parser wraps its output in a records root, so an item of an array under "
                             + "a key sits three elements down — which is why the depth is read from the "
                             + "document the split was settled against and not assumed")
                .isEqualTo(3);
        final PipelineData fragment = scenarios.stores.pipelines
                .readDocument(((Promoted) run.decision()).binding().getPipeline())
                .getPipelineData();
        assertThat(fragment.getElements().getAdd()).extracting(PipelineElement::getType)
                .containsExactly("Source", "JSONParser", "SplitFilter", "XSLTFilter");
        assertThat(fragment.getProperties().getAdd())
                .filteredOn(property -> "splitFilter".equals(property.getElement()))
                .extracting(property -> property.getName(), property -> property.getValue().getInteger())
                .containsExactly(tuple("splitDepth", 3), tuple("splitCount", 1));

        // A fragment containing a split filter must still be one the stage can run: the learner's next stream
        // is served by it with no model call. The fragment runner here skips the filter and leaves it to the
        // pipeline, but if it refused to run such a fragment, every bound XML or JSON feed would fail on its
        // second stream.
        final Script silent = Script.of();
        final StageRun served = scenarios.process(silent, run.doc(), stream(2, DOCUMENT)).learned();

        assertThat(silent.asked()).isEmpty();
        assertThat(served.decision()).describedAs(served.decision().toString()).isInstanceOf(Bound.class);
        assertThat(Scenarios.canonical(served.output())).isEqualTo(Scenarios.canonical(EVENTS));
    }
}
