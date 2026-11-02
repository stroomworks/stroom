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

package stroom.shapeshifter.ai.element;

import stroom.data.store.api.InputStreamProvider;
import stroom.data.store.api.SegmentOutputStream;
import stroom.data.store.api.Source;
import stroom.data.store.api.Store;
import stroom.data.store.api.Target;
import stroom.meta.api.MetaProperties;
import stroom.meta.api.MetaService;
import stroom.meta.shared.Meta;
import stroom.shapeshifter.ai.stage.CorpusIndex;
import stroom.shapeshifter.ai.stage.CorpusIndex.Row;
import stroom.shapeshifter.ai.stage.RegressionSet;
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

/// The corpus as a node keeps it (A18; design 04 §4.1): the entries of each promotion as one stream on
/// the **source feed**, of its own type, one entry per segment; and a row per entry in the index naming
/// the stream and segment. Kept on the feed the records came from so that the copy carries the feed's
/// classification and lives under the feed's retention rules, as A18 asks: a corpus that outlived the
/// data it was taken from would be the data, kept.
///
/// A stream is written once and never appended to, which is what a stream is; a promotion's entries are a
/// stream, and a binding's corpus is however many streams its promotions wrote, read through the index. The
/// cap (A63) drops rows, and a stream no row names any more is logically deleted.
@Singleton
public class StoredRegressionSet implements RegressionSet {

    /// The stream type the corpus is written as, so that it can be told from the feed's own data and
    /// from what the pipeline produced.
    public static final String CORPUS_TYPE = "Shapeshifter Corpus";

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
        // Each stream read once, however many of its segments the binding still names.
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
                // The stream is gone — retention took it, or a person did — or holds less than the row
                // says, or this user may not read the feed's data, which the corpus is. An entry nobody
                // can read holds nothing to anything; it is left out and said.
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
        // Said here rather than by the store, which would make a stream on no feed or fail somewhere less
        // telling.
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

    /// The feed of the stream the binding's newest entry is in.
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

    /// One stream for the entries, one segment per entry, each a line of JSON: the segment is what the
    /// row names, and the line is what a person opening the stream reads.
    private long write(final String feed, final List<Accepted> entries) {
        final MetaProperties properties = MetaProperties.builder()
                .feedName(feed)
                .typeName(CORPUS_TYPE)
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
                // A stream half written names nothing and would sit on the feed as data: taken off before
                // the failure is reported.
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

    /// What a segment holds: the entry's text, the index holding the rest.
    private record Entry(String record,
                         String text,
                         String preamble,
                         String postamble,
                         String output,
                         String target) {

    }
}
