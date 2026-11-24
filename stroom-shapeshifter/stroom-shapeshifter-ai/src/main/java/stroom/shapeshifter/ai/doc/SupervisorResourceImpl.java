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
import stroom.event.logging.rs.api.AutoLogged;
import stroom.event.logging.rs.api.AutoLogged.OperationType;
import stroom.security.api.SecurityContext;
import stroom.security.shared.DocumentPermission;
import stroom.shapeshifter.ai.element.LearnerDocuments;
import stroom.shapeshifter.ai.element.StageFactory;
import stroom.shapeshifter.ai.element.StepDetails;
import stroom.shapeshifter.ai.stage.Attempts;
import stroom.shapeshifter.ai.stage.Attempts.Page;
import stroom.shapeshifter.ai.stage.Attempts.Recorded;
import stroom.shapeshifter.ai.stage.Attempts.Turn;
import stroom.shapeshifter.ai.stage.Decision;
import stroom.shapeshifter.ai.stage.Deferrals;
import stroom.shapeshifter.ai.stage.DeletedPipelines;
import stroom.shapeshifter.ai.stage.Guidance;
import stroom.shapeshifter.ai.stage.Learner;
import stroom.shapeshifter.ai.stage.LearnerBindings;
import stroom.shapeshifter.ai.stage.LearningPipelines;
import stroom.shapeshifter.shared.AmendTurnRequest;
import stroom.shapeshifter.shared.AttemptCriteria;
import stroom.shapeshifter.shared.BacklogStream;
import stroom.shapeshifter.shared.GrantRequest;
import stroom.shapeshifter.shared.GuidanceRequest;
import stroom.shapeshifter.shared.ImproveOutcome;
import stroom.shapeshifter.shared.ImproveRequest;
import stroom.shapeshifter.shared.LearnerCriteria;
import stroom.shapeshifter.shared.LearnerRef;
import stroom.shapeshifter.shared.LearningMode;
import stroom.shapeshifter.shared.ReasonRequest;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.shapeshifter.shared.SupervisorAttempt;
import stroom.shapeshifter.shared.SupervisorGuidance;
import stroom.shapeshifter.shared.SupervisorLearner;
import stroom.shapeshifter.shared.SupervisorResource;
import stroom.shapeshifter.shared.SupervisorTurn;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.logging.LogUtil;
import stroom.util.pipeline.scope.PipelineScopeRunnable;
import stroom.util.shared.NullSafe;
import stroom.util.shared.PageRequest;
import stroom.util.shared.PageResponse;
import stroom.util.shared.PermissionException;
import stroom.util.shared.ResultPage;

import jakarta.inject.Inject;
import jakarta.inject.Provider;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/// The Supervisor of A28: the learners of every Smart Pipeline, their attempts, the translations serving them
/// and the notes left for them, and what a person may do to each.
///
/// What is learned for a feed belongs to the feed's pipeline, and is held to the pipeline's permissions: a
/// person who may view a pipeline may read its learners' sessions, waiting streams and notes (an attempt's
/// transcript carries what the model was told about that feed's data), and a person who may edit it may
/// act on them: leave a note, approve, reject, give more, relearn, give up, improve, accept or retract. A
/// pipeline since deleted has no permissions to ask; its learning is read by its Learning Flow's VIEW
/// instead, and nothing more is done with it (A137). The lists across Learning Flows (the learners and
/// attempts) are of the Learning Flows a person may view, and of the pipelines they may view.
///
/// A request names a learner by its pipeline and element alone. The Learning Flow it runs under is the one
/// its element names, or, for a pipeline since deleted, the one its learning is listed under.
@AutoLogged
public class SupervisorResourceImpl implements SupervisorResource {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(SupervisorResourceImpl.class);

    /// The most rows a page of the serving bindings may ask for, whatever it asks for.
    /// The attempts beside them have always clamped; these did not, so any person with VIEW on one
    /// document could ask for the whole table in one request, and a negative length reached the database
    /// as `LIMIT -1`.
    private static final int PAGE_LIMIT = 1000;

    private final Provider<Attempts> attemptsProvider;
    private final Provider<ShapeshifterAiStore> storeProvider;
    private final Provider<StageFactory> stageFactoryProvider;
    private final Provider<PipelineScopeRunnable> pipelineScopeProvider;
    private final Provider<SecurityContext> securityContextProvider;
    private final Provider<Guidance> guidanceProvider;
    private final Provider<Deferrals> deferralsProvider;
    private final Provider<LearnerDocuments> learnerDocumentsProvider;
    private final Provider<DeletedPipelines> deletedPipelinesProvider;
    private final Provider<LearnerBindings> learnerBindingsProvider;
    private final Provider<LearningPipelines> learningPipelinesProvider;
    private final Provider<DocFinder> docFinderProvider;

