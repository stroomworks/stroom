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

package stroom.pipeline.server.task;

import com.google.common.collect.ImmutableMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MarkerFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import stroom.feed.MetaMap;
import stroom.feed.server.FeedService;
import stroom.feed.shared.Feed;
import stroom.io.StreamCloser;
import stroom.node.server.NodeCache;
import stroom.pipeline.destination.Destination;
import stroom.pipeline.destination.DestinationProvider;
import stroom.pipeline.server.DefaultErrorWriter;
import stroom.pipeline.server.EncodingSelection;
import stroom.pipeline.server.ErrorWriterProxy;
import stroom.pipeline.server.LocationFactoryProxy;
import stroom.pipeline.server.PipelineService;
import stroom.pipeline.server.StreamLocationFactory;
import stroom.pipeline.server.errorhandler.ErrorReceiverProxy;
import stroom.pipeline.server.errorhandler.ErrorStatistics;
import stroom.pipeline.server.errorhandler.LoggedException;
import stroom.pipeline.server.errorhandler.RecordErrorReceiver;
import stroom.pipeline.server.factory.AbstractElement;
import stroom.pipeline.server.factory.Pipeline;
import stroom.pipeline.server.factory.PipelineDataCache;
import stroom.pipeline.server.factory.PipelineFactory;
import stroom.pipeline.server.factory.Processor;
import stroom.pipeline.server.task.ProcessStatisticsFactory.ProcessStatistics;
import stroom.pipeline.shared.PipelineEntity;
import stroom.pipeline.shared.data.PipelineData;
import stroom.pipeline.state.FeedHolder;
import stroom.pipeline.state.MetaData;
import stroom.pipeline.state.PipelineHolder;
import stroom.pipeline.state.RecordCount;
import stroom.pipeline.state.SearchIdHolder;
import stroom.pipeline.state.StreamHolder;
import stroom.pipeline.state.StreamProcessorHolder;
import stroom.statistics.internal.InternalStatisticEvent;
import stroom.statistics.internal.InternalStatisticsReceiver;
import stroom.streamstore.server.StreamSource;
import stroom.streamstore.server.StreamStore;
import stroom.streamstore.server.StreamTarget;
import stroom.streamstore.server.fs.serializable.RASegmentInputStream;
import stroom.streamstore.server.fs.serializable.StreamSourceInputStreamProvider;
import stroom.streamstore.shared.Stream;
import stroom.streamstore.shared.StreamAttributeConstants;
import stroom.streamstore.shared.StreamType;
import stroom.streamtask.server.InclusiveRanges;
import stroom.streamtask.server.InclusiveRanges.InclusiveRange;
import stroom.streamtask.server.StreamProcessorTaskExecutor;
import stroom.streamtask.shared.StreamProcessor;
import stroom.streamtask.shared.StreamProcessorFilter;
import stroom.streamtask.shared.StreamTask;
import stroom.util.date.DateUtil;
import stroom.util.io.PreviewInputStream;
import stroom.util.io.WrappedOutputStream;
import stroom.util.shared.ModelStringUtil;
import stroom.util.shared.Severity;
import stroom.util.spring.StroomScope;
import stroom.util.task.TaskMonitor;

import javax.inject.Inject;
import javax.inject.Named;
import javax.persistence.OptimisticLockException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

