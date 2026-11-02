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

import stroom.pipeline.shared.TextConverterDoc;
import stroom.pipeline.shared.TextConverterDoc.TextConverterType;
import stroom.shapeshifter.ai.extraction.Compilation.Compiled;
import stroom.shapeshifter.ai.extraction.Compilation.Rejected;
import stroom.shapeshifter.ai.learning.InputKind;
import stroom.shapeshifter.ai.learning.StepResult;
import stroom.shapeshifter.ai.learning.StepRunner;

import java.util.Optional;

/**
 * The {@code DSParser} element as a step of the A21 conversation: compile the candidate through the gate of
 * §8.1, then run it over the input. The output is the {@code records:2} document the next element sees.
 */
public final class DataSplitterStep implements StepRunner {

    public static final String ELEMENT_TYPE = "DSParser";
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

    /// Raw text, so the split question is the configuration that cuts it.
    @Override
    public InputKind consumes() {
        return InputKind.TEXT;
    }

    @Override
    public StepResult run(final String configuration, final String input) {
        return switch (compiler.compile(configuration)) {
            case Rejected rejected -> new StepResult(null, rejected.diagnostics());
            case Compiled compiled -> {
                final ExtractionResult result = DataSplitterRunner.run(compiled, input);
                yield new StepResult(result.records(), result.diagnostics(), result.recordRanges());
            }
        };
    }
}
