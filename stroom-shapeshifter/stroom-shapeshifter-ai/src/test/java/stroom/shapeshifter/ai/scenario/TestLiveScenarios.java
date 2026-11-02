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

package stroom.shapeshifter.ai.scenario;

import stroom.shapeshifter.ai.extraction.ExtractionCorpus.Golden;
import stroom.shapeshifter.ai.scenario.LiveAdvisor.Turn;
import stroom.shapeshifter.ai.stage.Decision;
import stroom.shapeshifter.ai.stage.Decision.Bound;
import stroom.shapeshifter.ai.stage.Decision.GivenUp;
import stroom.shapeshifter.ai.stage.Decision.Kept;
import stroom.shapeshifter.ai.stage.Decision.Promoted;
import stroom.shapeshifter.ai.stage.Decision.Provisional;
import stroom.shapeshifter.ai.stage.Decision.Rebound;
import stroom.shapeshifter.ai.stage.Input;
import stroom.shapeshifter.ai.stage.StageRun;
import stroom.shapeshifter.shared.BusinessRulesParameters;
import stroom.shapeshifter.shared.ExtractionQualityParameters;
import stroom.shapeshifter.shared.FlowExample;
import stroom.shapeshifter.shared.LearningMode;
import stroom.shapeshifter.shared.SchemaConformanceParameters;
import stroom.shapeshifter.shared.ScorerSetting;
import stroom.shapeshifter.shared.ScorerType;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.shapeshifter.shared.XPathAssertion;
import stroom.shapeshifter.shared.YieldBasis;
import stroom.shapeshifter.shared.YieldParameters;
import stroom.util.logging.AsciiTable;
import stroom.util.logging.AsciiTable.Column;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The live smoke of design 02 §6.1: the scenarios' machinery driven by a real model in place of a
 * script, to learn what no script can say — whether the questions as put draw replies the grammar
 * accepts, whether the feedback steers a second candidate to a passing one within the candidate limit,
 * and what an attempt costs. The results are a report, not a verdict: this test fails only if the
 * harness itself breaks. It runs only when the environment names an endpoint (see {@link LiveAdvisor}),
 * and writes each run's transcript under {@code build/live/<flow>} for the write-up.
 */
@EnabledIfEnvironmentVariable(named = LiveAdvisor.BASE_URL, matches = ".+")
class TestLiveScenarios {

    private static final Logger LOGGER = LoggerFactory.getLogger(TestLiveScenarios.class);
    /**
     * The candidate limit for the run, to measure how many attempts a strict schema costs a model; the
     * document's default when unset.
     */
    private static final String MAX_ATTEMPTS = "SHAPESHIFTER_LIVE_MAX_ATTEMPTS";
    /**
     * The example flow the run's documents carry as their steps — {@code DIRECT} (A21), {@code TARGET_FIRST}
     * (A31) or {@code ESCALATING} (A37) — so that flows can be compared on the same model and feeds; a new
     * document's when unset.
     */
    private static final String FLOW = "SHAPESHIFTER_LIVE_FLOW";
    /**
     * Which rows to run, as a regular expression over the row names ({@code 0[89]|1[0-4]} for the phase B
     * formats); every row when unset. A row not run is not in the report.
     */
    private static final String ROWS = "SHAPESHIFTER_LIVE_ROWS";
    private static final Pattern ROWS_UNDER_TEST = Pattern.compile(Optional.ofNullable(System.getenv(ROWS))
            .filter(rows -> !rows.isBlank())
            .orElse(".*"));
    // The variable's name before A81 is still read, so that an env file written then still names the flow.
    private static final FlowExample FLOW_UNDER_TEST = Optional.ofNullable(System.getenv(FLOW))
            .or(() -> Optional.ofNullable(System.getenv("SHAPESHIFTER_LIVE_PLAN")))
            .map(FlowExample::valueOf)
            .orElse(FlowExample.DIRECT);
    private static final Path OUT = Paths.get("build", "live", FLOW_UNDER_TEST.name().toLowerCase());
    private static final Golden CSV = Scenarios.corpus("001_csv_with_header");
    private static final Golden REGEX = Scenarios.corpus("004_simple_regex");
    private static final Golden MULTI_LINE = Scenarios.corpus("003_multiline_regex");
    private static final String NESTED_XML = Scenarios.resource("nested-audit.xml");
    private static final String SYSLOG = Scenarios.resource("syslog.log");
    private static final String AUDITD = Scenarios.resource("auditd.log");
    private static final String WINDOWS = Scenarios.resource("windows-security.xml");
    private static final String JSON_LINES = Scenarios.resource("records.jsonl");
    private static final String JSON_DOCUMENT = Scenarios.resource("records.json");
    private static final String FIXED_WIDTH = Scenarios.resource("fixed-width.log");
    private static final String CSV_MULTILINE = Scenarios.resource("csv-multiline.csv");
    private static final String INSTRUCTIONS = """
            The feed is door-access records from a building's badge readers: who went where and what \
            they did, when. Events should name the person as the user and the reader's location as \
            the device.""";

