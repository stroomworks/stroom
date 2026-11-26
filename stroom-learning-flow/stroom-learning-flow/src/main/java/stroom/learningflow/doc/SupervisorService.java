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
import stroom.learningflow.element.PipelineZones;
import stroom.learningflow.element.ResolvedZone;
import stroom.learningflow.element.StageFactory;
import stroom.learningflow.learning.TargetSchemas;
import stroom.learningflow.shared.AmendTurnRequest;
import stroom.learningflow.shared.AttemptCriteria;
import stroom.learningflow.shared.BacklogStream;
import stroom.learningflow.shared.GrantRequest;
import stroom.learningflow.shared.GuidanceRequest;
import stroom.learningflow.shared.ImproveOutcome;
import stroom.learningflow.shared.ImproveRequest;
import stroom.learningflow.shared.LearnerCriteria;
import stroom.learningflow.shared.LearnerRef;
import stroom.learningflow.shared.LearnerRequest;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.shared.LearningMode;
import stroom.learningflow.shared.RebuiltPrompt;
import stroom.learningflow.shared.RejectRequest;
import stroom.learningflow.shared.SupervisorAttempt;
import stroom.learningflow.shared.SupervisorGuidance;
import stroom.learningflow.shared.SupervisorLearner;
import stroom.learningflow.shared.SupervisorTurn;
import stroom.learningflow.stage.Attempts;
import stroom.learningflow.stage.Attempts.Page;
import stroom.learningflow.stage.Attempts.Recorded;
import stroom.learningflow.stage.Attempts.Turn;
import stroom.learningflow.stage.Decision;
import stroom.learningflow.stage.Decision.Sentinel;
import stroom.learningflow.stage.Deferrals;
import stroom.learningflow.stage.DeletedPipelines;
import stroom.learningflow.stage.Guidance;
import stroom.learningflow.stage.Input;
import stroom.learningflow.stage.Inputs;
import stroom.learningflow.stage.Learner;
import stroom.learningflow.stage.LearnerBindings;
import stroom.learningflow.stage.LearningPipelines;
import stroom.learningflow.stage.Rebuilt;
import stroom.learningflow.stage.SpendBreaker;
import stroom.security.api.SecurityContext;
import stroom.security.shared.AppPermission;
import stroom.security.shared.DocumentPermission;
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

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/// The service behind the Supervisor screen, which lists learning pipelines, their sessions and waiting
/// streams across every Learning Flow document, and lets a person act on them. Its REST resource
/// ([SupervisorResourceImpl]) does the audit logging and delegates here.
///
/// What is learned for a feed belongs to the feed's pipeline, so access follows the pipeline's permissions.
/// A user who may view a pipeline may read its learners' sessions, waiting streams and guidance (a session's
/// transcript includes what the model was shown of the feed's data). A user who may edit the pipeline may
/// also act on them: leave guidance, approve, reject, grant more budget, relearn, give up, improve or
/// retract. A deleted pipeline has no permissions to check, so its learning may be viewed by anyone with
/// VIEW on its Learning Flow document, and it cannot be changed. The lists that span documents only include
/// documents the user may view.
public class SupervisorService {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(SupervisorService.class);

    /// The maximum number of rows a page may hold, whatever the request asks for. Without a limit, any user
    /// with VIEW on one document could fetch the whole table in one request.
    private static final int PAGE_LIMIT = 1000;
    /// The number of rows a page holds if the request does not say.
    private static final int PAGE_LENGTH = 100;

    private final Provider<Attempts> attemptsProvider;
    private final Provider<LearningFlowStore> storeProvider;
    private final Provider<StageFactory> stageFactoryProvider;
    private final Provider<PipelineScopeRunnable> pipelineScopeProvider;
    private final Provider<SecurityContext> securityContextProvider;
    private final Provider<Guidance> guidanceProvider;
    private final Provider<Deferrals> deferralsProvider;
    /// Checks whether the user may view or change a learner, shared with the Quality Assurance tab.
    private final LearnerAccess access;
    private final Provider<DeletedPipelines> deletedPipelinesProvider;
    private final Provider<LearnerBindings> learnerBindingsProvider;
    private final Provider<LearningPipelines> learningPipelinesProvider;
    private final Provider<DocFinder> docFinderProvider;
    private final Provider<TargetSchemas> targetSchemasProvider;
    private final Provider<PipelineZones> pipelineZonesProvider;
    private final Provider<Inputs> inputsProvider;

