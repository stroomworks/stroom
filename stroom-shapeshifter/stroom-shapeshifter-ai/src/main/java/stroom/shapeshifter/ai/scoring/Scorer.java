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

import stroom.shapeshifter.shared.ScorerParameters;
import stroom.shapeshifter.shared.ScorerType;

import java.util.Optional;

/**
 * The scoring SPI of design §3 and §8.4: one implementation per {@link ScorerType}, measuring one signal
 * over one step's output. A scorer that has nothing to measure on a step — coverage on a transform,
 * yield per record on raw text — returns empty and is neither counted nor failed; the scorecard applies
 * a scorer only where its signal exists.
 */
public interface Scorer {

    ScorerType type();

    /**
     * @param parameters The document's parameters for this scorer, of the class that belongs to
     *                   {@link #type()}; null for a scorer that takes none.
     */
    Optional<Score> score(ScorerParameters parameters, Attempted step);

    /**
     * Refuse parameters this scorer cannot work with — a path that does not compile — when the
     * scorecard is built, before a model is asked or a stream fails on them.
     *
     * @throws IllegalArgumentException Saying what is wrong with the document's setting.
     */
    default void validate(final ScorerParameters parameters) {
    }
}
