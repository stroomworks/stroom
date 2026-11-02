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

package stroom.shapeshifter.ai.extraction;

import stroom.shapeshifter.ai.extraction.Compilation.Compiled;
import stroom.shapeshifter.ai.extraction.Compilation.Rejected;
import stroom.shapeshifter.ai.extraction.ExtractionCorpus.Golden;
import stroom.shapeshifter.ai.learning.ConfigurationReply;
import stroom.shapeshifter.ai.learning.Templates;
import stroom.shapeshifter.ai.scenario.LiveAdvisor;
import stroom.shapeshifter.shared.Template;
import stroom.util.logging.AsciiTable;
import stroom.util.logging.AsciiTable.Column;
import stroom.util.shared.Severity;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The corpus inversion of design §9: withhold each golden configuration, give a model the input and the
 * expected output, and run what comes back through the same compile, run and diff as the calibration. The
 * result is a reconstruction rate for the extraction stage — the measurement ruling A8 was made without.
 * <p>
 * This needs a model, so it runs only when one is named in the environment: {@code SHAPESHIFTER_AI_BASE_URL}
 * and {@code SHAPESHIFTER_LEARNING_MODEL} for an OpenAI-compatible endpoint, and {@code SHAPESHIFTER_AI_API_KEY}
 * if it wants one. Samples go to that endpoint unredacted; ruling A17 governs production, not this
 * evaluation, so the endpoint should be one the corpus may be sent to.
 */
@EnabledIfEnvironmentVariable(named = LiveAdvisor.BASE_URL, matches = ".+")
class TestExtractionReconstruction {

    private static final String MODEL = LiveAdvisor.MODEL;

    private static final Logger LOGGER = LoggerFactory.getLogger(TestExtractionReconstruction.class);

    private static final NodeFixture FIXTURE = new NodeFixture();

    /**
     * Improvement attempts per case, each fed the previous attempt's failure. Transport failures are retried
     * by the client and do not count.
     */
    private static final int ATTEMPTS = 3;

    /**
     * The stage's own extraction question, so that this measures the words the stage will use (design 02
     * §6.2), plus what only this test needs: the expected records, and the reply grammar.
     */
    private static final String OBJECTIVE = """
            You write Stroom Data Splitter 3.0 configurations. Given a sample of raw input and the records \
            document it must produce, reply with a configuration that produces exactly that document.

            """ + Templates.builtIn(Template.EXTRACTION_RULES) + """

            Reply with the configuration as a single fenced XML code block and nothing else.
            """;

    @Test
    void reconstructsGoldenConfigurationsFromInputAndExpectedOutput() {
        final ChatModel model = LiveAdvisor.modelFromEnvironment().orElseThrow();

        final List<Golden> goldens = ExtractionCorpus.goldens();
        final List<Reconstruction> reconstructions = goldens.stream()
                .map(golden -> {
                    try {
                        return reconstruct(model, golden, workedExample(goldens, golden));
                    } catch (final RuntimeException e) {
                        LOGGER.warn("{} failed: {}", golden, e.toString());
                        return new Reconstruction(golden, Outcome.FAILED, 0, 0, 0, 0);
                    }
                })
                .toList();

        final long exact = reconstructions.stream().filter(r -> r.outcome() == Outcome.EXACT).count();
        LOGGER.info("Reconstruction of {} extraction cases by {}: {} exact\n{}",
                goldens.size(),
                System.getenv(MODEL),
                exact,
                AsciiTable.builder(reconstructions)
                        .withColumn(Column.of("Case", (Reconstruction r) -> r.golden().stem()))
                        .withColumn(Column.of("Outcome", (Reconstruction r) -> r.outcome().name()))
                        .withColumn(Column.integer("Attempts", (Reconstruction r) -> r.attempts()))
                        .withColumn(Column.of("Records", (Reconstruction r) ->
                                r.recordCount() + "/" + r.golden().expectedRecordCount()))
                        .withColumn(Column.decimal("Coverage", (Reconstruction r) -> r.coverage(), 3))
                        .withColumn(Column.integer("Tokens", (Reconstruction r) -> r.tokens()))
                        .build());

        assertThat(exact)
                .as("the loop reconstructed nothing at all, which points at the harness before the model")
                .isPositive();
    }

