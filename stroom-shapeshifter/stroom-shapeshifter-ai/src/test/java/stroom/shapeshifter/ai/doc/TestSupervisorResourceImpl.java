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

package stroom.shapeshifter.ai.doc;

import stroom.docref.DocRef;
import stroom.docstore.api.DocFinder;
import stroom.pipeline.shared.PipelineDoc;
import stroom.security.api.SecurityContext;
import stroom.security.mock.MockSecurityContext;
import stroom.security.shared.DocumentPermission;
import stroom.shapeshifter.ai.element.LearnerDocuments;
import stroom.shapeshifter.ai.stage.Attempts;
import stroom.shapeshifter.ai.stage.Attempts.Attempt;
import stroom.shapeshifter.ai.stage.Attempts.Grant;
import stroom.shapeshifter.ai.stage.Attempts.Recorded;
import stroom.shapeshifter.ai.stage.Guidance;
import stroom.shapeshifter.ai.stage.Learner;
import stroom.shapeshifter.ai.stage.LearningPipelines;
import stroom.shapeshifter.ai.state.InMemoryDeletedPipelines;
import stroom.shapeshifter.ai.state.InMemoryGuidance;
import stroom.shapeshifter.ai.state.InMemoryLearnerBindings;
import stroom.shapeshifter.shared.AttemptCriteria;
import stroom.shapeshifter.shared.AttemptStatus;
import stroom.shapeshifter.shared.Binding;
import stroom.shapeshifter.shared.GrantRequest;
import stroom.shapeshifter.shared.GuidanceRequest;
import stroom.shapeshifter.shared.ImproveRequest;
import stroom.shapeshifter.shared.LearnerCriteria;
import stroom.shapeshifter.shared.LearnerRef;
import stroom.shapeshifter.shared.PromotionMode;
import stroom.shapeshifter.shared.ReasonRequest;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.shapeshifter.shared.SupervisorGuidance;
import stroom.shapeshifter.shared.SupervisorLearner;
import stroom.shapeshifter.shared.SupervisorResource;
import stroom.util.shared.PermissionException;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatcher;
import org.mockito.Mockito;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// A Supervisor request names a learner by its pipeline and element alone; the Learning Flow it runs under
/// is the one its element names. What may be done to a learner is held to its pipeline's permissions: a Data
/// Provider who owns a pipeline acts on its learning without needing the Learning Flow, and the Learning
/// Flow's permission alone does nothing to a pipeline's learner (except read one whose pipeline has been
/// deleted, which has no permissions of its own to ask).
class TestSupervisorResourceImpl {

    private static final LearnerRef OURS = new LearnerRef("pipeline-1", "shapeshifterAi");
    private static final LearnerRef THEIRS = new LearnerRef("pipeline-2", "shapeshifterAi");
    private static final LearnerRef GONE = new LearnerRef("pipeline-3", "shapeshifterAi");
    // Another pipeline's learner, under the same Learning Flow as OURS
    private static final LearnerRef NEIGHBOUR = new LearnerRef("pipeline-4", "shapeshifterAi");
    // A learner whose element names no Learning Flow
    private static final LearnerRef UNNAMED = new LearnerRef("pipeline-5", "shapeshifterAi");

    private final InMemoryGuidance guidance = new InMemoryGuidance();
    private final InMemoryDeletedPipelines deletedPipelines = new InMemoryDeletedPipelines();
    private final InMemoryLearnerBindings learnerBindings = new InMemoryLearnerBindings();
    private final ShapeshifterAiStore store = Mockito.mock(ShapeshifterAiStore.class);
    private final LearningPipelines pipelines = Mockito.mock(LearningPipelines.class);
    private final Attempts attempts = Mockito.mock(Attempts.class);
    private final SupervisorResourceImpl resource = resource(MockSecurityContext::new);

    @Test
    void guidanceIsGivenToALearner() {
        assertThat(resource.addGuidance(OURS.getPipelineUuid(), OURS.getElementId(),
                new GuidanceRequest("Times are local.")))
                .singleElement()
                .satisfies(said -> assertThat(said.getMessage()).isEqualTo("Times are local."));
    }

