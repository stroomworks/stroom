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

package stroom.learningflow.state;

import stroom.learningflow.stage.Learner;
import stroom.learningflow.stage.Places;
import stroom.util.shared.DocPath;

import java.util.HashMap;
import java.util.Map;

/// An in-memory [Places], which says where each learner's learned content is written, as a scenario sets
/// it. A learner the scenario has not set has no real pipeline here to put content beside, so its content
/// goes in a `Learning Flow` folder, named after its pipeline's UUID, which is the name the scenarios
/// expect. Every method is synchronised.
public final class InMemoryPlaces implements Places {

    private static final DocPath FOLDER = DocPath.fromParts("Learning Flow");

    private final Map<Learner, Place> places = new HashMap<>();

    @Override
    public synchronized Place of(final Learner learner) {
        return places.getOrDefault(learner, new Place(FOLDER, learner.pipelineUuid()));
    }

    /// Sets where the learner's content goes, as its element's properties would, set by a Content Template
    /// or a person.
    ///
    /// @param learner The learner.
    /// @param place   Where what it learns is written.
    public synchronized void put(final Learner learner, final Place place) {
        places.put(learner, place);
    }
}
