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

import stroom.shapeshifter.ai.extraction.ExtractionCorpus.Golden;
import stroom.shapeshifter.ai.learning.Question.Chain;
import stroom.shapeshifter.ai.pack.DemoDocument;
import stroom.shapeshifter.ai.stage.Decision.Promoted;
import stroom.shapeshifter.ai.stage.Input;
import stroom.shapeshifter.ai.stage.Learner;
import stroom.shapeshifter.ai.stage.StageRun;
import stroom.shapeshifter.shared.Binding;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// Design 02 §5, scenario 51: **one document, any format, any feed.**
///
/// Every format scenario before this one narrows `allowedElements` to the single parser its format
/// needs — `DSParser` for the text formats, `JSONParser` for JSON — so none of them asks the question
/// this one does: given every parser it might use, does the stage *choose* the right one for the feed in
/// front of it, and learn something that scores?
///
/// That is what a person setting this up actually has. They do not know what the next feed will be, so
/// they cannot narrow the list; they configure one document and point feeds at it. This scenario is
/// that document, and it is the configuration the demo content pack ships — proved here first, so the
/// pack is a thing that has been seen to work rather than a plausible arrangement of settings.
///
/// Four feeds, four formats, four pipelines, four bindings, each serving its own traffic: delimited text
/// with a header, JSON lines, syslog in two forms, and fixed-width columns with nothing to split on.
/// Each feed has its pipeline, as the Pipeline Factory makes them, and so its learner (A114, A127); one
/// document, one flow, one set of scorers.
///
/// **What a scripted model can and cannot prove.** The replies here are canned, so the *choice* of
/// parser is this scenario's and not a model's. What is proved is everything around that choice: that
/// the chain question offers the whole list, that the split question is shaped for the input kind that
/// follows from it, that a `JSONParser` is asked for no configuration while a `DSParser` is, that one
/// scorer set judges all four, and that each promotes on its own evidence and writes its format's
/// golden events. Whether a model picks well from that list is the live run's to answer (design 03
/// §2, phase B), and this scenario is what makes that run worth doing.
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

    /// Each feed learns its own binding, and each binding writes the format's golden events.
    ///
    /// The document is the same one throughout — each feed's learner binds its own binding (A124) and each
    /// serves its own feed — so what is proved is not four documents that each work but *one* that works
    /// four times over, on inputs that share nothing but the schema they must end at.
    @Test
    void oneDocumentLearnsEveryFormatItIsPointedAt() {
        final Scenarios scenarios = new Scenarios();
        final ShapeshifterAiDoc doc = doc();

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
        // And none of the four would have waited (design 04 §7, phase 2): every record of every format
        // cleared the bar, so no stream is left waiting once its task is decided again.
        assertThat(scenarios.deferrals.forDocument(DOC))
                .describedAs("what phase 4 would have deferred")
                .isEmpty();
        assertThat(table).allSatisfy(binding -> {
            assertThat(binding.isDraft()).isFalse();
            assertThat(binding.isProvisional())
                    .describedAs("each brought enough records to be judged on, so none is provisional")
                    .isFalse();
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
        final ShapeshifterAiDoc doc = doc();
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
                                  final ShapeshifterAiDoc doc,
                                  final String feed,
                                  final String data,
                                  final Script script) {
        final long next = scenarios.learnerBindings.all().size() + 1L;
        final StageRun run = scenarios.process(script, learner(feed), doc, stream(next, feed, data)).learned();
        // Exhausted, or a scripted reply nobody reached would pass in silence and the run would have
        // asked something other than what this scenario says it asks.
        script.verifyExhausted();
        // And the chain question carried every parser the document allows. This is the part a scripted
        // model can prove: that the stage *offers* the whole list and shapes everything after it around
        // the answer. Which one it picks is the model's judgement, and only a live run proves that.
        assertThat(script.asked()).first()
                .isInstanceOfSatisfying(Chain.class, chain -> assertThat(chain.allowedElements())
                        .containsExactlyInAnyOrderElementsOf(DemoDocument.ALLOWED_ELEMENTS));
        return run;
    }

    private static void assertPromoted(final StageRun run, final String events) {
        assertThat(run.decision()).describedAs(run.decision().toString()).isInstanceOf(Promoted.class);
        assertThat(Scenarios.canonical(run.output())).isEqualTo(Scenarios.canonical(events));
    }

    /// **The demo document**: the settings the content pack ships, taken from the one place they are
    /// written down so that the pack cannot drift from the scenario that proves it.
    ///
    /// What this test adds to [DemoDocument] is the two things a scenario needs and a pack does not:
    /// an identity, and no model — the script answers instead.
    private static ShapeshifterAiDoc doc() {
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
