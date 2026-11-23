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

/**
 * Extraction quality, the anti-degeneracy scorer of design 01 §8.3 (ruling A16): how much of the output
 * means something. Three measures over the records, averaged — the proportion of elements that are typed
 * rather than {@code Data}; the proportion of records whose {@code EventDetail} names a real branch
 * rather than {@code Unknown} — a learned admission, an {@code Unknown} carrying the marker of A62, being
 * neither (A53) — unless the document allows {@code Unknown}; and the proportion of records
 * carrying each field the document requires. A transform that emits the mandatory skeleton, puts
 * {@code Unknown} where a decision was due and dumps every field into {@code Data} validates perfectly
 * and scores here as the nothing it extracted. Applies to a step that transformed records into records.
 *
 * <p>{@code Data} and {@code Unknown} are event-logging's, so the first two measures are taken only where
 * the target is event-logging, or none (task 7, W1-4): another schema may have an element called
 * {@code Data} that means something. For another schema only the required fields are measured, and where
 * none is required there is nothing to measure and the check doesn't run.
 */
public final class ExtractionQualityScorer implements Scorer {

    private static final ElementId QUALITY = new ElementId("ExtractionQuality");
    private static final String ALL_ELEMENTS = "descendant-or-self::*";
    // A learned admission (A53, A62) is not counted: an Unknown carrying the marker is what an exhausted
    // session was allowed to say, and the marker item is not untyped data. A bare Unknown is refused as it
    // always was.
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

        // Which records fell short, one by one (design 04 §3): a record that names Unknown where the
        // document does not allow it, or that lacks a required field. The typed-element ratio is a
        // measure of the whole and lays nothing at a record.
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

    // The typed-element ratio and, unless the document allows Unknown, the share of records naming a real
    // branch: both event-logging's
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
