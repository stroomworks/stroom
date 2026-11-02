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

package stroom.shapeshifter.ai.scoring;

import stroom.pipeline.errorhandler.ErrorReceiver;
import stroom.pipeline.errorhandler.ErrorReceiverProxy;
import stroom.pipeline.filter.AbstractXMLFilter;
import stroom.pipeline.filter.SchemaFilter;
import stroom.pipeline.filter.SplitFilter;
import stroom.pipeline.xmlschema.FindXMLSchemaCriteria;
import stroom.pipeline.xmlschema.XmlSchemaStore;
import stroom.security.api.SecurityContext;
import stroom.shapeshifter.shared.SchemaConformanceParameters;
import stroom.shapeshifter.shared.ScorerParameters;
import stroom.shapeshifter.shared.ScorerType;
import stroom.util.shared.ElementId;
import stroom.util.shared.ErrorType;
import stroom.util.shared.Location;
import stroom.util.shared.Severity;
import stroom.util.shared.StoredError;
import stroom.xmlschema.shared.XmlSchemaDoc;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Schema conformance (design 01 §8.2), a gate by ruling A16: the proportion of records that validate
 * against the schemas of the document's schema group, judged per record as {@code SchemaFilterSplit}
 * judges them — the output split one top-level element at a time into Stroom's own {@code SchemaFilter}.
 * The feedback is the first failing records' messages, as Stroom post-processes them for people:
 * "Invalid content was found starting with element 'EventDetail'. One of '{EventSource}' is expected." —
 * followed by what the schema says the element in question may contain ({@link ContentModels}), since a
 * validator names one missing child at a time and a model told only that climbs the content model one
 * rung per candidate (design 02 §6.2). Applies to a step that transformed records into records; a
 * parser's records are the input's shape and are judged by the stream-level scorers (design 01 §4).
 * <p>
 * The schema filter is pipeline-scoped in a node and reports through the pipeline's error receiver, which
 * is swapped for a recording one for the length of the run — the same arrangement as
 * {@code DataSplitterCompiler}, with the same consequence: an instance is for one thread.
 */
public final class SchemaConformanceScorer implements Scorer {

    private static final ElementId CONFORMANCE = new ElementId("SchemaConformance");
    private static final int RECORDS_SHOWN = 5;

    private static final Pattern INCOMPLETE = Pattern.compile("The content of element '([^']+)' is not complete");
    private static final Pattern UNEXPECTED = Pattern.compile(
            "Invalid content was found starting with element '\\{?([^'}]+)\\}?'\\. "
            + "One of '\\{([^}]*)\\}' is expected");
    private static final int HINTS_SHOWN = 3;

    private final Provider<SchemaFilter> schemaFilters;
    private final ErrorReceiverProxy errorReceiverProxy;
    private final XmlSchemaStore schemaStore;
    private final SecurityContext securityContext;
    private final Map<String, ContentModels> contentModels = new HashMap<>();

    @Inject
    public SchemaConformanceScorer(final Provider<SchemaFilter> schemaFilters,
                                   final ErrorReceiverProxy errorReceiverProxy,
                                   final XmlSchemaStore schemaStore,
                                   final SecurityContext securityContext) {
        this.schemaFilters = schemaFilters;
        this.errorReceiverProxy = errorReceiverProxy;
        this.schemaStore = schemaStore;
        this.securityContext = securityContext;
    }

    @Override
    public ScorerType type() {
        return ScorerType.SCHEMA_CONFORMANCE;
    }

    @Override
    public Optional<Score> score(final ScorerParameters parameters, final Attempted step) {
        final String output = step.result().output();
        final Optional<OutputRecords> parsed = OutputRecords.ofTransformed(step)
                .filter(records -> !records.records().isEmpty());
        if (parsed.isEmpty()) {
            return Optional.empty();
        }
        final int total = parsed.get().records().size();
        final SchemaConformanceParameters conformance = (SchemaConformanceParameters) parameters;
        final RecordErrors errors = new RecordErrors();
        final RecordCounter counter = new RecordCounter(errors);
        final ErrorReceiver previous = errorReceiverProxy.getErrorReceiver();
        errorReceiverProxy.setErrorReceiver(errors);
        try {
            final SchemaFilter schemaFilter = schemaFilters.get();
            schemaFilter.setElementId(CONFORMANCE);
            final FindXMLSchemaCriteria constraint = new FindXMLSchemaCriteria();
            constraint.setSchemaGroup(conformance.getSchemaGroup());
            schemaFilter.setSchemaConstraint(constraint);
            counter.setTarget(schemaFilter);
            final SplitFilter split = new SplitFilter();
            split.setSplitCount(1);
            split.setTarget(counter);

            split.startProcessing();
            try {
                split.startStream();
                final XMLReader reader = ConfinedXml.reader();
                reader.setContentHandler(split);
                reader.parse(new InputSource(new StringReader(output)));
                split.endStream();
            } finally {
                split.endProcessing();
            }
        } catch (final SAXException | IOException | RuntimeException e) {
            // The output parsed once already, to be counted; a failure here is the validator's, and every
            // record it did not get to judge is a record that did not conform.
            errors.log(Severity.FATAL_ERROR, null, CONFORMANCE, e.getMessage(), ErrorType.GENERIC, e);
        } finally {
            errorReceiverProxy.setErrorReceiver(previous);
        }

        // Records the validator never reached count as failing, not as conforming.
        final int passing = Math.max(0, counter.records - errors.failing.size());
        final int failing = total - passing;
        final List<StoredError> diagnostics = new ArrayList<>();
        // The schema's own account first: it is what turns one missing child per candidate into one step.
        if (!errors.failing.isEmpty()) {
            diagnostics.addAll(hints(conformance.getSchemaGroup(), errors.failing.values()));
        }
        int shown = 0;
        for (final Map.Entry<Integer, String> failure : errors.failing.entrySet()) {
            if (shown++ == RECORDS_SHOWN) {
                diagnostics.add(new StoredError(Severity.WARNING, null, CONFORMANCE,
                        (failing - RECORDS_SHOWN) + " more record(s) did not validate"));
                break;
            }
            diagnostics.add(new StoredError(Severity.WARNING, null, CONFORMANCE,
                    "Record " + failure.getKey() + ": " + failure.getValue()));
        }
        // The validator counts records from one; the output's records are indexed from zero. An error
        // raised before any record began — the validator failing to start — is keyed zero and names no
        // record. A record the validator never reached is failing too, and is every index from the last
        // it reached.
        final Set<Integer> failingRecords = new TreeSet<>();
        for (final int record : errors.failing.keySet()) {
            if (record >= 1) {
                failingRecords.add(record - 1);
            }
        }
        for (int record = counter.records; record < total; record++) {
            failingRecords.add(record);
        }
        return Optional.of(new Score(type(), (double) passing / total, diagnostics, failingRecords));
    }

