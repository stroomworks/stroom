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

package stroom.learningflow.quality;

import stroom.docref.DocRef;
import stroom.learningflow.doc.LearnerAccess;
import stroom.learningflow.element.LearnerDocuments;
import stroom.learningflow.shared.Binding;
import stroom.learningflow.shared.LearnerRef;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.shared.QualityReport;
import stroom.learningflow.shared.QualitySample;
import stroom.learningflow.shared.RecordKind;
import stroom.learningflow.shared.VolumeDay;
import stroom.learningflow.stage.Learner;
import stroom.learningflow.stage.LearnerBindings;
import stroom.learningflow.stage.LearningPipelines;
import stroom.learningflow.stage.RegressionSet;
import stroom.learningflow.stage.RegressionSet.Accepted;
import stroom.learningflow.stage.RegressionSet.Kind;
import stroom.learningflow.state.InMemoryDeletedPipelines;
import stroom.pipeline.shared.PipelineDoc;
import stroom.security.api.SecurityContext;
import stroom.security.shared.AppPermission;
import stroom.security.shared.DocumentPermission;
import stroom.util.shared.PermissionException;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestQualityService {

    private static final long DAY_MS = 24L * 60 * 60 * 1000;
    // Noon on a day, so that which day it falls in is unambiguous
    private static final long NOW = 20_000 * DAY_MS + DAY_MS / 2;
    private static final Learner FIREWALL = new Learner("p-1", "learningFlow");
    private static final Learner GONE = new Learner("p-2", "learningFlow");

    private final LearnerDocuments documents = Mockito.mock(LearnerDocuments.class);
    private final LearningPipelines pipelines = Mockito.mock(LearningPipelines.class);
    private final LearnerBindings bindings = Mockito.mock(LearnerBindings.class);
    private final Volumes volumes = Mockito.mock(Volumes.class);
    private final RegressionSet regressionSet = Mockito.mock(RegressionSet.class);
    private final InMemoryDeletedPipelines deletedPipelines = new InMemoryDeletedPipelines();

    TestQualityService() {
        Mockito.when(documents.documentOf(FIREWALL)).thenReturn(Optional.of("ss-1"));
        Mockito.when(documents.documentOf(GONE)).thenReturn(Optional.empty());
        Mockito.when(pipelines.documentOf(GONE)).thenReturn(Optional.of("ss-1"));
        final Binding serving = binding("b-1", false);
        final Binding draft = binding("b-2", true);
        Mockito.when(bindings.forLearner(FIREWALL)).thenReturn(List.of(draft, serving));
    }

    @Test
    void theVolumeIsEveryDayOfTheFortnightWithTheDaysThatHadStreams() {
        final long today = NOW - DAY_MS / 2;
        Mockito.when(volumes.perDay(List.of("b-2", "b-1"), today - 13 * DAY_MS))
                .thenReturn(List.of(new VolumeDay(today - DAY_MS, 3L, 1200L), new VolumeDay(today, 1L, 40L)));

        final QualityReport report = service(viewer()).report("p-1", "learningFlow");

        assertThat(report.getDays()).hasSize(QualityService.DAYS);
        assertThat(report.getDays().get(0).getDayMs()).isEqualTo(today - 13 * DAY_MS);
        assertThat(report.getDays().get(0).getStreams()).isZero();
        assertThat(report.getDays().get(12).getRecords()).isEqualTo(1200);
        assertThat(report.getDays().get(13).getStreams()).isEqualTo(1);
    }

    @Test
    void theCardinalityIsOfTheTranslationInUseNotTheDraft() {
        Mockito.when(regressionSet.accepted("b-1")).thenReturn(List.of(
                accepted(Kind.TRANSLATION, "Network/Open", 1),
                accepted(Kind.TRANSLATION, "Network/Open", 2),
                accepted(Kind.TRANSLATION, "Network/Close", 3),
                accepted(Kind.UNKNOWN, null, 4),
                accepted(Kind.NONE, null, 5)));

        final QualityReport report = service(viewer()).report("p-1", "learningFlow");

        assertThat(report.getServingBindingUuid()).isEqualTo("b-1");
        assertThat(report.getTranslated()).isEqualTo(3);
        assertThat(report.getUnknown()).isEqualTo(1);
        assertThat(report.getNoOutput()).isEqualTo(1);
        assertThat(report.getDistinctTargets()).describedAs("two kinds of record settled").isEqualTo(2);
        Mockito.verify(regressionSet, Mockito.never()).accepted("b-2");
    }

    @Test
    void nothingInUseHasNoCardinality() {
        final Binding draft = binding("b-2", true);
        Mockito.when(bindings.forLearner(FIREWALL)).thenReturn(List.of(draft));
        final QualityReport report = service(viewer()).report("p-1", "learningFlow");
        assertThat(report.getServingBindingUuid()).isNull();
        assertThat(report.getTranslated()).isZero();
        Mockito.verifyNoInteractions(regressionSet);
    }

    @Test
    void someoneWhoMayNotViewThePipelineReadsNothing() {
        final QualityService service = service(context(Set.of(), Set.of(), true));
        assertThatThrownBy(() -> service.report("p-1", "learningFlow")).isInstanceOf(PermissionException.class);
        assertThatThrownBy(() -> service.samples("p-1", "learningFlow"))
                .isInstanceOf(PermissionException.class);
    }

    @Test
    void aPipelineAndElementMustBeNamed() {
        assertThatThrownBy(() -> service(viewer()).report(" ", "learningFlow"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void samplesAreTheNewestRecordsBesideWhatTheyBecameCapped() {
        final List<Accepted> kept = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            kept.add(accepted(Kind.TRANSLATION, "Network/Open", i));
        }
        Mockito.when(regressionSet.accepted("b-1")).thenReturn(kept);

        final List<QualitySample> samples = service(viewer()).samples("p-1", "learningFlow");

        assertThat(samples).hasSize(QualityService.MOST_SAMPLES);
        assertThat(samples.get(0).getAcceptedMs()).describedAs("newest first").isEqualTo(24);
        assertThat(samples.get(0).getText()).isEqualTo("line 24");
        assertThat(samples.get(0).getOutput()).isEqualTo("<Event>24</Event>");
        assertThat(samples.get(0).getKind()).isEqualTo(RecordKind.TRANSLATION);
    }

    @Test
    void samplesAreDataSoNeedThePermissionToViewData() {
        final QualityService service = service(context(Set.of(DocumentPermission.VIEW), Set.of(), false));
        assertThatThrownBy(() -> service.samples("p-1", "learningFlow"))
                .isInstanceOf(PermissionException.class)
                .hasMessageContaining("view data");
    }

    @Test
    void aDeletedPipelinesQualityIsReadByItsLearningFlowsViewers() {
        deletedPipelines.deleted("p-2", NOW);
        Mockito.when(bindings.forLearner(GONE)).thenReturn(List.of());
        final QualityService flowViewer = service(context(Set.of(), Set.of(DocumentPermission.VIEW), true));

        assertThat(flowViewer.report("p-2", "learningFlow").getDays()).hasSize(QualityService.DAYS);
    }

    private QualityService service(final SecurityContext context) {
        return new QualityService(
                new LearnerAccess(() -> context, () -> deletedPipelines, () -> documents, () -> pipelines),
                () -> context, () -> bindings, () -> volumes, () -> regressionSet, () -> NOW);
    }

    // Someone who may view the pipelines and data, and nothing more
    private static SecurityContext viewer() {
        return context(Set.of(DocumentPermission.VIEW), Set.of(), true);
    }

    // A user with these permissions on every pipeline and every Learning Flow, with or without the permission
    // to view data
    private static SecurityContext context(final Set<DocumentPermission> onPipelines,
                                           final Set<DocumentPermission> onFlows,
                                           final boolean viewData) {
        final SecurityContext context = Mockito.mock(SecurityContext.class);
        Mockito.when(context.getUserIdentityForAudit()).thenReturn("qa");
        Mockito.when(context.hasAppPermission(AppPermission.VIEW_DATA_PERMISSION)).thenReturn(viewData);
        Mockito.when(context.hasDocumentPermission(Mockito.any(), Mockito.any())).thenAnswer(invocation -> {
            final DocRef asked = invocation.getArgument(0);
            final DocumentPermission permission = invocation.getArgument(1);
            return PipelineDoc.TYPE.equals(asked.getType())
                    ? onPipelines.contains(permission)
                    : LearningFlowDoc.TYPE.equals(asked.getType()) && onFlows.contains(permission);
        });
        return context;
    }

    private static Binding binding(final String uuid, final boolean draft) {
        final Binding binding = Mockito.mock(Binding.class);
        Mockito.when(binding.getUuid()).thenReturn(uuid);
        Mockito.when(binding.isDraft()).thenReturn(draft);
        return binding;
    }

    private static Accepted accepted(final Kind kind, final String target, final long acceptedMs) {
        return new Accepted("<record/>", "line " + acceptedMs, null, null,
                kind == Kind.NONE
                        ? null
                        : "<Event>" + acceptedMs + "</Event>",
                kind, 0.95, 7L, acceptedMs, target);
    }
}
