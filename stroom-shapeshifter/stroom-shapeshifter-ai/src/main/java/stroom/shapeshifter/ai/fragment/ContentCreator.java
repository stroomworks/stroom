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

package stroom.shapeshifter.ai.fragment;

import stroom.docref.DocRef;
import stroom.util.shared.DocPath;

import java.util.Set;

/**
 * Where learned content goes. A document created straight through its store has no explorer node and
 * is invisible in the tree; in a node the explorer creates it, in the folder its learner's place names
 * (design 06 A113). Tests create through the stores and ignore the folder.
 */
public interface ContentCreator {

    /**
     * @param folder The folder to create in, created itself if need be.
     * @param type   The document type, e.g. {@code Pipeline}.
     * @param name   The document name.
     * @return The new, empty document.
     */
    DocRef create(DocPath folder, String type, String name);

    /// Make a folder, or find it where it is.
    ///
    /// @param folder The folder.
    void ensure(DocPath folder);

    /// What is already in a folder, of any type: what a new name must not be.
    ///
    /// @param folder The folder.
    /// @return The names of what it holds; none where it does not exist yet.
    Set<String> names(DocPath folder);

    /// A name nothing in the folder has (A132): the name itself where it is free, and otherwise the first
    /// free number after it — `Door access`, then `Door access 2`, `Door access 3`.
    ///
    /// @param folder The folder.
    /// @param name   The name wanted.
    /// @return The name to create under.
    default String unused(final DocPath folder, final String name) {
        final Set<String> taken = names(folder);
        if (!taken.contains(name)) {
            return name;
        }
        int number = 2;
        while (taken.contains(name + " " + number)) {
            number++;
        }
        return name + " " + number;
    }
}
