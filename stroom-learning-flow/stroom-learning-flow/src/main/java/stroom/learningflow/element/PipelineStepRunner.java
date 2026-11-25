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

package stroom.learningflow.element;

import stroom.learningflow.extraction.RecordJoin;
import stroom.learningflow.fragment.PipelineRuns;
import stroom.learningflow.learning.InputKind;
import stroom.learningflow.learning.StepResult;
import stroom.learningflow.learning.StepRunner;
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
import stroom.task.api.TaskContextFactory;

import jakarta.inject.Provider;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
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
/// It stands in the same relation to [stroom.learningflow.transformation.XsltStep] and its
/// neighbours as [stroom.learningflow.fragment.PipelineFragmentRunner] does to the stand-in that
/// walks a fragment: the module's runners are what Tier 1 runs on, needing no node, and these are what
/// a node judges with. They are held to the same answers by a Tier 2 scenario, because a stand-in that
/// has drifted would have the model correcting a fault the pipeline does not have.
public final class PipelineStepRunner implements StepRunner {

    /// What turns text into the events a filter expects. The fragment uses a `SplitFilter` for this and
    /// this uses a parser, because the fragment is given a stream and this is given one element's
    /// worth of records; the events that reach the element are the same either way.
    private static final String XML_PARSER_ID = "xmlParser";

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
        capture.setMaxRecords(PipelineRuns.MOST_RECORDS);

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
        // What is said about an input is heard from every element of the little chain, because the
        // element under test is not always the one that objects — text that is not well formed is refused
        // by the parser standing in front of it, and a step that failed with nothing to say is a re-ask
        // the model cannot act on.
        final List<String> speaking = chain.getAddedElements().stream()
                .map(PipelineElement::getId)
                .filter(element -> !PipelineRuns.SOURCE.equals(element))
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
                    return new StepResult(null, PipelineRuns.diagnostics(capture, speaking, logging, refused));
                }
                // This run's records and no others: one capture serves every run of a prepared chain,
                // and a run that read past the last one would stop reading anything at all once the cap
                // was reached — the count would stop advancing and every later run would report having
                // produced nothing, with no diagnostic to say why.
                capture.clear();
                String failure = null;
                try {
                    built.process(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                            StandardCharsets.UTF_8.name());
                } catch (final LoggedException e) {
                    // Said through the receiver already, which is where the diagnostics are read from.
                } catch (final RuntimeException e) {
                    failure = e.getClass().getSimpleName() + ": " + e.getMessage();
                }
                return new StepResult(RecordJoin.join(capture.outputOf(id)),
                        PipelineRuns.diagnostics(capture, speaking, logging, truncated(capture, failure)));
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
        builder.addElement(PipelineDataUtil.createElement(PipelineRuns.SOURCE, PipelineRuns.SOURCE, null, null));
        builder.addElement(PipelineDataUtil.createElement(id, elementType(), null, null));
        if (parser()) {
            builder.addLink(PipelineDataUtil.createLink(PipelineRuns.SOURCE, id));
        } else {
            builder.addElement(PipelineDataUtil.createElement(XML_PARSER_ID, PipelineRuns.XML_PARSER, null, null));
            builder.addLink(PipelineDataUtil.createLink(PipelineRuns.SOURCE, XML_PARSER_ID));
            builder.addLink(PipelineDataUtil.createLink(XML_PARSER_ID, id));
        }
        return builder.build();
    }

    /// What a run failed with, and — where the capture kept less than the run produced — that it did.
    /// A candidate judged on the first ten thousand records of a longer stream is judged on a sample of
    /// its own output, and saying so is the difference between a low score a person can read and one
    /// they cannot.
    private static String truncated(final HeadlessCapture capture, final String failure) {
        if (!capture.isTruncated()) {
            return failure;
        }
        final String said = "More than " + PipelineRuns.MOST_RECORDS + " records went through; what is "
                            + "judged is the first " + PipelineRuns.MOST_RECORDS + " of them";
        return failure == null
                ? said
                : failure + ". " + said;
    }
}
