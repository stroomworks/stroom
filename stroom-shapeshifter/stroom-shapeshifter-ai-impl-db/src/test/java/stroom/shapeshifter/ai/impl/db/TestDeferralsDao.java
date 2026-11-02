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

package stroom.shapeshifter.ai.impl.db;

import stroom.shapeshifter.ai.stage.Deferrals.Deferral;
import stroom.shapeshifter.ai.stage.Deferrals.Stamp;
import stroom.shapeshifter.ai.stage.Learner;

import com.google.inject.Guice;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// The deferral rows of design 04 §6: one per stream and document, brought up to date rather than
/// duplicated when the stream is put back again, and gone when it is released.
class TestDeferralsDao {

    private static final String DOC = "doc-" + System.nanoTime();
    private static final String OTHER_DOC = "other-" + System.nanoTime();
    private static final Learner LEARNER = new Learner("pipeline-" + DOC, "shapeshifterAi");
    private static final Learner OTHER_LEARNER = new Learner("pipeline-" + OTHER_DOC, "shapeshifterAi");

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

        // The pipeline is the learner's, so it is never missing (A114).
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

    /// What the learning job sweeps (A130): each learner and the transform its streams wait against, once
    /// however many streams wait on it; a stream that waits on no transform is not named. The table is
    /// shared with whatever else has run against the database, so only these two learners are looked at.
    @Test
    void eachLearnerAndTransformWaitedAgainstIsNamedOnce() {
        deferrals.deferred(LEARNER, DOC, 7L, "DOOR-ACCESS", "Raw Events", "frag:1", "short", 2);
        deferrals.deferred(LEARNER, DOC, 8L, "DOOR-ACCESS", "Raw Events", "frag:1", "short", 1);
        deferrals.deferred(LEARNER, DOC, 9L, "LIFT", "Raw Events", null, "no binding", 0);
        deferrals.deferred(OTHER_LEARNER, OTHER_DOC, 7L, "DOOR-ACCESS", "Raw Events", "frag:9", "short", 1);

        assertThat(deferrals.stampsWaiting())
                .filteredOn(stamp -> stamp.learner().equals(LEARNER) || stamp.learner().equals(OTHER_LEARNER))
                .containsExactlyInAnyOrder(new Stamp(LEARNER, "frag:1"), new Stamp(OTHER_LEARNER, "frag:9"));

        deferrals.released(OTHER_LEARNER, 7L);

        assertThat(deferrals.stampsWaiting())
                .filteredOn(stamp -> stamp.learner().equals(LEARNER) || stamp.learner().equals(OTHER_LEARNER))
                .containsExactly(new Stamp(LEARNER, "frag:1"));
    }
}
