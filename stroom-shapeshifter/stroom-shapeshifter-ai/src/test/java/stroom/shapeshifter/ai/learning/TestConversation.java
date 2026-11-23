/*
 * Copyright 2016-2026 Crown Copyright
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

import stroom.docref.DocRef;
import stroom.meta.shared.MetaFields;
import stroom.shapeshifter.ai.extraction.DataSplitterStep;
import stroom.shapeshifter.ai.extraction.ExtractionCorpus;
import stroom.shapeshifter.ai.extraction.ExtractionCorpus.Failing;
import stroom.shapeshifter.ai.extraction.ExtractionCorpus.Golden;
import stroom.shapeshifter.ai.extraction.NodeFixture;
import stroom.shapeshifter.ai.learning.Outcome.Abandoned;
import stroom.shapeshifter.ai.learning.Outcome.Learned;
import stroom.shapeshifter.ai.learning.Question.Chain;
import stroom.shapeshifter.ai.learning.Question.Configuration;
import stroom.shapeshifter.ai.scenario.Scenarios;
import stroom.shapeshifter.ai.scoring.CompileScorer;
import stroom.shapeshifter.ai.scoring.Scorecard;
import stroom.shapeshifter.ai.transformation.XsltStep;
import stroom.shapeshifter.shared.Check;
import stroom.shapeshifter.shared.CheckSetting;
import stroom.shapeshifter.shared.CheckState;
import stroom.shapeshifter.shared.ConfigureRole;
import stroom.shapeshifter.shared.FlowExample;
import stroom.shapeshifter.shared.FlowExit;
import stroom.shapeshifter.shared.FlowStep;
import stroom.shapeshifter.shared.LearningFlow;
import stroom.shapeshifter.shared.QuestionKind;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.shapeshifter.shared.StepOutcome;
import stroom.shapeshifter.shared.Transition;
import stroom.util.shared.ElementId;
import stroom.util.shared.Severity;
import stroom.util.shared.StoredError;
import stroom.xmlschema.shared.XmlSchemaDoc;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The A21 conversation end to end against a simulated model: a Shapeshifter AI document, a sample from
 * the {@code TestDS3} corpus, and canned replies that carry the corpus's own Data Splitter
 * configuration and a stylesheet that turns its records into {@code event-logging:3} events. What is
 * asserted is the order and content of the questions — that is the design — and the translation that
 * comes out of the end.
 */
class TestConversation {

    private static final NodeFixture FIXTURE = new NodeFixture();
    private static final Golden CSV = golden("001_csv_with_header");
    private static final Failing BAD_CSV = failing("008_invalid_xml_FAIL");
    private static final String XSLT = Scenarios.resource("csv-logon.xsl");
    private static final String EXPECTED_EVENTS = Scenarios.resource("csv-logon.events.xml");
    /**
     * The sample as the supervisor would build it: the text plus the stream's feed and type (A126).
     */
    private static final Sample SAMPLE = Sample.of(CSV.input(), "DOOR-ACCESS", "Raw Events");

    /**
     * These tests are about the conversation; the scorecard is the compile gate alone so that a step passes
     * when it runs. Scoring has its own tests and the scenarios.
     */
    private static Conversation conversation(final Advisor advisor) {
        // These tests are about A21's mechanics — the chain, the re-ask, the refusal, the budgets — so they
        // run the direct shape; the split and target turns of A31 have their own tests.
        return new Conversation(advisor, List.of(new DataSplitterStep(FIXTURE.compiler()), new XsltStep()),
                Scorecard.of(List.of(CheckSetting.of(Check.COMPILE, CheckState.GATE)),
                        List.of(new CompileScorer())), Clock.systemUTC());
    }

    private static ShapeshifterAiDoc policy() {
        // With `Direct`'s steps: a document built here has none of its own (Track 2 step 1c), and these
        // tests are about the conversation the steps drive.
        return ShapeshifterAiDoc.builder()
                .uuid("policy-1")
                .name("csv-logon")
                .flow(FlowExamples.flow(FlowExample.DIRECT))
                .build();
    }

