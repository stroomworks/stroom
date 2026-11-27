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

import stroom.util.shared.DocPath;

/// Decides where the content a learner learns is written in the explorer, and what it is called. The Smart
/// Pipeline element has folder and name properties for this, which a person may set. If they are not set,
/// the content goes beside the learner's pipeline, named after the pipeline with ` learned` appended.
///
/// The properties are read from the pipeline rather than passed in by the element, because the deferred
/// worker that resumes a session does not run the pipeline.
public interface Places {

    /// Gets where the learner's fragment and its configuration documents are written.
    ///
    /// @param learner The learner.
    /// @return The folder and name to use.
    Place of(Learner learner);


    // --------------------------------------------------------------------------------


    /// A folder and a name. If a fragment of that name already exists in the folder, the content creator
    /// adds the first free number after the name.
    ///
    /// @param folder The folder, as a path from the root of the explorer.
    /// @param name   The name given to the fragment, which its configuration documents are also prefixed
    ///               with.
    record Place(DocPath folder, String name) {

    }
}
