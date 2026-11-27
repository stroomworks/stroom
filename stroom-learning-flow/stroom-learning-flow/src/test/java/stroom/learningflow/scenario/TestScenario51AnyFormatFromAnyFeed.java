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

import stroom.learningflow.extraction.ExtractionCorpus.Golden;
import stroom.learningflow.learning.Question.Chain;
import stroom.learningflow.pack.DemoDocument;
import stroom.learningflow.shared.Binding;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.stage.Decision.Promoted;
import stroom.learningflow.stage.Input;
import stroom.learningflow.stage.Learner;
import stroom.learningflow.stage.StageRun;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// Tests one Learning Flow document, allowing every parser, learning feeds of several different formats.
///
/// The other format tests narrow `allowedElements` to the one parser their format needs (`DSParser` for
/// text, `JSONParser` for JSON). This one offers every parser, as a person setting up Learning Flow would,
/// since they do not know what the next feed will be: they configure one document and point feeds at it.
/// The document used is the configuration the demo content pack ships, so this test also shows that the
/// pack's settings work.
///
/// There are four feeds, each in its own format and with its own pipeline (as a Content Template makes
/// them), and so its own learner and binding: delimited text with a header, JSON lines, syslog in two forms,
/// and fixed-width columns with no delimiter. They share one document, one flow and one set of scorers.
///
/// The model's replies are scripted, so the choice of parser is made by the test, not by a model. What is
/// tested is everything around that choice: the chain question offers the whole list, the split question
/// suits the input kind the chosen parser produces, a `JSONParser` is asked for no configuration while a
/// `DSParser` is, one set of scorers judges all four, and each promotes on its own evidence and writes its
/// format's expected events. Whether a real model chooses well is for a live run against a model to show.
class TestScenario51AnyFormatFromAnyFeed {

    private static final String DOC = "doc-1";

    private static final Golden CSV = Scenarios.corpus("001_csv_with_header");
    private static final String CSV_XSLT = Scenarios.resource("csv-logon.xsl");
    private static final String CSV_EVENTS = Scenarios.resource("csv-logon.events.xml");

    private static final String JSON_LINES = Scenarios.resource("records.jsonl");
    private static final String JSON_XSLT = Scenarios.resource("records.xsl");
    private static final String JSON_EVENTS = Scenarios.resource("records.events.xml");

    private static final String SYSLOG = Scenarios.resource("syslog.log");
    private static final String SYSLOG_DS3 = Scenarios.resource("syslog.ds3.xml");
    private static final String SYSLOG_XSLT = Scenarios.resource("syslog.xsl");
    private static final String SYSLOG_EVENTS = Scenarios.resource("syslog.events.xml");

    private static final String FIXED = Scenarios.resource("fixed-width.log");
    private static final String FIXED_DS3 = Scenarios.resource("fixed-width.ds3.xml");
    private static final String FIXED_XSLT = Scenarios.resource("fixed-width.xsl");
    private static final String FIXED_EVENTS = Scenarios.resource("fixed-width.events.xml");

    /// Each feed's learner learns its own binding, and each binding writes the format's expected events.
    ///
    /// The document is the same one throughout, so this shows one document working for four inputs that
    /// share nothing but the event schema they are translated to, not four documents that each work.
    @Test
    void oneDocumentLearnsEveryFormatItIsPointedAt() {
        final Scenarios scenarios = new Scenarios();
        final LearningFlowDoc doc = doc();

        final StageRun csv = learn(scenarios, doc, "DOOR-ACCESS", CSV.input(),
                scenarios.script(CSV.configuration(), CSV_XSLT)
                        .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                        .expect(QuestionMatcher.configuration("DSParser"))
                        .reply(Scenarios.fenced(CSV.configuration()))
                        .expect(QuestionMatcher.configuration("XSLTFilter"))
                        .reply(Scenarios.fenced(CSV_XSLT)));
        assertPromoted(csv, CSV_EVENTS);

        final StageRun json = learn(scenarios, doc, "APP-EVENTS", JSON_LINES,
                scenarios.jsonScript("root", JSON_XSLT)
                        .expect(QuestionMatcher.chain()).reply("JSONParser -> XSLTFilter")
                        .expect(QuestionMatcher.configuration("XSLTFilter"))
                        .reply(Scenarios.fenced(JSON_XSLT)));
        assertPromoted(json, JSON_EVENTS);

        final StageRun syslog = learn(scenarios, doc, "FIREWALL", SYSLOG,
                scenarios.script(SYSLOG_DS3, SYSLOG_XSLT)
                        .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                        .expect(QuestionMatcher.configuration("DSParser"))
                        .reply(Scenarios.fenced(SYSLOG_DS3))
                        .expect(QuestionMatcher.configuration("XSLTFilter"))
                        .reply(Scenarios.fenced(SYSLOG_XSLT)));
        assertPromoted(syslog, SYSLOG_EVENTS);

        final StageRun fixed = learn(scenarios, doc, "MAINFRAME", FIXED,
                scenarios.script(FIXED_DS3, FIXED_XSLT)
                        .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                        .expect(QuestionMatcher.configuration("DSParser"))
                        .reply(Scenarios.fenced(FIXED_DS3))
                        .expect(QuestionMatcher.configuration("XSLTFilter"))
                        .reply(Scenarios.fenced(FIXED_XSLT)));
        assertPromoted(fixed, FIXED_EVENTS);

        // One document, a learner per feed, and a binding for each learner.
        final List<Binding> table = List.of("DOOR-ACCESS", "APP-EVENTS", "FIREWALL", "MAINFRAME").stream()
                .map(feed -> {
                    assertThat(scenarios.learnerBindings.forLearner(learner(feed)))
                            .describedAs("a binding for " + feed + "'s learner, bound to what it learned (A124)")
                            .hasSize(1);
                    return scenarios.learnerBindings.forLearner(learner(feed)).get(0);
                })
                .toList();
        // None of the four streams is left waiting, because every record of every format cleared the bar.
        assertThat(scenarios.deferrals.forDocument(DOC))
                .describedAs("what phase 4 would have deferred")
                .isEmpty();
        assertThat(table).allSatisfy(binding -> {
            assertThat(binding.isDraft()).isFalse();
            assertThat(binding.getScore())
                    .describedAs("and each was promoted on a score, not on hope")
                    .isNotNull();
        });
    }