    /**
     * A clock that jumps by a set amount every time it is read, so a question can be made to take as long
     * as a test needs.
     */
    private static Clock ticking(final long stepMs) {
        return new Clock() {
            private long now;

            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(final ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                now += stepMs;
                return Instant.ofEpochMilli(now);
            }
        };
    }

    @Test
    void aTargetThatIsNotEventLoggingHasNoFallbackSoASpentTransformStopsForAPerson() {
        // Task 7, W1-3: the fallback writes event-logging's marked Unknown, which another schema doesn't
        // have. Reached for one, the run stops for a person instead of asking for markup that can't conform
        final TargetSchema sightings = new TargetSchema(
                new DocRef(XmlSchemaDoc.TYPE, "schema-1", "sightings v2.4"), "sightings v2.4",
                "urn:sightings:2", "file://sightings-v2.4.xsd", "2.4", "Sightings", null);
        final CannedAdvisor model = new CannedAdvisor(
                "DSParser -> XSLTFilter",
                CannedAdvisor.fenced(CSV.configuration()),
                CannedAdvisor.fenced("<not a stylesheet"));
        final Conversation conversation = new Conversation(model,
                List.of(new DataSplitterStep(FIXTURE.compiler()), new XsltStep()),
                Scorecard.of(List.of(CheckSetting.of(Check.COMPILE, CheckState.GATE)),
                        List.of(new CompileScorer()), sightings), Clock.systemUTC());
        final ShapeshifterAiDoc doc = policy().copy()
                .flow(new LearningFlow(List.of(
                        FlowStep.of(QuestionKind.CHAIN),
                        FlowStep.builder(QuestionKind.CONFIGURE).role(ConfigureRole.PARSER).build(),
                        FlowStep.builder(QuestionKind.CONFIGURE)
                                .role(ConfigureRole.TRANSFORM)
                                .candidates(1)
                                .whenSpentGoTo("admit")
                                .build(),
                        FlowStep.builder(QuestionKind.ADMIT).candidates(1).build()), null, null))
                .build();

        final Outcome outcome = conversation.run(doc, SAMPLE);

        assertThat(outcome).isInstanceOf(Abandoned.class);
        final Abandoned abandoned = (Abandoned) outcome;
        assertThat(abandoned.exit()).isEqualTo(FlowExit.PARK);
        assertThat(abandoned.report().getError())
                .isEqualTo("Couldn't write the transform, and there is no fallback for sightings v2.4.");
        assertThat(abandoned.report().getDetails().getFirst()).contains("has no Unknown to mark a record with");
        assertThat(model.questions()).describedAs("the chain, the parser and the transform; no admission")
                .hasSize(3);
    }

    @Test
    void anAttemptThatOutrunsItsWallClockBudgetIsAbandoned() {
        // A5: the budget bounds the whole conversation. Every read of the clock moves it by a minute, so the
        // first question's check passes at one minute and the check after it fails at two.
        final CannedAdvisor model = new CannedAdvisor(
                "DSParser -> XSLTFilter",
                CannedAdvisor.fenced(CSV.configuration()),
                CannedAdvisor.fenced(XSLT));
        final Conversation conversation = new Conversation(model,
                List.of(new DataSplitterStep(FIXTURE.compiler()), new XsltStep()),
                Scorecard.of(List.of(CheckSetting.of(Check.COMPILE, CheckState.GATE)),
                        List.of(new CompileScorer())), ticking(60_000));

        final Outcome outcome = conversation.run(policy().copy().attemptBudgetMs(90_000).build(), SAMPLE);

        assertThat(outcome).isInstanceOf(Abandoned.class);
        assertThat(((Abandoned) outcome).reason())
                .isEqualTo("Ran out of time at \"Choose the elements\" (question 1).");
        assertThat(((Abandoned) outcome).report().getDetails())
                .containsExactly("Each run may take 1 minute 30 seconds; this one had taken 2 minutes.");
        assertThat(model.questions()).describedAs("the first question was asked; the second was not").hasSize(1);
        assertThat(outcome.transcript()).hasSize(1);
    }