    @Inject
    SupervisorResourceImpl(final Provider<Attempts> attemptsProvider,
                           final Provider<ShapeshifterAiStore> storeProvider,
                           final Provider<StageFactory> stageFactoryProvider,
                           final Provider<PipelineScopeRunnable> pipelineScopeProvider,
                           final Provider<SecurityContext> securityContextProvider,
                           final Provider<Guidance> guidanceProvider,
                           final Provider<Deferrals> deferralsProvider,
                           final Provider<LearnerDocuments> learnerDocumentsProvider,
                           final Provider<DeletedPipelines> deletedPipelinesProvider,
                           final Provider<LearnerBindings> learnerBindingsProvider,
                           final Provider<LearningPipelines> learningPipelinesProvider,
                           final Provider<DocFinder> docFinderProvider) {
        this.attemptsProvider = attemptsProvider;
        this.storeProvider = storeProvider;
        this.stageFactoryProvider = stageFactoryProvider;
        this.pipelineScopeProvider = pipelineScopeProvider;
        this.securityContextProvider = securityContextProvider;
        this.guidanceProvider = guidanceProvider;
        this.deferralsProvider = deferralsProvider;
        this.learnerDocumentsProvider = learnerDocumentsProvider;
        this.deletedPipelinesProvider = deletedPipelinesProvider;
        this.learnerBindingsProvider = learnerBindingsProvider;
        this.learningPipelinesProvider = learningPipelinesProvider;
        this.docFinderProvider = docFinderProvider;
    }

    // --------------------------------------------------------------------------------
    // Learners

    /// The learning pipelines (design 06 A115, A142): read and paged where the rows are, each named with
    /// its pipeline and Learning Flow, and its oldest wait judged against its Learning Flow's maximum
    /// deferral age (A57). The Learning Flows and pipelines this person may view go into the query, so the
    /// count and the pages are of what they may read.
    @Override
    @AutoLogged(OperationType.SEARCH)
    public ResultPage<SupervisorLearner> findLearners(final LearnerCriteria criteria) {
        final LearnerCriteria asked = criteria == null
                ? new LearnerCriteria()
                : criteria;
        final Map<String, DocRef> readable = readable(asked.getDocUuid());
        final Map<String, ShapeshifterAiDoc> docs = documents(readable.values());
        final PageRequest page = asked.getPageRequest() == null
                ? new PageRequest(0, 100)
                : asked.getPageRequest();
        final long offset = Math.max(0L, NullSafe.getOrElse(page.getOffset(), Integer::longValue, 0L));
        final LearningPipelines.Page found = learningPipelinesProvider.get().learners(readable.keySet(),
                learningOff(docs), asked.getStates(), asked.isShowDeletedPipelines(), viewablePipelines(),
                offset, pageLength(page));
        final List<SupervisorLearner> rows = named(found.learners(), readable, docs);
        return new ResultPage<>(rows, new PageResponse(offset, rows.size(), found.total(), true));
    }

    /// The learners of one pipeline, as its own editor shows them: those of its AI Translator elements that
    /// have learned or are learning, under whichever Learning Flows they run under. Held to the pipeline's
    /// VIEW.
    @Override
    @AutoLogged(OperationType.VIEW)
    public List<SupervisorLearner> fetchPipelineLearners(final String pipelineUuid) {
        if (NullSafe.isBlankString(pipelineUuid) || !mayPipeline(pipelineUuid, DocumentPermission.VIEW)) {
            throw new PermissionException(securityContextProvider.get().getUserRef(),
                    "You do not have permission to read this pipeline");
        }
        return pipelineRows(pipelineUuid);
    }

    /// The Learning Flow a learner runs under, for its pipeline's editor to show where it stands: its flow,
    /// pass mark and limits. Held to the pipeline's VIEW: the settings are how that pipeline's feed is
    /// learned, which a person who may see the pipeline may read without being able to open the document.
    @Override
    @AutoLogged(OperationType.VIEW)
    public ShapeshifterAiDoc fetchSettings(final String pipelineUuid, final String elementId) {
        final Learner learner = readable(learner(pipelineUuid, elementId));
        return document(documentOf(learner).orElseThrow(() -> namesNoLearningFlow(learner)));
    }