    @Inject
    public SupervisorService(final Provider<Attempts> attemptsProvider,
                             final Provider<LearningFlowStore> storeProvider,
                             final Provider<StageFactory> stageFactoryProvider,
                             final Provider<PipelineScopeRunnable> pipelineScopeProvider,
                             final Provider<SecurityContext> securityContextProvider,
                             final Provider<Guidance> guidanceProvider,
                             final Provider<Deferrals> deferralsProvider,
                             final LearnerAccess access,
                             final Provider<DeletedPipelines> deletedPipelinesProvider,
                             final Provider<LearnerBindings> learnerBindingsProvider,
                             final Provider<LearningPipelines> learningPipelinesProvider,
                             final Provider<DocFinder> docFinderProvider,
                             final Provider<TargetSchemas> targetSchemasProvider,
                             final Provider<PipelineZones> pipelineZonesProvider,
                             final Provider<Inputs> inputsProvider) {
        this.attemptsProvider = attemptsProvider;
        this.storeProvider = storeProvider;
        this.stageFactoryProvider = stageFactoryProvider;
        this.pipelineScopeProvider = pipelineScopeProvider;
        this.securityContextProvider = securityContextProvider;
        this.guidanceProvider = guidanceProvider;
        this.deferralsProvider = deferralsProvider;
        this.access = access;
        this.deletedPipelinesProvider = deletedPipelinesProvider;
        this.learnerBindingsProvider = learnerBindingsProvider;
        this.learningPipelinesProvider = learningPipelinesProvider;
        this.docFinderProvider = docFinderProvider;
        this.targetSchemasProvider = targetSchemasProvider;
        this.pipelineZonesProvider = pipelineZonesProvider;
        this.inputsProvider = inputsProvider;
    }

    /// Lists the learning pipelines (one row per learner) for the Supervisor's top level, filtered and paged
    /// by the database. Each row is named with its pipeline and document, and is flagged if its oldest
    /// waiting stream is older than its document's maximum deferral age. The documents the user may view
    /// are passed into the query, as they are for the other lists, so that counts and paging are correct.
    public ResultPage<SupervisorLearner> learners(final LearnerCriteria criteria) {
        final LearnerCriteria asked = criteria == null
                ? new LearnerCriteria()
                : criteria;
        final Map<String, DocRef> readable = readable(asked.getDocUuid());
        final Map<String, LearningFlowDoc> docs = documents(readable.values());
        final PageRequest page = pageOf(asked.getPageRequest());
        final long offset = offsetOf(page);
        final LearningPipelines.Page found = learningPipelinesProvider.get().learners(readable.keySet(),
                learningOff(docs), asked.getStates(), asked.isShowDeletedPipelines(), offset, pageLength(page));
        final List<SupervisorLearner> rows = named(found, readable, docs);
        return new ResultPage<>(rows, new PageResponse(offset, rows.size(), found.total(), true));
    }

    /// Lists the learners of one pipeline, for the pipeline's own editor: those of its Smart Pipeline
    /// elements that have learned or are learning, under whichever documents they run under. Requires VIEW
    /// on the pipeline.
    public List<SupervisorLearner> pipelineLearners(final String pipelineUuid) {
        if (NullSafe.isBlankString(pipelineUuid) || !access.mayPipeline(pipelineUuid, DocumentPermission.VIEW)) {
            throw new PermissionException(securityContextProvider.get().getUserRef(),
                    "You do not have permission to read this pipeline");
        }
        final Map<String, DocRef> every = new LinkedHashMap<>();
        storeProvider.get().listDocuments().forEach(docRef -> every.put(docRef.getUuid(), docRef));
        final Map<String, LearningFlowDoc> docs = documents(every.values());
        final LearningPipelines.Page found = learningPipelinesProvider.get().learners(every.keySet(),
                learningOff(docs), List.of(), false, pipelineUuid, 0L, PAGE_LIMIT);
        return named(found, every, docs).stream()
                .map(this::withTimeZone)
                .toList();
    }

    /// The UUIDs of documents with learning disabled, so that their learners can be shown as such.
    private static Set<String> learningOff(final Map<String, LearningFlowDoc> docs) {
        return docs.values().stream()
                .filter(doc -> doc.getLearningMode() == LearningMode.DISABLED)
                .map(LearningFlowDoc::getUuid)
                .collect(Collectors.toSet());
    }

