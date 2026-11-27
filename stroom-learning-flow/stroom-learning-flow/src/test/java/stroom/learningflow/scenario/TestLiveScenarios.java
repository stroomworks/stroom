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

package stroom.learningflow.scenario;

import stroom.learningflow.extraction.ExtractionCorpus.Golden;
import stroom.learningflow.learning.FlowExamples;
import stroom.learningflow.scenario.LiveAdvisor.Turn;
import stroom.learningflow.shared.BusinessRulesParameters;
import stroom.learningflow.shared.Check;
import stroom.learningflow.shared.CheckSetting;
import stroom.learningflow.shared.CheckState;
import stroom.learningflow.shared.ExtractionQualityParameters;
import stroom.learningflow.shared.FlowExample;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.shared.LearningMode;
import stroom.learningflow.shared.XPathAssertion;
import stroom.learningflow.shared.YieldBasis;
import stroom.learningflow.shared.YieldParameters;
import stroom.learningflow.stage.Decision;
import stroom.learningflow.stage.Decision.Bound;
import stroom.learningflow.stage.Decision.GivenUp;
import stroom.learningflow.stage.Decision.Kept;
import stroom.learningflow.stage.Decision.Promoted;
import stroom.learningflow.stage.Decision.Rebound;
import stroom.learningflow.stage.Input;
import stroom.learningflow.stage.StageRun;
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

/// A live smoke test that runs the scenario machinery against a real model instead of a scripted one.
///
/// It finds out what a script cannot: whether the questions draw replies that can be parsed, whether the
/// feedback steers the model to a passing candidate within the candidate limit, and what an attempt costs.
/// The results are a report, not a pass or fail: the test fails only if the harness itself breaks. It runs
/// only when the environment names a model endpoint (see [LiveAdvisor]), and writes each run's transcript and
/// a summary report under `build/live/<flow>`.
@EnabledIfEnvironmentVariable(named = LiveAdvisor.BASE_URL, matches = ".+")
class TestLiveScenarios {

    private static final Logger LOGGER = LoggerFactory.getLogger(TestLiveScenarios.class);
    /// The environment variable that sets the candidate limit for the run, to measure how many attempts a
    /// strict schema costs a model. The document's default is used when it is unset.
    private static final String MAX_ATTEMPTS = "LEARNING_FLOW_LIVE_MAX_ATTEMPTS";
    /// The environment variable naming the example flow the run's documents use: `DIRECT` (the chain, then
    /// each element's configuration), `TARGET_FIRST` (proposed target events first) or `ESCALATING`. It lets
    /// flows be compared on the same model and feeds. `DIRECT` is used when it is unset.
    private static final String FLOW = "LEARNING_FLOW_LIVE_FLOW";
    /// The environment variable that selects which runs to do, as a regular expression over the run names
    /// (e.g. `0[89]|1[0-4]` for the syslog, auditd, Windows, JSON, fixed-width and multi-line CSV formats).
    /// Every run is done when it is unset. A run that is not selected is left out of the report.
    private static final String ROWS = "LEARNING_FLOW_LIVE_ROWS";
    private static final Pattern ROWS_UNDER_TEST = Pattern.compile(Optional.ofNullable(System.getenv(ROWS))
            .filter(rows -> !rows.isBlank())
            .orElse(".*"));
    // The variable's earlier name, from when a flow was called a plan, is still read so that an env file
    // written then still selects the flow.
    private static final FlowExample FLOW_UNDER_TEST = Optional.ofNullable(System.getenv(FLOW))
            .or(() -> Optional.ofNullable(System.getenv("LEARNING_FLOW_LIVE_PLAN")))
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