@Component
@Scope(StroomScope.PROTOTYPE)
public class PipelineStreamProcessor implements StreamProcessorTaskExecutor {
    private static final Logger LOGGER = LoggerFactory.getLogger(PipelineStreamProcessor.class);
    private static final String PROCESSING = "Processing:";
    private static final String FINISHED = "Finished:";
    private static final int PREVIEW_SIZE = 100;
    private static final int MIN_STREAM_SIZE = 1;
    private static final Pattern XML_DECL_PATTERN = Pattern.compile("<\\?\\s*xml[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final String INTERNAL_STAT_KEY_PIPELINE_STREAM_PROCESSOR = "pipelineStreamProcessor";

    private final PipelineFactory pipelineFactory;
    private final StreamStore streamStore;
    private final FeedService feedService;
    private final PipelineService pipelineService;
    private final TaskMonitor taskMonitor;
    private final PipelineHolder pipelineHolder;
    private final FeedHolder feedHolder;
    private final StreamHolder streamHolder;
    private final SearchIdHolder searchIdHolder;
    private final LocationFactoryProxy locationFactory;
    private final StreamProcessorHolder streamProcessorHolder;
    private final ErrorReceiverProxy errorReceiverProxy;
    private final ErrorWriterProxy errorWriterProxy;
    private final MetaData metaData;
    private final RecordCount recordCount;
    private final StreamCloser streamCloser;
    private final RecordErrorReceiver recordErrorReceiver;
    private final NodeCache nodeCache;
    private final PipelineDataCache pipelineDataCache;
    private final InternalStatisticsReceiver internalStatisticsReceiver;
    private final SupersededOutputHelper supersededOutputHelper;

    private StreamProcessor streamProcessor;
    private StreamProcessorFilter streamProcessorFilter;
    private StreamTask streamTask;
    private StreamSource streamSource;

    private long startTime;

    @Inject
    public PipelineStreamProcessor(final PipelineFactory pipelineFactory,
                                   final StreamStore streamStore,
                                   @Named("cachedFeedService") final FeedService feedService,
                                   @Named("cachedPipelineService") final PipelineService pipelineService,
                                   final TaskMonitor taskMonitor,
                                   final PipelineHolder pipelineHolder,
                                   final FeedHolder feedHolder,
                                   final StreamHolder streamHolder,
                                   final SearchIdHolder searchIdHolder,
                                   final LocationFactoryProxy locationFactory,
                                   final StreamProcessorHolder streamProcessorHolder,
                                   final ErrorReceiverProxy errorReceiverProxy,
                                   final ErrorWriterProxy errorWriterProxy,
                                   final MetaData metaData,
                                   final RecordCount recordCount,
                                   final StreamCloser streamCloser,
                                   final RecordErrorReceiver recordErrorReceiver,
                                   final NodeCache nodeCache,
                                   final PipelineDataCache pipelineDataCache,
                                   final InternalStatisticsReceiver internalStatisticsReceiver,
                                   final SupersededOutputHelper supersededOutputHelper) {
        this.pipelineFactory = pipelineFactory;
        this.streamStore = streamStore;
        this.feedService = feedService;
        this.pipelineService = pipelineService;
        this.taskMonitor = taskMonitor;
        this.pipelineHolder = pipelineHolder;
        this.feedHolder = feedHolder;
        this.streamHolder = streamHolder;
        this.searchIdHolder = searchIdHolder;
        this.locationFactory = locationFactory;
        this.streamProcessorHolder = streamProcessorHolder;
        this.errorReceiverProxy = errorReceiverProxy;
        this.errorWriterProxy = errorWriterProxy;
        this.metaData = metaData;
        this.recordCount = recordCount;
        this.streamCloser = streamCloser;
        this.recordErrorReceiver = recordErrorReceiver;
        this.nodeCache = nodeCache;
        this.pipelineDataCache = pipelineDataCache;
        this.internalStatisticsReceiver = internalStatisticsReceiver;
        this.supersededOutputHelper = supersededOutputHelper;
    }

    @Override
    public void exec(final StreamProcessor streamProcessor, final StreamProcessorFilter streamProcessorFilter,
                     final StreamTask streamTask, final StreamSource streamSource) {
        this.streamProcessor = streamProcessor;
        this.streamProcessorFilter = streamProcessorFilter;
        this.streamTask = streamTask;
        this.streamSource = streamSource;

        // Record when processing began so we know how long it took
        // afterwards.
        startTime = System.currentTimeMillis();

        // Setup the error handler and receiver.
        errorReceiverProxy.setErrorReceiver(recordErrorReceiver);

        // Initialise the helper class that will ensure we only keep the latest output for this stream source and processor.
        final Stream stream = streamSource.getStream();
        supersededOutputHelper.init(stream, streamProcessor, streamTask, startTime);

        // Setup the process info writer.
        try (final ProcessInfoOutputStreamProvider processInfoOutputStreamProvider = new ProcessInfoOutputStreamProvider(streamStore,
                metaData,
                stream,
                streamProcessor,
                streamTask,
                recordCount,
                errorReceiverProxy,
                supersededOutputHelper)) {

            try {
                final DefaultErrorWriter errorWriter = new DefaultErrorWriter();
                errorWriter.addOutputStreamProvider(processInfoOutputStreamProvider);
                errorWriterProxy.setErrorWriter(errorWriter);

                process();

            } catch (final Exception e) {
                outputError(e);
            }
        } catch (final IOException e) {
            LOGGER.error(e.getMessage(), e);
        }
    }

    private void process() {
        Feed feed = null;
        PipelineEntity pipelineEntity = null;

        try {
            final Stream stream = streamSource.getStream();

            // Update the meta data for all output streams to use.
            metaData.put("Source Stream", String.valueOf(stream.getId()));
            metaData.put(StreamAttributeConstants.NODE, nodeCache.getDefaultNode().getName());

            // Set the search id to be the id of the stream processor filter.
            // Only do this where the task has specific data ranges that need extracting as this is only the case with a batch search.
            if (streamProcessorFilter != null && streamTask.getData() != null && streamTask.getData().length() > 0) {
                searchIdHolder.setSearchId(Long.toString(streamProcessorFilter.getId()));
            }

            // Load the feed.
            feed = feedService.load(stream.getFeed());
            feedHolder.setFeed(feed);

            // Set the pipeline so it can be used by a filter if needed.
            pipelineEntity = pipelineService.load(streamProcessor.getPipeline());
            pipelineHolder.setPipeline(pipelineEntity);

            // Create some processing info.
            final String info = " pipeline=" +
                    pipelineEntity.getName() +
                    ", feed=" +
                    feed.getName() +
                    ", streamId=" +
                    stream.getId() +
                    ", streamCreated=" +
                    DateUtil.createNormalDateTimeString(stream.getCreateMs());

            // Create processing start message.
            final String processingInfo = PROCESSING + info;

            // Log that we are starting to process.
            taskMonitor.info(processingInfo);
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info(processingInfo);
            }

            // Hold the source and feed so the pipeline filters can get them.
            streamProcessorHolder.setStreamProcessor(streamProcessor, streamTask);
            feedHolder.setFeed(feed);

            // Process the streams.
            final PipelineData pipelineData = pipelineDataCache.get(pipelineEntity);
            final Pipeline pipeline = pipelineFactory.create(pipelineData);
            processNestedStreams(pipeline, stream, streamSource, feed, stream.getStreamType());

            // Create processing finished message.
            final String finishedInfo = FINISHED +
                    info +
                    ", finished in " +
                    ModelStringUtil.formatDurationString(System.currentTimeMillis() - startTime);

            // Log that we have finished processing.
            taskMonitor.info(finishedInfo);
            LOGGER.info(finishedInfo);

        } catch (final Exception e) {
            outputError(e);

        } finally {
            // Record some statistics about processing.
            recordStats(feed, pipelineEntity);

            try {
                // Close all open streams.
                streamCloser.close();
            } catch (final IOException e) {
                outputError(e);
            }
        }
    }

