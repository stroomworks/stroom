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

import stroom.meta.api.MetaService;
import stroom.meta.shared.FindMetaCriteria;
import stroom.meta.shared.MetaFields;
import stroom.meta.shared.SelectionSummary;
import stroom.query.api.ExpressionTerm;
import stroom.query.planner.port.RowCountSignal;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Proves {@link MetaStatsAdapter} correctly wraps the real {@link MetaService} seam - see
 * {@code docs/query-optimiser-implementation-plan.md}, Task 3.1 (mirrors Phase 2's
 * {@code TestFieldInfoSourceAdapter} "test the real seam" style).
 */
class TestMetaStatsAdapter {

    private static SelectionSummary summaryWithItemCount(final long itemCount) {
        return new SelectionSummary(itemCount, 1, Set.of("Events"), 1, Set.of("Raw Events"), 0, 0, 1, Set.of(), null);
    }

    @Test
    void estimate_unknownFeed_returnsEmptyWithoutQueryingSelectionSummary() {
        final MetaService metaService = mock(MetaService.class);
        when(metaService.getFeeds()).thenReturn(Set.of("Events"));
        final MetaStatsAdapter adapter = new MetaStatsAdapter(metaService);

        assertThat(adapter.estimate("Bogus", null, null)).isEmpty();
        verify(metaService, never()).getSelectionSummary(any());
    }

    @Test
    void estimate_knownFeedNoTimeRange_returnsRowCount() {
        final MetaService metaService = mock(MetaService.class);
        when(metaService.getFeeds()).thenReturn(Set.of("Events"));
        when(metaService.getSelectionSummary(any())).thenReturn(summaryWithItemCount(42));
        final MetaStatsAdapter adapter = new MetaStatsAdapter(metaService);

        assertThat(adapter.estimate("Events", null, null)).contains(new RowCountSignal(42));
    }

    @Test
    void estimate_criteriaIncludesFeedAndTimeRangeTerms() {
        final MetaService metaService = mock(MetaService.class);
        when(metaService.getFeeds()).thenReturn(Set.of("Events"));
        when(metaService.getSelectionSummary(any())).thenReturn(summaryWithItemCount(0));
        final MetaStatsAdapter adapter = new MetaStatsAdapter(metaService);

        adapter.estimate("Events", 1_000L, 2_000L);

        final ArgumentCaptor<FindMetaCriteria> captor = ArgumentCaptor.forClass(FindMetaCriteria.class);
        verify(metaService).getSelectionSummary(captor.capture());
        final String expressionText = captor.getValue().getExpression().toString();
        assertThat(expressionText)
                .contains("Feed")
                .contains("Events")
                .contains(MetaFields.CREATE_TIME.getFldName())
                .contains(ExpressionTerm.Condition.BETWEEN.getDisplayValue());
    }

    @Test
    void estimate_onlyFromTime_usesGreaterThanOrEqualTo() {
        final MetaService metaService = mock(MetaService.class);
        when(metaService.getFeeds()).thenReturn(Set.of("Events"));
        when(metaService.getSelectionSummary(any())).thenReturn(summaryWithItemCount(0));
        final MetaStatsAdapter adapter = new MetaStatsAdapter(metaService);

        adapter.estimate("Events", 1_000L, null);

        final ArgumentCaptor<FindMetaCriteria> captor = ArgumentCaptor.forClass(FindMetaCriteria.class);
        verify(metaService).getSelectionSummary(captor.capture());
        assertThat(captor.getValue().getExpression().toString())
                .contains(ExpressionTerm.Condition.GREATER_THAN_OR_EQUAL_TO.getDisplayValue());
    }

    @Test
    void estimate_onlyToTime_usesLessThan() {
        final MetaService metaService = mock(MetaService.class);
        when(metaService.getFeeds()).thenReturn(Set.of("Events"));
        when(metaService.getSelectionSummary(any())).thenReturn(summaryWithItemCount(0));
        final MetaStatsAdapter adapter = new MetaStatsAdapter(metaService);

        adapter.estimate("Events", null, 2_000L);

        final ArgumentCaptor<FindMetaCriteria> captor = ArgumentCaptor.forClass(FindMetaCriteria.class);
        verify(metaService).getSelectionSummary(captor.capture());
        assertThat(captor.getValue().getExpression().toString())
                .contains(ExpressionTerm.Condition.LESS_THAN.getDisplayValue());
    }

    @Test
    void constructorRejectsNullMetaService() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new MetaStatsAdapter(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void estimateRejectsNullFeedName() {
        final MetaStatsAdapter adapter = new MetaStatsAdapter(mock(MetaService.class));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> adapter.estimate(null, null, null))
                .isInstanceOf(NullPointerException.class);
    }
}
