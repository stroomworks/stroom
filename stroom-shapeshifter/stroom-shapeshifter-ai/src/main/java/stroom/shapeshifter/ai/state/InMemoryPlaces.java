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

package stroom.shapeshifter.ai.state;

import stroom.shapeshifter.ai.stage.Learner;
import stroom.shapeshifter.ai.stage.Places;
import stroom.util.shared.DocPath;

import java.util.HashMap;
import java.util.Map;

/// Places as a scenario sets them. A learner it was told nothing of has no pipeline here to sit beside,
/// so its content goes in a `Shapeshifter` folder under its pipeline's uuid, which is the name the
/// scenarios read. Every method is synchronised.
public final class InMemoryPlaces implements Places {

    private static final DocPath FOLDER = DocPath.fromParts("Shapeshifter");

    private final Map<Learner, Place> places = new HashMap<>();

    @Override
    public synchronized Place of(final Learner learner) {
        return places.getOrDefault(learner, new Place(FOLDER, learner.pipelineUuid()));
    }

    /// What the learner's element says, as a factory or a person would set it.
    ///
    /// @param learner The learner.
    /// @param place   Where what it learns goes.
    public synchronized void put(final Learner learner, final Place place) {
        places.put(learner, place);
    }
}
