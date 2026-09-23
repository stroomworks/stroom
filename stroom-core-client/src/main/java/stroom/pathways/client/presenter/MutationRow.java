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

package stroom.pathways.client.presenter;

import stroom.pathways.shared.otel.trace.NanoTime;
import stroom.pathways.shared.pathway.PathwayMutation;
import stroom.util.shared.Expander;
import stroom.util.shared.TreeRow;

import java.util.Objects;

/**
 * One line of the mutation list: either a trace, or one change that trace made.
 *
 * <p>A single trace can teach a model fifty things at once, all stamped with the same time, which
 * buries everything around it. So the list holds a row per trace that opens to show what it changed.
 *
 * <p>This is a view of the history rather than part of it — {@link PathwayMutation} is shared with the
 * server and says nothing about being drawn.
 */
class MutationRow implements TreeRow {

    private final PathwayMutation mutation;
    private final String traceId;
    private final NanoTime time;
    private final long sequence;
    private final Expander expander;

    private MutationRow(final PathwayMutation mutation,
                        final String traceId,
                        final NanoTime time,
                        final long sequence,
                        final Expander expander) {
        this.mutation = mutation;
        this.traceId = traceId;
        this.time = time;
        this.sequence = sequence;
        this.expander = expander;
    }

    /**
     * @param sequence the last change the trace made, so selecting the trace shows the model as the
     *                 trace left it rather than as it stood partway through.
     */
    static MutationRow trace(final String traceId,
                             final NanoTime time,
                             final long sequence,
                             final boolean expanded) {
        return new MutationRow(null, traceId, time, sequence, new Expander(0, expanded, false));
    }

    static MutationRow change(final PathwayMutation mutation) {
        return new MutationRow(mutation, mutation.getTraceId(), mutation.getTime(),
                mutation.getSequence(), new Expander(1, false, true));
    }

    /**
     * The change this row shows, or null where the row is the trace that made them.
     */
    PathwayMutation getMutation() {
        return mutation;
    }

    boolean isTrace() {
        return mutation == null;
    }

    String getTraceId() {
        return traceId;
    }

    NanoTime getTime() {
        return time;
    }

    /**
     * The point in the history this row winds the model back to.
     */
    long getSequence() {
        return sequence;
    }

    @Override
    public Expander getExpander() {
        return expander;
    }

    // Rows are rebuilt every time the list is drawn, so what marks two of them as the same row is what
    // they stand for, not the object. Without this a trace would close again as soon as it was opened.
    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final MutationRow row = (MutationRow) o;
        return sequence == row.sequence && isTrace() == row.isTrace();
    }

    @Override
    public int hashCode() {
        return Objects.hash(sequence, isTrace());
    }
}
