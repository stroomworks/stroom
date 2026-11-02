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

import stroom.pipeline.xml.converter.ParserFactory;
import stroom.util.shared.StoredError;

import java.util.List;

/**
 * The outcome of the compile gate (design §8.1). A Data Splitter configuration either becomes a parser
 * factory that can be run over input, or is rejected with the diagnostics that say why. A rejected
 * configuration scores zero and never runs; its diagnostics are the most valuable feedback the model
 * can receive, because they say exactly what is wrong rather than merely that something is.
 */
public sealed interface Compilation {

    /**
     * @return Every diagnostic the compile produced, warnings included. Empty for a clean compile.
     */
    List<StoredError> diagnostics();

    /**
     * A configuration the Data Splitter accepted. It may still have produced warnings.
     */
    record Compiled(ParserFactory parserFactory, List<StoredError> diagnostics) implements Compilation {

    }

    /**
     * A configuration the Data Splitter refused: it was not well-formed XML, did not validate against the
     * Data Splitter schema, or did not link into a usable parser.
     */
    record Rejected(List<StoredError> diagnostics) implements Compilation {

    }
}
