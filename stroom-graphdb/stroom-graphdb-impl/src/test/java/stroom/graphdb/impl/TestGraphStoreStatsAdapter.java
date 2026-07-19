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

import stroom.docstore.api.DocumentNotFoundException;
import stroom.graphdb.shared.GraphDbDoc;
import stroom.query.language.functions.ValString;
import stroom.query.planner.port.RowCountSignal;
import stroom.util.shared.PermissionException;
import stroom.util.shared.UserRef;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Task P5.1: {@link GraphStoreStatsAdapter} against a real {@link GraphStores} fixture, with
 * {@link GraphDbDocCache}/{@link GraphStoreManager} faked exactly as {@code TestGraphSearchProvider} does (to
 * avoid standing up the real doc store/cache stack for what is otherwise a genuine end-to-end count).
 */
class TestGraphStoreStatsAdapter {

    private static final GraphDbDoc DOC = GraphDbDoc.builder().uuid("graph-uuid").name("TestGraph").build();

    @Test
    void estimate_returnsTheRealNodeCountForAKnownGraph(@TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root, DOC)) {
            stores.write(writer -> {
                stores.getNodes().insert(writer, 1L, Instant.parse("2026-01-01T00:00:00Z"),
                        List.of(), Map.of("id", ValString.create("n1")));
                stores.getNodes().insert(writer, 2L, Instant.parse("2026-01-01T00:00:00Z"),
                        List.of(), Map.of("id", ValString.create("n2")));
                return null;
            });

            final GraphDbDocCache graphDbDocCache = mock(GraphDbDocCache.class);
            when(graphDbDocCache.get("TestGraph")).thenReturn(DOC);
            final GraphStoreManager graphStoreManager = mock(GraphStoreManager.class);
            when(graphStoreManager.getOrOpen(DOC)).thenReturn(stores);

            final GraphStoreStatsAdapter adapter = new GraphStoreStatsAdapter(graphDbDocCache, graphStoreManager);

            final Optional<RowCountSignal> signal = adapter.estimate("TestGraph");
            assertThat(signal).contains(new RowCountSignal(2));
        }
    }

    @Test
    void estimate_returnsEmptyForAnUnknownGraphName() {
        // Code-review fix: GraphDbDocCacheImpl.create() now throws NoSuchElementException/DocumentNotFoundException
        // for its two distinct "not found" cases, not a plain NullPointerException - this test (and the one
        // below it) prove both are still translated to this adapter's "unknown store" empty-Optional contract.
        final GraphDbDocCache graphDbDocCache = mock(GraphDbDocCache.class);
        when(graphDbDocCache.get("Missing")).thenThrow(new NoSuchElementException("no graph db doc"));
        final GraphStoreManager graphStoreManager = mock(GraphStoreManager.class);

        final GraphStoreStatsAdapter adapter = new GraphStoreStatsAdapter(graphDbDocCache, graphStoreManager);

        assertThat(adapter.estimate("Missing")).isEmpty();
    }

    @Test
    void estimate_returnsEmptyWhenTheDocWasFoundByNameButTheStoreNoLongerHasIt() {
        final GraphDbDocCache graphDbDocCache = mock(GraphDbDocCache.class);
        when(graphDbDocCache.get("Missing")).thenThrow(new DocumentNotFoundException(DOC.asDocRef()));
        final GraphStoreManager graphStoreManager = mock(GraphStoreManager.class);

        final GraphStoreStatsAdapter adapter = new GraphStoreStatsAdapter(graphDbDocCache, graphStoreManager);

        assertThat(adapter.estimate("Missing")).isEmpty();
    }

    @Test
    void estimate_propagatesAPermissionExceptionFromTheCache() {
        final GraphDbDocCache graphDbDocCache = mock(GraphDbDocCache.class);
        when(graphDbDocCache.get("Forbidden")).thenThrow(
                new PermissionException(UserRef.builder().uuid("u1").subjectId("user").build(), "no access"));
        final GraphStoreManager graphStoreManager = mock(GraphStoreManager.class);

        final GraphStoreStatsAdapter adapter = new GraphStoreStatsAdapter(graphDbDocCache, graphStoreManager);

        assertThatThrownBy(() -> adapter.estimate("Forbidden")).isInstanceOf(PermissionException.class);
    }
}
