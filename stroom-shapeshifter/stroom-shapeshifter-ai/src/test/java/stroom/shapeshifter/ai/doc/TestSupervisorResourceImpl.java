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
import stroom.shapeshifter.ai.stage.Guidance;
import stroom.shapeshifter.ai.stage.Learner;
import stroom.shapeshifter.ai.stage.LearningPipelines;
import stroom.shapeshifter.ai.state.InMemoryDeletedPipelines;
import stroom.shapeshifter.ai.state.InMemoryGuidance;
import stroom.shapeshifter.ai.state.InMemoryLearnerBindings;
import stroom.shapeshifter.shared.Binding;
import stroom.shapeshifter.shared.GuidanceRequest;
import stroom.shapeshifter.shared.ImproveRequest;
import stroom.shapeshifter.shared.LearnerRef;
import stroom.shapeshifter.shared.LearnerRequest;
import stroom.shapeshifter.shared.RejectRequest;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.shapeshifter.shared.SupervisorLearner;
import stroom.util.shared.PermissionException;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// A Supervisor request names a learner and a document separately (design 06 phase J slice 2b). The
/// learner must be one that runs under that document: otherwise a person could tell, read or relearn
/// another document's learner by naming it. What may be done to a learner is held to its pipeline's
/// permissions: a Data Provider who owns a pipeline acts on its learning without needing the document, and
/// the document's permission alone does nothing to a pipeline's learner (except read one whose pipeline
/// has been deleted, which has no permissions of its own to ask).
class TestSupervisorResourceImpl {

    private static final LearnerRef OURS = new LearnerRef("pipeline-1", "shapeshifterAi");
    private static final LearnerRef THEIRS = new LearnerRef("pipeline-2", "shapeshifterAi");
    private static final LearnerRef GONE = new LearnerRef("pipeline-3", "shapeshifterAi");

    private final InMemoryGuidance guidance = new InMemoryGuidance();
    private final InMemoryDeletedPipelines deletedPipelines = new InMemoryDeletedPipelines();
    private final InMemoryLearnerBindings learnerBindings = new InMemoryLearnerBindings();
    private final ShapeshifterAiStore store = Mockito.mock(ShapeshifterAiStore.class);
    private final LearningPipelines pipelines = Mockito.mock(LearningPipelines.class);
    private final SupervisorResourceImpl resource = resource(MockSecurityContext::new);

    @Test
    void guidanceIsGivenToALearnerOfTheDocument() {
        assertThat(resource.hint("doc-1", new GuidanceRequest(OURS, "Times are local.")))
                .singleElement()
                .satisfies(said -> assertThat(said.getMessage()).isEqualTo("Times are local."));
    }

