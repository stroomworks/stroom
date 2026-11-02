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

package stroom.shapeshifter.ai.stage;

import stroom.shapeshifter.shared.Binding;

import java.util.List;
import java.util.Optional;

/// The bindings of A41 and A124, as rows: the fragment a learner is served by, and any draft awaiting review
/// to take its place. Rows rather than a field on the document, so that two nodes promoting for two
/// learners write two rows rather than racing to rewrite one document.
///
/// A row is the learner's (design 06 phase J slice 2b), and records the document the learner ran under
/// when it was written, for the Supervisor to show and to decide who may see it. The serving path reads
/// through a cache; these are the writes and the cache's source (design 01 §11.4).
public interface LearnerBindings {

    /// The learner's bindings, oldest first: the one that serves it, and any draft (A124).
    ///
    /// @param learner The learner.
    /// @return Its bindings.
    List<Binding> forLearner(Learner learner);

    /// The binding with this uuid, with whose it is.
    ///
    /// @param bindingUuid The binding's uuid, which is unique across learners.
    /// @return The row, if there is one.
    Optional<Held> byUuid(String bindingUuid);

    /// Add a binding to the learner's, giving it a uuid where it has none.
    ///
    /// @param learner The learner.
    /// @param docUuid The document the learner runs under.
    /// @param binding The binding.
    /// @return The binding as stored, with its uuid.
    Binding append(Learner learner, String docUuid, Binding binding);

    /// Replace the binding of the same uuid; one the learner does not have is added, since a promotion
    /// must not be lost because its row was pruned.
    ///
    /// @param learner The learner.
    /// @param docUuid The document the learner runs under.
    /// @param binding The binding.
    void replace(Learner learner, String docUuid, Binding binding);

    /// Remove a binding: a draft rejected, a binding retracted.
    ///
    /// @param learner     The learner whose binding it is.
    /// @param bindingUuid The binding's uuid.
    void remove(Learner learner, String bindingUuid);


    // --------------------------------------------------------------------------------


    /// A binding and whose it is.
    ///
    /// @param learner The learner whose binding it is.
    /// @param docUuid The document the learner ran under when it was written.
    /// @param binding The binding.
    record Held(Learner learner, String docUuid, Binding binding) {

    }
}
