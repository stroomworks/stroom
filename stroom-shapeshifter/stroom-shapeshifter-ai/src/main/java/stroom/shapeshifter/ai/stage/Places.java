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

import stroom.util.shared.DocPath;

/// Where what a learner learns goes, and what it is called (design 06 A113). The Shapeshifter AI element
/// has a folder and a name property for it, which a Pipeline Factory sets from its patterns; unset, what
/// the learner learns goes beside its pipeline under the pipeline's name with ` learned` after it (A133).
/// Read from the pipeline rather than handed over by the element, because the worker that carries a
/// session on runs no pipeline.
public interface Places {

    /// Where the learner's fragment and its configuration documents go.
    ///
    /// @param learner The learner.
    /// @return Its place.
    Place of(Learner learner);


    // --------------------------------------------------------------------------------


    /// A folder and a name. A second fragment in the folder takes the first free number after the name
    /// (A132), which is the content creator's to find.
    ///
    /// @param folder The folder, from the root of the explorer.
    /// @param name   The name the fragment is given, and its configuration documents are prefixed with.
    record Place(DocPath folder, String name) {

    }
}
