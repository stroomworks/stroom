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

package stroom.pipelinefactory.impl;

import stroom.meta.api.MetaService;
import stroom.meta.shared.FindMetaCriteria;
import stroom.meta.shared.Meta;
import stroom.meta.shared.MetaFields;
import stroom.pipelinefactory.impl.CombinationScanner.Scan;
import stroom.pipelinefactory.shared.PipelineFactoryDoc;
import stroom.query.api.ExpressionItem;
import stroom.query.api.ExpressionOperator;
import stroom.query.api.ExpressionOperator.Op;
import stroom.query.api.ExpressionTerm;
import stroom.query.api.ExpressionTerm.Condition;
import stroom.security.api.SecurityContext;
import stroom.util.shared.CriteriaFieldSort;
import stroom.util.shared.ResultPage;
import stroom.util.shared.UserRef;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestCombinationScanner {

    @Test
    void aStreamsCombinationIsItsValuesForTheKey() {
        final Meta stream = Meta.builder().id(7L).feedName("SYSLOG").typeName("Events").pipelineUuid("writer").build();

        assertThat(CombinationScanner.combinationOf(List.of("Feed", "Type"), stream))
                .isEqualTo(Combination.ofFeedAndType("SYSLOG", "Events"));
        assertThat(CombinationScanner.combinationOf(List.of("Pipeline"), stream))
                .isEqualTo(new Combination(Map.of("Pipeline", "writer")));
    }

    @Test
    void streamsAreReadABatchAtATimeInIdOrder() {
        // The place in the stream table moves to the highest id read, so a batch must be the lowest ids
        // after it: read in any other order, the streams below that id not in the batch would be skipped.
        final MetaService metaService = mock(MetaService.class);
        final SecurityContext securityContext = mock(SecurityContext.class);
        when(securityContext.asUserResult(any(UserRef.class), any())).thenAnswer(invocation ->
                ((Supplier<?>) invocation.getArgument(1)).get());
        final List<Meta> two = List.of(
                Meta.builder().id(11L).feedName("SYSLOG").typeName("Raw Events").build(),
                Meta.builder().id(12L).feedName("AUDIT").typeName("Raw Events").build());
        when(metaService.find(any())).thenReturn(ResultPage.createUnboundedList(two));
        final PipelineFactoryDoc factory = PipelineFactoryDoc.builder().uuid("f").name("f")
                .runAsUser(UserRef.forUserUuid("run-as")).build();

        final Scan scan = new CombinationScanner(metaService, securityContext).scan(factory, 10L, 2);

        final ArgumentCaptor<FindMetaCriteria> criteria = ArgumentCaptor.forClass(FindMetaCriteria.class);
        verify(metaService).find(criteria.capture());
        assertThat(criteria.getValue().getSortList()).extracting(CriteriaFieldSort::getId, CriteriaFieldSort::isDesc)
                .containsExactly(tuple(MetaFields.ID.getFldName(), false));
        assertThat(criteria.getValue().getPageRequest().getLength()).isEqualTo(2);
        assertThat(scan.found()).containsExactly(
                Combination.ofFeedAndType("SYSLOG", "Raw Events"),
                Combination.ofFeedAndType("AUDIT", "Raw Events"));
        assertThat(scan.lastId()).isEqualTo(12L);
        assertThat(scan.full()).isTrue();
    }

    @Test
    void aStreamWithNoValueForAKeyFieldHasNoCombination() {
        // A raw stream was written by no pipeline, so a factory keyed on the writing pipeline does not meet it.
        final Meta raw = Meta.builder().id(7L).feedName("SYSLOG").typeName("Raw Events").build();

        assertThat(CombinationScanner.combinationOf(List.of("Feed", "Pipeline"), raw)).isNull();
    }

    @Test
    void theReadIsWhatArrivedSinceTheLastExceptWhatIsDeletedAndTakesTheFactorysFilter() {
        final ExpressionOperator rawEvents = ExpressionOperator.builder()
                .addTerm("Type", Condition.EQUALS, "Raw Events")
                .build();
        final PipelineFactoryDoc factory = PipelineFactoryDoc.builder().uuid("f").name("f").streamFilter(rawEvents)
                .build();

        final List<ExpressionItem> children = CombinationScanner.expressionOf(factory, 42L).getChildren();

        assertThat(children).hasSize(3);
        assertThat(((ExpressionTerm) children.get(0)).getField()).isEqualTo(MetaFields.ID.getFldName());
        assertThat(((ExpressionTerm) children.get(0)).getCondition()).isEqualTo(Condition.GREATER_THAN);
        assertThat(((ExpressionTerm) children.get(0)).getValue()).isEqualTo("42");
        final ExpressionOperator status = (ExpressionOperator) children.get(1);
        assertThat(status.op()).isEqualTo(Op.OR);
        assertThat(status.getChildren()).extracting(item -> ((ExpressionTerm) item).getValue())
                .containsExactly("Unlocked", "Locked");
        assertThat(children.get(2)).isEqualTo(rawEvents);
    }
}