    /// Builds the document as an operator would set it up for a feed like this: the full set of scorers, the
    /// fields the business needs, and the default score threshold.
    private static LearningFlowDoc doc(final String name, final double coverageThreshold) {
        final LearningFlowDoc.Builder builder = Scenarios.document()
                .uuid("live-" + name)
                .name(name)
                .learningMode(LearningMode.AUTOMATIC)
                .flow(FlowExamples.flow(FLOW_UNDER_TEST))
                .allowedElements(List.of("DSParser", "XSLTFilter"))
                .instructions(INSTRUCTIONS)
                .minRecordsToLearn(5);
        Optional.ofNullable(System.getenv(MAX_ATTEMPTS)).map(Integer::valueOf).ifPresent(builder::maxAttempts);
        return builder
                .checks(List.of(
                        new CheckSetting(Check.COMPILE, CheckState.GATE, 1.0, null),
                        new CheckSetting(Check.COVERAGE, CheckState.ADVICE, coverageThreshold, null),
                        new CheckSetting(Check.YIELD, CheckState.ADVICE, 0.5,
                                new YieldParameters(1.0, YieldBasis.RECORDS)),
                        CheckSetting.of(Check.WHOLENESS, CheckState.GATE),
                        new CheckSetting(Check.CONFORMANCE, CheckState.GATE, 1.0, null),
                        new CheckSetting(Check.QUALITY, CheckState.GATE, 0.7,
                                new ExtractionQualityParameters(false,
                                                List.of("EventSource/User/Id", "EventSource/Device/Name"))),
                        new CheckSetting(Check.RULES, CheckState.ADVICE, 0.5,
                                new BusinessRulesParameters(List.of(new XPathAssertion(
                                                "interactive events name the user",
                                                "not(EventDetail/Authenticate) "
                                                + "or EventDetail/Authenticate/User/Id[normalize-space(.) != '']")),
                                                true)),
                        CheckSetting.of(Check.PRESERVATION, CheckState.GATE),
                        CheckSetting.of(Check.FIDELITY, CheckState.GATE),
                        CheckSetting.of(Check.REGRESSION, CheckState.GATE)))
                .build();
    }

    /// Returns the checks with the yield setting replaced. A feed whose records span several lines declares
    /// this through its expected yield per line.
    private static List<CheckSetting> withYield(final List<CheckSetting> checks,
                                                final YieldBasis basis,
                                                final double expected,
                                                final double threshold) {
        return checks.stream()
                .map(setting -> setting.getCheck() == Check.YIELD
                        ? new CheckSetting(Check.YIELD, CheckState.ADVICE, threshold,
                                new YieldParameters(expected, basis))
                        : setting)
                .toList();
    }

