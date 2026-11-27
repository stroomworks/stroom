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

package stroom.learningflow.stage;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/// Storage for immutable snapshots of a fragment's configuration, one per promotion, each keyed by a version
/// id. A binding points at its snapshot, a deferral row and an output record the snapshot in force, and an
/// as-processed reprocess runs the recorded snapshot, so that a stream can be reprocessed exactly as it was
/// first processed.
///
/// A snapshot is text: the fragment's structure, each element's code, and the versions of the documents it
/// was taken from, which is how a hand edit to the fragment is noticed. A snapshot lasts as long as its
/// binding. Tests keep snapshots in memory; a node uses a table.
public interface Snapshots {

    /// Stores a snapshot.
    ///
    /// @param snapshot The snapshot, with the id it is to be known by.
    void keep(Snapshot snapshot);

    /// Gets one snapshot by its id.
    ///
    /// @param snapshotId The snapshot's id.
    /// @return The snapshot, if it still exists.
    Optional<Snapshot> byId(String snapshotId);

    /// Gets a binding's snapshots, oldest first.
    ///
    /// @param bindingUuid The binding's UUID.
    /// @return Its snapshots.
    List<Snapshot> forBinding(String bindingUuid);

    /// Deletes the snapshots of a binding that has been removed.
    ///
    /// @param bindingUuid The binding's UUID.
    void discard(String bindingUuid);

    /// Moves a draft's snapshots to the serving binding when the draft is approved, since approval rebinds
    /// the serving binding to the draft's fragment.
    ///
    /// @param fromBindingUuid The UUID of the approved draft.
    /// @param toBindingUuid   The UUID of the serving binding it replaces.
    void move(String fromBindingUuid, String toBindingUuid);

    // --------------------------------------------------------------------------------

    /// One snapshot of a fragment's configuration.
    ///
    /// @param id           Its id: the version a binding points at and rows are stamped with.
    /// @param docUuid      The UUID of the document the learner ran under.
    /// @param bindingUuid  The UUID of the binding it belongs to.
    /// @param fragmentUuid The UUID of the fragment it was taken from.
    /// @param structure    The fragment's pipeline data, as JSON.
    /// @param code         A map of element id to the code (configuration text) that element runs with.
    /// @param versions     A map of document UUID to the version of that document the snapshot was taken from.
    /// @param createTimeMs When it was taken.
    record Snapshot(String id,
                    String docUuid,
                    String bindingUuid,
                    String fragmentUuid,
                    String structure,
                    Map<String, String> code,
                    Map<String, String> versions,
                    long createTimeMs) {

        public Snapshot {
            code = Map.copyOf(code);
            versions = Map.copyOf(versions);
        }
    }
}
