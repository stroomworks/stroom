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

package stroom.pathways.shared.pathway;

import stroom.pathways.shared.otel.trace.NanoTime;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Objects;

/**
 * One change a trace made to a learnt model, kept so the model's growth can be replayed rather than
 * only seen as it ended up.
 *
 * <p>Enough to redo or undo the change on its own: where in the model it happened, what it was before
 * and what it became. There is no separate field for the value's type because a
 * {@link ConstraintValue} already carries it.
 */
@JsonInclude(Include.NON_NULL)
public class PathwayMutation {

    // The names the grid sorts by, shared so the column clicked and the field ordered on cannot drift.
    public static final String FIELD_TIME = "Time";
    public static final String FIELD_TYPE = "Change";
    public static final String FIELD_PATH = "Node";
    public static final String FIELD_CONSTRAINT = "Constraint";
    public static final String FIELD_OLD_VALUE = "Was";
    public static final String FIELD_NEW_VALUE = "Now";
    public static final String FIELD_TRACE_ID = "Trace";
    public static final String FIELD_SPAN_ID = "Span";

    @JsonProperty
    private final NanoTime time;
    @JsonProperty
    private final String traceId;
    @JsonProperty
    private final String spanId;
    /** The node this happened to, named from the root down. Empty for the pathway itself. */
    @JsonProperty
    private final List<String> path;
    /** Which constraint changed, or null where the change was to the node rather than a constraint. */
    @JsonProperty
    private final String constraint;
    @JsonProperty
    private final MutationType type;
    /** What the constraint held before, or null where it held nothing. */
    @JsonProperty
    private final ConstraintValue oldValue;
    /** What it holds now, or null where the change added no value. */
    @JsonProperty
    private final ConstraintValue newValue;

    @JsonCreator
    public PathwayMutation(@JsonProperty("time") final NanoTime time,
                           @JsonProperty("traceId") final String traceId,
                           @JsonProperty("spanId") final String spanId,
                           @JsonProperty("path") final List<String> path,
                           @JsonProperty("constraint") final String constraint,
                           @JsonProperty("type") final MutationType type,
                           @JsonProperty("oldValue") final ConstraintValue oldValue,
                           @JsonProperty("newValue") final ConstraintValue newValue) {
        this.time = time;
        this.traceId = traceId;
        this.spanId = spanId;
        this.path = path;
        this.constraint = constraint;
        this.type = type;
        this.oldValue = oldValue;
        this.newValue = newValue;
    }

    public NanoTime getTime() {
        return time;
    }

    public String getTraceId() {
        return traceId;
    }

    public String getSpanId() {
        return spanId;
    }

    public List<String> getPath() {
        return path;
    }

    public String getConstraint() {
        return constraint;
    }

    public MutationType getType() {
        return type;
    }

    public ConstraintValue getOldValue() {
        return oldValue;
    }

    public ConstraintValue getNewValue() {
        return newValue;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final PathwayMutation that = (PathwayMutation) o;
        return Objects.equals(time, that.time)
               && Objects.equals(traceId, that.traceId)
               && Objects.equals(spanId, that.spanId)
               && Objects.equals(path, that.path)
               && Objects.equals(constraint, that.constraint)
               && type == that.type
               && Objects.equals(oldValue, that.oldValue)
               && Objects.equals(newValue, that.newValue);
    }

    @Override
    public int hashCode() {
        return Objects.hash(time, traceId, spanId, path, constraint, type, oldValue, newValue);
    }

    @Override
    public String toString() {
        return type + " " + path + (constraint == null
                ? ""
                : " " + constraint) + " " + oldValue + " -> " + newValue;
    }
}
