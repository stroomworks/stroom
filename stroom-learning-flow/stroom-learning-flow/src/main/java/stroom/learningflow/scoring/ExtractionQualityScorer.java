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

package stroom.learningflow.scoring;

import stroom.learningflow.learning.TargetSchema;
import stroom.learningflow.shared.Check;
import stroom.learningflow.shared.ExtractionQualityParameters;
import stroom.learningflow.shared.ScorerParameters;
import stroom.util.shared.ElementId;
import stroom.util.shared.Severity;
import stroom.util.shared.StoredError;

import net.sf.saxon.s9api.XdmNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/// The extraction quality scorer, which guards against degenerate translations by measuring how much of
/// the output means something. It averages three measures over the records:
///
/// - the proportion of elements that are typed rather than `Data`;
/// - unless the document allows `Unknown`, the proportion of records whose `EventDetail` names a real
///   branch rather than a bare `Unknown` (a learned admission, i.e. an `Unknown` carrying the
///   [stroom.learningflow.shared.LearnedUnknown] marker, is not counted against a record);
/// - for each field the document requires, the proportion of records that have it.
///
/// A transform that emits the mandatory skeleton, puts `Unknown` where a decision was due and dumps every
/// field into `Data` validates perfectly against the schema, but scores low here. It applies only to a
/// step that transformed records into records.
///
/// `Data` and `Unknown` are event-logging elements, so the first two measures are taken only where the
/// target is event-logging, or there is no target: another schema may have an element called `Data` that
/// means something. For another schema only the required fields are measured, and where none is required
/// there is nothing to measure and the check does not run.
public final class ExtractionQualityScorer implements Scorer {

    private static final ElementId QUALITY = new ElementId("ExtractionQuality");
    private static final String ALL_ELEMENTS = "descendant-or-self::*";
    // A learned admission is not counted: an Unknown carrying the marker is what an exhausted session was
    // allowed to write, and the marker item is not untyped data. A bare Unknown is still refused.
    private static final String DATA_ELEMENTS = Admission.DATA_BUT_MARKER;
    private static final String UNKNOWN_DETAIL = Admission.BARE_UNKNOWN;

    @Override
    public Check type() {
        return Check.QUALITY;
    }

    @Override
    public void validate(final ScorerParameters parameters) {
        for (final String field : ((ExtractionQualityParameters) parameters).getRequiredFields()) {
            OutputRecords.check(present(field));
        }
    }

    @Override
    public Optional<Score> score(final ScorerParameters parameters,
                                 final Attempted step,
                                 final TargetSchema target) {
        final Optional<OutputRecords> parsed = OutputRecords.ofTransformed(step);
        if (parsed.isEmpty() || parsed.get().records().isEmpty()) {
            return Optional.empty();
        }
        final OutputRecords output = parsed.get();
        final ExtractionQualityParameters quality = (ExtractionQualityParameters) parameters;
        final boolean eventLogging = TargetSchema.isEventLoggingOrNone(target);
        if (!eventLogging && quality.getRequiredFields().isEmpty()) {
            return Optional.empty();
        }
        final List<XdmNode> records = output.records();
        final List<StoredError> diagnostics = new ArrayList<>();
        final List<Double> measures = new ArrayList<>();

        // The records that fell short individually: a record that names Unknown where the document does
        // not allow it, or that lacks a required field. The typed-element ratio measures the output as a
        // whole and does not fail individual records.
        final Set<Integer> failingRecords = new TreeSet<>();
        if (eventLogging) {
            measureEventLogging(quality, output, records, diagnostics, measures, failingRecords);
        }
        for (final String field : quality.getRequiredFields()) {
            long present = 0;
            for (int i = 0; i < records.size(); i++) {
                if (output.holds(records.get(i), present(field))) {
                    present++;
                } else {
                    failingRecords.add(i);
                }
            }
            final double coverage = (double) present / records.size();
            measures.add(coverage);
            if (present < records.size()) {
                diagnostics.add(diagnostic("Required field " + field + " is present in " + present + " of "
                                           + records.size() + " records"));
            }
        }
        final double value = measures.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        return Optional.of(new Score(type(), value, diagnostics, failingRecords));
    }

    // Measures the typed-element ratio and, unless the document allows Unknown, the share of records
    // naming a real branch. Both are specific to event-logging.
    private static void measureEventLogging(final ExtractionQualityParameters quality,
                                            final OutputRecords output,
                                            final List<XdmNode> records,
                                            final List<StoredError> diagnostics,
                                            final List<Double> measures,
                                            final Set<Integer> failingRecords) {
        long elements = 0;
        long data = 0;
        long unknown = 0;
        for (int i = 0; i < records.size(); i++) {
            final XdmNode record = records.get(i);
            elements += output.count(record, ALL_ELEMENTS);
            data += output.count(record, DATA_ELEMENTS);
            if (output.holds(record, UNKNOWN_DETAIL)) {
                unknown++;
                if (!quality.isAllowUnknownEventDetail()) {
                    failingRecords.add(i);
                }
            }
        }
        final double typed = elements == 0
                ? 0.0
                : (double) (elements - data) / elements;
        measures.add(typed);
        if (data > 0) {
            diagnostics.add(diagnostic("Typed-element ratio " + round(typed) + ": " + data + " of " + elements
                                       + " elements are untyped Data"));
        }
        if (!quality.isAllowUnknownEventDetail()) {
            final double known = 1.0 - (double) unknown / records.size();
            measures.add(known);
            if (unknown > 0) {
                diagnostics.add(diagnostic(unknown + " of " + records.size()
                                           + " records name Unknown in EventDetail; Unknown extracts nothing"));
            }
        }
    }

    private static String present(final String field) {
        return field + "[normalize-space(.) != '']";
    }

    private static StoredError diagnostic(final String message) {
        return new StoredError(Severity.WARNING, null, QUALITY, message);
    }

    private static String round(final double value) {
        return String.format("%.2f", value);
    }
}