    private void recordStats(final Feed feed, final PipelineEntity pipelineEntity) {
        try {
            InternalStatisticEvent event = InternalStatisticEvent.createPlusOneCountStat(
                    INTERNAL_STAT_KEY_PIPELINE_STREAM_PROCESSOR,
                    System.currentTimeMillis(),
                    ImmutableMap.of(
                            "Feed", feed.getName(),
                            "Pipeline", pipelineEntity.getName(),
                            "Node", nodeCache.getDefaultNode().getName()));

            internalStatisticsReceiver.putEvent(event);

        } catch (final Exception ex) {
            LOGGER.error("recordStats", ex);
        }
    }

    public long getRead() {
        return recordCount.getRead();
    }

    public long getWritten() {
        return recordCount.getWritten();
    }

    public long getMarkerCount(final Severity... severity) {
        long count = 0;
        if (errorReceiverProxy.getErrorReceiver() instanceof ErrorStatistics) {
            final ErrorStatistics statistics = (ErrorStatistics) errorReceiverProxy.getErrorReceiver();
            for (final Severity sev : severity) {
                count += statistics.getRecords(sev);
            }
        }
        return count;
    }

    /**
     * Processes a source and writes the result to a target.
     */
    private void processNestedStreams(final Pipeline pipeline, final Stream stream, final StreamSource streamSource,
                                      final Feed feed, final StreamType streamType) {
        try {
            boolean startedProcessing = false;

            // Get the stream providers.
            streamHolder.setStream(stream);
            streamHolder.addProvider(streamSource);
            streamHolder.addProvider(streamSource.getChildStream(StreamType.META));
            streamHolder.addProvider(streamSource.getChildStream(StreamType.CONTEXT));

            // Get the main stream provider.
            final StreamSourceInputStreamProvider mainProvider = streamHolder.getProvider(streamType);

            try {
                final StreamLocationFactory streamLocationFactory = new StreamLocationFactory();
                locationFactory.setLocationFactory(streamLocationFactory);

                // Loop over the stream boundaries and process each
                // sequentially.
                final long streamCount = mainProvider.getStreamCount();
                for (long streamNo = 0; streamNo < streamCount && !taskMonitor.isTerminated(); streamNo++) {
                    InputStream inputStream;

                    // If the task requires specific events to be processed then
                    // add them.
                    final String data = streamTask.getData();
                    if (data != null && data.length() > 0) {
                        final List<InclusiveRange> ranges = InclusiveRanges.rangesFromString(data);
                        final RASegmentInputStream raSegmentInputStream = mainProvider.getSegmentInputStream(streamNo);
                        raSegmentInputStream.include(0);
                        for (final InclusiveRange range : ranges) {
                            for (long i = range.getMin(); i <= range.getMax(); i++) {
                                raSegmentInputStream.include(i);
                            }
                        }
                        raSegmentInputStream.include(raSegmentInputStream.count() - 1);
                        inputStream = raSegmentInputStream;

                    } else {
                        // Get the stream.
                        inputStream = mainProvider.getStream(streamNo);
                    }

                    // Get the appropriate encoding for the stream type.
                    final String encoding = EncodingSelection.select(feed, streamType);

                    // We want to get a preview of the input stream so we can
                    // skip it if it is effectively empty.
                    final PreviewInputStream previewInputStream = new PreviewInputStream(inputStream);
                    String preview = previewInputStream.previewAsString(PREVIEW_SIZE, encoding);
                    // Remove whitespace from the preview.
                    preview = preview.trim();

                    // If there are still characters in the preview then
                    // continue.
                    if (preview.length() >= MIN_STREAM_SIZE) {
                        // Try and remove XML declaration for cases where the
                        // input is blank except for an XML declaration.
                        preview = XML_DECL_PATTERN.matcher(preview).replaceFirst("");
                        // Remove whitespace from the preview.
                        preview = preview.trim();

                        // Skip the input stream if it is empty. replaces:
                        // inputStream.size >= MIN_STREAM_SIZE
                        if (preview.length() >= MIN_STREAM_SIZE) {
                            // Start processing if we haven't already.
                            if (!startedProcessing) {
                                startedProcessing = true;
                                pipeline.startProcessing();
                            }

                            streamHolder.setStreamNo(streamNo);
                            streamLocationFactory.setStreamNo(streamNo + 1);

                            // Process the boundary.
                            try {
                                pipeline.process(previewInputStream, encoding);
                            } catch (final LoggedException e) {
                                // The exception has already been logged so
                                // ignore it.
                                if (LOGGER.isTraceEnabled() && stream != null) {
                                    LOGGER.trace("Error while processing stream task: id = " + stream.getId(), e);
                                }
                            } catch (final Exception e) {
                                outputError(e);
                            }

                            // Reset the error statistics for the next stream.
                            if (errorReceiverProxy.getErrorReceiver() instanceof ErrorStatistics) {
                                ((ErrorStatistics) errorReceiverProxy.getErrorReceiver()).reset();
                            }
                        }
                    }
                }
            } catch (final LoggedException e) {
                // The exception has already been logged so ignore it.
                if (LOGGER.isTraceEnabled() && stream != null) {
                    LOGGER.trace("Error while processing stream task: id = " + stream.getId(), e);
                }
            } catch (final Exception e) {
                // An exception that's gets here is definitely a failure.
                outputError(e);

            } finally {
                try {
                    if (startedProcessing) {
                        pipeline.endProcessing();
                    }
                } catch (final LoggedException e) {
                    // The exception has already been logged so ignore it.
                    if (LOGGER.isTraceEnabled() && stream != null) {
                        LOGGER.trace("Error while processing stream task: id = " + stream.getId(), e);
                    }
                } catch (final Exception e) {
                    outputError(e);
                }
            }
        } catch (final Exception e) {
            outputError(e);
        }
    }

