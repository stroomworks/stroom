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

import stroom.query.language.functions.ValBoolean;
import stroom.query.language.functions.ValDate;
import stroom.query.language.functions.ValDouble;
import stroom.query.language.functions.ValLong;
import stroom.query.language.functions.ValString;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the encoding a property-index anchor is keyed on.
 *
 * <p>The property under test throughout is <b>reachability</b>: for any value that could be stored and any
 * literal that ought to find it, the literal's seek encodings must include the value's stored encoding. That is
 * the direction that matters - an anchor the seek cannot reach loses rows silently, whereas a seek that reaches
 * too far only costs the predicate a little work. Most tests below are written as that one question.</p>
 */
class TestGraphAnchorEncoding {

    /**
     * The headline case, and the bug this encoding exists to fix. Three spellings of one number must key
     * identically, so a value ingested as {@code 42} is found by a query for {@code 42.0}.
     */
    @Test
    void oneNumberSpelledThreeWays_keysIdentically() {
        final byte[] fromLong = GraphAnchorEncoding.anchorValueBytes(ValLong.create(42L));
        final byte[] fromDouble = GraphAnchorEncoding.anchorValueBytes(ValDouble.create(42.0));

        assertThat(fromDouble).isEqualTo(fromLong);
        assertThat(seeks("42")).contains(fromLong);
        assertThat(seeks("42.0")).contains(fromLong);
        assertThat(seeks("4.2e1")).contains(fromLong);
    }

    /**
     * A value stored as text and the same characters stored as a number must key <b>differently</b>, or the
     * index carries candidates it could have avoided. Both are still reachable from the literal, which is what
     * the second half asserts - the discriminator is an optimisation, not a filter.
     */
    @Test
    void textAndNumber_keyDifferentlyButAreBothReachable() {
        final byte[] asText = GraphAnchorEncoding.anchorValueBytes(ValString.create("42"));
        final byte[] asNumber = GraphAnchorEncoding.anchorValueBytes(ValLong.create(42L));

        assertThat(asText).isNotEqualTo(asNumber);
        assertThat(seeks("42")).contains(asText, asNumber);
    }

    /**
     * A boolean renders as {@code true}, and a query for it carries that text - so it must key as text. Keying
     * it numerically (it does have a {@code toDouble}) would put the anchor somewhere no literal reaches.
     */
    @Test
    void booleanKeysAsText_becauseThatIsWhatALiteralCarries() {
        assertThat(seeks("true")).contains(GraphAnchorEncoding.anchorValueBytes(ValBoolean.create(true)));
        assertThat(seeks("false")).contains(GraphAnchorEncoding.anchorValueBytes(ValBoolean.create(false)));
    }

    /**
     * An instant is a number of milliseconds, so every spelling of one instant keys identically - which is the
     * date half of the same bug. The epoch form is included because a query may perfectly well be written with
     * one.
     */
    @Test
    void oneInstantSpelledSeveralWays_keysIdentically() {
        final Instant instant = Instant.parse("2026-01-01T00:00:00.000Z");
        final byte[] stored = GraphAnchorEncoding.anchorValueBytes(ValDate.create(instant));

        assertThat(seeks("2026-01-01T00:00:00.000Z")).contains(stored);
        assertThat(seeks(String.valueOf(instant.toEpochMilli()))).contains(stored);
    }

    /**
     * A literal with no numeric reading seeks once. Worth pinning because the alternative - seeking a number
     * encoding derived from nonsense - would be silently wasteful on the commonest kind of query there is.
     */
    @Test
    void plainStringLiteral_seeksOnlyTheTextEncoding() {
        assertThat(GraphAnchorEncoding.seekValueBytes("d-42")).hasSize(1);
        assertThat(GraphAnchorEncoding.seekValueBytes("")).hasSize(1);
        assertThat(GraphAnchorEncoding.seekValueBytes("42")).as("numeric").hasSize(2);
    }

    /**
     * Negative zero must key as zero. The predicate treats {@code -0.0 == 0.0}, so an anchor keyed on the other
     * one is an anchor the predicate would match and the seek cannot find.
     */
    @Test
    void negativeZero_keysAsZero() {
        assertThat(GraphAnchorEncoding.anchorValueBytes(ValDouble.create(-0.0)))
                .isEqualTo(GraphAnchorEncoding.anchorValueBytes(ValDouble.create(0.0)));
        assertThat(seeks("-0.0")).contains(GraphAnchorEncoding.anchorValueBytes(ValDouble.create(0.0)));
    }

    /**
     * Unsigned byte order must match numeric order across the sign boundary. Nothing reads anchors in order
     * today; this is asserted so that when range anchors arrive they do not require every index to be rewritten.
     */
    @Test
    void byteOrder_matchesNumericOrderIncludingNegatives() {
        final List<Double> ascending = List.of(
                -Double.MAX_VALUE, -1e9, -42.5, -1.0, -Double.MIN_VALUE, 0.0,
                Double.MIN_VALUE, 1.0, 42.5, 1e9, Double.MAX_VALUE);

        final List<byte[]> encoded = new ArrayList<>();
        for (final Double value : ascending) {
            encoded.add(GraphAnchorEncoding.anchorValueBytes(ValDouble.create(value)));
        }

        final List<byte[]> sorted = new ArrayList<>(encoded);
        sorted.sort(unsignedLexicographic());

        assertThat(sorted).containsExactlyElementsOf(encoded);
    }

    /**
     * Longs beyond a double's exact range share an encoding. That is a collision rather than a loss, and the
     * distinction is the whole argument for the design: each value still <b>reaches its own anchor</b>, and the
     * predicate separates the neighbours that came with it.
     */
    @Test
    void hugeLongsCollideButRemainReachable() {
        final long value = (1L << 53) + 1;
        final byte[] stored = GraphAnchorEncoding.anchorValueBytes(ValLong.create(value));

        assertThat(seeks(String.valueOf(value))).as("still finds its own anchor").contains(stored);
        assertThat(GraphAnchorEncoding.anchorValueBytes(ValLong.create(value - 1)))
                .as("shares with its neighbour").isEqualTo(stored);
    }

    /**
     * Every encoding stays inside the property index's 32-byte inline tier, so numbers never pay for a lookup
     * entry. Cheap to assert and easy to break by widening the tag or the value.
     */
    @Test
    void numberEncodings_fitTheInlineTier() {
        assertThat(GraphAnchorEncoding.anchorValueBytes(ValDouble.create(Double.MAX_VALUE))).hasSize(9);
        assertThat(GraphAnchorEncoding.anchorValueBytes(ValDate.create(Instant.now()))).hasSize(9);
    }

    private static List<byte[]> seeks(final String literal) {
        return GraphAnchorEncoding.seekValueBytes(literal);
    }

    /** LMDB compares keys as unsigned bytes, so the ordering assertion has to as well. */
    private static Comparator<byte[]> unsignedLexicographic() {
        return Arrays::compareUnsigned;
    }
}