    /**
     * The document as an operator would set it up for a feed like this: the full scorer set, the fields the
     * business needs, and the floor at its default.
     */
    private static ShapeshifterAiDoc doc(final String name, final double coverageThreshold) {
        final ShapeshifterAiDoc.Builder builder = Scenarios.document()
                .uuid("live-" + name)
                .name(name)
                .learningMode(LearningMode.AUTOMATIC)
                .flow(FLOW_UNDER_TEST)
                .allowedElements(List.of("DSParser", "XSLTFilter"))
                .instructions(INSTRUCTIONS)
                .minRecordsToLearn(5)
                .promotionFloor(0.85);
        Optional.ofNullable(System.getenv(MAX_ATTEMPTS)).map(Integer::valueOf).ifPresent(builder::maxAttempts);
        return builder
                .scorers(List.of(
                        new ScorerSetting(ScorerType.COMPILE, 0.0, 1.0, true, null),
                        new ScorerSetting(ScorerType.INPUT_COVERAGE, 1.0, coverageThreshold, false, null),
                        new ScorerSetting(ScorerType.YIELD, 1.0, 0.5, false,
                                new YieldParameters(1.0, YieldBasis.RECORDS)),
                        new ScorerSetting(ScorerType.SCHEMA_CONFORMANCE, 1.0, 1.0, true,
                                new SchemaConformanceParameters("EVENTS")),
                        new ScorerSetting(ScorerType.EXTRACTION_QUALITY, 1.0, 0.7, true,
                                new ExtractionQualityParameters(false,
                                        List.of("EventSource/User/Id", "EventSource/Device/Name"))),
                        new ScorerSetting(ScorerType.BUSINESS_RULES, 1.0, 0.5, false,
                                new BusinessRulesParameters(List.of(new XPathAssertion(
                                        "interactive events name the user",
                                        "not(EventDetail/Authenticate) "
                                        + "or EventDetail/Authenticate/User/Id[normalize-space(.) != '']")),
                                        true))))
                .build();
    }

    /**
     * The scorers with the yield setting replaced: a feed whose record spans several lines says so by its
     * expected yield per line (design 02 scenario 44).
     */
    private static List<ScorerSetting> withYield(final List<ScorerSetting> scorers,
                                                 final YieldBasis basis,
                                                 final double expected,
                                                 final double threshold) {
        return scorers.stream()
                .map(setting -> setting.getType() == ScorerType.YIELD
                        ? new ScorerSetting(ScorerType.YIELD, setting.getWeight(), threshold, false,
                                new YieldParameters(expected, basis))
                        : setting)
                .toList();
    }

    /**
     * The scorers with one more business rule beside the standing one.
     */
    private static List<ScorerSetting> withRule(final List<ScorerSetting> scorers, final XPathAssertion rule) {
        return scorers.stream()
                .map(setting -> setting.getType() == ScorerType.BUSINESS_RULES
                        ? new ScorerSetting(ScorerType.BUSINESS_RULES, setting.getWeight(), setting.getThreshold(),
                                setting.isGate(), new BusinessRulesParameters(Stream.concat(
                                        ((BusinessRulesParameters) setting.getParameters()).getAssertions().stream(),
                                        Stream.of(rule)).toList(), true))
                        : setting)
                .toList();
    }

