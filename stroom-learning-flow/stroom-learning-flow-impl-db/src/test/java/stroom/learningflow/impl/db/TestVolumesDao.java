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

import stroom.docref.DocRef;
import stroom.learningflow.stage.Produced;

import com.google.inject.Guice;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// Tests [VolumesDao], which supplies the volume chart on a Smart Pipeline's Quality Assurance tab: streams
/// and records per day, by binding. A stream whose records were not counted counts as a stream with no
/// records, and a replay that cannot count keeps the counts already recorded.
class TestVolumesDao {

    private static final long DAY_MS = 24L * 60 * 60 * 1000;
    private static final String DOC = "doc-" + System.nanoTime();
    private static final String BINDING = "binding-" + System.nanoTime();
    private static final String OTHER = "other-" + System.nanoTime();
    private static final DocRef FRAGMENT = new DocRef("Pipeline", "f-1", "Firewall pipeline learned");

    @Inject
    private OutputsDao outputs;
    @Inject
    private VolumesDao volumes;

    @BeforeEach
    void setUp() {
        Guice.createInjector(new TestModule()).injectMembers(this);
    }

    @Test
    void eachDayCountsTheBindingsStreamsAndTheirRecords() {
        final long input = System.nanoTime();
        outputs.emitted(input, "p-1", produced(BINDING), 10);
        outputs.emitted(input + 1, "p-1", produced(BINDING), 5);
        outputs.emitted(input + 2, "p-1", produced(BINDING));
        outputs.emitted(input + 3, "p-1", produced(OTHER), 100);

        final long today = Math.floorDiv(System.currentTimeMillis(), DAY_MS) * DAY_MS;
        assertThat(volumes.perDay(List.of(BINDING), today - DAY_MS))
                .singleElement()
                .satisfies(day -> {
                    assertThat(day.getDayMs()).isEqualTo(today);
                    assertThat(day.getStreams()).describedAs("the stream nobody counted counts").isEqualTo(3);
                    assertThat(day.getRecords()).describedAs("and adds no records").isEqualTo(15);
                });
    }

    @Test
    void aReplayThatCannotCountKeepsWhatWasCounted() {
        final String replayed = "replayed-" + System.nanoTime();
        final long input = System.nanoTime();
        outputs.emitted(input, "p-1", produced(replayed), 12);
        outputs.emitted(input, "p-1", produced(replayed));

        assertThat(volumes.perDay(List.of(replayed), 0L))
                .singleElement()
                .satisfies(day -> {
                    assertThat(day.getStreams()).describedAs("one stream, however often replayed").isEqualTo(1);
                    assertThat(day.getRecords()).isEqualTo(12);
                });
    }

    @Test
    void noBindingsOrNothingSinceIsNoDays() {
        assertThat(volumes.perDay(List.of(), 0L)).isEmpty();
        assertThat(volumes.perDay(List.of("nothing-" + System.nanoTime()), 0L)).isEmpty();
        assertThat(volumes.perDay(List.of(BINDING), System.currentTimeMillis() + DAY_MS)).isEmpty();
    }

    private static Produced produced(final String binding) {
        return new Produced(DOC, binding, FRAGMENT, null, 0.9);
    }
}
