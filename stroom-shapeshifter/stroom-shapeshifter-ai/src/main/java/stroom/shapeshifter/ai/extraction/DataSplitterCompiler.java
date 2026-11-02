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

package stroom.shapeshifter.ai.extraction;

import stroom.pipeline.DefaultLocationFactory;
import stroom.pipeline.errorhandler.ErrorHandlerAdaptor;
import stroom.pipeline.errorhandler.ErrorReceiver;
import stroom.pipeline.errorhandler.ErrorReceiverProxy;
import stroom.pipeline.errorhandler.StoredErrorReceiver;
import stroom.pipeline.xml.converter.ds3.DS3ParserFactory;
import stroom.shapeshifter.ai.extraction.Compilation.Compiled;
import stroom.shapeshifter.ai.extraction.Compilation.Rejected;
import stroom.util.shared.ElementId;
import stroom.util.shared.Severity;
import stroom.util.shared.StoredError;

import jakarta.inject.Inject;
import jakarta.inject.Provider;

import java.io.StringReader;
import java.util.List;

/**
 * The compile gate for the extraction stage (design §8.1): turns Data Splitter configuration text into a
 * runnable parser, or into the diagnostics explaining why it cannot be run.
 * <p>
 * Nothing is written to a document store on the way through. The configuration is compiled from text,
 * which is what lets a candidate be scored before anything persists it (design §7.3, rule 1).
 * <p>
 * Scope: {@code DS3ParserFactory} and the proxy it reports through are pipeline-scoped in a node. This
 * class is constructed by hand in the harness; when it is bound for a node it must be given a pipeline
 * scope to run in, and it must not be shared between threads, since the proxy swap in {@link #compile}
 * is not synchronised.
 */
public class DataSplitterCompiler {

    /**
     * Diagnostics are attributed to the element that would run the configuration in a pipeline, so that
     * their text matches what an operator would see in the stepper for the same fault.
     */
    static final ElementId ELEMENT_ID = new ElementId("DS3Parser");

    private final Provider<DS3ParserFactory> parserFactoryProvider;
    private final ErrorReceiverProxy errorReceiverProxy;

    /**
     * @param errorReceiverProxy The proxy the schema validation behind the factory reports through. It is
     *                           pipeline-scoped in a node, so a compile points it at its own receiver for
     *                           the duration, exactly as a pipeline run does.
     */
    @Inject
    public DataSplitterCompiler(final Provider<DS3ParserFactory> parserFactoryProvider,
                                final ErrorReceiverProxy errorReceiverProxy) {
        this.parserFactoryProvider = parserFactoryProvider;
        this.errorReceiverProxy = errorReceiverProxy;
    }

    public Compilation compile(final String configuration) {
        final StoredErrorReceiver errorReceiver = new StoredErrorReceiver();
        final ErrorHandlerAdaptor errorHandler = new ErrorHandlerAdaptor(
                ELEMENT_ID,
                new DefaultLocationFactory(),
                errorReceiver);

        // Each factory holds the compiled state of one configuration, so a fresh one is taken per compile.
        final DS3ParserFactory parserFactory = parserFactoryProvider.get();
        // The proxy is pipeline-scoped and may already be pointed at a running pipeline's receiver; it is
        // borrowed for the compile and handed back, not cleared.
        final ErrorReceiver previous = errorReceiverProxy.getErrorReceiver();
        errorReceiverProxy.setErrorReceiver(errorReceiver);
        try {
            parserFactory.configure(new StringReader(configuration), errorHandler);
        } catch (final RuntimeException e) {
            // A parse failure reaches the error handler first and is then rethrown, wrapped, so it is
            // already on record by the time it arrives here. The Data Splitter's own compile faults — an
            // undefined variable reference, a duplicate id, a regex that does not compile — arrive as bare
            // runtime exceptions with nothing on record. Either way the configuration is rejected with the
            // message as its diagnostic, which is what the next candidate needs; it is never a crash.
            if (errorReceiver.getCount(Severity.FATAL_ERROR) == 0) {
                errorReceiver.log(Severity.FATAL_ERROR, null, ELEMENT_ID, e.getMessage(), e);
            }
        } finally {
            errorReceiverProxy.setErrorReceiver(previous);
        }

        final List<StoredError> diagnostics = List.copyOf(errorReceiver.getList());
        if (errorReceiver.getCount(Severity.ERROR) > 0 || errorReceiver.getCount(Severity.FATAL_ERROR) > 0) {
            return new Rejected(diagnostics);
        }
        return new Compiled(parserFactory, diagnostics);
    }
}