    private static Input stream(final long id, final String data) {
        return new Input(id, "DOOR-ACCESS", "Raw Events", Map.of("Format", "CSV"), data);
    }

    @Test
    void theScenariosAgainstARealModel() throws IOException {
        Files.createDirectories(OUT);
        final List<Outcome> outcomes = new ArrayList<>();

        // Scenario 1: the corpus's CSV with its header line, the default key.
        run("01-csv-with-header", advisor -> {
            final Scenarios scenarios = new Scenarios();
            return List.of(scenarios.process(advisor, doc("csv-with-header", 0.8), stream(1, CSV.input())).learned());
        }).ifPresent(outcomes::add);

        // A headerless CSV against the full scorer set: does a first candidate mean something, or does
        // it fall into the degeneracy trap and get steered out of it?
        run("02-csv-meaning", advisor -> {
            final Scenarios scenarios = new Scenarios();
            return List.of(scenarios.process(advisor, doc("csv-meaning", 0.9), stream(1, CsvLines.lines(7))).learned());
        }).ifPresent(outcomes::add);

        // Two record kinds in one stream, coverage at 0.9: the splitter must take both.
        run("03-two-record-kinds", advisor -> {
            final Scenarios scenarios = new Scenarios();
            return List.of(scenarios.process(advisor, doc("two-kinds", 0.9),
                    stream(1, CsvLines.lines(20, "user", 3))).learned());
        }).ifPresent(outcomes::add);

        // Scenario 27 live: learn the plain feed, watch the score fall when alarms appear, relearn.
        run("04-relearn", advisor -> {
            final Scenarios scenarios = new Scenarios();
            final ShapeshifterAiDoc doc = doc("relearn", 0.9).copy().relearnThreshold(0.8).build();
            final List<StageRun> runs = new ArrayList<>();
            runs.add(scenarios.process(advisor, doc, stream(1, CsvLines.lines(7))).learned());
            runs.add(scenarios.process(advisor, runs.get(0).doc(), stream(2, CsvLines.lines(20, "user", 3))).learned());
            runs.add(scenarios.process(advisor, runs.get(1).doc(), stream(3, CsvLines.lines(20, "user", 3))).learned());
            return runs;
        }).ifPresent(outcomes::add);

        // A different text shape from the corpus: "123 [abc] This is some text".
        run("05-regex-corpus", advisor -> {
            final Scenarios scenarios = new Scenarios();
            final ShapeshifterAiDoc doc = doc("regex", 0.9).copy()
                    .instructions("Lines of a number, a bracketed type and free text: a system's own log.")
                    .minRecordsToLearn(2)
                    .build();
            return List.of(scenarios.process(advisor, doc,
                    new Input(1, "SYSLOG-LIKE", "Raw Events", Map.of(), REGEX.input())).learned());
        }).ifPresent(outcomes::add);

        // The feeds A31 was designed for (design 02 §6.3): records of several lines, where a splitter can lose
        // fields before the model sees them; and nested XML, where the record is already a tree.
        run("06-multiline-audit", advisor -> {
            final Scenarios scenarios = new Scenarios();
            final ShapeshifterAiDoc doc = doc("multiline-audit", 0.9).copy()
                    .instructions("Linux audit records: blocks separated by a line of four dashes, a block of "
                                  + "several lines being one record whose lines share the msg=audit(...) id. Events "
                                  + "should name the auid or uid as the user and the node as the device.")
                    .minRecordsToLearn(2)
                    .build();
            return List.of(scenarios.process(advisor, doc,
                    new Input(1, "LINUX-AUDIT", "Raw Events", Map.of(), MULTI_LINE.input())).learned());
        }).ifPresent(outcomes::add);
        run("07-nested-xml", advisor -> {
            final Scenarios scenarios = new Scenarios();
            final ShapeshifterAiDoc doc = doc("nested-xml", 0.9).copy()
                    .instructions("An application's audit log as XML, one entry per thing a person did. Events "
                                  + "should name the login as the user, the host as the device, and the document "
                                  + "opened where there is one.")
                    .minRecordsToLearn(2)
                    .build();
            return List.of(scenarios.process(advisor, doc,
                    new Input(1, "DOCVAULT-AUDIT", "Raw Events", Map.of("Format", "XML"), NESTED_XML)).learned());
        }).ifPresent(outcomes::add);

        // Phase B (design 03 §3): syslog in both forms on one feed, two senders.
        run("08-syslog", advisor -> {
            final Scenarios scenarios = new Scenarios();
            final ShapeshifterAiDoc doc = doc("syslog", 0.9).copy()
                    .instructions("Syslog from two SSH gateways on one feed: one sends RFC 3164 (BSD) lines, the "
                                  + "other RFC 5424. Every line is one record and the message is sshd's "
                                  + "authentication line. Events should be Authenticate logons naming the user, "
                                  + "the client IP address and the gateway as the device; the BSD form carries no "
                                  + "year, and the year is 2026.")
                    .build();
            return List.of(scenarios.process(advisor, doc,
                    new Input(1, "GATEWAY-SSH", "Raw Events", Map.of("Format", "syslog"), SYSLOG)).learned());
        }).ifPresent(outcomes::add);

        run("09-auditd", advisor -> {
            final Scenarios scenarios = new Scenarios();
            final ShapeshifterAiDoc doc = doc("auditd", 0.9).copy()
                    .instructions("Linux audit records, interpreted, one line per record, no separators: an event "
                                  + "is the run of consecutive lines sharing one msg=audit(time:serial). Logins "
                                  + "(USER_LOGIN) are Authenticate events naming the user and the client address; "
                                  + "an execve (SYSCALL with EXECVE) is a Process event whose command and arguments "
                                  + "are the EXECVE's a0, a1… The time is seconds since the epoch.")
                    .scorers(withYield(doc("auditd", 0.9).getScorers(), YieldBasis.LINES, 0.35, 0.6))
                    .build();
            return List.of(scenarios.process(advisor, doc,
                    new Input(1, "LINUX-AUDITD", "Raw Events", Map.of(), AUDITD)).learned());
        }).ifPresent(outcomes::add);

        run("10-windows-security", advisor -> {
            final Scenarios scenarios = new Scenarios();
            final ShapeshifterAiDoc doc = doc("windows-security", 0.9).copy()
                    .instructions("Windows Security event log exported as XML: each Event is one record; the "
                                  + "EventID says what happened — 4624 a logon, 4634 a logoff, 4688 a process "
                                  + "created — and the EventData's named Data carry the user, the client "
                                  + "address and the process. The computer is the device. Map to typed events, "
                                  + "not to Data elements.")
                    .build();
            return List.of(scenarios.process(advisor, doc,
                    new Input(1, "WINDOWS-SECURITY", "Raw Events", Map.of("Format", "XML"), WINDOWS)).learned());
        }).ifPresent(outcomes::add);

        // JSON in both shapes: the chain may choose the JSONParser, which takes no configuration.
        run("11-json-lines", advisor -> {
            final Scenarios scenarios = new Scenarios();
            final ShapeshifterAiDoc doc = doc("json-lines", 0.9).copy()
                    .allowedElements(List.of("DSParser", "JSONParser", "XSLTFilter"))
                    .instructions("An API gateway's audit log as JSON lines, one object per line: logins and "
                                  + "logouts naming the user, the host and the client. Each is an Authenticate "
                                  + "event; the host is the device.")
                    .build();
            return List.of(scenarios.process(advisor, doc,
                    new Input(1, "API-GATEWAY", "Raw Events", Map.of("Format", "JSON"), JSON_LINES)).learned());
        }).ifPresent(outcomes::add);
        run("12-json-document", advisor -> {
            final Scenarios scenarios = new Scenarios();
            final ShapeshifterAiDoc doc = doc("json-document", 0.9).copy()
                    .allowedElements(List.of("DSParser", "JSONParser", "XSLTFilter"))
                    .instructions("An API gateway's audit log as one JSON document: an object whose events array "
                                  + "holds the records, logins and logouts naming the user, the host and the "
                                  + "client. Each is an Authenticate event; the host is the device.")
                    .build();
            return List.of(scenarios.process(advisor, doc,
                    new Input(1, "API-GATEWAY-DOC", "Raw Events", Map.of("Format", "JSON"), JSON_DOCUMENT)).learned());
        }).ifPresent(outcomes::add);

        // Fixed-width: nothing to split on, and a column dropped is caught by preservation, not coverage.
        run("13-fixed-width", advisor -> {
            final Scenarios scenarios = new Scenarios();
            final ShapeshifterAiDoc doc = doc("fixed-width", 0.9).copy()
                    .instructions("A mainframe sign-on log in fixed-width columns with no delimiter: the time as "
                                  + "yyyyMMddHHmmss, the user, the terminal, the action (LOGON or LOGOFF), the "
                                  + "result (GRANTED or DENIED) and a reason, which may hold spaces. Each line is "
                                  + "an Authenticate event stating its outcome and the reason; the terminal is the "
                                  + "device.")
                    .scorers(withRule(doc("fixed-width", 0.9).getScorers(), new XPathAssertion(
                            "a sign-on decision states its outcome",
                            "not(EventDetail/Authenticate) "
                            + "or EventDetail/Authenticate/Outcome/Success[. = 'true' or . = 'false']")))
                    .build();
            return List.of(scenarios.process(advisor, doc,
                    new Input(1, "MAINFRAME-SIGNON", "Raw Events", Map.of(), FIXED_WIDTH)).learned());
        }).ifPresent(outcomes::add);

        // CSV with a quoted last field that holds commas, doubled quotes and line breaks: a record may span
        // lines, which the expected yield per line states.
        run("14-csv-multiline", advisor -> {
            final Scenarios scenarios = new Scenarios();
            final ShapeshifterAiDoc doc = doc("csv-multiline", 0.9).copy()
                    .instructions("A document store's audit export as CSV without a header: time, user, "
                                  + "workstation, action (VIEW or DELETE), document and a note. The note is "
                                  + "quoted where it holds a comma, a quote (doubled) or a line break, so a "
                                  + "record may span lines. Each record is one View or Delete event on a "
                                  + "Document, its note the outcome's description; the workstation is the "
                                  + "device.")
                    .scorers(withYield(doc("csv-multiline", 0.9).getScorers(), YieldBasis.LINES, 0.77, 0.9))
                    .build();
            return List.of(scenarios.process(advisor, doc,
                    new Input(1, "DOCUMENT-STORE", "Raw Events", Map.of("Format", "CSV"), CSV_MULTILINE)).learned());
        }).ifPresent(outcomes::add);

        LOGGER.info("Live scenarios against {}, {}:\n{}", System.getenv(LiveAdvisor.MODEL), FLOW_UNDER_TEST,
                AsciiTable.builder(outcomes)
                        .withColumn(Column.of("Run", (Outcome o) -> o.name()))
                        .withColumn(Column.of("Decisions", (Outcome o) -> o.decisions()))
                        .withColumn(Column.integer("Questions", (Outcome o) -> o.questions()))
                        .withColumn(Column.of("Score", (Outcome o) -> o.score()))
                        .withColumn(Column.integer("Tokens", (Outcome o) -> (int) o.tokens()))
                        .withColumn(Column.integer("Seconds", (Outcome o) -> (int) (o.millis() / 1000)))
                        .withColumn(Column.of("Failure", (Outcome o) -> o.failure()))
                        .build());
        Files.writeString(OUT.resolve("report.md"), report(outcomes), StandardCharsets.UTF_8);
        assertThat(outcomes).describedAs("the harness ran the selected scenarios").isNotEmpty();
    }