    /// A feed already learned is served without the model being asked again — for every format, not just
    /// the one the earlier scenarios happened to test.
    @Test
    void aFeedAlreadyLearnedIsServedWithoutAskingAgain() {
        final Scenarios scenarios = new Scenarios();
        final LearningFlowDoc doc = doc();
        learn(scenarios, doc, "APP-EVENTS", JSON_LINES,
                scenarios.jsonScript("root", JSON_XSLT)
                        .expect(QuestionMatcher.chain()).reply("JSONParser -> XSLTFilter")
                        .expect(QuestionMatcher.configuration("XSLTFilter"))
                        .reply(Scenarios.fenced(JSON_XSLT)));

        final Script silent = Script.of();
        final StageRun second = scenarios.process(silent, learner("APP-EVENTS"), doc,
                stream(9L, "APP-EVENTS", JSON_LINES)).learned();

        assertThat(silent.asked()).describedAs("nothing was asked: the binding serves it").isEmpty();
        assertThat(Scenarios.canonical(second.output()))
                .describedAs("and what it writes is what was learned")
                .isEqualTo(Scenarios.canonical(JSON_EVENTS));
        assertThat(scenarios.learnerBindings.forLearner(learner("APP-EVENTS"))).hasSize(1);
    }

    private static StageRun learn(final Scenarios scenarios,
                                  final LearningFlowDoc doc,
                                  final String feed,
                                  final String data,
                                  final Script script) {
        final long next = scenarios.learnerBindings.all().size() + 1L;
        final StageRun run = scenarios.process(script, learner(feed), doc, stream(next, feed, data)).learned();
        // Check every scripted reply was used. Otherwise a reply nobody reached would pass silently, and the
        // run would have asked something other than what this test says it asks.
        script.verifyExhausted();
        // The chain question offered every element the document allows. A scripted model can show that the
        // stage offers the whole list and shapes everything after it around the answer; which element a real
        // model picks can only be shown by a live run.
        assertThat(script.asked()).first()
                .isInstanceOfSatisfying(Chain.class, chain -> assertThat(chain.allowedElements())
                        .containsExactlyInAnyOrderElementsOf(DemoDocument.ALLOWED_ELEMENTS));
        return run;
    }

    private static void assertPromoted(final StageRun run, final String events) {
        assertThat(run.decision()).describedAs(run.decision().toString()).isInstanceOf(Promoted.class);
        assertThat(Scenarios.canonical(run.output())).isEqualTo(Scenarios.canonical(events));
    }

    /// Builds the demo document: the settings the content pack ships, taken from [DemoDocument], the one
    /// place they are defined, so that the pack cannot drift from this test.
    ///
    /// This test adds only a UUID and a name. No model is configured, because the script answers instead.
    private static LearningFlowDoc doc() {
        return DemoDocument.configure(Scenarios.document())
                .uuid(DOC)
                .name("any-feed")
                .build();
    }

    /// The learner of a feed's own pipeline.
    private static Learner learner(final String feed) {
        return new Learner("pipeline-" + feed, Scenarios.LEARNER.elementId());
    }

    private static Input stream(final long id, final String feed, final String data) {
        return new Input(id, feed, "Raw Events", Map.of(), data, learner(feed).pipelineUuid());
    }
}
