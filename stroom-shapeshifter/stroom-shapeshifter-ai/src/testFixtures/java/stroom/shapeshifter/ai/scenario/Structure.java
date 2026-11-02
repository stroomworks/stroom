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

import stroom.shapeshifter.ai.learning.Boundary;
import stroom.shapeshifter.ai.learning.Question;
import stroom.shapeshifter.ai.learning.Question.Split;
import stroom.shapeshifter.ai.learning.Question.TargetFor;
import stroom.shapeshifter.ai.learning.StepResult;
import stroom.shapeshifter.ai.learning.StepRunner;
import stroom.shapeshifter.ai.learning.TargetChecks;
import stroom.shapeshifter.ai.scoring.OutputRecords;
import stroom.shapeshifter.ai.stage.RecordSkeleton;

import java.util.List;
import java.util.Optional;

/**
 * A scripted model's answers to the structural questions of A31 — <i>Split</i> and <i>Target</i> — worked
 * out from the configurations the script will go on to give, so that a scenario about serving, promotion
 * or relearning states only the questions it is about. The split is one record per line; a target is
 * what the script's own splitter and stylesheet make of the record, or {@code none} where they make
 * nothing of it (a header). A scenario about the targets themselves scripts those turns explicitly, and
 * this is not consulted for them.
 */
public final class Structure {

    /**
     * One record per line, the line as one field: the split for every line-shaped feed the scenarios use.
     */
    public static final String LINE_SPLIT = Scenarios.resource("line-split.ds3.xml");

    private final List<StepRunner> runners;
    private final String splitter;
    private final String recordElement;
    private final String arrayKey;
    private final String stylesheet;

    /**
     * @param splitter   The Data Splitter configuration the script will answer the parser question with.
     * @param stylesheet The stylesheet the script will answer the transform question with.
     */
    public Structure(final List<StepRunner> runners, final String splitter, final String stylesheet) {
        this(runners, splitter, null, null, stylesheet);
    }

    /// For input that is already XML: the element that is one record, which answers the split question, and
    /// the stylesheet the script will answer the transform question with.
    public static Structure ofXml(final List<StepRunner> runners, final String recordElement,
                                  final String stylesheet) {
        return new Structure(runners, null, recordElement, null, stylesheet);
    }

    /// For JSON: the key of the array whose items are records — or {@link Boundary#ROOT} — which answers the
    /// split question, and the stylesheet over the parser's XML the script will answer the transform question
    /// with.
    public static Structure ofJson(final List<StepRunner> runners, final String arrayKey, final String stylesheet) {
        return new Structure(runners, null, null, arrayKey, stylesheet);
    }

    private Structure(final List<StepRunner> runners,
                      final String splitter,
                      final String recordElement,
                      final String arrayKey,
                      final String stylesheet) {
        this.runners = runners;
        this.splitter = splitter;
        this.recordElement = recordElement;
        this.arrayKey = arrayKey;
        this.stylesheet = stylesheet;
    }

    /// Whether this is the structure for the input a question is about. A pipeline may hold two
    /// supervised stages — one given raw text, one given the records the first made of it — and each
    /// answers the split and target questions in its own terms, so a scenario over the pair carries a
    /// structure per stage and each says which is its.
    public boolean fits(final Question question) {
        final String sample = switch (question) {
            case Split split -> split.sample().text();
            case TargetFor target -> target.sample().text();
            default -> null;
        };
        if (sample == null) {
            return false;
        }
        // The splitter is for raw text and the record element for markup, which is the one thing that
        // tells the two stages' questions apart without asking which element sent them.
        return splitter != null
                ? !RecordSkeleton.isMarkup(sample)
                : RecordSkeleton.isMarkup(sample);
    }

