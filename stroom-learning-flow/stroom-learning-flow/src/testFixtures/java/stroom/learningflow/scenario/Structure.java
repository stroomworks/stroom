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

import stroom.learningflow.learning.Boundary;
import stroom.learningflow.learning.Question;
import stroom.learningflow.learning.Question.Split;
import stroom.learningflow.learning.Question.TargetFor;
import stroom.learningflow.learning.StepResult;
import stroom.learningflow.learning.StepRunner;
import stroom.learningflow.learning.TargetChecks;
import stroom.learningflow.scoring.OutputRecords;
import stroom.learningflow.shared.RecordBoundary;
import stroom.learningflow.stage.RecordSkeleton;

import java.util.List;
import java.util.Optional;

/// A [Script]'s answers to the structural questions (*Split* and *Target*), worked out from the
/// configurations the script will go on to give, so that a scenario about serving, promotion or relearning
/// states only the questions it is about.
///
/// For text input, the split is one record per line. A target is what the script's own splitter and
/// stylesheet make of the record, or `none` where they make nothing of it (a header, for example). A
/// scenario about the targets themselves scripts those questions explicitly, and this is not used for
/// them.
public final class Structure {

    /// A Data Splitter configuration with one record per line and the whole line as one field: the split
    /// for every line-based feed the scenarios use.
    public static final String LINE_SPLIT = Scenarios.resource("line-split.ds3.xml");

    private final List<StepRunner> runners;
    private final String splitter;
    private final String recordElement;
    private final String arrayKey;
    private final String stylesheet;

    /// Creates the structure for text input.
    ///
    /// @param runners    The step runners used to run the splitter and stylesheet.
    /// @param splitter   The Data Splitter configuration the script will answer the parser question with.
    /// @param stylesheet The stylesheet the script will answer the transform question with.
    public Structure(final List<StepRunner> runners, final String splitter, final String stylesheet) {
        this(runners, splitter, null, null, stylesheet);
    }

    /// Creates the structure for input that is already XML, from the element that is one record (which
    /// answers the split question) and the stylesheet the script will answer the transform question with.
    public static Structure ofXml(final List<StepRunner> runners, final String recordElement,
                                  final String stylesheet) {
        return new Structure(runners, null, recordElement, null, stylesheet);
    }

    /// Creates the structure for JSON input, from the key of the array whose items are records, or
    /// [Boundary#ROOT] (which answers the split question), and the stylesheet over the parser's XML that
    /// the script will answer the transform question with.
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

    /// Whether this structure is for the input a question is about. A pipeline may contain two Smart
    /// Pipeline elements, one given raw text and one given the records the first made of it, and each
    /// answers the split and target questions in its own terms. So a scenario over such a pipeline has one
    /// structure per element, and each says which questions are its own.
    public boolean fits(final Question question) {
        final String sample = switch (question) {
            case Split split -> split.sample().text();
            case TargetFor target -> target.sample().text();
            default -> null;
        };
        if (sample == null) {
            return false;
        }
        // The splitter is for raw text and the record element for markup. That is the one thing that tells
        // the two elements' questions apart without asking which element sent them.
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

    /// For JSON: the event the stylesheet makes of the array item at the record's position, run over the
    /// XML the parser makes of the sample.
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

    /// For XML input: the event the stylesheet makes of the record element at the record's position.
    private String targetForElement(final String sample, final String record) {
        final Optional<OutputRecords> document = OutputRecords.parse(sample);
        if (document.isEmpty()) {
            return TargetChecks.NONE;
        }
        // `batch/*`, or `*` for the root's children: the records are the children of a container, which
        // may have different element names, rather than the elements of one name.
        final List<String> records = recordElement.endsWith("*")
                ? document.get().recordsBy(RecordBoundary.ofChildrenOf(recordElement.equals("*")
                        ? RecordBoundary.ROOT
                        : recordElement.substring(0, recordElement.length() - 2)))
                        .stream().map(Object::toString).toList()
                : TargetChecks.elementsNamed(document.get(), recordElement);
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

    /// For text input: the event the script's own chain makes of one record, as the fenced document the
    /// target question expects, or `none` where the chain emits no record for it. The chain runs over the
    /// whole sample, as it will in the conversation, because a splitter that reads a header cannot make
    /// sense of one line alone. The record's event is the one at the position of the first emitted record
    /// whose values all come from the record.
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
