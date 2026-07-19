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

package stroom.graphdb.impl;

/**
 * Task P7.2: thrown by {@link GraphTraversalEngine} when a traversal is aborted by one of its safety ceilings
 * (variable-length hop range, total BFS path-state budget, or wall-clock deadline) rather than let it run to
 * completion or hang the calling thread. A plain {@link RuntimeException} subtype so it flows through
 * {@code GraphSearchProvider.createResultStore}'s existing {@code catch (RuntimeException e)} block exactly like
 * any other search error - surfaced to the user as a result-store error, not a crash.
 */
public class GraphTraversalLimitExceededException extends RuntimeException {

    public GraphTraversalLimitExceededException(final String message) {
        super(message);
    }
}