    /// Completes a page of learner rows with the names of their pipeline and document, and with flags
    /// derived from the document: whether the oldest wait exceeds the maximum deferral age, whether the
    /// rolling score is below the relearn threshold, whether there is no marked-Unknown fallback, and why
    /// learning is paused by the spend breaker, if it is.
    private List<SupervisorLearner> named(final LearningPipelines.Page found,
                                          final Map<String, DocRef> docRefs,
                                          final Map<String, LearningFlowDoc> docs) {
        final DocFinder docFinder = docFinderProvider.get();
        final long now = System.currentTimeMillis();
        final Set<String> noFallback = noFallback(docs.values());
        final Map<String, String> spendPaused = spendPaused(docs.values());
        return found.learners().stream()
                .map(row -> row.named(docFinder.decorate(row.getPipeline()),
                        docRefs.get(row.getDoc().getUuid()),
                        row.getOldestWaitMs() != null
                        && now - row.getOldestWaitMs() > maxDeferralAge(docs, row.getDoc().getUuid()),
                        belowThreshold(docs.get(row.getDoc().getUuid()), row.getRollingScore()),
                        noFallback.contains(row.getDoc().getUuid()),
                        spendPaused.get(row.getDoc().getUuid())))
                .toList();
    }

    /// Gets the document a learner runs under, so that its pipeline's editor can show the flow, pass mark
    /// and limits. Requires VIEW on the pipeline rather than on the document: the settings describe how
    /// that pipeline's feed is learned, which anyone who may see the pipeline may read, even if they cannot
    /// open the document itself.
    public LearningFlowDoc settings(final String docUuid, final String pipelineUuid, final String elementId) {
        readable(learnerOf(docUuid, new LearnerRef(pipelineUuid, elementId)), docUuid);
        return document(docUuid);
    }

    /// Lists the streams waiting on one learner, oldest first, a page at a time.
    public ResultPage<BacklogStream> waiting(final String docUuid,
                                             final String pipelineUuid,
                                             final String elementId,
                                             final PageRequest pageRequest) {
        final Learner learner = readable(learnerOf(docUuid, new LearnerRef(pipelineUuid, elementId)), docUuid);
        final PageRequest page = pageOf(pageRequest);
        final long offset = offsetOf(page);
        final Deferrals.Waiting waiting = deferralsProvider.get().waitingOn(learner, offset, pageLength(page));
        final DocRef doc = new DocRef(LearningFlowDoc.TYPE, docUuid);
        final long maxAge = maxDeferralAge(documents(List.of(doc)), docUuid);
        final long now = System.currentTimeMillis();
        final List<BacklogStream> rows = waiting.rows().stream()
                .map(row -> new BacklogStream(doc, row.inputId(), row.feed(), row.type(), row.failedAgainst(),
                        row.reason(), row.failingRecords(), row.firstDeferredMs(), row.attempts(),
                        row.raised(maxAge, now)))
                .toList();
        return new ResultPage<>(rows, new PageResponse(offset, rows.size(), waiting.total(), true));
    }

    /// Sends a learner back to learn afresh from its next stream. Requires EDIT on its pipeline.
    public Boolean relearnLearner(final String docUuid, final LearnerRequest request) {
        final Learner learner = changeable(docUuid, request);
        final String who = securityContextProvider.get().getUserIdentityForAudit();
        final String reason = NullSafe.isBlankString(request.getReason())
                ? "Sent back to be learned again by " + who
                : "Sent back to be learned again by " + who + ": " + request.getReason();
        final LearningFlowDoc doc = document(docUuid);
        stageFactoryProvider.get().supervisorActions().relearn(doc, learner, reason);
        return Boolean.TRUE;
    }

    /// Gives a learner up. A reason is required, because the learner's streams error with it.
    public Boolean giveUp(final String docUuid, final LearnerRequest request) {
        if (request == null || NullSafe.isBlankString(request.getReason())) {
            throw new IllegalArgumentException("Giving a learner up says why: its streams error with the "
                                               + "reason");
        }
        final Learner learner = changeable(docUuid, request);
        final String by = securityContextProvider.get().getUserIdentityForAudit();
        final LearningFlowDoc doc = document(docUuid);
        stageFactoryProvider.get().supervisorActions().giveUp(doc, learner, request.getReason(), by);
        return Boolean.TRUE;
    }

