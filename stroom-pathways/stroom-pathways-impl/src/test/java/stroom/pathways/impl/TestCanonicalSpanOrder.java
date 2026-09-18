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

import stroom.pathways.shared.otel.trace.NanoDuration;
import stroom.pathways.shared.otel.trace.Span;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whether the order handed to the key builder says what route a trace took rather than what the
 * scheduler did.
 *
 * <p>Times are written in microseconds and multiplied up, because a microsecond is the scale the spans
 * this exists for actually run at.
 */
class TestCanonicalSpanOrder {

    private static final long US = 1_000L;

    @Test
    void concurrentChildrenComeBackInTheSameOrderWhicheverStartedFirst() {
        // Two spans that overlap, seen both ways round.
        final List<Span> aFirst = List.of(
                span("a", 100, 900),
                span("b", 200, 800));
        final List<Span> bFirst = List.of(
                span("b", 100, 900),
                span("a", 200, 800));

        assertThat(names(order().sort(aFirst)))
                .as("overlapping spans are named in one order regardless of which started first")
                .isEqualTo(names(order().sort(bFirst)))
                .containsExactly("a", "b");
    }

    @Test
    void childrenThatRanOneAfterAnotherKeepTheirOrder() {
        // Well clear of the tolerance, so these are two separate steps rather than one moment.
        final List<Span> spans = List.of(
                span("save", 10_000, 11_000),
                span("validate", 100, 900));

        assertThat(names(order().sort(spans)))
                .as("a real sequence is behaviour, not noise, so it is kept")
                .containsExactly("validate", "save");
    }

    /**
     * The case this class exists for: short calls made from several threads at once land close together
     * without necessarily touching, and arrive in a different order every run.
     */
    @Test
    void aBurstFromSeveralThreadsSettlesOnOneOrderHoweverItIsShuffled() {
        final List<Span> burst = new ArrayList<>(List.of(
                span("Ping", 0, 400),
                span("Commit", 460, 800),
                span("Prepare statement", 500, 900),
                span("Ping", 700, 1_100),
                span("Set autocommit", 950, 1_400)));

        final List<String> first = names(order().sort(burst));
        final Random random = new Random(1);
        for (int i = 0; i < 20; i++) {
            Collections.shuffle(burst, random);
            assertThat(names(order().sort(burst)))
                    .as("shuffle %d produced a different key", i)
                    .isEqualTo(first);
        }

        assertThat(first).containsExactly(
                "Commit", "Ping", "Ping", "Prepare statement", "Set autocommit");
    }

    @Test
    void separateBurstsStayInTimeOrderAndAreOrderedByNameWithin() {
        final List<Span> spans = List.of(
                // Second burst, deliberately listed first.
                span("x", 50_000, 50_400),
                span("b", 50_100, 50_500),
                // First burst.
                span("z", 100, 500),
                span("a", 200, 600));

        assertThat(names(order().sort(spans)))
                .as("bursts are ordered by when they happened, names only within a burst")
                .containsExactly("a", "z", "b", "x");
    }

    @Test
    void aChainOfOverlapsIsOneBurstEvenWhereTheEndsDoNotTouch() {
        // c does not reach a, but b bridges them, so all three are one moment.
        final List<Span> spans = List.of(
                span("a", 0, 1_000),
                span("b", 900, 2_000),
                span("c", 1_900, 3_000));

        assertThat(names(order().sort(spans))).containsExactly("a", "b", "c");
        assertThat(names(order().sort(List.of(
                span("c", 0, 1_000),
                span("b", 900, 2_000),
                span("a", 1_900, 3_000)))))
                .as("the same three spans, names dealt out differently, still form one burst")
                .containsExactly("a", "b", "c");
    }

    @Test
    void theToleranceGathersSpansThatMissEachOtherNarrowly() {
        // A 20us gap: two threads, not one sequence. Inside the default 1ms tolerance.
        final List<Span> spans = List.of(
                span("b", 0, 400),
                span("a", 420, 800));

        assertThat(names(order().sort(spans)))
                .as("a gap this small is the scheduler, not a step")
                .containsExactly("a", "b");

        assertThat(names(new CanonicalSpanOrder(NanoDuration.ofNanos(0)).sort(spans)))
                .as("with no tolerance only real overlap groups, so the gap separates them")
                .containsExactly("b", "a");
    }

    @Test
    void emptyAndSingleInputsAreReturnedAsTheyAre() {
        assertThat(order().sort(List.of())).isEmpty();
        assertThat(order().sort(null)).isEmpty();
        assertThat(names(order().sort(List.of(span("only", 0, 100))))).containsExactly("only");
    }

    private static CanonicalSpanOrder order() {
        return new CanonicalSpanOrder((stroom.util.shared.time.SimpleDuration) null);
    }

    private static List<String> names(final List<Span> spans) {
        return spans.stream().map(Span::getName).toList();
    }

    private static Span span(final String name, final long startMicros, final long endMicros) {
        return Span.builder()
                .name(name)
                .startTimeUnixNano(Long.toString(startMicros * US))
                .endTimeUnixNano(Long.toString(endMicros * US))
                .build();
    }
}
