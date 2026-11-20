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
import stroom.shapeshifter.ai.stage.Decision.Bound;
import stroom.shapeshifter.ai.stage.Decision.Promoted;
import stroom.shapeshifter.ai.stage.Deferrals.Deferral;
import stroom.shapeshifter.ai.stage.Input;
import stroom.shapeshifter.ai.stage.Stage;
import stroom.shapeshifter.ai.stage.StageRun;
import stroom.shapeshifter.ai.state.InMemoryOutputs;
import stroom.shapeshifter.shared.Binding;
import stroom.shapeshifter.shared.Check;
import stroom.shapeshifter.shared.CheckSetting;
import stroom.shapeshifter.shared.CheckState;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Track 1 task 2, fix 1: **the stream the translation stopped reading**
/// (`docs/shapeshifter-ai-parser-drift-not-detected.md`).
///
/// Every other check judges what came out. A record the parser cannot read becomes no event, so nothing
/// judges it — and the stream is recorded as clean, at the score the translation had on the day it was
/// promoted. That is how the dev instance's NETFILTER feed went on reporting success after its timestamp
/// format changed and it had stopped producing anything at all.
///
/// A serving run now says what went **in** as well as what came out: the records the parser cut, the
/// errors it reported, and whether the stream held anything. What is done about it is the Learning
/// Flow's to say, through the same checks that judge a candidate.
class TestScenario64TheStreamTheTranslationStoppedReading {

    private static final String DOC = "doc-1";
    /// How many times one stream may open a session against the same translation (`Stage`).
    private static final int MOST_SESSIONS = 3;
    private static final String FEED = "DOOR-ACCESS";

    /// A headerless CSV of exactly four fields; any other line is unmatched content.
    private static final String FIELDS = Scenarios.resource("csv-fields.ds3.xml");
    private static final String TRANSLATES_BOTH = Scenarios.resource("csv-logon.xsl");

    /// A transform that writes an event for a logon and nothing at all for anything else: the records it
    /// drops leave no trace in the output for a check of what came out to find.
    private static final String LOGONS_ONLY_SILENTLY = """
            <?xml version="1.0" encoding="UTF-8" ?>
            <xsl:stylesheet
                xmlns="event-logging:3"
                xpath-default-namespace="records:2"
                xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                version="2.0">
              <xsl:template match="records">
                <Events xsi:schemaLocation="event-logging:3 file://event-logging-v3.0.0.xsd" Version="3.0.0">
                  <xsl:apply-templates/>
                </Events>
              </xsl:template>
              <xsl:template match="record[data[@name='what']/@value != 'logon']"/>
              <xsl:template match="record">
                <Event>
                  <EventTime>
                    <TimeCreated><xsl:value-of select="data[@name='dt']/@value"/></TimeCreated>
                  </EventTime>
                  <EventSource>
                    <System><Name>Door Access</Name><Environment>Test</Environment></System>
                    <Generator>CSV</Generator>
                    <Device><Name><xsl:value-of select="data[@name='where']/@value"/></Name></Device>
                  </EventSource>
                  <EventDetail>
                    <TypeId>logon</TypeId>
                    <Authenticate>
                      <Action>Logon</Action>
                      <User><Id><xsl:value-of select="data[@name='who']/@value"/></Id></User>
                    </Authenticate>
                  </EventDetail>
                </Event>
              </xsl:template>
            </xsl:stylesheet>""";

    /// Six records the learned parser reads.
    private static final String CLEAN = lines(6, ",");
    /// The same six records after the feed changed to pipes: the parser matches none of them.
    private static final String DRIFT = lines(6, "|");
    /// A feed part-way through a change: four lines the parser still reads, two it does not.
    private static final String PARTLY_DRIFTED = lines(4, ",") + lines(2, "|");

