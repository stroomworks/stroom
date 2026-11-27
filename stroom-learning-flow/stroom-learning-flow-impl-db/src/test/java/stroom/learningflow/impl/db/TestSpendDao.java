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

package stroom.learningflow.impl.db;

import stroom.learningflow.stage.Spend.Spent;

import com.google.inject.Guice;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/// Tests [SpendDao] against a real database.
class TestSpendDao {

    private static final long WINDOW_MS = 600_000L;
    private static final int THREADS = 8;
    private static final int ROUNDS = 10;

    @Inject
    private SpendDao spend;

    @BeforeEach
    void setUp() {
        Guice.createInjector(new TestModule()).injectMembers(this);
    }

    /// Many nodes record spend at once for a document with no spend row yet. If the row were created with
    /// `INSERT IGNORE`, which takes a shared lock on a duplicate, the following `FOR UPDATE` would deadlock
    /// and the database would abort one transaction. Created under the exclusive lock, they queue and every
    /// call is counted.
    @Test
    void theFirstSpendOfANewDocumentRecordedAtOnceAllCounts() throws Exception {
        final ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            for (int round = 0; round < ROUNDS; round++) {
                final String docUuid = "spend-doc-" + System.nanoTime();
                final CountDownLatch start = new CountDownLatch(1);
                final List<Future<Spent>> recorded = new ArrayList<>();
                for (int i = 0; i < THREADS; i++) {
                    recorded.add(pool.submit(() -> {
                        start.await();
                        return spend.record(docUuid, 10L, 1, WINDOW_MS);
                    }));
                }
                start.countDown();
                for (final Future<Spent> each : recorded) {
                    each.get(30, TimeUnit.SECONDS);
                }

                final Spent spent = spend.spent(docUuid, WINDOW_MS);
                assertThat(spent.tokens())
                        .describedAs("round " + round + ": every thread's tokens counted")
                        .isEqualTo(10L * THREADS);
                assertThat(spent.calls())
                        .describedAs("round " + round + ": every thread's call counted")
                        .isEqualTo(THREADS);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void spendIsAddedToTheDocumentsOwnRow() {
        final String docUuid = "spend-doc-" + System.nanoTime();

        final Spent first = spend.record(docUuid, 5L, 1, WINDOW_MS);
        final Spent second = spend.record(docUuid, 7L, 2, WINDOW_MS);

        assertThat(first.tokens()).isEqualTo(5L);
        assertThat(second.tokens()).isEqualTo(12L);
        assertThat(second.calls()).isEqualTo(3);
        assertThat(second.windowStartMs()).isEqualTo(first.windowStartMs());
        assertThat(spend.spent("spend-other-" + System.nanoTime(), WINDOW_MS).tokens()).isZero();
    }
}
