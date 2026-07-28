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

package stroom.query.planner.port;

import java.util.Objects;
import java.util.OptionalDouble;

/**
 * The cost signal {@link IndexShardStats} answers with - richer than a bare {@link RowCountSignal} because
 * index cost also depends on physical size and indexing throughput (see
 * Task 3.1).
 *
 * @param documentCount never negative; summed across whichever shards matched the query's time range.
 * @param byteSize      never negative; summed {@code IndexShard.getFileSize} across the same shards.
 * @param documentsPerSecond empty when no matching shard has a usable
 *                           {@code IndexShard.getCommitDocumentCountPs} (that method reflects only the most
 *                           recent commit's rate and returns {@code null} when the shard has no commit-duration
 *                           data - this field surfaces that same absence rather than inventing a fallback rate).
 */
public record IndexCostSignal(long documentCount, long byteSize, OptionalDouble documentsPerSecond) {

    public IndexCostSignal {
        if (documentCount < 0) {
            throw new IllegalArgumentException("documentCount must not be negative: " + documentCount);
        }
        if (byteSize < 0) {
            throw new IllegalArgumentException("byteSize must not be negative: " + byteSize);
        }
        Objects.requireNonNull(documentsPerSecond, "documentsPerSecond");
    }
}
