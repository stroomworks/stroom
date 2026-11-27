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
import stroom.learningflow.shared.AttemptCriteria;
import stroom.learningflow.shared.Binding;
import stroom.learningflow.shared.GrantRequest;
import stroom.learningflow.shared.GuidanceRequest;
import stroom.learningflow.shared.ImproveRequest;
import stroom.learningflow.shared.LearnerCriteria;
import stroom.learningflow.shared.LearnerRef;
import stroom.learningflow.shared.LearnerState;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.shared.PromotionMode;
import stroom.learningflow.shared.QuestionKind;
import stroom.learningflow.shared.ReasonRequest;
import stroom.learningflow.shared.StepOutcome;
import stroom.learningflow.shared.SupervisorGuidance;
import stroom.learningflow.shared.SupervisorLearner;
import stroom.learningflow.shared.SupervisorResource;
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

/// Tests the access rules of the service behind the Smart Pipelines screen and a pipeline's Learning Status
/// tab.
///
/// A request names a learner by its pipeline and element alone; the Learning Flow it runs under is the one its
/// element names. What may be done to a learner is governed by its pipeline's permissions: a Data Provider who
/// owns a pipeline can act on its learning without access to the Learning Flow, and permission on the Learning
/// Flow alone gives no access to a pipeline's learner. The exception is a learner whose pipeline has been
/// deleted, which has no permissions of its own, so it is readable through the Learning Flow's permission.
class TestSupervisorService {

    private static final LearnerRef OURS = new LearnerRef("pipeline-1", "learningFlow");
    private static final LearnerRef THEIRS = new LearnerRef("pipeline-2", "learningFlow");
    private static final LearnerRef GONE = new LearnerRef("pipeline-3", "learningFlow");
    // Another pipeline's learner, under the same Learning Flow as OURS
    private static final LearnerRef NEIGHBOUR = new LearnerRef("pipeline-4", "learningFlow");
    // A learner whose element names no Learning Flow
    private static final LearnerRef UNNAMED = new LearnerRef("pipeline-5", "learningFlow");

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

