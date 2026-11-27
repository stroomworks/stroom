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

/// Runs one element of a candidate chain on a node, exactly as a pipeline will run it: the real element,
/// the real pools and the real function library, given the candidate's configuration without that
/// configuration being written to any document.
///
/// A learning session asks the model about one element at a time, each with what the element before it
/// wrote, and this class supports that. A pipeline is built for this element alone (behind an XML parser
/// where the element is a filter, since a filter expects SAX events and is given text here), the
/// configuration is injected, the input is processed, and what the element wrote is captured.
///
/// It relates to `stroom.learningflow.transformation.XsltStep` and the module's other step runners as
/// `stroom.learningflow.fragment.PipelineFragmentRunner` does to the stand-in fragment runner. The
/// module's runners are used by Tier 1 tests, which need no node, and these are what a node judges with.
/// A Tier 2 scenario test checks they give the same answers, because a stand-in that has drifted would
/// have the model correcting a fault the real pipeline does not have.
public final class PipelineStepRunner implements StepRunner {

    /// The id of the XML parser put in front of an element that is not a parser, to turn the text this
    /// runner is given into the SAX events a filter expects.
    private static final String XML_PARSER_ID = "xmlParser";

    private final StepRunner describes;
    private final Provider<PipelineFactory> pipelineFactoryProvider;
    private final Provider<HeadlessCapture> captureProvider;
    private final Provider<InjectedCode> injectedCodeProvider;
    private final Provider<ErrorReceiverProxy> errorReceiverProvider;
    private final TaskContextFactory taskContextFactory;

    /// @param describes The module's runner for this element type, which provides the element's type and
    ///                  id, the document it takes and whether it is a parser. This class replaces only how
    ///                  the element is run.
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

    /// Builds the pipeline once so it can be given one record after another. Building it compiles the
    /// candidate, and compiling does not depend on the input, so a stylesheet judged over ten thousand
    /// records is compiled once rather than ten thousand times.
    ///
    /// Closing the result restores what was replaced while it was prepared (the previous error receiver
    /// and injected code) and calls the pipeline's `endProcessing`, which returns a pooled stylesheet.
    @Override
    public Prepared prepare(final String configuration) {
        final String id = elementId();
        final HeadlessCapture capture = captureProvider.get();
        capture.setMaxRecords(PipelineRuns.MOST_RECORDS);

        final ErrorReceiverProxy errorReceiverProxy = errorReceiverProvider.get();
        final ErrorReceiver previous = errorReceiverProxy.getErrorReceiver();
        final LoggingErrorReceiver logging = new LoggingErrorReceiver();
        errorReceiverProxy.setErrorReceiver(logging);

        // Inject the candidate configuration rather than writing it anywhere, because nothing is written
        // until it has passed judgement. An element with a fixed configuration that the model is never
        // asked for runs with that configuration.
        final InjectedCode injectedCode = injectedCodeProvider.get();
        final Map<String, String> borrowed = injectedCode.asMap();
        final String code = configuration == null
                ? fixedConfiguration().orElse(null)
                : configuration;
        injectedCode.set(code == null
                ? Map.of()
                : Map.of(id, code));

        final PipelineData chain = chain(id);
        // Diagnostics are gathered from every element of this small chain, because the element under test
        // is not always the one that reports the problem: text that is not well formed is refused by the
        // XML parser in front of it. A step that failed with no reason would give the model nothing to
        // act on when it is asked again.
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
            // Already reported through the receiver; a configuration that will not compile fails here.
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
                // Clear the capture so it holds only this run's records. One capture serves every run of
                // a prepared chain, and without clearing it, it would stop recording once its cap was
                // reached, so every later run would report having produced nothing, with no diagnostic
                // to say why.
                capture.clear();
                String failure = null;
                try {
                    built.process(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                            StandardCharsets.UTF_8.name());
                } catch (final LoggedException e) {
                    // Already reported through the receiver, which is where the diagnostics are read from.
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

    /// Builds the smallest pipeline that can run this element over the text it is given: the element
    /// after the source, with an XML parser in between where the element is a filter and so expects events.
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

    /// The error a run failed with, plus a note where the capture kept fewer records than the run
    /// produced. A candidate run over more than [PipelineRuns#MOST_RECORDS] records is judged on only the
    /// first of them, and saying so lets a person make sense of a low score.
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
