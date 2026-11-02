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

import stroom.shapeshifter.ai.stage.CorpusIndex.Row;
import stroom.shapeshifter.ai.stage.RegressionSet.Kind;

import com.google.inject.Guice;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/// The corpus index of design 04 §4.1: rows in the order accepted, the cap dropping the oldest, and the
/// streams nothing names any more handed back for deletion.
class TestCorpusIndexDao {

    private static final String DOC = "doc-" + System.nanoTime();
    private static final String BINDING = "rule-" + System.nanoTime();
    private static final String DRAFT = "draft-" + System.nanoTime();

    @Inject
    private CorpusIndexDao index;

    @BeforeEach
    void setUp() {
        Guice.createInjector(new TestModule()).injectMembers(this);
        index.discard(BINDING);
        index.discard(DRAFT);
    }

    @Test
    void rowsComeBackInTheOrderTheyWereAccepted() {
        index.add(List.of(row(BINDING, 10L, 0, Kind.TRANSLATION), row(BINDING, 10L, 1, Kind.UNKNOWN)));
        index.add(List.of(row(BINDING, 11L, 0, Kind.NONE)));

        assertThat(index.rows(BINDING))
                .extracting(Row::metaId, Row::segment, Row::kind)
                .containsExactly(
                        tuple(10L, 0, Kind.TRANSLATION),
                        tuple(10L, 1, Kind.UNKNOWN),
                        tuple(11L, 0, Kind.NONE));
        assertThat(index.rows(BINDING).get(0).attemptId()).isEqualTo(7L);
    }

    @Test
    void theCapDropsTheOldestAndNamesTheStreamsNothingNamesAnyMore() {
        index.add(List.of(row(BINDING, 10L, 0, Kind.TRANSLATION), row(BINDING, 10L, 1, Kind.TRANSLATION)));
        index.add(List.of(row(BINDING, 11L, 0, Kind.TRANSLATION), row(BINDING, 11L, 1, Kind.TRANSLATION)));

        assertThat(index.trim(BINDING, 3))
                .describedAs("one row of stream 10 remains, so stream 10 is still named")
                .isEmpty();
        assertThat(index.rows(BINDING)).hasSize(3);
        assertThat(index.trim(BINDING, 2))
                .describedAs("now nothing names stream 10")
                .containsExactly(10L);
        assertThat(index.trim(BINDING, 2)).describedAs("within the cap, nothing is dropped").isEmpty();
    }

    @Test
    void discardHandsBackEveryStreamOfTheBindingAndNoOtherBindings() {
        index.add(List.of(row(BINDING, 10L, 0, Kind.TRANSLATION)));
        index.add(List.of(row(DRAFT, 12L, 0, Kind.TRANSLATION)));

        assertThat(index.discard(BINDING)).containsExactly(10L);
        assertThat(index.rows(BINDING)).isEmpty();
        assertThat(index.rows(DRAFT)).hasSize(1);
    }

    @Test
    void moveReKeysADraftsRowsAfterTheIncumbents() {
        index.add(List.of(row(BINDING, 10L, 0, Kind.TRANSLATION)));
        index.add(List.of(row(DRAFT, 12L, 0, Kind.UNKNOWN)));

        index.move(DRAFT, BINDING);

        assertThat(index.rows(DRAFT)).isEmpty();
        assertThat(index.rows(BINDING)).extracting(Row::metaId).containsExactly(10L, 12L);
    }

    private static Row row(final String binding, final long metaId, final int segment, final Kind kind) {
        return new Row(DOC, binding, metaId, segment, kind, 0.9, 7L, 1_000L);
    }
}
