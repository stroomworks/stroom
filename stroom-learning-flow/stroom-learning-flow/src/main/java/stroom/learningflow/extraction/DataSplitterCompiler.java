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

package stroom.learningflow.extraction;

import stroom.learningflow.extraction.Compilation.Compiled;
import stroom.learningflow.extraction.Compilation.Rejected;
import stroom.pipeline.DefaultLocationFactory;
import stroom.pipeline.errorhandler.ErrorHandlerAdaptor;
import stroom.pipeline.errorhandler.ErrorReceiver;
import stroom.pipeline.errorhandler.ErrorReceiverProxy;
import stroom.pipeline.errorhandler.StoredErrorReceiver;
import stroom.pipeline.xml.converter.ds3.DS3ParserFactory;
import stroom.util.shared.ElementId;
import stroom.util.shared.Severity;
import stroom.util.shared.StoredError;

import jakarta.inject.Inject;
import jakarta.inject.Provider;

import java.io.StringReader;
import java.util.List;

/// Compiles Data Splitter configuration text proposed by the model into a runnable parser, or into the
/// diagnostics that explain why it cannot be run. [DataSplitterStep] uses it before running a candidate.
///
/// The configuration is compiled straight from text and nothing is written to a document store, so a
/// candidate can be judged before anything is saved.
///
/// `DS3ParserFactory` and the `ErrorReceiverProxy` it reports through are pipeline-scoped, so an instance must
/// be used inside a pipeline scope. It must not be shared between threads, because the proxy swap in
/// [#compile] is not synchronised.
public class DataSplitterCompiler {

    /// The element id the diagnostics are attributed to. It is the element that would run the configuration
    /// in a pipeline, so that the text matches what an operator would see in the stepper for the same fault.
    static final ElementId ELEMENT_ID = new ElementId("DS3Parser");

    private final Provider<DS3ParserFactory> parserFactoryProvider;
    private final ErrorReceiverProxy errorReceiverProxy;

    /// @param parserFactoryProvider Supplies a fresh Data Splitter parser factory for each compile.
    /// @param errorReceiverProxy    The proxy the schema validation behind the factory reports through. It is
    ///                              pipeline-scoped, so a compile points it at its own receiver for the
    ///                              duration, as a pipeline run does.
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
        // The proxy is pipeline-scoped and may already point at a running pipeline's receiver, so the
        // previous receiver is put back afterwards rather than cleared.
        final ErrorReceiver previous = errorReceiverProxy.getErrorReceiver();
        errorReceiverProxy.setErrorReceiver(errorReceiver);
        try {
            parserFactory.configure(new StringReader(configuration), errorHandler);
        } catch (final RuntimeException e) {
            // An XML parse failure reaches the error handler first and is then rethrown wrapped, so it has
            // already been recorded by the time it gets here. The Data Splitter's own compile faults (an
            // undefined variable reference, a duplicate id, a regex that does not compile) arrive as bare
            // runtime exceptions with nothing recorded, so the message is recorded here. Either way the
            // configuration is rejected with a diagnostic the model can act on, rather than failing the run.
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