    @Test
    void anAttemptThatOutrunsItsTokenBudgetIsAbandoned() {
        final CannedAdvisor model = new CannedAdvisor(
                "DSParser -> XSLTFilter",
                CannedAdvisor.fenced(CSV.configuration()),
                CannedAdvisor.fenced(XSLT)) {
            private long tokens;

            @Override
            public String ask(final List<Exchange> transcript, final Question question) {
                tokens += 700;
                return super.ask(transcript, question);
            }

            @Override
            public long tokensUsed() {
                return tokens;
            }
        };

        final Outcome outcome = conversation(model).run(policy().copy().tokenBudget(1000L).build(), SAMPLE);

        assertThat(outcome).isInstanceOf(Abandoned.class);
        assertThat(((Abandoned) outcome).reason()).startsWith("Ran out of tokens at ");
        assertThat(((Abandoned) outcome).report().getDetails())
                .containsExactly("A run may spend 1,000 tokens; this one had spent 1,400.");
        assertThat(model.questions()).hasSize(2);
    }

    @Test
    void asksTheChainThenEachConfigurationInChainOrderAndTranslatesTheSample() {
        final CannedAdvisor model = new CannedAdvisor(
                "DSParser -> XSLTFilter",
                CannedAdvisor.fenced(CSV.configuration()),
                CannedAdvisor.fenced(XSLT));

        final Outcome outcome = conversation(model).run(policy(), SAMPLE);

        assertThat(model.questions()).hasSize(3);
        final Chain chain = (Chain) model.questions().get(0);
        assertThat(chain.allowedElements())
                .describedAs("the document allows four; only the two with runners are offered")
                .containsExactly("DSParser", "XSLTFilter");
        assertThat(chain.sample().text()).isEqualTo(CSV.input());
        assertThat(chain.sample().headers())
                .describedAs("only the feed and type reach the model")
                .containsExactly(
                        Map.entry(MetaFields.FIELD_FEED, "DOOR-ACCESS"),
                        Map.entry(MetaFields.FIELD_TYPE, "Raw Events"));

        final Configuration parser = (Configuration) model.questions().get(1);
        assertThat(parser.elementType()).isEqualTo("DSParser");
        assertThat(parser.documentType()).isEqualTo("TextConverter");
        assertThat(parser.input())
                .describedAs("the first element is given the sample itself")
                .isEqualTo(CSV.input());
        assertThat(model.transcriptAt(1)).hasSize(1);

        final Configuration transform = (Configuration) model.questions().get(2);
        assertThat(transform.elementType()).isEqualTo("XSLTFilter");
        assertThat(transform.documentType()).isEqualTo("XSLT");
        assertThat(transform.input())
                .describedAs("the transform question carries the parser's real output, not a description")
                .isEqualTo(CSV.expectedRecords());
        assertThat(model.transcriptAt(2)).hasSize(2);

        assertThat(outcome).isInstanceOf(Learned.class);
        final Learned learned = (Learned) outcome;
        assertThat(learned.chain()).extracting(LearnedStep::elementType).containsExactly("DSParser", "XSLTFilter");
        assertThat(learned.chain().get(0).configuration()).isEqualTo(CSV.configuration());
        assertThat(learned.chain().get(1).configuration()).isEqualTo(XSLT);
        assertThat(learned.output()).isEqualTo(EXPECTED_EVENTS);
        assertThat(learned.transcript()).hasSize(3);
    }

