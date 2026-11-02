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

package stroom.shapeshifter.ai.state;

import stroom.shapeshifter.ai.stage.Outputs;
import stroom.shapeshifter.ai.stage.Produced;
import stroom.shapeshifter.ai.stage.Replayable;
import stroom.util.shared.TextRange;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The bindings of every output as a list: what scenarios run over, and what a node runs over until the
 * A26 module exists — node-local and gone on restart.
 Every method is synchronised: one node's tasks share it.
 */
public final class InMemoryOutputs implements Outputs {

    private final List<Emitted> emitted = new ArrayList<>();

    @Override
    public synchronized void emitted(final long inputId,
                                     final String pipeline,
                                     final Produced produced,
                                     final List<TextRange> spans) {
        // One row per input per binding per pipeline, as the table has it: a stream processed twice by one
        // pipeline under one binding is one thing to replay, and two pipelines are two outputs.
        final List<TextRange> known = spans.isEmpty()
                // A run with no parser to ask knows nothing about where the records began, and knowing
                // nothing must not erase what was known: an as-processed reprocess of a stream keeps the
                // spans the run that produced it recorded.
                ? span(produced.docUuid(), inputId, pipeline)
                : List.copyOf(spans);
        emitted.removeIf(output -> output.inputId() == inputId
                                   && output.produced().bindingUuid().equals(produced.bindingUuid())
                                   && Objects.equals(output.pipeline(), pipeline));
        emitted.add(new Emitted(inputId, pipeline, produced, known));
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

    /// The last spans recorded for this input on this pipeline by this document's stage, or empty where
    /// none were.
    private List<TextRange> span(final String docUuid, final long inputId, final String pipeline) {
        return emitted.stream()
                .filter(output -> output.inputId() == inputId)
                .filter(output -> output.produced().docUuid().equals(docUuid))
                .filter(output -> Objects.equals(output.pipeline(), pipeline))
                .map(Emitted::spans)
                .filter(spans -> !spans.isEmpty())
                .reduce((first, last) -> last)
                .orElseGet(List::of);
    }

    @Override
    public synchronized Optional<TextRange> span(final String docUuid,
                                                 final long inputId,
                                                 final String pipeline,
                                                 final int recordIndex) {
        return emitted.stream()
                .filter(output -> output.inputId() == inputId)
                .filter(output -> output.produced().docUuid().equals(docUuid))
                .filter(output -> Objects.equals(output.pipeline(), pipeline))
                .map(Emitted::spans)
                .filter(spans -> recordIndex >= 0 && recordIndex < spans.size())
                .map(spans -> spans.get(recordIndex))
                .reduce((first, last) -> last);
    }

    @Override
    public synchronized Optional<Produced> asProcessed(final String docUuid,
                                                       final long inputId,
                                                       final String pipeline) {
        // The last thing recorded for this input on this pipeline by this document's stage: a stream
        // served twice keeps one row per binding, and what its output is now is what ran last.
        return emitted.stream()
                .filter(output -> output.inputId() == inputId)
                // This document's stage, since a pipeline may hold two and they record the same input.
                .filter(output -> output.produced().docUuid().equals(docUuid))
                // This pipeline's, exactly: a stream another pipeline produced is another output, and an
                // output of no pipeline is of no pipeline rather than of any.
                .filter(output -> Objects.equals(output.pipeline(), pipeline))
                .map(Emitted::produced)
                .reduce((first, last) -> last);
    }

    @Override
    public synchronized int prune(final long producedBeforeMs) {
        // Nothing in memory is old: a node's own list lasts as long as the node does, and what a node
        // holds is bounded by that.
        return 0;
    }

    public synchronized List<Emitted> emitted() {
        return List.copyOf(emitted);
    }

    public record Emitted(long inputId, String pipeline, Produced produced, List<TextRange> spans) {

    }
}
