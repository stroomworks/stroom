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

import stroom.shapeshifter.ai.stage.Snapshots;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/// The snapshots as a list: what scenarios inspect. Every method is synchronised.
public final class InMemorySnapshots implements Snapshots {

    private final List<Snapshot> snapshots = new ArrayList<>();

    @Override
    public synchronized void keep(final Snapshot snapshot) {
        snapshots.add(snapshot);
    }

    @Override
    public synchronized Optional<Snapshot> byId(final String snapshotId) {
        return snapshots.stream().filter(snapshot -> snapshot.id().equals(snapshotId)).findFirst();
    }

    @Override
    public synchronized List<Snapshot> forBinding(final String bindingUuid) {
        return snapshots.stream().filter(snapshot -> snapshot.bindingUuid().equals(bindingUuid)).toList();
    }

    @Override
    public synchronized void discard(final String bindingUuid) {
        snapshots.removeIf(snapshot -> snapshot.bindingUuid().equals(bindingUuid));
    }

    @Override
    public synchronized void move(final String fromBindingUuid, final String toBindingUuid) {
        snapshots.replaceAll(snapshot -> snapshot.bindingUuid().equals(fromBindingUuid)
                ? new Snapshot(snapshot.id(), snapshot.docUuid(), toBindingUuid, snapshot.fragmentUuid(),
                        snapshot.structure(), snapshot.code(), snapshot.versions(), snapshot.createTimeMs())
                : snapshot);
    }
}
