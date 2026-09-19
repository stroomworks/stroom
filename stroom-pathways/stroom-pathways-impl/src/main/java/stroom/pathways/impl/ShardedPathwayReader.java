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

package stroom.pathways.impl;

import stroom.bytebuffer.ByteBufferUtils;
import stroom.pathways.shared.FindPathwayCriteria;
import stroom.pathways.shared.PathwayResultPage;
import stroom.pathways.shared.PathwaySummary;
import stroom.pathways.shared.PathwaysDoc;
import stroom.pathways.shared.otel.trace.NanoTime;
import stroom.pathways.shared.pathway.Pathway;
import stroom.planb.impl.dao.ShardKeyRouter;
import stroom.planb.impl.dao.trace.PathwaysDb;
import stroom.planb.shared.SharedFileStoreSettings;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.shared.CriteriaFieldSort;
import stroom.util.shared.NullSafe;
import stroom.util.shared.PageRequest;
import stroom.util.shared.PageResponse;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Answers a pathway search from every shard of a document's model.
 *
 * <p>A pathway is keyed on its operation name and that name decides its shard, so no pathway appears
 * in two shards and nothing has to be combined — only gathered and put back in order.
 *
 * <p>Done in two passes. The first reads names only, which costs no deserialising, and sorts them to
 * get the one order a page can be taken from. The second fetches just the page, and knows which shard
 * to ask because the name says so. The alternative — deserialising every match to find out which
 * belong on the page — would read the whole model to return twenty rows.
 */
@Singleton
public class ShardedPathwayReader {

    private static final Comparator<PathwaySummary> BY_NAME =
            Comparator.comparing(PathwaySummary::getName, Comparator.nullsFirst(Comparator.naturalOrder()));

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(ShardedPathwayReader.class);

    private final PathwaysShardStore shardStore;
    private final PathwaySerde pathwaySerde;

    @Inject
    public ShardedPathwayReader(final PathwaysShardStore shardStore,
                                final PathwaySerde pathwaySerde) {
        this.shardStore = shardStore;
        this.pathwaySerde = pathwaySerde;
    }

    public PathwayResultPage findPathways(final PathwaysDoc doc, final FindPathwayCriteria criteria) {
        final SharedFileStoreSettings settings = doc.getSharedFileStore();
        if (settings == null || NullSafe.isBlankString(settings.getSharedPath())) {
            // Nothing configured, so nothing was ever written for it.
            return new PathwayResultPage(List.of(), PageResponse.empty());
        }
        final int shardCount = shardCountOf(settings);
        final PageRequest pageRequest = criteria.getPageRequest();

        // Every matching pathway, not just the ones on the page: a sort on anything but the name has
        // to see them all before it can say which belong on it. Only each one's header is read, and
        // the scan of the keys has that in hand already.
        final List<PathwaySummary> summaries = new ArrayList<>();
        for (int shard = 0; shard < shardCount; shard++) {
            summaries.addAll(matchingSummaries(doc, shard, criteria.getFilter()));
        }
        summaries.sort(comparator(criteria.getSortList()));

        final List<PathwaySummary> page = summaries.stream()
                .skip(pageRequest.getOffset())
                .limit(pageRequest.getLength())
                .toList();

        final PageResponse pageResponse = PageResponse
                .builder()
                .offset(pageRequest.getOffset())
                .length(page.size())
                .total((long) summaries.size())
                .exact(true)
                .build();
        return new PathwayResultPage(page, pageResponse);
    }

    // What one shard holds that the filter accepts. The name is taken from the key so a rejected
    // pathway costs nothing beyond that; only an accepted one has its header decoded.
    private List<PathwaySummary> matchingSummaries(final PathwaysDoc doc,
                                                   final int shard,
                                                   final String filter) {
        final List<PathwaySummary> summaries = new ArrayList<>();
        readShard(doc, shard, db -> {
            db.getPathways().iterate((key, val) -> {
                final String name = ByteBufferUtils.toString(key);
                if (NullSafe.isBlankString(filter) || name.contains(filter)) {
                    summaries.add(pathwaySerde.readSummary(val));
                }
            });
            return summaries;
        });
        return summaries;
    }

