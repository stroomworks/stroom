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

package stroom.dependency.client.component;

import com.google.gwt.storage.client.Storage;
import com.google.inject.Inject;

/// Remembers which [StructureGraphView] the user last chose, in the browser's local storage, so every
/// Structure view opens on it, in this tab and later ones. Where the browser keeps no local storage
/// (e.g. it is turned off), nothing is remembered and each view opens on [StructureGraphView#DEFAULT].
public class StructureGraphViewStore {

    private static final String KEY = "stroom.structureGraph.view";

    /// Made by GIN, or by a story that needs to forget the choice.
    @Inject
    public StructureGraphViewStore() {
        // Nothing to set up: the browser's storage is looked up on each use
    }

    /// @return The view the user last chose, or [StructureGraphView#DEFAULT] if they haven't chosen
    /// one or it can't be read.
    public StructureGraphView get() {
        final Storage storage = storage();
        return StructureGraphView.fromName(storage == null
                ? null
                : storage.getItem(KEY));
    }

    /// Remembers the view the user has chosen.
    ///
    /// @param view The view.
    public void set(final StructureGraphView view) {
        final Storage storage = storage();
        if (storage != null && view != null) {
            try {
                storage.setItem(KEY, view.name());
            } catch (final RuntimeException e) {
                // The storage is full or refused the write: the choice lasts only as long as the view
            }
        }
    }

    /// Forgets the view the user chose, so the next opens on [StructureGraphView#DEFAULT].
    public void clear() {
        final Storage storage = storage();
        if (storage != null) {
            storage.removeItem(KEY);
        }
    }

    private static Storage storage() {
        try {
            return Storage.getLocalStorageIfSupported();
        } catch (final RuntimeException e) {
            // A browser that blocks storage can throw on access, rather than report it unsupported
            return null;
        }
    }
}
