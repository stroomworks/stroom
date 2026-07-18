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

package stroom.query.language;

import stroom.docref.DocRef;
import stroom.query.api.SearchRequest;
import stroom.query.common.v2.QueryOptimiserConfig;
import stroom.query.language.functions.ExpressionContext;

import org.junit.jupiter.api.Test;

import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Proves {@link DispatchingQueryCompiler} routes every call to {@link LegacyQueryCompiler} while the flag is off
 * (the default) and to {@link OptimisingQueryCompiler} while it is on, re-reading the flag on every call rather
 * than caching it - see docs/query-optimiser-implementation-plan.md, Task 1.5.
 */
class TestDispatchingQueryCompiler {

    private static final String QUERY = "from X select a";

    private final LegacyQueryCompiler legacy = mock(LegacyQueryCompiler.class);
    private final OptimisingQueryCompiler optimising = mock(OptimisingQueryCompiler.class);
    private final SearchRequest seed = mock(SearchRequest.class);
    private final SearchRequest legacyResult = mock(SearchRequest.class);
    private final SearchRequest optimisingResult = mock(SearchRequest.class);
    private final ExpressionContext expressionContext = mock(ExpressionContext.class);

    private DispatchingQueryCompiler dispatcher(final boolean enabled) {
        return new DispatchingQueryCompiler(legacy, optimising, () -> new QueryOptimiserConfig(enabled));
    }

    @Test
    void flagOff_delegatesToLegacy() {
        when(legacy.create(QUERY, seed, expressionContext)).thenReturn(legacyResult);

        final SearchRequest result = dispatcher(false).create(QUERY, seed, expressionContext);

        assertThat(result).isSameAs(legacyResult);
        verify(optimising, never()).create(any(), any(), any());
    }

    @Test
    void flagOn_delegatesToOptimising() {
        when(optimising.create(QUERY, seed, expressionContext)).thenReturn(optimisingResult);

        final SearchRequest result = dispatcher(true).create(QUERY, seed, expressionContext);

        assertThat(result).isSameAs(optimisingResult);
        verify(legacy, never()).create(any(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void extractDataSourceOnly_respectsFlagToo() {
        final Consumer<DocRef> consumer = mock(Consumer.class);

        dispatcher(true).extractDataSourceOnly(QUERY, consumer);
        verify(optimising).extractDataSourceOnly(QUERY, consumer);
        verify(legacy, never()).extractDataSourceOnly(any(), any());

        dispatcher(false).extractDataSourceOnly(QUERY, consumer);
        verify(legacy).extractDataSourceOnly(QUERY, consumer);
    }

    @Test
    void flagIsReReadPerCall_notCachedAtConstruction() {
        final boolean[] enabled = {false};
        final DispatchingQueryCompiler dispatcher =
                new DispatchingQueryCompiler(legacy, optimising, () -> new QueryOptimiserConfig(enabled[0]));

        when(legacy.create(QUERY, seed, expressionContext)).thenReturn(legacyResult);
        when(optimising.create(QUERY, seed, expressionContext)).thenReturn(optimisingResult);

        assertThat(dispatcher.create(QUERY, seed, expressionContext)).isSameAs(legacyResult);

        enabled[0] = true;
        assertThat(dispatcher.create(QUERY, seed, expressionContext)).isSameAs(optimisingResult);
    }

    @Test
    void constructorRejectsNullArguments() {
        assertThatThrownBy(() ->
                new DispatchingQueryCompiler(null, optimising, () -> new QueryOptimiserConfig(false)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() ->
                new DispatchingQueryCompiler(legacy, null, () -> new QueryOptimiserConfig(false)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new DispatchingQueryCompiler(legacy, optimising, null))
                .isInstanceOf(NullPointerException.class);
    }
}
