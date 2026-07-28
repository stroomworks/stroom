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

import java.util.Optional;

/**
 * The graph datasource row-count cost signal (implementation plan Task P5.1, {@code
 * }) - mirrors {@link StateStoreStats} exactly in shape, since a
 * graph's anchor/adjacency access is also key-addressed point/prefix-lookup shaped, not a Lucene-style
 * partitioned scan.
 *
 * <p>The real adapter lives in {@code stroom-graphdb-impl} (as {@code GraphStoreStatsAdapter}), for the same
 * layering reason {@link StateStoreStats}'s own Javadoc gives for its adapter living in {@code
 * stroom-planb-impl}: the module owning the real store already depends on {@code stroom-query-planner}, not the
 * other way round.</p>
 */
public interface GraphStoreStats {

    /**
     * @param graphName never null; the graph's name as it would appear in a {@code MATCH}'s datasource.
     * @return empty if {@code graphName} is not a known graph.
     */
    Optional<RowCountSignal> estimate(String graphName);
}
