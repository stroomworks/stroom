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
import stroom.data.store.api.OutputStreamProvider;
import stroom.data.store.api.Store;
import stroom.data.store.api.Target;
import stroom.meta.api.MetaProperties;
import stroom.planb.impl.dao.LmdbWriter;
import stroom.planb.impl.dao.trace.PathwayEventsDb;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;

import com.google.inject.Inject;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.function.Consumer;

public class MessageReceiverFactory {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(MessageReceiverFactory.class);

    private final Store streamStore;
    private final PathwayEventsSerde pathwayEventsSerde;
    private final ByteBuffers byteBuffers;


    @Inject
    public MessageReceiverFactory(final Store streamStore,
                                  final PathwayEventsSerde pathwayEventsSerde,
                                  final ByteBuffers byteBuffers) {
        this.streamStore = streamStore;
        this.pathwayEventsSerde = pathwayEventsSerde;
        this.byteBuffers = byteBuffers;
    }

    public void create(final PathwayEventsDb eventsDb,
                       final LmdbWriter lmdbWriter,
                       final String feedName,
                       final Consumer<MessageReceiver> messageReceiverConsumer) {
        final MetaProperties metaProperties = MetaProperties.builder()
                .feedName(feedName)
                .typeName("Report")
//                .pipelineUuid(reportDoc.getUuid())
                .build();
        try {
            try (final Target streamTarget = streamStore.openTarget(metaProperties)) {
                try (final OutputStreamProvider outputStreamProvider = streamTarget.next()) {
                    try (final Writer writer = new OutputStreamWriter(outputStreamProvider.get())) {
                        final BufferingMessageReceiver receiver = new BufferingMessageReceiver(
                                writer, eventsDb, lmdbWriter, byteBuffers, pathwayEventsSerde);
                        messageReceiverConsumer.accept(receiver);
                        receiver.flush();
                    }
                }
            }
        } catch (final IOException | RuntimeException e) {
            LOGGER.error(e::getMessage, e);
        }
    }
}