    /// A deleted pipeline's learning is kept to be read until it ages out, and nothing more is done with it:
    /// what was said to its learner can still be read, though the pipeline names no Learning Flow now, and
    /// nothing is said to it, withdrawn, improved or retracted.
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
                .describedAs("nothing withdrawn")
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> resource.improve("rule-gone", new ImproveRequest(null)))
                .describedAs("its binding not improved")
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> resource.retract("rule-gone", new ReasonRequest("Gone.")))
                .describedAs("or retracted")
                .isInstanceOf(IllegalStateException.class);
        assertThat(guidance.standing(gone)).hasSize(1);
    }

    /// Giving a learner up must state a reason, because its streams then fail with that reason as the error;
    /// and nothing is done to a deleted pipeline's learner.
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

    /// A deleted pipeline has no permissions to check: its learning is read by the VIEW of the Learning Flow
    /// it is listed under, whether or not it ever bound anything, and without that, not at all.
    @Test
    void aDeletedPipelinesLearningIsReadByTheLearningFlowItIsListedUnder() {
        deletedPipelines.deleted(GONE.getPipelineUuid(), 1_000L);
        final SupervisorService reader = resource(grants(Map.of(
                new DocRef(LearningFlowDoc.TYPE, "doc-1"), Set.of(DocumentPermission.VIEW))));
        final SupervisorService another = resource(grants(Map.of(
                new DocRef(LearningFlowDoc.TYPE, "doc-2"), Set.of(DocumentPermission.VIEW))));

        assertThat(reader.fetchGuidance(GONE.getPipelineUuid(), GONE.getElementId())).isEmpty();
        assertThatThrownBy(() -> another.fetchGuidance(GONE.getPipelineUuid(), GONE.getElementId()))
                .describedAs("not by another Learning Flow's")
                .isInstanceOf(PermissionException.class);
    }

    /// A Data Provider who may edit their pipeline acts on its learning without being able to see the
    /// Learning Flow it learns under: tells it something, reads what was said, and reads its settings.
    @Test
    void thePipelinesEditorActsWithoutTheLearningFlow() {
        final SupervisorService provider = resource(grants(Map.of(
                pipeline(OURS), Set.of(DocumentPermission.VIEW, DocumentPermission.EDIT))));

        assertThat(provider.addGuidance(OURS.getPipelineUuid(), OURS.getElementId(),
                new GuidanceRequest("Times are local.")))
                .singleElement()
                .satisfies(said -> assertThat(said.getMessage()).isEqualTo("Times are local."));
        assertThat(provider.fetchGuidance(OURS.getPipelineUuid(), OURS.getElementId())).hasSize(1);
        Mockito.when(store.readDocument(new DocRef(LearningFlowDoc.TYPE, "doc-1")))
                .thenReturn(LearningFlowDoc.builder().uuid("doc-1").name("Firewall").build());
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
        final SupervisorService owner = resource(grants(Map.of(
                new DocRef(LearningFlowDoc.TYPE, "doc-1"),
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
        final SupervisorService viewer = resource(grants(Map.of(
                pipeline(OURS), Set.of(DocumentPermission.VIEW))));

        assertThat(viewer.fetchGuidance(OURS.getPipelineUuid(), OURS.getElementId())).hasSize(1);
        assertThatThrownBy(() -> viewer.addGuidance(OURS.getPipelineUuid(), OURS.getElementId(),
                new GuidanceRequest("And the year.")))
                .describedAs("told nothing")
                .isInstanceOf(PermissionException.class);
        assertThatThrownBy(() -> viewer.deleteGuidance(OURS.getPipelineUuid(), OURS.getElementId(), said))
                .describedAs("nothing withdrawn")
                .isInstanceOf(PermissionException.class);
        assertThatThrownBy(() -> viewer.giveUp(OURS.getPipelineUuid(), OURS.getElementId(),
                new ReasonRequest("Retired.")))
                .describedAs("not given up")
                .isInstanceOf(PermissionException.class);
        assertThatThrownBy(() -> viewer.improve("rule-1", new ImproveRequest(null)))
                .describedAs("its translation not improved")
                .isInstanceOf(PermissionException.class);
        assertThatThrownBy(() -> viewer.retract("rule-1", new ReasonRequest("Wrong.")))
                .describedAs("or retracted")
                .isInstanceOf(PermissionException.class);
        assertThat(guidance.standing(learner(OURS))).hasSize(1);
    }

    /// A binding someone may not read, one whose pipeline has since been deleted, and one that isn't there
    /// all get the same answer, or the answer would reveal which bindings exist.
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
        final SupervisorService stranger = resource(grants(Map.of()));

        assertThatThrownBy(() -> stranger.retract("rule-1", new ReasonRequest("Wrong.")))
                .isInstanceOf(PermissionException.class)
                .hasMessageContaining("permission to change binding");
        assertThatThrownBy(() -> stranger.retract("rule-gone", new ReasonRequest("Wrong.")))
                .describedAs("not told it was deleted")
                .isInstanceOf(PermissionException.class)
                .hasMessageContaining("permission to change binding");
        assertThatThrownBy(() -> resource.retract("no-such-binding", new ReasonRequest("Wrong.")))
                .isInstanceOf(PermissionException.class)
                .hasMessageContaining("permission to change binding");
    }

    /// Permission to change a learner is its own pipeline's, and several pipelines' learners may run under one
    /// Learning Flow: a person who may edit one pipeline cannot withdraw guidance left for another's learner
    /// by naming its id beside their own learner.
    @Test
    void aHintIsTakenBackOnlyFromTheLearnerItWasSaidTo() {
        final long theirs = guidance.given(learner(NEIGHBOUR), "doc-1", "Said to the neighbour.", "sam");
        final long ours = guidance.given(learner(OURS), "doc-1", "Said to ours.", "jo");
        final SupervisorService provider = resource(grants(Map.of(
                pipeline(OURS), Set.of(DocumentPermission.VIEW, DocumentPermission.EDIT))));

        assertThat(provider.deleteGuidance(OURS.getPipelineUuid(), OURS.getElementId(), theirs))
                .describedAs("what was said to their own learner is unchanged")
                .extracting(SupervisorGuidance::getId)
                .containsExactly(ours);
        assertThat(guidance.standing(learner(NEIGHBOUR)))
                .describedAs("and the neighbour's guidance still stands")
                .extracting(Guidance.Given::message)
                .containsExactly("Said to the neighbour.");

        assertThat(provider.deleteGuidance(OURS.getPipelineUuid(), OURS.getElementId(), ours))
                .describedAs("while their own is withdrawn")
                .isEmpty();
    }

    /// A pipeline's own editor lists its learners, under any Learning Flow, by asking for that pipeline alone.
    @Test
    void aPipelinesLearnersAreListedForItsViewer() {
        Mockito.when(store.listDocuments()).thenReturn(Set.of(new DocRef(LearningFlowDoc.TYPE, "doc-1",
                "Firewall")));
        Mockito.when(store.readDocument(new DocRef(LearningFlowDoc.TYPE, "doc-1", "Firewall")))
                .thenReturn(LearningFlowDoc.builder().uuid("doc-1").name("Firewall").build());
        Mockito.when(pipelines.learners(Mockito.anyCollection(), Mockito.anySet(), Mockito.anyCollection(),
                        Mockito.anyBoolean(), Mockito.anyCollection(), Mockito.anyLong(), Mockito.anyInt()))
                .thenReturn(new LearningPipelines.Page(List.of(), 0L));
        final SupervisorService viewer = resource(grants(Map.of(
                pipeline(OURS), Set.of(DocumentPermission.VIEW))));

        final List<SupervisorLearner> listed = viewer.fetchPipelineLearners(OURS.getPipelineUuid());

        assertThat(listed).isEmpty();
        // Every Learning Flow is searched, not only those the viewer may see
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
        final Attempts found = Mockito.mock(Attempts.class);
        Mockito.when(store.listDocuments()).thenReturn(Set.of(new DocRef(LearningFlowDoc.TYPE, "doc-1"),
                new DocRef(LearningFlowDoc.TYPE, "doc-2")));
        Mockito.when(pipelines.pipelines()).thenReturn(Set.of(OURS.getPipelineUuid(),
                NEIGHBOUR.getPipelineUuid(), GONE.getPipelineUuid()));
        Mockito.when(pipelines.learners(Mockito.anyCollection(), Mockito.anySet(), Mockito.anyCollection(),
                        Mockito.anyBoolean(), Mockito.anyCollection(), Mockito.anyLong(), Mockito.anyInt()))
                .thenReturn(new LearningPipelines.Page(List.of(), 0L));
        Mockito.when(found.found(Mockito.any(), Mockito.anyCollection(), Mockito.anyCollection()))
                .thenReturn(new Attempts.Page(List.of(), 0L));
        deletedPipelines.deleted(GONE.getPipelineUuid(), 1_000L);
        final SupervisorService reader = resource(grants(Map.of(
                new DocRef(LearningFlowDoc.TYPE, "doc-1"), Set.of(DocumentPermission.VIEW),
                pipeline(OURS), Set.of(DocumentPermission.VIEW))), found);
        final Set<String> viewable = Set.of(OURS.getPipelineUuid(), GONE.getPipelineUuid());

        reader.findLearners(new LearnerCriteria());
        reader.findAttempts(new AttemptCriteria());

        Mockito.verify(pipelines).learners(Mockito.eq(Set.of("doc-1")), Mockito.anySet(),
                Mockito.anyCollection(), Mockito.anyBoolean(), Mockito.argThat(sameAs(viewable)),
                Mockito.anyLong(), Mockito.anyInt());
        Mockito.verify(found).found(Mockito.any(), Mockito.eq(List.of("doc-1")),
                Mockito.argThat(sameAs(viewable)));
    }

    /// A session is history and keeps its Learning Flow, but acting on it changes its learner, with its
    /// Learning Flow's settings: once the pipeline names another Learning Flow, it is acted on from there.
    @Test
    void anAttemptIsActedOnOnlyWhileItsPipelineRunsUnderItsLearningFlow() {
        // Pipeline 1 now names doc-1; this session was learning for it under doc-2
        final long moved = attempts.opened(new Attempts.Attempt(learner(OURS), "doc-2", "FIREWALL", "Raw Events",
                1L, "node-1", PromotionMode.REVIEW, 0L), 0L).orElseThrow();

        assertThatThrownBy(() -> resource.approve(moved))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no longer runs under its Learning Flow");
        assertThatThrownBy(() -> resource.reject(moved, new ReasonRequest("Wrong.")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> resource.grant(moved, new GrantRequest(1_000L, 1_000L)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> resource.exhaust(moved))
                .isInstanceOf(IllegalStateException.class);
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
                        Mockito.anyBoolean(), Mockito.eq(List.of(OURS.getPipelineUuid())), Mockito.anyLong(),
                        Mockito.anyInt()))
                .thenReturn(new LearningPipelines.Page(List.of(row(OURS, "doc-1"), row(THEIRS, "doc-2")), 2));
        final SupervisorService viewer = resource(grants(Map.of(
                pipeline(OURS), Set.of(DocumentPermission.VIEW))));

        assertThat(viewer.fetchPipelineLearners(OURS.getPipelineUuid()))
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
                        Mockito.anyBoolean(), Mockito.eq(List.of(OURS.getPipelineUuid())), Mockito.anyLong(),
                        Mockito.anyInt()))
                .thenReturn(new LearningPipelines.Page(List.of(row(OURS, "doc-1"), row(THEIRS, "doc-2")), 2));
        final SupervisorService viewer = resource(grants(Map.of(
                pipeline(OURS), Set.of(DocumentPermission.VIEW))));

        final List<SupervisorLearner> listed = viewer.fetchPipelineLearners(OURS.getPipelineUuid());

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

        assertThat(resource(() -> noData).fetchPrompt(id, 1).getRefusal())
                .contains("only to someone who may view data");
        assertThat(resource.fetchPrompt(id, 1).getRefusal())
                .describedAs("the stream it learned from is gone")
                .contains("has been deleted");
        assertThatThrownBy(() -> resource.fetchPrompt(id, 9)).isInstanceOf(IllegalArgumentException.class);
    }

    /// Approving, retracting or giving up changes what serves a pipeline, so an auditor must see which it was,
    /// not an "Uncategorised remote API call".
    @Test
    void everyCallIsAuditedAsWhatItDoes() {
        ResourceAuditing.assertEveryCallIsAudited(SupervisorResource.class, SupervisorResourceImpl.class);
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
        return resource(securityContext, attempts);
    }

    private SupervisorService resource(final Supplier<SecurityContext> securityContext,
                                       final Attempts attemptsUsed) {
        final LearnerDocuments documents = Mockito.mock(LearnerDocuments.class);
        Mockito.when(documents.documentOf(learner(OURS))).thenReturn(Optional.of("doc-1"));
        Mockito.when(documents.documentOf(learner(THEIRS))).thenReturn(Optional.of("doc-2"));
        Mockito.when(documents.documentOf(learner(NEIGHBOUR))).thenReturn(Optional.of("doc-1"));
        Mockito.when(documents.documentOf(learner(UNNAMED))).thenReturn(Optional.empty());
        // A deleted pipeline names no Learning Flow, so the learning pipelines list says which it is read under
        Mockito.when(documents.documentOf(learner(GONE))).thenReturn(Optional.empty());
        Mockito.when(pipelines.documentOf(learner(GONE))).thenReturn(Optional.of("doc-1"));
        final DocFinder docFinder = Mockito.mock(DocFinder.class);
        Mockito.when(docFinder.decorate(Mockito.any())).thenAnswer(invocation -> invocation.getArgument(0));
        Mockito.when(stageFactory.breaker()).thenAnswer(invocation -> new SpendBreaker(spend, 0L, 0));
        return new SupervisorService(
                () -> attemptsUsed,
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
