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

import stroom.docref.DocRef;
import stroom.explorer.api.ExplorerService;
import stroom.explorer.shared.PermissionInheritance;
import stroom.meta.shared.MetaFields;
import stroom.pipeline.PipelineStore;
import stroom.pipeline.factory.ElementRegistryFactory;
import stroom.pipeline.factory.PipelineStackLoader;
import stroom.pipeline.shared.PipelineDoc;
import stroom.pipeline.shared.data.PipelineDataBuilder;
import stroom.pipelinefactory.shared.PipelineFactoryDoc;
import stroom.processor.api.ProcessorFilterService;
import stroom.query.api.ExpressionItem;
import stroom.query.api.ExpressionOperator;
import stroom.query.api.ExpressionTerm;
import stroom.query.api.ExpressionTerm.Condition;
import stroom.query.api.datasource.QueryField;
import stroom.security.api.SecurityContext;
import stroom.util.shared.DocPath;
import stroom.util.shared.PermissionException;
import stroom.util.shared.UserRef;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestPipelineMaker {

    private static final ExpressionOperator RAW_EVENTS = ExpressionOperator.builder()
            .addTextTerm(MetaFields.TYPE, Condition.EQUALS, "Raw Events")
            .build();

    @Test
    void aFilterTakesTheFactorysStreamsOfTheCombinationAlone() {
        final PipelineFactoryDoc factory = factory().streamFilter(RAW_EVENTS).build();
        final Combination syslog = Combination.ofFeedAndType("SYSLOG", "Raw Events");

        final ExpressionOperator expression = PipelineMaker.expressionOf(factory, syslog, syslog.values());

        assertThat(expression.getChildren()).containsExactly(
                RAW_EVENTS,
                text(MetaFields.FEED, "SYSLOG"),
                text(MetaFields.TYPE, "Raw Events"));
    }

    @Test
    void aFactoryWithNoStreamFilterIsMatchedOnItsKeyAlone() {
        final PipelineFactoryDoc factory = factory().build();
        final Combination syslog = Combination.ofFeedAndType("SYSLOG", "Raw Events");

        final ExpressionOperator expression = PipelineMaker.expressionOf(factory, syslog, syslog.values());

        assertThat(expression.getChildren()).hasSize(2);
    }

    @Test
    void aPipelineIsMatchedByItsUuidAndNamedByItsName() {
        final PipelineFactoryDoc factory = factory().key(List.of("Pipeline")).build();
        final Combination written = new Combination(Map.of("Pipeline", "writer-uuid"));
        final Map<String, String> names = new LinkedHashMap<>();
        names.put("Pipeline", "Writer");

        final List<ExpressionItem> terms = PipelineMaker.expressionOf(factory, written, names).getChildren();

        final DocRef writer = PipelineDoc.buildDocRef().uuid("writer-uuid").name("Writer").build();
        assertThat(terms).containsExactly(ExpressionTerm.builder()
                .field("Pipeline")
                .condition(Condition.IS_DOC_REF)
                .docRef(writer)
                .build());
    }

    @Test
    void aCombinationFitsAKeyWhenItHasAValueForEachFieldAndNoOther() {
        final Combination syslog = Combination.ofFeedAndType("SYSLOG", "Raw Events");

        assertThat(syslog.fits(List.of("Feed", "Type"))).isTrue();
        assertThat(syslog.fits(List.of("Type", "Feed"))).isTrue();
        assertThat(syslog.fits(List.of("Feed"))).isFalse();
        assertThat(syslog.fits(List.of("Feed", "Type", "Pipeline"))).isFalse();
    }

    @Test
    void everythingIsMadeAsTheRunAsUserAndARefusalMakesNothing() {
        // A123: the factory makes only what its run-as user could make. The application's integration
        // tests run under a security context that allows everything, so the refusal is shown here.
        final ExplorerService explorerService = mock(ExplorerService.class);
        final ProcessorFilterService processorFilterService = mock(ProcessorFilterService.class);
        final SecurityContext securityContext = mock(SecurityContext.class);
        final UserRef runAs = UserRef.forUserUuid("run-as");
        final List<UserRef> ranAs = new ArrayList<>();
        when(securityContext.asUserResult(any(UserRef.class), any())).thenAnswer(invocation -> {
            ranAs.add(invocation.getArgument(0));
            return ((Supplier<?>) invocation.getArgument(1)).get();
        });
        when(securityContext.useAsReadResult(any())).thenAnswer(invocation ->
                ((Supplier<?>) invocation.getArgument(0)).get());
        when(explorerService.ensureFolderPath(any(DocPath.class), any(PermissionInheritance.class)))
                .thenThrow(new PermissionException(runAs, "You may not create a folder here"));
        final DocRef templateRef = PipelineDoc.buildDocRef().uuid("template").name("AI").build();
        final PipelineDoc template = PipelineDoc.builder()
                .uuid("template")
                .name("AI")
                .pipelineData(new PipelineDataBuilder().build())
                .build();
        final PipelineStore pipelineStore = mock(PipelineStore.class);
        when(pipelineStore.readDocument(templateRef)).thenReturn(template);
        final PipelineStackLoader stackLoader = mock(PipelineStackLoader.class);
        when(stackLoader.loadPipelineStack(template)).thenReturn(List.of(template));
        final PipelineMaker maker = new PipelineMaker(explorerService, pipelineStore, stackLoader,
                mock(ElementRegistryFactory.class), processorFilterService, securityContext);
        final PipelineFactoryDoc factory = factory()
                .enabled(true)
                .template(templateRef)
                .runAsUser(runAs)
                .build();

        assertThatThrownBy(() -> maker.make(factory, Combination.ofFeedAndType("SYSLOG", "Raw Events")))
                .isInstanceOf(PipelineMakerException.class)
                .hasMessage("Pipeline Factory 'raw' could not make a pipeline for {Feed=SYSLOG, Type=Raw Events}: "
                            + "You may not create a folder here");
        assertThat(ranAs).containsExactly(runAs);
        verify(explorerService, never()).create(any(), any(), any(), any());
        verifyNoInteractions(processorFilterService);
    }

    private static PipelineFactoryDoc.Builder factory() {
        return PipelineFactoryDoc.builder().uuid("factory").name("raw");
    }

    private static ExpressionItem text(final QueryField field, final String value) {
        return ExpressionOperator.builder()
                .addTextTerm(field, Condition.EQUALS, value)
                .build()
                .getChildren()
                .getFirst();
    }
}