    /// What waits on one learner (design 06 A115), oldest first, a page at a time.
    @Override
    @AutoLogged(OperationType.SEARCH)
    public ResultPage<BacklogStream> findWaiting(final String pipelineUuid,
                                                 final String elementId,
                                                 final PageRequest pageRequest) {
        final Learner learner = readable(learner(pipelineUuid, elementId));
        final PageRequest page = pageRequest == null
                ? new PageRequest(0, 100)
                : pageRequest;
        final long offset = Math.max(0L, NullSafe.getOrElse(page.getOffset(), Integer::longValue, 0L));
        final Deferrals.Waiting waiting = deferralsProvider.get().waitingOn(learner, offset, pageLength(page));
        final String docUuid = documentOf(learner).orElse(null);
        final DocRef doc = docUuid == null
                ? null
                : new DocRef(ShapeshifterAiDoc.TYPE, docUuid);
        final long maxAge = maxDeferralAge(doc == null
                ? Map.of()
                : documents(List.of(doc)), docUuid);
        final long now = System.currentTimeMillis();
        final List<BacklogStream> rows = waiting.rows().stream()
                .map(row -> new BacklogStream(doc, row.inputId(), row.feed(), row.type(), row.failedAgainst(),
                        row.reason(), row.failingRecords(), row.firstDeferredMs(), row.attempts(),
                        row.raised(maxAge, now)))
                .toList();
        return new ResultPage<>(rows, new PageResponse(offset, rows.size(), waiting.total(), true));
    }

    /// Send a learner back to learn afresh from its next stream (A28, A29).
    @Override
    @AutoLogged(value = OperationType.PROCESS, verb = "Sending a Smart Pipeline learner back to learn afresh")
    public SupervisorLearner relearn(final String pipelineUuid,
                                     final String elementId,
                                     final ReasonRequest request) {
        final Learner learner = changeable(learner(pipelineUuid, elementId));
        final String who = securityContextProvider.get().getUserIdentityForAudit();
        final String given = request == null
                ? null
                : request.getReason();
        final String reason = NullSafe.isBlankString(given)
                ? "Sent back to be learned again by " + who
                : "Sent back to be learned again by " + who + ": " + given;
        final ShapeshifterAiDoc doc = document(runningDocument(learner));
        pipelineScopeProvider.get().scopeRunnable(() ->
                stageFactoryProvider.get().create().relearn(doc, learner, reason));
        return row(learner);
    }

    /// Give a learner up (design 06 A115). The reason is asked for rather than assumed: its streams error
    /// with it.
    @Override
    @AutoLogged(value = OperationType.PROCESS, verb = "Giving up a Smart Pipeline learner")
    public SupervisorLearner giveUp(final String pipelineUuid,
                                    final String elementId,
                                    final ReasonRequest request) {
        if (request == null || NullSafe.isBlankString(request.getReason())) {
            throw new IllegalArgumentException("Giving a learner up says why: its streams error with the "
                                               + "reason");
        }
        final Learner learner = changeable(learner(pipelineUuid, elementId));
        final String by = securityContextProvider.get().getUserIdentityForAudit();
        final ShapeshifterAiDoc doc = document(runningDocument(learner));
        pipelineScopeProvider.get().scopeRunnable(() ->
                stageFactoryProvider.get().create().giveUp(doc, learner, request.getReason(), by));
        return row(learner);
    }

    // --------------------------------------------------------------------------------
    // Guidance

    /// What has been said to a learner (A46), oldest first.
    @Override
    @AutoLogged(OperationType.VIEW)
    public List<SupervisorGuidance> fetchGuidance(final String pipelineUuid, final String elementId) {
        return said(readable(learner(pipelineUuid, elementId)));
    }

    /// Tell a learner something, without asking for anything to be run.
    ///
    /// Held to EDIT on the pipeline rather than VIEW: what is written here is carried into every question
    /// the learner asks from now on, which is a change to how its feed is learned.
    @Override
    @AutoLogged(OperationType.CREATE)
    public List<SupervisorGuidance> addGuidance(final String pipelineUuid,
                                                final String elementId,
                                                final GuidanceRequest request) {
        if (request == null || NullSafe.isBlankString(request.getMessage())) {
            throw new IllegalArgumentException("There is nothing to record: the message is empty");
        }
        final Learner learner = changeable(learner(pipelineUuid, elementId));
        guidanceProvider.get().given(learner, runningDocument(learner), request.getMessage(),
                securityContextProvider.get().getUserIdentityForAudit());
        return said(learner);
    }

    /// Take back something said to a learner. A hint that turned out to be wrong is worse than no hint:
    /// it is carried into every question the learner asks until it is withdrawn.
    ///
    /// Only a hint said to this learner is taken back: an id naming another learner's, even under the same
    /// Learning Flow, takes nothing back, as permission to change that one is its own pipeline's.
    ///
    /// The turns that already carried it still say they did (A45), which is what a re-walk replays: what
    /// was withdrawn stops being carried, and does not stop having been.
    @Override
    @AutoLogged(OperationType.DELETE)
    public List<SupervisorGuidance> deleteGuidance(final String pipelineUuid,
                                                   final String elementId,
                                                   final long id) {
        final Learner learner = changeable(learner(pipelineUuid, elementId));
        guidanceProvider.get().withdraw(learner, runningDocument(learner), id);
        return said(learner);
    }

    // --------------------------------------------------------------------------------
    // Attempts