    @Test
    void feedbackGoesToTheStepThatFailedAndTheChainIsNotReAsked() {
        final CannedAdvisor model = new CannedAdvisor(
                "DSParser, XSLTFilter",
                CannedAdvisor.fenced(BAD_CSV.configuration()),
                CannedAdvisor.fenced(CSV.configuration()),
                CannedAdvisor.fenced(XSLT));

        final Outcome outcome = conversation(model).run(policy(), SAMPLE);

        assertThat(outcome).isInstanceOf(Learned.class);
        assertThat(model.questions()).hasSize(4);
        final Configuration first = (Configuration) model.questions().get(1);
        final Configuration reAsk = (Configuration) model.questions().get(2);
        assertThat(first.feedback()).isEmpty();
        assertThat(reAsk.elementType()).isEqualTo("DSParser");
        assertThat(reAsk.previousConfiguration()).isEqualTo(BAD_CSV.configuration());
        assertThat(reAsk.feedback())
                .describedAs("the compile gate's diagnostics are the feedback")
                .isNotEmpty()
                .allMatch(error -> error.getSeverity().greaterThanOrEqual(Severity.ERROR));
        assertThat(model.questions().get(3)).isInstanceOf(Configuration.class);
    }

    @Test
    void aReplyThatIsNotADocumentIsRefusedWithFeedback() {
        final CannedAdvisor model = new CannedAdvisor(
                "DSParser, XSLTFilter",
                "I would suggest splitting on commas.",
                CannedAdvisor.fenced(CSV.configuration()),
                CannedAdvisor.fenced(XSLT));

        final Outcome outcome = conversation(model).run(policy(), SAMPLE);

        assertThat(outcome).isInstanceOf(Learned.class);
        final Configuration reAsk = (Configuration) model.questions().get(2);
        assertThat(reAsk.previousConfiguration()).isNull();
        assertThat(reAsk.feedback()).extracting(StoredError::getMessage)
                .singleElement()
                .asString()
                .contains("not a single TextConverter document");
    }

    @Test
    void aChainOutsideTheAllowedSetIsRefusedWithFeedback() {
        final CannedAdvisor model = new CannedAdvisor(
                "CombinedParser",
                "DSParser",
                CannedAdvisor.fenced(CSV.configuration()));

        final Outcome outcome = conversation(model).run(policy(), SAMPLE);

        assertThat(outcome).isInstanceOf(Learned.class);
        final Chain reAsk = (Chain) model.questions().get(1);
        assertThat(reAsk.feedback()).extracting(StoredError::getMessage)
                .singleElement()
                .asString()
                .contains("allowed element types");
        assertThat(((Learned) outcome).output()).isEqualTo(CSV.expectedRecords());
    }

    @Test
    void theChainQuestionIsSkippedWhenOneElementIsAllowed() {
        final CannedAdvisor model = new CannedAdvisor(CannedAdvisor.fenced(XSLT));
        final ShapeshifterAiDoc transformOnly = policy().copy().allowedElements(List.of("XSLTFilter")).build();

        final Outcome outcome = conversation(model).run(transformOnly, Sample.of(CSV.expectedRecords()));

        assertThat(model.questions()).singleElement().isInstanceOf(Configuration.class);
        assertThat(((Learned) outcome).output()).isEqualTo(EXPECTED_EVENTS);
    }

    @Test
    void abandonsAStepAfterMaxAttempts() {
        final CannedAdvisor model = new CannedAdvisor(
                "DSParser",
                CannedAdvisor.fenced(BAD_CSV.configuration()),
                CannedAdvisor.fenced(BAD_CSV.configuration()));
        final ShapeshifterAiDoc twoAttempts = policy().copy().maxAttempts(2).build();

        final Outcome outcome = conversation(model).run(twoAttempts, SAMPLE);

        assertThat(outcome).isInstanceOf(Abandoned.class);
        final Abandoned abandoned = (Abandoned) outcome;
        assertThat(abandoned.reason()).isEqualTo("Couldn't write the parser: all 2 answers fell short.");
        assertThat(abandoned.report().getDetails()).first().isEqualTo("It was writing DSParser.");
        assertThat(abandoned.diagnostics())
                .describedAs("the last try's diagnostics never reached a question, so they travel here")
                .isNotEmpty();
        assertThat(abandoned.transcript()).hasSize(3);
    }

