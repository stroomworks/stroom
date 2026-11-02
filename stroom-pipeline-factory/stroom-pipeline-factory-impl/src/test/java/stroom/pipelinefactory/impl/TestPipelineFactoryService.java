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
import stroom.explorer.shared.ExplorerNode;
import stroom.pipeline.shared.PipelineDoc;
import stroom.pipelinefactory.shared.PipelineFactoryCombination;
import stroom.processor.shared.ProcessorFilter;
import stroom.security.api.SecurityContext;
import stroom.security.shared.DocumentPermission;
import stroom.util.shared.PermissionException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TestPipelineFactoryService {

    private static final String FACTORY = "factory";
    private static final Combination SYSLOG = Combination.ofFeedAndType("SYSLOG", "Raw Events");
    private static final Combination AUDIT = Combination.ofFeedAndType("AUDIT", "Raw Events");

    @Mock
    private ExplorerService explorerService;
    @Mock
    private SecurityContext securityContext;

    private final InMemoryCombinations combinations = new InMemoryCombinations();
    private PipelineFactoryService service;

    @BeforeEach
    void setUp() {
        service = new PipelineFactoryService(combinations, explorerService, securityContext);
    }

    @Test
    void eachCombinationSaysWhetherItsPipelineIsStillThere() {
        allow(DocumentPermission.VIEW);
        when(securityContext.asProcessingUserResult(any())).thenAnswer(invocation ->
                ((Supplier<?>) invocation.getArgument(0)).get());
        final DocRef kept = pipeline("kept");
        final DocRef deleted = pipeline("deleted");
        made(SYSLOG, kept);
        made(AUDIT, deleted);
        when(explorerService.getFromDocRef(kept)).thenReturn(Optional.of(mock(ExplorerNode.class)));
        when(explorerService.getFromDocRef(deleted)).thenReturn(Optional.empty());

        assertThat(service.combinations(FACTORY)).extracting(
                        PipelineFactoryCombination::getValues,
                        PipelineFactoryCombination::getState,
                        PipelineFactoryCombination::isPipelineExists)
                .containsExactly(
                        tuple(SYSLOG.values(), PipelineFactoryCombination.MADE, true),
                        tuple(AUDIT.values(), PipelineFactoryCombination.MADE, false));
    }

    @Test
    void aPersonWhoMayNotViewTheFactoryIsNotShownWhatItMet() {
        assertThatThrownBy(() -> service.combinations(FACTORY)).isInstanceOf(PermissionException.class);
    }

    @Test
    void forgettingNeedsEditAndLetsTheCombinationBeMetAgain() {
        combinations.met(FACTORY, SYSLOG);

        assertThatThrownBy(() -> service.forget(FACTORY, SYSLOG.values())).isInstanceOf(PermissionException.class);
        assertThat(combinations.list(FACTORY)).hasSize(1);

        allow(DocumentPermission.EDIT);
        assertThat(service.forget(FACTORY, SYSLOG.values())).isTrue();
        assertThat(combinations.met(FACTORY, SYSLOG)).isTrue();
    }

    private void allow(final DocumentPermission permission) {
        when(securityContext.hasDocumentPermission(any(DocRef.class), eq(permission))).thenReturn(true);
    }

    private void made(final Combination combination, final DocRef pipeline) {
        combinations.met(FACTORY, combination);
        combinations.made(FACTORY, combination, new Made(pipeline, ProcessorFilter.builder().uuid("f").build()));
    }

    private static DocRef pipeline(final String uuid) {
        return PipelineDoc.buildDocRef().uuid(uuid).name(uuid).build();
    }
}