    private void outputError(final Exception ex) {
        outputError(ex, Severity.FATAL_ERROR);
    }

    /**
     * Used to handle any errors that may occur during translation.
     */
    private void outputError(final Exception ex, final Severity severity) {
        if (errorReceiverProxy != null && !(ex instanceof LoggedException)) {
            try {
                if (ex.getMessage() != null) {
                    errorReceiverProxy.log(severity, null, "PipelineStreamProcessor", ex.getMessage(), ex);
                } else {
                    errorReceiverProxy.log(severity, null, "PipelineStreamProcessor", ex.toString(), ex);
                }
            } catch (final Throwable e) {
                // Ignore exception as we generated it.
            }

            if (errorReceiverProxy.getErrorReceiver() instanceof ErrorStatistics) {
                ((ErrorStatistics) errorReceiverProxy.getErrorReceiver()).checkRecord(-1);
            }

            if (LOGGER.isTraceEnabled() && streamSource.getStream() != null) {
                LOGGER.trace("Error while processing stream task: id = " + streamSource.getStream().getId(), ex);
            }
        } else {
            LOGGER.error(MarkerFactory.getMarker("FATAL"), ex.getMessage(), ex);
        }
    }

    @Override
    public String toString() {
        return String.valueOf(streamSource.getStream());
    }