    private Optional<Outcome> run(final String name, final Function<LiveAdvisor, List<StageRun>> scenario) {
        if (!ROWS_UNDER_TEST.matcher(name).find()) {
            LOGGER.info("Live run {} not selected by {}", name, ROWS);
            return Optional.empty();
        }
        // The advisor speaks for a document like the scenarios': the same instructions and scorer demands.
        final LiveAdvisor advisor = LiveAdvisor.fromEnvironment(doc(name, 0.9)).orElseThrow();
        List<StageRun> runs = List.of();
        String failure = "";
        try {
            runs = scenario.apply(advisor);
        } catch (final RuntimeException e) {
            // The harness's own failure is the finding: recorded, and the other runs go on.
            failure = e.getClass().getSimpleName() + ": " + e.getMessage();
            LOGGER.warn("Live run {} failed", name, e);
        }
        final Outcome outcome = new Outcome(name, runs, advisor.turns(), advisor.tokens(), advisor.millis(), failure);
        transcript(outcome);
        return Optional.of(outcome);
    }

    private void transcript(final Outcome outcome) {
        final StringBuilder text = new StringBuilder("# " + outcome.name() + "\n\n");
        text.append("Model: ").append(System.getenv(LiveAdvisor.MODEL))
                .append(", flow ").append(FLOW_UNDER_TEST).append("\n\n");
        text.append("Decisions: ").append(outcome.decisions()).append("\n\n");
        if (!outcome.failure().isEmpty()) {
            text.append("Failure: ").append(outcome.failure()).append("\n\n");
        }
        int i = 0;
        for (final Turn turn : outcome.turns()) {
            text.append("## Turn ").append(++i).append(" — ").append(turn.kind())
                    .append(" (").append(turn.tokens()).append(" tokens, ").append(turn.millis()).append(" ms)\n\n");
            text.append("### Asked\n\n").append(turn.prompt()).append("\n\n");
            text.append("### Replied\n\n").append(turn.reply()).append("\n\n");
        }
        for (final StageRun run : outcome.runs()) {
            text.append("## Verdicts for decision ").append(describe(run.decision())).append("\n\n");
            if (run.decision() instanceof final GivenUp givenUp) {
                givenUp.diagnostics().forEach(error -> text.append("- ").append(error.getSeverity().getDisplayValue())
                        .append(": ").append(error.getMessage()).append('\n'));
            }
            run.verdicts().forEach(verdict -> verdict.judgements().forEach(judgement -> text.append("- ")
                    .append(judgement.setting().getType().getDisplayValue()).append(": ")
                    .append(judgement.score().value()).append('\n')));
            text.append('\n');
        }
        try {
            Files.writeString(OUT.resolve(outcome.name() + ".md"), text.toString(), StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String report(final List<Outcome> outcomes) {
        final StringBuilder text = new StringBuilder("# Live scenarios — " + System.getenv(LiveAdvisor.MODEL));
        text.append(", flow ").append(FLOW_UNDER_TEST).append("\n\n");
        text.append("| Run | Decisions | Questions | Score | Tokens | Seconds | Failure |\n");
        text.append("|---|---|---|---|---|---|---|\n");
        for (final Outcome o : outcomes) {
            text.append("| ").append(o.name()).append(" | ").append(o.decisions()).append(" | ").append(o.questions())
                    .append(" | ").append(o.score()).append(" | ").append(o.tokens()).append(" | ")
                    .append(o.millis() / 1000).append(" | ").append(o.failure()).append(" |\n");
        }
        return text.toString();
    }

    private static String describe(final Decision decision) {
        return switch (decision) {
            case Promoted p -> "Promoted " + round(p.score());
            case Provisional p -> "Provisional " + round(p.score());
            case Rebound r -> "Rebound " + round(r.score());
            case Kept k -> "Kept: " + k.reason();
            case GivenUp g -> "GivenUp: " + g.reason();
            case Bound b -> "Bound";
            default -> decision.getClass().getSimpleName();
        };
    }

    private static String round(final double value) {
        return String.format("%.3f", value);
    }

    private record Outcome(String name, List<StageRun> runs, List<Turn> turns, long tokens, long millis,
                           String failure) {

        String decisions() {
            return String.join("; ", runs.stream().map(run -> describe(run.decision())).toList());
        }

        int questions() {
            return turns.size();
        }

        String score() {
            return runs.isEmpty()
                    ? ""
                    : describe(runs.get(runs.size() - 1).decision());
        }
    }
}
