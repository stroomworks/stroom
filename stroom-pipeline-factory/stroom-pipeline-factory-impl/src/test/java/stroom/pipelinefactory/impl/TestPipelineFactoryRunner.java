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
import stroom.pipeline.shared.PipelineDoc;
import stroom.pipelinefactory.impl.CombinationScanner.Scan;
import stroom.pipelinefactory.impl.MetCombination.State;
import stroom.pipelinefactory.shared.PipelineFactoryDoc;
import stroom.processor.shared.ProcessorFilter;
import stroom.security.api.SecurityContext;
import stroom.util.shared.UserRef;
import stroom.util.time.StroomDuration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TestPipelineFactoryRunner {

    private static final Combination SYSLOG = Combination.ofFeedAndType("SYSLOG", "Raw Events");
    private static final Combination AUDIT = Combination.ofFeedAndType("AUDIT", "Raw Events");
    private static final int BATCH = 100;

    @Mock
    private PipelineFactoryStore store;
    @Mock
    private CombinationScanner scanner;
    @Mock
    private PipelineMaker maker;
    @Mock
    private SecurityContext securityContext;

    private final InMemoryCombinations combinations = new InMemoryCombinations();
    private PipelineFactoryRunner runner;
    private PipelineFactoryDoc factory;

    @BeforeEach
    void setUp() {
        final PipelineFactoryConfig config = new PipelineFactoryConfig(null, BATCH, 3, StroomDuration.ofMinutes(10));
        runner = new PipelineFactoryRunner(store, scanner, maker, combinations, () -> config, securityContext);
        factory = factory("raw events", true);
    }

    @Test
    void eachCombinationMetForTheFirstTimeGetsAPipelineAndTheScanMovesOn() {
        scans(new Scan(found(SYSLOG, AUDIT), 50L, false));
        madeFor(SYSLOG);
        madeFor(AUDIT);

        runner.run(factory);

        assertThat(combinations.list(factory.getUuid())).extracting(MetCombination::combination,
                        MetCombination::state)
                .containsExactly(tuple(SYSLOG, State.MADE), tuple(AUDIT, State.MADE));
        assertThat(combinations.lastMetaId(factory.getUuid())).isEqualTo(50L);
    }

    @Test
    void aCombinationAlreadyMetIsNotMadeAgainEvenIfItsPipelineHasGone() {
        // design 06 §3.4: the record, not the pipeline, says the factory has met it, so a pipeline a person
        // deletes stays deleted.
        combinations.met(factory.getUuid(), SYSLOG);
        combinations.made(factory.getUuid(), SYSLOG, made(SYSLOG));
        combinations.lastMetaId(factory.getUuid(), 50L);
        when(scanner.scan(factory, 50L, BATCH)).thenReturn(new Scan(found(SYSLOG), 80L, false));

        runner.run(factory);

        verify(maker, never()).make(any(), any());
        assertThat(combinations.lastMetaId(factory.getUuid())).isEqualTo(80L);
    }

    @Test
    void aFailureIsRecordedAndTriedAgainOnlyOnceItHasWaited() {
        combinations.at(System.currentTimeMillis());
        // AUDIT is in the first batch; later runs find nothing new.
        when(scanner.scan(eq(factory), anyLong(), eq(BATCH))).thenAnswer(invocation -> {
            final long after = invocation.getArgument(1);
            return after == 0L
                    ? new Scan(found(AUDIT), 10L, false)
                    : new Scan(Set.of(), after, false);
        });
        when(maker.make(factory, AUDIT)).thenThrow(new PipelineMakerException("The template AI does not exist"));

        runner.run(factory);
        runner.run(factory);

        verify(maker, times(1)).make(factory, AUDIT);
        final MetCombination audit = combinations.list(factory.getUuid()).getFirst();
        assertThat(audit.state()).isEqualTo(State.FAILED);
        assertThat(audit.message()).isEqualTo("The template AI does not exist");

        // Once it has waited long enough it is tried again, and this time it is made.
        combinations.at(System.currentTimeMillis() - StroomDuration.ofMinutes(11).toMillis());
        combinations.failed(factory.getUuid(), AUDIT, "still failing");
        doAnswer(invocation -> made(AUDIT)).when(maker).make(factory, AUDIT);

        runner.run(factory);

        assertThat(combinations.list(factory.getUuid()).getFirst().state()).isEqualTo(State.MADE);
    }

    @Test
    void fullBatchesAreReadOneAfterAnotherUpToTheRunsLimit() {
        when(scanner.scan(eq(factory), anyLong(), eq(BATCH))).thenAnswer(invocation -> {
            final long after = invocation.getArgument(1);
            return new Scan(Set.of(), after + BATCH, true);
        });

        runner.run(factory);

        // Three batches are the limit set up here; the rest of the history waits for the next run.
        assertThat(combinations.lastMetaId(factory.getUuid())).isEqualTo(3L * BATCH);
        verify(scanner, times(3)).scan(eq(factory), anyLong(), anyInt());
    }

    @Test
    void onlyEnabledFactoriesWithoutProblemsRunAndOneFailingStopsNoOther() {
        final PipelineFactoryDoc disabled = factory("disabled", false);
        final PipelineFactoryDoc broken = factory("broken", true);
        final PipelineFactoryDoc working = factory("working", true);
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(securityContext).asProcessingUser(any(Runnable.class));
        when(store.listDocuments()).thenReturn(new LinkedHashSet<>(List.of(
                ref(disabled), ref(broken), ref(working))));
        when(store.readDocument(ref(disabled))).thenReturn(disabled);
        when(store.readDocument(ref(broken))).thenReturn(broken);
        when(store.readDocument(ref(working))).thenReturn(working);
        when(scanner.scan(broken, 0L, BATCH)).thenThrow(new IllegalStateException("the meta store is down"));
        when(scanner.scan(working, 0L, BATCH)).thenReturn(new Scan(found(SYSLOG), 5L, false));
        when(maker.make(working, SYSLOG)).thenReturn(made(SYSLOG));

        runner.exec();

        verify(scanner, never()).scan(eq(disabled), anyLong(), anyInt());
        assertThat(combinations.list(working.getUuid())).extracting(MetCombination::state)
                .containsExactly(State.MADE);
    }

    private void scans(final Scan scan) {
        when(scanner.scan(factory, 0L, BATCH)).thenReturn(scan);
    }

    private void madeFor(final Combination combination) {
        when(maker.make(factory, combination)).thenReturn(made(combination));
    }

    private static Made made(final Combination combination) {
        final DocRef pipeline = PipelineDoc.buildDocRef()
                .uuid("pipeline-" + combination.values().get("Feed"))
                .name(combination.values().get("Feed") + " Raw Events")
                .build();
        return new Made(pipeline, ProcessorFilter.builder().uuid("filter-" + pipeline.getUuid()).build());
    }

    private static Set<Combination> found(final Combination... combinations) {
        return new LinkedHashSet<>(List.of(combinations));
    }

    private static PipelineFactoryDoc factory(final String name, final boolean enabled) {
        return PipelineFactoryDoc.builder()
                .uuid("factory-" + name)
                .name(name)
                .enabled(enabled)
                .template(PipelineDoc.buildDocRef().uuid("template").name("AI").build())
                .runAsUser(UserRef.forUserUuid("run-as"))
                .build();
    }

    private static DocRef ref(final PipelineFactoryDoc factory) {
        return PipelineFactoryDoc.buildDocRef().uuid(factory.getUuid()).name(factory.getName()).build();
    }
}
