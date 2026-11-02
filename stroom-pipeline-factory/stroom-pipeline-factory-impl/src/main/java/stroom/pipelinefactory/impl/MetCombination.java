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

package stroom.pipelinefactory.impl;

import stroom.docref.DocRef;

/// What a factory knows about one combination it has met (design 06 §3.4).
///
/// @param combination  The combination.
/// @param state        Where making its pipeline has got to.
/// @param tries        How many times the factory has tried to make it.
/// @param pipeline     The pipeline made for it; null until one is.
/// @param filterUuid   The UUID of the filter made for it; null until one is.
/// @param message      Why the last try failed; null if it did not.
/// @param createTimeMs When the factory first met it.
/// @param updateTimeMs When the factory last changed what it knows about it.
public record MetCombination(Combination combination,
                             State state,
                             int tries,
                             DocRef pipeline,
                             String filterUuid,
                             String message,
                             long createTimeMs,
                             long updateTimeMs) {


    // --------------------------------------------------------------------------------


    /// Where making a combination's pipeline has got to.
    public enum State {
        /// The factory has met it and is making its pipeline, or stopped part-way.
        PENDING,
        /// Its pipeline and filter exist, or did when they were made.
        MADE,
        /// Making them failed, and the factory will try again.
        FAILED
    }
}