    /**
     * What the schema says about the elements the messages complain of: the whole content model of an
     * element reported incomplete, and of the parents that could hold an unexpected child beside what
     * was expected instead. A few, distinct, so the feedback stays readable.
     */
    private List<StoredError> hints(final String schemaGroup, final Collection<String> messages) {
        final Set<String> texts = new LinkedHashSet<>();
        final Set<String> misplaced = new LinkedHashSet<>();
        try {
            final ContentModels models = contentModels.computeIfAbsent(schemaGroup, this::load);
            for (final String message : messages) {
                final Matcher incomplete = INCOMPLETE.matcher(message);
                if (incomplete.find()) {
                    texts.addAll(models.describe(local(incomplete.group(1))));
                }
                final Matcher unexpected = UNEXPECTED.matcher(message);
                if (unexpected.find()) {
                    final String found = local(unexpected.group(1));
                    final List<String> expected = Arrays.stream(unexpected.group(2).split(","))
                            .map(SchemaConformanceScorer::local)
                            .filter(name -> !name.isEmpty())
                            .toList();
                    if (expected.contains(found)) {
                        // The right name in the wrong namespace: a literal result element outside the
                        // stylesheet's default namespace, most often.
                        misplaced.add(found + " was found where " + found + " is expected, so it is in the wrong "
                                      + "namespace or none" + models.namespace()
                                              .map(ns -> ": declare xmlns=\"" + ns + "\" on the xsl:stylesheet "
                                                         + "element so every literal result element is in it")
                                              .orElse(""));
                    } else {
                        texts.addAll(models.describeParentsOf(found, expected));
                    }
                }
                if (texts.size() >= HINTS_SHOWN) {
                    break;
                }
            }
        } catch (final RuntimeException e) {
            // The hint is best effort; the score and the validator's own messages stand without it.
            return List.of();
        }
        final List<StoredError> hints = new ArrayList<>();
        misplaced.forEach(text -> hints.add(new StoredError(Severity.WARNING, null, CONFORMANCE, text)));
        for (final String text : texts.stream().limit(HINTS_SHOWN).toList()) {
            hints.add(new StoredError(Severity.WARNING, null, CONFORMANCE, "The schema says: " + text
                                                                            + ". Add every required child at once, "
                                                                            + "not one per attempt."));
        }
        if (!texts.isEmpty()) {
            hints.add(new StoredError(Severity.INFO, null, CONFORMANCE, "Notation: " + ContentModels.legend()));
        }
        return hints;
    }

    /**
     * The group's schemas as the validator reads them — as the processing user, since the stream's user
     * need not be able to read schema documents to have their output judged against them.
     */
    private ContentModels load(final String schemaGroup) {
        final FindXMLSchemaCriteria criteria = new FindXMLSchemaCriteria();
        criteria.setSchemaGroup(schemaGroup);
        return securityContext.asProcessingUserResult(() -> new ContentModels(
                schemaStore.find(criteria).getValues().stream()
                        .map(XmlSchemaDoc::getData)
                        .filter(Objects::nonNull)
                        .toList()));
    }

    /**
     * A name as Xerces writes it in a message — maybe quoted, maybe prefixed or in braces — as a local name.
     */
    private static String local(final String written) {
        final String bare = written.trim().replace("\"", "").replace("{", "").replace("}", "");
        return bare.substring(bare.lastIndexOf(':') + 1);
    }

    /**
     * Sits between the split and the schema filter, so that every error the validator raises can be laid
     * at the record whose document is passing through.
     */
    private static final class RecordCounter extends AbstractXMLFilter {

        private final RecordErrors errors;
        private int records;

        private RecordCounter(final RecordErrors errors) {
            this.errors = errors;
        }

        @Override
        public void startDocument() throws SAXException {
            records++;
            errors.current = records;
            super.startDocument();
        }
    }

    /**
     * The first error at or above {@code ERROR} for each record that raised one, by record number.
     */
    private static final class RecordErrors implements ErrorReceiver {

        private final Map<Integer, String> failing = new LinkedHashMap<>();
        private int current;

        @Override
        public void log(final Severity severity,
                        final Location location,
                        final ElementId elementId,
                        final String message,
                        final ErrorType errorType,
                        final Throwable e) {
            if (severity.greaterThanOrEqual(Severity.ERROR)) {
                failing.putIfAbsent(current, message);
            }
        }
    }
}
