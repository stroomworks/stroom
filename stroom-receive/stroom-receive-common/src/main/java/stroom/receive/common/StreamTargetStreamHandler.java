/*
 * Copyright 2017 Crown Copyright
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
 *
 */

package stroom.receive.common;

import stroom.data.shared.StreamTypeNames;
import stroom.data.store.api.OutputStreamProvider;
import stroom.data.store.api.Store;
import stroom.data.store.api.Target;
import stroom.data.zip.StroomZipEntry;
import stroom.data.zip.StroomZipFileType;
import stroom.data.zip.StroomZipNameSet;
import stroom.feed.api.FeedProperties;
import stroom.meta.api.AttributeMap;
import stroom.meta.api.AttributeMapUtil;
import stroom.meta.api.MetaProperties;
import stroom.meta.api.StandardHeaderArguments;
import stroom.meta.shared.Meta;
import stroom.meta.statistics.api.MetaStatistics;
import stroom.util.io.CloseableUtil;
import stroom.util.io.StreamUtil;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.logging.LogUtil;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Type of {@link StreamHandler} that store the entries in the stream store.
 * There are some special rules about how this works.
 * <p>
 * This is fine if all the meta data indicates they belong to the same feed
 * 001.meta, 002.meta, 001.dat, 002.dat
 * <p>
 * This is also fine if 001.meta indicates 001 belongs to feed X and 002.meta
 * indicates 001 belongs to feed Y 001.meta, 002.meta, 001.dat, 002.dat
 * <p>
 * However if the global header map indicates feed Z and the files are send in
 * the following order 001.dat, 002.dat, 001.meta, 002.meta this is invalid ....
 * I.E. as soon as we add non header stream for a feed if the header turns out
 * to be different we must throw an exception.
 */
public class StreamTargetStreamHandler implements StreamHandler, Closeable {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(StreamTargetStreamHandler.class);

    private final byte[] buffer = new byte[StreamUtil.BUFFER_SIZE];
    private final Consumer<Long> progressHandler = (totalBytes) -> {
    };
    private final Store store;
    private final FeedProperties feedProperties;
    private final MetaStatistics metaDataStatistics;
    private final String typeName;
    private final AttributeMap globalAttributeMap;
    private final HashSet<Meta> streamSet;
    private final StroomZipNameSet stroomZipNameSet;
    private final Map<String, Target> targetMap = new HashMap<>();
    private final ByteArrayOutputStream currentHeaderByteArrayOutputStream = new ByteArrayOutputStream();
    private StroomZipEntry currentStroomZipEntry = null;
    private StroomZipEntry lastDatStroomZipEntry = null;
    private StroomZipEntry lastCtxStroomZipEntry = null;
    private String currentFeedName;
    private AttributeMap currentAttributeMap;

    private OutputStreamProvider currentOutputStreamProvider;
    private Layer currentLayer;

    StreamTargetStreamHandler(final Store store,
                              final FeedProperties feedProperties,
                              final MetaStatistics metaDataStatistics,
                              final String feedName,
                              final String typeName,
                              final AttributeMap globalAttributeMap) {
        this.store = store;
        this.feedProperties = feedProperties;
        this.metaDataStatistics = metaDataStatistics;
        this.currentFeedName = feedName;
        this.typeName = typeName;
        this.streamSet = new HashSet<>();
        this.stroomZipNameSet = new StroomZipNameSet(true);
        this.globalAttributeMap = globalAttributeMap;
    }

