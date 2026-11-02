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

package stroom.shapeshifter.ai.stage;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/// The snapshots of a fragment's configuration (A61, A70): one per promotion, keyed by a version id —
/// what a binding points at, what a deferral row is stamped with, what an output records, and what an
/// as-processed reprocess runs. A snapshot is text: the fragment's structure and each element's code, and
/// the versions of the documents it was taken from, by which a hand edit is noticed (A72). It lives as
/// long as its binding (A71). In-memory in scenarios; a table in a node.
public interface Snapshots {

    /// Keep a snapshot.
    ///
    /// @param snapshot The snapshot, with the id it is to be known by.
    void keep(Snapshot snapshot);

    /// One snapshot by its id.
    ///
    /// @param snapshotId The id.
    /// @return The snapshot, where it still exists.
    Optional<Snapshot> byId(String snapshotId);

    /// A binding's snapshots, oldest first.
    ///
    /// @param bindingUuid The binding.
    /// @return Its snapshots.
    List<Snapshot> forBinding(String bindingUuid);

    /// A binding has gone, and its snapshots with it (A71).
    ///
    /// @param bindingUuid The binding.
    void discard(String bindingUuid);

    /// Approve rebinds the incumbent to a draft's fragment, and the draft's snapshots become the
    /// incumbent's.
    ///
    /// @param fromBindingUuid The draft.
    /// @param toBindingUuid   The binding it replaced.
    void move(String fromBindingUuid, String toBindingUuid);

    // --------------------------------------------------------------------------------

    /// One snapshot.
    ///
    /// @param id           Its id: the version a binding points at and a row is stamped with.
    /// @param docUuid      The document.
    /// @param bindingUuid  The binding.
    /// @param fragmentUuid The fragment it was taken from.
    /// @param structure    The fragment's pipeline data, as JSON.
    /// @param code         Element id to the code that element runs with.
    /// @param versions     Document uuid to the version of it this was taken from.
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
