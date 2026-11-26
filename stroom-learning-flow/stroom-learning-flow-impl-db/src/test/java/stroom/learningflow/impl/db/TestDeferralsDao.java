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

import stroom.learningflow.stage.Deferrals.Deferral;
import stroom.learningflow.stage.Deferrals.Stamp;
import stroom.learningflow.stage.Learner;

import com.google.inject.Guice;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/// Tests [DeferralsDao]: there is one row per learner and stream, which is updated rather than duplicated
/// when the stream is deferred again, and deleted when the stream is released.
class TestDeferralsDao {

    private static final String DOC = "doc-" + System.nanoTime();
    private static final String OTHER_DOC = "other-" + System.nanoTime();
    private static final Learner LEARNER = new Learner("pipeline-" + DOC, "learningFlow");
    private static final Learner OTHER_LEARNER = new Learner("pipeline-" + OTHER_DOC, "learningFlow");

    @Inject
    private DeferralsDao deferrals;

    @BeforeEach
    void setUp() {
        Guice.createInjector(new TestModule()).injectMembers(this);
        for (final String doc : List.of(DOC, OTHER_DOC)) {
            deferrals.forDocument(doc).forEach(row -> deferrals.released(row.learner(), row.inputId()));
        }
    }

    @Test
    void aStreamPutBackTwiceIsOneRowThatKeepsWhenItFirstWaited() {
        deferrals.deferred(LEARNER, DOC, 7L, "DOOR-ACCESS", "Raw Events", "frag:1",
                "2 of 6 record(s) fell short of the bar", 2);
        final Deferral first = deferrals.forDocument(DOC).getFirst();
        deferrals.deferred(LEARNER, DOC, 7L, "DOOR-ACCESS", "Raw Events", "frag:2",
                "1 of 6 record(s) fell short of the bar", 1);

        final List<Deferral> rows = deferrals.forDocument(DOC);

        assertThat(rows).hasSize(1);
        final Deferral row = rows.getFirst();
        assertThat(row.attempts()).isEqualTo(2);
        assertThat(row.failedAgainst()).isEqualTo("frag:2");
        assertThat(row.failingRecords()).isEqualTo(1);
        assertThat(row.reason()).isEqualTo("1 of 6 record(s) fell short of the bar");
        assertThat(row.firstDeferredMs()).isEqualTo(first.firstDeferredMs());
    }

    @Test
    void aStreamWithNoBindingFailedAgainstNothing() {
        deferrals.deferred(LEARNER, DOC, 8L, "LIFT", "Raw Events", null, "no binding for this learner", 0);

        final Deferral row = deferrals.forDocument(DOC).getFirst();

        // The pipeline is part of the learner's key, so it is never missing.
        assertThat(row.learner()).isEqualTo(LEARNER);
        assertThat(row.pipeline()).isEqualTo(LEARNER.pipelineUuid());
        assertThat(row.failedAgainst()).isNull();
        assertThat(row.failingRecords()).isZero();
        assertThat(row.attempts()).isEqualTo(1);
    }

    @Test
    void oneStreamsRowComesBackByItsId() {
        deferrals.deferred(LEARNER, DOC, 7L, "DOOR-ACCESS", "Raw Events", "frag:1", "short", 2);
        deferrals.deferred(LEARNER, DOC, 9L, "DOOR-ACCESS", "Raw Events", null, "no binding", 0);
        deferrals.deferred(OTHER_LEARNER, OTHER_DOC, 8L, "LIFT", "Raw Events", null, "no binding", 0);

        assertThat(deferrals.of(LEARNER, 9L)).isPresent().get().extracting(Deferral::reason).isEqualTo("no binding");
        assertThat(deferrals.of(LEARNER, 99L)).isEmpty();
        assertThat(deferrals.of(OTHER_LEARNER, 9L)).describedAs("another learner's stream of that id").isEmpty();
    }

    @Test
    void releasedRowsAreGoneAndOtherDocumentsAreUntouched() {
        deferrals.deferred(LEARNER, DOC, 7L, "DOOR-ACCESS", "Raw Events", "frag:1", "short", 2);
        deferrals.deferred(LEARNER, DOC, 9L, "DOOR-ACCESS", "Raw Events", "frag:1", "short", 3);
        deferrals.deferred(OTHER_LEARNER, OTHER_DOC, 7L, "DOOR-ACCESS", "Raw Events", "frag:9", "short", 1);

        deferrals.released(LEARNER, 7L);

        assertThat(deferrals.forDocument(DOC)).extracting(Deferral::inputId).containsExactly(9L);
        assertThat(deferrals.forDocument(OTHER_DOC)).extracting(Deferral::inputId).containsExactly(7L);
    }

    /// Tests what the learning job sweeps for streams a promotion's wake did not reach: each learner and
    /// the transform its streams failed against, once however many streams there are, including a null
    /// transform for streams waiting for a first learning. The table is shared with whatever else has run
    /// against the database, so only these two learners are checked.
    @Test
    void eachLearnerAndTransformWaitedAgainstIsNamedOnce() {
        deferrals.deferred(LEARNER, DOC, 7L, "DOOR-ACCESS", "Raw Events", "frag:1", "short", 2);
        deferrals.deferred(LEARNER, DOC, 8L, "DOOR-ACCESS", "Raw Events", "frag:1", "short", 1);
        deferrals.deferred(LEARNER, DOC, 9L, "LIFT", "Raw Events", null, "no binding", 0);
        deferrals.deferred(OTHER_LEARNER, OTHER_DOC, 7L, "DOOR-ACCESS", "Raw Events", "frag:9", "short", 1);

        // This includes the row with no stamp. A stream waiting for a first learning failed against no
        // transform, and if it were left out of this sweep it would wait for the full retry delay even
        // after a translation existed to serve it.
        assertThat(deferrals.stampsWaiting())
                .filteredOn(stamp -> stamp.learner().equals(LEARNER) || stamp.learner().equals(OTHER_LEARNER))
                .extracting(Stamp::learner, Stamp::failedAgainst)
                .containsExactlyInAnyOrder(
                        tuple(LEARNER, "frag:1"),
                        tuple(LEARNER, null),
                        tuple(OTHER_LEARNER, "frag:9"));

        deferrals.released(OTHER_LEARNER, 7L);

        assertThat(deferrals.stampsWaiting())
                .filteredOn(stamp -> stamp.learner().equals(LEARNER) || stamp.learner().equals(OTHER_LEARNER))
                .extracting(Stamp::learner, Stamp::failedAgainst)
                .containsExactlyInAnyOrder(tuple(LEARNER, "frag:1"), tuple(LEARNER, null));
    }
}