    @Test
    void aPolicyAllowingNothingRunnableIsAbandonedBeforeAsking() {
        final CannedAdvisor model = new CannedAdvisor();
        final ShapeshifterAiDoc jsonOnly = policy().copy().allowedElements(List.of("JSONParser")).build();

        final Outcome outcome = conversation(model).run(jsonOnly, SAMPLE);

        assertThat(outcome).isInstanceOf(Abandoned.class);
        assertThat(((Abandoned) outcome).report().getDetails()).containsExactly("It allows: JSONParser.");
        assertThat(model.questions()).isEmpty();
    }

    @Test
    void aRunOnlyElementThatFailsAbandonsWithItsDiagnostics() {
        final StepRunner passThrough = new StepRunner() {
            @Override
            public String elementType() {
                return "XMLParser";
            }

            @Override
            public String elementId() {
                return "xmlParser";
            }

            @Override
            public Optional<Configured> configured() {
                return Optional.empty();
            }

            @Override
            public StepResult run(final String configuration, final String input) {
                return new StepResult(null, List.of(new StoredError(
                        Severity.FATAL_ERROR, null, new ElementId(elementId()), "not XML")));
            }
        };
        final CannedAdvisor model = new CannedAdvisor("XMLParser, XSLTFilter");
        final Conversation conversation = new Conversation(model, List.of(passThrough, new XsltStep()),
                Scorecard.of(List.of(), List.of()), Clock.systemUTC());

        // A run-only element with no runner in a node, named so that the conversation offers it
        final ShapeshifterAiDoc doc = policy().copy().allowedElements(List.of("XMLParser", "XSLTFilter")).build();
        final Outcome outcome = conversation.run(doc, SAMPLE);

        assertThat(outcome).isInstanceOf(Abandoned.class);
        final Abandoned abandoned = (Abandoned) outcome;
        assertThat(abandoned.reason()).endsWith(": XMLParser failed on its input.");
        assertThat(abandoned.diagnostics()).extracting(StoredError::getMessage).containsExactly("not XML");
        assertThat(model.questions()).hasSize(1);
    }

    @Test
    void aRunOnlyElementThatFailsIsARunFailureAndItsStepMayGoOnSpent() {
        // No configuration was asked for, so nothing failed to compile: the outcome is run-failed, and the one
        // run is the step's only candidate, so 'on spent' is where the flow says to go from it.
        final StepRunner failing = new StepRunner() {
            @Override
            public String elementType() {
                return "XMLParser";
            }

            @Override
            public String elementId() {
                return "xmlParser";
            }

            @Override
            public Optional<Configured> configured() {
                return Optional.empty();
            }

            @Override
            public boolean parser() {
                return true;
            }

            @Override
            public StepResult run(final String configuration, final String input) {
                return new StepResult(null, List.of(new StoredError(
                        Severity.FATAL_ERROR, null, new ElementId(elementId()), "not XML")));
            }
        };
        // Either rule takes the flow to 'end': the one that fires on the failure, and the one that fires
        // when the step's candidates are spent.
        for (final Transition transition : List.of(new Transition(StepOutcome.RUN_FAILED, Transition.END),
                new Transition(null, Transition.END))) {
            final CannedAdvisor model = new CannedAdvisor("XMLParser, XSLTFilter");
            final ShapeshifterAiDoc doc = policy().copy().flow(FlowExamples.flow(FlowExample.DIRECT).withSteps(List.of(
                    FlowStep.builder(QuestionKind.CHAIN).elements("XMLParser", "XSLTFilter").build(),
                    new FlowStep(null, QuestionKind.CONFIGURE, ConfigureRole.PARSER, null, null, null,
                            List.of(transition)),
                    FlowStep.builder(QuestionKind.CONFIGURE).role(ConfigureRole.TRANSFORM).build()))).build();
            final Conversation conversation = new Conversation(model, List.of(failing, new XsltStep()),
                    Scorecard.of(List.of(), List.of()), Clock.systemUTC());

            final Outcome outcome = conversation.run(doc, SAMPLE);

            // The transition fired: the flow ended at 'end', where the unconfigured elements are found out,
            // rather than abandoning at the parser.
            assertThat(outcome).describedAs(transition.toString()).isInstanceOf(Abandoned.class);
            assertThat(((Abandoned) outcome).reason()).describedAs(transition.toString())
                    .startsWith("The learning steps ended without writing ");
        }
        final CannedAdvisor model = new CannedAdvisor("XMLParser, XSLTFilter");
        final ShapeshifterAiDoc doc = policy().copy().flow(FlowExamples.flow(FlowExample.DIRECT).withSteps(List.of(
                FlowStep.builder(QuestionKind.CHAIN).elements("XMLParser", "XSLTFilter").build(),
                FlowStep.builder(QuestionKind.CONFIGURE)
                        .role(ConfigureRole.PARSER)
                        .goTo(StepOutcome.COMPILE_FAILED, "end")
                        .build(),
                FlowStep.builder(QuestionKind.CONFIGURE).role(ConfigureRole.TRANSFORM).build()))).build();
        final Outcome outcome = new Conversation(model, List.of(failing, new XsltStep()),
                Scorecard.of(List.of(), List.of()), Clock.systemUTC()).run(doc, SAMPLE);
        assertThat(((Abandoned) outcome).reason()).describedAs("nothing was compiled, so compile-failed is not it")
                .isEqualTo("Couldn't write the parser: XMLParser failed on its input.");
    }

