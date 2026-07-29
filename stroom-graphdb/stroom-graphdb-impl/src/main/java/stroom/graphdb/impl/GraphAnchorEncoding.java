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

import stroom.query.language.functions.DateUtil;
import stroom.query.language.functions.Val;
import stroom.query.language.functions.ValNumber;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/**
 * The single definition of how a property value becomes the bytes a {@link GraphPropertyIndex} anchor is keyed
 * on, for both the side that writes anchors and the side that seeks them.
 *
 * <h2>The rule everything here follows</h2>
 *
 * <p><b>The index must agree with the predicate, and where it cannot, it must err towards matching too much.</b>
 * An anchor is only a <em>candidate</em> filter - {@code GraphTraversalEngine} re-checks every candidate against
 * the node's real decoded properties before it becomes a row. So an anchor that returns too much costs a little
 * wasted work, while one that returns too little silently loses rows. Every choice below spends the cheap
 * failure to avoid the expensive one.</p>
 *
 * <h2>Two encodings, one discriminator byte</h2>
 *
 * <p>A value is keyed either as text or as a number:</p>
 *
 * <ul>
 *   <li><b>{@link #TAG_TEXT}</b> - the value's rendered form in UTF-8. Strings, booleans, and anything else
 *       without a numeric reading.</li>
 *   <li><b>{@link #TAG_NUMBER}</b> - eight bytes derived from the value as a {@code double}. Every
 *       {@link ValNumber}, which includes dates (an instant is a number of milliseconds).</li>
 * </ul>
 *
 * <p>Numbers are keyed on their <b>value</b> rather than their text, which is the whole point: {@code 42},
 * {@code 42.0} and {@code 4.2e1} are one number with three spellings, and a store that keyed them separately
 * would answer {@code WHERE n.score = 42.0} with nothing for a value ingested as {@code 42}. Dates are the same
 * problem in a different costume - {@code 2026-01-01T00:00:00.000Z} and its equivalents are one instant.</p>
 *
 * <p>The discriminator keeps the string {@code "42"} from colliding with the number 42. A collision would in
 * fact be harmless, because the predicate re-checks; the byte is here so the index does not carry candidates it
 * can cheaply avoid.</p>
 *
 * <h2>Why a double, given longs are wider</h2>
 *
 * <p>Encoding every number through {@code double} is what makes {@code ValLong(42)} and {@code ValDouble(42.0)}
 * produce identical bytes without anyone having to know which is stored. It costs precision above
 * 2<sup>53</sup>, where distinct longs share a {@code double} - but that is a <b>collision, not a loss</b>. Such
 * a long still finds its own anchor; it merely shares the bucket with its neighbours, and the predicate
 * separates them. Erring towards matching too much, exactly as the rule requires.</p>
 *
 * <h2>The encoding is order-preserving</h2>
 *
 * <p>Nothing reads anchors in order yet. The transform below (flip the sign bit of a positive, invert a
 * negative entirely) costs nothing at write time and makes unsigned byte order match numeric order, which is the
 * difference between range anchors being a later feature and a later rewrite of every index. Do not replace it
 * with a plain {@code doubleToLongBits} for tidiness.</p>
 *
 * <h2>Seeking, when the stored type is unknown</h2>
 *
 * <p>A query knows the literal it was given and nothing about what is stored: {@code WHERE n.score = 42} does
 * not say whether {@code score} holds a number or the text {@code "42"}. Rather than maintain a per-property
 * type registry - state to keep correct, and wrong the moment a property holds both - {@link #seekValueBytes}
 * returns <b>every encoding the literal could plausibly have</b>, and the caller seeks each and unions the
 * results. Two seeks at most, on the cheapest tier, and the predicate settles it.</p>
 */
public final class GraphAnchorEncoding {

    /** Rendered text, UTF-8. The encoding every value used before numbers were keyed by value. */
    static final byte TAG_TEXT = 0;

    /** A number, as the eight bytes {@link #numberBytes} produces. Includes dates. */
    static final byte TAG_NUMBER = 1;

    private static final int NUMBER_WIDTH = 8;

    private GraphAnchorEncoding() {
        // Static utility.
    }

