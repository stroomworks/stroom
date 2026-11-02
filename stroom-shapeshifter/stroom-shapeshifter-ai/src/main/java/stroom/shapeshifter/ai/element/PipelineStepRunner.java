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

package stroom.shapeshifter.ai.element;

import stroom.pipeline.errorhandler.ErrorReceiver;
import stroom.pipeline.errorhandler.ErrorReceiverProxy;
import stroom.pipeline.errorhandler.LoggedException;
import stroom.pipeline.errorhandler.LoggingErrorReceiver;
import stroom.pipeline.factory.InjectedCode;
import stroom.pipeline.factory.Pipeline;
import stroom.pipeline.factory.PipelineFactory;
import stroom.pipeline.shared.data.PipelineData;
import stroom.pipeline.shared.data.PipelineDataBuilder;
import stroom.pipeline.shared.data.PipelineDataUtil;
import stroom.pipeline.shared.data.PipelineElement;
import stroom.pipeline.stepping.capture.HeadlessCapture;
import stroom.shapeshifter.ai.extraction.PerRecord;
import stroom.shapeshifter.ai.extraction.RecordJoin;
import stroom.shapeshifter.ai.learning.InputKind;
import stroom.shapeshifter.ai.learning.StepResult;
import stroom.shapeshifter.ai.learning.StepRunner;
import stroom.task.api.TaskContextFactory;
import stroom.util.shared.ElementId;
import stroom.util.shared.Indicators;
import stroom.util.shared.Severity;
import stroom.util.shared.StoredError;

import jakarta.inject.Provider;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/// One element of a candidate chain, run as the pipeline will run it (§12 item 2): the real element,
/// the real pools, the real function library, given the candidate's configuration without that
/// configuration having been written anywhere (§12 item 1).
///
/// The conversation asks one element at a time, each with what the element before it wrote, and that is
/// what this preserves: a pipeline is built for this element alone — behind a parser where the element
/// is a filter, since a filter is pushed SAX events and what it is given here is text — the
/// configuration is injected, the stream is processed and what the element wrote is captured.
///
/// It stands in the same relation to [stroom.shapeshifter.ai.transformation.XsltStep] and its
/// neighbours as [stroom.shapeshifter.ai.fragment.PipelineFragmentRunner] does to the stand-in that
/// walks a fragment: the module's runners are what Tier 1 runs on, needing no node, and these are what
/// a node judges with. They are held to the same answers by a Tier 2 scenario, because a stand-in that
/// has drifted would have the model correcting a fault the pipeline does not have.
public final class PipelineStepRunner implements StepRunner {

    private static final String SOURCE = "Source";
    /// What turns text into the events a filter expects. The fragment uses a `SplitFilter` for this and
    /// this uses a parser, because the fragment is given a stream and this is given one element's
    /// worth of records; the events that reach the element are the same either way.
    private static final String XML_PARSER = "XMLParser";
    private static final String XML_PARSER_ID = "xmlParser";

    /// A judgement is made on a sample (§3), and the capture holds what it keeps in memory.
    private static final int MOST_RECORDS = 10_000;

    private final StepRunner describes;
    private final Provider<PipelineFactory> pipelineFactoryProvider;
    private final Provider<HeadlessCapture> captureProvider;
    private final Provider<InjectedCode> injectedCodeProvider;
    private final Provider<ErrorReceiverProxy> errorReceiverProvider;
    private final TaskContextFactory taskContextFactory;

    /// @param describes The module's runner for this element type, which says what the element is
    ///                  called, what document it takes and whether it parses. What it *does* is what
    ///                  this replaces.
    public PipelineStepRunner(final StepRunner describes,
                              final Provider<PipelineFactory> pipelineFactoryProvider,
                              final Provider<HeadlessCapture> captureProvider,
                              final Provider<InjectedCode> injectedCodeProvider,
                              final Provider<ErrorReceiverProxy> errorReceiverProvider,
                              final TaskContextFactory taskContextFactory) {
        this.describes = describes;
        this.pipelineFactoryProvider = pipelineFactoryProvider;
        this.captureProvider = captureProvider;
        this.injectedCodeProvider = injectedCodeProvider;
        this.errorReceiverProvider = errorReceiverProvider;
        this.taskContextFactory = taskContextFactory;
    }

    @Override
    public String elementType() {
        return describes.elementType();
    }

    @Override
    public String elementId() {
        return describes.elementId();
    }

    @Override
    public Optional<Configured> configured() {
        return describes.configured();
    }

    @Override
    public Optional<String> fixedConfiguration() {
        return describes.fixedConfiguration();
    }

    @Override
    public boolean parser() {
        return describes.parser();
    }

    @Override
    public InputKind consumes() {
        return describes.consumes();
    }

    @Override
    public StepResult run(final String configuration, final String input) {
        try (Prepared prepared = prepare(configuration)) {
            return prepared.run(input);
        }
    }

