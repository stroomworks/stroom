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

import stroom.pipeline.shared.data.PipelineData;
import stroom.pipeline.shared.data.PipelineElement;
import stroom.shapeshifter.ai.learning.Exchange;
import stroom.shapeshifter.ai.learning.InputKind;
import stroom.shapeshifter.ai.learning.Question.Configuration;
import stroom.shapeshifter.ai.learning.Question.Split;
import stroom.shapeshifter.ai.learning.Question.TargetFor;
import stroom.shapeshifter.ai.stage.Decision.Bound;
import stroom.shapeshifter.ai.stage.Decision.Parked;
import stroom.shapeshifter.ai.stage.Decision.Promoted;
import stroom.shapeshifter.ai.stage.Input;
import stroom.shapeshifter.ai.stage.StageRun;
import stroom.shapeshifter.shared.BusinessRulesParameters;
import stroom.shapeshifter.shared.ExtractionQualityParameters;
import stroom.shapeshifter.shared.FlowExample;
import stroom.shapeshifter.shared.LearningFlow;
import stroom.shapeshifter.shared.LearningMode;
import stroom.shapeshifter.shared.RecordBoundary;
import stroom.shapeshifter.shared.SchemaConformanceParameters;
import stroom.shapeshifter.shared.ScorerSetting;
import stroom.shapeshifter.shared.ScorerType;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.shapeshifter.shared.XPathAssertion;
import stroom.shapeshifter.shared.YieldBasis;
import stroom.shapeshifter.shared.YieldParameters;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/// Design 02 §5, scenario 46 (design 03 §3): JSON, as lines and as one document with an array of records. The
/// chain is a JSONParser, which takes no configuration, so no configuration question is asked for it; the
/// split question asks which array holds the records — root for the lines — and the transform is asked with
/// the parser's real XML. The lines promote outright; the document, whose records sit inside an array, binds
/// provisionally until the record boundary reaches the stage's count (design 01 §10.1).
class TestScenario46Json {

    private static final String XSLT = Scenarios.resource("records.xsl");
    private static final String LINES = Scenarios.resource("records.jsonl");
    private static final String DOCUMENT = Scenarios.resource("records.json");
    private static final String EVENTS = Scenarios.resource("records.events.xml");

