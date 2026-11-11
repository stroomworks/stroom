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

package stroom.docstore.api;

import stroom.docref.DocRef;

/// One stored dependency edge, as recorded: no permission check has been applied to it.
///
/// @param from         The source, with its live name. A source that is not a document, e.g. a processor
///                     filter, has its own type and uuid.
/// @param to           The target, with its live name if it exists, otherwise the name it was recorded
///                     with.
/// @param ambiguous    True if the source names the target by a name more than one document has, so the
///                     target is only one of several candidates.
/// @param targetExists True if the target is a document that exists (not deleted).
public record DependencyEdge(DocRef from, DocRef to, boolean ambiguous, boolean targetExists) {

}
