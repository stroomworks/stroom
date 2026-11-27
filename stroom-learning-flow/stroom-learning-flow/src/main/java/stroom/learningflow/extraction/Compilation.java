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

import stroom.pipeline.xml.converter.ParserFactory;
import stroom.util.shared.StoredError;

import java.util.List;

/// The result of compiling a Data Splitter configuration proposed by the model, from [DataSplitterCompiler].
///
/// A configuration either compiles into a parser factory that can be run over input, or is rejected with the
/// diagnostics that say why. A rejected configuration is never run. Its diagnostics are sent back to the model,
/// because they say exactly what is wrong, which is the most useful feedback it can get.
public sealed interface Compilation {

    /// @return Every diagnostic the compile produced, warnings included. Empty for a clean compile.
    List<StoredError> diagnostics();

    /// A configuration the Data Splitter accepted. It may still have produced warnings.
    record Compiled(ParserFactory parserFactory, List<StoredError> diagnostics) implements Compilation {

    }

    /// A configuration the Data Splitter refused, because it was not well-formed XML, did not validate against
    /// the Data Splitter schema, or could not be built into a usable parser.
    record Rejected(List<StoredError> diagnostics) implements Compilation {

    }
}