    @Test
    void aStreamTheParserCanNoLongerReadIsDeferredRatherThanRecordedAsClean() {
        final Scenarios scenarios = new Scenarios();
        final ShapeshifterAiDoc doc = doc();
        final Binding binding = learn(scenarios, doc);
        final Script silent = Script.of();

        final StageRun bound = task(scenarios, silent).decide(doc, Scenarios.LEARNER, stream(2L, DRIFT));
        assertThat(bound.decision()).describedAs("the binding still binds it: nothing has run yet")
                .isInstanceOf(Bound.class);

        assertThatThrownBy(() -> task(scenarios, silent).stream(doc, Scenarios.LEARNER, binding,
                stream(2L, DRIFT), new TextSink()))
                .describedAs("the task ends deferred, and what the downstream was given is discarded")
                .isInstanceOf(stroom.processor.api.DeferTaskException.class);

        assertThat(scenarios.deferrals.of(Scenarios.LEARNER, 2L)).describedAs("the stream waits").isPresent();
        assertThat(scenarios.deferrals.of(Scenarios.LEARNER, 2L).orElseThrow().reason())
                .contains("The parser read no records from this stream");
        assertThat(scenarios.states.relearnReason(Scenarios.LEARNER))
                .describedAs("and the learner is marked to learn the new format")
                .isPresent();
        assertThat(scenarios.outputs.emitted()).extracting(InMemoryOutputs.Emitted::inputId)
                .describedAs("nothing is recorded as having been produced from it")
                .doesNotContain(2L);
    }

    @Test
    void recordsThatBecameNeitherAnEventNorADeclaredSkipDeferToo() {
        // The quieter half of the same fault: the parser reads every record, and the translation turns
        // only some of them into events. Completeness is the check that sees it, and it is a gate.
        final Scenarios scenarios = new Scenarios();
        final ShapeshifterAiDoc doc = doc();
        final Binding binding = learn(scenarios, doc, LOGONS_ONLY_SILENTLY);
        final Script silent = Script.of();

        assertThatThrownBy(() -> task(scenarios, silent).stream(doc, Scenarios.LEARNER, binding,
                stream(2L, withLogoffs()), new TextSink()))
                .isInstanceOf(stroom.processor.api.DeferTaskException.class);

        assertThat(scenarios.deferrals.of(Scenarios.LEARNER, 2L).orElseThrow().reason())
                .contains("became neither an event nor a declared skip");
    }

    @Test
    void aCleanStreamIsStillServedAndItsRowHoldsThisRunsScore() {
        // The regression this fix could most easily cause: a healthy stream deferred, which costs a
        // relearning on every stream of a working feed
        final Scenarios scenarios = new Scenarios();
        final ShapeshifterAiDoc doc = doc();
        final Binding binding = learn(scenarios, doc);
        final TextSink sink = new TextSink();

        assertThat(task(scenarios, Script.of()).stream(doc, Scenarios.LEARNER, binding, stream(2L, CLEAN), sink)
                .clear()).isTrue();

        assertThat(sink.records()).isEqualTo(6);
        assertThat(scenarios.deferrals.of(Scenarios.LEARNER, 2L)).describedAs("nothing waits").isEmpty();
        assertThat(scenarios.outputs.emitted())
                .describedAs("the stream is recorded as served, beside the one it was learned from")
                .extracting(InMemoryOutputs.Emitted::inputId).contains(2L);
    }

    @Test
    void aFeedTheParserHasStartedReadingBadlyIsHeldBackWhereCoverageIsAGate() {
        // Short of total failure, it is the Learning Flow that decides. Four of the six lines still
        // match, so the records that were read all became events and completeness is satisfied; what
        // sees the two lines that were not read is input coverage.
        final Scenarios scenarios = new Scenarios();
        final ShapeshifterAiDoc doc = doc().copy()
                .checks(List.of(new CheckSetting(Check.COVERAGE, CheckState.GATE, 0.9, null)))
                .build();
        final Binding binding = learn(scenarios, doc);

        assertThatThrownBy(() -> task(scenarios, Script.of()).stream(doc, Scenarios.LEARNER, binding,
                stream(2L, PARTLY_DRIFTED), new TextSink()))
                .isInstanceOf(stroom.processor.api.DeferTaskException.class);

        assertThat(scenarios.deferrals.of(Scenarios.LEARNER, 2L).orElseThrow().reason())
                .contains("it could not")
                .contains("67%")
                .contains("90%");
    }