    /// The pipeline built once and given one record after another (§12 item 25): building it compiles
    /// the candidate, and compiling does not depend on the input, so a stylesheet judged over ten
    /// thousand records is compiled once rather than ten thousand times.
    ///
    /// What is taken while it is prepared is given back when it is closed: the error receiver that was
    /// in place, whatever the carrier held, and the pipeline's own `endProcessing`, which is what
    /// returns a pooled stylesheet.
    @Override
    public Prepared prepare(final String configuration) {
        final String id = elementId();
        final HeadlessCapture capture = captureProvider.get();
        capture.setMaxRecords(MOST_RECORDS);

        final ErrorReceiverProxy errorReceiverProxy = errorReceiverProvider.get();
        final ErrorReceiver previous = errorReceiverProxy.getErrorReceiver();
        final LoggingErrorReceiver logging = new LoggingErrorReceiver();
        errorReceiverProxy.setErrorReceiver(logging);

        // The candidate, which is written nowhere: this is a judgement, and §7.3 rule 1 says nothing is
        // written until it has been judged. An element whose configuration is its own business (§12
        // item 26) runs with that, since nobody was ever going to be asked for it.
        final InjectedCode injectedCode = injectedCodeProvider.get();
        final Map<String, String> borrowed = injectedCode.asMap();
        final String code = configuration == null
                ? fixedConfiguration().orElse(null)
                : configuration;
        injectedCode.set(code == null
                ? Map.of()
                : Map.of(id, code));

        final PipelineData chain = chain(id);
        final List<String> speaking = chain.getAddedElements().stream()
                .map(PipelineElement::getId)
                .filter(element -> !SOURCE.equals(element))
                .toList();
        Pipeline pipeline = null;
        String building = null;
        try {
            pipeline = pipelineFactoryProvider.get().create(chain, taskContextFactory.current(), capture);
            pipeline.startProcessing();
        } catch (final LoggedException e) {
            // Said through the receiver already: a configuration that will not compile refuses here.
            pipeline = null;
        } catch (final RuntimeException e) {
            building = e.getClass().getSimpleName() + ": " + e.getMessage();
            pipeline = null;
        }

        final Pipeline built = pipeline;
        final String refused = building;
        return new Prepared() {
            @Override
            public StepResult run(final String input) {
                if (built == null) {
                    return new StepResult(null, diagnostics(capture, 0, speaking, logging, refused));
                }
                // This run's records and no others: one capture serves every run of a prepared chain,
                // and a run that read past the last one would stop reading anything at all once the cap
                // was reached — the count would stop advancing and every later run would report having
                // produced nothing, with no diagnostic to say why.
                capture.clear();
                final int before = 0;
                String failure = null;
                try {
                    built.process(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                            StandardCharsets.UTF_8.name());
                } catch (final LoggedException e) {
                    failure = null;
                } catch (final RuntimeException e) {
                    failure = e.getClass().getSimpleName() + ": " + e.getMessage();
                }
                final List<String> written = capture.outputOf(id).stream()
                        .skip(before)
                        .filter(text -> text != null && !text.isBlank())
                        .toList();
                return new StepResult(RecordJoin.join(written),
                        diagnostics(capture, before, speaking, logging, truncated(capture, failure)));
            }

            @Override
            public void close() {
                try {
                    if (built != null) {
                        built.endProcessing();
                    }
                } finally {
                    injectedCode.set(borrowed);
                    errorReceiverProxy.setErrorReceiver(previous);
                }
            }
        };
    }

    /// The smallest pipeline that can run this element over the text it is given: the element behind the
    /// source, with a parser in between where the element is a filter and so expects events.
    private PipelineData chain(final String id) {
        final PipelineDataBuilder builder = new PipelineDataBuilder();
        builder.addElement(PipelineDataUtil.createElement(SOURCE, SOURCE, null, null));
        builder.addElement(PipelineDataUtil.createElement(id, elementType(), null, null));
        if (parser()) {
            builder.addLink(PipelineDataUtil.createLink(SOURCE, id));
        } else {
            builder.addElement(PipelineDataUtil.createElement(XML_PARSER_ID, XML_PARSER, null, null));
            builder.addLink(PipelineDataUtil.createLink(SOURCE, XML_PARSER_ID));
            builder.addLink(PipelineDataUtil.createLink(XML_PARSER_ID, id));
        }
        return builder.build();
    }

    /// What was said about this input, once each: every element of the little chain, because the
    /// element under test is not always the one that objects — text that is not well formed is refused
    /// by the parser standing in front of it, and a step that failed with nothing to say is a re-ask
    /// the model cannot act on.
    ///
    /// @param from Records before this one belong to an earlier input, since one prepared pipeline
    ///             captures every record it is given.
    /// What a run failed with, and — where the capture kept less than the run produced — that it did.
    /// A candidate judged on the first ten thousand records of a longer stream is judged on a sample of
    /// its own output, and saying so is the difference between a low score a person can read and one
    /// they cannot.
    private static String truncated(final HeadlessCapture capture, final String failure) {
        if (!capture.isTruncated()) {
            return failure;
        }
        final String said = "More than " + MOST_RECORDS + " records went through; what is judged is the "
                            + "first " + MOST_RECORDS + " of them";
        return failure == null
                ? said
                : failure + ". " + said;
    }

    private static List<StoredError> diagnostics(final HeadlessCapture capture,
                                                 final int from,
                                                 final List<String> elementIds,
                                                 final LoggingErrorReceiver logging,
                                                 final String failure) {
        final List<StoredError> errors = new ArrayList<>();
        for (final String elementId : elementIds) {
            capture.getRecords().stream().skip(from).forEach(record -> {
                final HeadlessCapture.ElementIo io = record.byElement().get(elementId);
                final Indicators indicators = io == null
                        ? null
                        : io.indicators();
                if (indicators != null) {
                    errors.addAll(indicators.getErrorList());
                }
            });
            final Indicators outside = logging.getIndicators(new ElementId(elementId));
            if (outside != null) {
                errors.addAll(outside.getErrorList());
            }
        }
        if (errors.isEmpty() && failure != null) {
            errors.add(new StoredError(Severity.FATAL_ERROR, null, new ElementId(elementIds.getFirst()),
                    failure));
        }
        return PerRecord.told(errors);
    }
}