    @Override
    @AutoLogged(OperationType.SEARCH)
    public ResultPage<SupervisorAttempt> findAttempts(final AttemptCriteria criteria) {
        final AttemptCriteria asked = criteria == null
                ? new AttemptCriteria()
                : criteria;
        // The Learning Flows and pipelines this person may see go into the query rather than filtering its
        // answer: a count taken before the filtering would tell them how many attempts exist that they may
        // not see, and the pages they turned would come back short with no way to reach the end. One
        // learner's sessions, as its pipeline's editor asks for them, are held to the pipeline instead, and
        // may be under any Learning Flow.
        final Page page = asked.getLearner() == null
                ? attemptsProvider.get().found(asked, mayRead(asked.getDocUuid()), viewablePipelines())
                : attemptsProvider.get().found(asked, learnersDocuments(asked.getLearner()));
        // Which of the page's pipelines are deleted, asked once for the page rather than once a row.
        final Set<String> deleted = deletedPipelinesProvider.get().deletedOf(page.attempts().stream()
                .map(attempt -> attempt.attempt().learner().pipelineUuid())
                .collect(Collectors.toSet()));
        final List<SupervisorAttempt> rows = page.attempts().stream()
                .map(attempt -> attempt(attempt, List.of(),
                        deleted.contains(attempt.attempt().learner().pipelineUuid())))
                .toList();
        final long offset = asked.getPageRequest() == null || asked.getPageRequest().getOffset() == null
                ? 0L
                : asked.getPageRequest().getOffset();
        return new ResultPage<>(rows, new PageResponse(offset, rows.size(), page.total(), true));
    }

    @Override
    @AutoLogged(OperationType.VIEW)
    public SupervisorAttempt fetchAttempt(final long id) {
        return detail(read(id));
    }

