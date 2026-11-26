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

/// What one record is in a stream that arrives as, or is parsed into, XML or JSON. The split step settles it.
///
/// There are three shapes:
///
/// - **An element**, by local name: every `<record>` is one record. This is the usual XML case.
/// - **The children of an element**, whatever they are called: every child of `<batch>` is one record.
///   This is for a feed that mixes several record types as siblings under a container (common in audit
///   exports, SIEM exports and event logs), where no single element name describes a record.
/// - **A JSON array**, by key, or [#ROOT] where every top-level value is one record.
///
/// A binding carries the boundary it was learned with, so that the Smart Pipeline element counts the
/// stream's records and judges yield by it rather than by the root's children. Raw text has no boundary,
/// since the parser's configuration cuts it into records.
@JsonPropertyOrder({"element", "children", "array", "depth"})
@JsonInclude(Include.NON_NULL)
public final class RecordBoundary {

    /// The JSON boundary where every top-level value is one record, as JSON lines are.
    public static final String ROOT = "root";

    /// The element whose **children** are each one record, or [#ROOT] for the document's own root; null
    /// where the boundary is of another shape.
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

    // The minimal valid boundary, for serialisation tests: the real constructor refuses an empty one.
    @SerialisationTestConstructor
    private RecordBoundary() {
        this(null, null, ROOT, null);
    }

    /// @param element The local name of the element that is one record.
    /// @return The boundary of XML input whose records are each that element.
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

    /// @param array The key of the array whose items are records, or [#ROOT].
    /// @return The boundary of JSON input whose records are that array's items.
    public static RecordBoundary ofArray(final String array) {
        return new RecordBoundary(null, null, array, null);
    }

    /// @param depth How deep in the document one record sits.
    /// @return A copy of this boundary recording that depth.
    public RecordBoundary atDepth(final Integer depth) {
        return new RecordBoundary(element, children, array, depth);
    }

    /// How deep in the document the transform receives one record sits. The fragment's `SplitFilter` is set
    /// to split at this depth, so that the stylesheet sees one record at a time and memory use is bounded by
    /// the record rather than by the stream. It is one for the children of a root, and three for the items of
    /// an array under a key, since the JSON parser wraps its output in a records root. It is measured from
    /// the document the split was settled against, not assumed, which is why it is stored rather than derived.
    ///
    /// @return The depth, or empty for a binding learned by an earlier version that did not record it. No
    /// depth is guessed for such a binding, because the usual depth of one would split a JSON document at its
    /// single top-level map, or a nested XML document at its one wrapper, giving one document for the whole
    /// stream. Such a binding's fragment is written without a split filter until it is learned again.
    public OptionalInt splitDepth() {
        return depth == null
                ? OptionalInt.empty()
                : OptionalInt.of(depth);
    }

    /// @return The depth as it is stored, which is null for a binding learned before depths were recorded.
    public Integer getDepth() {
        return depth;
    }

    /// @return The local name of the element that is one record, or null where the boundary is of another
    /// shape.
    public String getElement() {
        return element;
    }

    /// @return The key of the array whose items are records, or [#ROOT]; null where the boundary is of another
    /// shape.
    public String getArray() {
        return array;
    }

    /// @return The local name of the element whose children are each one record, or [#ROOT] for the
    /// document's own root; null where the boundary is of another shape.
    public String getChildren() {
        return children;
    }

    /// @return Whether the boundary is the children of an element rather than an element or an array.
    @JsonIgnore
    public boolean isChildren() {
        return children != null;
    }

    /// @return Whether the boundary is a JSON array (so [#getArray()] is set).
    @JsonIgnore
    public boolean isArray() {
        return array != null;
    }

    // Two boundaries that name the same records are equal whether or not either records its depth, since
    // the depth follows from the name and the document.
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
