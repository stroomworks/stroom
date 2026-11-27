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

package stroom.learningflow.stage;

import stroom.learningflow.shared.Binding;

import java.util.List;
import java.util.Optional;

/// Storage for learners' bindings: the binding a learner is served by, and any draft awaiting review to
/// take its place.
///
/// Bindings are stored as rows rather than on the Learning Flow document, because the document holds only
/// what a person authors, and so that two nodes promoting for two learners write two separate rows rather
/// than racing to rewrite one document.
///
/// A row is keyed by its learner, and records the document the learner ran under when it was written, so
/// that the Supervisor can show it and decide who may see it. The serving path reads through a cache
/// (`CachedLearnerBindings`); the implementation behind the cache does the writes and loads the cache.
public interface LearnerBindings {

    /// Gets the learner's bindings, oldest first: the one that serves it, and any draft.
    ///
    /// @param learner The learner to look up.
    /// @return Its bindings, empty if it has none.
    List<Binding> forLearner(Learner learner);

    /// Gets the binding with this UUID, together with the learner it belongs to.
    ///
    /// @param bindingUuid The binding's UUID, which is unique across learners.
    /// @return The binding and its owner, if there is one.
    Optional<Held> byUuid(String bindingUuid);

    /// Adds a binding to the learner's bindings, giving it a UUID if it has none.
    ///
    /// @param learner The learner the binding is for.
    /// @param docUuid The UUID of the Learning Flow document the learner runs under.
    /// @param binding The binding to add.
    /// @return The binding as stored, with its UUID.
    Binding append(Learner learner, String docUuid, Binding binding);

    /// Replaces the binding with the same UUID. If the learner has no such binding it is added, so that a
    /// promotion is not lost because its row was pruned in the meantime.
    ///
    /// **The write is checked against the version the binding was read at**, where it has one. Locking the
    /// row is not enough on its own, because every field is written back: a node promoting a binding read
    /// from its cache would otherwise revert a change another node had made in between to the binding's
    /// pipeline and snapshot. A binding that was built rather than read carries no version and is written
    /// without the check.
    ///
    /// @param learner The learner the binding is for.
    /// @param docUuid The UUID of the Learning Flow document the learner runs under.
    /// @param binding The binding to write.
    /// @return Whether it was written. False means the row has changed since the binding was read, so the
    ///         caller's copy is stale and must not be forced over the more recent one.
    boolean replace(Learner learner, String docUuid, Binding binding);

    /// Removes a binding, e.g. when a draft is rejected or a binding is retracted.
    ///
    /// @param learner     The learner whose binding it is.
    /// @param bindingUuid The binding's UUID.
    void remove(Learner learner, String bindingUuid);


    // --------------------------------------------------------------------------------


    /// A binding together with the learner it belongs to.
    ///
    /// @param learner The learner whose binding it is.
    /// @param docUuid The UUID of the document the learner ran under when the binding was written.
    /// @param binding The binding.
    record Held(Learner learner, String docUuid, Binding binding) {

    }
}