    public Optional<String> answer(final Question question) {
        return switch (question) {
            case Split split -> Optional.of(arrayKey != null
                    ? arrayKey
                    : recordElement != null
                            ? recordElement
                            : Scenarios.fenced(LINE_SPLIT));
            case TargetFor target -> Optional.of(arrayKey != null
                    ? targetForItem(target.sample().text(), target.record())
                    : recordElement != null
                            ? targetForElement(target.sample().text(), target.record())
                            : targetFor(target.sample().text(), target.record()));
            default -> Optional.empty();
        };
    }

    /**
     * For JSON: the event the stylesheet makes of the array item at the record's position, over the XML the
     * parser makes of the sample.
     */
    private String targetForItem(final String sample, final String record) {
        final StepResult parsed = runner("JSONParser").run(null, sample);
        final Optional<OutputRecords> document = parsed.passed()
                ? OutputRecords.parse(parsed.output())
                : Optional.empty();
        if (document.isEmpty()) {
            return TargetChecks.NONE;
        }
        final List<String> records = Boundary.ROOT.equals(arrayKey)
                ? TargetChecks.rootRecords(document.get())
                : TargetChecks.arrayItems(document.get(), arrayKey);
        final int at = records.indexOf(record);
        if (at < 0) {
            return TargetChecks.NONE;
        }
        final StepResult events = runner("XSLTFilter").run(stylesheet, parsed.output());
        return OutputRecords.parse(events.output())
                .filter(produced -> produced.records().size() > at)
                .map(produced -> Scenarios.fenced(produced.records().get(at).toString()))
                .orElse(TargetChecks.NONE);
    }

    /**
     * For XML input: the event the stylesheet makes of the record element at the record's position.
     */
    private String targetForElement(final String sample, final String record) {
        final Optional<OutputRecords> document = OutputRecords.parse(sample);
        if (document.isEmpty()) {
            return TargetChecks.NONE;
        }
        final List<String> records = TargetChecks.elementsNamed(document.get(), recordElement);
        final int at = records.indexOf(record);
        if (at < 0) {
            return TargetChecks.NONE;
        }
        final StepResult events = runner("XSLTFilter").run(stylesheet, sample);
        return OutputRecords.parse(events.output())
                .filter(produced -> produced.records().size() > at)
                .map(produced -> Scenarios.fenced(produced.records().get(at).toString()))
                .orElse(TargetChecks.NONE);
    }

    /**
     * The event the script's own chain makes of one record, as the fenced document the target question
     * wants; {@code none} where the chain emits no record for it. The chain runs over the whole sample,
     * as it will in the conversation — a splitter that reads a header cannot make sense of one line alone —
     * and the record's event is the one at the position of the emitted record whose values all come from
     * the record.
     */
    private String targetFor(final String sample, final String record) {
        final StepResult records = runner("DSParser").run(splitter, sample);
        final Optional<OutputRecords> parsed = OutputRecords.parse(records.output());
        if (records.output() == null || parsed.isEmpty()) {
            return TargetChecks.NONE;
        }
        int index = -1;
        for (int i = 0; i < parsed.get().records().size() && index < 0; i++) {
            final List<String> values = parsed.get()
                    .evaluate(parsed.get().records().get(i), "descendant::*[local-name() = 'data']/@value")
                    .stream()
                    .map(item -> item.getStringValue())
                    .filter(value -> !value.isBlank())
                    .toList();
            if (!values.isEmpty() && values.stream().allMatch(record::contains)) {
                index = i;
            }
        }
        if (index < 0) {
            return TargetChecks.NONE;
        }
        final StepResult events = runner("XSLTFilter").run(stylesheet, records.output());
        final int at = index;
        return OutputRecords.parse(events.output())
                .filter(produced -> produced.records().size() > at)
                .map(produced -> Scenarios.fenced(produced.records().get(at).toString()))
                .orElse(TargetChecks.NONE);
    }

    private StepRunner runner(final String elementType) {
        return runners.stream()
                .filter(runner -> runner.elementType().equals(elementType))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No runner for " + elementType));
    }
}
