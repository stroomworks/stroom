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

package stroom.learningflow.fragment;

import stroom.docref.DocRef;
import stroom.util.shared.DocPath;

import java.util.Set;

/// Creates and deletes the documents that learning writes (fragments, XSLTs, text converters). A document
/// created directly through its store has no explorer node and is invisible in the tree, so on a node
/// [ExplorerContentCreator] creates it through the explorer, in the folder set on the learner's Smart
/// Pipeline element. Tests create documents through the stores and ignore the folder.
public interface ContentCreator {

    /// Creates a new, empty document.
    ///
    /// @param folder The folder to create in, which is itself created if need be.
    /// @param type   The document type, e.g. `Pipeline`.
    /// @param name   The document name.
    /// @return The new, empty document.
    DocRef create(DocPath folder, String type, String name);

    /// Deletes a learned document along with its explorer node. This is used to remove a draft fragment's
    /// documents once the draft has been copied into the fragment that serves the learner. Deleting
    /// through the store alone would leave the explorer node behind.
    ///
    /// @param docRef The document to delete.
    void delete(DocRef docRef);

    /// Creates a folder if it does not already exist.
    ///
    /// @param folder The folder.
    void ensure(DocPath folder);

    /// Lists the names of everything in a folder, of any type, so that a new document can be given a name
    /// that is not already taken.
    ///
    /// @param folder The folder.
    /// @return The names of what it holds; none where it does not exist yet.
    Set<String> names(DocPath folder);

    /// Finds a name that nothing in the folder already has. This is the name itself where it is free, and
    /// otherwise the name followed by the first free number, e.g. `Door access`, then `Door access 2`,
    /// `Door access 3`.
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
