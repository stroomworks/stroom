/*
 * Copyright 2016-2025 Crown Copyright
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

package stroom.query.language.functions;

import stroom.query.language.functions.ref.StoredValues;
import stroom.query.language.functions.ref.ValueReferenceIndex;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Regression test for the {@code getState()} StroomQL expression function.
 * <p>
 * A pre-production review found that {@code StateProviderImpl.getState} was changed to propagate
 * exceptions (e.g. a permission deny, or a {@link NumberFormatException} from a key shape mismatched
 * to the store type) instead of returning a {@link ValErr}. {@link GetState}'s runtime path
 * ({@code Gen.eval}) already caught such exceptions and converted them to a {@link ValErr}, but its
 * set-time path ({@code setParams}, the all-literal-args case that builds a {@link StaticValueGen})
 * did not, so a throwing {@link StateFetcher} escaped uncaught at query setup instead of yielding a
 * {@link ValErr} cell. This test proves that {@code setParams} now catches that exception too.
 * </p>
 */
class TestGetState {

    private static final String MAP_NAME = "someMap";
    private static final String KEY_NAME = "someKey";
    private static final long EFFECTIVE_TIME_MS = 1_000L;
    private static final String PERMISSION_DENIED_MESSAGE = "Permission denied to state map 'someMap'";

    /**
     * The regression case: all args are literal so {@link GetState#setParams} takes the
     * {@code StaticValueGen} (set-time) branch. Before the fix, a throwing {@link StateFetcher}
     * would escape {@code setParams} as an uncaught {@link RuntimeException}. After the fix it is
     * caught and turned into a {@link ValErr}.
     */
    @Test
    void testSetTimeLookup_throwingFetcher_yieldsValErr() throws Exception {
        final StateFetcher throwingFetcher = (map, key, effectiveTimeMs) -> {
            throw new RuntimeException(PERMISSION_DENIED_MESSAGE);
        };
        final ExpressionContext expressionContext = ExpressionContext.builder()
                .stateFetcher(throwingFetcher)
                .build();
        final GetState getState = new GetState(expressionContext, GetState.NAME);

        final Param[] params = {
                ValString.create(MAP_NAME),
                ValString.create(KEY_NAME),
                ValLong.create(EFFECTIVE_TIME_MS)
        };

        // Must not throw: the fix wraps the set-time lookup in the same try/catch as Gen.eval.
        Assertions.assertThatCode(() -> getState.setParams(params))
                .doesNotThrowAnyException();

        final Val result = evaluate(getState);

        Assertions.assertThat(result)
                .isInstanceOf(ValErr.class);
        Assertions.assertThat(result.toString())
                .contains(PERMISSION_DENIED_MESSAGE);
    }

    /**
     * Companion case: a non-throwing fetcher still passes its value straight through at set-time -
     * a {@link ValErr} is only produced on failure, not unconditionally.
     */
    @Test
    void testSetTimeLookup_successfulFetcher_passesValueThrough() throws Exception {
        final Val expected = ValString.create("theStateValue");
        final StateFetcher successfulFetcher = (map, key, effectiveTimeMs) -> expected;
        final ExpressionContext expressionContext = ExpressionContext.builder()
                .stateFetcher(successfulFetcher)
                .build();
        final GetState getState = new GetState(expressionContext, GetState.NAME);

        final Param[] params = {
                ValString.create(MAP_NAME),
                ValString.create(KEY_NAME),
                ValLong.create(EFFECTIVE_TIME_MS)
        };
        getState.setParams(params);

        final Val result = evaluate(getState);

        Assertions.assertThat(result)
                .isEqualTo(expected);
    }

    /**
     * Exercises the runtime path (non-literal args, via a {@link Ref}) to show a throwing fetcher
     * also yields a {@link ValErr} there. This path was already correct before the fix (it is
     * {@code Gen.eval}'s existing try/catch) - included for completeness alongside the set-time
     * regression case above.
     */
    @Test
    void testRuntimeLookup_throwingFetcher_yieldsValErr() throws Exception {
        final StateFetcher throwingFetcher = (map, key, effectiveTimeMs) -> {
            throw new RuntimeException(PERMISSION_DENIED_MESSAGE);
        };
        final ExpressionContext expressionContext = ExpressionContext.builder()
                .stateFetcher(throwingFetcher)
                .build();
        final GetState getState = new GetState(expressionContext, GetState.NAME);

        // Use a Ref (not a literal Val) for the map argument so that setParams cannot resolve all
        // params at set-time and instead builds the runtime (Gen) generator.
        final Ref mapRef = new Ref("mapField", 0);
        final Param[] params = {
                mapRef,
                ValString.create(KEY_NAME),
                ValLong.create(EFFECTIVE_TIME_MS)
        };
        getState.setParams(params);

        final ValueReferenceIndex valueReferenceIndex = new ValueReferenceIndex();
        getState.addValueReferences(valueReferenceIndex);
        final StoredValues storedValues = valueReferenceIndex.createStoredValues();
        final Generator generator = getState.createGenerator();

        // Populate the field value that the Ref will pick up.
        generator.set(new Val[]{ValString.create(MAP_NAME)}, storedValues);

        final Val result = generator.eval(storedValues, () -> null);

        Assertions.assertThat(result)
                .isInstanceOf(ValErr.class);
        Assertions.assertThat(result.toString())
                .contains(PERMISSION_DENIED_MESSAGE);
    }

    private Val evaluate(final GetState getState) {
        final ValueReferenceIndex valueReferenceIndex = new ValueReferenceIndex();
        getState.addValueReferences(valueReferenceIndex);
        final StoredValues storedValues = valueReferenceIndex.createStoredValues();
        final Generator generator = getState.createGenerator();
        return generator.eval(storedValues, () -> null);
    }
}
