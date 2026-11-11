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

/// How sure the server is that an edge in a dependency graph exists.
public enum EdgeCertainty {

    /// Read from a document's content, e.g. an `xsl:import` in an XSLT.
    STATIC,

    /// Read from configuration, e.g. a processor filter linking a feed to a pipeline.
    CONFIGURED,

    /// Worked out indirectly, so it may be wrong.
    INFERRED
}