    /// Returns the checks with one more business rule added to the existing one.
    private static List<CheckSetting> withRule(final List<CheckSetting> checks, final XPathAssertion rule) {
        return checks.stream()
                .map(setting -> setting.getCheck() == Check.RULES
                        ? new CheckSetting(Check.RULES, setting.getState(), setting.getThreshold(),
                                new BusinessRulesParameters(Stream.concat(
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

        // The corpus's CSV with its header line.
        run("01-csv-with-header", advisor -> {
            final Scenarios scenarios = new Scenarios();
            return List.of(scenarios.process(advisor, doc("csv-with-header", 0.8), stream(1, CSV.input())).learned());
        }).ifPresent(outcomes::add);

        // A CSV without a header against the full set of scorers. This shows whether the first candidate
        // extracts real meaning, or produces events that validate but extract nothing and has to be steered
        // away from that by the extraction quality check.
        run("02-csv-meaning", advisor -> {
            final Scenarios scenarios = new Scenarios();
            return List.of(scenarios.process(advisor, doc("csv-meaning", 0.9), stream(1, CsvLines.lines(7))).learned());
        }).ifPresent(outcomes::add);

        // Two kinds of record in one stream, with a coverage threshold of 0.9, so the splitter must handle both.
        run("03-two-record-kinds", advisor -> {
            final Scenarios scenarios = new Scenarios();
            return List.of(scenarios.process(advisor, doc("two-kinds", 0.9),
                    stream(1, CsvLines.lines(20, "user", 3))).learned());
        }).ifPresent(outcomes::add);

        // Learn the plain feed, then see the score fall when a second kind of record appears, and relearn.
        run("04-relearn", advisor -> {
            final Scenarios scenarios = new Scenarios();
            final LearningFlowDoc doc = doc("relearn", 0.9).copy().relearnThreshold(0.8).build();
            final List<StageRun> runs = new ArrayList<>();
            runs.add(scenarios.process(advisor, doc, stream(1, CsvLines.lines(7))).learned());
            runs.add(scenarios.process(advisor, runs.get(0).doc(), stream(2, CsvLines.lines(20, "user", 3))).learned());
            runs.add(scenarios.process(advisor, runs.get(1).doc(), stream(3, CsvLines.lines(20, "user", 3))).learned());
            return runs;
        }).ifPresent(outcomes::add);

        // A different text shape from the corpus: "123 [abc] This is some text".
        run("05-regex-corpus", advisor -> {
            final Scenarios scenarios = new Scenarios();
            final LearningFlowDoc doc = doc("regex", 0.9).copy()
                    .instructions("Lines of a number, a bracketed type and free text: a system's own log.")
                    .minRecordsToLearn(2)
                    .build();
            return List.of(scenarios.process(advisor, doc,
                    new Input(1, "SYSLOG-LIKE", "Raw Events", Map.of(), REGEX.input())).learned());
        }).ifPresent(outcomes::add);

        // Feeds that learning against target events is meant for: records of several lines, where a splitter
        // can lose fields before the model sees them, and nested XML, where the record is already a tree.
        run("06-multiline-audit", advisor -> {
            final Scenarios scenarios = new Scenarios();
            final LearningFlowDoc doc = doc("multiline-audit", 0.9).copy()
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
            final LearningFlowDoc doc = doc("nested-xml", 0.9).copy()
                    .instructions("An application's audit log as XML, one entry per thing a person did. Events "
                                  + "should name the login as the user, the host as the device, and the document "
                                  + "opened where there is one.")
                    .minRecordsToLearn(2)
                    .build();
            return List.of(scenarios.process(advisor, doc,
                    new Input(1, "DOCVAULT-AUDIT", "Raw Events", Map.of("Format", "XML"), NESTED_XML)).learned());
        }).ifPresent(outcomes::add);

        // Syslog in both its forms on one feed, from two senders.
        run("08-syslog", advisor -> {
            final Scenarios scenarios = new Scenarios();
            final LearningFlowDoc doc = doc("syslog", 0.9).copy()
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
            final LearningFlowDoc doc = doc("auditd", 0.9).copy()
                    .instructions("Linux audit records, interpreted, one line per record, no separators: an event "
                                  + "is the run of consecutive lines sharing one msg=audit(time:serial). Logins "
                                  + "(USER_LOGIN) are Authenticate events naming the user and the client address; "
                                  + "an execve (SYSCALL with EXECVE) is a Process event whose command and arguments "
                                  + "are the EXECVE's a0, a1… The time is seconds since the epoch.")
                    .checks(withYield(doc("auditd", 0.9).getChecks(), YieldBasis.LINES, 0.35, 0.6))
                    .build();
            return List.of(scenarios.process(advisor, doc,
                    new Input(1, "LINUX-AUDITD", "Raw Events", Map.of(), AUDITD)).learned());
        }).ifPresent(outcomes::add);

        run("10-windows-security", advisor -> {
            final Scenarios scenarios = new Scenarios();
            final LearningFlowDoc doc = doc("windows-security", 0.9).copy()
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
            final LearningFlowDoc doc = doc("json-lines", 0.9).copy()
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
            final LearningFlowDoc doc = doc("json-document", 0.9).copy()
                    .allowedElements(List.of("DSParser", "JSONParser", "XSLTFilter"))
                    .instructions("An API gateway's audit log as one JSON document: an object whose events array "
                                  + "holds the records, logins and logouts naming the user, the host and the "
                                  + "client. Each is an Authenticate event; the host is the device.")
                    .build();
            return List.of(scenarios.process(advisor, doc,
                    new Input(1, "API-GATEWAY-DOC", "Raw Events", Map.of("Format", "JSON"), JSON_DOCUMENT)).learned());
        }).ifPresent(outcomes::add);

        // Fixed-width columns have no delimiter to split on, and a dropped column is caught by the preservation
        // check, not by coverage.
        run("13-fixed-width", advisor -> {
            final Scenarios scenarios = new Scenarios();
            final LearningFlowDoc doc = doc("fixed-width", 0.9).copy()
                    .instructions("A mainframe sign-on log in fixed-width columns with no delimiter: the time as "
                                  + "yyyyMMddHHmmss, the user, the terminal, the action (LOGON or LOGOFF), the "
                                  + "result (GRANTED or DENIED) and a reason, which may hold spaces. Each line is "
                                  + "an Authenticate event stating its outcome and the reason; the terminal is the "
                                  + "device.")
                    .checks(withRule(doc("fixed-width", 0.9).getChecks(), new XPathAssertion(
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
            final LearningFlowDoc doc = doc("csv-multiline", 0.9).copy()
                    .instructions("A document store's audit export as CSV without a header: time, user, "
                                  + "workstation, action (VIEW or DELETE), document and a note. The note is "
                                  + "quoted where it holds a comma, a quote (doubled) or a line break, so a "
                                  + "record may span lines. Each record is one View or Delete event on a "
                                  + "Document, its note the outcome's description; the workstation is the "
                                  + "device.")
                    .checks(withYield(doc("csv-multiline", 0.9).getChecks(), YieldBasis.LINES, 0.77, 0.9))
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
        // The advisor is set up for a document like the ones the runs use, with the same instructions and
        // scorers.
        final LiveAdvisor advisor = LiveAdvisor.fromEnvironment(doc(name, 0.9)).orElseThrow();
        List<StageRun> runs = List.of();
        String failure = "";
        try {
            runs = scenario.apply(advisor);
        } catch (final RuntimeException e) {
            // A failure here is itself a finding, so it is recorded and the other runs carry on.
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
                    .append(judgement.setting().getCheck().getDisplayValue()).append(": ")
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
