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

package stroom.learningflow.element;

import stroom.data.shared.StreamTypeNames;
import stroom.data.store.api.InputStreamProvider;
import stroom.data.store.api.SegmentOutputStream;
import stroom.data.store.api.Source;
import stroom.data.store.api.Store;
import stroom.data.store.api.Target;
import stroom.learningflow.stage.CorpusIndex;
import stroom.learningflow.stage.CorpusIndex.Row;
import stroom.learningflow.stage.RegressionSet;
import stroom.meta.api.MetaProperties;
import stroom.meta.api.MetaService;
import stroom.meta.shared.Meta;
import stroom.util.io.StreamUtil;
import stroom.util.json.JsonUtil;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.logging.LogUtil;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/// The node's storage for the regression corpus: the records a binding was accepted on, each with the
/// output it was accepted with. The entries of each promotion are written as one stream on the **source
/// feed**, with its own stream type and one entry per segment, and the index ([CorpusIndex]) holds a row
/// per entry naming the stream and segment. The corpus is kept on the feed the records came from so that
/// it carries the feed's classification and is subject to the feed's retention rules; a corpus that
/// outlived the data it was taken from would amount to keeping that data.
///
/// Streams are written once and never appended to. Each promotion's entries form one stream, and a
/// binding's corpus is all the streams its promotions wrote, read through the index. The document's
/// regression cap is applied by dropping rows, and a stream that no row refers to any more is logically
/// deleted.
@Singleton
public class StoredRegressionSet implements RegressionSet {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(StoredRegressionSet.class);

    private final Store store;
    private final MetaService metaService;
    private final CorpusIndex index;

    @Inject
    public StoredRegressionSet(final Store store, final MetaService metaService, final CorpusIndex index) {
        this.store = store;
        this.metaService = metaService;
        this.index = index;
    }

    @Override
    public List<Accepted> accepted(final String bindingUuid) {
        final List<Row> rows = index.rows(bindingUuid);
        // Read each stream once, however many of its segments the binding still refers to.
        final Map<Long, List<String>> streams = new LinkedHashMap<>();
        final List<Accepted> accepted = new ArrayList<>();
        for (final Row row : rows) {
            final List<String> segments = streams.computeIfAbsent(row.metaId(), this::read);
            if (row.segment() < segments.size()) {
                final Entry entry = JsonUtil.readValue(segments.get(row.segment()), Entry.class);
                accepted.add(new Accepted(entry.record(), entry.text(), entry.preamble(), entry.postamble(),
                        entry.output(), row.kind(), row.score(), row.attemptId(), row.acceptTimeMs(),
                        entry.target()));
            } else {
                // The stream has gone (removed by retention or by a person), holds fewer entries than the
                // row says, or this user may not read the feed's data, which is what the corpus is. An
                // entry that cannot be read cannot hold a candidate to anything, so it is left out and
                // logged.
                LOGGER.warn(() -> LogUtil.message("Corpus entry for binding {} names segment {} of stream {}, "
                                                  + "which cannot be read: deleted, or not this user's to read",
                        bindingUuid, row.segment(), row.metaId()));
            }
        }
        return accepted;
    }

    @Override
    public void accept(final String docUuid,
                       final String bindingUuid,
                       final String feed,
                       final String type,
                       final List<Accepted> entries,
                       final int cap) {
        if (entries.isEmpty()) {
            return;
        }
        // Checked here rather than left to the store, which would create a stream with no feed or fail
        // with a less helpful message.
        Objects.requireNonNull(feed, "The feed to keep binding " + bindingUuid + "'s records on");
        final long metaId = write(feed, entries);
        final List<Row> rows = new ArrayList<>();
        for (int segment = 0; segment < entries.size(); segment++) {
            final Accepted entry = entries.get(segment);
            rows.add(new Row(docUuid, bindingUuid, metaId, segment, entry.kind(), entry.score(), entry.attemptId(),
                    entry.acceptedMs()));
        }
        index.add(rows);
        delete(index.trim(bindingUuid, cap));
    }

    /// Gets the feed of the stream that holds the binding's newest entry.
    @Override
    public Optional<String> feedOf(final String bindingUuid) {
        final List<Row> rows = index.rows(bindingUuid);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(metaService.getMeta(rows.getLast().metaId(), true))
                .map(Meta::getFeedName);
    }

    @Override
    public void discard(final String bindingUuid) {
        delete(index.discard(bindingUuid));
    }

    @Override
    public void move(final String fromBindingUuid, final String toBindingUuid, final int cap) {
        index.move(fromBindingUuid, toBindingUuid);
        delete(index.trim(toBindingUuid, cap));
    }

    /// Writes the entries as one stream, with one segment per entry, each a line of JSON. The index rows
    /// refer to entries by segment, and a person opening the stream sees one line per entry.
    private long write(final String feed, final List<Accepted> entries) {
        final MetaProperties properties = MetaProperties.builder()
                .feedName(feed)
                // The corpus has its own stream type, so that it can be told apart from the feed's own data
                // and from what the pipeline produced.
                .typeName(StreamTypeNames.LEARNING_FLOW_CORPUS)
                .build();
        try (final Target target = store.openTarget(properties)) {
            try (final SegmentOutputStream out = target.next().get()) {
                for (int i = 0; i < entries.size(); i++) {
                    if (i > 0) {
                        out.addSegment();
                    }
                    final Accepted entry = entries.get(i);
                    out.write((JsonUtil.writeValueAsString(new Entry(entry.record(), entry.text(), entry.preamble(),
                            entry.postamble(), entry.output(), entry.target()), false) + "\n")
                            .getBytes(StandardCharsets.UTF_8));
                }
            } catch (final IOException | RuntimeException e) {
                // A half-written stream is not referenced by any row and would sit on the feed as data, so
                // it is deleted before the failure is reported.
                target.logicallyDelete();
                throw e;
            }
            return target.getMeta().getId();
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private List<String> read(final long metaId) {
        try (final Source source = store.openSource(metaId)) {
            try (final InputStreamProvider provider = source.get(0)) {
                return StreamUtil.streamToString(provider.get()).lines().toList();
            }
        } catch (final Exception e) {
            LOGGER.warn(() -> LogUtil.message("Corpus stream {} could not be read: {}", metaId, e.getMessage()), e);
            return List.of();
        }
    }

    private void delete(final List<Long> metaIds) {
        metaIds.forEach(metaService::delete);
    }

    /// What a segment holds: the entry's text. The index holds the rest of the entry's details.
    private record Entry(String record,
                         String text,
                         String preamble,
                         String postamble,
                         String output,
                         String target) {

    }
}
