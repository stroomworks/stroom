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


package stroom.learningflow.doc;

import stroom.docref.DocRef;
import stroom.docstore.api.DocFinder;
import stroom.learningflow.element.LearnerDocuments;
import stroom.learningflow.element.PipelineZones;
import stroom.learningflow.element.StageFactory;
import stroom.learningflow.learning.TargetSchema;
import stroom.learningflow.learning.TargetSchemas;
import stroom.learningflow.shared.Binding;
import stroom.learningflow.shared.GuidanceRequest;
import stroom.learningflow.shared.ImproveRequest;
import stroom.learningflow.shared.LearnerRef;
import stroom.learningflow.shared.LearnerRequest;
import stroom.learningflow.shared.LearnerState;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.shared.PromotionMode;
import stroom.learningflow.shared.QuestionKind;
import stroom.learningflow.shared.RejectRequest;
import stroom.learningflow.shared.StepOutcome;
import stroom.learningflow.shared.SupervisorLearner;
import stroom.learningflow.shared.TimeZoneSource;
import stroom.learningflow.stage.Attempts;
import stroom.learningflow.stage.Guidance;
import stroom.learningflow.stage.Inputs;
import stroom.learningflow.stage.Learner;
import stroom.learningflow.stage.LearningPipelines;
import stroom.learningflow.stage.SpendBreaker;
import stroom.learningflow.state.InMemoryAttempts;
import stroom.learningflow.state.InMemoryDeletedPipelines;
import stroom.learningflow.state.InMemoryGuidance;
import stroom.learningflow.state.InMemoryLearnerBindings;
import stroom.learningflow.state.InMemorySpend;
import stroom.pipeline.shared.PipelineDoc;
import stroom.security.api.SecurityContext;
import stroom.security.mock.MockSecurityContext;
import stroom.security.shared.AppPermission;
import stroom.security.shared.DocumentPermission;
import stroom.util.shared.PermissionException;
import stroom.xmlschema.shared.XmlSchemaDoc;

import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Tests the access rules of the service behind the Supervisor screen.
///
/// A Supervisor request names a learner and a document separately, so the service checks that the learner runs
/// under that document; otherwise a person could give guidance to, read or relearn another document's learner
/// just by naming it. What may be done to a learner is governed by its pipeline's permissions: a Data Provider
/// who owns a pipeline can act on its learning without access to the document, and permission on the document
/// alone gives no access to a pipeline's learner. The exception is a learner whose pipeline has been deleted,
/// which has no permissions of its own, so it is readable through the document's permission.
class TestSupervisorService {

    private static final LearnerRef OURS = new LearnerRef("pipeline-1", "learningFlow");
    private static final LearnerRef THEIRS = new LearnerRef("pipeline-2", "learningFlow");
    private static final LearnerRef GONE = new LearnerRef("pipeline-3", "learningFlow");

    private final InMemoryGuidance guidance = new InMemoryGuidance();
    private final InMemoryDeletedPipelines deletedPipelines = new InMemoryDeletedPipelines();
    private final InMemoryLearnerBindings learnerBindings = new InMemoryLearnerBindings();
    private final LearningFlowStore store = Mockito.mock(LearningFlowStore.class);
    private final LearningPipelines pipelines = Mockito.mock(LearningPipelines.class);
    // What every document has spent; a test sets a spend ceiling on a document when it wants the breaker tripped
    private final InMemorySpend spend = new InMemorySpend();
    private final StageFactory stageFactory = Mockito.mock(StageFactory.class);
    // The streams that sessions learned from; none is readable unless a test says otherwise
    private final Inputs inputs = Mockito.mock(Inputs.class);
    private final InMemoryAttempts attempts = new InMemoryAttempts();
    // No target schema resolves unless a test sets one
    private TargetSchemas targetSchemas = TargetSchemas.none();
    private final SupervisorService resource = resource(MockSecurityContext::new);

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