    @Override
    public long addEntry(final String entry, final InputStream inputStream) throws IOException {
        long bytesWritten;
        LOGGER.debug(() -> LogUtil.message("handleEntryStart() - {}", entry));

        final StroomZipEntry nextEntry = stroomZipNameSet.add(entry);
        final StroomZipFileType stroomZipFileType = nextEntry.getStroomZipFileType();

        // We don't want to aggregate reference feeds.
        final boolean singleEntry = feedProperties.isReference(currentFeedName);

        if (singleEntry && currentStroomZipEntry != null && !nextEntry.equalsBaseName(currentStroomZipEntry)) {
            // Close it if we have opened it.
            if (targetMap.containsKey(currentFeedName)) {
                LOGGER.debug(() -> LogUtil.message(
                        "handleEntryStart() - Closing due to singleEntry={} currentFeedName={} " +
                                "currentStroomZipEntry={} nextEntry={}",
                        singleEntry, currentFeedName, currentStroomZipEntry, nextEntry));
                closeCurrentFeed();
            }
        }

        currentStroomZipEntry = nextEntry;

        if (StroomZipFileType.META.equals(stroomZipFileType)) {
            // Buffer up the header.
            currentHeaderByteArrayOutputStream.reset();
            bytesWritten = StreamUtil.streamToStream(
                    inputStream,
                    currentHeaderByteArrayOutputStream,
                    buffer,
                    progressHandler);

            final byte[] headerBytes = currentHeaderByteArrayOutputStream.toByteArray();
            currentAttributeMap = null;
            if (globalAttributeMap != null) {
                currentAttributeMap = AttributeMapUtil.cloneAllowable(globalAttributeMap);
            } else {
                currentAttributeMap = new AttributeMap();
            }
            AttributeMapUtil.read(headerBytes, currentAttributeMap);

            if (metaDataStatistics != null) {
                metaDataStatistics.recordStatistics(currentAttributeMap);
            }

            // Are we switching feed?
            final String feedName = currentAttributeMap.get(StandardHeaderArguments.FEED);
            if (feedName != null) {
                if (currentFeedName == null || !currentFeedName.equals(feedName)) {
                    // Yes ... load the new feed
                    currentFeedName = feedName;

                    final String currentBaseName = currentStroomZipEntry.getBaseName();

                    // Have we stored some data or context
                    if (lastDatStroomZipEntry != null
                            && stroomZipNameSet.getBaseName(lastDatStroomZipEntry.getFullName())
                            .equals(currentBaseName)) {
                        throw new IOException("Header and Data out of order for multiple feed data");
                    }
                    if (lastCtxStroomZipEntry != null
                            && stroomZipNameSet.getBaseName(lastCtxStroomZipEntry.getFullName())
                            .equals(currentBaseName)) {
                        throw new IOException("Header and Data out of order for multiple feed data");
                    }
                }
            }

            checkLayer(StroomZipFileType.META);
            try (final OutputStream outputStream = currentOutputStreamProvider.get(StreamTypeNames.META)) {
                outputStream.write(headerBytes);
            }

        } else if (StroomZipFileType.CONTEXT.equals(stroomZipFileType)) {
            // Check to see if we need to move to the next output and do so if necessary.
            checkLayer(stroomZipFileType);
            try (final OutputStream currentOutputStream = currentOutputStreamProvider.get(StreamTypeNames.CONTEXT)) {
                bytesWritten = StreamUtil.streamToStream(
                        inputStream,
                        currentOutputStream,
                        buffer,
                        progressHandler);
            }

            lastCtxStroomZipEntry = currentStroomZipEntry;

        } else {
            // Check to see if we need to move to the next output and do so if necessary.
            checkLayer(stroomZipFileType);
            try (final OutputStream currentOutputStream = currentOutputStreamProvider.get()) {
                bytesWritten = StreamUtil.streamToStream(
                        inputStream,
                        currentOutputStream,
                        buffer,
                        progressHandler);
            }

            lastDatStroomZipEntry = currentStroomZipEntry;
        }

        return bytesWritten;
    }

    /**
     * Layers are used to synchronise writing context, meta and actual data to the current output stream provider.
     *
     * @param type The type that needs to be added to the current layer.
     */
    private void checkLayer(final StroomZipFileType type) {
        if (currentLayer == null || currentLayer.hasType(type)) {
            // We have either not initialised any layer or the current layer already includes this type so start
            // a new layer.
            currentLayer = new Layer();
            // Tell the new layer that it will contain the requested type.
            currentLayer.hasType(type);
            // Get a new output stream provider for the new layer.
            currentOutputStreamProvider = getTarget().next();
        }
    }

    void error() {
        targetMap.values().forEach(store::deleteTarget);
        targetMap.clear();
    }

    public void closeDelete() {
        targetMap.values().forEach(store::deleteTarget);
        targetMap.clear();
    }

    @Override
    public void close() {
        targetMap.values().forEach(CloseableUtil::closeLogAndIgnoreException);
        targetMap.clear();
    }

    private void closeCurrentFeed() {
        LOGGER.debug(() -> LogUtil.message("closeCurrentFeed() - {}", currentFeedName));
        CloseableUtil.closeLogAndIgnoreException(targetMap.remove(currentFeedName));
    }

    public Set<Meta> getStreamSet() {
        return Collections.unmodifiableSet(streamSet);
    }

    private AttributeMap getCurrentAttributeMap() {
        if (currentAttributeMap != null) {
            return currentAttributeMap;
        }
        return globalAttributeMap;
    }

    private Target getTarget() {
        return targetMap.computeIfAbsent(currentFeedName, k -> {
            LOGGER.debug(() -> LogUtil.message("getOutputStreamProvider() - open stream for {}", currentFeedName));

            // Get the effective time if one has been provided.
            final Long effectiveMs = StreamFactory.getReferenceEffectiveTime(getCurrentAttributeMap(), true);

            final MetaProperties metaProperties = MetaProperties.builder()
                    .feedName(currentFeedName)
                    .typeName(typeName)
                    .effectiveMs(effectiveMs)
                    .build();

            final Target streamTarget = store.openTarget(metaProperties);
            streamSet.add(streamTarget.getMeta());

            return streamTarget;
        });
    }

    private static class Layer {

        final Set<StroomZipFileType> types = new HashSet<>();

        boolean hasType(final StroomZipFileType type) {
            return !types.add(type);
        }
    }
}