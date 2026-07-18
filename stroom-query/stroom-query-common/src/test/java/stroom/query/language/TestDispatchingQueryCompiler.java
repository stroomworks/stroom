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
import stroom.query.api.ExplainPlan;
import stroom.query.api.SearchRequest;
import stroom.query.common.v2.QueryOptimiserConfig;
import stroom.query.common.v2.QueryOptimiserMode;
import stroom.query.language.functions.ExpressionContext;

import org.junit.jupiter.api.Test;

import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Proves {@link DispatchingQueryCompiler} routes every call to {@link LegacyQueryCompiler} while the mode is
 * {@code OFF} (the default) and to {@link OptimisingQueryCompiler} while it is {@code ON}, re-reading the mode on
 * every call rather than caching it (Task 1.5) - and that {@code SHADOW} always serves legacy's result (Task
 * 5.4), unaffected by whatever the optimising compiler does, even when it throws.
 */
class TestDispatchingQueryCompiler {

    private static final String QUERY = "from X select a";

    private final LegacyQueryCompiler legacy = mock(LegacyQueryCompiler.class);
    private final OptimisingQueryCompiler optimising = mock(OptimisingQueryCompiler.class);
    private final SearchRequest seed = mock(SearchRequest.class);
    private final SearchRequest legacyResult = mock(SearchRequest.class);
    private final SearchRequest optimisingResult = mock(SearchRequest.class);
    private final ExpressionContext expressionContext = mock(ExpressionContext.class);

    private DispatchingQueryCompiler dispatcher(final QueryOptimiserMode mode) {
        return new DispatchingQueryCompiler(legacy, optimising, () -> new QueryOptimiserConfig(mode));
    }

    @Test
    void modeOff_delegatesToLegacy() {
        when(legacy.create(QUERY, seed, expressionContext)).thenReturn(legacyResult);

        final SearchRequest result = dispatcher(QueryOptimiserMode.OFF).create(QUERY, seed, expressionContext);

        assertThat(result).isSameAs(legacyResult);
        verify(optimising, never()).create(any(), any(), any());
    }

    @Test
    void modeOn_delegatesToOptimising() {
        when(optimising.create(QUERY, seed, expressionContext)).thenReturn(optimisingResult);

        final SearchRequest result = dispatcher(QueryOptimiserMode.ON).create(QUERY, seed, expressionContext);

        assertThat(result).isSameAs(optimisingResult);
        verify(legacy, never()).create(any(), any(), any());
    }

    @Test
    void modeShadow_alwaysServesLegacyResult_butAlsoCallsOptimising() {
        when(legacy.create(QUERY, seed, expressionContext)).thenReturn(legacyResult);
        when(optimising.create(QUERY, seed, expressionContext)).thenReturn(optimisingResult);

        final SearchRequest result = dispatcher(QueryOptimiserMode.SHADOW).create(QUERY, seed, expressionContext);

        assertThat(result).isSameAs(legacyResult);
        verify(legacy).create(QUERY, seed, expressionContext);
        verify(optimising).create(QUERY, seed, expressionContext);
    }

    @Test
    void modeShadow_optimisingThrowing_stillServesLegacyResult() {
        when(legacy.create(QUERY, seed, expressionContext)).thenReturn(legacyResult);
        when(optimising.create(QUERY, seed, expressionContext)).thenThrow(new RuntimeException("boom"));

        final SearchRequest result = dispatcher(QueryOptimiserMode.SHADOW).create(QUERY, seed, expressionContext);

        assertThat(result).isSameAs(legacyResult);
    }

    @Test
    void modeShadow_alsoAsksForAnEstimate_viaExplain() {
        when(legacy.create(QUERY, seed, expressionContext)).thenReturn(legacyResult);
        when(optimising.create(QUERY, seed, expressionContext)).thenReturn(optimisingResult);
        when(optimising.explain(QUERY, expressionContext)).thenReturn(mock(ExplainPlan.class));

        dispatcher(QueryOptimiserMode.SHADOW).create(QUERY, seed, expressionContext);

        verify(optimising).explain(QUERY, expressionContext);
    }

