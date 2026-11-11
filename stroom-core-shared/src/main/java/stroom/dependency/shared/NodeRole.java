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

/// Where a node sits in a dependency graph, which decides which row of its lane the layout puts it in.
public enum NodeRole {

    /// A document that data passes through or ends up in, such as a feed, pipeline, index or dashboard.
    /// Drawn on the flow row.
    FLOW,

    /// A document that something on the flow row uses, such as an XSLT, text converter or schema.
    /// Drawn in the reference row beneath each lane that uses it.
    REFERENCE,

    /// Something outside Stroom, such as an HTTP endpoint.
    EXTERNAL
}