    private static class ProcessInfoOutputStreamProvider extends AbstractElement
            implements DestinationProvider, Destination, AutoCloseable {
        private final StreamStore streamStore;
        private final MetaData metaData;
        private final Stream stream;
        private final StreamProcessor streamProcessor;
        private final StreamTask streamTask;
        private final RecordCount recordCount;
        private final ErrorReceiverProxy errorReceiverProxy;
        private final SupersededOutputHelper supersededOutputHelper;

        private OutputStream processInfoOutputStream;
        private StreamTarget processInfoStreamTarget;

        ProcessInfoOutputStreamProvider(final StreamStore streamStore,
                                        final MetaData metaData,
                                        final Stream stream,
                                        final StreamProcessor streamProcessor,
                                        final StreamTask streamTask,
                                        final RecordCount recordCount,
                                        final ErrorReceiverProxy errorReceiverProxy,
                                        final SupersededOutputHelper supersededOutputHelper) {
            this.streamStore = streamStore;
            this.metaData = metaData;
            this.stream = stream;
            this.streamProcessor = streamProcessor;
            this.streamTask = streamTask;
            this.recordCount = recordCount;
            this.errorReceiverProxy = errorReceiverProxy;
            this.supersededOutputHelper = supersededOutputHelper;
        }

        @Override
        public Destination borrowDestination() {
            return this;
        }

        @Override
        public void returnDestination(final Destination destination) {
        }

        @Override
        public OutputStream getByteArrayOutputStream() {
            return getOutputStream(null, null);
        }

        @Override
        public OutputStream getOutputStream(final byte[] header, final byte[] footer) {
            if (processInfoOutputStream == null) {
                // Create a processing info stream to write all processing
                // information to.
                final Stream errorStream = Stream.createProcessedStream(stream, stream.getFeed(), StreamType.ERROR,
                        streamProcessor, streamTask);

                processInfoStreamTarget = streamStore.openStreamTarget(errorStream);
                processInfoOutputStream = new WrappedOutputStream(processInfoStreamTarget.getOutputStream()) {
                    @Override
                    public void close() throws IOException {
                        try {
                            super.flush();
                            super.close();

                        } finally {
                            // Only do something if an output stream was used.
                            if (processInfoStreamTarget != null) {
                                // Write meta data.
                                final MetaMap metaMap = metaData.getMetaMap();
                                processInfoStreamTarget.getAttributeMap().putAll(metaMap);

                                try {
                                    // Write statistics meta data.
                                    // Get current process statistics
                                    final ProcessStatistics processStatistics = ProcessStatisticsFactory.create(recordCount, errorReceiverProxy);
                                    processStatistics.write(processInfoStreamTarget.getAttributeMap());
                                } catch (final RuntimeException e) {
                                    LOGGER.error(e.getMessage(), e);
                                }

                                // Close the stream target.
                                try {
                                    if (supersededOutputHelper.isSuperseded()) {
                                        streamStore.deleteStreamTarget(processInfoStreamTarget);
                                    } else {
                                        streamStore.closeStreamTarget(processInfoStreamTarget);
                                    }
                                } catch (final OptimisticLockException e) {
                                    // This exception will be thrown is the stream target has already been deleted by another thread if it was superseded.
                                    LOGGER.debug("Optimistic lock exception thrown when closing stream target (see trace for details)");
                                    LOGGER.trace(e.getMessage(), e);
                                } catch (final RuntimeException e) {
                                    LOGGER.error(e.getMessage(), e);
                                }
                            }
                        }
                    }
                };
            }

            return processInfoOutputStream;
        }

        public void close() throws IOException {
            if (processInfoOutputStream != null) {
                processInfoOutputStream.close();
            }
        }

        @Override
        public List<Processor> createProcessors() {
            return Collections.emptyList();
        }
    }
}