    /// A deleted pipeline's learning is kept, read only, until it ages out. The guidance given to its learner
    /// can still be read under the document its bindings were kept under, even though the deleted pipeline no
    /// longer names a document. No new guidance can be given, and nothing can be withdrawn, improved or
    /// retracted.
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
        assertThatThrownBy(() -> resource.retract("doc-1", "rule-gone", new RejectRequest("Gone.")))
                .describedAs("or retracted")
                .isInstanceOf(IllegalStateException.class);
        assertThat(guidance.standing(gone)).hasSize(1);
    }

    /// A person can give up or send back only a learner of the given document whose pipeline still exists.
    /// Giving up must state a reason, because the learner's streams then fail with that reason as the error.
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

    /// A deleted pipeline's learner is readable under the document it is listed under, even if it never had a
    /// binding. A learner that only ever had sessions must still be readable, so that its waiting streams and
    /// the guidance given to it can be seen from its row.
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
        final SupervisorService provider = resource(grants(Map.of(
                pipeline(OURS), Set.of(DocumentPermission.VIEW, DocumentPermission.EDIT))));

        assertThat(provider.hint("doc-1", new GuidanceRequest(OURS, "Times are local.")))
                .singleElement()
                .satisfies(said -> assertThat(said.getMessage()).isEqualTo("Times are local."));
        assertThat(provider.guidance("doc-1", OURS.getPipelineUuid(), OURS.getElementId())).hasSize(1);
        Mockito.when(store.readDocument(new DocRef(LearningFlowDoc.TYPE, "doc-1")))
                .thenReturn(LearningFlowDoc.builder().uuid("doc-1").name("Firewall").build());
        assertThat(provider.settings("doc-1", OURS.getPipelineUuid(), OURS.getElementId()).getName())
                .isEqualTo("Firewall");
        // The pipeline's permission does not extend to another pipeline's learner
        assertThatThrownBy(() -> provider.guidance("doc-2", THEIRS.getPipelineUuid(), THEIRS.getElementId()))
                .isInstanceOf(PermissionException.class);
    }

    /// Permission on the document alone gives no access to a pipeline's learner, because it is the pipeline's
    /// owner who decides what guidance its feed is given and what it approves and relearns.
    @Test
    void theDocumentAloneIsNotEnough() {
        final SupervisorService owner = resource(grants(Map.of(
                new DocRef(LearningFlowDoc.TYPE, "doc-1"),
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
        final SupervisorService viewer = resource(grants(Map.of(
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

        assertThatThrownBy(() -> resource.improve("doc-2", "rule-1", new ImproveRequest(null)))
                .isInstanceOf(PermissionException.class);
        assertThatThrownBy(() -> resource.improve("doc-1", "no-such-binding", new ImproveRequest(null)))
                .isInstanceOf(PermissionException.class);
    }

    /// A deleted pipeline has no permissions to check, so its learning is readable by anyone with VIEW on its
    /// document, and by nobody else.
    @Test
    void aDeletedPipelinesLearningIsReadByItsDocument() {
        deletedPipelines.deleted(GONE.getPipelineUuid(), 1_000L);
        final SupervisorService reader = resource(grants(Map.of(
                new DocRef(LearningFlowDoc.TYPE, "doc-1"), Set.of(DocumentPermission.VIEW))));
        final SupervisorService stranger = resource(grants(Map.of()));

        assertThat(reader.guidance("doc-1", GONE.getPipelineUuid(), GONE.getElementId())).isEmpty();
        assertThatThrownBy(() -> stranger.guidance("doc-1", GONE.getPipelineUuid(), GONE.getElementId()))
                .isInstanceOf(PermissionException.class);
    }

    /// Someone who can view a pipeline can list its learners under every document, by asking for that pipeline
    /// alone, even documents they cannot see.
    @Test
    void aPipelinesLearnersAreListedForItsViewer() {
        Mockito.when(store.listDocuments()).thenReturn(Set.of(new DocRef(LearningFlowDoc.TYPE, "doc-1",
                "Firewall")));
        Mockito.when(store.readDocument(new DocRef(LearningFlowDoc.TYPE, "doc-1", "Firewall")))
                .thenReturn(LearningFlowDoc.builder().uuid("doc-1").name("Firewall").build());
        Mockito.when(pipelines.learners(Mockito.anyCollection(), Mockito.anySet(), Mockito.anyCollection(),
                        Mockito.anyBoolean(), Mockito.eq(OURS.getPipelineUuid()), Mockito.anyLong(),
                        Mockito.anyInt()))
                .thenReturn(new LearningPipelines.Page(List.of(), 0L));
        final SupervisorService viewer = resource(grants(Map.of(
                pipeline(OURS), Set.of(DocumentPermission.VIEW))));

        final List<SupervisorLearner> listed = viewer.pipelineLearners(OURS.getPipelineUuid());

        assertThat(listed).isEmpty();
        // Every document is searched, not only those the viewer may see
        Mockito.verify(pipelines).learners(Mockito.argThat(docs -> docs.contains("doc-1")),
                Mockito.anySet(), Mockito.anyCollection(), Mockito.eq(false),
                Mockito.eq(OURS.getPipelineUuid()), Mockito.eq(0L), Mockito.anyInt());
    }

    /// *Finish with Unknowns* writes event-logging's marked `Unknown` event, so a learner whose document
    /// targets another schema is listed as having no fallback, and the client does not offer it.
    @Test
    void aLearnerWhoseDocumentTargetsAnotherSchemaHasNoFallback() {
        final DocRef sightingsRef = new DocRef(XmlSchemaDoc.TYPE, "schema-1", "sightings v2.4");
        final DocRef ours = new DocRef(LearningFlowDoc.TYPE, "doc-1", "ours");
        final DocRef theirs = new DocRef(LearningFlowDoc.TYPE, "doc-2", "theirs");
        Mockito.when(store.listDocuments()).thenReturn(Set.of(ours, theirs));
        Mockito.when(store.readDocument(ours)).thenReturn(LearningFlowDoc.builder().uuid("doc-1").name("ours")
                .targetSchema(sightingsRef).build());
        Mockito.when(store.readDocument(theirs)).thenReturn(LearningFlowDoc.builder().uuid("doc-2")
                .name("theirs").build());
        targetSchemas = new TargetSchemas() {
            @Override
            public Optional<TargetSchema> resolve(final DocRef docRef) {
                return sightingsRef.equals(docRef)
                        ? Optional.of(new TargetSchema(sightingsRef, "sightings v2.4", "urn:sightings:2",
                        "file://sightings-v2.4.xsd", "2.4", "Sightings", null))
                        : Optional.empty();
            }

            @Override
            public Optional<TargetSchema> latestEventLogging() {
                return Optional.empty();
            }
        };
        Mockito.when(pipelines.learners(Mockito.anyCollection(), Mockito.anySet(), Mockito.anyCollection(),
                        Mockito.anyBoolean(), Mockito.eq(OURS.getPipelineUuid()), Mockito.anyLong(),
                        Mockito.anyInt()))
                .thenReturn(new LearningPipelines.Page(List.of(row(OURS, "doc-1"), row(THEIRS, "doc-2")), 2));
        final SupervisorService viewer = resource(grants(Map.of(
                pipeline(OURS), Set.of(DocumentPermission.VIEW))));

        assertThat(viewer.pipelineLearners(OURS.getPipelineUuid()))
                .extracting(learner -> learner.getDoc().getUuid(), SupervisorLearner::isFallbackUnavailable)
                .containsExactly(Tuple.tuple("doc-1", true), Tuple.tuple("doc-2", false));
    }

    /// A learner whose document has reached its spend ceiling is listed with the spend breaker's message, and
    /// one whose document has not is listed with no message. Also checks a pipeline with no time zone of its
    /// own is listed as UTC.
    @Test
    void aLearnerWhoseDocumentHasReachedItsSpendCeilingSaysSo() {
        final DocRef ours = new DocRef(LearningFlowDoc.TYPE, "doc-1", "ours");
        final DocRef theirs = new DocRef(LearningFlowDoc.TYPE, "doc-2", "theirs");
        Mockito.when(store.listDocuments()).thenReturn(Set.of(ours, theirs));
        Mockito.when(store.readDocument(ours)).thenReturn(LearningFlowDoc.builder().uuid("doc-1").name("ours")
                .spendTokensPerHour(1_000L).build());
        Mockito.when(store.readDocument(theirs)).thenReturn(LearningFlowDoc.builder().uuid("doc-2")
                .name("theirs").build());
        spend.record("doc-1", 1_500L, 3, SpendBreaker.WINDOW_MS);
        Mockito.when(pipelines.learners(Mockito.anyCollection(), Mockito.anySet(), Mockito.anyCollection(),
                        Mockito.anyBoolean(), Mockito.eq(OURS.getPipelineUuid()), Mockito.anyLong(),
                        Mockito.anyInt()))
                .thenReturn(new LearningPipelines.Page(List.of(row(OURS, "doc-1"), row(THEIRS, "doc-2")), 2));
        final SupervisorService viewer = resource(grants(Map.of(
                pipeline(OURS), Set.of(DocumentPermission.VIEW))));

        final List<SupervisorLearner> listed = viewer.pipelineLearners(OURS.getPipelineUuid());

        assertThat(listed.get(0).getSpendPaused()).startsWith("This Learning Flow spent 1500 token(s)");
        assertThat(listed.get(1).getSpendPaused()).isNull();
        assertThat(listed.get(0).getTimeZone()).describedAs("a pipeline setting no zone reads UTC").isEqualTo("UTC");
        assertThat(listed.get(0).getTimeZoneSource()).isEqualTo(TimeZoneSource.DEFAULT);
    }

    /// A turn's prompt is rebuilt on request from the recorded templates and the stream it learned from. The
    /// prompt contains the stream's own text, so it is refused to someone without permission to view data. If
    /// the stream has since been deleted the prompt cannot be rebuilt, and the service says so rather than
    /// guessing at it.
    @Test
    void aPromptIsRefusedWithoutViewDataOrWithoutItsStream() {
        final long id = attemptWithATurn();
        final SecurityContext noData = Mockito.mock(SecurityContext.class);
        Mockito.when(noData.hasDocumentPermission(Mockito.any(), Mockito.any())).thenReturn(true);
        Mockito.when(noData.hasAppPermission(AppPermission.VIEW_DATA_PERMISSION)).thenReturn(false);

        assertThat(resource(() -> noData).prompt(id, 1).getRefusal())
                .contains("only to someone who may view data");
        assertThat(resource.prompt(id, 1).getRefusal())
                .describedAs("the stream it learned from is gone")
                .contains("has been deleted");
        assertThatThrownBy(() -> resource.prompt(id, 9)).isInstanceOf(IllegalArgumentException.class);
    }

    // Opens an attempt for our learner, on stream 1, with one answered turn
    private long attemptWithATurn() {
        final long id = attempts.opened(new Attempts.Attempt(learner(OURS), "doc-1", "F", "T", 1L, "node-1",
                PromotionMode.AUTOMATIC, Long.MAX_VALUE), 0L).orElseThrow();
        attempts.turn(id, new Attempts.Turn(1, "chain", 1, QuestionKind.CHAIN, "Chain: choose",
                "DSParser -> XSLTFilter", "model", StepOutcome.PASSED, null, List.of()));
        return id;
    }

    private static SupervisorLearner row(final LearnerRef learner, final String docUuid) {
        return new SupervisorLearner(learner, new DocRef("Pipeline", learner.getPipelineUuid()),
                new DocRef(LearningFlowDoc.TYPE, docUuid), LearnerState.LEARNING, null, null, null, null,
                null, 0, 0, null, false, null, 0, false, false);
    }

    private SupervisorService resource(final Supplier<SecurityContext> securityContext) {
        final LearnerDocuments documents = Mockito.mock(LearnerDocuments.class);
        Mockito.when(documents.documentOf(learner(OURS))).thenReturn(Optional.of("doc-1"));
        Mockito.when(documents.documentOf(learner(THEIRS))).thenReturn(Optional.of("doc-2"));
        // A deleted pipeline names no document, so the learning pipelines table says which one it is read under.
        Mockito.when(documents.documentOf(learner(GONE))).thenReturn(Optional.empty());
        Mockito.when(pipelines.documentOf(learner(GONE))).thenReturn(Optional.of("doc-1"));
        final DocFinder docFinder = Mockito.mock(DocFinder.class);
        Mockito.when(docFinder.decorate(Mockito.any())).thenAnswer(invocation -> invocation.getArgument(0));
        Mockito.when(stageFactory.breaker()).thenAnswer(invocation -> new SpendBreaker(spend, 0L, 0));
        return new SupervisorService(
                () -> attempts,
                () -> store,
                () -> stageFactory,
                () -> null,
                securityContext::get,
                () -> guidance,
                () -> null,
                new LearnerAccess(securityContext::get, () -> deletedPipelines, () -> documents, () -> pipelines),
                () -> deletedPipelines,
                () -> learnerBindings,
                () -> pipelines,
                () -> docFinder,
                () -> targetSchemas,
                () -> new PipelineZones(documents),
                () -> inputs);
    }

    // A user with these permissions on these documents (matched by type and UUID), and none on any other
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
