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

package stroom.dependency.shared;

/// How one node in a dependency graph relates to another.
///
/// `doc_dependency` records only that one document points at another, so the server derives the kind
/// from the pair of document types and, for pipelines, from the pipeline itself.
public enum EdgeKind {

    /// Data moves from one node to the next, e.g. a feed is processed by a pipeline.
    FLOW,

    /// A node reads another at query time, e.g. a dashboard querying an index. Drawn in the direction the
    /// data moves, which is the reverse of the direction it is configured in.
    QUERY,

    /// A node uses another to do its work, e.g. a pipeline using an XSLT.
    REFERENCE,

    /// A pipeline inherits from a parent pipeline.
    INHERITANCE,

    /// A node calls something outside Stroom over HTTP.
    HTTP,

    /// A node reads another by name at runtime, e.g. an XSLT reading a dictionary.
    LOOKUP
}
