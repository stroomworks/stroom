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

package stroom.shapeshifter.shared;

import stroom.util.shared.SerialisationTestConstructor;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.Objects;
import java.util.OptionalInt;

/// What one record is in a stream that arrives as, or is parsed into, markup (A31, A35): the local name of
/// the element that is one record where the input is XML, or the key of the array whose items are records
/// where it is JSON — [#ROOT] where every top-level value is one. A binding carries the boundary its variant
/// was learned with, so that the stage counts the stream's records and judges yield by it rather than by
/// the root's children; raw text has none, since the parser's configuration cuts it.
@JsonPropertyOrder({"element", "array", "depth"})
@JsonInclude(Include.NON_NULL)
public final class RecordBoundary {

    /// The JSON boundary where every top-level value is one record, as JSON lines are.
    public static final String ROOT = "root";

    @JsonProperty
    private final String element;
    @JsonProperty
    private final String array;
    @JsonProperty
    private final Integer depth;

    @JsonCreator
    public RecordBoundary(@JsonProperty("element") final String element,
                          @JsonProperty("array") final String array,
                          @JsonProperty("depth") final Integer depth) {
        if ((element == null) == (array == null)) {
            throw new IllegalArgumentException("A record boundary is an element or an array, one of them");
        }
        this.element = element;
        this.array = array;
        this.depth = depth;
    }

    /// A boundary that is only enough to be one: what a serialisation test builds, since the real
    /// constructor refuses the empty one.
    @SerialisationTestConstructor
    private RecordBoundary() {
        this(null, ROOT, null);
    }

    /// The boundary of input that is already XML: the element that is one record.
    public static RecordBoundary ofElement(final String element) {
        return new RecordBoundary(element, null, null);
    }

    /// The boundary of JSON: the key of the array whose items are records, or [#ROOT].
    public static RecordBoundary ofArray(final String array) {
        return new RecordBoundary(null, array, null);
    }

    /// The same boundary, knowing how deep in the document one record sits.
    public RecordBoundary atDepth(final Integer depth) {
        return new RecordBoundary(element, array, depth);
    }

    /// How deep in the document the transform receives one record sits: what the fragment's `SplitFilter`
    /// is set to split at, so that the stylesheet sees one record and memory is bounded by the record and
    /// not by the stream (§12 item 25). One for the children of a root; three for the items of an array
    /// under a key, since the JSON parser wraps its output in a records root. It is read from the document
    /// the split was settled against and not assumed, which is why it is kept rather than derived.
    ///
    /// @return Empty for a binding learned before the depth was recorded. A guess would be worse than
    /// nothing: the usual depth of one splits a JSON document at its single top-level map and a nested
    /// XML document at its one wrapper, which is one document for the whole stream and bounds nothing.
    /// A binding with no depth is written without a filter, exactly as it was before item 25, until it is
    /// learned again.
    public OptionalInt splitDepth() {
        return depth == null
                ? OptionalInt.empty()
                : OptionalInt.of(depth);
    }

    /// The depth as it is stored, which is null for a binding learned before it was recorded.
    public Integer getDepth() {
        return depth;
    }

    /// The local name of the element that is one record, or null where the boundary is an array.
    public String getElement() {
        return element;
    }

    /// The key of the array whose items are records, [#ROOT], or null where the boundary is an element.
    public String getArray() {
        return array;
    }

    /// Whether the boundary is a JSON array — [#getArray()] is set — rather than an element.
    @JsonIgnore
    public boolean isArray() {
        return array != null;
    }

    /// A boundary is a name: two that name the same records are the same boundary, whether or not
    /// either has been told how deep they sit, since the depth follows from the name and the document.
    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final RecordBoundary that = (RecordBoundary) o;
        return Objects.equals(element, that.element) && Objects.equals(array, that.array);
    }

    @Override
    public int hashCode() {
        return Objects.hash(element, array);
    }

    @Override
    public String toString() {
        return element != null
                ? "element " + element
                : "array " + array;
    }
}