    @Test
    void modeShadow_explainThrowing_stillServesLegacyResult() {
        when(legacy.create(QUERY, seed, expressionContext)).thenReturn(legacyResult);
        when(optimising.create(QUERY, seed, expressionContext)).thenReturn(optimisingResult);
        when(optimising.explain(QUERY, expressionContext)).thenThrow(new RuntimeException("boom"));

        final SearchRequest result = dispatcher(QueryOptimiserMode.SHADOW).create(QUERY, seed, expressionContext);

        assertThat(result).isSameAs(legacyResult);
    }

    @Test
    void modeOff_andModeOn_neverAskForAnEstimateDuringCreate() {
        when(legacy.create(QUERY, seed, expressionContext)).thenReturn(legacyResult);
        when(optimising.create(QUERY, seed, expressionContext)).thenReturn(optimisingResult);

        dispatcher(QueryOptimiserMode.OFF).create(QUERY, seed, expressionContext);
        dispatcher(QueryOptimiserMode.ON).create(QUERY, seed, expressionContext);

        verify(optimising, never()).explain(any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void extractDataSourceOnly_respectsModeToo() {
        final Consumer<DocRef> consumer = mock(Consumer.class);

        dispatcher(QueryOptimiserMode.ON).extractDataSourceOnly(QUERY, consumer);
        verify(optimising).extractDataSourceOnly(QUERY, consumer);
        verify(legacy, never()).extractDataSourceOnly(any(), any());

        dispatcher(QueryOptimiserMode.OFF).extractDataSourceOnly(QUERY, consumer);
        verify(legacy).extractDataSourceOnly(QUERY, consumer);

        // SHADOW behaves exactly like OFF here - nothing to shadow-diff for a datasource-only extraction.
        dispatcher(QueryOptimiserMode.SHADOW).extractDataSourceOnly(QUERY, consumer);
        verify(legacy, times(2)).extractDataSourceOnly(QUERY, consumer);
    }

    @Test
    void explain_respectsModeToo() {
        final ExplainPlan legacyPlan = mock(ExplainPlan.class);
        final ExplainPlan optimisingPlan = mock(ExplainPlan.class);
        when(legacy.explain(QUERY, expressionContext)).thenReturn(legacyPlan);
        when(optimising.explain(QUERY, expressionContext)).thenReturn(optimisingPlan);

        assertThat(dispatcher(QueryOptimiserMode.ON).explain(QUERY, expressionContext)).isSameAs(optimisingPlan);
        verify(legacy, never()).explain(any(), any());

        assertThat(dispatcher(QueryOptimiserMode.OFF).explain(QUERY, expressionContext)).isSameAs(legacyPlan);
        verify(optimising).explain(QUERY, expressionContext);

        // SHADOW behaves exactly like OFF here - explain() is already advisory-only, nothing to shadow-diff.
        assertThat(dispatcher(QueryOptimiserMode.SHADOW).explain(QUERY, expressionContext)).isSameAs(legacyPlan);
    }

    @Test
    void modeIsReReadPerCall_notCachedAtConstruction() {
        final QueryOptimiserMode[] mode = {QueryOptimiserMode.OFF};
        final DispatchingQueryCompiler dispatcher =
                new DispatchingQueryCompiler(legacy, optimising, () -> new QueryOptimiserConfig(mode[0]));

        when(legacy.create(QUERY, seed, expressionContext)).thenReturn(legacyResult);
        when(optimising.create(QUERY, seed, expressionContext)).thenReturn(optimisingResult);

        assertThat(dispatcher.create(QUERY, seed, expressionContext)).isSameAs(legacyResult);

        mode[0] = QueryOptimiserMode.ON;
        assertThat(dispatcher.create(QUERY, seed, expressionContext)).isSameAs(optimisingResult);
    }

    @Test
    void constructorRejectsNullArguments() {
        assertThatThrownBy(() ->
                new DispatchingQueryCompiler(null, optimising, () -> new QueryOptimiserConfig(QueryOptimiserMode.OFF)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() ->
                new DispatchingQueryCompiler(legacy, null, () -> new QueryOptimiserConfig(QueryOptimiserMode.OFF)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new DispatchingQueryCompiler(legacy, optimising, null))
                .isInstanceOf(NullPointerException.class);
    }
}
