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

package stroom.learningflow.learning;

import stroom.learningflow.shared.RecordBoundary;

/// Where one record ends and the next begins in a stream, as settled by the split step of a learning flow.
///
/// For raw text it is the Data Splitter configuration that cuts the text into records. For input that is
/// already XML it is the local name of the element that is one record, or the element whose children are
/// each one record. For JSON it is the key of the array whose items are records, or [#ROOT] where each
/// top-level value is one. Exactly one of these four is set.
///
/// @param configuration The Data Splitter configuration that cuts the records, or null.
/// @param element       The element that is one record, or null.
/// @param children      The element whose children are each one record, [#ROOT] for the document's own
///                      root, or null. This is for a feed that mixes several record types as siblings
///                      under one container, where no single element name describes a record.
/// @param array         The key of the array whose items are records, or [#ROOT], or null.
/// @param depth         The element depth at which one record sits in the document the transform is
///                      given, where it is known. The fragment's `SplitFilter` splits at this depth. Null
///                      for raw text, whose parser cuts the records itself.
public record Boundary(String configuration, String element, String children, String array, Integer depth) {

    /// The JSON boundary where every top-level value is one record, as JSON lines are.
    public static final String ROOT = "root";

    public Boundary {
        final int set = (configuration == null ? 0 : 1) + (element == null ? 0 : 1)
                        + (children == null ? 0 : 1) + (array == null ? 0 : 1);
        if (set != 1) {
            throw new IllegalArgumentException("A boundary is a configuration, an element, the children of "
                                               + "an element, or an array: one of them");
        }
    }

    /// The boundary of raw text: a configuration that cuts it.
    public static Boundary ofConfiguration(final String configuration) {
        return new Boundary(configuration, null, null, null, null);
    }

    /// The boundary of input that is already XML: the element that is one record.
    public static Boundary ofElement(final String element) {
        return new Boundary(null, element, null, null, null);
    }

    /// The boundary of input that is already XML, with how deep one record sits.
    public static Boundary ofElement(final String element, final Integer depth) {
        return new Boundary(null, element, null, null, depth);
    }

    /// The boundary of XML whose records are mixed: every child element of the named one is a record.
    ///
    /// @param parent The container's local name, or [#ROOT] for the document's own root.
    /// @param depth  How deep one record sits.
    /// @return The boundary.
    public static Boundary ofChildrenOf(final String parent, final Integer depth) {
        return new Boundary(null, null, parent, null, depth);
    }

    /// The boundary of JSON: the key of the array whose items are records, or [#ROOT].
    public static Boundary ofArray(final String array) {
        return new Boundary(null, null, null, array, null);
    }

    /// The boundary of JSON, with how deep one record sits.
    public static Boundary ofArray(final String array, final Integer depth) {
        return new Boundary(null, null, null, array, depth);
    }

    /// Returns the boundary in the form a binding stores it: the element, the container whose children are
    /// records, or the array, with the depth.
    ///
    /// @return The shared boundary, or null for raw text, whose parser configuration cuts the records and
    /// needs nothing stored beside it.
    public RecordBoundary shared() {
        if (element != null) {
            return RecordBoundary.ofElement(element).atDepth(depth);
        }
        if (children != null) {
            return RecordBoundary.ofChildrenOf(children).atDepth(depth);
        }
        return array != null
                ? RecordBoundary.ofArray(array).atDepth(depth)
                : null;
    }
}
