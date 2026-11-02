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

package stroom.pipelinefactory.impl.db;

import stroom.docref.DocRef;
import stroom.pipeline.shared.PipelineDoc;
import stroom.pipelinefactory.impl.Combination;
import stroom.pipelinefactory.impl.Made;
import stroom.pipelinefactory.impl.MetCombination;
import stroom.pipelinefactory.impl.MetCombination.State;
import stroom.processor.shared.ProcessorFilter;
import stroom.test.common.util.db.DbTestModule;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/// What a Pipeline Factory has met and how far it has looked, as rows (design 06 §3.4).
class TestCombinationsDao {

    private static final Combination SYSLOG = Combination.ofFeedAndType("SYSLOG", "Raw Events");
    private static final Combination AUDIT = Combination.ofFeedAndType("AUDIT", "Raw Events");

    @Inject
    private CombinationsDao combinations;

    private String factory;

    @BeforeEach
    void setUp() {
        Guice.createInjector(new AbstractModule() {
            @Override
            protected void configure() {
                install(new DbTestModule());
                install(new PipelineFactoryDbModule());
                install(new PipelineFactoryDaoModule());
            }
        }).injectMembers(this);
        factory = "factory-" + System.nanoTime();
    }

    @Test
    void aCombinationIsMetOnceWhateverOrderItsFieldsComeIn() {
        final Map<String, String> typeFirst = new LinkedHashMap<>();
        typeFirst.put("Type", "Raw Events");
        typeFirst.put("Feed", "SYSLOG");

        assertThat(combinations.met(factory, SYSLOG)).isTrue();
        assertThat(combinations.met(factory, SYSLOG)).isFalse();
        assertThat(combinations.met(factory, new Combination(typeFirst))).isFalse();
        assertThat(combinations.met(factory, AUDIT)).isTrue();
        assertThat(combinations.met("another factory " + factory, SYSLOG)).isTrue();

        assertThat(combinations.list(factory)).extracting(MetCombination::combination, MetCombination::state)
                .containsExactly(
                        tuple(SYSLOG, State.PENDING),
                        tuple(AUDIT, State.PENDING));
    }

    @Test
    void whatWasMadeIsKeptAndIsNoLongerToBeTriedAgain() {
        combinations.met(factory, SYSLOG);
        final DocRef pipeline = PipelineDoc.buildDocRef().uuid("pipeline-uuid").name("SYSLOG Raw Events").build();

        combinations.made(factory, SYSLOG, new Made(pipeline, ProcessorFilter.builder().uuid("filter-uuid").build()));

        final MetCombination met = combinations.list(factory).getFirst();
        assertThat(met.state()).isEqualTo(State.MADE);
        assertThat(met.tries()).isOne();
        assertThat(met.pipeline()).isEqualTo(pipeline);
        assertThat(met.filterUuid()).isEqualTo("filter-uuid");
        assertThat(met.message()).isNull();
        assertThat(combinations.unmade(factory, Long.MAX_VALUE)).isEmpty();
    }

    @Test
    void aFailureIsKeptWithItsReasonAndTriedAgainOnceItHasWaited() {
        combinations.met(factory, SYSLOG);
        combinations.met(factory, AUDIT);
        combinations.failed(factory, AUDIT, "The template AI does not exist");
        final long afterTheFailure = System.currentTimeMillis() + 1;

        final MetCombination audit = combinations.list(factory).get(1);
        assertThat(audit.state()).isEqualTo(State.FAILED);
        assertThat(audit.tries()).isOne();
        assertThat(audit.message()).isEqualTo("The template AI does not exist");
        // A pending combination is one a run stopped part-way through, and is tried again with the failures.
        assertThat(combinations.unmade(factory, afterTheFailure)).containsExactly(SYSLOG, AUDIT);
        assertThat(combinations.unmade(factory, 0L)).isEmpty();
    }

    @Test
    void aForgottenCombinationIsMetAgain() {
        combinations.met(factory, SYSLOG);

        assertThat(combinations.forget(factory, SYSLOG)).isTrue();
        assertThat(combinations.forget(factory, SYSLOG)).isFalse();
        assertThat(combinations.met(factory, SYSLOG)).isTrue();
    }

    @Test
    void aDeletedFactorysRecordIsForgottenAndNoOtherFactorys() {
        final String other = "other-" + factory;
        combinations.met(factory, SYSLOG);
        combinations.lastMetaId(factory, 30L);
        combinations.met(other, SYSLOG);

        combinations.forgetFactory(factory);

        assertThat(combinations.list(factory)).isEmpty();
        assertThat(combinations.lastMetaId(factory)).isZero();
        assertThat(combinations.list(other)).hasSize(1);
    }

    @Test
    void theScanResumesWhereItLeftOff() {
        assertThat(combinations.lastMetaId(factory)).isZero();

        combinations.lastMetaId(factory, 120L);
        combinations.lastMetaId(factory, 250L);

        assertThat(combinations.lastMetaId(factory)).isEqualTo(250L);
    }

    @Test
    void aCombinationKeyedOnAPipelineReadsBack() {
        final Combination written = new Combination(Map.of("Pipeline", "writer-uuid"));
        combinations.met(factory, written);

        assertThat(combinations.list(factory)).extracting(MetCombination::combination)
                .containsExactly(written);
    }
}