    @Test
    void aLearnerOfAnotherDocumentIsRefused() {
        final long theirs = guidance.given(learner(THEIRS), "doc-2", "Said under doc-2.", "jo");

        assertThatThrownBy(() -> resource.hint("doc-1", new GuidanceRequest(THEIRS, "Planted.")))
                .describedAs("told something")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not run under this document");
        assertThatThrownBy(() -> resource.guidance("doc-1", THEIRS.getPipelineUuid(),
                THEIRS.getElementId()))
                .describedAs("read")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> resource.withdraw("doc-1", theirs, THEIRS.getPipelineUuid(),
                THEIRS.getElementId()))
                .describedAs("or read by way of withdrawing")
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(guidance.standing(learner(THEIRS)))
                .describedAs("and nothing planted on it")
                .extracting(Guidance.Given::message)
                .containsExactly("Said under doc-2.");
    }

    /// A deleted pipeline's learning is kept to be read until it ages out, and nothing more is done with it
    /// (design 06 A137): what was said to its learner can still be read from the document its bindings
    /// were kept under, though the pipeline names no document now, and nothing is said to it, taken back,
    /// improved, accepted or retracted.
    @Test
    void aDeletedPipelinesLearnerIsReadAndNotChanged() {
        final Learner gone = learner(GONE);
        learnerBindings.append(gone, "doc-1", Binding.builder()
                .uuid("rule-gone")
                .pipeline(new DocRef("Pipeline", "fragment-gone", "DOOR-ACCESS learned"))
                .build());
        final long said = guidance.given(gone, "doc-1", "Times are local.", "jo");
        deletedPipelines.deleted(GONE.getPipelineUuid(), 1_000L);

        assertThat(resource.guidance("doc-1", GONE.getPipelineUuid(), GONE.getElementId()))
                .describedAs("read, from the document it learned for")
                .singleElement()
                .satisfies(given -> assertThat(given.getMessage()).isEqualTo("Times are local."));
        assertThatThrownBy(() -> resource.guidance("doc-2", GONE.getPipelineUuid(), GONE.getElementId()))
                .describedAs("but not from another")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> resource.hint("doc-1", new GuidanceRequest(GONE, "And the year.")))
                .describedAs("told nothing")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("has been deleted");
        assertThatThrownBy(() -> resource.withdraw("doc-1", said, GONE.getPipelineUuid(),
                GONE.getElementId()))
                .describedAs("nothing taken back")
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> resource.improve("doc-1", "rule-gone", new ImproveRequest(null)))
                .describedAs("its binding not improved")
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> resource.accept("doc-1", "rule-gone"))
                .describedAs("accepted")
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> resource.retract("doc-1", "rule-gone", new RejectRequest("Gone.")))
                .describedAs("or retracted")
                .isInstanceOf(IllegalStateException.class);
        assertThat(guidance.standing(gone)).hasSize(1);
    }

    /// A person's give up and send back act on a learner of the document they may edit, whose pipeline
    /// is still there (design 06 A115, A137); giving up says why, since its streams error with the reason.
    @Test
    void aLearnerIsGivenUpOrSentBackOnlyWithCause() {
        assertThatThrownBy(() -> resource.giveUp("doc-1", new LearnerRequest(OURS, " ")))
                .describedAs("giving up says why")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("says why");
        assertThatThrownBy(() -> resource.giveUp("doc-1", new LearnerRequest(THEIRS, "Retired.")))
                .describedAs("not another document's learner")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not run under this document");
        assertThatThrownBy(() -> resource.relearnLearner("doc-1", new LearnerRequest(THEIRS, null)))
                .isInstanceOf(IllegalArgumentException.class);

        learnerBindings.append(learner(GONE), "doc-1", Binding.builder()
                .uuid("binding-gone")
                .pipeline(new DocRef("Pipeline", "fragment-gone", "DOOR-ACCESS learned"))
                .build());
        deletedPipelines.deleted(GONE.getPipelineUuid(), 1_000L);
        assertThatThrownBy(() -> resource.giveUp("doc-1", new LearnerRequest(GONE, "Retired.")))
                .describedAs("nor a deleted pipeline's")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("has been deleted");
    }

    /// A deleted pipeline's learner is read from the document it is listed under, whether or not it ever
    /// bound anything (A137): a learner that only had sessions was refused, its waiting streams and what
    /// had been said to it unreadable from its row.
    @Test
    void aDeletedPipelinesLearnerIsReadUnderTheDocumentItIsListedUnder() {
        deletedPipelines.deleted(GONE.getPipelineUuid(), 1_000L);

        assertThat(resource.guidance("doc-1", GONE.getPipelineUuid(), GONE.getElementId())).isEmpty();
        assertThatThrownBy(() -> resource.guidance("doc-2", GONE.getPipelineUuid(), GONE.getElementId()))
                .describedAs("and under no other")
                .isInstanceOf(IllegalArgumentException.class);
    }

    /// A Data Provider who may edit their pipeline acts on its learning without being able to see the
    /// document it learns under: tells it something, reads what was said, and reads the document's settings.
    @Test
    void thePipelinesEditorActsWithoutTheDocument() {
        final SupervisorResourceImpl provider = resource(grants(Map.of(
                pipeline(OURS), Set.of(DocumentPermission.VIEW, DocumentPermission.EDIT))));

        assertThat(provider.hint("doc-1", new GuidanceRequest(OURS, "Times are local.")))
                .singleElement()
                .satisfies(said -> assertThat(said.getMessage()).isEqualTo("Times are local."));
        assertThat(provider.guidance("doc-1", OURS.getPipelineUuid(), OURS.getElementId())).hasSize(1);
        Mockito.when(store.readDocument(new DocRef(ShapeshifterAiDoc.TYPE, "doc-1")))
                .thenReturn(ShapeshifterAiDoc.builder().uuid("doc-1").name("Firewall").build());
        assertThat(provider.settings("doc-1", OURS.getPipelineUuid(), OURS.getElementId()).getName())
                .isEqualTo("Firewall");
        // But not another pipeline's learner, under the same document or another
        assertThatThrownBy(() -> provider.guidance("doc-2", THEIRS.getPipelineUuid(), THEIRS.getElementId()))
                .isInstanceOf(PermissionException.class);
    }

    /// The document's permission alone does nothing to a pipeline's learner: the pipeline's owner decides
    /// what its feed is told, approved and relearned with.
    @Test
    void theDocumentAloneIsNotEnough() {
        final SupervisorResourceImpl owner = resource(grants(Map.of(
                new DocRef(ShapeshifterAiDoc.TYPE, "doc-1"),
                Set.of(DocumentPermission.VIEW, DocumentPermission.EDIT))));

        assertThatThrownBy(() -> owner.hint("doc-1", new GuidanceRequest(OURS, "Planted.")))
                .describedAs("told something")
                .isInstanceOf(PermissionException.class);
        assertThatThrownBy(() -> owner.guidance("doc-1", OURS.getPipelineUuid(), OURS.getElementId()))
                .describedAs("read")
                .isInstanceOf(PermissionException.class);
        assertThatThrownBy(() -> owner.settings("doc-1", OURS.getPipelineUuid(), OURS.getElementId()))
                .describedAs("or its settings read from the pipeline's side")
                .isInstanceOf(PermissionException.class);
        assertThatThrownBy(() -> owner.pipelineLearners(OURS.getPipelineUuid()))
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

        assertThat(viewer.guidance("doc-1", OURS.getPipelineUuid(), OURS.getElementId())).hasSize(1);
        assertThatThrownBy(() -> viewer.hint("doc-1", new GuidanceRequest(OURS, "And the year.")))
                .describedAs("told nothing")
                .isInstanceOf(PermissionException.class);
        assertThatThrownBy(() -> viewer.withdraw("doc-1", said, OURS.getPipelineUuid(), OURS.getElementId()))
                .describedAs("nothing taken back")
                .isInstanceOf(PermissionException.class);
        assertThatThrownBy(() -> viewer.giveUp("doc-1", new LearnerRequest(OURS, "Retired.")))
                .describedAs("not given up")
                .isInstanceOf(PermissionException.class);
        assertThatThrownBy(() -> viewer.accept("doc-1", "rule-1"))
                .describedAs("its translation not accepted")
                .isInstanceOf(PermissionException.class);
        assertThatThrownBy(() -> viewer.retract("doc-1", "rule-1", new RejectRequest("Wrong.")))
                .describedAs("or taken back")
                .isInstanceOf(PermissionException.class);
        assertThat(guidance.standing(learner(OURS))).hasSize(1);
    }

    /// A binding is acted on only under the document its learner runs under, and one that isn't there
    /// answers as one that may not be changed.
    @Test
    void aBindingIsChangedOnlyUnderItsOwnDocument() {
        learnerBindings.append(learner(OURS), "doc-1", Binding.builder()
                .uuid("rule-1")
                .pipeline(new DocRef("Pipeline", "fragment-1", "FIREWALL learned"))
                .build());

        assertThatThrownBy(() -> resource.accept("doc-2", "rule-1"))
                .isInstanceOf(PermissionException.class);
        assertThatThrownBy(() -> resource.accept("doc-1", "no-such-binding"))
                .isInstanceOf(PermissionException.class);
    }

    /// A deleted pipeline has no permissions to ask: its learning is read by its document's VIEW, and
    /// without that, not at all.
    @Test
    void aDeletedPipelinesLearningIsReadByItsDocument() {
        deletedPipelines.deleted(GONE.getPipelineUuid(), 1_000L);
        final SupervisorResourceImpl reader = resource(grants(Map.of(
                new DocRef(ShapeshifterAiDoc.TYPE, "doc-1"), Set.of(DocumentPermission.VIEW))));
        final SupervisorResourceImpl stranger = resource(grants(Map.of()));

        assertThat(reader.guidance("doc-1", GONE.getPipelineUuid(), GONE.getElementId())).isEmpty();
        assertThatThrownBy(() -> stranger.guidance("doc-1", GONE.getPipelineUuid(), GONE.getElementId()))
                .isInstanceOf(PermissionException.class);
    }

    /// A pipeline's own editor lists its learners, under any document, by asking for that pipeline alone.
    @Test
    void aPipelinesLearnersAreListedForItsViewer() {
        Mockito.when(store.listDocuments()).thenReturn(Set.of(new DocRef(ShapeshifterAiDoc.TYPE, "doc-1",
                "Firewall")));
        Mockito.when(store.readDocument(new DocRef(ShapeshifterAiDoc.TYPE, "doc-1", "Firewall")))
                .thenReturn(ShapeshifterAiDoc.builder().uuid("doc-1").name("Firewall").build());
        Mockito.when(pipelines.learners(Mockito.anyCollection(), Mockito.anySet(), Mockito.anyCollection(),
                        Mockito.anyBoolean(), Mockito.eq(OURS.getPipelineUuid()), Mockito.anyLong(),
                        Mockito.anyInt()))
                .thenReturn(new LearningPipelines.Page(List.of(), 0L));
        final SupervisorResourceImpl viewer = resource(grants(Map.of(
                pipeline(OURS), Set.of(DocumentPermission.VIEW))));

        final List<SupervisorLearner> listed = viewer.pipelineLearners(OURS.getPipelineUuid());

        assertThat(listed).isEmpty();
        // Under every document, not only those the viewer may see
        Mockito.verify(pipelines).learners(Mockito.argThat(docs -> docs.contains("doc-1")),
                Mockito.anySet(), Mockito.anyCollection(), Mockito.eq(false),
                Mockito.eq(OURS.getPipelineUuid()), Mockito.eq(0L), Mockito.anyInt());
    }

    private SupervisorResourceImpl resource(final Supplier<SecurityContext> securityContext) {
        final LearnerDocuments documents = Mockito.mock(LearnerDocuments.class);
        Mockito.when(documents.documentOf(learner(OURS))).thenReturn(Optional.of("doc-1"));
        Mockito.when(documents.documentOf(learner(THEIRS))).thenReturn(Optional.of("doc-2"));
        // A deleted pipeline names no document; the learning pipelines list says which it is read under.
        Mockito.when(documents.documentOf(learner(GONE))).thenReturn(Optional.empty());
        Mockito.when(pipelines.documentOf(learner(GONE))).thenReturn(Optional.of("doc-1"));
        final DocFinder docFinder = Mockito.mock(DocFinder.class);
        Mockito.when(docFinder.decorate(Mockito.any())).thenAnswer(invocation -> invocation.getArgument(0));
        return new SupervisorResourceImpl(
                () -> null,
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

    private static DocRef pipeline(final LearnerRef ref) {
        return new DocRef(PipelineDoc.TYPE, ref.getPipelineUuid());
    }

    private static Learner learner(final LearnerRef ref) {
        return new Learner(ref.getPipelineUuid(), ref.getElementId());
    }
}