    @Test
    void feedbackCarriedToARunOnlyElementReachesTheNextQuestionAsked() {
        // A goto lands on a CONFIGURE step whose element takes no configuration: nothing is asked there, so
        // what the transition carried is kept for the transform, which is asked next.
        final StepRunner passThrough = new StepRunner() {
            @Override
            public String elementType() {
                return "XMLParser";
            }

            @Override
            public String elementId() {
                return "xmlParser";
            }

            @Override
            public Optional<Configured> configured() {
                return Optional.empty();
            }

            @Override
            public boolean parser() {
                return true;
            }

            @Override
            public StepResult run(final String configuration, final String input) {
                return new StepResult("<records xmlns=\"records:2\"><record/></records>", List.of());
            }
        };
        final CannedAdvisor model = new CannedAdvisor("XMLParser, XSLTFilter", "not a stylesheet",
                Scenarios.fenced(XSLT));
        final ShapeshifterAiDoc doc = policy().copy().flow(FlowExamples.flow(FlowExample.DIRECT).withSteps(List.of(
                FlowStep.builder(QuestionKind.CHAIN).elements("XMLParser", "XSLTFilter").build(),
                FlowStep.builder(QuestionKind.CONFIGURE).role(ConfigureRole.PARSER).build(),
                FlowStep.builder(QuestionKind.CONFIGURE)
                        .role(ConfigureRole.TRANSFORM)
                        .goTo(StepOutcome.REFUSED, "parser")
                        .build()))).build();
        final Conversation conversation = new Conversation(model, List.of(passThrough, new XsltStep()),
                Scorecard.of(List.of(), List.of()), Clock.systemUTC());

        final Outcome outcome = conversation.run(doc, SAMPLE);

        assertThat(outcome).describedAs(outcome.toString()).isInstanceOf(Learned.class);
        assertThat(model.questions()).hasSize(3);
        assertThat(model.questions().get(2).feedback()).extracting(StoredError::getMessage)
                .anyMatch(message -> message.contains("was not a single XSLT document"));
    }

    private static Golden golden(final String stem) {
        return ExtractionCorpus.goldens().stream()
                .filter(golden -> golden.stem().equals(stem))
                .findFirst()
                .orElseThrow();
    }

    private static Failing failing(final String stem) {
        return ExtractionCorpus.failing().stream()
                .filter(failing -> failing.stem().equals(stem))
                .findFirst()
                .orElseThrow();
    }

}
