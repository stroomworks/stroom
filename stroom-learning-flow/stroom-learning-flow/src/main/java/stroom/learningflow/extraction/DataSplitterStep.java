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
import stroom.learningflow.learning.InputKind;
import stroom.learningflow.learning.StepResult;
import stroom.learningflow.learning.StepRunner;
import stroom.pipeline.shared.TextConverterDoc;
import stroom.pipeline.shared.TextConverterDoc.TextConverterType;
import stroom.pipeline.shared.data.PipelineElementType;

import java.util.Optional;

/// Runs a Data Splitter configuration proposed by the model, as one step of a learning session.
///
/// The configuration is compiled first, so that one which fails to compile goes back to the model as
/// diagnostics rather than being run. One that compiles is run over the input, and its `records:2` XML output
/// becomes the input to the next element in the chain.
public final class DataSplitterStep implements StepRunner {

    public static final String ELEMENT_TYPE = PipelineElementType.TYPE_DS_PARSER;
    private static final Configured CONFIGURED = new Configured(TextConverterDoc.TYPE, "textConverter",
            TextConverterType.DATA_SPLITTER);

    private final DataSplitterCompiler compiler;

    public DataSplitterStep(final DataSplitterCompiler compiler) {
        this.compiler = compiler;
    }

    @Override
    public String elementType() {
        return ELEMENT_TYPE;
    }

    @Override
    public String elementId() {
        return "dsParser";
    }

    @Override
    public Optional<Configured> configured() {
        return Optional.of(CONFIGURED);
    }

    @Override
    public boolean parser() {
        return true;
    }

    /// The Data Splitter reads raw text, so the split question asks the model for a Data Splitter
    /// configuration that cuts the text into records.
    @Override
    public InputKind consumes() {
        return InputKind.TEXT;
    }

    @Override
    public StepResult run(final String configuration, final String input) {
        return switch (compiler.compile(configuration)) {
            case final Rejected rejected -> new StepResult(null, rejected.diagnostics());
            case final Compiled compiled -> {
                final ExtractionResult result = DataSplitterRunner.run(compiled, input);
                yield new StepResult(result.records(), result.diagnostics(), result.recordRanges());
            }
        };
    }
}