    private Reconstruction reconstruct(final ChatModel model, final Golden golden, final Golden example) {
        final List<ChatMessage> conversation = new ArrayList<>();
        conversation.add(SystemMessage.from(OBJECTIVE));
        conversation.add(UserMessage.from(
                "A worked example.\n\nInput:\n" + example.input()
                        + "\n\nExpected records:\n" + example.expectedRecords()
                        + "\n\nConfiguration:\n" + example.configuration()
                        + "\n\nNow this one.\n\nInput:\n" + golden.input()
                        + "\n\nExpected records:\n" + golden.expectedRecords()));

        Outcome outcome = Outcome.REFUSED;
        int recordCount = 0;
        double coverage = 0;
        long tokens = 0;
        int attempt = 0;
        while (attempt < ATTEMPTS && outcome != Outcome.EXACT) {
            attempt++;
            final ChatResponse response = model.chat(conversation);
            conversation.add(response.aiMessage());
            if (response.tokenUsage() != null) {
                tokens += response.tokenUsage().totalTokenCount();
            }

            final Attempt result = evaluate(golden, response.aiMessage());
            outcome = result.outcome();
            recordCount = result.recordCount();
            coverage = result.coverage();
            LOGGER.info("{} attempt {}: {}", golden, attempt, outcome);
            if (outcome != Outcome.EXACT) {
                conversation.add(UserMessage.from(result.feedback()));
            }
        }
        return new Reconstruction(golden, outcome, attempt, recordCount, coverage, tokens);
    }

    private Attempt evaluate(final Golden golden, final AiMessage reply) {
        final Optional<String> configuration = ConfigurationReply.configuration(reply.text());
        if (configuration.isEmpty()) {
            return new Attempt(Outcome.REFUSED, 0, 0,
                    "That reply was not a single configuration document. Reply with exactly one fenced "
                            + "XML code block containing the configuration and nothing else.");
        }

        final Compilation compilation = FIXTURE.compiler().compile(configuration.get());
        if (compilation instanceof final Rejected rejected) {
            return new Attempt(Outcome.REJECTED, 0, 0,
                    "The configuration was rejected:\n" + describe(rejected.diagnostics())
                            + "\n\nReply with a corrected configuration.");
        }

        final ExtractionResult result = DataSplitterRunner.run((Compiled) compilation, golden.input());
        final double charRatio = result.coverage(golden.input()).charRatio();
        if (result.records().equals(golden.expectedRecords())) {
            return new Attempt(Outcome.EXACT, result.recordCount(), charRatio, "");
        }
        final boolean errors = result.count(Severity.ERROR) + result.count(Severity.FATAL_ERROR) > 0;
        return new Attempt(
                errors
                        ? Outcome.ERRORS
                        : Outcome.DIFFERENT,
                result.recordCount(),
                charRatio,
                (errors
                        ? "Running the configuration raised errors:\n" + describe(result.diagnostics()) + "\n\n"
                        : "")
                        + "It produced " + result.recordCount() + " records covering "
                        + Math.round(charRatio * 100) + "% of the input, but the output differs from what "
                        + "is expected.\n\nIt produced:\n" + result.records()
                        + "\n\nExpected:\n" + golden.expectedRecords()
                        + "\n\nReply with a corrected configuration.");
    }

    /**
     * Every case is shown one other case, so the prompt leans on a worked example (design §4.1) without
     * ever being shown the answer.
     */
    private static Golden workedExample(final List<Golden> goldens, final Golden golden) {
        return goldens.stream()
                .filter(other -> !other.equals(golden))
                .findFirst()
                .orElseThrow();
    }

    private static String describe(final List<?> diagnostics) {
        return diagnostics.stream()
                .map(Object::toString)
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
    }

    /**
     * Ordered worst to best. The final outcome of a case is that of its last attempt.
     */
    private enum Outcome {
        REFUSED,
        REJECTED,
        ERRORS,
        /**
         * The run itself failed on this case — a request refused, a connection lost past the client's
         * retries — recorded so the other cases still count.
         */
        FAILED,
        DIFFERENT,
        EXACT
    }

    private record Attempt(Outcome outcome, int recordCount, double coverage, String feedback) {

    }

    private record Reconstruction(Golden golden,
                                  Outcome outcome,
                                  int attempts,
                                  int recordCount,
                                  double coverage,
                                  long tokens) {

    }
}
