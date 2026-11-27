/*
 * Copyright 2016-2026 Crown Copyright
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

package stroom.learningflow.state;

import stroom.learningflow.stage.Outputs;
import stroom.learningflow.stage.Produced;
import stroom.learningflow.stage.Replayable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/// An in-memory [Outputs], recording which binding produced each output as a list, for scenarios and for a
/// node run without the database module. It is local to one node and lost on restart. Every method is
/// synchronised, because one node's tasks share it.
public final class InMemoryOutputs implements Outputs {

    private final List<Emitted> emitted = new ArrayList<>();

    @Override
    public synchronized void emitted(final long inputId,
                                     final String pipeline,
                                     final Produced produced) {
        // One row per input, binding and pipeline, as in the table: a stream processed twice by one
        // pipeline under one binding is one row to replay, and two pipelines make two outputs.
        emitted.removeIf(output -> output.inputId() == inputId
                                   && output.produced().bindingUuid().equals(produced.bindingUuid())
                                   && Objects.equals(output.pipeline(), pipeline));
        emitted.add(new Emitted(inputId, pipeline, produced));
    }

    @Override
    public synchronized List<Replayable> boundBy(final String bindingUuid, final String fragmentUuid) {
        return emitted.stream()
                .filter(output -> output.produced().bindingUuid().equals(bindingUuid))
                .filter(output -> fragmentUuid == null
                                  || fragmentUuid.equals(output.produced().fragment().getUuid()))
                .map(output -> new Replayable(output.inputId(), output.pipeline()))
                .toList();
    }

    @Override
    public synchronized Optional<Produced> asProcessed(final String docUuid,
                                                       final long inputId,
                                                       final String pipeline) {
        // The last row recorded for this input on this pipeline under this document. A stream served
        // twice keeps one row per binding, and its current output is the one that ran last.
        return emitted.stream()
                .filter(output -> output.inputId() == inputId)
                // Only this document's rows, since a pipeline may contain two Smart Pipeline elements,
                // each recording the same input.
                .filter(output -> output.produced().docUuid().equals(docUuid))
                // Only this pipeline's rows: a stream another pipeline produced is a different output, and
                // a null pipeline matches only rows with no pipeline, not every pipeline.
                .filter(output -> Objects.equals(output.pipeline(), pipeline))
                .map(Emitted::produced)
                .reduce((first, last) -> last);
    }

    @Override
    public synchronized int prune(final long producedBeforeMs) {
        // Nothing is pruned in memory: the list lasts only as long as the node, which limits its size.
        return 0;
    }

    public synchronized List<Emitted> emitted() {
        return List.copyOf(emitted);
    }

    public record Emitted(long inputId, String pipeline, Produced produced) {

    }
}