    @Test
    void andIsServedAnywayWhereTheLearningFlowMakesCoverageAdvice() {
        // The one model of checks decides this, not the code: what refuses a candidate while it is
        // learned is what refuses a stream while it is served. A document that has turned input coverage
        // down to advice is asking to go on serving a feed its parser reads badly — which is what
        // *Lenient* asks for — and its rolling score falls instead.
        final Scenarios scenarios = new Scenarios();
        final ShapeshifterAiDoc doc = doc().copy()
                .checks(List.of(new CheckSetting(Check.COVERAGE, CheckState.ADVICE, 0.9, null)))
                .build();
        final Binding binding = learn(scenarios, doc);

        assertThat(task(scenarios, Script.of()).stream(doc, Scenarios.LEARNER, binding,
                stream(2L, PARTLY_DRIFTED), new TextSink()).records())
                .describedAs("the four records it could still read are served")
                .isEqualTo(4);

        assertThat(scenarios.deferrals.of(Scenarios.LEARNER, 2L))
                .describedAs("advice does not hold a stream back")
                .isEmpty();
    }

    @Test
    void aStreamStopsOpeningPaidSessionsOnceTheTranslationHasNotMoved() {
        // Audit finding W4-5. The corpus's no-worse rule can keep every candidate out — a feed whose
        // format has changed for good needs a translation that reads both, and may not get one — and the
        // deferred task retries every five minutes for as long as the stream waits.
        final Scenarios scenarios = new Scenarios();
        final ShapeshifterAiDoc doc = doc();
        final Binding binding = learn(scenarios, doc);
        final Script silent = Script.of();

        for (int retry = 0; retry < 5; retry++) {
            final int number = retry;
            assertThatThrownBy(() -> task(scenarios, silent).stream(doc, Scenarios.LEARNER, binding,
                    stream(2L, DRIFT), new TextSink()))
                    .describedAs("retry " + number)
                    .isInstanceOf(stroom.processor.api.DeferTaskException.class)
                    .satisfies(thrown -> {
                        if (number >= MOST_SESSIONS) {
                            assertThat(thrown.getMessage()).contains("waits for a person");
                        }
                    });
        }

        final Deferral waiting = scenarios.deferrals.of(Scenarios.LEARNER, 2L).orElseThrow();
        assertThat(waiting.attempts()).describedAs("every retry is counted").isEqualTo(5);
        assertThat(scenarios.attempts.forDocument(DOC, 20))
                .describedAs("one session for the first learning, and one for the drift: not one per retry")
                .hasSize(2);
    }

    private static Stage task(final Scenarios scenarios, final Script silent) {
        return scenarios.stage(document -> silent, scenarios.learnerBindings);
    }

    private static Binding learn(final Scenarios scenarios, final ShapeshifterAiDoc doc) {
        return learn(scenarios, doc, TRANSLATES_BOTH);
    }

    private static Binding learn(final Scenarios scenarios, final ShapeshifterAiDoc doc, final String xslt) {
        final Script script = scenarios.script(FIELDS, xslt)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(FIELDS))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(xslt));
        final StageRun run = scenarios.process(script, doc, stream(1L, CLEAN)).learned();
        script.verifyExhausted();
        assertThat(run.decision()).describedAs(run.decision().toString()).isInstanceOf(Promoted.class);
        return ((Promoted) run.decision()).binding();
    }

    private static ShapeshifterAiDoc doc() {
        return DemoDocument.configure(Scenarios.document()).uuid(DOC).name("door").heldOutFraction(0.0).build();
    }

    /// @param count     How many lines.
    /// @param delimiter What separates the fields: a comma is what the parser learned.
    /// @return The stream.
    private static String lines(final int count, final String delimiter) {
        final StringBuilder text = new StringBuilder();
        for (int i = 0; i < count; i++) {
            text.append("2020-06-17T08:").append(String.format("%02d", i)).append(":00.000Z")
                    .append(delimiter).append("user").append(i)
                    .append(delimiter).append("office")
                    .append(delimiter).append("logon").append('\n');
        }
        return text.toString();
    }

    /// Six records the parser reads in full, of which two are logoffs: a translation that writes events
    /// for logons alone leaves those two as nothing at all.
    private static String withLogoffs() {
        return lines(4, ",") + "2020-06-17T08:04:00.000Z,user4,office,logoff\n"
               + "2020-06-17T08:05:00.000Z,user5,office,logoff\n";
    }

    private static Input stream(final long id, final String data) {
        return new Input(id, FEED, "Raw Events", Map.of(), data, "pipeline-1");
    }
}