    /**
     * Encodes a stored property value as the anchor key bytes to write.
     *
     * <p><b>Preconditions:</b> {@code value} is not null.
     * <b>Postconditions:</b> returns one encoding - the canonical one for this value's type. A number returns
     * {@link #TAG_NUMBER} bytes; anything else returns {@link #TAG_TEXT} bytes.
     * <b>Null status:</b> {@code value} is not nullable; the return value is never null.
     *
     * @param value the property's decoded value.
     * @return the bytes to key its anchor on.
     */
    public static byte[] anchorValueBytes(final Val value) {
        Objects.requireNonNull(value, "value must not be null");
        // Dispatched on ValNumber rather than on Type, because that interface is exactly the set of values with
        // a meaningful numeric reading - notably excluding ValBoolean, which has a toDouble() but whose rendered
        // form ("true") is what a query literal would carry.
        if (value instanceof ValNumber) {
            final Double asDouble = value.toDouble();
            if (asDouble != null) {
                return numberBytes(asDouble);
            }
        }
        return textBytes(value.toString());
    }

    /**
     * Every encoding a query literal could plausibly match, to be seeked in turn and unioned.
     *
     * <p>Always includes the text encoding, because a property may hold {@code "42"} as a string. Adds the
     * number encoding when the literal reads as a number or as a timestamp. So {@code 'abc'} yields one
     * encoding, {@code 42} yields two, and no lookup of what is actually stored is needed.</p>
     *
     * <p><b>Preconditions:</b> {@code literal} is not null.
     * <b>Postconditions:</b> returns one or two encodings, text first. Never empty, so a caller always has
     * something to seek.
     * <b>Null status:</b> {@code literal} is not nullable; the return value is never null.
     *
     * @param literal the query literal's own text, as written.
     * @return the encodings to seek.
     */
    public static List<byte[]> seekValueBytes(final String literal) {
        Objects.requireNonNull(literal, "literal must not be null");
        final Double number = asNumber(literal);
        return number == null
                ? List.of(textBytes(literal))
                : List.of(textBytes(literal), numberBytes(number));
    }

    /**
     * The numeric reading of a literal, or null if it has none.
     *
     * <p>Timestamps are read here too, and deliberately fold into the same numeric space as ordinary numbers
     * rather than getting a tag of their own. A date and a number that happen to share an epoch value would then
     * collide - which is both vanishingly unlikely and harmless, and it keeps the seek at two encodings rather
     * than three.</p>
     */
    private static Double asNumber(final String literal) {
        final String trimmed = literal.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            // Double.parseDouble accepts forms no query author would write as a number - "Infinity", "0x1p3",
            // and a trailing "d" or "f" - but accepting them only widens the seek, and the predicate rejects
            // whatever does not really match.
            return Double.parseDouble(trimmed);
        } catch (final NumberFormatException e) {
            // Not a number; it may still be a timestamp.
        }
        try {
            return (double) DateUtil.parseNormalDateTimeString(trimmed);
        } catch (final RuntimeException e) {
            return null;
        }
    }

    private static byte[] textBytes(final String text) {
        final byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
        final byte[] encoded = new byte[utf8.length + 1];
        encoded[0] = TAG_TEXT;
        System.arraycopy(utf8, 0, encoded, 1, utf8.length);
        return encoded;
    }

    /**
     * Eight big-endian bytes whose unsigned order matches numeric order.
     *
     * <p>IEEE-754 bits are almost sortable already: positives ascend correctly but sort below negatives (their
     * sign bit is clear), and negatives descend. Flipping a positive's sign bit lifts it above every negative;
     * inverting a negative entirely both clears its sign bit and reverses its order. Together they give one
     * ascending sequence.</p>
     *
     * <p>Negative zero is normalised, because {@code -0.0 == 0.0} to the predicate but has different bits - and
     * an anchor the predicate would match but the seek cannot find is exactly the silent row loss this class
     * exists to prevent.</p>
     */
    private static byte[] numberBytes(final double value) {
        final double normalised = value == 0.0
                ? 0.0
                : value;
        final long bits = Double.doubleToLongBits(normalised);
        final long ordered = bits >= 0
                ? bits ^ Long.MIN_VALUE
                : ~bits;

        final byte[] encoded = new byte[NUMBER_WIDTH + 1];
        encoded[0] = TAG_NUMBER;
        for (int i = 0; i < NUMBER_WIDTH; i++) {
            encoded[NUMBER_WIDTH - i] = (byte) (ordered >>> (i * Byte.SIZE));
        }
        return encoded;
    }
}
