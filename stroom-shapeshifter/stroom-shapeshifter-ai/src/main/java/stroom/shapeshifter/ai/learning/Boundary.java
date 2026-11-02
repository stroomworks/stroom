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

package stroom.shapeshifter.ai.learning;

import stroom.shapeshifter.shared.RecordBoundary;

/// The record boundary a flow settles (A31, A35): for raw text the parser configuration that cuts one
/// record per unit and emits it whole; for input that is already XML the local name of the element that is
/// one record; for JSON the key of the array whose items are records, or [#ROOT] where each top-level value
/// is one. Exactly one of the three is set.
///
/// @param configuration The Data Splitter configuration that cuts the records, or null.
/// @param element       The element that is one record, or null.
/// @param array         The key of the array whose items are records, or [#ROOT], or null.
/// @param depth         How deep in the document the transform receives one record sits, where it is
///                      known: what the fragment's `SplitFilter` is set to split at (§12 item 25). Null
///                      for raw text, whose parser cuts the records itself.
public record Boundary(String configuration, String element, String array, Integer depth) {

    /// The JSON boundary where every top-level value is one record, as JSON lines are.
    public static final String ROOT = "root";

    public Boundary {
        final int set = (configuration == null ? 0 : 1) + (element == null ? 0 : 1) + (array == null ? 0 : 1);
        if (set != 1) {
            throw new IllegalArgumentException("A boundary is a configuration, an element or an array, one of them");
        }
    }

    /// The boundary of raw text: a configuration that cuts it.
    public static Boundary ofConfiguration(final String configuration) {
        return new Boundary(configuration, null, null, null);
    }

    /// The boundary of input that is already XML: the element that is one record.
    public static Boundary ofElement(final String element) {
        return new Boundary(null, element, null, null);
    }

    /// The boundary of input that is already XML, with how deep one record sits.
    public static Boundary ofElement(final String element, final Integer depth) {
        return new Boundary(null, element, null, depth);
    }

    /// The boundary of JSON: the key of the array whose items are records, or [#ROOT].
    public static Boundary ofArray(final String array) {
        return new Boundary(null, null, array, null);
    }

    /// The boundary of JSON, with how deep one record sits.
    public static Boundary ofArray(final String array, final Integer depth) {
        return new Boundary(null, null, array, depth);
    }

    /// The boundary as a binding carries it (A35): the element or the array; null for raw text, whose parser
    /// configuration cuts the records and needs no count beside it.
    public RecordBoundary shared() {
        if (element != null) {
            return RecordBoundary.ofElement(element).atDepth(depth);
        }
        return array != null
                ? RecordBoundary.ofArray(array).atDepth(depth)
                : null;
    }
}