    private static ShapeshifterAiDoc doc() {
        return Scenarios.document()
                .uuid("doc-1")
                .name("api-gateway")
                .learningMode(LearningMode.AUTOMATIC)
                .flow(FlowExample.TARGET_FIRST)
                .allowedElements(List.of("JSONParser", "XSLTFilter"))
                .minRecordsToLearn(5)
                .scorers(List.of(
                        new ScorerSetting(ScorerType.COMPILE, 0.0, 1.0, true, null),
                        new ScorerSetting(ScorerType.YIELD, 1.0, 0.5, false,
                                new YieldParameters(1.0, YieldBasis.RECORDS)),
                        new ScorerSetting(ScorerType.SCHEMA_CONFORMANCE, 1.0, 1.0, true,
                                new SchemaConformanceParameters("EVENTS")),
                        new ScorerSetting(ScorerType.EXTRACTION_QUALITY, 1.0, 0.7, true,
                                new ExtractionQualityParameters(false, List.of("EventSource/User/Id"))),
                        new ScorerSetting(ScorerType.BUSINESS_RULES, 1.0, 1.0, false,
                                new BusinessRulesParameters(List.of(new XPathAssertion(
                                        "logons name the user",
                                        "not(EventDetail/Authenticate) "
                                        + "or EventDetail/Authenticate/User/Id[normalize-space(.) != '']")),
                                        true))))
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
        // Chain, split, two targets — a login and a logout, told apart by their keys — and the transform; nothing
        // for the parser, which takes no configuration.
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
        // Run 7 (design 02 §6.3): counted as one record, a transform emitting one event from the array's
        // first item scored a yield of one per record and was promoted. Slice 19 made the count the
        // array's items, so it read as one in twelve and was re-asked. Item 25 dissolves the trap
        // altogether: the transform is shown one record and run over every record, so "the first item"
        // *is* the record it was given, and the same stylesheet writes twelve events. A whole class of
        // degeneracy stops being expressible when the transform can no longer see past its own record.
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
        // A47: the escalating flow asks for a configuration before any target exists, so a stream of
        // logins and logouts would otherwise show the model a login and a sentence saying the rest
        // differ. Saying a second kind exists is not the same as showing it, and a stylesheet written
        // from a login alone drops the logouts.
        final Scenarios scenarios = new Scenarios();
        final Script script = scenarios.jsonScript("events", XSLT)
                .expect(QuestionMatcher.chain()).reply("JSONParser -> XSLTFilter")
                .expect(QuestionMatcher.split().withoutFeedback()).reply("events")
                .expect(QuestionMatcher.configuration("XSLTFilter").withoutFeedback())
                .reply(Scenarios.fenced(XSLT));

        final StageRun run = scenarios.process(script,
                doc().copy().flow(LearningFlow.of(FlowExample.ESCALATING)).build(), stream(1, DOCUMENT)).learned();

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
        // A47's other half: under target-first every kind is already in front of the model as a record
        // beside the event it must become, so repeating them in the question is a budget (A44) spent
        // saying the same thing twice.
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
        // What the yield scorer is still for: a transform run one record at a time can no longer emit one
        // event and ignore the rest, but it can still write nothing at all for the record it was given.
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
        // §12 item 25, and the mistake it exists to catch: a stylesheet that reaches into the document's
        // envelope for a value. Over the whole document the envelope is there, every event names its user
        // and the candidate is perfect; the filter the fragment carries replicates the structure above a
        // record but not the envelope's other contents, so run as the pipeline will run it every event
        // names nobody and the document's own business rule refuses it. Scored over the whole document
        // this would have been promoted and then produced nameless events for ever.
        final Scenarios scenarios = new Scenarios();
        final String readsTheEnvelope = XSLT.replace(
                "select=\"string[@key = 'user']\"",
                "select=\"//string[@key = 'source']\"");
        // The targets come from this stylesheet run over the whole document, as the conversation runs it, so
        // it answers every question the conversation puts: what it does not survive is the promotion gate,
        // which runs the chain the way the fragment will.
        final Script script = scenarios.jsonScript("events", readsTheEnvelope)
                .expect(QuestionMatcher.chain()).reply("JSONParser -> XSLTFilter")
                .expect(QuestionMatcher.split()).reply("events")
                .expect(QuestionMatcher.configuration("XSLTFilter"))
                .reply(Scenarios.fenced(readsTheEnvelope))
                // The candidate spent, the flow's exhaustion step asks once more (design 04 §4): the same
                // stylesheet fails the same way, and that is the end of it.
                .expect(QuestionMatcher.admission("XSLTFilter"))
                .reply(Scenarios.fenced(readsTheEnvelope));

        final StageRun run = scenarios.process(script, doc().copy().maxAttempts(1).build(),
                stream(1, DOCUMENT)).learned();

        script.verifyExhausted();
        // Its candidates spent, the session parks for a person (A65): nothing is bound and the stream waits.
        assertThat(run.decision()).describedAs(run.decision().toString()).isInstanceOf(Parked.class);
        assertThat(((Parked) run.decision()).reason())
                .describedAs("caught where it is written rather than at the gate: the conversation shows the "
                             + "transform one record and runs it over every record, so the envelope is "
                             + "missing the first time the candidate is judged")
                .isEqualTo("No admission for XSLTFilter after 1 attempts");
        assertThat(scenarios.learnerBindings.forLearner(Scenarios.LEARNER)).describedAs("and nothing was bound")
                .isEmpty();
    }

    @Test
    void scenario46ADocumentsRecordsAreTheItemsOfAnArray() {
        final Scenarios scenarios = new Scenarios();
        final Script script = scenarios.jsonScript("events", XSLT)
                .expect(QuestionMatcher.chain()).reply("JSONParser -> XSLTFilter")
                // A key that is not an array's is refused; the array of records is accepted.
                .expect(QuestionMatcher.split().withoutFeedback()).reply("client")
                .expect(QuestionMatcher.split().withFeedbackMentioning("No array with the key \"client\""))
                .reply("events")
                .expect(QuestionMatcher.configuration("XSLTFilter").withTargets(2)).reply(Scenarios.fenced(XSLT));

        final StageRun run = scenarios.process(script, doc(), stream(1, DOCUMENT)).learned();

        script.verifyExhausted();
        // Learned whole; the array the split named reaches the stage's count and the yield scorer (A35): the
        // document brings twelve records, not the root's one map, so twelve events is a yield of one per
        // record and the stream is promoted outright — where run 7 (design 02 §6.3) had one record, and a
        // one-event transform promoted on it. The binding carries the array for the streams it will serve.
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

        // §12 item 25: the fragment that will run this in a pipeline splits at the array's items, so the
        // stylesheet sees one record and memory is bounded by the record and not by the stream. The depth
        // is read from the parsed document, not assumed: the items of an array under a key sit one deeper
        // than the children of a root.
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

        // And the fragment that now carries a filter is still a fragment this stage can run: the next
        // stream of the learner is served by it, with no model call. The filter is the pipeline's to run —
        // the runner passes over it, and running the chain per record as the pipeline does is what item
        // 25 still owes — but a fragment it refused to run would break every bound markup feed on its
        // second stream.
        final Script silent = Script.of();
        final StageRun served = scenarios.process(silent, run.doc(), stream(2, DOCUMENT)).learned();

        assertThat(silent.asked()).isEmpty();
        assertThat(served.decision()).describedAs(served.decision().toString()).isInstanceOf(Bound.class);
        assertThat(Scenarios.canonical(served.output())).isEqualTo(Scenarios.canonical(EVENTS));
    }
}