    @Override
    @AutoLogged(value = OperationType.PROCESS, verb = "Answering or editing a learning attempt turn")
    public SupervisorAttempt amendTurn(final long id, final int number, final AmendTurnRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("No answer was given for turn " + number);
        }
        final Recorded attempt = stillUnder(live(change(id)));
        final String answeredBy = securityContextProvider.get().getUserIdentityForAudit();
        onStage(attempt, (stage, doc) -> stage.amend(doc, id, number, request.getAnswer(), answeredBy));
        return detail(read(id));
    }

    @Override
    @AutoLogged(value = OperationType.PROCESS, verb = "Approving a drafted translation")
    public SupervisorAttempt approve(final long id) {
        final Recorded attempt = stillUnder(live(change(id)));
        onStage(attempt, (stage, doc) -> stage.approve(doc, bindingOf(attempt)));
        return detail(read(id));
    }

    @Override
    @AutoLogged(value = OperationType.PROCESS, verb = "Rejecting a drafted translation")
    public SupervisorAttempt reject(final long id, final ReasonRequest request) {
        if (request == null || NullSafe.isBlankString(request.getReason())) {
            throw new IllegalArgumentException("A rejection says why: the learner gives up with the reason");
        }
        final Recorded attempt = stillUnder(live(change(id)));
        onStage(attempt, (stage, doc) -> stage.reject(doc, bindingOf(attempt), request.getReason()));
        return detail(read(id));
    }

    /// Give a parked session more to spend (A77), and carry it on.
    @Override
    @AutoLogged(value = OperationType.PROCESS, verb = "Granting a parked learning attempt more to spend")
    public SupervisorAttempt grant(final long id, final GrantRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Nothing was granted");
        }
        final Recorded attempt = stillUnder(live(change(id)));
        onStage(attempt, (stage, doc) -> stage.grant(doc, id, request.getTokens(), request.getMs()));
        return detail(read(id));
    }

    /// Send a parked session to the exhaustion step (A65), and carry it on.
    @Override
    @AutoLogged(value = OperationType.PROCESS, verb = "Sending a parked learning attempt to its exhaustion step")
    public SupervisorAttempt exhaust(final long id) {
        final Recorded attempt = stillUnder(live(change(id)));
        onStage(attempt, (stage, doc) -> stage.exhaust(doc, id));
        return detail(read(id));
    }

    // --------------------------------------------------------------------------------
    // Bindings

    /// Ask for a binding that is already serving to be made better (A46).
    ///
    /// Held to EDIT on the binding's pipeline, as approving a draft is: it spends a model's tokens and may
    /// rebind the learner.
    ///
    /// It runs while the request is open, which is the one thing here that is not yet what A46 asks for:
    /// the ruling says an improvement is taken by the deferred worker. The stage says why in
    /// `Stage.improve` — the worker would need the attempt to remember that it is an improvement and to
    /// find its sample in the regression set rather than in the stream store. Nothing waits on it
    /// meanwhile: the incumbent serves every stream throughout, and a candidate takes over only through
    /// the ordinary gate.
    @Override
    @AutoLogged(value = OperationType.PROCESS, verb = "Asking for a serving translation to be improved")
    public ImproveOutcome improve(final String bindingUuid, final ImproveRequest request) {
        final Learner learner = changeableBinding(bindingUuid);
        final String by = securityContextProvider.get().getUserIdentityForAudit();
        final String message = request == null
                ? null
                : request.getMessage();
        final ShapeshifterAiDoc doc = document(runningDocument(learner));
        final Decision decision = pipelineScopeProvider.get().scopeResult(() ->
                stageFactoryProvider.get().create().improve(doc, bindingUuid, message, by).decision());
        // Described from the one switch that describes every decision: the stage pane, the error stream
        // and this button say the same thing because they say it from the same place.
        return new ImproveOutcome(StepDetails.describe(decision));
    }

    /// Accept a provisional binding now (design 01 §6, §11.6), rather than waiting for the records
    /// that would promote it.
    ///
    /// Held to EDIT on the binding's pipeline, as the other acts that change what serves a learner are.
    /// Not a way round the gate: the binding cleared the promotion floor when it was bound, and what is
    /// skipped is the wait for more records to judge it on.
    @Override
    @AutoLogged(value = OperationType.PROCESS, verb = "Accepting a provisional translation")
    public SupervisorLearner accept(final String bindingUuid) {
        final Learner learner = changeableBinding(bindingUuid);
        final String by = securityContextProvider.get().getUserIdentityForAudit();
        final ShapeshifterAiDoc doc = document(runningDocument(learner));
        pipelineScopeProvider.get().scopeRunnable(() ->
                stageFactoryProvider.get().create().accept(doc, bindingUuid, by));
        return row(learner);
    }

    /// Take back a binding that is serving (A28, design 01 §11.6).
    ///
    /// The reason is asked for rather than assumed: it is recorded against the attempt that wrote the binding.
    ///
    /// Answers how many streams the binding produced output for, which is the one consequence the person
    /// cannot see for themselves, and what they would reprocess (A129).
    ///
    /// Held to EDIT on the binding's pipeline, as approving a draft is: it changes what serves it.
    @Override
    @AutoLogged(value = OperationType.PROCESS, verb = "Retracting a serving translation")
    public Integer retract(final String bindingUuid, final ReasonRequest request) {
        if (request == null || NullSafe.isBlankString(request.getReason())) {
            throw new IllegalArgumentException("A retraction says why: the reason is recorded against "
                                               + "the attempt that wrote the binding");
        }
        final Learner learner = changeableBinding(bindingUuid);
        final String by = securityContextProvider.get().getUserIdentityForAudit();
        final ShapeshifterAiDoc doc = document(runningDocument(learner));
        return pipelineScopeProvider.get().scopeResult(() ->
                stageFactoryProvider.get().create().retract(doc, bindingUuid, request.getReason(), by));
    }

    // --------------------------------------------------------------------------------
    // Rows

    /// One pipeline's learners, under every Learning Flow, named.
    private List<SupervisorLearner> pipelineRows(final String pipelineUuid) {
        final Map<String, DocRef> every = new LinkedHashMap<>();
        storeProvider.get().listDocuments().forEach(docRef -> every.put(docRef.getUuid(), docRef));
        final Map<String, ShapeshifterAiDoc> docs = documents(every.values());
        final LearningPipelines.Page found = learningPipelinesProvider.get().learners(every.keySet(),
                learningOff(docs), List.of(), false, List.of(pipelineUuid), 0L, PAGE_LIMIT);
        return named(found.learners(), every, docs);
    }

    /// The learner's row as it now stands, for an act on it to answer with; null where it is no longer listed.
    private SupervisorLearner row(final Learner learner) {
        return pipelineRows(learner.pipelineUuid()).stream()
                .filter(row -> learner.elementId().equals(row.getLearner().getElementId()))
                .findFirst()
                .orElse(null);
    }

    /// The rows, each named with its pipeline and Learning Flow, its wait judged against its Learning Flow's
    /// maximum deferral age, and its rolling score against its relearn threshold.
    private List<SupervisorLearner> named(final List<SupervisorLearner> rows,
                                          final Map<String, DocRef> docRefs,
                                          final Map<String, ShapeshifterAiDoc> docs) {
        final DocFinder docFinder = docFinderProvider.get();
        final long now = System.currentTimeMillis();
        return rows.stream()
                .map(row -> row.named(docFinder.decorate(row.getPipeline()),
                        docRefs.get(row.getDoc().getUuid()),
                        row.getOldestWaitMs() != null
                        && now - row.getOldestWaitMs() > maxDeferralAge(docs, row.getDoc().getUuid()),
                        belowThreshold(docs.get(row.getDoc().getUuid()), row.getRollingScore())))
                .toList();
    }

    /// The Learning Flows given with learning turned off, whose learners are said to be so.
    private static Set<String> learningOff(final Map<String, ShapeshifterAiDoc> docs) {
        return docs.values().stream()
                .filter(doc -> doc.getLearningMode() == LearningMode.DISABLED)
                .map(ShapeshifterAiDoc::getUuid)
                .collect(Collectors.toSet());
    }

    /// The pipelines with learning this person may see in a list: those they may view, and those since
    /// deleted, whose learning is read by the Learning Flow it is listed under, which the lists also require.
    /// Only the few pipelines that have learning are asked about.
    private Set<String> viewablePipelines() {
        final Set<String> pipelines = learningPipelinesProvider.get().pipelines();
        final Set<String> deleted = deletedPipelinesProvider.get().deletedOf(pipelines);
        return pipelines.stream()
                .filter(pipelineUuid -> deleted.contains(pipelineUuid)
                                        || mayPipeline(pipelineUuid, DocumentPermission.VIEW))
                .collect(Collectors.toSet());
    }

    /// The documents, read once each; one that cannot be read is left out, and its rows take the defaults.
    private Map<String, ShapeshifterAiDoc> documents(final Collection<DocRef> docRefs) {
        final Map<String, ShapeshifterAiDoc> docs = new LinkedHashMap<>();
        for (final DocRef docRef : docRefs) {
            try {
                final ShapeshifterAiDoc doc = storeProvider.get().readDocument(docRef);
                if (doc != null) {
                    docs.put(docRef.getUuid(), doc);
                }
            } catch (final RuntimeException e) {
                LOGGER.debug(() -> LogUtil.message("Document {} could not be read: {}", docRef.getUuid(),
                        e.getMessage()));
            }
        }
        return docs;
    }

    /// Whether a learner's rolling score is below its document's relearn threshold (design 04 §6): where
    /// the document has one and the learner has served enough to be scored.
    private static boolean belowThreshold(final ShapeshifterAiDoc doc, final Double rollingScore) {
        return doc != null
               && rollingScore != null
               && rollingScore < doc.getRelearnThreshold();
    }

    private static long maxDeferralAge(final Map<String, ShapeshifterAiDoc> docs, final String docUuid) {
        final ShapeshifterAiDoc doc = docs.get(docUuid);
        return doc == null
                ? ShapeshifterAiDoc.builder().uuid("defaults").build().getMaxDeferralAgeMs()
                : doc.getMaxDeferralAgeMs();
    }

    private List<SupervisorGuidance> said(final Learner learner) {
        return guidanceProvider.get().standing(learner).stream()
                .map(given -> new SupervisorGuidance(given.id(), given.message(), given.author(),
                        given.timeMs()))
                .toList();
    }

    /// How many rows a page may hold: what was asked for, within what the server will give. A request
    /// is not to be trusted with either end of it — an unbounded length hands over the whole table in
    /// one call, and a negative one reaches the database as `LIMIT -1` and answers with a server error
    /// rather than a refusal.
    private static int pageLength(final PageRequest page) {
        final int asked = NullSafe.getOrElse(page.getLength(), Integer::intValue, 100);
        return Math.max(1, Math.min(asked, PAGE_LIMIT));
    }

    // --------------------------------------------------------------------------------
    // Learners and the Learning Flows they run under

    /// The learner a request names by its pipeline and element.
    private static Learner learner(final String pipelineUuid, final String elementId) {
        if (NullSafe.isBlankString(pipelineUuid) || NullSafe.isBlankString(elementId)) {
            throw new IllegalArgumentException("Say which learner: its pipeline and element");
        }
        return new Learner(pipelineUuid, elementId);
    }

    /// The learner a row names.
    private static Learner learner(final LearnerRef ref) {
        if (ref == null) {
            throw new IllegalArgumentException("Say which learner: its pipeline and element");
        }
        return learner(ref.getPipelineUuid(), ref.getElementId());
    }

    /// The Learning Flow the learner runs under: as its pipeline's element says now, or for a pipeline since
    /// deleted, which says nothing, as the learning pipelines list says, so that what it learned can still be
    /// read from the Learning Flow it is listed under (A137), whether or not it ever bound anything.
    private Optional<String> documentOf(final Learner learner) {
        return deletedPipelinesProvider.get().isDeleted(learner.pipelineUuid())
                ? learningPipelinesProvider.get().documentOf(learner)
                : learnerDocumentsProvider.get().documentOf(learner);
    }

    /// The Learning Flow a live learner runs under, which a change to it is made under.
    private String runningDocument(final Learner learner) {
        return learnerDocumentsProvider.get().documentOf(learner)
                .orElseThrow(() -> namesNoLearningFlow(learner));
    }

    private static IllegalStateException namesNoLearningFlow(final Learner learner) {
        return new IllegalStateException("Element " + learner.elementId() + " of pipeline "
                                         + learner.pipelineUuid() + " doesn't name a Learning Flow");
    }

    private ShapeshifterAiDoc document(final String docUuid) {
        final ShapeshifterAiDoc doc = storeProvider.get().readDocument(
                new DocRef(ShapeshifterAiDoc.TYPE, docUuid));
        if (doc == null) {
            throw new IllegalArgumentException("There is no Learning Flow " + docUuid);
        }
        return doc;
    }

    /// The documents this person may read, by uuid and keeping their names, narrowed to the one they
    /// asked about if they asked about one. A handful per installation, so asking the store about each
    /// costs nothing next to the query it saves.
    private Map<String, DocRef> readable(final String asked) {
        final Map<String, DocRef> readable = new LinkedHashMap<>();
        storeProvider.get().listDocuments().stream()
                .filter(docRef -> asked == null || asked.equals(docRef.getUuid()))
                .filter(docRef -> may(docRef.getUuid(), DocumentPermission.VIEW))
                .forEach(docRef -> readable.put(docRef.getUuid(), docRef));
        return readable;
    }

    /// The documents a learner's sessions may be under, where this person may read its pipeline: every
    /// document, since its pipeline may have run under more than one; none where they may not.
    private List<String> learnersDocuments(final LearnerRef ref) {
        final Learner learner = learner(ref);
        return storeProvider.get().listDocuments().stream()
                .map(DocRef::getUuid)
                .filter(uuid -> mayLearner(learner, uuid, DocumentPermission.VIEW))
                .toList();
    }

    /// The documents this person may see, narrowed to the one they asked about if they asked about one.
    private List<String> mayRead(final String asked) {
        return storeProvider.get().listDocuments().stream()
                .map(DocRef::getUuid)
                .filter(uuid -> asked == null || asked.equals(uuid))
                .filter(uuid -> may(uuid, DocumentPermission.VIEW))
                .toList();
    }

    // --------------------------------------------------------------------------------
    // Permissions

    /// The learner, where this person may view its pipeline (or, for a pipeline since deleted, the Learning
    /// Flow its learning is listed under).
    private Learner readable(final Learner learner) {
        if (!mayLearner(learner, documentOf(learner).orElse(null), DocumentPermission.VIEW)) {
            throw new PermissionException(securityContextProvider.get().getUserRef(),
                    "You do not have permission to read the pipeline of element " + learner.elementId());
        }
        return learner;
    }

    /// The learner, where this person may edit its pipeline and the pipeline has not been deleted (A137).
    /// Permission is asked first, so a person who may not read the pipeline isn't told it was deleted.
    private Learner changeable(final Learner learner) {
        live(readable(learner));
        if (!mayPipeline(learner.pipelineUuid(), DocumentPermission.EDIT)) {
            throw new PermissionException(securityContextProvider.get().getUserRef(),
                    "You do not have permission to change how the pipeline of element " + learner.elementId()
                    + " learns");
        }
        return learner;
    }

    /// The learner of a binding this person may change. A binding that isn't there answers as one they may
    /// not read, or the answer would say which bindings exist.
    private Learner changeableBinding(final String bindingUuid) {
        final Learner learner = learnerBindingsProvider.get().byUuid(bindingUuid)
                .map(LearnerBindings.Held::learner)
                .filter(held -> mayLearner(held, documentOf(held).orElse(null), DocumentPermission.VIEW))
                .orElseThrow(() -> new PermissionException(securityContextProvider.get().getUserRef(),
                        "You do not have permission to change binding " + bindingUuid));
        return changeable(learner);
    }

    /// An attempt whose pipeline has not been deleted. A deleted pipeline's learning is kept to be read
    /// until it ages out, and nothing more is done with it (design 06 A137): what a person would change
    /// from here would serve, or teach, a pipeline that will never run again.
    private Recorded live(final Recorded attempt) {
        live(attempt.attempt().learner());
        return attempt;
    }

    /// The learner, where its pipeline has not been deleted.
    private Learner live(final Learner learner) {
        if (deletedPipelinesProvider.get().isDeleted(learner.pipelineUuid())) {
            throw new IllegalStateException("The pipeline of element " + learner.elementId() + " has been "
                                            + "deleted: what it learned is kept to be read until it ages "
                                            + "out, and nothing more is done with it");
        }
        return learner;
    }

    /// An attempt whose learner still runs under the Learning Flow it was learning for. An attempt is history
    /// and keeps its Learning Flow, but a change made from one is a change to its learner, made with its
    /// Learning Flow's settings: once the pipeline has been pointed at another Learning Flow, those settings
    /// are no longer the ones it learns by, so the change is made from the Learning Flow it names now.
    private Recorded stillUnder(final Recorded attempt) {
        final boolean runsUnder = documentOf(attempt.attempt().learner())
                .filter(attempt.attempt().docUuid()::equals)
                .isPresent();
        if (!runsUnder) {
            throw new IllegalStateException("Attempt " + attempt.id() + " was learning for a pipeline that no "
                                            + "longer runs under its Learning Flow; act on it from the "
                                            + "Learning Flow the pipeline names now");
        }
        return attempt;
    }

    /// An attempt a person may read. An attempt they may not see and an attempt that never existed
    /// answer the same way, or the answer itself says which documents exist and what has been learned
    /// for them.
    private Recorded read(final long id) {
        return permitted(id, DocumentPermission.VIEW);
    }

    /// An attempt a person may change. Approving a draft puts its binding in force, rejecting one gives a
    /// learner up, and running an attempt again spends a model's tokens: these change what serves the
    /// attempt's pipeline and are held to the same permission as editing the pipeline.
    private Recorded change(final long id) {
        return permitted(id, DocumentPermission.EDIT);
    }

    private Recorded permitted(final long id, final DocumentPermission permission) {
        final Recorded attempt = attemptsProvider.get().byId(id).orElse(null);
        if (attempt == null
            || !mayLearner(attempt.attempt().learner(), attempt.attempt().docUuid(), permission)) {
            throw new PermissionException(securityContextProvider.get().getUserRef(),
                    "You do not have permission to read attempt " + id);
        }
        return attempt;
    }

    /// Whether this person may do a thing to a learner: by its pipeline's permission, or, for a pipeline since
    /// deleted, which has none to ask, by its document's, to read alone. Nothing is changed for a deleted
    /// pipeline's learner (A137), so a change asked of one is refused.
    private boolean mayLearner(final Learner learner, final String docUuid, final DocumentPermission permission) {
        return access().mayLearner(learner, docUuid, permission);
    }

    private boolean mayPipeline(final String pipelineUuid, final DocumentPermission permission) {
        return access().mayPipeline(pipelineUuid, permission);
    }

    private boolean may(final String docUuid, final DocumentPermission permission) {
        return access().mayDocument(docUuid, permission);
    }

    // The rules, shared with the Quality Assurance tab's resource
    private LearnerAccess access() {
        return new LearnerAccess(securityContextProvider, deletedPipelinesProvider);
    }

    // --------------------------------------------------------------------------------
    // Attempts as the client reads them

    private String bindingOf(final Recorded attempt) {
        if (attempt.bindingUuid() == null) {
            throw new IllegalStateException("Attempt " + attempt.id() + " drafted no binding to decide about");
        }
        return attempt.bindingUuid();
    }

    /// In a pipeline scope, though there is no pipeline: what a stage is built from reports through the
    /// scoped error receiver, as it does for the deferred worker.
    private void onStage(final Recorded attempt, final OnStage action) {
        final ShapeshifterAiDoc doc = storeProvider.get().readDocument(
                new DocRef(ShapeshifterAiDoc.TYPE, attempt.attempt().docUuid()));
        if (doc == null) {
            throw new IllegalStateException("The Learning Flow of attempt " + attempt.id()
                                            + " has been deleted");
        }
        pipelineScopeProvider.get().scopeRunnable(() ->
                action.run(stageFactoryProvider.get().create(), doc));
    }

    private SupervisorAttempt detail(final Recorded attempt) {
        return attempt(attempt, attempt.turns().stream()
                        .map(SupervisorResourceImpl::turn)
                        .toList(),
                deletedPipelinesProvider.get().isDeleted(attempt.attempt().learner().pipelineUuid()));
    }

    private static SupervisorAttempt attempt(final Recorded attempt,
                                             final List<SupervisorTurn> turns,
                                             final boolean pipelineDeleted) {
        return new SupervisorAttempt(
                attempt.id(),
                new DocRef(ShapeshifterAiDoc.TYPE, attempt.attempt().docUuid()),
                new LearnerRef(attempt.attempt().learner().pipelineUuid(), attempt.attempt().learner().elementId()),
                attempt.attempt().feed(),
                attempt.attempt().type(),
                attempt.attempt().inputId(),
                attempt.attempt().node(),
                attempt.attempt().promotionMode(),
                attempt.status(),
                attempt.decision(),
                attempt.bindingUuid(),
                attempt.score(),
                attempt.tokensSpent(),
                attempt.grant().tokens(),
                attempt.grant().ms(),
                attempt.exhaustNext(),
                attempt.createTimeMs(),
                attempt.updateTimeMs(),
                turns,
                pipelineDeleted,
                attempt.report());
    }

    private static SupervisorTurn turn(final Turn turn) {
        return new SupervisorTurn(turn.number(), turn.stepId(), turn.candidate(), turn.kind(),
                turn.question(), turn.answer(), turn.answeredBy(), turn.outcome(), turn.score());
    }


    // --------------------------------------------------------------------------------


    @FunctionalInterface
    private interface OnStage {

        void run(stroom.shapeshifter.ai.stage.Stage stage, ShapeshifterAiDoc doc);
    }
}
