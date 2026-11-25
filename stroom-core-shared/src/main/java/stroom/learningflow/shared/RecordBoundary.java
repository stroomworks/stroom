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

package stroom.learningflow.shared;

import stroom.util.shared.SerialisationTestConstructor;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.Objects;
import java.util.OptionalInt;

/// What one record is in a stream that arrives as, or is parsed into, markup (A31, A35).
///
/// Three shapes:
///
/// - **An element**, by local name: every `<record>` is one record. The usual XML case.
/// - **The children of an element**, whatever they are called: every child of `<batch>` is one record.
///   For a feed that interleaves several record types as siblings under a container — common in audit
///   exports, SIEM exports and event logs — where no single name describes a record and the old
///   vocabulary could say nothing at all (`docs/learning-flow-mixed-record-elements.md`).
/// - **A JSON array**, by key, or [#ROOT] where every top-level value is one record.
///
/// A binding carries the boundary its variant was learned with, so that the stage counts the stream's
/// records and judges yield by it rather than by the root's children; raw text has none, since the
/// parser's configuration cuts it.
@JsonPropertyOrder({"element", "children", "array", "depth"})
@JsonInclude(Include.NON_NULL)
public final class RecordBoundary {

    /// The JSON boundary where every top-level value is one record, as JSON lines are.
    public static final String ROOT = "root";

    /// The element whose **children** are each one record, [#ROOT] for the document's own root; null
    /// where the boundary is not of that shape.
    @JsonProperty
    private final String children;
    @JsonProperty
    private final String element;
    @JsonProperty
    private final String array;
    @JsonProperty
    private final Integer depth;

    @JsonCreator
    public RecordBoundary(@JsonProperty("element") final String element,
                          @JsonProperty("children") final String children,
                          @JsonProperty("array") final String array,
                          @JsonProperty("depth") final Integer depth) {
        final int named = (element == null
                ? 0
                : 1) + (children == null
                ? 0
                : 1) + (array == null
                ? 0
                : 1);
        if (named != 1) {
            throw new IllegalArgumentException("A record boundary is an element, the children of an "
                                               + "element, or an array: one of them");
        }
        this.element = element;
        this.children = children;
        this.array = array;
        this.depth = depth;
    }

    /// A boundary that is only enough to be one: what a serialisation test builds, since the real
    /// constructor refuses the empty one.
    @SerialisationTestConstructor
    private RecordBoundary() {
        this(null, null, ROOT, null);
    }

    /// The boundary of input that is already XML: the element that is one record.
    public static RecordBoundary ofElement(final String element) {
        return new RecordBoundary(element, null, null, null);
    }

    /// The boundary of XML whose records are mixed: every child element of the named one is a record,
    /// whatever it is called.
    ///
    /// @param parent The local name of the element whose children are records, or [#ROOT] for the
    ///               document's own root.
    /// @return The boundary.
    public static RecordBoundary ofChildrenOf(final String parent) {
        return new RecordBoundary(null, parent, null, null);
    }

    /// The boundary of JSON: the key of the array whose items are records, or [#ROOT].
    public static RecordBoundary ofArray(final String array) {
        return new RecordBoundary(null, null, array, null);
    }

    /// The same boundary, knowing how deep in the document one record sits.
    public RecordBoundary atDepth(final Integer depth) {
        return new RecordBoundary(element, children, array, depth);
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

    /// The local name of the element whose children are each one record, [#ROOT] for the document's own
    /// root, or null where the boundary is not of that shape.
    public String getChildren() {
        return children;
    }

    /// Whether the boundary is the children of an element rather than an element or an array.
    @JsonIgnore
    public boolean isChildren() {
        return children != null;
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
        // The children of an element are not the same records as the element itself, so the two must not
        // compare equal however they are named
        return Objects.equals(element, that.element)
               && Objects.equals(children, that.children)
               && Objects.equals(array, that.array);
    }

    @Override
    public int hashCode() {
        return Objects.hash(element, children, array);
    }

    @Override
    public String toString() {
        if (element != null) {
            return "element " + element;
        }
        if (children != null) {
            return "children of " + children;
        }
        return "array " + array;
    }
}