    @Test
    void aLearnerWhoseElementNamesNoLearningFlowIsToldNothing() {
        assertThatThrownBy(() -> resource.addGuidance(UNNAMED.getPipelineUuid(), UNNAMED.getElementId(),
                new GuidanceRequest("Times are local.")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("doesn't name a Learning Flow");
        assertThatThrownBy(() -> resource.addGuidance(" ", OURS.getElementId(), new GuidanceRequest("Hello")))
                .describedAs("nor a learner not named at all")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Say which learner");
    }

    /// A deleted pipeline's learning is kept to be read until it ages out, and nothing more is done with it
    /// (design 06 A137): what was said to its learner can still be read, though the pipeline names no
    /// Learning Flow now, and nothing is said to it, taken back, improved, accepted or retracted.
    @Test
    void aDeletedPipelinesLearnerIsReadAndNotChanged() {
        final Learner gone = learner(GONE);
        learnerBindings.append(gone, "doc-1", Binding.builder()
                .uuid("rule-gone")
                .pipeline(new DocRef("Pipeline", "fragment-gone", "DOOR-ACCESS learned"))
                .build());
        final long said = guidance.given(gone, "doc-1", "Times are local.", "jo");
        deletedPipelines.deleted(GONE.getPipelineUuid(), 1_000L);

        assertThat(resource.fetchGuidance(GONE.getPipelineUuid(), GONE.getElementId()))
                .describedAs("read, from the Learning Flow it is listed under")
                .singleElement()
                .satisfies(given -> assertThat(given.getMessage()).isEqualTo("Times are local."));
        assertThatThrownBy(() -> resource.addGuidance(GONE.getPipelineUuid(), GONE.getElementId(),
                new GuidanceRequest("And the year.")))
                .describedAs("told nothing")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("has been deleted");
        assertThatThrownBy(() -> resource.deleteGuidance(GONE.getPipelineUuid(), GONE.getElementId(), said))
                .describedAs("nothing taken back")
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> resource.improve("rule-gone", new ImproveRequest(null)))
                .describedAs("its binding not improved")
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> resource.accept("rule-gone"))
                .describedAs("accepted")
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> resource.retract("rule-gone", new ReasonRequest("Gone.")))
                .describedAs("or retracted")
                .isInstanceOf(IllegalStateException.class);
        assertThat(guidance.standing(gone)).hasSize(1);
    }

    /// Giving a learner up says why, since its streams error with the reason; and nothing is done to a deleted
    /// pipeline's learner (design 06 A115, A137).
    @Test
    void aLearnerIsGivenUpOnlyWithCause() {
        assertThatThrownBy(() -> resource.giveUp(OURS.getPipelineUuid(), OURS.getElementId(),
                new ReasonRequest(" ")))
                .describedAs("giving up says why")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("says why");
        assertThatThrownBy(() -> resource.giveUp(OURS.getPipelineUuid(), OURS.getElementId(), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> resource.relearn(OURS.getPipelineUuid(), "", null))
                .describedAs("a learner is named by its pipeline and element")
                .isInstanceOf(IllegalArgumentException.class);

        deletedPipelines.deleted(GONE.getPipelineUuid(), 1_000L);
        assertThatThrownBy(() -> resource.giveUp(GONE.getPipelineUuid(), GONE.getElementId(),
                new ReasonRequest("Retired.")))
                .describedAs("nor a deleted pipeline's")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("has been deleted");
    }

    /// A deleted pipeline has no permissions to ask: its learning is read by the VIEW of the Learning Flow it
    /// is listed under, whether or not it ever bound anything (A137), and without that, not at all.
    @Test
    void aDeletedPipelinesLearningIsReadByTheLearningFlowItIsListedUnder() {
        deletedPipelines.deleted(GONE.getPipelineUuid(), 1_000L);
        final SupervisorResourceImpl reader = resource(grants(Map.of(
                new DocRef(ShapeshifterAiDoc.TYPE, "doc-1"), Set.of(DocumentPermission.VIEW))));
        final SupervisorResourceImpl another = resource(grants(Map.of(
                new DocRef(ShapeshifterAiDoc.TYPE, "doc-2"), Set.of(DocumentPermission.VIEW))));

        assertThat(reader.fetchGuidance(GONE.getPipelineUuid(), GONE.getElementId())).isEmpty();
        assertThatThrownBy(() -> another.fetchGuidance(GONE.getPipelineUuid(), GONE.getElementId()))
                .describedAs("not by another Learning Flow's")
                .isInstanceOf(PermissionException.class);
    }

    /// A Data Provider who may edit their pipeline acts on its learning without being able to see the
    /// Learning Flow it learns under: tells it something, reads what was said, and reads its settings.
    @Test
    void thePipelinesEditorActsWithoutTheLearningFlow() {
        final SupervisorResourceImpl provider = resource(grants(Map.of(
                pipeline(OURS), Set.of(DocumentPermission.VIEW, DocumentPermission.EDIT))));

        assertThat(provider.addGuidance(OURS.getPipelineUuid(), OURS.getElementId(),
                new GuidanceRequest("Times are local.")))
                .singleElement()
                .satisfies(said -> assertThat(said.getMessage()).isEqualTo("Times are local."));
        assertThat(provider.fetchGuidance(OURS.getPipelineUuid(), OURS.getElementId())).hasSize(1);
        Mockito.when(store.readDocument(new DocRef(ShapeshifterAiDoc.TYPE, "doc-1")))
                .thenReturn(ShapeshifterAiDoc.builder().uuid("doc-1").name("Firewall").build());
        assertThat(provider.fetchSettings(OURS.getPipelineUuid(), OURS.getElementId()).getName())
                .describedAs("the Learning Flow its element names")
                .isEqualTo("Firewall");
        // But not another pipeline's learner, under the same Learning Flow or another
        assertThatThrownBy(() -> provider.fetchGuidance(NEIGHBOUR.getPipelineUuid(), NEIGHBOUR.getElementId()))
                .isInstanceOf(PermissionException.class);
        assertThatThrownBy(() -> provider.fetchGuidance(THEIRS.getPipelineUuid(), THEIRS.getElementId()))
                .isInstanceOf(PermissionException.class);
    }

    /// The Learning Flow's permission alone does nothing to a pipeline's learner: the pipeline's owner decides
    /// what its feed is told, approved and relearned with.
    @Test
    void theLearningFlowAloneIsNotEnough() {
        final SupervisorResourceImpl owner = resource(grants(Map.of(
                new DocRef(ShapeshifterAiDoc.TYPE, "doc-1"),
                Set.of(DocumentPermission.VIEW, DocumentPermission.EDIT))));

        assertThatThrownBy(() -> owner.addGuidance(OURS.getPipelineUuid(), OURS.getElementId(),
                new GuidanceRequest("Planted.")))
                .describedAs("told something")
                .isInstanceOf(PermissionException.class);
        assertThatThrownBy(() -> owner.fetchGuidance(OURS.getPipelineUuid(), OURS.getElementId()))
                .describedAs("read")
                .isInstanceOf(PermissionException.class);
        assertThatThrownBy(() -> owner.fetchSettings(OURS.getPipelineUuid(), OURS.getElementId()))
                .describedAs("or its settings read from the pipeline's side")
                .isInstanceOf(PermissionException.class);
        assertThatThrownBy(() -> owner.fetchPipelineLearners(OURS.getPipelineUuid()))
                .describedAs("nor its pipeline's learners listed")
                .isInstanceOf(PermissionException.class);
        assertThat(guidance.standing(learner(OURS))).isEmpty();
    }

    /// Viewing a pipeline reads its learning; changing it takes editing the pipeline.
    @Test
    void viewingThePipelineReadsAndChangesNothing() {
        final long said = guidance.given(learner(OURS), "doc-1", "Times are local.", "jo");
        learnerBindings.append(learner(OURS), "doc-1", Binding.builder()
                .uuid("rule-1")
                .pipeline(new DocRef("Pipeline", "fragment-1", "FIREWALL learned"))
                .build());
        final SupervisorResourceImpl viewer = resource(grants(Map.of(
                pipeline(OURS), Set.of(DocumentPermission.VIEW))));

        assertThat(viewer.fetchGuidance(OURS.getPipelineUuid(), OURS.getElementId())).hasSize(1);
        assertThatThrownBy(() -> viewer.addGuidance(OURS.getPipelineUuid(), OURS.getElementId(),
                new GuidanceRequest("And the year.")))
                .describedAs("told nothing")
                .isInstanceOf(PermissionException.class);
        assertThatThrownBy(() -> viewer.deleteGuidance(OURS.getPipelineUuid(), OURS.getElementId(), said))
                .describedAs("nothing taken back")
                .isInstanceOf(PermissionException.class);
        assertThatThrownBy(() -> viewer.giveUp(OURS.getPipelineUuid(), OURS.getElementId(),
                new ReasonRequest("Retired.")))
                .describedAs("not given up")
                .isInstanceOf(PermissionException.class);
        assertThatThrownBy(() -> viewer.accept("rule-1"))
                .describedAs("its translation not accepted")
                .isInstanceOf(PermissionException.class);
        assertThatThrownBy(() -> viewer.retract("rule-1", new ReasonRequest("Wrong.")))
                .describedAs("or taken back")
                .isInstanceOf(PermissionException.class);
        assertThat(guidance.standing(learner(OURS))).hasSize(1);
    }

    /// A binding someone may not read, one whose pipeline has since been deleted, and one that isn't there
    /// all answer them alike, or the answer would say which bindings exist.
    @Test
    void aBindingIsNotSaidToExistToThoseWhoMayNotReadIt() {
        learnerBindings.append(learner(OURS), "doc-1", Binding.builder()
                .uuid("rule-1")
                .pipeline(new DocRef("Pipeline", "fragment-1", "FIREWALL learned"))
                .build());
        learnerBindings.append(learner(GONE), "doc-1", Binding.builder()
                .uuid("rule-gone")
                .pipeline(new DocRef("Pipeline", "fragment-gone", "DOOR-ACCESS learned"))
                .build());
        deletedPipelines.deleted(GONE.getPipelineUuid(), 1_000L);
        final SupervisorResourceImpl stranger = resource(grants(Map.of()));

        assertThatThrownBy(() -> stranger.accept("rule-1"))
                .isInstanceOf(PermissionException.class)
                .hasMessageContaining("permission to change binding");
        assertThatThrownBy(() -> stranger.accept("rule-gone"))
                .describedAs("not told it was deleted")
                .isInstanceOf(PermissionException.class)
                .hasMessageContaining("permission to change binding");
        assertThatThrownBy(() -> resource.accept("no-such-binding"))
                .isInstanceOf(PermissionException.class)
                .hasMessageContaining("permission to change binding");
    }

    /// Permission to change a learner is its own pipeline's, and several pipelines' learners may run under one
    /// Learning Flow: a person who may edit one pipeline cannot take back what was said to another's learner
    /// by naming its hint's id beside their own learner.
    @Test
    void aHintIsTakenBackOnlyFromTheLearnerItWasSaidTo() {
        final long theirs = guidance.given(learner(NEIGHBOUR), "doc-1", "Said to the neighbour.", "sam");
        final long ours = guidance.given(learner(OURS), "doc-1", "Said to ours.", "jo");
        final SupervisorResourceImpl provider = resource(grants(Map.of(
                pipeline(OURS), Set.of(DocumentPermission.VIEW, DocumentPermission.EDIT))));

        assertThat(provider.deleteGuidance(OURS.getPipelineUuid(), OURS.getElementId(), theirs))
                .describedAs("what was said to their own learner is unchanged")
                .extracting(SupervisorGuidance::getId)
                .containsExactly(ours);
        assertThat(guidance.standing(learner(NEIGHBOUR)))
                .describedAs("and the neighbour's hint is still carried")
                .extracting(Guidance.Given::message)
                .containsExactly("Said to the neighbour.");

        assertThat(provider.deleteGuidance(OURS.getPipelineUuid(), OURS.getElementId(), ours))
                .describedAs("while their own is taken back")
                .isEmpty();
    }

    /// A pipeline's own editor lists its learners, under any Learning Flow, by asking for that pipeline alone.
    @Test
    void aPipelinesLearnersAreListedForItsViewer() {
        Mockito.when(store.listDocuments()).thenReturn(Set.of(new DocRef(ShapeshifterAiDoc.TYPE, "doc-1",
                "Firewall")));
        Mockito.when(store.readDocument(new DocRef(ShapeshifterAiDoc.TYPE, "doc-1", "Firewall")))
                .thenReturn(ShapeshifterAiDoc.builder().uuid("doc-1").name("Firewall").build());
        Mockito.when(pipelines.learners(Mockito.anyCollection(), Mockito.anySet(), Mockito.anyCollection(),
                        Mockito.anyBoolean(), Mockito.anyCollection(), Mockito.anyLong(), Mockito.anyInt()))
                .thenReturn(new LearningPipelines.Page(List.of(), 0L));
        final SupervisorResourceImpl viewer = resource(grants(Map.of(
                pipeline(OURS), Set.of(DocumentPermission.VIEW))));

        final List<SupervisorLearner> listed = viewer.fetchPipelineLearners(OURS.getPipelineUuid());

        assertThat(listed).isEmpty();
        // Under every Learning Flow, not only those the viewer may see
        Mockito.verify(pipelines).learners(Mockito.argThat(docs -> docs.contains("doc-1")),
                Mockito.anySet(), Mockito.anyCollection(), Mockito.eq(false),
                Mockito.eq(List.of(OURS.getPipelineUuid())), Mockito.eq(0L), Mockito.anyInt());
    }

    /// The lists across Learning Flows show only the learners and attempts of pipelines the person may view,
    /// under the Learning Flows they may view. A deleted pipeline's is read by its Learning Flow's VIEW, which
    /// the lists already require. The pipelines go into the query, so counts and pages are of what they may
    /// read.
    @Test
    void theListsShowOnlyThePipelinesThePersonMayView() {
        Mockito.when(store.listDocuments()).thenReturn(Set.of(new DocRef(ShapeshifterAiDoc.TYPE, "doc-1"),
                new DocRef(ShapeshifterAiDoc.TYPE, "doc-2")));
        Mockito.when(pipelines.pipelines()).thenReturn(Set.of(OURS.getPipelineUuid(),
                NEIGHBOUR.getPipelineUuid(), GONE.getPipelineUuid()));
        Mockito.when(pipelines.learners(Mockito.anyCollection(), Mockito.anySet(), Mockito.anyCollection(),
                        Mockito.anyBoolean(), Mockito.anyCollection(), Mockito.anyLong(), Mockito.anyInt()))
                .thenReturn(new LearningPipelines.Page(List.of(), 0L));
        Mockito.when(attempts.found(Mockito.any(), Mockito.anyCollection(), Mockito.anyCollection()))
                .thenReturn(new Attempts.Page(List.of(), 0L));
        deletedPipelines.deleted(GONE.getPipelineUuid(), 1_000L);
        final SupervisorResourceImpl reader = resource(grants(Map.of(
                new DocRef(ShapeshifterAiDoc.TYPE, "doc-1"), Set.of(DocumentPermission.VIEW),
                pipeline(OURS), Set.of(DocumentPermission.VIEW))));
        final Set<String> viewable = Set.of(OURS.getPipelineUuid(), GONE.getPipelineUuid());

        reader.findLearners(new LearnerCriteria());
        reader.findAttempts(new AttemptCriteria());

        Mockito.verify(pipelines).learners(Mockito.eq(Set.of("doc-1")), Mockito.anySet(),
                Mockito.anyCollection(), Mockito.anyBoolean(), Mockito.argThat(sameAs(viewable)),
                Mockito.anyLong(), Mockito.anyInt());
        Mockito.verify(attempts).found(Mockito.any(), Mockito.eq(List.of("doc-1")),
                Mockito.argThat(sameAs(viewable)));
    }

    /// An attempt is history and keeps its Learning Flow, but acting on it changes its learner, with its
    /// Learning Flow's settings: once the pipeline names another Learning Flow, it is acted on from there.
    @Test
    void anAttemptIsActedOnOnlyWhileItsPipelineRunsUnderItsLearningFlow() {
        // Pipeline 1 now names doc-1; this attempt was learning for it under doc-2
        final Recorded moved = new Recorded(7L,
                new Attempt(learner(OURS), "doc-2", "FIREWALL", "Raw Events", 1L, "node-1",
                        PromotionMode.REVIEW, 0L),
                AttemptStatus.PARKED, "Paused", "draft-1", null, 0L, 0L, 0L, List.of(), Grant.NONE, false);
        Mockito.when(attempts.byId(7L)).thenReturn(Optional.of(moved));

        assertThatThrownBy(() -> resource.approve(7L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no longer runs under its Learning Flow");
        assertThatThrownBy(() -> resource.reject(7L, new ReasonRequest("Wrong.")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> resource.grant(7L, new GrantRequest(1_000L, 1_000L)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> resource.exhaust(7L))
                .isInstanceOf(IllegalStateException.class);
    }

    /// Approving, retracting or giving up changes what serves a pipeline, so an auditor must see which it was,
    /// not an "Uncategorised remote API call".
    @Test
    void everyCallIsAuditedAsWhatItDoes() {
        ResourceAuditing.assertEveryCallIsAudited(SupervisorResource.class, SupervisorResourceImpl.class);
    }

    private SupervisorResourceImpl resource(final Supplier<SecurityContext> securityContext) {
        final LearnerDocuments documents = Mockito.mock(LearnerDocuments.class);
        Mockito.when(documents.documentOf(learner(OURS))).thenReturn(Optional.of("doc-1"));
        Mockito.when(documents.documentOf(learner(THEIRS))).thenReturn(Optional.of("doc-2"));
        Mockito.when(documents.documentOf(learner(NEIGHBOUR))).thenReturn(Optional.of("doc-1"));
        Mockito.when(documents.documentOf(learner(UNNAMED))).thenReturn(Optional.empty());
        // A deleted pipeline names no Learning Flow; the learning pipelines list says which it is read under.
        Mockito.when(documents.documentOf(learner(GONE))).thenReturn(Optional.empty());
        Mockito.when(pipelines.documentOf(learner(GONE))).thenReturn(Optional.of("doc-1"));
        final DocFinder docFinder = Mockito.mock(DocFinder.class);
        Mockito.when(docFinder.decorate(Mockito.any())).thenAnswer(invocation -> invocation.getArgument(0));
        return new SupervisorResourceImpl(
                () -> attempts,
                () -> store,
                () -> null,
                () -> null,
                securityContext::get,
                () -> guidance,
                () -> null,
                () -> documents,
                () -> deletedPipelines,
                () -> learnerBindings,
                () -> pipelines,
                () -> docFinder);
    }

    // A person granted these permissions on these documents (by type and uuid), and none on any other
    private static Supplier<SecurityContext> grants(final Map<DocRef, Set<DocumentPermission>> granted) {
        final SecurityContext context = Mockito.mock(SecurityContext.class);
        Mockito.when(context.getUserIdentityForAudit()).thenReturn("jo");
        Mockito.when(context.hasDocumentPermission(Mockito.any(), Mockito.any())).thenAnswer(invocation -> {
            final DocRef asked = invocation.getArgument(0);
            final DocumentPermission permission = invocation.getArgument(1);
            return granted.entrySet().stream()
                    .anyMatch(entry -> entry.getKey().getType().equals(asked.getType())
                                       && entry.getKey().getUuid().equals(asked.getUuid())
                                       && entry.getValue().contains(permission));
        });
        return () -> context;
    }

    // Matches a collection holding exactly these, in any order
    private static ArgumentMatcher<Collection<String>> sameAs(final Set<String> expected) {
        return actual -> actual != null
                         && actual.size() == expected.size()
                         && Set.copyOf(actual).equals(expected);
    }

    private static DocRef pipeline(final LearnerRef ref) {
        return new DocRef(PipelineDoc.TYPE, ref.getPipelineUuid());
    }

    private static Learner learner(final LearnerRef ref) {
        return new Learner(ref.getPipelineUuid(), ref.getElementId());
    }
}
