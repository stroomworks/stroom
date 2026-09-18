/*
 * Copyright 2025 Crown Copyright
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

package stroom.pathways.impl;

import stroom.bytebuffer.impl6.ByteBuffers;
import stroom.pathways.shared.PathwaysDoc;
import stroom.pathways.shared.otel.trace.NanoTime;
import stroom.pathways.shared.otel.trace.Span;
import stroom.pathways.shared.otel.trace.Trace;
import stroom.pathways.shared.pathway.PathKey;
import stroom.pathways.shared.pathway.PathNode;
import stroom.pathways.shared.pathway.Pathway;
import stroom.planb.impl.dao.LmdbWriter;
import stroom.planb.impl.dao.trace.NanoTimeUtil;
import stroom.planb.impl.dao.trace.PathwaysDb;
import stroom.planb.impl.dao.trace.PathwaysDb.SimpleDb;
import stroom.planb.impl.serde.trace.HexStringUtil;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.shared.Severity;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

public class TraceProcessor {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(TraceProcessor.class);
    private static final ByteBuffer PROCESSED = ByteBuffer.allocateDirect(0);

    private final ByteBuffers byteBuffers;
    private final PathwaySerde pathwaySerde;

    public TraceProcessor(final ByteBuffers byteBuffers,
                          final PathwaySerde pathwaySerde) {
        this.byteBuffers = byteBuffers;
        this.pathwaySerde = pathwaySerde;
    }

    /**
     * What became of one trace.
     *
     * <p>Three answers rather than two, because a caller that deletes the only copy of a trace once
     * this returns has to tell "there was nothing left to do" from "this could not be done".
     */
    public enum ApplyOutcome {
        /** Folded into the model, or marked so it is not offered again. The store was written to. */
        APPLIED,
        /** This store had already applied it. Nothing was written and there is nothing left to do. */
        ALREADY_APPLIED,
        /** Nothing could be done with it — it has no root span to key a pathway on. Not marked. */
        NOT_APPLICABLE
    }

    /**
     * Folds one trace into the model.
     *
     * @return what became of it. {@link ApplyOutcome#APPLIED} means the store was written to and needs
     * copying back.
     * @throws RuntimeException where the trace could not be applied. Deliberately not absorbed: the
     * caller holds the only copy and decides whether to delete it, so a failure it cannot see is a
     * trace lost.
     */
    public ApplyOutcome processTrace(final LmdbWriter writer,
                                     final PathwaysDb pathwaysDb,
                                     final byte[] traceId,
                                     final Function<byte[], Optional<Trace>> traceFunction,
                                     final PathwaysDoc doc,
                                     final MessageReceiver messageReceiver) {
        return byteBuffers.useBytes(traceId, keyByteBuffer -> {
            final SimpleDb processingStatus = pathwaysDb.getProcessingStatus();
            final boolean processed = processingStatus
                    .get(writer.getWriteTxn(), keyByteBuffer.duplicate(), Objects::nonNull);
            if (!processed) {
                final Optional<Trace> optTrace = traceFunction.apply(traceId);
                if (optTrace.isEmpty()) {
                    // findTrace returns empty only when the bucket holds no span at all for this
                    // trace — a trace with spans but no root span still comes back. Mark as
                    // processed so it is skipped on future ticks.
                    LOGGER.warn("Skipping trace root {} as the bucket holds no spans for it; " +
                                    "marking as processed to suppress future re-scans",
                            HexStringUtil.encode(traceId));
                    processingStatus.insert(writer, keyByteBuffer, PROCESSED);
                    writer.tryCommit();
                    return ApplyOutcome.APPLIED;
                } else {
                    final Trace trace = optTrace.get();
                    LOGGER.debug(() -> "\n" + trace.toString());
                    if (trace.root() == null) {
                        // A pathway is keyed on the root span's name, and this trace has no root
                        // span. Left unmarked rather than marked processed, because the root may
                        // still arrive: nothing offers a trace for processing until it has one, so
                        // leaving the marker off costs nothing and keeps the trace eligible.
                        messageReceiver.log(Severity.WARNING, () -> "Skipping trace "
                                + HexStringUtil.encode(traceId) + " as it has no root span");
                        return ApplyOutcome.NOT_APPLICABLE;
                    } else {
                        buildPathways(writer, trace, doc, messageReceiver, pathwaysDb);
                        processingStatus.insert(writer, keyByteBuffer, PROCESSED);
                        writer.tryCommit();
                        return ApplyOutcome.APPLIED;
                    }
                }
            }
            return ApplyOutcome.ALREADY_APPLIED;
        });
    }

    private void buildPathways(final LmdbWriter writer,
                               final Trace trace,
                               final PathwaysDoc doc,
                               final MessageReceiver messageReceiver,
                               final PathwaysDb pathwaysDb) {
        final CanonicalSpanOrder spanOrder = new CanonicalSpanOrder(doc.getTemporalOrderingTolerance());
        final PathKeyFactory pathKeyFactory = new PathKeyFactoryImpl();
        final NodeMutatorImpl nodeMutator = new NodeMutatorImpl(spanOrder);

        final Span root = trace.root();
        final PathKey pathKey = pathKeyFactory.create(Collections.singletonList(root));

        // Load current path.
        final SimpleDb pathways = pathwaysDb.getPathways();
        final byte[] keyBytes = pathKey.toString().getBytes(StandardCharsets.UTF_8);
        // What the pathway took last time, which is what it will take again give or take this trace.
        // Read before the value is decoded, because decoding consumes the buffer's position.
        final int[] storedSize = {0};
        byteBuffers.useBytes(keyBytes, keyByteBuffer -> {
            Pathway pathway = pathways.get(writer.getWriteTxn(), keyByteBuffer, valueByteBuffer -> {
                if (valueByteBuffer == null) {
                    messageReceiver.log(Severity.INFO, () -> "Adding new root path: " + root.getName());
                    final PathNode pathNode = new PathNode(root.getName());
                    final Instant now = Instant.now();
                    final NanoTime nanoTime = NanoTimeUtil.fromInstant(now);
                    return Pathway.builder()
                            .name(pathKey.toString())
                            .createTime(nanoTime)
                            .lastUsedTime(nanoTime)
                            .pathKey(pathKey)
                            .root(pathNode)
                            .build();
                }
                storedSize[0] = valueByteBuffer.remaining();
                return pathwaySerde.readPathway(valueByteBuffer);
            });

            PathNode pathNode = pathway.getRoot();
            pathNode = nodeMutator.process(trace, pathKey, pathNode, messageReceiver, doc);

            // Update pathway in database.
            final Instant now = Instant.now();
            final NanoTime nanoTime = NanoTimeUtil.fromInstant(now);
            pathway = pathway
                    .copy()
                    .updateTime(nanoTime)
                    .root(pathNode)
                    .build();

            // Write pathway.
            pathwaySerde.writePathway(pathway, storedSize[0], byteBuffer ->
                    pathways.insert(writer, keyByteBuffer, byteBuffer));
        });
    }
}