    /// Gets the learner a request names, checking that its pipeline has not been deleted and that the user
    /// may edit it.
    private Learner changeable(final String docUuid, final LearnerRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Say which learner: its pipeline and element");
        }
        return changeable(live(learnerOf(docUuid, request.getLearner())));
    }

    /// Reads each document once. A document that cannot be read is left out, and its rows use the default
    /// settings.
    private Map<String, LearningFlowDoc> documents(final Collection<DocRef> docRefs) {
        final Map<String, LearningFlowDoc> docs = new LinkedHashMap<>();
        for (final DocRef docRef : docRefs) {
            try {
                final LearningFlowDoc doc = storeProvider.get().readDocument(docRef);
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

    /// The UUIDs of documents whose target schema isn't event-logging, which therefore have no marked
    /// Unknown event for *Finish with Unknowns* to write. A document naming no target, or one whose target
    /// this node can't read, is treated as event-logging.
    private Set<String> noFallback(final Collection<LearningFlowDoc> docs) {
        final TargetSchemas targetSchemas = targetSchemasProvider.get();
        return docs.stream()
                .filter(doc -> targetSchemas.of(doc)
                        .map(target -> !target.isEventLogging())
                        .orElse(false))
                .map(LearningFlowDoc::getUuid)
                .collect(Collectors.toSet());
    }

    /// Adds to a learner's row the time zone its pipeline reads timestamps in when a stream has no
    /// `TimeZone` header, where that zone was set, and any problem with the pipeline's property, so that a
    /// surprising date can be traced to what set the zone.
    private SupervisorLearner withTimeZone(final SupervisorLearner row) {
        final ResolvedZone zone = pipelineZonesProvider.get().of(learner(row.getLearner()), null);
        return row.withTimeZone(zone.id(), zone.source(), zone.problem());
    }

    /// For each document that cannot start a learning session now because a spend ceiling (its own or the
    /// cluster's) has been reached, the reason why. It asks the same breaker the stages use, so the list
    /// shows exactly what is holding the streams back.
    private Map<String, String> spendPaused(final Collection<LearningFlowDoc> docs) {
        final SpendBreaker breaker = stageFactoryProvider.get().breaker();
        final Map<String, String> paused = new HashMap<>();
        for (final LearningFlowDoc doc : docs) {
            breaker.tripped(doc).ifPresent(reason -> paused.put(doc.getUuid(), reason));
        }
        return paused;
    }

    /// Whether a learner's rolling score is below its document's relearn threshold. False if the document
    /// could not be read or the learner has not yet served enough records to have a rolling score.
    private static boolean belowThreshold(final LearningFlowDoc doc, final Double rollingScore) {
        return doc != null
               && rollingScore != null
               && rollingScore < doc.getRelearnThreshold();
    }

    private static long maxDeferralAge(final Map<String, LearningFlowDoc> docs, final String docUuid) {
        final LearningFlowDoc doc = docs.get(docUuid);
        return doc == null
                ? LearningFlowDoc.builder().build().getMaxDeferralAgeMs()
                : doc.getMaxDeferralAgeMs();
    }

    /// Grants a parked session more budget and reopens it for the worker. Requires EDIT on its pipeline.
    public SupervisorAttempt grant(final long id, final GrantRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Nothing was granted");
        }
        final Recorded attempt = live(change(id));
        stageFactoryProvider.get().supervisorActions().grant(documentOf(attempt), id, request.getTokens(),
                request.getMs());
        return detail(read(id));
    }

    /// Sends a parked session to the exhaustion step and reopens it for the worker. Requires EDIT on its
    /// pipeline.
    public SupervisorAttempt exhaust(final long id) {
        final Recorded attempt = live(change(id));
        stageFactoryProvider.get().supervisorActions().exhaust(documentOf(attempt), id);
        return detail(read(id));
    }

    /// Finds the learning sessions the user may read, newest first, filtered by the criteria.
    ///
    /// @param criteria The filters, and which page of results to return.
    /// @return The page of sessions, without their turns.
    public ResultPage<SupervisorAttempt> find(final AttemptCriteria criteria) {
        final AttemptCriteria asked = criteria == null
                ? new AttemptCriteria()
                : criteria;
        // The documents the user may see are passed into the query rather than used to filter its result.
        // Filtering afterwards would make the total count reveal how many sessions exist on documents they
        // may not see, and pages would come back short with no way to reach the end. One learner's
        // sessions, as its pipeline's editor asks for them, are checked against the pipeline instead, and
        // may be under any document.
        final Page page = attemptsProvider.get().found(asked, asked.getLearner() == null
                ? List.copyOf(readable(asked.getDocUuid()).keySet())
                : learnersDocuments(asked.getLearner()));
        // Which of the page's pipelines are deleted, looked up once for the page rather than once per row.
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

    /// Asks for a binding that is already serving to be improved.
    ///
    /// Requires EDIT on the binding's pipeline, as approving a draft does, because it spends a model's
    /// tokens and may rebind the learner.
    ///
    /// The request opens a session and leaves it waiting at its first question; the worker then asks the
    /// model. A model conversation can last minutes, and holding an HTTP thread for it would not survive the
    /// node restarting or the browser giving up. Nothing waits on it: the serving binding goes on serving
    /// every stream, and a candidate takes over only by passing the ordinary promotion gate.
    ///
    /// The result therefore says only that the improvement was requested, not how it turned out. The
    /// outcome appears on the Learning Status tab, as every other session's does.
    public ImproveOutcome improve(final String docUuid, final String bindingUuid, final ImproveRequest request) {
        changeableBinding(docUuid, bindingUuid);
        final String by = securityContextProvider.get().getUserIdentityForAudit();
        final String message = request == null
                ? null
                : request.getMessage();
        final LearningFlowDoc doc = document(docUuid);
        final Decision decision = pipelineScopeProvider.get().scopeResult(() ->
                stageFactoryProvider.get().create().improve(doc, bindingUuid, message, by).decision());
        // If the session could not be opened, the reason is returned to the person now rather than left to
        // show up as an abandoned session. (Other problems, such as the binding being a draft, having no
        // accepted records, or learning being disabled, are thrown as exceptions by the stage.)
        if (decision instanceof final Sentinel refused) {
            return new ImproveOutcome(refused.reason());
        }
        return new ImproveOutcome("Asked. The AI is working on it; what it comes to will show here.");
    }

    /// Retracts a binding that is serving.
    ///
    /// A reason is required, because it is recorded against the session that wrote the binding.
    ///
    /// Returns how many streams the binding produced output for. That is the one consequence the person
    /// cannot see for themselves, and tells them how many streams they may want to reprocess.
    ///
    /// Requires EDIT on the binding's pipeline, as approving a draft does, because it changes what serves the
    /// pipeline.
    public Integer retract(final String docUuid, final String bindingUuid, final RejectRequest request) {
        if (request == null || NullSafe.isBlankString(request.getReason())) {
            throw new IllegalArgumentException("A retraction says why: the reason is recorded against "
                                               + "the attempt that wrote the binding");
        }
        changeableBinding(docUuid, bindingUuid);
        final String by = securityContextProvider.get().getUserIdentityForAudit();
        final LearningFlowDoc doc = document(docUuid);
        return stageFactoryProvider.get().promotion().retract(doc, bindingUuid, request.getReason(), by);
    }

    /// Lists the guidance standing for a learner, oldest first. Requires VIEW on its pipeline.
    public List<SupervisorGuidance> guidance(final String docUuid,
                                             final String pipelineUuid,
                                             final String elementId) {
        return said(readable(learnerOf(docUuid, new LearnerRef(pipelineUuid, elementId)), docUuid));
    }

    /// Leaves guidance for a learner, without asking for anything to be run.
    ///
    /// Requires EDIT on the pipeline rather than VIEW, because the guidance is included in every question
    /// the learner asks from now on, which changes how its feed is learned.
    public List<SupervisorGuidance> hint(final String docUuid, final GuidanceRequest request) {
        if (request == null || NullSafe.isBlankString(request.getMessage())) {
            throw new IllegalArgumentException("There is nothing to record: the message is empty");
        }
        final Learner learner = changeable(live(learnerOf(docUuid, request.getLearner())));
        guidanceProvider.get().given(learner, docUuid, request.getMessage(),
                securityContextProvider.get().getUserIdentityForAudit());
        return said(learner);
    }

    /// Withdraws guidance left for a learner. Guidance that turns out to be wrong is worse than none,
    /// because it is included in every question the learner asks until it is withdrawn.
    ///
    /// Turns that already included it still record that they did, and a replayed session replays them as
    /// they were. Withdrawn guidance is only left out of questions asked from now on.
    public List<SupervisorGuidance> withdraw(final String docUuid,
                                             final long id,
                                             final String pipelineUuid,
                                             final String elementId) {
        final Learner learner = changeable(live(learnerOf(docUuid,
                new LearnerRef(pipelineUuid, elementId))));
        guidanceProvider.get().withdraw(docUuid, id);
        return said(learner);
    }

    /// Gets one learning session with its turns. Requires VIEW on its pipeline.
    ///
    /// @param id The session's id.
    /// @return The session and its turns.
    public SupervisorAttempt fetch(final long id) {
        return detail(read(id));
    }

    /// Records a person's answer in place of the model's for one turn, and leaves the session for the
    /// learning job to resume. Requires EDIT on its pipeline, and the pipeline must still run under the
    /// session's document.
    ///
    /// @param id      The session's id.
    /// @param number  The number of the turn being answered.
    /// @param request The person's answer.
    /// @return The session as it now stands.
    public SupervisorAttempt amend(final long id, final int number, final AmendTurnRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("No answer was given for turn " + number);
        }
        final Recorded attempt = stillUnder(live(change(id)));
        final String answeredBy = securityContextProvider.get().getUserIdentityForAudit();
        stageFactoryProvider.get().supervisorActions().amend(documentOf(attempt), id, number, request.getAnswer(),
                answeredBy);
        return detail(read(id));
    }

    /// Rebuilds the prompt that was sent to the model for one turn, for a person to inspect.
    ///
    /// Prompts are rebuilt rather than stored, because a stored prompt would be a copy of the stream's text
    /// that could be read without the view-data permission and would outlive the stream under retention.
    /// If the prompt cannot be rebuilt exactly, the reason is returned rather than an approximation.
    public RebuiltPrompt prompt(final long id, final int number) {
        final Recorded attempt = read(id);
        if (attempt.turns().stream().noneMatch(turn -> turn.number() == number)) {
            throw new IllegalArgumentException("Attempt " + id + " has no turn " + number);
        }
        if (!securityContextProvider.get().hasAppPermission(AppPermission.VIEW_DATA_PERMISSION)) {
            return RebuiltPrompt.refused("The prompt carries the stream's own text, so it is shown only to "
                                         + "someone who may view data.");
        }
        if (attempt.attempt().isImprovement() || attempt.attempt().inputId() == null) {
            return RebuiltPrompt.refused("This run improved a translation from the records it was accepted "
                                         + "on rather than from a stream, so its prompt can't be rebuilt.");
        }
        final Optional<Input> input = inputsProvider.get().byId(attempt.attempt().inputId());
        if (input.isEmpty()) {
            return RebuiltPrompt.refused("The stream this run learned from has been deleted, or can't be "
                                         + "read, so its prompt can't be rebuilt.");
        }
        final LearningFlowDoc doc = documentOf(attempt);
        // Run in a pipeline scope even though there is no pipeline here, because rebuilding runs pipelines
        // and the stage's components report errors through the scoped error receiver, as they do for the
        // deferred worker.
        final Optional<Rebuilt> rebuilt = pipelineScopeProvider.get().scopeResult(() ->
                stageFactoryProvider.get().create().rebuild(doc, id, input.get()));
        if (rebuilt.isEmpty()) {
            return RebuiltPrompt.refused("This run was asked before the words it used were kept, so its "
                                         + "prompt can't be rebuilt without guessing.");
        }
        final Rebuilt prompts = rebuilt.get();
        final String prompt = prompts.prompts().get(number);
        if (prompt == null) {
            return RebuiltPrompt.refused("This question was asked in a later round of the run, after the "
                                         + "whole stream was checked, or after the Learning Flow's steps "
                                         + "changed; only the first round can be rebuilt.");
        }
        final List<String> notes = new ArrayList<>();
        notes.add("The questions and answers before it went with it, as the turns above show them.");
        final Integer withdrawn = prompts.withdrawn().get(number);
        if (withdrawn != null) {
            notes.add(withdrawn + " note(s) it carried have since been withdrawn, and aren't shown.");
        }
        if (prompts.olderThanBuiltIn()) {
            notes.add("Stroom has been upgraded since this run. The feedback is derived again, so the checks' "
                      + "wording in it may differ from what was sent.");
        }
        return new RebuiltPrompt(prompt, notes, null);
    }

    /// Approves the draft a session wrote, recording who approved it. Requires EDIT on its pipeline.
    ///
    /// @param id The session's id.
    /// @return The session as it now stands.
    public SupervisorAttempt approve(final long id) {
        final Recorded attempt = live(change(id));
        final String by = securityContextProvider.get().getUserIdentityForAudit();
        stageFactoryProvider.get().promotion().approve(documentOf(attempt), bindingOf(attempt), by);
        return detail(read(id));
    }

    /// Rejects the draft a session wrote, recording who rejected it and why. Rejecting a draft also gives
    /// the learner up with the reason. Requires EDIT on its pipeline.
    ///
    /// @param id      The session's id.
    /// @param request The reason for the rejection, which is required.
    /// @return The session as it now stands.
    public SupervisorAttempt reject(final long id, final RejectRequest request) {
        if (request == null || request.getReason() == null || request.getReason().isBlank()) {
            throw new IllegalArgumentException("A rejection says why: the learner gives up with the reason");
        }
        final Recorded attempt = live(change(id));
        final String by = securityContextProvider.get().getUserIdentityForAudit();
        stageFactoryProvider.get().promotion().reject(documentOf(attempt), bindingOf(attempt), request.getReason(),
                by);
        return detail(read(id));
    }

    private List<SupervisorGuidance> said(final Learner learner) {
        return guidanceProvider.get().standing(learner).stream()
                .map(given -> new SupervisorGuidance(given.id(), given.message(), given.author(),
                        given.timeMs()))
                .toList();
    }

    /// The number of rows a page may hold: what was asked for, clamped to between 1 and [#PAGE_LIMIT].
    /// Both ends are clamped because an unbounded length would return the whole table in one call, and a
    /// negative one would reach the database as `LIMIT -1` and cause a server error.
    private static int pageLength(final PageRequest page) {
        final int asked = NullSafe.getOrElse(page.getLength(), Integer::intValue, PAGE_LENGTH);
        return Math.max(1, Math.min(asked, PAGE_LIMIT));
    }

    /// The page asked for, or the first page if none was.
    private static PageRequest pageOf(final PageRequest asked) {
        return asked == null
                ? new PageRequest(0, PAGE_LENGTH)
                : asked;
    }

    /// The offset a page starts at, which is never negative.
    private static long offsetOf(final PageRequest page) {
        return Math.max(0L, NullSafe.getOrElse(page.getOffset(), Integer::longValue, 0L));
    }

    /// The documents the user may view, keyed by UUID, narrowed to the one asked about if there is one.
    /// An installation has only a handful of documents, so checking each one costs little compared with the
    /// query it saves.
    private Map<String, DocRef> readable(final String asked) {
        final Map<String, DocRef> readable = new LinkedHashMap<>();
        storeProvider.get().listDocuments().stream()
                .filter(docRef -> asked == null || asked.equals(docRef.getUuid()))
                .filter(docRef -> access.mayDocument(docRef.getUuid(), DocumentPermission.VIEW))
                .forEach(docRef -> readable.put(docRef.getUuid(), docRef));
        return readable;
    }

    /// The learner a request names, by its pipeline UUID and element id.
    private static Learner learner(final LearnerRef ref) {
        if (ref == null || NullSafe.isBlankString(ref.getPipelineUuid())
            || NullSafe.isBlankString(ref.getElementId())) {
            throw new IllegalArgumentException("Say which learner: its pipeline and element");
        }
        return new Learner(ref.getPipelineUuid(), ref.getElementId());
    }

    /// The learner a request names, checked to be one that runs under the document the request names. The
    /// request names the learner and the document separately, so without this check a user permitted on
    /// one document could act on another document's learner by naming it.
    private Learner learnerOf(final String docUuid, final LearnerRef ref) {
        final Learner learner = learner(ref);
        if (!runsUnder(learner, docUuid)) {
            throw new IllegalArgumentException("Element " + learner.elementId() + " of pipeline "
                                               + learner.pipelineUuid() + " does not run under this document");
        }
        return learner;
    }

    /// Checks that a session's pipeline has not been deleted. A deleted pipeline's learning is kept, read
    /// only, until it ages out, because anything a person changed would serve or teach a pipeline that will
    /// never run again.
    private Recorded live(final Recorded attempt) {
        live(attempt.attempt().learner());
        return attempt;
    }

    /// Checks that the learner's pipeline has not been deleted.
    private Learner live(final Learner learner) {
        if (deletedPipelinesProvider.get().isDeleted(learner.pipelineUuid())) {
            throw new IllegalStateException("The pipeline of element " + learner.elementId() + " has been "
                                            + "deleted: what it learned is kept to be read until it ages "
                                            + "out, and nothing more is done with it");
        }
        return learner;
    }

    /// Checks that the user may change a binding: its learner runs under the document named, its pipeline
    /// has not been deleted, and the user may edit the pipeline. A binding that doesn't exist gets the same
    /// permission error, so that the response doesn't reveal which bindings exist.
    private void changeableBinding(final String docUuid, final String bindingUuid) {
        final Learner learner = learnerBindingsProvider.get().byUuid(bindingUuid)
                .map(held -> held.learner())
                .filter(held -> runsUnder(held, docUuid))
                .orElseThrow(() -> new PermissionException(securityContextProvider.get().getUserRef(),
                        "You do not have permission to change binding " + bindingUuid));
        changeable(live(learner));
    }

    /// Checks that a session's learner still runs under the document the session was learning for. A
    /// session keeps its document as a historical record, but changing a session changes its learner, and
    /// once the pipeline has been pointed at another document the old document no longer governs it.
    private Recorded stillUnder(final Recorded attempt) {
        if (!runsUnder(attempt.attempt().learner(), attempt.attempt().docUuid())) {
            throw new IllegalStateException("Attempt " + attempt.id() + " was learning for a pipeline that no "
                                            + "longer runs under its document; act on it from the document "
                                            + "the pipeline names now");
        }
        return attempt;
    }

    /// Whether the learner runs under the document. For a live pipeline this is the document its element
    /// names now. For a deleted pipeline, which names nothing, it is the document the learning pipelines
    /// list has for it, so that what it learned can still be viewed under that document, whether or not it
    /// ever bound anything. A deleted pipeline's learner cannot be changed, so this only allows viewing.
    private boolean runsUnder(final Learner learner, final String docUuid) {
        return access.documentOf(learner).filter(docUuid::equals).isPresent();
    }

    private static LearnerRef ref(final Learner learner) {
        return new LearnerRef(learner.pipelineUuid(), learner.elementId());
    }

    private LearningFlowDoc document(final String docUuid) {
        return stored(docUuid)
                .orElseThrow(() -> new IllegalArgumentException("There is no Learning Flow " + docUuid));
    }

    private Optional<LearningFlowDoc> stored(final String docUuid) {
        return Optional.ofNullable(storeProvider.get().readDocument(new DocRef(LearningFlowDoc.TYPE, docUuid)));
    }

    /// Gets a session the user may view. A session they may not see and one that does not exist give the
    /// same error, so that the response doesn't reveal which sessions exist.
    private Recorded read(final long id) {
        return permitted(id, DocumentPermission.VIEW);
    }

    /// Gets a session the user may change. Approving a draft puts its binding in force, rejecting one gives
    /// the learner up, and resuming a session spends a model's tokens. All of these change what serves the
    /// session's pipeline, so they require the same permission as editing the pipeline.
    private Recorded change(final long id) {
        return permitted(id, DocumentPermission.EDIT);
    }

    private Recorded permitted(final long id, final DocumentPermission permission) {
        final Recorded attempt = attemptsProvider.get().byId(id).orElse(null);
        if (attempt == null
            || !access.mayLearner(attempt.attempt().learner(), attempt.attempt().docUuid(), permission)) {
            throw new PermissionException(securityContextProvider.get().getUserRef(),
                    "You do not have permission to read attempt " + id);
        }
        return attempt;
    }

    /// Checks the user may view the learner's pipeline (or, for a deleted pipeline, its document).
    private Learner readable(final Learner learner, final String docUuid) {
        if (!access.mayLearner(learner, docUuid, DocumentPermission.VIEW)) {
            throw new PermissionException(securityContextProvider.get().getUserRef(),
                    "You do not have permission to read the pipeline of element " + learner.elementId());
        }
        return learner;
    }

    /// Checks the user may edit the learner's pipeline. Only called once the pipeline is known to be live.
    private Learner changeable(final Learner learner) {
        if (!access.mayPipeline(learner.pipelineUuid(), DocumentPermission.EDIT)) {
            throw new PermissionException(securityContextProvider.get().getUserRef(),
                    "You do not have permission to change how the pipeline of element " + learner.elementId()
                    + " learns");
        }
        return learner;
    }

    /// The documents a learner's sessions may be under: every document, since its pipeline may have run
    /// under more than one, if the user may view the learner; none if they may not.
    private List<String> learnersDocuments(final LearnerRef ref) {
        final Learner learner = learner(ref);
        return storeProvider.get().listDocuments().stream()
                .map(DocRef::getUuid)
                .filter(uuid -> access.mayLearner(learner, uuid, DocumentPermission.VIEW))
                .toList();
    }

    private String bindingOf(final Recorded attempt) {
        if (attempt.bindingUuid() == null) {
            throw new IllegalStateException("Attempt " + attempt.id() + " drafted no binding to decide about");
        }
        return attempt.bindingUuid();
    }

    /// The Learning Flow a session ran under, which must still exist for anything to be done to it.
    private LearningFlowDoc documentOf(final Recorded attempt) {
        return stored(attempt.attempt().docUuid())
                .orElseThrow(() -> new IllegalStateException("The Learning Flow of attempt " + attempt.id()
                                                             + " has been deleted"));
    }

    private SupervisorAttempt detail(final Recorded attempt) {
        return attempt(attempt, attempt.turns().stream()
                        .map(SupervisorService::turn)
                        .toList(),
                deletedPipelinesProvider.get().isDeleted(attempt.attempt().learner().pipelineUuid()));
    }

    private static SupervisorAttempt attempt(final Recorded attempt,
                                             final List<SupervisorTurn> turns,
                                             final boolean pipelineDeleted) {
        return new SupervisorAttempt(
                attempt.id(),
                new DocRef(LearningFlowDoc.TYPE, attempt.attempt().docUuid()),
                ref(attempt.attempt().learner()),
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
                attempt.report(),
                attempt.review().evidence(),
                attempt.review().reviewedBy(),
                attempt.review().reviewedTimeMs());
    }

    private static SupervisorTurn turn(final Turn turn) {
        return new SupervisorTurn(turn.number(), turn.stepId(), turn.candidate(), turn.kind(),
                turn.question(), turn.answer(), turn.answeredBy(), turn.outcome(), turn.score());
    }
}