    // Orders by the columns the grid was sorted on, in the order they were clicked. Falls back to the
    // name where nothing was asked for, which is where the grid starts.
    private static Comparator<PathwaySummary> comparator(final List<CriteriaFieldSort> sortList) {
        Comparator<PathwaySummary> comparator = null;
        for (final CriteriaFieldSort sort : NullSafe.list(sortList)) {
            final Comparator<PathwaySummary> field = fieldComparator(sort);
            if (field != null) {
                comparator = comparator == null
                        ? field
                        : comparator.thenComparing(field);
            }
        }
        return comparator == null
                ? BY_NAME
                : comparator;
    }

    private static Comparator<PathwaySummary> fieldComparator(final CriteriaFieldSort sort) {
        final Comparator<PathwaySummary> comparator = switch (NullSafe.string(sort.getId())) {
            case PathwaySummary.FIELD_NAME -> sort.isIgnoreCase()
                    ? Comparator.comparing(PathwaySummary::getName,
                            Comparator.nullsFirst(String.CASE_INSENSITIVE_ORDER))
                    : BY_NAME;
            case PathwaySummary.FIELD_CREATE_TIME -> byTime(PathwaySummary::getCreateTime);
            case PathwaySummary.FIELD_UPDATE_TIME -> byTime(PathwaySummary::getUpdateTime);
            case PathwaySummary.FIELD_LAST_USED_TIME -> byTime(PathwaySummary::getLastUsedTime);
            case PathwaySummary.FIELD_SIZE -> Comparator.comparingLong(PathwaySummary::getSizeBytes);
            // A column the reader knows nothing about orders on nothing, rather than throwing away a
            // page of results.
            default -> null;
        };
        return comparator == null || !sort.isDesc()
                ? comparator
                : comparator.reversed();
    }

    // A pathway that has never recorded the time sorts before one that has, rather than blowing up.
    private static Comparator<PathwaySummary> byTime(final Function<PathwaySummary, NanoTime> time) {
        return Comparator.comparing(time, Comparator.nullsFirst(Comparator.naturalOrder()));
    }

    /**
     * One learnt pathway in full, or empty where the document holds none by that name.
     *
     * <p>Separate from the list because the list does not want this: a pathway keeps every path it has
     * seen an operation take, so reading one to render a row is what a page of rows could not afford.
     */
    public Optional<Pathway> fetchPathway(final PathwaysDoc doc, final String name) {
        final SharedFileStoreSettings settings = doc.getSharedFileStore();
        if (settings == null || NullSafe.isBlankString(settings.getSharedPath())
            || NullSafe.isBlankString(name)) {
            return Optional.empty();
        }
        final int shard = ShardKeyRouter.computeShardIndex(name, shardCountOf(settings));
        final Pathway[] found = {null};
        readShard(doc, shard, db -> {
            withKey(name, keyBuffer -> db.getPathways().get(keyBuffer, valueBuffer -> {
                if (valueBuffer != null) {
                    found[0] = pathwaySerde.readPathway(valueBuffer);
                }
                return null;
            }));
            return null;
        });
        return Optional.ofNullable(found[0]);
    }

    private static void withKey(final String name, final Consumer<ByteBuffer> consumer) {
        final byte[] keyBytes = name.getBytes(StandardCharsets.UTF_8);
        final ByteBuffer keyBuffer = ByteBuffer.allocateDirect(keyBytes.length);
        keyBuffer.put(keyBytes).flip();
        consumer.accept(keyBuffer);
    }

    // A shard that cannot be read must not fail the whole search: the rest of the model is still worth
    // showing, and a shard nothing has applied to yet has no model at all, which is normal.
    private static int shardCountOf(final SharedFileStoreSettings settings) {
        return Math.max(1, settings.getShardCount());
    }

    private <R> void readShard(final PathwaysDoc doc,
                               final int shard,
                               final Function<PathwaysDb, R> work) {
        try {
            shardStore.readShard(doc, shard, work);
        } catch (final IOException | RuntimeException e) {
            LOGGER.error(() -> "Could not read shard " + shard + " of " + doc.getName()
                               + ", so its pathways are missing from this result: " + e.getMessage(), e);
        }
    }
}
